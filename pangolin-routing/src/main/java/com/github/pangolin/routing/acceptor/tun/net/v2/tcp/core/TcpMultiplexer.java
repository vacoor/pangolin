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
 * v2 TCP 栈顶层容器。持有 per-stack 的全部栈级资源:
 * <ul>
 *   <li>{@link #retransmitter} — 重传 / TLP / RTO 调度(per-stack 独立)</li>
 *   <li>{@link #output} — 出包器(per-stack 独立 Challenge ACK 桶)</li>
 *   <li>{@link #mib} — MIB 计数器(per-stack 独立)</li>
 *   <li>{@link #handshakerFactory} — 握手器工厂</li>
 *   <li>{@link #initializer} — 装配钩子({@link com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.hook.TcpSockInitializer})</li>
 *   <li>注册表三件套:{@link #listener.synRegistry} / {@link #establishedRegistry} / {@link #timewaitRegistry}</li>
 * </ul>
 *
 * <p><b>Sock 装配</b>:所有 sock(listen / child / established)创建后经
 * {@link #configure(TcpSock)} 统一注入 {@link TcpSock#multiplexer} +
 * {@link TcpSock#sender} + {@link TcpSock#receiver},使调用方能通过
 * {@code sock.sender().xxx()} / {@code sock.receiver().xxx()} 访问发送 / 接收行为。
 *
 * <p><b>架构三元</b>(对齐 gVisor endpoint + sender + receiver):
 * <pre>
 *   TcpSock (= endpoint: 控制块 + FSM)
 *     ├── sender   (Sender: cwnd/rto/push/retransmit 的统一入口)
 *     └── receiver (Receiver: rcvWnd/OFO/quickack 的统一入口)
 * </pre>
 *
 * <p><b>子类</b>:当前只有 {@link Tcp4Multiplexer}(IPv4);{@code Tcp6Multiplexer} 未实现。
 *
 * <p><b>线程模型</b>:读写 {@link #establishedRegistry} / {@link #timewaitRegistry}
 * 跨 EL(TUN EL + sock EL),用 ConcurrentHashMap;{@link #listener.synRegistry} 只在 TUN EL
 * 访问,用 HashMap。
 */
@Slf4j
public abstract class TcpMultiplexer extends TcpStack {

    protected TcpMultiplexer(TcpConfig config, EventLoopGroup tcpGroup, TcpSockInitializer initializer) {
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
     * <p>R4.2b-2:configure 留在 TcpMultiplexer 而非 TcpStack,因为依赖
     * {@code sk.multiplexer(this)} 的 'this' 是 TcpMultiplexer 类型。R4.2b-3 重命名
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
     * (如 {@code Tcp4Multiplexer})在此装 {@code synAckFailureAction} /
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
            synackRttMeas(child, req);
        }
        return child;
    }

    /**
     * 三次握手完成时用 SYN-ACK 首发到 ACK 的时间差作为首个 RTT 样本。
     * Karn's rule:若 SYN-ACK 曾重传(num_retrans > 0),不取样(无法区分 ACK 确认的是哪一次)。
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c">synackRttMeas</a>
     */
    private static void synackRttMeas(TcpSock child, TcpRequestSock req) {
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
        listener.addRequest(req);
    }

    protected void moveToEstablished(final TcpRequestSock req, final TcpSock sock) {
        if (req.synPacket() != null) {
            req.synPacket().release();
            req.synPacket(null);
        }
        // sender/receiver/multiplexer 已经在 tcp_v4_syn_recv_sock 的 init(newsk) 里 configure
        listener.removeRequest(req);
        establishedRegistry.put(sock.fourTuple(), sock);
    }

    protected void shutdownStack(TcpSock sk, int how) {
        if (!sk.hasConnection() || (how & TcpConstants.SEND_SHUTDOWN) == 0) {
            return;
        }
        if (sk.state() == TcpConnectionState.TCP_ESTABLISHED
                || sk.state() == TcpConnectionState.CLOSE_WAIT) {
            if (closeState(sk)) {
                output.sendFin(sk);
            }
        }
    }

    public void consume(final ChannelHandlerContext ctx, final TcpPacketBuf pkt) {
        rcv(ctx, pkt);
    }

    public boolean sk_acceptq_is_full() {
        return listener.synRegistry.size() >= listener.maxSynBacklog;
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

    protected int ackIncoming(TcpSock sk, TcpPacketBuf pkt, int flag) {
        if (!sk.hasConnection() || !pkt.isAck()) {
            return 1;
        }
        return TcpAck.tcpAck(sk, pkt, flag);
    }

    /**
     * 对齐 Linux {@code dataQueue} (tcp_input.c:5229) 的七分支判定:
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
    protected int dataQueue(ChannelHandlerContext ctx, TcpSock sk, TcpPacketBuf pkt) {
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
        final int rwnd = sk.receiveWindow();

        if (seq == rcvNxt) {
            if (rwnd == 0) {
                if (pkt.tcpPayloadLength() == 0 && pkt.isFin()) {
                    // 即使通告零窗口,仍接受裸 FIN(v1 L1617-1618)
                    queue_and_out(ctx, sk, pkt);
                } else {
                    outOfWindow(sk, SkbDropReason.SKB_DROP_REASON_TCP_ZEROWINDOW);
                }
                return 0;
            }
            queue_and_out(ctx, sk, pkt);
            return 0;
        }

        if (!after(endSeq, rcvNxt)) {
            // 完整重传段(纯 dup)
            outOfWindow(sk, SkbDropReason.SKB_DROP_REASON_TCP_OLD_DATA);
            return 0;
        }

        if (!before(seq, rcvNxt + rwnd)) {
            // seq 在窗口外(零窗口探测等)
            outOfWindow(sk, SkbDropReason.SKB_DROP_REASON_TCP_OVERWINDOW);
            return 0;
        }

        if (before(seq, rcvNxt)) {
            // 部分重叠: seq < rcv_nxt < end_seq
            if (rwnd == 0) {
                outOfWindow(sk, SkbDropReason.SKB_DROP_REASON_TCP_ZEROWINDOW);
            } else {
                queue_and_out(ctx, sk, pkt);
            }
            return 0;
        }

        // 纯乱序: seq > rcv_nxt && seq < rcv_nxt + rwnd
        dataQueueOfo(sk, pkt);
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
         * receiveWindow() = max(0, rcv_wup + rcv_wnd - rcv_nxt) 会随 rcv_nxt 前进
         * 而在下一次 tcp_select_window 触发窗口收缩,对端自然感知反压。对齐 Linux
         * "应用未读 socket → tp->rcv_wnd 缩窗" 语义。
         */
        if (sk.receiveBuffer().isReadable() && !sk.rcvPaused()) {
            consume(sk, sk.receiveBuffer().readAll());
        }

        if (r.finDelivered) {
            // 当前段或先前 OFO 中的 FIN 已抵达 rcv_nxt,执行状态迁移并回 ACK。
            finIncoming(ctx, sk);
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
     * {@code rcvStateProcess} 非数据路径(CLOSE_WAIT/CLOSING/LAST_ACK 的
     * 重传 FIN)走过时不需要推进(seq &lt; rcv_nxt)。
     *
     * <ul>
     *   <li><b>SYN_RECV / ESTABLISHED</b>:迁移至 {@link TcpConnectionState#CLOSE_WAIT},
     *       置 pingpong,发 ACK。v2 正常路径下 SYN_RECV + FIN+ACK 会先由
     *       {@code rcvStateProcess} 的 SYN_RECV 分支 {@code tryEstablish} 迁入
     *       ESTABLISHED,再进入 {@code dataQueue} 命中 ESTABLISHED case;与 Linux
     *       {@code rcvStateProcess} 先 {@code tcp_set_state(ESTABLISHED)} fall-through
     *       到末尾 {@code dataQueue} 的路径等价。两侧 SYN_RECV case 均作防御性合并保留。</li>
     *   <li><b>CLOSE_WAIT / CLOSING / LAST_ACK</b>:重传 FIN,状态保持,发 ACK。</li>
     *   <li><b>FIN_WAIT_1</b>:simultaneous close,先发 ACK 再迁 {@link TcpConnectionState#CLOSING}
     *       (对齐 Linux tcp_input.c:4355)。</li>
     *   <li><b>FIN_WAIT_2</b>:4-way close 完成,发最后 ACK 并 {@link #timeWait}
     *       (sk 随后被销毁,调用方不应再触碰)。</li>
     *   <li><b>default</b>:LISTEN / SYN_SENT / CLOSED / TIME_WAIT — v2 架构下不应到达,no-op。</li>
     * </ul>
     */
    protected void finIncoming(ChannelHandlerContext ctx, TcpSock sk) {
        sk.addShutdown(TcpConstants.RCV_SHUTDOWN);
        final TcpConnectionState prevState = sk.state();
        switch (prevState) {
            case TCP_SYN_RECV:
            case TCP_ESTABLISHED:
                sk.state(TcpConnectionState.CLOSE_WAIT);
                sk.enterPingpongMode();
                output.sendAck(sk);
                notifyPeerFin(sk);
                return;
            case CLOSE_WAIT:
            case CLOSING:
            case LAST_ACK:
                output.sendAck(sk);
                return;
            case FIN_WAIT_1:
                output.sendAck(sk);
                sk.state(TcpConnectionState.CLOSING);
                notifyPeerFin(sk);
                return;
            case FIN_WAIT_2:
                output.sendAck(sk);
                notifyPeerFin(sk);
                timeWait(ctx, sk, TcpConnectionState.TIME_WAIT);
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
    protected void outOfWindow(TcpSock sk, int reason) {
        sk.enterQuickAckMode(TcpConstants.TCP_MAX_QUICKACKS);
        sk.addAckPending(TcpConstants.ACK_SCHED);
        mib.inc(
                com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpMib.OUTOFWINDOWICMPS);
        mib.incDrop(reason);
    }

    /**
     * 纯乱序段(seq &gt; rcv_nxt) 入 OFO 队列(等价 Linux {@code dataQueueOfo}
     * tcp_input.c:5055)。v2 通过 {@link TcpReceiveBuffer#offerOfo} 完成:
     * <ul>
     *   <li>保留 FIN 语义以便后续填洞时重放 {@code tcp_fin};</li>
     *   <li>受 {@link TcpReceiveBuffer#OFO_MAX_BYTES} 内存预算限制,超限时先 prune 再判定。</li>
     * </ul>
     * 入队后统一 quickack + ACK_NOW(对齐 Linux OFO 到达立即回 DSACK 行为)。
     */
    protected void dataQueueOfo(TcpSock sk, TcpPacketBuf pkt) {
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
            // 对齐 Linux dataQueueOfo → tcp_dsack_set (Case 3 of RFC 2883):
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

    protected void dataSndCheck(TcpSock sk) {
        pushPendingFrames(sk);
    }

    protected void pushPendingFrames(TcpSock sk) {
        if (sk.hasConnection() && sk.tcpSendHead() != null) {
            boolean needProbe = output.writeXmit(sk, sk.mss(), TCP_NAGLE_OFF, 0);
            if (needProbe) {
                armProbe0(sk);
            }
        }
    }

    protected static boolean sequenceAcceptable(TcpSock sk, TcpPacketBuf pkt) {
        int seq = pkt.tcpSeq();
        int segLen = pkt.tcpPayloadLength() + (pkt.isSyn() ? 1 : 0) + (pkt.isFin() ? 1 : 0);
        int endSeq = seq + segLen;
        int rcvWup = sk.rcvWup();
        int rcvWndEnd = sk.rcvNxt() + sk.receiveWindow();

        if (before(endSeq, rcvWup)) {
            return false;
        }
        if (after(endSeq, rcvWndEnd)) {
            return !after(seq, rcvWndEnd);
        }
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
        sk.probeTimerAction(() -> probeTimer(sk));
        sk.keepaliveTimerAction(() -> keepaliveTimer(sk));

        /*
         * 统一走 initializer.onEstablished(sk, this) — initializer 构造期已 requireNonNull:
         * - TcpChannelInitializer:创建 TcpChannel,用户 pipeline 接管 payload
         * - TcpPassthroughInitializer (ext.backend):backend 透传,挂 TcpPassthroughHandler 并装反向适配器
         * - TcpSockInitializer.DENY:onRequest 阶段已直接发 RST 销毁 req,此路径不会触发
         */
        initializer.onEstablished(sk, this);
        armKeepalive(sk, sk.keepaliveTimeMs());
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

    protected void ackSndCheck(TcpSock sk) {
        if (!sk.hasAckPending(TcpConstants.ACK_SCHED | TcpConstants.ACK_NOW)) {
            return;
        }
        if ((after(sk.rcvNxt(), sk.rcvWup() + sk.rcvMss()) && sk.rcvWnd() >= sk.receiveWindow())
                || sk.inQuickAckMode()
                || sk.hasAckPending(TcpConstants.ACK_NOW)) {
            output.sendAck(sk);
            return;
        }
        sendDelayedAck(sk);
    }

    protected void sendDelayedAck(TcpSock sk) {
        long ato = Math.max(1L, sk.ackTimeoutMs());
        if (sk.inPingpongMode()) {
            ato = Math.max(ato, config.delayedAckMs());
        }
        sk.addAckPending(TcpConstants.ACK_SCHED | TcpConstants.ACK_TIMER);
        TcpTimerScheduler.INSTANCE.scheduleDelayedAck(sk, ato, () -> delackTimer(sk));
    }

    protected void delackTimer(TcpSock sk) {
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
        output.sendAck(sk);
    }

    protected void armProbe0(TcpSock sk) {
        if (!sk.hasConnection() || sk.packetsOut() != 0 || sk.tcpSendHead() == null) {
            return;
        }
        TcpTimerScheduler.INSTANCE.scheduleWriteTimer(
                sk,
                com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TimerType.ZERO_WINDOW_PROBE,
                sk.tcpProbe0BaseMs(),
                () -> probeTimer(sk)
        );
    }

    protected void probeTimer(TcpSock sk) {
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
            output.sendReset(sk);
            tcpDone(sk);
            return;
        }

        if (sk.probesOut() >= TcpConstants.TCP_RETRIES2) {
            sk.skErr(110);
            output.sendReset(sk);
            tcpDone(sk);
            return;
        }

        long timeout = output.sendProbe0(sk);
        if (timeout > 0L) {
            TcpTimerScheduler.INSTANCE.scheduleWriteTimer(
                    sk,
                    com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TimerType.ZERO_WINDOW_PROBE,
                    timeout,
                    () -> probeTimer(sk)
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
        TcpTimerScheduler.INSTANCE.scheduleKeepalive(sk, Math.max(delayMs, 1L), () -> keepaliveTimer(sk));
    }

    protected void keepaliveTimer(TcpSock sk) {
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
            output.sendReset(sk);
            tcpDone(sk);
            return;
        }

        int err = output.writeWakeup(sk, 1);
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
     * 应用层 payload 入发送队列 — 对齐 Linux {@code sendmsg}(net/ipv4/tcp.c)。
     *
     * <p>Linux 用 {@code lock_sock / release_sock} 把对 sk 的操作序列化到单线程上下文,
     * 再调 {@link #sendmsgLocked}。v2 用 sock 的 {@link EventLoop} 归属达成同样语义 —
     * 当前线程 == sock.eventLoop() 时直接同步执行,否则 {@code execute} 跳转到该 EL。
     *
     * <p>调用方**只需传一个 ByteBuf + flush 标志**,不关心 MSS / 分片 / cwnd / Nagle — 这些
     * 由 {@link #sendmsgLocked} 内部处理。所有权:传入的 {@code data} 引用计数由本方法
     * 负责 release(无论成功失败),调用方 **retain 一次后交给本方法即可**,不要再自己 release。
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp.c">sendmsg</a>
     */
    public void sendmsg(TcpSock sk, ByteBuf data, boolean flush) {
        final EventLoop owner = sk.eventLoop();
        if (owner != null && !owner.inEventLoop()) {
            owner.execute(() -> sendmsgLocked(sk, data, flush));
        } else {
            sendmsgLocked(sk, data, flush);
        }
    }

    /**
     * 已持锁(== 已在 sock.eventLoop())路径上的 send — 对齐 Linux {@code sendmsgLocked}
     * (net/ipv4/tcp.c)。
     *
     * <p>v2 简化:
     * <ul>
     *   <li>入参是一个 ByteBuf(非 msghdr iov),不做 iov 循环;</li>
     *   <li>入队前按 {@code currentMss(sk)} 切片,每个 skb = 1 个 MSS 段 —
     *       偏离 Linux 的 "size_goal 大 skb + 出口 fragment 切分" 模型,但对端线上
     *       字节流一致(详见 tcp.java.md);</li>
     *   <li>不做 tail skb 合并(Linux {@code skb_add_data_nocache});</li>
     *   <li>不支持 MSG_MORE / TCP_CORK 持续累积,flush 为二元开关。</li>
     * </ul>
     *
     * <p>对 {@code data} 的引用计数负责 release(无论状态检查失败、total==0、还是成功切片后)。
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp.c">sendmsgLocked</a>
     */
    protected void sendmsgLocked(TcpSock sk, ByteBuf data, boolean flush) {
        try {
            if (!sk.hasConnection() || !sk.state().canSend()) {
                return;
            }
            final int total = data.readableBytes();
            if (total == 0) {
                return;
            }
            final int mss = Math.max(1, output.currentMss(sk));
            int offset = 0;
            while (offset < total) {
                final int len = Math.min(total - offset, mss);
                final ByteBuf slice = data.retainedSlice(data.readerIndex() + offset, len);
                sk.queueSkb(new TcpSegment(
                        slice,
                        sk.writeSeq(),
                        len,
                        (byte) TcpConstants.TCPHDR_ACK,
                        0L));
                offset += len;
            }
            if (flush) {
                pushPendingFrames(sk);
            }
        } finally {
            data.release();
        }
    }

}
