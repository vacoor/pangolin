package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.codec.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpOptionCodec;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.hook.TcpSockHandler;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.hook.TcpSockInitializer;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import lombok.extern.slf4j.Slf4j;

import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConstants.SHUTDOWN_MASK;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConstants.TCP_INIT_CWND;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConstants.TCP_MSS_DEFAULT;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConstants.TCP_NAGLE_OFF;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpUtils.determineEndSeq;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSequence.after;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSequence.before;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSequence.between;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpOutOps.oowRateLimited;

/**
 * v2 TCP 栈入站段分派器 + FSM 主入口。R4.2b-3 重命名自 {@code TcpMultiplexer}。
 *
 * <p><b>职责</b>:
 * <ul>
 *   <li>入站路由(abstract):{@link #rcv} / {@link #send_reset} /
 *       {@link #inet_rtx_syn_ack} / {@link #conn_request} / {@link #sendSynAck} /
 *       {@link #syn_recv_sock},由 {@link Ipv4SegmentDispatcher} 实现;</li>
 *   <li>FSM 处理(concrete,R4.2b-4 下沉):{@code checkReq} / {@code ackIncoming} /
 *       {@code dataQueue} / {@code finIncoming} / {@code outOfWindow} 等;</li>
 *   <li>per-sock 装配:{@link #configure} 建立 {@link TcpSock#multiplexer()} 反向引用
 *       + 创建 {@link Sender} / {@link Receiver}。</li>
 * </ul>
 *
 * <p><b>继承关系</b>:{@code extends} {@link TcpStack}(R4.2b-3 暂留,R4.2b-4 改组合)。
 * registries / 全局组件 / 生命周期 API 均从 {@link TcpStack} 继承。
 *
 * <p><b>架构三元</b>(对齐 gVisor endpoint + sender + receiver):
 * <pre>
 *   TcpSock (= endpoint: 控制块 + FSM)
 *     ├── sender   (Sender: cwnd/rto/push/retransmit 的统一入口)
 *     └── receiver (Receiver: rcvWnd/OFO/quickack 的统一入口)
 * </pre>
 *
 * <p><b>子类</b>:当前只有 {@link Ipv4SegmentDispatcher}(IPv4);IPv6 未实现。
 */
@Slf4j
public abstract class SegmentDispatcher extends TcpStack {

    protected SegmentDispatcher(TcpConfig config, EventLoopGroup tcpGroup, TcpSockInitializer initializer) {
        super(config, tcpGroup, initializer);
        init();
    }

    protected void init() {
        TcpSock listenSk = init(new TcpSock());
        listenSk.state(TcpConnectionState.TCP_LISTEN);
        this.listener = new Listener(listenSk, DEFAULT_MAX_SYN_BACKLOG);
        this.lookup = new SockLookup(establishedRegistry, timewaitRegistry, listener);
    }

    /**
     * Sock 装配钩子 — 所有 sock(listen / child / established)创建后必须经过本方法。
     * 默认实现:调 {@link #configure(TcpSock)} 注入 multiplexer / sender / receiver
     * 反向引用。子类若需要额外装配(如 IPv4 / IPv6 特定字段),应 {@code super.init(sk)}
     * 后再补自身逻辑。
     */
    protected TcpSock init(TcpSock sk) {
        return configure(sk);
    }

    /**
     * Per-sock 注入 per-stack 服务。建立 {@link TcpSock#multiplexer()} 反向引用,
     * 创建 {@link Sender} / {@link Receiver} 并挂入 sock。本方法由 {@link #init(TcpSock)}
     * 调用,外部代码通常不需要直接调。幂等 — 多次调用会覆盖 sender/receiver,但实际
     * 调用路径(listen sock / tcp_v4_syn_recv_sock)保证只经过一次。
     *
     * <p>R4.2b-2:configure 留在 SegmentDispatcher 而非 TcpStack,因为依赖
     * {@code sk.multiplexer(this)} 的 'this' 是 SegmentDispatcher 类型。R4.2b-3 重命名
     * {@code sk.multiplexer} → {@code sk.stack} 后可上移。
     */
    public TcpSock configure(TcpSock sk) {
        if (sk != null) {
            sk.multiplexer(this);
            // 幂等:createChild 可能已装配 sender/receiver 用于预填充字段(R2.3/R3.2),
            // 本方法不重建,避免覆盖已填好的状态。
            if (sk.sender() == null) sk.sender(new Sender(sk));
            if (sk.receiver() == null) sk.receiver(new Receiver(sk));
        }
        return sk;
    }

    public abstract void rcv(ChannelHandlerContext net, TcpPacketBuf pkt);

    public abstract void send_reset(ChannelHandlerContext net, TcpPacketBuf pkt, int err);

    public abstract void inet_rtx_syn_ack(ChannelHandlerContext net, TcpSock listenSock, TcpRequestSock req);

    protected abstract TcpRequestSock conn_request(ChannelHandlerContext net, TcpSock listenSock, TcpPacketBuf pkt);

    /**
     * 发 SYN-ACK 响应 — 由 {@link TcpSockInitializer#onRequest} 决定何时调用。实现方
     * (如 {@code Ipv4SegmentDispatcher})在此装 {@code synAckFailureAction} /
     * {@code handshakeCloseListener} 并发送 SYN-ACK,启动 SYN-ACK 重传 timer。
     * synPacket 已由 {@code tcp_v4_conn_request} retain 一次,本方法不再参与 retain。
     *
     * <p><b>契约</b>:本方法只应被调一次;{@code TcpPassthroughInitializer} 在 backend connect
     * 成功后调用,工厂 / Raw initializer 在 onRequest 默认实现里立即调用,DENY 不调用。
     */
    public abstract void sendSynAck(TcpRequestSock req);

    protected abstract TcpSock syn_recv_sock(ChannelHandlerContext net, TcpSock listenSock, TcpPacketBuf pkt, TcpRequestSock req);

    /**
     * 对齐 Linux {@code __inet_lookup_skb}(net/ipv4/inet_hashtables.c):先查 ESTABLISHED 槽,
     * miss 再查 TIME_WAIT 槽,最后回退到半连接 / LISTEN。返回 {@link TcpTimewaitSock}
     * 时由 {@code tcp_v4_rcv} 派发到 {@code timewaitStateProcess}。
     *
     * <p>R4.2b-1:实现下沉到 {@link SockLookup},本方法仅作 delegate 保留兼容。
     * 后续 R4.2b-3 随 {@code SegmentDispatcher} 抽出一并删除。
     */
    protected SockCommon __inet_lookup_skb(final TcpPacketBuf pkt) {
        return lookup.lookup(pkt);
    }

    /**
     * SYN_RECV 阶段的逐段校验 — 严格对齐 Linux
     * <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c">checkReq</a>
     * (net/ipv4/tcp_minisocks.c)。
     *
     * <p>处理顺序(与 Linux 一一对应):
     * <ol>
     *   <li>{@code tcp_parse_options} — 解析入站 TS 选项;</li>
     *   <li>PAWS 判定 — {@code th->rst} 不参与 PAWS;</li>
     *   <li>TSECR 范围校验 — {@code rcv_tsecr} 必须落在 {@code [snt_tsval_first, snt_tsval_last]};</li>
     *   <li>纯 SYN 重传分支 — {@code seq == rcv_isn && flg == SYN && !paws_reject}
     *       + {@code oowRateLimited(LINUX_MIB_TCPACKSKIPPEDSYNRECV)} 后 {@code inet_rtx_syn_ack};</li>
     *   <li>OOW / PAWS 拒绝分支 — 若 {@code paws_reject || !tcp_in_window(...)} 则
     *       {@code req->rsk_ops->send_ack} 回 Challenge ACK(非 RST 且未限流),
     *       PAWSESTABREJECTED 记 MIB;</li>
     *   <li>TS 推进 PAWS 基线 — {@code saw_tstamp && !after(seq, rcv_nxt)} 时更新 {@code ts_recent};</li>
     *   <li>{@code seq == rcv_isn} → 清 SYN 标志(对齐 Linux 对 "retrans SYN with data" 的截断);</li>
     *   <li>RST / SYN 走 embryonic_reset — 发 {@code tcp_v4_send_reset} 后销毁 req;</li>
     *   <li>非 ACK 段静默丢弃;</li>
     *   <li>ACK# 必须 {@code == snd_isn + 1},否则走 embryonic_reset(v2 在此显式校验,
     *       Linux 则下沉到 child 的 {@code rcvStateProcess});</li>
     *   <li>{@code syn_recv_sock} 建 child → {@code synackRttMeas} RTT 取样 → 返回 child。</li>
     * </ol>
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c">checkReq</a>
     */
    public TcpSock checkReq(ChannelHandlerContext net,
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

        // (3) TSECR 范围 —— 对齐 checkReq 中的 snt_tsval_first/last LAND-style 校验。
        //     仅当本端已至少发过一次 SYN-ACK(snt_tsval_last != 0)且对端回显 TSecr 时适用。
        if (sawTstamp && handshaker.sntTsvalLast() != 0L
                && !between((int) handshaker.sntTsvalFirst(), (int) rcvTsecr, (int) handshaker.sntTsvalLast())) {
            mib.inc(TcpMib.TSECRREJECTED);
            return null;
        }

        final int seq = pkt.tcpSeq();
        final int endSeq = determineEndSeq(pkt);
        // 与 Linux 保持同义:flg == TCP_FLAG_SYN(纯 SYN,不带 ACK/RST/FIN)。
        final boolean pureSyn = pkt.isSyn() && !pkt.isAck() && !pkt.isRst() && !pkt.isFin();

        // (4) 纯 SYN 重传 —— 对 3WH 的第一段 SYN 做 SYN-ACK 重发,受 oow_rate_limited 限流。
        if (seq == handshaker.rcvIsn() && pureSyn && !pawsReject) {
            if (oowRateLimited(handshaker, pkt)) {
                mib.inc(TcpMib.TCPACKSKIPPEDSYNRECV);
            } else {
                inet_rtx_syn_ack(net, listener.listenSock, req);
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
                if (oowRateLimited(handshaker, pkt)) {
                    mib.inc(TcpMib.TCPACKSKIPPEDSYNRECV);
                } else {
                    handshaker.sendChallengeAck(net.channel());
                }
            }
            if (pawsReject) {
                mib.inc(TcpMib.PAWSESTABREJECTED);
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

        // (10) ACK# 校验 —— v2 在此处显式判定,等价于 Linux 下沉到 child rcvStateProcess 的行为。
        if (pkt.tcpAckNum() != handshaker.sndIsn() + 1) {
            send_reset(net, pkt, -1);
            inet_csk_destroy_sock(req);
            return null;
        }

        // (11) syn_recv_sock + synackRttMeas(Karn's rule:num_retrans == 0 才取样)。
        TcpSock child = syn_recv_sock(net, listener.listenSock, pkt, req);
        if (child != null) {
            Listener.synackRttMeas(child, req);
        }
        return child;
    }

    public void consume(final ChannelHandlerContext ctx, final TcpPacketBuf pkt) {
        rcv(ctx, pkt);
    }

    public boolean write(final FourTuple key, final ByteBuf data) {
        TcpSock sk = establishedRegistry.get(key);
        if (sk == null || !sk.hasConnection() || !sk.state().canSend()) {
            data.release();
            return false;
        }
        sendmsg(sk, data, true);
        return true;
    }


    protected static void initWl(TcpSock sk, int seq) {
        if (sk.hasConnection()) {
            sk.sndWl1(seq);
        }
    }

    protected void initTransfer(TcpSock sk) {
        if (sk == null) {
            return;
        }
        sk.probeTimerAction(sk.sender()::probeTimer);
        sk.keepaliveTimerAction(sk.sender()::keepaliveTimer);

        /*
         * 统一走 initializer.onEstablished(sk, this) — initializer 构造期已 requireNonNull:
         * - TcpChannelInitializer:创建 TcpChannel,用户 pipeline 接管 payload
         * - TcpPassthroughInitializer (ext.backend):backend 透传,挂 TcpPassthroughHandler 并装反向适配器
         * - TcpSockInitializer.DENY:onRequest 阶段已直接发 RST 销毁 req,此路径不会触发
         */
        initializer.onEstablished(sk, this);
        sk.sender().armKeepalive(sk.keepaliveTimeMs());
    }

    protected static int initializeRcvMss(TcpSock sk) {
        if (!sk.hasConnection()) {
            return TCP_MSS_DEFAULT;
        }
        int mss = sk.mss();
        int hint = Math.min(mss, sk.rcvWnd() / 2);
        hint = Math.min(hint, TCP_INIT_CWND * mss);
        return Math.max(hint, TCP_MSS_DEFAULT);
    }


    protected void consume(TcpSock sk, ByteBuf data) {
        /*
         * P1.3 单一出口:sink 统一走 sock.handler()。listener.listenSock 不经本路径,
         * 但 handler==null(destroy 途中、异常装配)时防御性 release,不泄露。
         */
        TcpSockHandler handler = sk == null ? null : sk.handler();
        if (handler == null) {
            data.release();
            return;
        }
        handler.onInboundData(data);
    }

    /**
     * 应用层 payload 入发送队列入口 — delegate 到 {@link Sender#sendmsg}(R4.2b-4e 下沉)。
     * 保留 Dispatcher 层入口给 user API({@link #write})和测试 harness 用。
     */
    public void sendmsg(TcpSock sk, ByteBuf data, boolean flush) {
        sk.sender().sendmsg(data, flush);
    }

}
