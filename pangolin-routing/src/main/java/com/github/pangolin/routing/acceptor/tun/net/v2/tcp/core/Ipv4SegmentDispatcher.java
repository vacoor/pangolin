package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.codec.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpLogUtils;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.hook.TcpSockHandler;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.hook.TcpSockInitializer;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import lombok.extern.slf4j.Slf4j;

import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpAck.FLAG_NO_CHALLENGE_ACK;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpAck.FLAG_SLOWPATH;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpAck.FLAG_UPDATE_TS_RECENT;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpUtils.determineEndSeq;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSequence.after;

@Slf4j
public class Ipv4SegmentDispatcher extends SegmentDispatcher {

    /**
     * 工厂模式构造:三次握手完成后由 {@link TcpSockInitializer#onEstablished}
     * 挂 {@link TcpSockHandler}(如 netty 子包的 {@code TcpChannelInitializer} 创建
     * {@link com.github.pangolin.routing.acceptor.tun.net.v2.tcp.netty.TcpChannel},
     * 或 ext.backend 子包的 {@code TcpPassthroughInitializer} 透传到 backend)。
     */
    public Ipv4SegmentDispatcher(TcpConfig config, TcpSockInitializer initializer) {
        this(config, initializer, null);
    }

    /**
     * 工厂模式 + 独立 {@code tcpGroup}:为每条已建立连接从 {@code tcpGroup.next()}
     * 绑定专属 EL,与 TUN EL 解耦,避免所有连接串在 TUN 单线程上。
     */
    public Ipv4SegmentDispatcher(TcpConfig config, TcpSockInitializer initializer, EventLoopGroup tcpGroup) {
        super(config, tcpGroup, initializer);
    }

    // init(TcpSock) 继承父类默认实现(configure sender/receiver),IPv4 无额外装配逻辑。

    @Override
    public void rcv(ChannelHandlerContext net, TcpPacketBuf pkt) {
        log.info(TcpLogUtils.logify(pkt, 0));
        tcp_v4_rcv(net, pkt);
    }

    @Override
    public void send_reset(ChannelHandlerContext net, TcpPacketBuf pkt, int err) {
        output.v4SendReset(net, pkt);
    }

    @Override
    public void inet_rtx_syn_ack(ChannelHandlerContext net, TcpSock listenSock, TcpRequestSock req) {
        req.request().retransmitSynAck(net.channel());
    }

    /**
     * P2.1:SYN-ACK 调度入口 — 由 {@link TcpSockInitializer#onRequest} 决定何时调用。
     *
     * <p><b>契约</b>:调用前 {@code tcp_v4_conn_request} 已完成
     * {@code req.synPacket(pkt.retain()) / req.net(net)},本方法只装 failure / close listener
     * 并触发 {@code sendSynAckAfterBackendConnected}。synPacket 的释放由 {@code moveToEstablished}
     * 或 {@code inet_csk_destroy_sock(req)} 负责,balance 与 conn_request 的单次 retain 对齐。
     */
    @Override
    public void sendSynAck(TcpRequestSock req) {
        final ChannelHandlerContext net = req.net();
        req.request().synAckFailureAction(() -> inet_csk_destroy_sock(req));
        req.handshakeCloseListener(future -> {
            if (req.synPacket() != null) {
                output.v4SendReset(net, req.synPacket());
            }
            inet_csk_destroy_sock(req);
        });
        req.request().sendSynAckAfterBackendConnected(net.channel());
    }

    @Override
    protected TcpRequestSock conn_request(ChannelHandlerContext net, TcpSock listenSock, TcpPacketBuf pkt) {
        return tcp_v4_conn_request(net, listener.listenSock, pkt);
    }

    @Override
    protected TcpSock syn_recv_sock(ChannelHandlerContext net, TcpSock listenSock, TcpPacketBuf pkt, TcpRequestSock req) {
        return tcp_v4_syn_recv_sock(net, listener.listenSock, pkt, req);
    }

    protected void tcp_v4_rcv(ChannelHandlerContext net, TcpPacketBuf pkt) {
        SockCommon sk = lookup.lookup(pkt);
        if (sk == null) {
            if (!pkt.isRst()) {
                send_reset(net, pkt, -3);
            }
            return;
        }

        if (sk instanceof TcpTimewaitSock) {
            TcpTimewaitSock tw = (TcpTimewaitSock) sk;
            if (!tw.handleIncoming(net, pkt, this)) {
                // TW_SYN 重用 bucket:bucket 已 kill,重入 rcv 派发到 LISTEN 走 conn_request
                tcp_v4_rcv(net, pkt);
            }
            return;
        }

        if (sk instanceof TcpRequestSock) {
            TcpRequestSock req = (TcpRequestSock) sk;
            TcpSock nsk = checkReq(net, req.listener(), pkt, req);
            if (nsk == null) {
                return;
            }
            moveToEstablished(req, nsk);
            sk = nsk;
        }

        final TcpSock sockToUse = (TcpSock) sk;
        // 对齐 v1:ESTABLISHED 连接的状态机处理跳到该 sock 绑定的 EL 执行,TUN EL 只负责派发。
        // listener.listenSock 无专属 EL,其 eventLoop() 回退到 TUN EL,本分支自然等价于原地执行。
        final EventLoop loop = sockToUse.eventLoop();
        if (loop == null || loop.inEventLoop()) {
            tcp_v4_do_rcv_safely(net, sockToUse, pkt);
        } else {
            pkt.retain();
            loop.execute(() -> {
                try {
                    tcp_v4_do_rcv_safely(net, sockToUse, pkt);
                } finally {
                    pkt.release();
                }
            });
        }
    }

    private void tcp_v4_do_rcv_safely(ChannelHandlerContext net, TcpSock sockToUse, TcpPacketBuf pkt) {
        try {
            int err = tcp_v4_do_rcv(net, sockToUse, pkt);
            if (err != 0) {
                send_reset(net, pkt, err);
                if (sockToUse != listener.listenSock) {
                    inet_csk_destroy_sock(sockToUse);
                }
            }
        } catch (Throwable cause) {
            if (sockToUse != listener.listenSock) {
                inet_csk_destroy_sock(sockToUse);
            }
            throw cause;
        }
    }

    protected int tcp_v4_do_rcv(ChannelHandlerContext net, TcpSock sk, TcpPacketBuf pkt) {
        return rcvStateProcess(net, sk, pkt);
    }

    protected int rcvStateProcess(ChannelHandlerContext net, TcpSock sk, TcpPacketBuf pkt) {
        switch (sk.state()) {
            case TCP_CLOSED:
                return -1;
            case TCP_LISTEN:
                if (pkt.isAck()) {
                    return -1;
                }
                if (pkt.isRst()) {
                    return 0;
                }
                if (pkt.isSyn() && !pkt.isFin()) {
                    if (listener.synQueueFull()) {
                        return -1;
                    }
                    // conn_request 内部完成 listener.addRequest + 后端连接启动(对齐 v1 tcp_conn_request)
                    TcpRequestSock req = conn_request(net, sk, pkt);
                    if (req == null) {
                        return -1;
                    }
                    return 0;
                }
                return 0;
            case TCP_SYN_SENT:
                return -1;
            default:
                break;
        }

        if (!sk.hasConnection()) {
            return -1;
        }

        TcpIncomingPreValidator validator = new TcpIncomingPreValidator(sk, () -> tcpDone(sk));
        if (!validator.validate(net, pkt)) {
            return 0;
        }

        int reason = sk.sender().ackIncoming(pkt, FLAG_SLOWPATH | FLAG_UPDATE_TS_RECENT | FLAG_NO_CHALLENGE_ACK);
        if (reason <= 0) {
            if (sk.state() == TcpConnectionState.TCP_SYN_RECV) {
                return reason == 0 ? 0 : -reason;
            }
            if (reason < 0) {
                output.sendChallengeAck(sk, false);
                return 0;
            }
        }

        switch (sk.state()) {
            case TCP_SYN_RECV:
                tryEstablish(sk, pkt);
                break;
            case FIN_WAIT_1:
                if (sk.sndUna() == sk.writeSeq()) {
                    // FIN_WAIT_1 → FIN_WAIT_2: linger2 < 0 表示应用已放弃 2MSL 等待
                    // 立即 abort (对齐 v1 tcp_input.c:2193 abort-on-data-after-close)
                    if (sk.linger2() < 0) {
                        tcpDone(sk);
                        return 0;
                    }
                    sk.state(TcpConnectionState.FIN_WAIT_2);
                    sk.addShutdown(TcpConstants.SEND_SHUTDOWN);
                    // 对齐 Linux tcp_input.c 中 TCP_FIN_WAIT1 → TCP_FIN_WAIT2 分支:
                    // 若当前段piggyback peer 的 FIN,直接下沉到 TW bucket 会让后续
                    // dataQueue → finIncoming 消费 FIN 的路径失效(sk 已销毁),对齐
                    // `else if (th->fin) inet_csk_reset_keepalive_timer(sk, tmo)` 的延迟策略:
                    // 保留重量级 sk 走完本段的 dataQueue 再由 finIncoming 迁入 TIME_WAIT。
                    if (pkt.isFin()) {
                        TcpTimerScheduler.INSTANCE.scheduleKeepalive(sk, sk.tcpFinTimeMs(), () -> onFinWait2Keepalive(sk));
                    } else {
                        scheduleFinWait2Timeout(sk);
                    }
                }
                break;
            case CLOSING:
                if (sk.sndUna() == sk.writeSeq()) {
                    timeWait(net, sk, TcpConnectionState.TIME_WAIT);
                    return 0;
                }
                break;
            case LAST_ACK:
                if (sk.sndUna() == sk.writeSeq()) {
                    tcpDone(sk);
                    return 0;
                }
                break;
            default:
                break;
        }

        switch (sk.state()) {
            case TCP_ESTABLISHED:
                return dataQueueAndPostProcess(net, sk, pkt);
            case FIN_WAIT_1:
            case FIN_WAIT_2:
                if (sk.hasShutdown(TcpConstants.RCV_SHUTDOWN) && hasDataBeyondRcvNxt(sk, pkt)) {
                    send_reset(net, pkt, -1);
                    tcpDone(sk);
                    return 0;
                }
                // FIN+data 段由 Receiver.handleDataQueue → queueAndOut 统一处理:receiveBuffer.offer
                // 推进 rcv_nxt,finDelivered 时触发 Receiver.finIncoming 全状态 switch(FIN_WAIT_1 →
                // CLOSING,FIN_WAIT_2 → TIME_WAIT)。裸 FIN(seq == rcv_nxt,无 payload)同路径生效。
                return dataQueueAndPostProcess(net, sk, pkt);
            case CLOSE_WAIT:
            case CLOSING:
            case LAST_ACK:
                // 接收方向已关闭:任何新数据 / 越界 FIN 都视为协议违规(对齐 v1 tcp_input.c:5160)
                if (hasDataBeyondRcvNxt(sk, pkt)) {
                    send_reset(net, pkt, -1);
                    tcpDone(sk);
                    return 0;
                }
                // 重传 FIN(seq < rcv_nxt):状态不变,走统一 Receiver.finIncoming
                // (CLOSE_WAIT / CLOSING / LAST_ACK 分支仅发 ACK)。
                if (pkt.isFin()) {
                    sk.receiver().finIncoming(net);
                }
                return 0;
            case TIME_WAIT:
                // 正常情况下 TcpSock 在进入 TIME_WAIT 时已被 timeWait 销毁 →
                // 后续段由 TW bucket({@link SegmentDispatcher#timewaitRegistry})接管,
                // 走 timewaitStateProcess 路径。此分支仅作防御性 no-op。
                return 0;
            default:
                return 0;
        }
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L1722">tcp_v4_conn_request</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L7195">tcp_conn_request</a>
     */
    protected TcpRequestSock tcp_v4_conn_request(ChannelHandlerContext net, TcpSock listenSock, TcpPacketBuf pkt) {
        TcpHandshaker handshaker = handshakerFactory.newHandshaker(pkt);
        TcpRequestSock req = new TcpRequestSock(FourTuple.of(pkt), listener.listenSock, handshaker);
        openreqInit(req, handshaker, pkt);
        // tcp_timeout_init 等价于 v1 L86:首个 SYN-ACK RTO 基线
        req.timeout = TcpConstants.RTO_INIT_MS;
        req.rsk_timer = null;
        req.num_retrans = 0;

        /*
         * 对齐 Linux {@code tcp_conn_request}:addToHalfQueue 先入队,再交给
         * initializer 决定 SYN-ACK 发送时机:
         *   - 默认实现立发(对应 listener 已就绪)
         *   - TcpPassthroughInitializer 先连 backend 再发(保留 v1 "backend 连上再 SYN-ACK" 语义)
         *   - DENY 立发 RST + 销毁 req(对齐 "无 listener → RST")
         * synPacket / net 在此统一 retain + stash;lifetime 与 req 绑定,由
         * moveToEstablished 或 inet_csk_destroy_sock(req) 负责释放。
         */
        listener.addRequest(req);
        req.net(net);
        req.synPacket((TcpPacketBuf) pkt.retain());
        initializer.onRequest(req, this);
        return req;
    }

    /**
     * 将 SYN 中协商结果写回 req,便于后续 {@code tcp_create_openreq_child} 初始化子 sock。
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L7068">openreqInit</a>
     */
    private static void openreqInit(TcpRequestSock req, TcpHandshaker handshaker, TcpPacketBuf pkt) {
        req.rcv_isn = handshaker.rcvIsn();
        req.rcv_nxt = handshaker.rcvNxt();
        req.snt_isn = handshaker.sndIsn();
        req.mss = handshaker.clientMss();
        req.snd_wnd = handshaker.clientInitWnd();
        if (handshaker.clientWscale() >= 0) {
            req.wscale_ok = true;
            req.snd_wscale = handshaker.clientWscale();
            req.rcv_wscale = handshaker.serverWscale();
        } else {
            req.wscale_ok = false;
            req.snd_wscale = 0;
            req.rcv_wscale = 0;
        }
        req.tstamp_ok = handshaker.clientTimestamp();
        req.ts_recent = handshaker.clientTsVal() & 0xFFFFFFFFL;
        req.ts_off = 0L;
        req.req_usec_ts = false;
        req.syn_tos = 0;
        req.last_oow_ack_time = 0;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L1742">tcp_v4_syn_recv_sock</a>
     */
    protected TcpSock tcp_v4_syn_recv_sock(ChannelHandlerContext net, TcpSock listenSock, TcpPacketBuf pkt, TcpRequestSock req) {
        TcpSock newsk = init(req.request().buildChildSock(net.channel(), req.childChannel(), pkt));
        newsk.childCloseListener(req.handshakeCloseListener());
        newsk.state(TcpConnectionState.TCP_SYN_RECV);
        // 对齐 v1:每条 ESTABLISHED 连接绑定专属 EL,状态机 / 定时器 / sink 全部在该 EL 上串行。
        // initializer.proposeEventLoop 优先(TcpPassthroughInitializer 会返回 backend EL,保留
        // "状态机与 backend I/O 同线程"语义);返回 null 时回退到 tcpGroup.next()。
        EventLoop proposed = initializer.proposeEventLoop(req, this);
        if (proposed != null) {
            newsk.tcpEventLoop(proposed);
        } else if (tcpGroup != null) {
            newsk.tcpEventLoop(tcpGroup.next());
        }
        // B4: 子 sock MSS 同步(对齐 v1 tcp_create_openreq_child → output.syncMss)
        output.syncMss(newsk, 0);
        return newsk;
    }

    private void tryEstablish(TcpSock sk, TcpPacketBuf pkt) {
        if (!sk.hasConnection()) {
            return;
        }
        sk.sender().initWl(pkt.tcpSeq());
        sk.state(TcpConnectionState.TCP_ESTABLISHED);
        sk.sndUna(pkt.tcpAckNum());
        sk.sndWnd(pkt.tcpWindow() << sk.sndWscale());
        initTransfer(sk);
        sk.rcvMss(sk.receiver().initializeRcvMss());
        if (sk.hasShutdown(TcpConstants.SEND_SHUTDOWN)) {
            sk.sender().shutdown(TcpConstants.SEND_SHUTDOWN);
        }
    }

    /**
     * ESTABLISHED / FIN_WAIT_x 路径的数据入队 — 代理到 {@link Receiver#handleDataQueue}
     * 后,触发 push + ACK 调度;CLOSE_WAIT 时关闭 backend 透传 childChannel(保留 v1 语义)。
     */
    protected int dataQueueAndPostProcess(ChannelHandlerContext ctx, TcpSock sk, TcpPacketBuf pkt) {
        int err = sk.receiver().handleDataQueue(ctx, pkt);
        if (!sk.hasConnection() || sk.state() == TcpConnectionState.TCP_CLOSED) {
            return err;
        }
        sk.sender().pushPending();
        sk.receiver().ackSndCheck();
        if (sk.state() == TcpConnectionState.CLOSE_WAIT
                && sk.childChannel() != null
                && sk.childChannel().isOpen()) {
            sk.childChannel().close();
        }
        return err;
    }

    /**
     * 对齐 Linux {@code timeWait(sk, TCP_FIN_WAIT2, tmo)} 的调度入口
     * (net/ipv4/tcp_input.c / tcp_minisocks.c):
     * <ul>
     *   <li>tmo &le; {@code TIME_WAIT_MS}:立即将重量级 {@link TcpSock} 下沉为
     *       携带 {@code tw_substate=FIN_WAIT_2} 的 {@link TcpTimewaitSock},
     *       2MSL 定时器亦由 TW bucket 托管。</li>
     *   <li>tmo &gt; {@code TIME_WAIT_MS}:linger2 超出单倍 2MSL 的余量先挂在
     *       原 sock 上,由 {@link #onFinWait2Keepalive} 到期后再转入 TW bucket。</li>
     * </ul>
     */
    private void scheduleFinWait2Timeout(TcpSock sk) {
        if (!sk.hasConnection()) {
            return;
        }
        final int tmo = sk.tcpFinTimeMs();
        if (tmo > TcpConstants.TIME_WAIT_MS) {
            TcpTimerScheduler.INSTANCE.scheduleKeepalive(sk, tmo - TcpConstants.TIME_WAIT_MS, () -> onFinWait2Keepalive(sk));
        } else {
            timeWait(sk, TcpConnectionState.FIN_WAIT_2, tmo);
        }
    }

    private static boolean hasDataBeyondRcvNxt(TcpSock sk, TcpPacketBuf pkt) {
        int seq = pkt.tcpSeq();
        int endSeq = determineEndSeq(pkt);
        return endSeq != seq && after(endSeq - (pkt.isFin() ? 1 : 0), sk.rcvNxt());
    }

    private void onFinWait2Keepalive(TcpSock sk) {
        if (!sk.hasConnection() || sk.state() != TcpConnectionState.FIN_WAIT_2) {
            return;
        }
        if (sk.linger2() >= 0) {
            final int tmo = sk.tcpFinTimeMs() - (int) TcpConstants.TIME_WAIT_MS;
            if (tmo > 0) {
                timeWait(sk, TcpConnectionState.FIN_WAIT_2, tmo);
                return;
            }
        }
        sk.addShutdown(TcpConstants.RCV_SHUTDOWN);
        output.sendReset(sk);
        tcpDone(sk);
    }
}
