package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpLogUtils;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConnectionState;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpOutput;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpHandshaker;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.FourTuple;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConfig;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConstants;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpTimewaitSock;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpTimerScheduler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import lombok.extern.slf4j.Slf4j;

import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpAck.FLAG_NO_CHALLENGE_ACK;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpAck.FLAG_SLOWPATH;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpAck.FLAG_UPDATE_TS_RECENT;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpUtils.determineEndSeq;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSequence.after;
import static com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSequence.before;

@Slf4j
public class Tcp4Multiplexer extends TcpMultiplexer {

    /**
     * 工厂模式构造:三次握手完成后由 {@link TcpSockInitializer#onEstablished}
     * 挂 {@link TcpSockHandler}(如 netty 子包的 {@code TcpChannelFactory} 创建
     * {@link com.github.pangolin.routing.acceptor.tun.net.v2.tcp.netty.TcpChannel},
     * 或 ext.backend 子包的 {@code BackendProxyInitializer} 透传到 backend)。
     */
    public Tcp4Multiplexer(TcpConfig config, TcpSockInitializer initializer) {
        this(config, initializer, null);
    }

    /**
     * 工厂模式 + 独立 {@code tcpGroup}:为每条已建立连接从 {@code tcpGroup.next()}
     * 绑定专属 EL,与 TUN EL 解耦,避免所有连接串在 TUN 单线程上。
     */
    public Tcp4Multiplexer(TcpConfig config, TcpSockInitializer initializer, EventLoopGroup tcpGroup) {
        super(config, tcpGroup, initializer);
    }

    @Override
    protected TcpSock init(TcpSock sk) {
        return sk;
    }

    @Override
    public void tcp_rcv(ChannelHandlerContext net, TcpPacketBuf pkt) {
        log.info(TcpLogUtils.logify(pkt, 0));
        tcp_v4_rcv(net, pkt);
    }

    @Override
    public void send_reset(ChannelHandlerContext net, TcpPacketBuf pkt, int err) {
        TcpOutput.INSTANCE.tcp_v4_send_reset(net, pkt);
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
                TcpOutput.INSTANCE.tcp_v4_send_reset(net, req.synPacket());
            }
            inet_csk_destroy_sock(req);
        });
        req.request().sendSynAckAfterBackendConnected(net.channel());
    }

    @Override
    protected TcpRequestSock conn_request(ChannelHandlerContext net, TcpSock listenSock, TcpPacketBuf pkt) {
        return tcp_v4_conn_request(net, listenSock, pkt);
    }

    @Override
    protected TcpSock syn_recv_sock(ChannelHandlerContext net, TcpSock listenSock, TcpPacketBuf pkt, TcpRequestSock req) {
        return tcp_v4_syn_recv_sock(net, listenSock, pkt, req);
    }

    protected void tcp_v4_rcv(ChannelHandlerContext net, TcpPacketBuf pkt) {
        SockCommon sk = __inet_lookup_skb(pkt);
        if (sk == null) {
            if (!pkt.isRst()) {
                send_reset(net, pkt, -3);
            }
            return;
        }

        if (sk instanceof TcpTimewaitSock) {
            tcp_timewait_state_process(net, (TcpTimewaitSock) sk, pkt);
            return;
        }

        if (sk instanceof TcpRequestSock) {
            TcpRequestSock req = (TcpRequestSock) sk;
            TcpSock nsk = tcp_check_req(net, req.listener(), pkt, req);
            if (nsk == null) {
                return;
            }
            moveToEstablished(req, nsk);
            sk = nsk;
        }

        final TcpSock sockToUse = (TcpSock) sk;
        // 对齐 v1:ESTABLISHED 连接的状态机处理跳到该 sock 绑定的 EL 执行,TUN EL 只负责派发。
        // listenSock 无专属 EL,其 eventLoop() 回退到 TUN EL,本分支自然等价于原地执行。
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
                if (sockToUse != listenSock) {
                    inet_csk_destroy_sock(sockToUse);
                }
            }
        } catch (Throwable cause) {
            if (sockToUse != listenSock) {
                inet_csk_destroy_sock(sockToUse);
            }
            throw cause;
        }
    }

    protected int tcp_v4_do_rcv(ChannelHandlerContext net, TcpSock sk, TcpPacketBuf pkt) {
        return tcp_rcv_state_process(net, sk, pkt);
    }

    protected int tcp_rcv_state_process(ChannelHandlerContext net, TcpSock sk, TcpPacketBuf pkt) {
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
                    if (sk_acceptq_is_full()) {
                        return -1;
                    }
                    // conn_request 内部完成 addToHalfQueue + 后端连接启动(对齐 v1 tcp_conn_request)
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

        TcpIncomingPreValidator validator = new TcpIncomingPreValidator(sk, () -> tcp_done(sk));
        if (!validator.validate(net, pkt)) {
            return 0;
        }

        int reason = tcp_ack(sk, pkt, FLAG_SLOWPATH | FLAG_UPDATE_TS_RECENT | FLAG_NO_CHALLENGE_ACK);
        if (reason <= 0) {
            if (sk.state() == TcpConnectionState.TCP_SYN_RECV) {
                return reason == 0 ? 0 : -reason;
            }
            if (reason < 0) {
                TcpOutput.INSTANCE.tcp_send_challenge_ack(sk, false);
                return 0;
            }
        }

        switch (sk.state()) {
            case TCP_SYN_RECV:
                tcp_try_establish(sk, pkt);
                break;
            case FIN_WAIT_1:
                if (sk.sndUna() == sk.writeSeq()) {
                    // FIN_WAIT_1 → FIN_WAIT_2: linger2 < 0 表示应用已放弃 2MSL 等待
                    // 立即 abort (对齐 v1 tcp_input.c:2193 abort-on-data-after-close)
                    if (sk.linger2() < 0) {
                        tcp_done(sk);
                        return 0;
                    }
                    sk.state(TcpConnectionState.FIN_WAIT_2);
                    sk.addShutdown(TcpConstants.SEND_SHUTDOWN);
                    // 对齐 Linux tcp_input.c 中 TCP_FIN_WAIT1 → TCP_FIN_WAIT2 分支:
                    // 若当前段piggyback peer 的 FIN,直接下沉到 TW bucket 会让后续
                    // tcp_data_queue → tcp_fin 消费 FIN 的路径失效(sk 已销毁),对齐
                    // `else if (th->fin) inet_csk_reset_keepalive_timer(sk, tmo)` 的延迟策略:
                    // 保留重量级 sk 走完本段的 tcp_data_queue 再由 tcp_fin 迁入 TIME_WAIT。
                    if (pkt.isFin()) {
                        TcpTimerScheduler.INSTANCE.scheduleKeepalive(sk, sk.tcpFinTimeMs(), () -> onFinWait2Keepalive(sk));
                    } else {
                        scheduleFinWait2Timeout(sk);
                    }
                }
                break;
            case CLOSING:
                if (sk.sndUna() == sk.writeSeq()) {
                    tcp_time_wait(net, sk, TcpConnectionState.TIME_WAIT);
                    return 0;
                }
                break;
            case LAST_ACK:
                if (sk.sndUna() == sk.writeSeq()) {
                    tcp_done(sk);
                    return 0;
                }
                break;
            default:
                break;
        }

        switch (sk.state()) {
            case TCP_ESTABLISHED:
                return tcp_data_queue(net, sk, pkt);
            case FIN_WAIT_1:
            case FIN_WAIT_2:
                if (sk.hasShutdown(TcpConstants.RCV_SHUTDOWN) && hasDataBeyondRcvNxt(sk, pkt)) {
                    send_reset(net, pkt, -1);
                    tcp_done(sk);
                    return 0;
                }
                // FIN+data 段由 tcp_data_queue → queue_and_out 统一处理:receiveBuffer.offer
                // 推进 rcv_nxt,finDelivered 时触发 tcp_fin 全状态 switch(FIN_WAIT_1 → CLOSING,
                // FIN_WAIT_2 → TIME_WAIT)。裸 FIN(seq == rcv_nxt,无 payload)同路径生效。
                return tcp_data_queue(net, sk, pkt);
            case CLOSE_WAIT:
            case CLOSING:
            case LAST_ACK:
                // 接收方向已关闭:任何新数据 / 越界 FIN 都视为协议违规(对齐 v1 tcp_input.c:5160)
                if (hasDataBeyondRcvNxt(sk, pkt)) {
                    send_reset(net, pkt, -1);
                    tcp_done(sk);
                    return 0;
                }
                // 重传 FIN(seq < rcv_nxt):状态不变,走统一 tcp_fin(CLOSE_WAIT/CLOSING/LAST_ACK
                // 分支仅发 ACK)— 对齐 Linux tcp_fin switch。
                if (pkt.isFin()) {
                    tcp_fin(net, sk);
                }
                return 0;
            case TIME_WAIT:
                // 正常情况下 TcpSock 在进入 TIME_WAIT 时已被 tcp_time_wait 销毁 →
                // 后续段由 TW bucket({@link TcpMultiplexer#timewaitRegistry})接管,
                // 走 tcp_timewait_state_process 路径。此分支仅作防御性 no-op。
                return 0;
            default:
                return 0;
        }
    }

    /**
     * 对齐 Linux {@code tcp_timewait_state_process}(net/ipv4/tcp_minisocks.c):
     * 处理 TIME_WAIT bucket 收到的迟到段。
     *
     * <ul>
     *   <li><b>Timestamps / PAWS</b>:若协商并缓存了 {@code tw_ts_recent},按
     *       {@link TcpTimewaitSock#pawsRejected(int)} 判定,拒绝段累加
     *       {@code LINUX_MIB_PAWSESTABREJECTED} 并刷新 2MSL;合法段同步更新
     *       {@code tw_ts_recent / tw_ts_recent_stamp}(对齐 {@code tcp_store_ts_recent})。</li>
     *   <li><b>TW_SYN 被动复用</b>:SYN 且 SEQ 超过 {@code tw_rcv_nxt} 或 timestamps 更新
     *       时,kill twsk 后重新派发到 listen 路径建立新连接(对齐 {@code TCP_TW_SYN})。</li>
     *   <li><b>RST</b>:SEQ == {@code tw_rcv_nxt} 时 kill twsk;其它 SEQ 静默丢弃,
     *       避免 RFC 5961 弱 RST 攻击(对齐 {@code TCP_TW_RST})。</li>
     *   <li><b>迟到 FIN / 数据</b>:重放 ACK(seq = tw_snd_nxt, ack = tw_rcv_nxt)+
     *       {@code inet_twsk_reschedule} 刷新 2MSL(对齐 {@code TCP_TW_ACK})。</li>
     * </ul>
     *
     * <p>依据 {@link TcpTimewaitSock#tw_substate} 分派 FIN_WAIT_2 / TIME_WAIT:
     * FIN_WAIT_2 阶段收到对端 FIN 需推进 {@code tw_rcv_nxt} 并迁入 TIME_WAIT 子状态,
     * 同时刷新为 2MSL;FIN_WAIT_2 阶段收到裸数据则重放 ACK 但不迁移子状态。
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c">tcp_timewait_state_process</a>
     */
    protected void tcp_timewait_state_process(ChannelHandlerContext net, TcpTimewaitSock tw, TcpPacketBuf pkt) {
        if (tw == null) {
            return;
        }

        // (1) Timestamps 解析 — 仅当本侧曾协商 timestamps 时才参与 PAWS 判定
        int tsVal = -1;
        boolean sawTs = false;
        boolean pawsReject = false;
        if (tw.tw_ts_enabled) {
            long[] ts = com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpOptionCodec
                    .parseTimestamp(pkt.tcpOptionsSlice());
            if (ts != null) {
                sawTs = true;
                tsVal = (int) ts[0];
                pawsReject = tw.pawsRejected(tsVal);
            }
        }

        // (2) TW_SYN — 对新 SYN 允许在 TW bucket 上被动重建连接
        //     条件:SYN && !RST && !ACK && (seq after tw_rcv_nxt 或 timestamps 更新)。
        if (pkt.isSyn() && !pkt.isRst() && !pkt.isAck()) {
            boolean seqAdvanced = after(pkt.tcpSeq(), tw.tw_rcv_nxt);
            boolean tsAdvanced = sawTs && (((int) tw.tw_ts_recent) - tsVal) < 0;
            if (seqAdvanced || tsAdvanced) {
                inet_twsk_kill(tw);
                // 重新进入派发:TW bucket 已空,查找会回退到 LISTEN 走 tcp_conn_request。
                tcp_v4_rcv(net, pkt);
                return;
            }
        }

        // (3) RST
        if (pkt.isRst()) {
            if (!pawsReject && pkt.tcpSeq() == tw.tw_rcv_nxt) {
                inet_twsk_kill(tw);
            }
            return;
        }

        // (4) PAWS 拒绝:回放 ACK + 刷新 2MSL,累加 MIB;合法段同步刷新 ts_recent
        if (pawsReject) {
            com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpMibStats.INSTANCE.inc(
                    com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpMib.PAWSESTABREJECTED);
            TcpOutput.INSTANCE.tcp_timewait_send_ack(net, tw);
            inet_twsk_reschedule(tw, config.timeWaitMs());
            return;
        }
        if (sawTs) {
            tw.updateTsRecent(tsVal);
        }

        // (5) FIN_WAIT_2 子状态 — 等待对端 FIN。
        //     对齐 Linux tcp_timewait_state_process:收到期望序号 FIN 后推进 rcv_nxt,
        //     迁入 TIME_WAIT 子状态并重置为 2MSL;非 FIN 的迟到数据仅回放 ACK,子状态保持 FIN_WAIT_2。
        if (tw.tw_substate == TcpConnectionState.FIN_WAIT_2) {
            if (pkt.isFin() && pkt.tcpSeq() == tw.tw_rcv_nxt) {
                // FIN 消耗一个 SEQ:推进 rcv_nxt,迁入 TIME_WAIT 子状态进入 2MSL 静默等待
                tw.tw_rcv_nxt = tw.tw_rcv_nxt + 1;
                tw.tw_substate = TcpConnectionState.TIME_WAIT;
                TcpOutput.INSTANCE.tcp_timewait_send_ack(net, tw);
                inet_twsk_reschedule(tw, config.timeWaitMs());
                return;
            }
            if (pkt.tcpPayloadLength() > 0) {
                // 迟到数据 — 重放 ACK 让对端感知,子状态不变,保持 FIN_WAIT_2 的 linger2 定时器
                TcpOutput.INSTANCE.tcp_timewait_send_ack(net, tw);
                return;
            }
            // 纯 ACK / 空段 / 越界 FIN — 静默丢弃
            return;
        }

        // (6) TIME_WAIT 子状态 — 迟到 FIN / 数据段:重放 ACK + 重置 2MSL。
        //     纯 ACK / 空段静默丢弃,避免反射风暴。
        if (pkt.isFin() || pkt.tcpPayloadLength() > 0) {
            TcpOutput.INSTANCE.tcp_timewait_send_ack(net, tw);
            inet_twsk_reschedule(tw, config.timeWaitMs());
        }
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L1722">tcp_v4_conn_request</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L7195">tcp_conn_request</a>
     */
    protected TcpRequestSock tcp_v4_conn_request(ChannelHandlerContext net, TcpSock listenSock, TcpPacketBuf pkt) {
        TcpHandshaker handshaker = handshakerFactory.newHandshaker(pkt);
        TcpRequestSock req = new TcpRequestSock(FourTuple.of(pkt), listenSock, handshaker);
        tcp_openreq_init(req, handshaker, pkt);
        // tcp_timeout_init 等价于 v1 L86:首个 SYN-ACK RTO 基线
        req.timeout = TcpConstants.RTO_INIT_MS;
        req.rsk_timer = null;
        req.num_retrans = 0;

        /*
         * 对齐 Linux {@code tcp_conn_request}:addToHalfQueue 先入队,再交给
         * initializer 决定 SYN-ACK 发送时机:
         *   - 默认实现立发(对应 listener 已就绪)
         *   - BackendProxyInitializer 先连 backend 再发(保留 v1 "backend 连上再 SYN-ACK" 语义)
         *   - DENY 立发 RST + 销毁 req(对齐 "无 listener → RST")
         * synPacket / net 在此统一 retain + stash;lifetime 与 req 绑定,由
         * moveToEstablished 或 inet_csk_destroy_sock(req) 负责释放。
         */
        addToHalfQueue(listenSock, req);
        req.net(net);
        req.synPacket((TcpPacketBuf) pkt.retain());
        initializer.onRequest(req, this);
        return req;
    }

    /**
     * 将 SYN 中协商结果写回 req,便于后续 {@code tcp_create_openreq_child} 初始化子 sock。
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L7068">tcp_openreq_init</a>
     */
    private static void tcp_openreq_init(TcpRequestSock req, TcpHandshaker handshaker, TcpPacketBuf pkt) {
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
        // initializer.proposeEventLoop 优先(BackendProxyInitializer 会返回 backend EL,保留
        // "状态机与 backend I/O 同线程"语义);返回 null 时回退到 tcpGroup.next()。
        EventLoop proposed = initializer.proposeEventLoop(req, this);
        if (proposed != null) {
            newsk.tcpEventLoop(proposed);
        } else if (tcpGroup != null) {
            newsk.tcpEventLoop(tcpGroup.next());
        }
        // B4: 子 sock MSS 同步(对齐 v1 tcp_create_openreq_child → output.tcp_sync_mss)
        TcpOutput.INSTANCE.tcp_sync_mss(newsk, 0);
        return newsk;
    }

    private void tcp_try_establish(TcpSock sk, TcpPacketBuf pkt) {
        if (!sk.hasConnection()) {
            return;
        }
        tcp_init_wl(sk, pkt.tcpSeq());
        sk.state(TcpConnectionState.TCP_ESTABLISHED);
        sk.sndUna(pkt.tcpAckNum());
        sk.sndWnd(pkt.tcpWindow() << sk.sndWscale());
        tcp_init_transfer(sk);
        sk.rcvMss(tcp_initialize_rcv_mss(sk));
        if (sk.hasShutdown(TcpConstants.SEND_SHUTDOWN)) {
            tcp_shutdown(sk, TcpConstants.SEND_SHUTDOWN);
        }
    }

    @Override
    protected int tcp_data_queue(ChannelHandlerContext ctx, TcpSock sk, TcpPacketBuf pkt) {
        int err = super.tcp_data_queue(ctx, sk, pkt);
        if (!sk.hasConnection() || sk.state() == TcpConnectionState.TCP_CLOSED) {
            return err;
        }
        tcp_data_snd_check(sk);
        tcp_ack_snd_check(sk);
        if (sk.state() == TcpConnectionState.CLOSE_WAIT
                && sk.childChannel() != null
                && sk.childChannel().isOpen()) {
            sk.childChannel().close();
        }
        return err;
    }

    /**
     * 对齐 Linux {@code tcp_time_wait(sk, TCP_FIN_WAIT2, tmo)} 的调度入口
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
            tcp_time_wait(sk, TcpConnectionState.FIN_WAIT_2, tmo);
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
                tcp_time_wait(sk, TcpConnectionState.FIN_WAIT_2, tmo);
                return;
            }
        }
        sk.addShutdown(TcpConstants.RCV_SHUTDOWN);
        TcpOutput.INSTANCE.tcp_send_reset(sk);
        tcp_done(sk);
    }
}
