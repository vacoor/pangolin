package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.codec.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.cc.NewRenoCongestionControl;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.cc.RateSample;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.cc.TcpCongestionControl;
import io.netty.buffer.ByteBuf;
import lombok.extern.slf4j.Slf4j;

/**
 * 发送侧关注点的聚合对象,对齐 gVisor netstack 的 {@code sender}
 * (pkg/tcpip/transport/tcp/snd.go)。每条 {@link TcpSock} 对应一个 {@code Sender},
 * 由 {@link SegmentDispatcher#configure(TcpSock)} 创建并挂入 {@link TcpSock#sender()}。
 *
 * <p><b>职责</b>:
 * <ul>
 *   <li>发送侧状态字段(R2.3 物理下沉):sndUna / sndNxt / writeSeq / cwnd / ssthresh /
 *       srttUs / rttvarUs / packetsOut / congestionState / RACK / F-RTO 等 38 字段</li>
 *   <li>应用层写入(R4.2b-4e):{@link #sendmsg} / {@link #pushPending} / {@link #shutdown}</li>
 *   <li>ACK 处理(R4.2b-4g):{@link #ackIncoming}(内部走 TcpAck)</li>
 *   <li>发送侧 timer(R4.2b-4d):{@link #armProbe0} / {@link #probeTimer} /
 *       {@link #armKeepalive} / {@link #keepaliveTimer}</li>
 *   <li>初始化(R4.2b-i):{@link #initWl}</li>
 *   <li>Facade API(R2):sendFin / sendReset / sendAck / retransmit / backoff /
 *       rearmRto / rtoMs / currentMss 等(delegate 到 TcpOutput / TcpRetransmitter)</li>
 * </ul>
 *
 * <p>不含接收侧(OFO / reassembly / rcvWnd),后者由 {@link Receiver}。
 *
 * <p><b>线程模型</b>:所有方法必须在 {@code sock.eventLoop()} 上调用。
 *
 * <p><b>使用示例</b>:
 * <pre>
 *   sock.sender().sendmsg(buf, true);       // 应用层写入(MSS 切片内部处理)
 *   sock.sender().sendFin();                // 半关
 *   sock.sender().retransmit();             // 重传 RTX 队首段
 *   sock.sender().backoff();                // RTO 指数退避
 *   long rto = sock.sender().rtoMs();       // 读当前 RTO
 * </pre>
 */
@Slf4j
public final class Sender {

    private final TcpSock sock;

    /** SND.UNA — 最早未 ACK 字节的 seq(R2.3)。Mirrors Linux {@code tp->snd_una}。 */
    private int sndUna;
    /** SND.NXT — 下一个待发字节的 seq(R2.3)。Mirrors Linux {@code tp->snd_nxt}。 */
    private int sndNxt;
    /** tail 分配时的 seq 游标,≥ sndNxt(R2.3)。Mirrors Linux {@code tp->write_seq}。 */
    private int writeSeq;
    /** 对端通告的发送窗口(字节,已 scale)。Mirrors Linux {@code tp->snd_wnd}。 */
    private int sndWnd;
    /** 历史最大 sndWnd(PAWS 校验基线)。Mirrors Linux {@code tp->max_window}。 */
    private int maxWindow;
    /** 上次 window 更新时的 ACK seq。Mirrors Linux {@code tp->snd_wl1}。 */
    private int sndWl1;
    /** 上次 Nagle/Minshall 检查的 sent seq。Mirrors Linux {@code tp->snd_sml}。 */
    private int sndSml;
    /** 飞行中段数(出队但未 ACK)。Mirrors Linux {@code tp->packets_out}。 */
    private int packetsOut;
    /** 已被 SACK 标记的段数。Mirrors Linux {@code tp->sacked_out}。 */
    private int sackedOut;
    /** 已被 LOST 标记的段数(RACK / NewReno tag)。Mirrors Linux {@code tp->lost_out}。 */
    private int lostOut;
    /**
     * 飞行中"重传段"的计数 — Mirrors Linux {@code tp->retrans_out}。
     *
     * <p>段在 {@link TcpOutput#retransmitSkb} 首次打 {@code TCPCB_SACKED_RETRANS}
     * 时 ++;在 {@code tcp_clean_rtx_queue} 累计 ACK 释放该段时 --;
     * 进入 {@code tcp_enter_loss} 时整体清零(那一刻所有 in-flight 段都被
     * 重新视作未重传,后续 RTO 重传重新计数)。
     *
     * <p>用于 {@link #packetsInFlight} 的完整公式:
     * {@code packets_out - sacked_out - lost_out + retrans_out}。
     */
    private int retransOut;
    /** cwnd 使用高水位时戳。Mirrors Linux {@code tp->snd_cwnd_stamp}。 */
    private long sndCwndStampMs;
    /** cwnd 使用高水位。Mirrors Linux {@code tp->snd_cwnd_used}。 */
    private int sndCwndUsed;
    /** cwnd 是否成为发送瓶颈。Mirrors Linux {@code tp->is_cwnd_limited}。 */
    private boolean isCwndLimited;
    /** undo 前 cwnd 快照。Mirrors Linux {@code tp->prior_cwnd}。 */
    private int priorCwnd;
    /** undo 前 ssthresh 快照。Mirrors Linux {@code tp->prior_ssthresh}。 */
    private int priorSsthresh;
    /** 拥塞窗口(段数)。Mirrors Linux {@code tp->snd_cwnd}。 */
    private int cwnd = TcpConstants.TCP_INIT_CWND;
    /** 慢启动阈值(段数);默认 {@code Integer.MAX_VALUE} 表示仍在 slow start。Mirrors Linux {@code tp->snd_ssthresh}。 */
    private int ssthresh = Integer.MAX_VALUE;
    /**
     * 拥塞控制算法 SPI(BBR-ready 统一接口) — 默认 NewReno。Phase 1a 仅承载
     * {@code ssthresh} / {@code undoCwnd} 公式,cwnd 增长仍在 {@code Sender}
     * 内联;Phase 6/7 引入 CUBIC / BBR 时彻底迁到 SPI。
     */
    private TcpCongestionControl congestionControl = NewRenoCongestionControl.INSTANCE;
    /**
     * RateSample scratch — 单连接复用对象,免每次 ACK GC 压力。Phase 1a-2 只填
     * {@code ackedPackets / ackedBytes / appLimited} 走通 NewReno;Phase 1b 后续
     * 聚合 BBR 专用字段(delivered / sendElapsedUs / ...)。
     */
    private final RateSample rateSampleScratch = new RateSample();
    /** 平滑 RTT(us)。Mirrors Linux {@code tp->srtt_us}。 */
    private long srttUs;
    /** RTT 方差(us)。Mirrors Linux {@code tp->rttvar_us}。 */
    private long rttvarUs;
    /**
     * writeXmit 再入深度 —— 专给 {@code EmbeddedChannel} 测试路径用的守卫。
     *
     * <p>背景:生产路径(NIO EventLoop)里 {@code writeAndFlush} 不会 inline 驱动
     * scheduled tasks,定时器只在 select loop 下一轮才 fire,无 re-entry 问题。
     * 但 {@code EmbeddedChannel.writeAndFlush} 内部的 {@code maybeRunPendingTasks}
     * 会同步驱动调度器,让 TLP 等定时器在 writeAndFlush 调用栈里 inline fire。
     * 若此时 fire 到的 callback 又回头调 {@code writeXmit},就会在外层尚未完成
     * {@code eventNewDataSent}(pop write queue / enqueueRtx / pktsOut++)时看到
     * 陈旧的 {@code tcpSendHead},导致同一段被双入队。加这个计数器让 re-entrant
     * callback 自行跳过;外层 writeXmit 尾部的 {@code scheduleLossProbe} 会重新
     * 评估是否再武装 TLP。
     *
     * <p>生产 NIO 下,{@link #isInXmit()} 恒为 false(调度器根本不会在 writeXmit
     * 栈帧里 fire),guard 是零开销 no-op。
     */
    private int xmitDepth;
    /** dupack 计数器。Mirrors Linux {@code tp->dup_ack}(经由 {@code icsk_ca_state} 触发)。 */
    private int dupacks;
    /** 拥塞控制阶段。Mirrors Linux {@code icsk->icsk_ca_state}。 */
    private TcpSock.CongestionState congestionState = TcpSock.CongestionState.OPEN;
    /** Recovery 入口的 sndNxt 快照。Mirrors Linux {@code tp->high_seq}。 */
    private int highSeq;
    /** Congestion Avoidance 增量累计器。Mirrors Linux {@code tp->snd_cwnd_cnt}。 */
    private int caIncrCounter;
    /** 本 epoch 未被 ACK 覆盖的重传段计数。Mirrors Linux {@code tp->undo_retrans}。 */
    private int undoRetrans;
    /** undo 快照 sndUna。Mirrors Linux {@code tp->undo_marker}。 */
    private int undoMarker;
    /** F-RTO 武装时的 sndNxt 快照。Mirrors Linux {@code tp->frto_highmark}。 */
    private int frtoHighmark;
    /** F-RTO 状态机计数器。Mirrors Linux {@code tp->frto_counter}。 */
    private int frtoCounter;
    /** 累计已确认字节数。Mirrors Linux {@code tp->bytes_acked}。 */
    private long bytesAcked;
    /** RACK 最近 SACKed 段 sentTime。Mirrors Linux {@code tp->rack.mstamp}。 */
    private long rackMstamp;
    /** RACK 当前窗内 RTT。Mirrors Linux {@code tp->rack.rtt_us}。 */
    private long rackRttUs;
    /** RACK reo_wnd 放宽步数;初值 1(Linux 默认)。Mirrors Linux {@code tp->rack.reo_wnd_steps}。 */
    private int rackReoWndSteps = 1;
    /** RACK reo_wnd 持续 epoch 数。Mirrors Linux {@code tp->rack.reo_wnd_persist}。 */
    private int rackReoWndPersist;
    /** RACK DSACK 是否观察过。Mirrors Linux {@code tp->rack.dsack_seen}。 */
    private boolean rackDsackSeen;
    /** 已交付段数累计。Mirrors Linux {@code tp->delivered}。 */
    private int delivered;
    /** 上次 RACK step 更新时的 delivered 快照。Mirrors Linux {@code tp->rack.last_delivered}。 */
    private int rackLastDelivered;
    /** 每 ACK scratchpad:本 ACK 内已投递段 tx.delivered 最大值。Mirrors Linux {@code rs->prior_delivered}。 */
    private int rackAckPriorDelivered;
    /** 上次发送时戳(毫秒 jiffies)。 */
    private long lastSendTimeMs;

    // ── BBR-ready 基础设施(Phase 1b 加入,NewReno/CUBIC 不读) ─────────────────
    /**
     * {@code tp->delivered_mstamp}(微秒)— 最近一次 {@code delivered} 推进的时戳。
     * {@code __tcp_transmit_skb} 复制到 {@code TcpSegment.priorMstamp};BBR 用
     * {@code rate_sample.ack_elapsed = now - prior_mstamp} 计算 ack-side 区间。
     */
    private long deliveredMstampUs;
    /**
     * 发送时刻 sender 是否 application-limited(写队列空 / cwnd 未用满)— 对齐 Linux
     * {@code tp->app_limited}。BBR 用此过滤 sample,不让 idle 期间错误压低 BDP 估计。
     * v2 Phase 1b 仅维护字段,Phase 7 BBR 落地时由 sender 主路径填。
     */
    private boolean appLimited;
    /**
     * Pacing rate(字节/秒)— 对齐 Linux {@code sk->sk_pacing_rate}。{@code 0} 表示
     * 不限速(NewReno / CUBIC 默认),sender 主路径短路跳过 pacing token bucket。
     * BBR 在 {@code onAck} 内根据 BtlBw × pacing_gain 写入。
     */
    private long pacingRateBps;
    /** 单 Sender pacing 状态(token + lastNs)。Phase 1c 引入,默认空闲不消费。 */
    private final PacingTokenBucket pacingBucket = new PacingTokenBucket();

    // ── R7.1 probe / keepalive / linger2(从 TcpSock 迁入) ──────────────────

    /** FIN_WAIT_2 超时(ms)。Mirrors Linux {@code tp->linger2}。 */
    private int linger2 = (int) TcpConstants.FIN_WAIT_2_TIMEOUT_MS;
    /** 零窗探测指数退避 shift。Mirrors Linux {@code icsk->icsk_probes_tstamp/icsk_backoff}。 */
    private int probeBackoffShift;
    /** 已发探测包计数。Mirrors Linux {@code icsk->icsk_probes_out}。 */
    private int probesOut;
    /** 进入探测阶段的时戳(jiffies)。Mirrors Linux {@code icsk->icsk_probes_tstamp}。 */
    private long probesTstampMs;
    /** TCP_USER_TIMEOUT(ms,0 表示未设置)。Mirrors Linux {@code icsk->icsk_user_timeout}。 */
    private long userTimeoutMs;
    /** keepalive 空闲阈值。Mirrors Linux {@code tp->keepalive_time}。 */
    private long keepaliveTimeMs = TcpConstants.TCP_KEEPALIVE_TIME_MS;
    /** keepalive 探测间隔。Mirrors Linux {@code tp->keepalive_intvl}。 */
    private long keepaliveIntvlMs = TcpConstants.TCP_KEEPALIVE_INTVL_MS;
    /** keepalive 探测次数上限。Mirrors Linux {@code tp->keepalive_probes}。 */
    private int keepaliveProbes = TcpConstants.TCP_KEEPALIVE_PROBES;
    /** keepalive 开关(SO_KEEPALIVE)。Mirrors Linux {@code sock_flag(sk, SOCK_KEEPOPEN)}。 */
    private boolean keepaliveEnabled;

    /**
     * RTO 指数退避 shift(R2.3 物理迁移到 Sender)。Mirrors Linux
     * {@code inet_csk(sk)->icsk_backoff}。默认 0,每次 RTO timer 触发递增(上限 6)。
     */
    private int rtoBackoffShift;
    /**
     * 首段重传发送时戳(us),0 表示当前无未确认的重传。Mirrors Linux
     * {@code tp->retrans_stamp}。R2.3 物理迁移到 Sender。
     */
    private long retransStamp;
    /**
     * TLP 探测段的 highSeq,0 表示未在 TLP 阶段。Mirrors Linux
     * {@code tp->tlp_high_seq}。R2.3 物理迁移到 Sender。
     */
    private int tlpHighSeq;

    Sender(TcpSock sock) {
        this.sock = sock;
    }

    public TcpSock sock() {
        return sock;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 发送行为 API
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 应用层 payload 入发送队列。Mirrors Linux {@code tcp_sendmsg}(net/ipv4/tcp.c):
     * 跨 EL 跳转 + MSS 切片 + 可选 push。调用方把 ByteBuf 的引用权转给本方法,
     * 内部负责 release(无论成功失败)。
     *
     * <p>{@code TcpChannel.doWrite} 和 {@code TcpPassthroughInitializer} 反向适配器
     * 都走这里,是发送数据的统一入口。
     *
     * <p>R4.2b-4e:实现从 {@code SegmentDispatcher} 物理迁入。
     */
    public void sendmsg(ByteBuf data, boolean flush) {
        final io.netty.channel.EventLoop owner = sock.eventLoop();
        if (owner != null && !owner.inEventLoop()) {
            owner.execute(() -> sendmsgLocked(data, flush));
        } else {
            sendmsgLocked(data, flush);
        }
    }

    /**
     * 已持锁(== 已在 sock.eventLoop())路径上的 send — 对齐 Linux {@code sendmsgLocked}
     * (net/ipv4/tcp.c)。对 {@code data} 引用计数负责 release。
     */
    private void sendmsgLocked(ByteBuf data, boolean flush) {
        try {
            if (!sock.hasConnection() || !sock.state().canSend()) {
                return;
            }
            final int total = data.readableBytes();
            if (total == 0) {
                return;
            }
            final int mss = Math.max(1, sock.stack().output().currentMss(sock));
            int offset = 0;
            while (offset < total) {
                final int len = Math.min(total - offset, mss);
                final ByteBuf slice = data.retainedSlice(data.readerIndex() + offset, len);
                sock.queueSkb(new TcpSegment(
                        slice,
                        sock.writeSeq(),
                        len,
                        (byte) TcpConstants.TCPHDR_ACK,
                        0L));
                offset += len;
            }
            if (flush) {
                pushPending();
            }
        } finally {
            data.release();
        }
    }

    /** Mirrors Linux {@code tcp_current_mss} (tcp_output.c). */
    public int currentMss() {
        return sock.stack().output().currentMss(sock);
    }

    /**
     * Mirrors Linux {@code tcp_push_pending_frames} (tcp_output.c) — push 本轮待发段。
     * R4.2b-4e:实现从 {@code SegmentDispatcher} 物理迁入。
     */
    public void pushPending() {
        if (sock.hasConnection() && sock.tcpSendHead() != null) {
            boolean needProbe = sock.stack().output().writeXmit(sock, sock.mss(), TcpConstants.TCP_NAGLE_OFF, 0);
            if (needProbe) {
                armProbe0();
            }
        }
    }

    /**
     * 初始化 SND.WL1 — 对齐 Linux {@code tcp_init_wl}。
     * 3WH 收到最后 ACK 推进 ESTABLISHED 时调用,用 ACK 段的 SEQ 初始化 WL1,
     * 后续窗口更新要求 SEQ &ge; WL1。R4.2b-i:从 {@code SegmentDispatcher.initWl} 迁入。
     */
    public void initWl(int seq) {
        if (sock.hasConnection()) {
            sock.sndWl1(seq);
        }
    }

    /**
     * 入站 ACK 处理入口 — 对齐 Linux {@code tcp_ack}(net/ipv4/tcp_input.c)。
     * R4.2b-4f:从 {@code SegmentDispatcher} 物理迁入。
     *
     * @param pkt 入站段,必须带 ACK 标志
     * @param flag 对齐 Linux FLAG_* 位集(FLAG_SLOWPATH / FLAG_UPDATE_TS_RECENT 等)
     * @return 错误码:0 正常,1 非 ACK / 无连接
     */
    public int ackIncoming(TcpPacketBuf pkt, int flag) {
        if (!sock.hasConnection() || !pkt.isAck()) {
            return 1;
        }
        return TcpAck.tcpAck(sock, pkt, flag);
    }

    /**
     * 半关(本端主动发 FIN)状态迁移 — 对齐 Linux {@code tcp_shutdown}。
     * R4.2b-4e:从 {@code SegmentDispatcher} 物理迁入。
     */
    public void shutdown(int how) {
        if (!sock.hasConnection() || (how & TcpConstants.SEND_SHUTDOWN) == 0) {
            return;
        }
        if (sock.state() == TcpConnectionState.TCP_ESTABLISHED
                || sock.state() == TcpConnectionState.CLOSE_WAIT) {
            if (sock.stack().closeState(sock)) {
                sock.stack().output().sendFin(sock);
            }
        }
    }

    /** Mirrors Linux {@code tcp_send_fin} — 本端主动发 FIN。 */
    public void sendFin() {
        sock.stack().output().sendFin(sock);
    }

    /** Mirrors Linux {@code tcp_send_active_reset} — 本端主动发 RST。 */
    public void sendReset() {
        sock.stack().output().sendReset(sock);
    }

    /** Mirrors Linux {@code __tcp_send_ack} — 立即发一个纯 ACK。 */
    public void sendAck() {
        sock.stack().output().sendAck(sock);
    }

    /** Mirrors Linux {@code tcp_retransmit_skb} — 重传 RTX 队首段。 */
    public void retransmit() {
        sock.stack().retransmitter().retransmit(sock);
    }

    /** Mirrors Linux {@code tcp_rearm_rto} — ACK 推进后重置 / 取消 RTO 定时器。 */
    public void rearmRto() {
        sock.stack().retransmitter().rearmRto(sock);
    }

    /** Mirrors Linux {@code tcp_event_retransmit_timer} — RTO 到期入口。 */
    public void onRtoTimeout() {
        sock.stack().retransmitter().onTimeout(sock);
    }

    /** Mirrors Linux {@code tcp_send_challenge_ack} — RFC 5961 挑战 ACK。 */
    public void sendChallengeAck(boolean accecnReflector) {
        sock.stack().output().sendChallengeAck(sock, accecnReflector);
    }

    /** Mirrors Linux {@code tcp_sync_mss} — MSS 刷新(PMTU/ICMP 驱动)。 */
    public int syncMss(int pmtu) {
        return sock.stack().output().syncMss(sock, pmtu);
    }

    /** Mirrors Linux {@code tcp_retransmit_skb} — 重传 RTX 队首段(不经 RTO timer)。 */
    public void retransmitSkb() {
        sock.stack().output().retransmitSkb(sock);
    }

    /** Mirrors Linux {@code tcp_send_loss_probe} — TLP 探测包。 */
    public void sendLossProbe() {
        sock.stack().output().sendLossProbe(sock);
    }

    /** Mirrors Linux {@code tcp_rearm_rto} variant — 按当前 RTO 装重传定时器。 */
    public void scheduleRetransmit() {
        sock.stack().retransmitter().scheduleRetransmit(sock);
    }

    /** Mirrors Linux {@code tcp_schedule_loss_probe} — 装 TLP 定时器。 */
    public void scheduleLossProbe(long delayMs) {
        sock.stack().retransmitter().scheduleLossProbe(sock, delayMs);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 发送侧状态访问器 — 当前 delegate 到 TcpSock 的物理字段
    // ═══════════════════════════════════════════════════════════════════════

    /** RTO 指数退避 +1(上限 6)。Mirrors Linux {@code tcp_retransmit_timer} 中的 backoff 递增。 */
    public void backoff() {
        if (rtoBackoffShift < 6) {
            rtoBackoffShift++;
        }
    }

    /** 复位 RTO backoff。Mirrors Linux {@code icsk->icsk_backoff = 0}(ACK 推进后)。 */
    public void resetBackoff() {
        rtoBackoffShift = 0;
    }

    /** 当前 RTO backoff shift。Mirrors Linux {@code icsk->icsk_backoff}。 */
    public int backoffShift() {
        return rtoBackoffShift;
    }

    /** 直接设置 backoff shift(只在 attach 等恢复路径用)。 */
    public void backoffShift(int v) {
        this.rtoBackoffShift = Math.max(0, v);
    }

    /** 首段重传发送时戳(us);0 表示当前无未确认的重传。Mirrors Linux {@code tp->retrans_stamp}。 */
    public long retransStamp() {
        return retransStamp;
    }

    public void retransStamp(long us) {
        this.retransStamp = us;
    }

    /** TLP 探测段的 highSeq。Mirrors Linux {@code tp->tlp_high_seq}。 */
    public int tlpHighSeq() {
        return tlpHighSeq;
    }

    public void tlpHighSeq(int seq) {
        this.tlpHighSeq = seq;
    }

    /** 最早未 ACK 字节的 seq。Mirrors Linux {@code tp->snd_una}。 */
    public int sndUna() {
        return sndUna;
    }

    public void sndUna(int v) {
        this.sndUna = v;
    }

    /** 下一个待发字节的 seq。Mirrors Linux {@code tp->snd_nxt}。 */
    public int sndNxt() {
        return sndNxt;
    }

    public void sndNxt(int v) {
        this.sndNxt = v;
    }

    /** tail 分配时的 seq 游标。Mirrors Linux {@code tp->write_seq}。 */
    public int writeSeq() {
        return writeSeq;
    }

    public void writeSeq(int v) {
        this.writeSeq = v;
    }

    /** 对端通告的发送窗口(字节)。Mirrors Linux {@code tp->snd_wnd}。 */
    public int sndWnd() {
        return sndWnd;
    }

    /** 更新 sndWnd,同时刷新 maxWindow(单调增)。 */
    public void sndWnd(int v) {
        this.sndWnd = v;
        if (Integer.compareUnsigned(v, maxWindow) > 0) {
            this.maxWindow = v;
        }
    }

    /** 历史最大 sndWnd。Mirrors Linux {@code tp->max_window}。 */
    public int maxWindow() {
        return maxWindow;
    }

    public void maxWindow(int v) {
        this.maxWindow = v;
    }

    /** 上次 window 更新时的 ACK seq。Mirrors Linux {@code tp->snd_wl1}。 */
    public int sndWl1() {
        return sndWl1;
    }

    public void sndWl1(int v) {
        this.sndWl1 = v;
    }

    /** 上次 Nagle/Minshall 检查的 sent seq。Mirrors Linux {@code tp->snd_sml}。 */
    public int sndSml() {
        return sndSml;
    }

    public void sndSml(int v) {
        this.sndSml = v;
    }

    /** 飞行中段数。Mirrors Linux {@code tp->packets_out}。 */
    public int packetsOut() {
        return packetsOut;
    }

    public void packetsOut(int v) {
        this.packetsOut = Math.max(v, 0);
    }

    public void incrementPacketsOut() {
        packetsOut++;
    }

    public void decrementPacketsOut(int n) {
        packetsOut = Math.max(0, packetsOut - n);
    }

    /** SACK 标记的段数。Mirrors Linux {@code tp->sacked_out}。 */
    public int sackedOut() {
        return sackedOut;
    }

    public void sackedOut(int v) {
        this.sackedOut = Math.max(v, 0);
    }

    public void incrementSackedOut() {
        sackedOut++;
    }

    public void decrementSackedOut(int n) {
        sackedOut = Math.max(0, sackedOut - n);
    }

    /** LOST 标记的段数。Mirrors Linux {@code tp->lost_out}。 */
    public int lostOut() {
        return lostOut;
    }

    public void lostOut(int v) {
        this.lostOut = Math.max(v, 0);
    }

    public void incrementLostOut() {
        lostOut++;
    }

    public void decrementLostOut(int n) {
        lostOut = Math.max(0, lostOut - n);
    }

    /** 飞行中重传段计数。Mirrors Linux {@code tp->retrans_out}。 */
    public int retransOut() {
        return retransOut;
    }

    public void retransOut(int v) {
        this.retransOut = Math.max(v, 0);
    }

    public void incrementRetransOut() {
        retransOut++;
    }

    public void decrementRetransOut(int n) {
        retransOut = Math.max(0, retransOut - n);
    }

    /** cwnd 使用高水位时戳。Mirrors Linux {@code tp->snd_cwnd_stamp}。 */
    public long sndCwndStampMs() {
        return sndCwndStampMs;
    }

    public void sndCwndStampMs(long v) {
        this.sndCwndStampMs = v;
    }

    /** cwnd 使用高水位。Mirrors Linux {@code tp->snd_cwnd_used}。 */
    public int sndCwndUsed() {
        return sndCwndUsed;
    }

    public void sndCwndUsed(int v) {
        this.sndCwndUsed = Math.max(v, 0);
    }

    /** cwnd 是否成为发送瓶颈。Mirrors Linux {@code tp->is_cwnd_limited}。 */
    public boolean isCwndLimited() {
        return isCwndLimited;
    }

    public void isCwndLimited(boolean v) {
        this.isCwndLimited = v;
    }

    /** undo 前 cwnd 快照。Mirrors Linux {@code tp->prior_cwnd}。 */
    public int priorCwnd() {
        return priorCwnd;
    }

    public void priorCwnd(int v) {
        this.priorCwnd = v;
    }

    /** undo 前 ssthresh 快照。Mirrors Linux {@code tp->prior_ssthresh}。 */
    public int priorSsthresh() {
        return priorSsthresh;
    }

    public void priorSsthresh(int v) {
        this.priorSsthresh = v;
    }

    /** 拥塞窗口(段数)。Mirrors Linux {@code tp->snd_cwnd}。 */
    public int cwnd() {
        return cwnd;
    }

    public void cwnd(int v) {
        // 对齐 Linux:cwnd 最小 1 SMSS(进 Loss 时 tcp_enter_loss 设 1)。
        // 历史 v2 用 max(v, 2) 是过度保守,改为 max(v, 1) 与 Linux 一致。
        this.cwnd = Math.max(v, 1);
    }

    /** cwnd++ 原子操作。 */
    public void incrementCwnd() {
        this.cwnd++;
    }

    /** 慢启动阈值。Mirrors Linux {@code tp->snd_ssthresh}。 */
    public int ssthresh() {
        return ssthresh;
    }

    public void ssthresh(int v) {
        this.ssthresh = v;
    }

    /** 拥塞控制算法 SPI 当前实例。默认 NewReno。 */
    public TcpCongestionControl congestionControl() {
        return congestionControl;
    }

    /** 注入拥塞控制算法实现 — 通常在握手完成时由 stack 配置一次。 */
    public void congestionControl(TcpCongestionControl cc) {
        this.congestionControl = cc != null ? cc : NewRenoCongestionControl.INSTANCE;
    }

    /** 平滑 RTT (us)。Mirrors Linux {@code tp->srtt_us}。 */
    public long srttUs() {
        return srttUs;
    }

    public void srttUs(long v) {
        this.srttUs = v;
    }

    /** RTT 方差 (us)。Mirrors Linux {@code tp->rttvar_us}。 */
    public long rttvarUs() {
        return rttvarUs;
    }

    public void rttvarUs(long v) {
        this.rttvarUs = v;
    }

    /** writeXmit 进入/退出打桩,re-entry 守卫,详见字段 javadoc。 */
    public void enterXmit() { xmitDepth++; }
    public void exitXmit()  { if (xmitDepth > 0) xmitDepth--; }
    public boolean isInXmit() { return xmitDepth > 0; }

    /** dupack 计数器。Mirrors Linux {@code tp->dup_ack}。 */
    public int dupacks() {
        return dupacks;
    }

    public void dupacks(int v) {
        this.dupacks = v;
    }

    /** dupacks++,返回自增后的值。 */
    public int incrementDupacks() {
        return ++dupacks;
    }

    /** 拥塞控制阶段。Mirrors Linux {@code icsk->icsk_ca_state}。 */
    public TcpSock.CongestionState congestionState() {
        return congestionState;
    }

    public void congestionState(TcpSock.CongestionState v) {
        this.congestionState = v;
    }

    /** Recovery 入口的 sndNxt 快照。Mirrors Linux {@code tp->high_seq}。 */
    public int highSeq() {
        return highSeq;
    }

    public void highSeq(int v) {
        this.highSeq = v;
    }

    /** CA 增量累计器。Mirrors Linux {@code tp->snd_cwnd_cnt}。 */
    public int caIncrCounter() {
        return caIncrCounter;
    }

    public void caIncrCounter(int v) {
        this.caIncrCounter = v;
    }

    public void addCaIncrCounter(int v) {
        this.caIncrCounter += v;
    }

    /** undoRetrans counter。Mirrors Linux {@code tp->undo_retrans}。 */
    public int undoRetrans() {
        return undoRetrans;
    }

    public void undoRetrans(int v) {
        this.undoRetrans = Math.max(v, 0);
    }

    public void incrementUndoRetrans() {
        this.undoRetrans++;
    }

    public void decrementUndoRetrans(int n) {
        this.undoRetrans = Math.max(undoRetrans - n, 0);
    }

    /** undo 快照 sndUna。Mirrors Linux {@code tp->undo_marker}。 */
    public int undoMarker() {
        return undoMarker;
    }

    public void undoMarker(int v) {
        this.undoMarker = v;
    }

    /** F-RTO 武装时的 sndNxt 快照。Mirrors Linux {@code tp->frto_highmark}。 */
    public int frtoHighmark() {
        return frtoHighmark;
    }

    public void frtoHighmark(int v) {
        this.frtoHighmark = v;
    }

    /** F-RTO 状态机计数器。Mirrors Linux {@code tp->frto_counter}。 */
    public int frtoCounter() {
        return frtoCounter;
    }

    public void frtoCounter(int v) {
        this.frtoCounter = v;
    }

    /** 累计已确认字节。Mirrors Linux {@code tp->bytes_acked}。 */
    public long bytesAcked() { return bytesAcked; }
    public void bytesAcked(long v) { this.bytesAcked = v; }
    public void addBytesAcked(long delta) { this.bytesAcked += delta; }

    /** RACK 最近 SACKed 段 sentTime。 */
    public long rackMstamp() { return rackMstamp; }
    public void rackMstamp(long v) { this.rackMstamp = v; }

    /** RACK 当前 RTT(us)。 */
    public long rackRttUs() { return rackRttUs; }
    public void rackRttUs(long v) { this.rackRttUs = v; }

    /** RACK reo_wnd 放宽步数。 */
    public int rackReoWndSteps() { return rackReoWndSteps; }
    public void rackReoWndSteps(int v) { this.rackReoWndSteps = v; }

    /** RACK reo_wnd 持续 epoch 数。 */
    public int rackReoWndPersist() { return rackReoWndPersist; }
    public void rackReoWndPersist(int v) { this.rackReoWndPersist = v; }

    /** RACK DSACK 是否观察过。 */
    public boolean rackDsackSeen() { return rackDsackSeen; }
    public void rackDsackSeen(boolean v) { this.rackDsackSeen = v; }

    /** 已交付段数累计。Mirrors Linux {@code tp->delivered}。 */
    public int delivered() { return delivered; }
    public void delivered(int v) { this.delivered = v; }
    public void addDelivered(int n) {
        this.delivered += n;
        // 对齐 Linux tcp_rate_check_app_limited 内的 delivered_mstamp 推进:
        // 每次 delivered 增长就刷新时戳(微秒,用 nanoTime 源,与 sentTimeUs 同口径)。
        this.deliveredMstampUs = System.nanoTime() / 1_000L;
    }

    /** 最近一次 {@code delivered} 推进的时戳(微秒)— BBR rate sample 用。 */
    public long deliveredMstampUs() { return deliveredMstampUs; }
    public void deliveredMstampUs(long v) { this.deliveredMstampUs = v; }

    /** sender 是否 application-limited(写队列空 / cwnd 未用满)— BBR 过滤 sample 用。 */
    public boolean appLimited() { return appLimited; }
    public void appLimited(boolean v) { this.appLimited = v; }

    /** Pacing rate(字节/秒)— BBR 写入,sender 在 writeXmit 主路径消费。0 表示不限速。 */
    public long pacingRateBps() { return pacingRateBps; }
    public void pacingRateBps(long v) { this.pacingRateBps = Math.max(v, 0L); }

    /** Per-Sender pacing token bucket。 */
    public PacingTokenBucket pacingBucket() { return pacingBucket; }

    // ── PRR 状态(RFC 6937,Recovery 期 cwnd 平滑下降) ─────────────────────────
    /** Recovery 期间累计交付的段数。Mirrors Linux {@code tp->prr_delivered}。 */
    private int prrDelivered;
    /** Recovery 期间发出的段数。Mirrors Linux {@code tp->prr_out}。 */
    private int prrOut;

    public int prrDelivered() { return prrDelivered; }
    public void prrDelivered(int v) { this.prrDelivered = Math.max(v, 0); }
    public int prrOut() { return prrOut; }
    public void prrOut(int v) { this.prrOut = Math.max(v, 0); }

    // ── Per-ACK scratchpad(BBR rate sample 输入,cleanRtxQueue 内填) ─────────
    /** 本次 ACK 释放最旧段的 sentTimeUs(微秒);0 表示无效样本。 */
    private long ackFirstSentTimeUs;
    /** 本次 ACK 释放最旧段的 priorMstamp(微秒)。 */
    private long ackFirstPriorMstamp;
    /** 本次 ACK 释放的段集合是否含重传段(Karn's rule:含则 BDP 估计应排除)。 */
    private boolean ackHasRetrans;
    /** 本次 ACK 释放的真实字节数(累计)。 */
    private int ackTotalBytes;

    public long ackFirstSentTimeUs() { return ackFirstSentTimeUs; }
    public void ackFirstSentTimeUs(long v) { this.ackFirstSentTimeUs = v; }
    public long ackFirstPriorMstamp() { return ackFirstPriorMstamp; }
    public void ackFirstPriorMstamp(long v) { this.ackFirstPriorMstamp = v; }
    public boolean ackHasRetrans() { return ackHasRetrans; }
    public void ackHasRetrans(boolean v) { this.ackHasRetrans = v; }
    public int ackTotalBytes() { return ackTotalBytes; }
    public void ackTotalBytes(int v) { this.ackTotalBytes = v; }
    /** ACK 入口前 reset,让 cleanRtxQueue 重新填。 */
    public void resetAckScratchpad() {
        this.ackFirstSentTimeUs = 0L;
        this.ackFirstPriorMstamp = 0L;
        this.ackHasRetrans = false;
        this.ackTotalBytes = 0;
    }

    /** 上次 RACK step 更新时的 delivered 快照。 */
    public int rackLastDelivered() { return rackLastDelivered; }
    public void rackLastDelivered(int v) { this.rackLastDelivered = v; }

    /** 每 ACK scratchpad:本 ACK 内已投递段 tx.delivered 最大值。 */
    public int rackAckPriorDelivered() { return rackAckPriorDelivered; }
    public void rackAckPriorDelivered(int v) { this.rackAckPriorDelivered = v; }

    /** 上次发送时戳(毫秒 jiffies)。 */
    public long lastSendTimeMs() { return lastSendTimeMs; }
    public void lastSendTimeMs(long v) { this.lastSendTimeMs = v; }

    // ═══════════════════════════════════════════════════════════════════════
    // R7.1 probe / keepalive / linger2 状态访问器(从 TcpSock 迁入)
    // ═══════════════════════════════════════════════════════════════════════

    /** FIN_WAIT_2 超时。Mirrors Linux {@code tp->linger2}。 */
    public int linger2() { return linger2; }
    public void linger2(int v) { this.linger2 = v; }

    /** 零窗探测指数退避 shift。 */
    public int probeBackoffShift() { return probeBackoffShift; }
    public void probeBackoffShift(int v) { this.probeBackoffShift = Math.max(v, 0); }
    /** 退避 shift +1(上限 31)— 对齐 Linux {@code tcp_probe_timer} 的退避逻辑。 */
    public void incProbeBackoff() {
        if (probeBackoffShift < 31) {
            probeBackoffShift++;
        }
    }

    /** 已发探测包计数。 */
    public int probesOut() { return probesOut; }
    public void probesOut(int v) { this.probesOut = Math.max(v, 0); }

    /** 进入探测阶段的时戳。 */
    public long probesTstampMs() { return probesTstampMs; }
    public void probesTstampMs(long v) { this.probesTstampMs = Math.max(v, 0L); }

    /** TCP_USER_TIMEOUT(ms)。 */
    public long userTimeoutMs() { return userTimeoutMs; }
    public void userTimeoutMs(long v) { this.userTimeoutMs = Math.max(v, 0L); }

    /** keepalive 空闲阈值(Mirrors Linux TCP_KEEPIDLE)。 */
    public long keepaliveTimeMs() { return keepaliveTimeMs; }
    public void keepaliveTimeMs(long v) { this.keepaliveTimeMs = Math.max(v, 1L); }

    /** keepalive 探测间隔(Mirrors Linux TCP_KEEPINTVL)。 */
    public long keepaliveIntvlMs() { return keepaliveIntvlMs; }
    public void keepaliveIntvlMs(long v) { this.keepaliveIntvlMs = Math.max(v, 1L); }

    /** keepalive 探测次数上限(Mirrors Linux TCP_KEEPCNT)。 */
    public int keepaliveProbes() { return keepaliveProbes; }
    public void keepaliveProbes(int v) { this.keepaliveProbes = Math.max(v, 1); }

    /** keepalive 开关。 */
    public boolean keepaliveEnabled() { return keepaliveEnabled; }
    public void keepaliveEnabled(boolean v) { this.keepaliveEnabled = v; }

    /**
     * 自上次有效段交付/发送以来的 idle 时间(ms)。对齐 Linux
     * {@code keepalive_time_elapsed}:取 {@code lrcv_time} 与 {@code last_send_time} 的较新者。
     */
    public long keepaliveElapsedMs() {
        long now = com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpClock.tcp_jiffies32();
        long lastRecv = sock.receiver() != null ? sock.receiver().lastRecvTimeMs() : 0L;
        long lastActivity = lastRecv != 0L ? lastRecv : lastSendTimeMs;
        if (lastActivity == 0L) {
            return 0L;
        }
        return Math.max(now - lastActivity, 0L);
    }

    /** 对齐 Linux {@code TCP_RTO_MAX}。 */
    public long tcpRtoMaxMs() {
        return TcpConstants.RTO_MAX_MS;
    }

    /**
     * 对齐 Linux {@code tcp_packets_in_flight}(include/net/tcp.h):
     * {@code packets_out - sacked_out - lost_out + retrans_out}。
     *
     * <p>{@code retrans_out} 维护点:
     * <ul>
     *   <li>{@link TcpOutput#retransmitSkb} 首次给段打 {@code TCPCB_SACKED_RETRANS} → ++;</li>
     *   <li>{@code tcp_clean_rtx_queue}({@code TcpAck.cleanRtxQueue})累计 ACK 释放
     *       带 {@code TCPCB_SACKED_RETRANS} 的段 → --;</li>
     *   <li>进入 {@code tcp_enter_loss}({@code Sender.onTimeoutByCc}) → 清零,
     *       后续 RTO 重传从头计数。</li>
     * </ul>
     *
     * R7.3a:方法体从 TcpSock 迁入。
     */
    public int packetsInFlight() {
        return Math.max(0, packetsOut - sackedOut - lostOut + retransOut);
    }

    /**
     * 对齐 Linux {@code tcp_set_rto}(tcp_input.c):先将 {@code base = srtt + 4·rttvar}
     * clamp 到 {@code [RTO_MIN_MS, RTO_MAX_MS]},再按 {@code rtoBackoffShift}
     * 逐步左移,每一步检测是否触顶 {@code RTO_MAX_MS}。
     *
     * <p>若 {@code srttUs} 为 0(未取样)使用 {@link TcpConstants#RTO_INIT_MS}(1000ms)。
     * R7.2:从 TcpSock 迁入,方法体全部读 Sender 字段,归位。
     */
    public long rtoMs() {
        long baseMs;
        if (srttUs <= 0L) {
            baseMs = TcpConstants.RTO_INIT_MS;
        } else {
            long varianceUs = Math.max(4L * rttvarUs, 0L);
            long baseUs = srttUs + varianceUs;
            baseMs = Math.max((baseUs + 999L) / 1_000L, TcpConstants.RTO_MIN_MS);
        }
        long r = baseMs;
        for (int i = 0; i < rtoBackoffShift; i++) {
            if (r >= TcpConstants.RTO_MAX_MS) {
                return TcpConstants.RTO_MAX_MS;
            }
            r <<= 1;
            if (r < 0L) {
                return TcpConstants.RTO_MAX_MS;
            }
        }
        return Math.min(r, TcpConstants.RTO_MAX_MS);
    }

    /** 零窗探测基线 = max(RTO, RTO_MIN)。 */
    public long tcpProbe0BaseMs() {
        return Math.max(rtoMs(), TcpConstants.RTO_MIN_MS);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // R7.3b CC 状态机(从 TcpSock 迁入)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 每个 ACK 处理尾部的 CC 反馈入口 — 对齐 Linux {@code tcp_ack} 尾部
     * congestion-control 更新块:
     * <ul>
     *   <li>{@code !advanced}:ACK 未推进 SND.UNA(duplicate ACK);
     *     <ul>
     *       <li>第 3 个 dupack 且当前 CA_Open:进 CA_Recovery(ssthresh=cwnd/2,
     *           cwnd=ssthresh+3,标记 head LOST,立即重传)</li>
     *       <li>已在 CA_Recovery:每个 dupack cwnd++(NewReno cwnd inflation)</li>
     *     </ul>
     *   </li>
     *   <li>{@code advanced}:ACK 推进了 SND.UNA;
     *     <ul>
     *       <li>CA_Recovery 且 SND.UNA 跨过 highSeq:退出 → cwnd=ssthresh,CA_Open</li>
     *       <li>CA_Loss:直接退出 → CA_Open(清 F-RTO)</li>
     *       <li>dupacks 归零,按 slow-start / CA cwnd 增长</li>
     *     </ul>
     *   </li>
     * </ul>
     * R7.3b:方法体从 TcpSock 迁入。
     */
    public void onAckedByCc(int newlyAcked, boolean advanced) {
        if (!advanced) {
            final int newDup = incrementDupacks();
            // 对齐 Linux:OPEN 收到第一个 dupack → 进 CA_Disorder。
            if (congestionState == TcpSock.CongestionState.OPEN && newDup >= 1) {
                TcpSock.CongestionState old = congestionState;
                congestionState = TcpSock.CongestionState.DISORDER;
                congestionControl.onStateChange(sock, old, congestionState);
            }
            if (newDup == 3
                    && (congestionState == TcpSock.CongestionState.OPEN
                            || congestionState == TcpSock.CongestionState.DISORDER)) {
                if (log.isInfoEnabled()) {
                    log.info("[TCP-FR] {} fastRetransmit(3 dupacks) sndUna={} sndNxt={}"
                                    + "packetsOut={} lostOut={} sackedOut={} retransOut={} "
                                    + "cwnd={} ssthresh={} caState={}",
                            sock.fourTuple(),
                            Integer.toUnsignedString(sndUna),
                            Integer.toUnsignedString(sndNxt),
                            sock.packetsOut(), sock.lostOut(), sock.sackedOut(), retransOut,
                            cwnd, ssthresh, congestionState);
                }
                // 进 Recovery: undo 快照 → 计算 ssthresh → 切状态字段 → 通知 CC
                // 让 CC 在 onStateChange 内决定 cwnd(NewReno: ssthresh+3;BBR: 通常不动)
                tcpInitUndo();
                ssthresh = congestionControl.ssthresh(sock);
                highSeq = sndNxt;
                tlpHighSeq = 0;
                TcpSock.CongestionState old = congestionState;
                congestionState = TcpSock.CongestionState.RECOVERY;
                caIncrCounter = 0;
                congestionControl.onStateChange(sock, old, congestionState);
                // 对齐 Linux tcp_enter_recovery → markHeadLost
                TcpAck.markHeadLost(sock, 1);
                sock.stack().retransmitter().retransmit(sock);
            } else if (congestionState == TcpSock.CongestionState.RECOVERY) {
                // dupack inflation 走 SPI:ackedPackets=0 表示 dupack
                rateSampleScratch.reset();
                congestionControl.onAck(sock, rateSampleScratch);
            }
            return;
        }

        // advanced(SND.UNA 推进)路径
        TcpSock.CongestionState old = congestionState;
        if (old == TcpSock.CongestionState.RECOVERY
                && TcpSequence.after(sndUna, highSeq)) {
            congestionState = TcpSock.CongestionState.OPEN;
            caIncrCounter = 0;
            congestionControl.onStateChange(sock, old, congestionState);
        } else if (old == TcpSock.CongestionState.LOSS) {
            congestionState = TcpSock.CongestionState.OPEN;
            caIncrCounter = 0;
            clearFrto();
            congestionControl.onStateChange(sock, old, congestionState);
        } else if (old == TcpSock.CongestionState.DISORDER) {
            // CA_Disorder + SND.UNA 推进 → reorder 不是丢包 → 回 OPEN(不动 cwnd/ssthresh)
            congestionState = TcpSock.CongestionState.OPEN;
            congestionControl.onStateChange(sock, old, congestionState);
        }

        dupacks = 0;

        // cwnd 增长走 SPI(NewReno: slow start / CA;CUBIC: cubic 曲线;BBR: BDP 模型)
        rateSampleScratch.reset();
        rateSampleScratch.ackedPackets = newlyAcked;
        // ackedBytes:cleanRtxQueue 内累加的真实字节数;cleanRtxQueue 未填(纯 dup ACK 等)
        // 时退化为 newlyAcked × MSS 估算。
        rateSampleScratch.ackedBytes = ackTotalBytes > 0
                ? ackTotalBytes
                : newlyAcked * Math.max(sock.mss(), 1);
        // BBR rate sample 字段(由 cleanRtxQueue 填的 scratchpad 转过来)
        rateSampleScratch.delivered = delivered;
        rateSampleScratch.priorDelivered = rackAckPriorDelivered;
        rateSampleScratch.appLimited = appLimited;
        rateSampleScratch.minRttUs = sock.minRttUs();
        rateSampleScratch.rttUs = srttUs;
        rateSampleScratch.isAckedRetrans = ackHasRetrans;
        if (ackFirstSentTimeUs > 0L) {
            // 对齐 Linux tcp_rate_gen:
            //   send_us = now - first_acked.sent_time
            //   ack_us  = now - first_acked.prior_mstamp
            //   interval = max(send_us, ack_us)
            long nowUs = System.nanoTime() / 1_000L;
            rateSampleScratch.sendElapsedUs = Math.max(0L, nowUs - ackFirstSentTimeUs);
            if (ackFirstPriorMstamp > 0L) {
                rateSampleScratch.ackElapsedUs = Math.max(0L, nowUs - ackFirstPriorMstamp);
            }
        }
        congestionControl.onAck(sock, rateSampleScratch);
    }

    /**
     * RTO 到期后的 CC 反馈入口 — 对齐 Linux {@code tcp_enter_loss}:
     * 快照 undo 基线 + 武装 F-RTO(如有 undo 机会)+ ssthresh = cwnd/2 + cwnd = 1
     * + CA 状态 → CA_Loss。
     * R7.3b:方法体从 TcpSock 迁入。
     */
    public void onTimeoutByCc() {
        if (log.isInfoEnabled()) {
            log.info("[TCP-RTO] {} onTimeoutByCc(enter_loss) sndUna={} sndNxt={}"
                            + "packetsOut={} lostOut={} sackedOut={} retransOut={} cwnd={} "
                            + "ssthresh={} srttUs={} rtoMs={} caState={}",
                    sock.fourTuple(),
                    Integer.toUnsignedString(sock.sndUna()),
                    Integer.toUnsignedString(sock.sndNxt()),
                    sock.packetsOut(), sock.lostOut(), sock.sackedOut(), retransOut,
                    cwnd, ssthresh, srttUs, rtoMs(), congestionState,
                    new Throwable("[TCP-RTO] caller stack"));
        }
        tcpInitUndo();
        // F-RTO 武装条件(RFC 5682):有 undo 机会(undoMarker 已建立)且
        // RTO 瞬间仍有在飞数据(sndNxt > undoMarker),才把 snd_nxt 快照为 frto_high_mark。
        if (undoMarker != 0 && TcpSequence.after(sndNxt, undoMarker)) {
            frtoHighmark = sndNxt;
            frtoCounter = 1;
        } else {
            clearFrto();
        }
        // CC SPI 决定 ssthresh — 与 Recovery 入口同语义,所有算法在此返回新阈值
        ssthresh = congestionControl.ssthresh(sock);
        // cwnd 由 CC.onStateChange(_, LOSS) 设置(NewReno: 1;BBR: 不变)
        dupacks = 0;
        caIncrCounter = 0;
        tlpHighSeq = 0;
        // 对齐 Linux tcp_enter_loss(net/ipv4/tcp_input.c):扫 rtx 队列重置每段标记。
        //   - 一律清 TCPCB_SACKED_RETRANS + TCPCB_LOST(LOST_MASK);
        //   - 非 TCPCB_SACKED_ACKED 段重打 TCPCB_LOST,并重新计入 lost_out;
        //   - retrans_out = 0(Linux 在函数体起始处直接归零)。
        // SACK_ACKED 段不重打 LOST(已视作交付,等待累计 ACK 释放即可)。
        // RTO 后 retransmitSkb 走 LOST-driven 路径选段,首次重传时 retrans_out++
        // 重新累积,与 Linux 行为一致。
        int newLostOut = 0;
        for (TcpSegment skb : sock.sendBuffer().rtxView()) {
            int s = skb.sacked();
            s &= ~(TcpConstants.TCPCB_SACKED_RETRANS | TcpConstants.TCPCB_LOST);
            if ((s & TcpConstants.TCPCB_SACKED_ACKED) == 0) {
                s |= TcpConstants.TCPCB_LOST;
                newLostOut++;
            }
            skb.sacked(s);
        }
        lostOut = newLostOut;
        retransOut = 0;
        TcpSock.CongestionState oldStateForLoss = congestionState;
        congestionState = TcpSock.CongestionState.LOSS;
        // CC 在此设最终 cwnd:NewReno → 1;BBR → 由 BBR 模型决定
        congestionControl.onStateChange(sock, oldStateForLoss, TcpSock.CongestionState.LOSS);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // R7.3c Undo / F-RTO 家族(从 TcpSock 迁入)
    // ═══════════════════════════════════════════════════════════════════════

    /** 清除 F-RTO 武装状态 — 对应 Linux {@code tp->frto = 0}。 */
    public void clearFrto() {
        frtoHighmark = 0;
        frtoCounter = 0;
    }

    /**
     * 对齐 Linux {@code tcp_init_undo}:进入 Recovery/Loss 前快照
     * {@code cwnd / ssthresh / snd_una} 到 {@code priorCwnd / priorSsthresh
     * / undoMarker},为后续 {@code tcp_try_undo_*} 提供回滚基线。
     * 同时清零 {@code undoRetrans / retransStamp}(本 epoch 的计数 / 打戳由
     * {@code TcpOutput.retransmitSkb} 在首次重传时写入,避免上一 epoch 残留污染
     * DSACK-driven undo 判定)。
     */
    public void tcpInitUndo() {
        priorCwnd = cwnd;
        priorSsthresh = (ssthresh == Integer.MAX_VALUE) ? 0 : ssthresh;
        undoMarker = sndUna;
        undoRetrans = 0;
        retransStamp = 0L;
    }

    /**
     * 对齐 Linux {@code tcp_undo_cwnd_reduction}:将 cwnd/ssthresh 回滚到
     * {@link #tcpInitUndo} 记录的快照(取 max 防止已自然增长的 cwnd 被压回);
     * 清空 undo / retrans 记录,并顺带清 F-RTO。
     *
     * @param unmarkLoss 为 true 时清空 lostOut(tcp_try_undo_loss 专用)
     */
    public void tcpUndoCwndReduction(boolean unmarkLoss) {
        // CC SPI 决定 undoCwnd — NewReno/CUBIC: max(priorCwnd, cwnd) 不让自然增长被压回
        if (priorCwnd > 0) cwnd = congestionControl.undoCwnd(sock);
        if (priorSsthresh > 0) ssthresh = Math.max(ssthresh, priorSsthresh);
        undoMarker = 0;
        retransStamp = 0L;
        undoRetrans = 0;
        clearFrto();
        if (unmarkLoss) {
            lostOut = 0;
        }
    }

    /**
     * 对齐 Linux {@code tcp_try_undo_recovery} 的 TSECR-based 伪 FR 闭环。
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c">tcp_try_undo_recovery</a>
     */
    public boolean tcpTryUndoRecovery(int tsecr) {
        if (congestionState != TcpSock.CongestionState.RECOVERY) return false;
        if (undoMarker == 0 || retransStamp == 0L) return false;
        if (tsecr == -1) return false;
        final int retransStampMs = (int) (retransStamp / 1000L);
        if (!TcpSequence.before(tsecr, retransStampMs)) return false;
        tcpUndoCwndReduction(false);
        congestionState = TcpSock.CongestionState.OPEN;
        caIncrCounter = 0;
        dupacks = 0;
        return true;
    }

    /**
     * 对齐 Linux {@code tcp_try_undo_loss} 的 TSECR-based 伪 RTO 闭环。
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c">tcp_try_undo_loss</a>
     */
    public boolean tcpTryUndoLoss(int tsecr) {
        if (congestionState != TcpSock.CongestionState.LOSS) return false;
        if (undoMarker == 0 || retransStamp == 0L) return false;
        if (tsecr == -1) return false;
        final int retransStampMs = (int) (retransStamp / 1000L);
        if (!TcpSequence.before(tsecr, retransStampMs)) return false;
        tcpUndoCwndReduction(false);
        congestionState = TcpSock.CongestionState.OPEN;
        caIncrCounter = 0;
        dupacks = 0;
        return true;
    }

    /**
     * F-RTO(RFC 5682)基于 sndUna 追上 frtoHighmark 的伪 RTO 判定。
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c">tcp_process_loss</a>
     */
    public boolean tcpProcessFrto() {
        if (congestionState != TcpSock.CongestionState.LOSS) return false;
        if (frtoCounter == 0) return false;
        if (undoMarker == 0) return false;
        if (TcpSequence.before(sndUna, frtoHighmark)) return false;
        tcpUndoCwndReduction(true);
        congestionState = TcpSock.CongestionState.OPEN;
        caIncrCounter = 0;
        dupacks = 0;
        return true;
    }

    /**
     * 对齐 Linux {@code tcp_try_undo_dsack}:DSACK 抵消所有重传后的 undo 路径。
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c">tcp_try_undo_dsack</a>
     */
    public boolean tcpTryUndoDsack() {
        if (congestionState != TcpSock.CongestionState.RECOVERY
                && congestionState != TcpSock.CongestionState.LOSS) {
            return false;
        }
        if (undoMarker == 0 || retransStamp == 0L) return false;
        if (undoRetrans != 0) return false;
        tcpUndoCwndReduction(false);
        congestionState = TcpSock.CongestionState.OPEN;
        caIncrCounter = 0;
        dupacks = 0;
        return true;
    }

    /** 下一次探测的绝对等待时长,按当前退避 shift 指数放大,并夹到 maxWhenMs。 */
    public long tcpProbe0WhenMs(long maxWhenMs) {
        int backoff = Math.min(9, probeBackoffShift);
        long when = tcpProbe0BaseMs() << backoff;
        return Math.min(when, maxWhenMs);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // R7.3d cwnd 管理(从 TcpSock 迁入)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 空闲期后的 cwnd 回退 — 对齐 Linux {@code tcp_slow_start_after_idle_check}。
     * 在 {@code tcp_write_xmit} 入口处调用:sysctl 关闭 / 尚有 packets_out / 未曾发送
     * 时跳过;否则若 idle 跨过一个 RTO,调 {@link #tcpCwndRestart} 对半衰减 cwnd。
     */
    public void tcpSlowStartAfterIdleCheck() {
        if (!SysctlOptions.ipv4_sysctl_tcp_slow_start_after_idle) return;
        if (packetsOut != 0) return;
        if (lastSendTimeMs == 0L) return;
        long now = com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpClock.tcp_jiffies32();
        long delta = now - lastSendTimeMs;
        long rto = rtoMs();
        if (delta > rto) {
            tcpCwndRestart(delta, rto);
        }
    }

    /**
     * 对齐 Linux {@code tcp_cwnd_restart}:restart_cwnd = min(INIT, cwnd);
     * 按 RTO 步长把 cwnd 右移 1 直至降到 restart_cwnd;刷新 cwnd_stamp,
     * 重置 cwnd_used。CA_Open 下同步把 ssthresh 抬到 max(ssthresh, 3*cwnd/4+1)。
     */
    private void tcpCwndRestart(long delta, long rtoMs) {
        int restartCwnd = TcpConstants.TCP_INIT_CWND;
        int curCwnd = cwnd;
        if (congestionState == TcpSock.CongestionState.OPEN) {
            int conservative = (curCwnd * 3 / 4) + 1;
            if (ssthresh < conservative) ssthresh = conservative;
        }
        restartCwnd = Math.min(restartCwnd, curCwnd);
        long remain = delta;
        while ((remain -= rtoMs) > 0 && curCwnd > restartCwnd) {
            curCwnd >>= 1;
        }
        cwnd = Math.max(curCwnd, restartCwnd);
        sndCwndStampMs = com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpClock.tcp_jiffies32();
        sndCwndUsed = 0;
    }

    /**
     * 对齐 Linux {@code tcp_cwnd_validate}:本轮发送结束后确认 cwnd 可用性。
     * cwnd-limited 分支刷新 stamp;application-limited 且超 1 RTO 时走
     * {@link #tcpCwndApplicationLimited} 向下收敛。
     */
    public void tcpCwndValidate(boolean isCwndLimitedFlag) {
        if (isCwndLimitedFlag) {
            isCwndLimited = true;
            sndCwndStampMs = com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpClock.tcp_jiffies32();
        } else {
            if (isCwndLimited) {
                long now = com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpClock.tcp_jiffies32();
                long elapsed = now - sndCwndStampMs;
                if (elapsed >= rtoMs()) {
                    tcpCwndApplicationLimited();
                }
            }
        }
    }

    /**
     * application-limited 场景下的 cwnd 收敛 — 对齐 Linux
     * {@code tcp_cwnd_application_limited}。CA_Open 下把 cwnd 向
     * (cwnd + max(cwnd_used, INIT)) / 2 收敛,并保守抬 ssthresh。
     */
    /**
     * 对齐 Linux {@code tcp_rack_update_reo_wnd}:DSACK 触发 reo_wnd 动态放宽
     * (1-RTT 门控 + 持续 epoch 递减衰减),供 RACK 乱序检测用。
     * R7.3e:方法体从 TcpSock 迁入。
     *
     * @param priorDelivered 本 ACK 所确认段的 {@code tx.delivered} 最大值
     */
    public void tcpRackUpdateReoWnd(int priorDelivered) {
        if (priorDelivered == 0) return;
        // S-3: 同一 RTT 内的旧 DSACK 不再步进
        if (rackDsackSeen && TcpSequence.before(priorDelivered, rackLastDelivered)) {
            rackDsackSeen = false;
        }
        if (rackDsackSeen) {
            rackReoWndSteps = Math.min(rackReoWndSteps + 1, 0xFF);
            rackDsackSeen = false;
            rackLastDelivered = delivered;
            rackReoWndPersist = TcpConstants.TCP_RACK_RECOVERY_THRESH;
        } else if (rackReoWndPersist <= 0) {
            rackReoWndSteps = 1;
        } else {
            rackReoWndPersist--;
        }
    }

    private void tcpCwndApplicationLimited() {
        if (congestionState == TcpSock.CongestionState.OPEN) {
            int initWin = TcpConstants.TCP_INIT_CWND;
            int winUsed = Math.max(sndCwndUsed, initWin);
            int c = cwnd;
            if (winUsed < c) {
                int conservative = (c * 3 / 4) + 1;
                if (ssthresh < conservative) ssthresh = conservative;
                cwnd = (c + winUsed) >> 1;
            }
            sndCwndUsed = 0;
        }
        sndCwndStampMs = com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpClock.tcp_jiffies32();
        isCwndLimited = false;
    }

    /** 若 USER_TIMEOUT 配置了,把等待时间夹到剩余窗口内,否则原值返回。 */
    public long tcpClampProbe0ToUserTimeout(long whenMs) {
        if (userTimeoutMs == 0L || probesTstampMs == 0L) {
            return whenMs;
        }
        long elapsed = com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpClock.tcp_jiffies32() - probesTstampMs;
        if (elapsed < 0L) {
            elapsed = 0L;
        }
        long remaining = Math.max(userTimeoutMs - elapsed, TcpConstants.RTO_MIN_MS);
        return Math.min(remaining, whenMs);
    }

    /** 探测状态重置 —— 收到对端任何 ACK / 窗口更新时调用。 */
    public void resetProbeState() {
        probeBackoffShift = 0;
        probesOut = 0;
        probesTstampMs = 0L;
    }

    /**
     * FIN_WAIT_2 阶段的超时计算 —— 对齐 Linux {@code tcp_fin_time}。
     * 若 {@code linger2 > 0},取 {@code max(linger2, 3.5 × RTO)},否则回退默认 2MSL。
     */
    public int tcpFinTimeMs() {
        int finTimeout = linger2 != 0 ? linger2 : (int) TcpConstants.FIN_WAIT_2_TIMEOUT_MS;
        long rto = sock.rtoMs();
        long minTimeout = (rto << 2) - (rto >> 1);
        if (finTimeout < minTimeout) {
            finTimeout = (int) minTimeout;
        }
        return finTimeout;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 发送侧 Timer 行为(R4.2b-4d 从 SegmentDispatcher 迁入)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 零窗探测 timer 武装 — 对齐 Linux {@code tcp_reset_xmit_timer(..., ICSK_TIME_PROBE0)}。
     * 在没有在途包且队列有待发数据时安排。
     */
    public void armProbe0() {
        if (!sock.hasConnection() || packetsOut() != 0 || sock.tcpSendHead() == null) {
            return;
        }
        TcpTimerScheduler.INSTANCE.scheduleWriteTimer(
                sock,
                TimerType.ZERO_WINDOW_PROBE,
                tcpProbe0BaseMs(),
                this::probeTimer
        );
    }

    /**
     * 零窗探测到期回调 — 对齐 Linux {@code tcp_probe_timer}。处理 USER_TIMEOUT、
     * TCP_RETRIES2 重试上限,成功发出探测后按指数退避重新武装。
     */
    public void probeTimer() {
        if (!sock.hasConnection()) {
            return;
        }
        if (packetsOut() > 0 || sock.tcpSendHead() == null) {
            resetProbeState();
            return;
        }

        long now = com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpClock.tcp_jiffies32();
        if (probesTstampMs == 0L) {
            probesTstampMs = now;
        } else if (userTimeoutMs > 0 && now - probesTstampMs >= userTimeoutMs) {
            sock.skErr(110);
            sock.stack().output().sendReset(sock);
            sock.stack().tcpDone(sock);
            return;
        }

        if (probesOut >= TcpConstants.TCP_RETRIES2) {
            sock.skErr(110);
            sock.stack().output().sendReset(sock);
            sock.stack().tcpDone(sock);
            return;
        }

        long timeout = sock.stack().output().sendProbe0(sock);
        if (timeout > 0L) {
            TcpTimerScheduler.INSTANCE.scheduleWriteTimer(
                    sock,
                    TimerType.ZERO_WINDOW_PROBE,
                    timeout,
                    this::probeTimer
            );
        }
    }

    /**
     * Keepalive timer 武装 — 对齐 Linux {@code inet_csk_reset_keepalive_timer}。
     * LISTEN / SYN_RECV / CLOSED / TIME_WAIT 不武装。
     */
    public void armKeepalive(long delayMs) {
        if (!sock.hasConnection()
                || !keepaliveEnabled
                || sock.state() == TcpConnectionState.TIME_WAIT
                || sock.state() == TcpConnectionState.TCP_CLOSED
                || sock.state() == TcpConnectionState.TCP_LISTEN
                || sock.state() == TcpConnectionState.TCP_SYN_RECV) {
            return;
        }
        TcpTimerScheduler.INSTANCE.scheduleKeepalive(sock, Math.max(delayMs, 1L), this::keepaliveTimer);
    }

    /**
     * Keepalive timer 到期回调 — 对齐 Linux {@code tcp_keepalive_timer}。
     * FIN_WAIT_2 不做 keepalive;有在途包时续期;idle 超过 keepaliveTime 后发 probe。
     */
    public void keepaliveTimer() {
        if (!sock.hasConnection() || !keepaliveEnabled) {
            return;
        }

        if (sock.state() == TcpConnectionState.FIN_WAIT_2) {
            return;
        }

        if (packetsOut() > 0 || sock.tcpSendHead() != null) {
            armKeepalive(keepaliveTimeMs);
            return;
        }

        long elapsed = keepaliveElapsedMs();
        if (elapsed < keepaliveTimeMs) {
            armKeepalive(keepaliveTimeMs - elapsed);
            return;
        }

        long userTimeout = userTimeoutMs;
        if ((userTimeout > 0L && elapsed >= userTimeout && probesOut > 0)
                || (userTimeout == 0L && probesOut >= keepaliveProbes)) {
            sock.skErr(110);
            sock.stack().output().sendReset(sock);
            sock.stack().tcpDone(sock);
            return;
        }

        int err = sock.stack().output().writeWakeup(sock, 1);
        long next;
        if (err <= 0) {
            probesOut++;
            next = keepaliveIntvlMs;
        } else {
            next = TcpConstants.TCP_RESOURCE_PROBE_INTERVAL_MS;
        }
        armKeepalive(next);
    }
}
