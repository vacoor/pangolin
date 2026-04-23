package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import io.netty.buffer.ByteBuf;

/**
 * 发送侧关注点的聚合对象,对齐 gVisor netstack 的 {@code sender}
 * (pkg/tcpip/transport/tcp/snd.go)。每条 {@link TcpSock} 对应一个 {@code Sender},
 * 由 {@link TcpMultiplexer#configure(TcpSock)} 创建并挂入 {@link TcpSock#sender()}。
 *
 * <p><b>职责</b>:cwnd / ssthresh / nagle / push / fragment / 重传调度 / RTO 状态;
 * 不含接收侧(OFO / reassembly / rcvWnd),后者由 {@link Receiver}。
 *
 * <p><b>当前实现形态</b>:Sender 是 facade — 方法 delegate 到
 * {@link TcpOutput} / {@link TcpRetransmitter} / {@link TcpMultiplexer} 的底层实现,
 * 发送侧 mutable state(writeSeq / sndUna / cwnd / ssthresh / packetsOut / sendBuffer 等)
 * 仍存在 TcpSock。未来若要物理下沉,替换本类方法实现即可,调用方零感知。
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
    /** 平滑 RTT(us)。Mirrors Linux {@code tp->srtt_us}。 */
    private long srttUs;
    /** RTT 方差(us)。Mirrors Linux {@code tp->rttvar_us}。 */
    private long rttvarUs;
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
     */
    public void sendmsg(ByteBuf data, boolean flush) {
        sock.multiplexer().sendmsg(sock, data, flush);
    }

    /** Mirrors Linux {@code tcp_current_mss} (tcp_output.c). */
    public int currentMss() {
        return sock.multiplexer().output().currentMss(sock);
    }

    /** Mirrors Linux {@code tcp_push_pending_frames} (tcp_output.c) — push 本轮待发段。 */
    public void pushPending() {
        sock.multiplexer().pushPendingFrames(sock);
    }

    /** Mirrors Linux {@code tcp_send_fin} — 本端主动发 FIN。 */
    public void sendFin() {
        sock.multiplexer().output().sendFin(sock);
    }

    /** Mirrors Linux {@code tcp_send_active_reset} — 本端主动发 RST。 */
    public void sendReset() {
        sock.multiplexer().output().sendReset(sock);
    }

    /** Mirrors Linux {@code __tcp_send_ack} — 立即发一个纯 ACK。 */
    public void sendAck() {
        sock.multiplexer().output().sendAck(sock);
    }

    /** Mirrors Linux {@code tcp_retransmit_skb} — 重传 RTX 队首段。 */
    public void retransmit() {
        sock.multiplexer().retransmitter().retransmit(sock);
    }

    /** Mirrors Linux {@code tcp_rearm_rto} — ACK 推进后重置 / 取消 RTO 定时器。 */
    public void rearmRto() {
        sock.multiplexer().retransmitter().rearmRto(sock);
    }

    /** Mirrors Linux {@code tcp_event_retransmit_timer} — RTO 到期入口。 */
    public void onRtoTimeout() {
        sock.multiplexer().retransmitter().onTimeout(sock);
    }

    /** Mirrors Linux {@code tcp_send_challenge_ack} — RFC 5961 挑战 ACK。 */
    public void sendChallengeAck(boolean accecnReflector) {
        sock.multiplexer().output().sendChallengeAck(sock, accecnReflector);
    }

    /** Mirrors Linux {@code tcp_sync_mss} — MSS 刷新(PMTU/ICMP 驱动)。 */
    public int syncMss(int pmtu) {
        return sock.multiplexer().output().syncMss(sock, pmtu);
    }

    /** Mirrors Linux {@code tcp_retransmit_skb} — 重传 RTX 队首段(不经 RTO timer)。 */
    public void retransmitSkb() {
        sock.multiplexer().output().retransmitSkb(sock);
    }

    /** Mirrors Linux {@code tcp_send_loss_probe} — TLP 探测包。 */
    public void sendLossProbe() {
        sock.multiplexer().output().sendLossProbe(sock);
    }

    /** Mirrors Linux {@code tcp_rearm_rto} variant — 按当前 RTO 装重传定时器。 */
    public void scheduleRetransmit() {
        sock.multiplexer().retransmitter().scheduleRetransmit(sock);
    }

    /** Mirrors Linux {@code tcp_schedule_loss_probe} — 装 TLP 定时器。 */
    public void scheduleLossProbe(long delayMs) {
        sock.multiplexer().retransmitter().scheduleLossProbe(sock, delayMs);
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

    /** 当前 RTO (ms),按 srtt/rttvar + backoff。Mirrors Linux {@code inet_csk(sk)->icsk_rto}。 */
    public long rtoMs() {
        return sock.rtoMs();
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
        this.cwnd = Math.max(v, 2);
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
    public void addDelivered(int n) { this.delivered += n; }

    /** 上次 RACK step 更新时的 delivered 快照。 */
    public int rackLastDelivered() { return rackLastDelivered; }
    public void rackLastDelivered(int v) { this.rackLastDelivered = v; }

    /** 每 ACK scratchpad:本 ACK 内已投递段 tx.delivered 最大值。 */
    public int rackAckPriorDelivered() { return rackAckPriorDelivered; }
    public void rackAckPriorDelivered(int v) { this.rackAckPriorDelivered = v; }
}
