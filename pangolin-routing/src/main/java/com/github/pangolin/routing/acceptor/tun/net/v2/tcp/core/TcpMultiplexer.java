package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpOptionCodec;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConnection;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConnectionState;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.ConnectionKey;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpReceiveBuffer;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSendBuffer;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSkb;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.SkbDropReason;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpAck;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpOutput;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpRetransmitter;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpHandshaker;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpHandshakerFactory;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.FourTuple;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.SysctlOptions;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConfig;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConstants;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpTimewaitSock;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConnectionTimers;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpTimerScheduler;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConstants.SHUTDOWN_MASK;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConstants.TCP_INIT_CWND;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConstants.TCP_MSS_DEFAULT;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConstants.TCP_NAGLE_OFF;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpUtils.determineEndSeq;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSequence.after;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSequence.before;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSequence.between;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpOutOps.tcp_oow_rate_limited;

@Slf4j
public abstract class TcpMultiplexer {
    public static final int DEFAULT_MAX_SYN_BACKLOG = 1024;

    /**
     * 返回墙钟秒数(对应 Linux {@code get_seconds()},用于 {@code ts_recent_stamp})。
     * 溢出语义与 Linux 32 位秒戳一致(2106 年回绕)。
     */
    static int nowSeconds() {
        return (int) (System.currentTimeMillis() / 1000L);
    }

    public static final int TCP_STATE_MASK = 0xF;
    public static final int TCP_ACTION_FIN = 1 << TcpConnectionState.TCP_CLOSED.ordinal();
    public static final int[] NEW_STATE = new int[TcpConnectionState.values().length + 1];

    static {
        NEW_STATE[TcpConnectionState.TCP_ESTABLISHED.ordinal() + 1] = TcpConnectionState.FIN_WAIT_1.ordinal() | TCP_ACTION_FIN;
        NEW_STATE[TcpConnectionState.TCP_SYN_SENT.ordinal() + 1] = TcpConnectionState.TCP_CLOSED.ordinal();
        NEW_STATE[TcpConnectionState.TCP_SYN_RECV.ordinal() + 1] = TcpConnectionState.FIN_WAIT_1.ordinal() | TCP_ACTION_FIN;
        NEW_STATE[TcpConnectionState.FIN_WAIT_1.ordinal() + 1] = TcpConnectionState.FIN_WAIT_1.ordinal();
        NEW_STATE[TcpConnectionState.FIN_WAIT_2.ordinal() + 1] = TcpConnectionState.FIN_WAIT_2.ordinal();
        NEW_STATE[TcpConnectionState.TIME_WAIT.ordinal() + 1] = TcpConnectionState.TCP_CLOSED.ordinal();
        NEW_STATE[TcpConnectionState.TCP_CLOSED.ordinal() + 1] = TcpConnectionState.TCP_CLOSED.ordinal();
        NEW_STATE[TcpConnectionState.CLOSE_WAIT.ordinal() + 1] = TcpConnectionState.LAST_ACK.ordinal() | TCP_ACTION_FIN;
        NEW_STATE[TcpConnectionState.LAST_ACK.ordinal() + 1] = TcpConnectionState.LAST_ACK.ordinal();
        NEW_STATE[TcpConnectionState.TCP_LISTEN.ordinal() + 1] = TcpConnectionState.TCP_CLOSED.ordinal();
        NEW_STATE[TcpConnectionState.CLOSING.ordinal() + 1] = TcpConnectionState.CLOSING.ordinal();
    }

    protected final TcpConfig config;
    protected final TcpHandshakerFactory handshakerFactory;
    /**
     * 每连接专属 EL 池 — 对齐 v1 里 backend {@code childGroup} 的角色。新建立的 child sock
     * 在 {@code tcp_v4_syn_recv_sock} 中从 {@code tcpGroup.next()} 取一条 EL 绑定,整个
     * 连接生命周期(状态机 / timer / user channel / backend channel)都跑在该 EL 上。
     * 为 {@code null} 时所有 sock 回退到 TUN channel 的 EL(退化为单线程)。
     */
    protected final EventLoopGroup tcpGroup;
    /**
     * sock 装配钩子 — 必传,构造期 requireNonNull 。{@link #tcp_init_transfer} 调
     * {@link TcpSockInitializer#onEstablished} 让上层(如 netty 子包的 {@code TcpChannelFactory}、
     * ext.backend 子包的 {@code BackendProxyInitializer})创建并挂 {@link TcpSockHandler}。
     * 显式关闭端口传 {@link TcpSockInitializer#DENY}。
     */
    protected final TcpSockInitializer initializer;
    /**
     * 半连接队列 — 读写均在 TUN EL(入站 SYN / SYN-ACK 重传 timer 回调 / 握手失败均
     * 通过 TUN channel EL 调度),{@link java.util.HashMap} 足够。
     */
    protected final Map<FourTuple, TcpRequestSock> synRegistry;
    /**
     * ESTABLISHED 槽位 — 跨 EL 并发:
     * <ul>
     *   <li>{@code moveToEstablished} 在 TUN EL 上 put(握手完成)</li>
     *   <li>{@code __inet_lookup_skb} 在 TUN EL 上 get(每入站包查表)</li>
     *   <li>{@code inet_csk_destroy_sock} 在 <b>sock EL</b> 上 remove(tcp_v4_do_rcv 路径
     *       在 sock 专属 EL 上执行)</li>
     * </ul>
     * 因此必须使用 {@link ConcurrentHashMap}。对齐 v1 {@code Maps.newConcurrentMap()}。
     */
    protected final Map<FourTuple, TcpSock> establishedRegistry;
    /**
     * TIME_WAIT 迷你 bucket 注册表 — 对齐 Linux {@code inet_timewait_sock} 加入的
     * {@code ehash} TW 槽位。键为进入 TIME_WAIT 时的四元组,值为快照后的
     * {@link TcpTimewaitSock};2MSL 到期或收到有效 RST 时由 {@link #inet_twsk_kill} 移除。
     *
     * <p>跨 EL 并发:{@code tcp_time_wait} 在 sock EL 上 put,{@code inet_twsk_kill} 也在
     * sock EL 上 remove,但 {@code __inet_lookup_skb} 在 TUN EL 上 get,需 ConcurrentHashMap。
     */
    protected final Map<FourTuple, TcpTimewaitSock> timewaitRegistry;
    protected final int maxSynBacklog;
    protected TcpSock listenSock;

    protected TcpMultiplexer(TcpConfig config, EventLoopGroup tcpGroup, TcpSockInitializer initializer) {
        this.config = config;
        this.handshakerFactory = new TcpHandshakerFactory(config);
        this.tcpGroup = tcpGroup;
        this.initializer = Objects.requireNonNull(initializer, "initializer");
        this.synRegistry = new HashMap<>();
        this.establishedRegistry = new ConcurrentHashMap<>();
        this.timewaitRegistry = new ConcurrentHashMap<>();
        this.maxSynBacklog = DEFAULT_MAX_SYN_BACKLOG;
        init();
    }

    protected void init() {
        listenSock = init(new TcpSock());
        listenSock.state(TcpConnectionState.TCP_LISTEN);
    }

    protected abstract TcpSock init(TcpSock sk);

    public abstract void tcp_rcv(ChannelHandlerContext net, TcpPacketBuf pkt);

    public abstract void send_reset(ChannelHandlerContext net, TcpPacketBuf pkt, int err);

    public abstract void inet_rtx_syn_ack(ChannelHandlerContext net, TcpSock listenSock, TcpRequestSock req);

    protected abstract TcpRequestSock conn_request(ChannelHandlerContext net, TcpSock listenSock, TcpPacketBuf pkt);

    /**
     * 发 SYN-ACK 响应 — 由 {@link TcpSockInitializer#onRequest} 决定何时调用。实现方
     * (如 {@code Tcp4Multiplexer})在此装 {@code synAckFailureAction} /
     * {@code handshakeCloseListener} 并发送 SYN-ACK,启动 SYN-ACK 重传 timer。
     * synPacket 已由 {@code tcp_v4_conn_request} retain 一次,本方法不再参与 retain。
     *
     * <p><b>契约</b>:本方法只应被调一次;{@code BackendProxyInitializer} 在 backend connect
     * 成功后调用,工厂 / Raw initializer 在 onRequest 默认实现里立即调用,DENY 不调用。
     */
    public abstract void sendSynAck(TcpRequestSock req);

    protected abstract TcpSock syn_recv_sock(ChannelHandlerContext net, TcpSock listenSock, TcpPacketBuf pkt, TcpRequestSock req);

    /**
     * 对齐 Linux {@code __inet_lookup_skb}(net/ipv4/inet_hashtables.c):先查 ESTABLISHED 槽,
     * miss 再查 TIME_WAIT 槽(v2 的 {@link #timewaitRegistry}),最后回退到半连接 /
     * LISTEN。返回 {@link TcpTimewaitSock} 时由 {@code tcp_v4_rcv} 派发到
     * {@code tcp_timewait_state_process}。
     */
    protected SockCommon __inet_lookup_skb(final TcpPacketBuf pkt) {
        final FourTuple key = FourTuple.of(pkt);
        TcpSock established = establishedRegistry.get(key);
        if (established != null) {
            return established;
        }
        TcpTimewaitSock tw = timewaitRegistry.get(key);
        if (tw != null) {
            return tw;
        }
        TcpRequestSock req = synRegistry.get(key);
        if (req != null) {
            return req;
        }
        return listenSock;
    }

    /**
     * SYN_RECV 阶段的逐段校验 — 严格对齐 Linux
     * <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c">tcp_check_req</a>
     * (net/ipv4/tcp_minisocks.c)。
     *
     * <p>处理顺序(与 Linux 一一对应):
     * <ol>
     *   <li>{@code tcp_parse_options} — 解析入站 TS 选项;</li>
     *   <li>PAWS 判定 — {@code th->rst} 不参与 PAWS;</li>
     *   <li>TSECR 范围校验 — {@code rcv_tsecr} 必须落在 {@code [snt_tsval_first, snt_tsval_last]};</li>
     *   <li>纯 SYN 重传分支 — {@code seq == rcv_isn && flg == SYN && !paws_reject}
     *       + {@code tcp_oow_rate_limited(LINUX_MIB_TCPACKSKIPPEDSYNRECV)} 后 {@code inet_rtx_syn_ack};</li>
     *   <li>OOW / PAWS 拒绝分支 — 若 {@code paws_reject || !tcp_in_window(...)} 则
     *       {@code req->rsk_ops->send_ack} 回 Challenge ACK(非 RST 且未限流),
     *       PAWSESTABREJECTED 记 MIB;</li>
     *   <li>TS 推进 PAWS 基线 — {@code saw_tstamp && !after(seq, rcv_nxt)} 时更新 {@code ts_recent};</li>
     *   <li>{@code seq == rcv_isn} → 清 SYN 标志(对齐 Linux 对 "retrans SYN with data" 的截断);</li>
     *   <li>RST / SYN 走 embryonic_reset — 发 {@code tcp_v4_send_reset} 后销毁 req;</li>
     *   <li>非 ACK 段静默丢弃;</li>
     *   <li>ACK# 必须 {@code == snd_isn + 1},否则走 embryonic_reset(v2 在此显式校验,
     *       Linux 则下沉到 child 的 {@code tcp_rcv_state_process});</li>
     *   <li>{@code syn_recv_sock} 建 child → {@code tcp_synack_rtt_meas} RTT 取样 → 返回 child。</li>
     * </ol>
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c">tcp_check_req</a>
     */
    public TcpSock tcp_check_req(ChannelHandlerContext net,
                                 TcpSock listenSock,
                                 TcpPacketBuf pkt,
                                 TcpRequestSock req) {
        final TcpHandshaker handshaker = req.request();

        // (1) tcp_parse_options:仅取 TS 选项(PAWS / TSECR 所需)。
        long[] tsOpt = handshaker.clientTimestamp()
                ? TcpOptionCodec.parseTimestamp(pkt.tcpOptionsSlice())
                : null;
        final boolean sawTstamp = tsOpt != null;
        final int rcvTsval = sawTstamp ? (int) tsOpt[0] : 0;
        final long rcvTsecr = sawTstamp ? tsOpt[1] : 0L;

        // (2) tcp_paws_reject —— th->rst 段不做 PAWS。
        boolean pawsReject = sawTstamp && !pkt.isRst() && handshaker.pawsRejected(rcvTsval);

        // (3) TSECR 范围 —— 对齐 tcp_check_req 中的 snt_tsval_first/last LAND-style 校验。
        //     仅当本端已至少发过一次 SYN-ACK(snt_tsval_last != 0)且对端回显 TSecr 时适用。
        if (sawTstamp && handshaker.sntTsvalLast() != 0L
                && !between((int) handshaker.sntTsvalFirst(), (int) rcvTsecr, (int) handshaker.sntTsvalLast())) {
            TcpMibStats.INSTANCE.inc(TcpMib.TSECRREJECTED);
            return null;
        }

        final int seq = pkt.tcpSeq();
        final int endSeq = determineEndSeq(pkt);
        // 与 Linux 保持同义:flg == TCP_FLAG_SYN(纯 SYN,不带 ACK/RST/FIN)。
        final boolean pureSyn = pkt.isSyn() && !pkt.isAck() && !pkt.isRst() && !pkt.isFin();

        // (4) 纯 SYN 重传 —— 对 3WH 的第一段 SYN 做 SYN-ACK 重发,受 oow_rate_limited 限流。
        if (seq == handshaker.rcvIsn() && pureSyn && !pawsReject) {
            if (tcp_oow_rate_limited(handshaker, pkt)) {
                TcpMibStats.INSTANCE.inc(TcpMib.TCPACKSKIPPEDSYNRECV);
            } else {
                inet_rtx_syn_ack(net, listenSock, req);
                req.num_retrans++;
            }
            return null;
        }

        // (5) OOW / PAWS 拒绝 —— 落窗外或 PAWS 已拒,回 Challenge ACK(对齐 req->rsk_ops->send_ack)。
        //     in-window 区间 = [rcv_nxt, rcv_nxt + tcp_synack_window(req))。
        final int winEnd = handshaker.rcvNxt() + handshaker.synackWindow();
        final boolean inWindow = !before(endSeq, handshaker.rcvNxt()) && !after(seq, winEnd);
        if (pawsReject || !inWindow) {
            if (!pkt.isRst()) {
                if (tcp_oow_rate_limited(handshaker, pkt)) {
                    TcpMibStats.INSTANCE.inc(TcpMib.TCPACKSKIPPEDSYNRECV);
                } else {
                    handshaker.sendChallengeAck(net.channel());
                }
            }
            if (pawsReject) {
                TcpMibStats.INSTANCE.inc(TcpMib.PAWSESTABREJECTED);
            }
            return null;
        }

        // (6) PAWS 基线推进 —— saw_tstamp && !after(seq, rcv_nxt) 时更新 ts_recent。
        if (sawTstamp && !after(seq, handshaker.rcvNxt())) {
            handshaker.updateTsRecent(rcvTsval);
        }

        // (7) seq == rcv_isn:截断 SYN 标志 —— 对应 Linux "flg &= ~TCP_FLAG_SYN" 的语义,
        //     防止下面的 RST|SYN 检查把合法 3WH ACK(可能重带 SYN)误判为 embryonic_reset。
        final boolean hasRst = pkt.isRst();
        final boolean hasSyn = pkt.isSyn() && seq != handshaker.rcvIsn();

        // (8) RST / SYN → embryonic_reset:发 RST 并销毁 req。
        if (hasRst || hasSyn) {
            if (!hasRst) {
                send_reset(net, pkt, -1);
            }
            inet_csk_destroy_sock(req);
            return null;
        }

        // (9) 非 ACK 静默丢弃。
        if (!pkt.isAck()) {
            return null;
        }

        // (10) ACK# 校验 —— v2 在此处显式判定,等价于 Linux 下沉到 child tcp_rcv_state_process 的行为。
        if (pkt.tcpAckNum() != handshaker.sndIsn() + 1) {
            send_reset(net, pkt, -1);
            inet_csk_destroy_sock(req);
            return null;
        }

        // (11) syn_recv_sock + tcp_synack_rtt_meas(Karn's rule:num_retrans == 0 才取样)。
        TcpSock child = syn_recv_sock(net, listenSock, pkt, req);
        if (child != null) {
            tcp_synack_rtt_meas(child, req);
        }
        return child;
    }

    /**
     * 三次握手完成时用 SYN-ACK 首发到 ACK 的时间差作为首个 RTT 样本。
     * Karn's rule:若 SYN-ACK 曾重传(num_retrans > 0),不取样(无法区分 ACK 确认的是哪一次)。
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c">tcp_synack_rtt_meas</a>
     */
    private static void tcp_synack_rtt_meas(TcpSock child, TcpRequestSock req) {
        if (req.num_retrans > 0) {
            return;
        }
        long sntSynackUs = req.request().synAckSentUs();
        if (sntSynackUs == 0L) {
            return;
        }
        long rttUs = (System.nanoTime() / 1_000L) - sntSynackUs;
        if (rttUs <= 0L) {
            return;
        }
        if (!child.hasConnection()) {
            return;
        }
        TcpConnection conn = child.connection();
        if (conn == null || conn.rttEstimator() == null) {
            return;
        }
        conn.rttEstimator().addSample(conn, rttUs);
    }

    protected void addToHalfQueue(final TcpSock listenSock, final TcpRequestSock req) {
        synRegistry.putIfAbsent(req.fourTuple(), req);
    }

    protected void moveToEstablished(final TcpRequestSock req, final TcpSock sock) {
        if (req.synPacket() != null) {
            req.synPacket().release();
            req.synPacket(null);
        }
        synRegistry.remove(req.fourTuple(), req);
        establishedRegistry.put(sock.fourTuple(), sock);
    }

    public void tcp_done(TcpSock tp) {
        if (!tp.hasConnection()) {
            return;
        }
        tp.state(TcpConnectionState.TCP_CLOSED);
        tp.skShutdown(SHUTDOWN_MASK);
        inet_csk_destroy_sock(tp);
    }

    public void inet_csk_destroy_sock(TcpSock sk) {
        if (!sk.hasConnection()) {
            return;
        }
        sk.close();
        establishedRegistry.remove(sk.fourTuple(), sk);
    }

    public void inet_csk_destroy_sock(TcpRequestSock req) {
        // P2.1:销毁前让 initializer 释放 attachment 资源(如 backend state)
        try {
            initializer.onRequestDestroyed(req);
        } catch (Throwable ignore) {
            // 保护:用户 initializer 异常不阻塞 req 销毁
        }
        req.request().cancelRetransmitTimer();
        if (req.connectFuture() != null && req.connectFuture().channel() != null) {
            if (req.handshakeCloseListener() != null) {
                req.connectFuture().channel().closeFuture().removeListener(req.handshakeCloseListener());
            }
            req.connectFuture().channel().close();
        }
        if (req.childChannel() != null && req.childChannel().isOpen()) {
            if (req.handshakeCloseListener() != null) {
                req.childChannel().closeFuture().removeListener(req.handshakeCloseListener());
            }
            req.childChannel().close();
        }
        if (req.synPacket() != null) {
            req.synPacket().release();
            req.synPacket(null);
        }
        synRegistry.remove(req.fourTuple(), req);
    }

    public boolean tcp_close_state(TcpSock sk) {
        if (!sk.hasConnection()) {
            return false;
        }
        int next = NEW_STATE[sk.state().ordinal() + 1];
        int ns = next & TCP_STATE_MASK;
        sk.state(TcpConnectionState.values()[ns]);
        return 0 != (next & TCP_ACTION_FIN);
    }

    public void tcp_time_wait(ChannelHandlerContext ctx, TcpSock tp, TcpConnectionState state) {
        tcp_time_wait(tp, state, config.timeWaitMs());
    }

    /**
     * 对齐 Linux {@code tcp_time_wait}(net/ipv4/tcp_minisocks.c):
     * 从重量级 {@link TcpSock} 中摘出 TIME_WAIT 阶段所需的最小快照构建 {@link TcpTimewaitSock},
     * 注册到 {@link #timewaitRegistry} 并安排 2MSL 定时器;原 {@link TcpSock} 立即销毁,
     * 释放发送 / 接收缓冲、取消所有定时器,腾出 {@link #establishedRegistry} 中的槽位。
     *
     * <p>{@code state} 参数对齐 Linux {@code tcp_time_wait(sk, state, timeo)} 用于
     * 区分 FIN_WAIT_2 / TIME_WAIT 子状态 — 值会写入
     * {@link TcpTimewaitSock#tw_substate},由 {@code tcp_timewait_state_process}
     * 根据该字段分派(FIN_WAIT_2 等对端 FIN,TIME_WAIT 静默重放 ACK)。二者到期行为
     * 均为 {@link #inet_twsk_kill}。
     *
     * <p>迟到段重放 FIN-ACK 的通路由 {@code tcp_timewait_state_process} +
     * {@code TcpOutput.tcp_timewait_send_ack} 承担,共享 TUN 侧 channel,无需 {@link TcpSock}。
     */
    public void tcp_time_wait(TcpSock tp, TcpConnectionState state, long timeoutMs) {
        if (!tp.hasConnection()) {
            return;
        }
        final FourTuple ft = tp.fourTuple();
        final TcpTimewaitSock tw = new TcpTimewaitSock(
                ft,
                tp.channel(),
                tp.rcvNxt(),
                tp.sndNxt(),
                tp.rcvWnd(),
                tp.rcvWscale(),
                tp.timestampEnabled(),
                tp.recentTimestamp() & 0xFFFFFFFFL,
                tp.tsRecentStamp());
        // 对齐 Linux tcp_time_wait(sk, state, timeo):state ∈ {FIN_WAIT_2, TIME_WAIT}
        tw.tw_substate = (state == TcpConnectionState.FIN_WAIT_2)
                ? TcpConnectionState.FIN_WAIT_2
                : TcpConnectionState.TIME_WAIT;

        final long delay = Math.max(timeoutMs, 1L);
        tw.tw_timeout = System.currentTimeMillis() + delay;
        timewaitRegistry.put(ft, tw);
        com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpMibStats.INSTANCE.inc(
                com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpMib.TCPTIMEWAITCREATED);

        final EventLoop el = tp.eventLoop();
        if (el != null) {
            tw.tw_timer = el.schedule(() -> inet_twsk_kill(tw), delay, TimeUnit.MILLISECONDS);
        }

        // 原 TcpSock 下沉为 twsk 后立即销毁:取消所有定时器、释放缓冲、下架 ESTABLISHED 槽
        tp.state(TcpConnectionState.TCP_CLOSED);
        tp.skShutdown(SHUTDOWN_MASK);
        inet_csk_destroy_sock(tp);
    }

    /**
     * 对齐 Linux {@code inet_twsk_kill}(net/ipv4/inet_timewait_sock.c):从 TW bucket 移除,
     * 取消挂起的 2MSL 定时器。线程归属:必须在 twsk 关联 EventLoop 上调用(v2 当前从
     * 事件循环派发任务或在 2MSL 到期处自然触发,均满足)。
     */
    public void inet_twsk_kill(TcpTimewaitSock tw) {
        if (tw == null) {
            return;
        }
        timewaitRegistry.remove(tw.fourTuple(), tw);
        java.util.concurrent.ScheduledFuture<?> f = tw.tw_timer;
        if (f != null && !f.isDone()) {
            f.cancel(false);
        }
        tw.tw_timer = null;
    }

    /**
     * 对齐 Linux {@code inet_twsk_reschedule}(net/ipv4/inet_timewait_sock.c):收到迟到 FIN
     * 重放 ACK 后刷新 2MSL 定时器。
     */
    public void inet_twsk_reschedule(TcpTimewaitSock tw, long timeoutMs) {
        if (tw == null) {
            return;
        }
        java.util.concurrent.ScheduledFuture<?> prev = tw.tw_timer;
        if (prev != null && !prev.isDone()) {
            prev.cancel(false);
        }
        final long delay = Math.max(timeoutMs, 1L);
        tw.tw_timeout = System.currentTimeMillis() + delay;
        final Channel ch = tw.tw_channel;
        if (ch != null && ch.eventLoop() != null) {
            tw.tw_timer = ch.eventLoop().schedule(() -> inet_twsk_kill(tw), delay, TimeUnit.MILLISECONDS);
        }
    }

    protected void tcp_shutdown(TcpSock sk, int how) {
        if (!sk.hasConnection() || (how & TcpConstants.SEND_SHUTDOWN) == 0) {
            return;
        }
        if (sk.state() == TcpConnectionState.TCP_ESTABLISHED
                || sk.state() == TcpConnectionState.CLOSE_WAIT) {
            if (tcp_close_state(sk)) {
                TcpOutput.INSTANCE.tcp_send_fin(sk);
            }
        }
    }

    public void consume(final ChannelHandlerContext ctx, final TcpPacketBuf pkt) {
        tcp_rcv(ctx, pkt);
    }

    public boolean sk_acceptq_is_full() {
        return synRegistry.size() >= maxSynBacklog;
    }

    public boolean write(final FourTuple key, final ByteBuf data) {
        TcpSock sk = establishedRegistry.get(key);
        if (sk == null || !sk.hasConnection() || !sk.state().canSend()) {
            data.release();
            return false;
        }
        enqueueWrite(sk, data, true);
        return true;
    }

    protected int tcp_ack(TcpSock sk, TcpPacketBuf pkt, int flag) {
        if (!sk.hasConnection() || !pkt.isAck()) {
            return 1;
        }
        return TcpAck.tcpAck(sk, pkt, flag);
    }

    /**
     * 对齐 Linux {@code tcp_data_queue} (tcp_input.c:5229) 的七分支判定:
     * <ol>
     *   <li>{@code seq == rcv_nxt} 且窗口 &gt; 0 → 顺序入队 {@code queue_and_out};</li>
     *   <li>{@code seq == rcv_nxt} 且窗口 = 0 且携带 FIN(无 payload)→ 仍接受 FIN;</li>
     *   <li>{@code seq == rcv_nxt} 且窗口 = 0 且非纯 FIN → 按零窗口丢弃;</li>
     *   <li>{@code end_seq <= rcv_nxt} → 完整重传段丢弃(OLD_DATA);</li>
     *   <li>{@code seq >= rcv_nxt + rwnd} → 越窗丢弃(OVERWINDOW);</li>
     *   <li>{@code seq < rcv_nxt < end_seq} → 部分重叠,窗口 0 丢尾否则入队;</li>
     *   <li>其它 → 纯乱序,入 OFO 队列。</li>
     * </ol>
     * FIN 的状态机迁移由 {@code queue_and_out} 内 {@code tcp_fin_state_process} 承担。
     */
    protected int tcp_data_queue(ChannelHandlerContext ctx, TcpSock sk, TcpPacketBuf pkt) {
        if (!sk.hasConnection()) {
            return 0;
        }
        final int seq = pkt.tcpSeq();
        final int endSeq = com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpUtils.determineEndSeq(pkt);
        // 纯 ACK / SYN-only 段已在 pre-validator 或状态机上游剥离;此处若无 seq 消耗直接返回
        if (seq == endSeq) {
            return 0;
        }
        if (!sk.state().canReceive()) {
            return 0;
        }

        final int rcvNxt = sk.rcvNxt();
        final int rwnd = sk.tcp_receive_window();

        if (seq == rcvNxt) {
            if (rwnd == 0) {
                if (pkt.tcpPayloadLength() == 0 && pkt.isFin()) {
                    // 即使通告零窗口,仍接受裸 FIN(v1 L1617-1618)
                    queue_and_out(ctx, sk, pkt);
                } else {
                    tcp_out_of_window(sk, SkbDropReason.SKB_DROP_REASON_TCP_ZEROWINDOW);
                }
                return 0;
            }
            queue_and_out(ctx, sk, pkt);
            return 0;
        }

        if (!after(endSeq, rcvNxt)) {
            // 完整重传段(纯 dup)
            tcp_out_of_window(sk, SkbDropReason.SKB_DROP_REASON_TCP_OLD_DATA);
            return 0;
        }

        if (!before(seq, rcvNxt + rwnd)) {
            // seq 在窗口外(零窗口探测等)
            tcp_out_of_window(sk, SkbDropReason.SKB_DROP_REASON_TCP_OVERWINDOW);
            return 0;
        }

        if (before(seq, rcvNxt)) {
            // 部分重叠: seq < rcv_nxt < end_seq
            if (rwnd == 0) {
                tcp_out_of_window(sk, SkbDropReason.SKB_DROP_REASON_TCP_ZEROWINDOW);
            } else {
                queue_and_out(ctx, sk, pkt);
            }
            return 0;
        }

        // 纯乱序: seq > rcv_nxt && seq < rcv_nxt + rwnd
        tcp_data_queue_ofo(sk, pkt);
        return 0;
    }

    /**
     * 等价 Linux {@code queue_and_out} (tcp_input.c:5150) — 将段交付上层,
     * 推进 {@code rcv_nxt},若段自身或被 FIN 填洞后的 OFO 排水释放出 FIN,
     * 则触发 {@code tcp_fin} 状态转移;最后按快慢判定 ACK 调度策略。
     *
     * <p>v2 拆分:数据入 {@link TcpReceiveBuffer}(含 OFO FIN 感知),
     * 是否触发 {@code tcp_fin} 由 {@link TcpReceiveBuffer.OfferResult#finDelivered}
     * 决定 — 避免乱序 FIN 提前进入 CLOSE_WAIT。
     */
    private void queue_and_out(ChannelHandlerContext ctx, TcpSock sk, TcpPacketBuf pkt) {
        final int priorRcvNxt = sk.rcvNxt();
        final int payloadLen  = pkt.tcpPayloadLength();
        final int seq         = pkt.tcpSeq();
        final int endSeq      = com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpUtils.determineEndSeq(pkt);
        final boolean fin     = pkt.isFin();

        if (payloadLen > 0) {
            sk.onDataReceived(payloadLen);
        }

        final ByteBuf payload = pkt.tcpPayloadSlice();
        final ByteBuf segment = payloadLen > 0
                ? payload.retainedSlice()
                : io.netty.buffer.Unpooled.EMPTY_BUFFER;

        final TcpReceiveBuffer.OfferResult r = sk.receiveBuffer()
                .offer(seq, endSeq, priorRcvNxt, segment, fin);
        sk.rcvNxt(r.rcvNxt);
        if (r.hasDsack()) {
            // 对齐 Linux tcp_dsack_set:接收到已在 RCV.NXT 之前的整段 → 下一次 ACK 携带 DSACK
            sk.setDsack(r.dsackStart, r.dsackEnd);
            sk.enterQuickAckMode(TcpConstants.TCP_MAX_QUICKACKS);
            sk.addAckPending(TcpConstants.ACK_NOW);
        }

        /*
         * rcvPaused(autoRead=false)时保留数据在 receiveBuffer,不 drain 给 userChannel;
         * rcv_nxt 已随上面 offer 推进,但由于 receiveBuffer 不清空,
         * tcp_receive_window() = max(0, rcv_wup + rcv_wnd - rcv_nxt) 会随 rcv_nxt 前进
         * 而在下一次 tcp_select_window 触发窗口收缩,对端自然感知反压。对齐 Linux
         * "应用未读 socket → tp->rcv_wnd 缩窗" 语义。
         */
        if (sk.receiveBuffer().isReadable() && !sk.rcvPaused()) {
            consume(sk, sk.receiveBuffer().readAll());
        }

        if (r.finDelivered) {
            // 当前段或先前 OFO 中的 FIN 已抵达 rcv_nxt,执行状态迁移并回 ACK。
            tcp_fin(ctx, sk);
            return;
        }

        // ACK 调度: 乱序恢复(OFO 被排空)或本段非顺位 → ACK_NOW + quickack;否则 schedule。
        if (payloadLen > 0) {
            boolean ofoDrain = r.rcvNxt != priorRcvNxt + payloadLen;
            if (ofoDrain || seq != priorRcvNxt) {
                sk.enterQuickAckMode(TcpConstants.TCP_INIT_CWND);
                sk.addAckPending(TcpConstants.ACK_NOW);
            } else {
                sk.addAckPending(TcpConstants.ACK_SCHED);
            }
        }
    }

    /**
     * 对齐 Linux {@code tcp_fin}(tcp_input.c:4318)全状态 switch。
     *
     * <p>调用约定:{@code rcv_nxt} 必须已由上游推进到 FIN 之后 —
     * {@link #queue_and_out} 通过 {@code receiveBuffer.offer} 推进,
     * {@code tcp_rcv_state_process} 非数据路径(CLOSE_WAIT/CLOSING/LAST_ACK 的
     * 重传 FIN)走过时不需要推进(seq &lt; rcv_nxt)。
     *
     * <ul>
     *   <li><b>SYN_RECV / ESTABLISHED</b>:迁移至 {@link TcpConnectionState#CLOSE_WAIT},
     *       置 pingpong,发 ACK。v2 正常路径下 SYN_RECV + FIN+ACK 会先由
     *       {@code tcp_rcv_state_process} 的 SYN_RECV 分支 {@code tcp_try_establish} 迁入
     *       ESTABLISHED,再进入 {@code tcp_data_queue} 命中 ESTABLISHED case;与 Linux
     *       {@code tcp_rcv_state_process} 先 {@code tcp_set_state(ESTABLISHED)} fall-through
     *       到末尾 {@code tcp_data_queue} 的路径等价。两侧 SYN_RECV case 均作防御性合并保留。</li>
     *   <li><b>CLOSE_WAIT / CLOSING / LAST_ACK</b>:重传 FIN,状态保持,发 ACK。</li>
     *   <li><b>FIN_WAIT_1</b>:simultaneous close,先发 ACK 再迁 {@link TcpConnectionState#CLOSING}
     *       (对齐 Linux tcp_input.c:4355)。</li>
     *   <li><b>FIN_WAIT_2</b>:4-way close 完成,发最后 ACK 并 {@link #tcp_time_wait}
     *       (sk 随后被销毁,调用方不应再触碰)。</li>
     *   <li><b>default</b>:LISTEN / SYN_SENT / CLOSED / TIME_WAIT — v2 架构下不应到达,no-op。</li>
     * </ul>
     */
    protected void tcp_fin(ChannelHandlerContext ctx, TcpSock sk) {
        sk.addShutdown(TcpConstants.RCV_SHUTDOWN);
        final TcpConnectionState prevState = sk.state();
        switch (prevState) {
            case TCP_SYN_RECV:
            case TCP_ESTABLISHED:
                sk.state(TcpConnectionState.CLOSE_WAIT);
                sk.enterPingpongMode();
                TcpOutput.INSTANCE.tcp_send_ack(sk);
                notifyPeerFin(sk);
                return;
            case CLOSE_WAIT:
            case CLOSING:
            case LAST_ACK:
                TcpOutput.INSTANCE.tcp_send_ack(sk);
                return;
            case FIN_WAIT_1:
                TcpOutput.INSTANCE.tcp_send_ack(sk);
                sk.state(TcpConnectionState.CLOSING);
                notifyPeerFin(sk);
                return;
            case FIN_WAIT_2:
                TcpOutput.INSTANCE.tcp_send_ack(sk);
                notifyPeerFin(sk);
                tcp_time_wait(ctx, sk, TcpConnectionState.TIME_WAIT);
                return;
            default:
                // TCP_LISTEN / TCP_SYN_SENT / TCP_CLOSED / TIME_WAIT — 不应到达,防御性 no-op
                return;
        }
    }

    private static void notifyPeerFin(TcpSock sk) {
        TcpSockHandler h = sk.handler();
        if (h != null) {
            try {
                h.onPeerFin();
            } catch (Throwable ignore) {
                // 保护:用户 handler 异常不影响状态机推进
            }
        }
    }

    /**
     * 越窗 / 过旧段统一丢弃路径 — 对齐 Linux {@code out_of_window}(tcp_input.c:5202)
     * 和 {@code NET_INC_STATS(net, LINUX_MIB_OUTOFWINDOWICMPS)} + 尾部
     * {@code kfree_skb_reason(skb, reason)} 投递。
     */
    protected void tcp_out_of_window(TcpSock sk, int reason) {
        sk.enterQuickAckMode(TcpConstants.TCP_MAX_QUICKACKS);
        sk.addAckPending(TcpConstants.ACK_SCHED);
        com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpMibStats.INSTANCE.inc(
                com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpMib.OUTOFWINDOWICMPS);
        com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpMibStats.INSTANCE.incDrop(reason);
    }

    /**
     * 纯乱序段(seq &gt; rcv_nxt) 入 OFO 队列(等价 Linux {@code tcp_data_queue_ofo}
     * tcp_input.c:5055)。v2 通过 {@link TcpReceiveBuffer#offerOfo} 完成:
     * <ul>
     *   <li>保留 FIN 语义以便后续填洞时重放 {@code tcp_fin};</li>
     *   <li>受 {@link TcpReceiveBuffer#OFO_MAX_BYTES} 内存预算限制,超限时先 prune 再判定。</li>
     * </ul>
     * 入队后统一 quickack + ACK_NOW(对齐 Linux OFO 到达立即回 DSACK 行为)。
     */
    protected void tcp_data_queue_ofo(TcpSock sk, TcpPacketBuf pkt) {
        final int seq     = pkt.tcpSeq();
        final int endSeq  = com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpUtils.determineEndSeq(pkt);
        final boolean fin = pkt.isFin();

        if (seq == endSeq) {
            return;
        }

        final ByteBuf payload    = pkt.tcpPayloadSlice();
        final int     payloadLen = payload.readableBytes();
        final ByteBuf segment    = payloadLen > 0
                ? payload.retainedSlice()
                : io.netty.buffer.Unpooled.EMPTY_BUFFER;

        TcpReceiveBuffer.OfoResult r = sk.receiveBuffer().offerOfo(seq, endSeq, segment, fin);
        if (r.hasDsack()) {
            // 对齐 Linux tcp_data_queue_ofo → tcp_dsack_set (Case 3 of RFC 2883):
            // 新 OFO 段与已存 OFO 段重叠时,重复区段通过下一次 ACK 首块以 DSACK 通告。
            sk.setDsack(r.dsackStart, r.dsackEnd);
        }
        if (!r.queued && !r.hasDsack()) {
            // 预算耗尽或空段丢弃,且无 DSACK 需要回 — 维持接收窗口外语义不变
            return;
        }

        // OFO 段抵达 或 产生了 DSACK:立即 quickack 并安排 ACK_NOW 以触发 SACK/DSACK 通告
        sk.enterQuickAckMode(TcpConstants.TCP_MAX_QUICKACKS);
        sk.addAckPending(TcpConstants.ACK_NOW);
    }

    protected void tcp_data_snd_check(TcpSock sk) {
        tcp_push_pending_frames(sk);
    }

    protected void tcp_push_pending_frames(TcpSock sk) {
        if (sk.hasConnection() && sk.tcpSendHead() != null) {
            boolean needProbe = TcpOutput.INSTANCE.tcp_write_xmit(sk, sk.mss(), TCP_NAGLE_OFF, 0);
            if (needProbe) {
                armProbe0(sk);
            }
        }
    }

    protected static boolean tcp_sequence_acceptable(TcpSock sk, TcpPacketBuf pkt) {
        int seq = pkt.tcpSeq();
        int segLen = pkt.tcpPayloadLength() + (pkt.isSyn() ? 1 : 0) + (pkt.isFin() ? 1 : 0);
        int endSeq = seq + segLen;
        int rcvWup = sk.rcvWup();
        int rcvWndEnd = sk.rcvNxt() + sk.tcp_receive_window();

        if (before(endSeq, rcvWup)) {
            return false;
        }
        if (after(endSeq, rcvWndEnd)) {
            return !after(seq, rcvWndEnd);
        }
        return true;
    }

    protected static void tcp_init_wl(TcpSock sk, int seq) {
        if (sk.hasConnection()) {
            sk.sndWl1(seq);
        }
    }

    protected void tcp_init_transfer(TcpSock sk) {
        if (sk == null) {
            return;
        }
        sk.probeTimerAction(() -> tcp_probe_timer(sk));
        sk.keepaliveTimerAction(() -> tcp_keepalive_timer(sk));

        /*
         * 统一走 initializer.onEstablished(sk, this) — initializer 构造期已 requireNonNull:
         * - TcpChannelFactory:创建 TcpChannel,用户 pipeline 接管 payload
         * - BackendProxyInitializer (ext.backend):backend 透传,挂 BackendProxyHandler 并装反向适配器
         * - TcpSockInitializer.DENY:onRequest 阶段已直接发 RST 销毁 req,此路径不会触发
         */
        initializer.onEstablished(sk, this);
        armKeepalive(sk, sk.keepaliveTimeMs());
    }

    protected static int tcp_initialize_rcv_mss(TcpSock sk) {
        if (!sk.hasConnection()) {
            return TCP_MSS_DEFAULT;
        }
        int mss = sk.mss();
        int hint = Math.min(mss, sk.rcvWnd() / 2);
        hint = Math.min(hint, TCP_INIT_CWND * mss);
        return Math.max(hint, TCP_MSS_DEFAULT);
    }

    protected void tcp_ack_snd_check(TcpSock sk) {
        if (!sk.hasAckPending(TcpConstants.ACK_SCHED | TcpConstants.ACK_NOW)) {
            return;
        }
        if ((after(sk.rcvNxt(), sk.rcvWup() + sk.rcvMss()) && sk.rcvWnd() >= sk.tcp_receive_window())
                || sk.inQuickAckMode()
                || sk.hasAckPending(TcpConstants.ACK_NOW)) {
            TcpOutput.INSTANCE.tcp_send_ack(sk);
            return;
        }
        tcp_send_delayed_ack(sk);
    }

    protected void tcp_send_delayed_ack(TcpSock sk) {
        long ato = Math.max(1L, sk.ackTimeoutMs());
        if (sk.inPingpongMode()) {
            ato = Math.max(ato, config.delayedAckMs());
        }
        sk.addAckPending(TcpConstants.ACK_SCHED | TcpConstants.ACK_TIMER);
        TcpTimerScheduler.INSTANCE.scheduleDelayedAck(sk, ato, () -> tcp_delack_timer(sk));
    }

    protected void tcp_delack_timer(TcpSock sk) {
        if (!sk.hasConnection() || !sk.hasAckPending(TcpConstants.ACK_TIMER)) {
            return;
        }
        sk.clearAckPending(TcpConstants.ACK_TIMER);
        if (!sk.hasAckPending(TcpConstants.ACK_SCHED)) {
            return;
        }
        if (!sk.inPingpongMode()) {
            sk.ackTimeoutMs(Math.min(sk.ackTimeoutMs() << 1, sk.rtoMs()));
        } else {
            sk.exitPingpongMode();
            sk.ackTimeoutMs(TcpConstants.TCP_ATO_MIN_MS);
        }
        TcpOutput.INSTANCE.tcp_send_ack(sk);
    }

    protected void armProbe0(TcpSock sk) {
        if (!sk.hasConnection() || sk.packetsOut() != 0 || sk.tcpSendHead() == null) {
            return;
        }
        TcpTimerScheduler.INSTANCE.scheduleWriteTimer(
                sk,
                com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TimerType.ZERO_WINDOW_PROBE,
                sk.tcpProbe0BaseMs(),
                () -> tcp_probe_timer(sk)
        );
    }

    protected void tcp_probe_timer(TcpSock sk) {
        if (!sk.hasConnection()) {
            return;
        }
        if (sk.packetsOut() > 0 || sk.tcpSendHead() == null) {
            sk.resetProbeState();
            return;
        }

        long now = com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpClock.tcp_jiffies32();
        if (sk.probesTstampMs() == 0L) {
            sk.probesTstampMs(now);
        } else if (sk.userTimeoutMs() > 0 && now - sk.probesTstampMs() >= sk.userTimeoutMs()) {
            sk.skErr(110);
            TcpOutput.INSTANCE.tcp_send_reset(sk);
            tcp_done(sk);
            return;
        }

        if (sk.probesOut() >= TcpConstants.TCP_RETRIES2) {
            sk.skErr(110);
            TcpOutput.INSTANCE.tcp_send_reset(sk);
            tcp_done(sk);
            return;
        }

        long timeout = TcpOutput.INSTANCE.tcp_send_probe0(sk);
        if (timeout > 0L) {
            TcpTimerScheduler.INSTANCE.scheduleWriteTimer(
                    sk,
                    com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TimerType.ZERO_WINDOW_PROBE,
                    timeout,
                    () -> tcp_probe_timer(sk)
            );
        }
    }

    protected void armKeepalive(TcpSock sk, long delayMs) {
        if (!sk.hasConnection()
                || !sk.keepaliveEnabled()
                || sk.state() == TcpConnectionState.TIME_WAIT
                || sk.state() == TcpConnectionState.TCP_CLOSED
                || sk.state() == TcpConnectionState.TCP_LISTEN
                || sk.state() == TcpConnectionState.TCP_SYN_RECV) {
            return;
        }
        TcpTimerScheduler.INSTANCE.scheduleKeepalive(sk, Math.max(delayMs, 1L), () -> tcp_keepalive_timer(sk));
    }

    protected void tcp_keepalive_timer(TcpSock sk) {
        if (!sk.hasConnection() || !sk.keepaliveEnabled()) {
            return;
        }

        if (sk.state() == TcpConnectionState.FIN_WAIT_2) {
            return;
        }

        if (sk.packetsOut() > 0 || sk.tcpSendHead() != null) {
            armKeepalive(sk, sk.keepaliveTimeMs());
            return;
        }

        long elapsed = sk.keepaliveElapsedMs();
        if (elapsed < sk.keepaliveTimeMs()) {
            armKeepalive(sk, sk.keepaliveTimeMs() - elapsed);
            return;
        }

        long userTimeout = sk.userTimeoutMs();
        if ((userTimeout > 0L && elapsed >= userTimeout && sk.probesOut() > 0)
                || (userTimeout == 0L && sk.probesOut() >= sk.keepaliveProbes())) {
            sk.skErr(110);
            TcpOutput.INSTANCE.tcp_send_reset(sk);
            tcp_done(sk);
            return;
        }

        int err = TcpOutput.INSTANCE.tcp_write_wakeup(sk, 1);
        long next;
        if (err <= 0) {
            sk.probesOut(sk.probesOut() + 1);
            next = sk.keepaliveIntvlMs();
        } else {
            next = TcpConstants.TCP_RESOURCE_PROBE_INTERVAL_MS;
        }
        armKeepalive(sk, next);
    }

    protected void consume(TcpSock sk, ByteBuf data) {
        /*
         * P1.3 单一出口:sink 统一走 sock.handler()。listenSock 不经本路径,
         * 但 handler==null(destroy 途中、异常装配)时防御性 release,不泄露。
         */
        TcpSockHandler handler = sk == null ? null : sk.handler();
        if (handler == null) {
            data.release();
            return;
        }
        handler.onInboundData(data);
    }

    public void enqueueWrite(TcpSock sk, ByteBuf data, boolean flush) {
        final Runnable task = () -> {
            if (!sk.hasConnection() || !sk.state().canSend()) {
                data.release();
                return;
            }
            sk.tcp_queue_skb(new TcpSkb(
                    data,
                    sk.writeSeq(),
                    data.readableBytes(),
                    (byte) TcpConstants.TCPHDR_ACK,
                    0L));
            if (flush) {
                tcp_push_pending_frames(sk);
            }
        };

        final EventLoop owner = sk.eventLoop();
        if (owner != null && !owner.inEventLoop()) {
            owner.execute(task);
        } else {
            task.run();
        }
    }

}
