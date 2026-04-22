package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.core;

import com.github.pangolin.routing.acceptor.tun.fakedns.DnsEngine;
import com.github.pangolin.routing.acceptor.tun.net.codec.Tcp4PacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.codec.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.*;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.sock.TcpSock;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.sock.tcp_request_sock;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpOptionCodec;
import com.github.pangolin.routing.support.SocketChannelFactory;
import io.netty.channel.*;
import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpConstants.TCP_MAX_WSCALE;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpConstants.TCP_TIMEOUT_INIT;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpState.TCP_NEW_SYN_RECV;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpLogUtils.logFormat;

@Slf4j
public class TcpHandshaker {

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L7195">tcp_conn_request</a>
     */
    public static tcp_request_sock tcp_conn_request(Channel net,
                                                    TcpMultiplexer multiplexer,
                                                    request_sock_ops rsk_ops,
                                                    tcp_request_sock_ops af_ops,
                                                    TcpSock parent,
                                                    final TcpPacketBuf pkt,
                                                    DnsEngine dnsEngine,
                                                    SocketChannelFactory socketChannelFactory,
                                                    int connTimeoutMs, EventLoopGroup childGroup,
                                                    TcpOutput output) {
        if (multiplexer.sk_acceptq_is_full()) {
            return null;
        }

        /*-
         * 这里创建的 request_sock 状态是 TCP_NEW_SYN_RECV.
         * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/inet_connection_sock.c#L950">inet_reqsk_alloc</a>
         */
        tcp_request_sock req = inet_reqsk_alloc(dnsEngine, socketChannelFactory, connTimeoutMs, childGroup, parent, true);
        if (null == req) {
            return null;
        }

        // req.syncookie = want_cookie;
        req.af_specific = af_ops;
        req.ts_off = 0;
        req.req_usec_ts = false;

        final int user_mss = 0; // FIXME parent.rx_opt.user_mss;

        final tcp_options_received tmp_opt = new tcp_options_received();
        tmp_opt.mss_clamp = af_ops.mss_clamp;
        tmp_opt.user_mss = parent.rx_opt.user_mss;

        tcp_parse_options(parent, tmp_opt, pkt, false);

        tmp_opt.tstamp_ok = SysctlOptions.ipv4_sysctl_tcp_timestamps && tmp_opt.saw_tstmap != 0;
        tcp_openreq_init(req, tmp_opt, pkt);

        // dst = af_ops->route_req(sk, skb, &fl, req, isn)
        req.ir_loc_addr = pkt.dstAddr();
        req.ir_rmt_addr = pkt.srcAddr();

        // FIXME
        if (tmp_opt.tstamp_ok) {
            req.req_usec_ts = false;    // FIXME dst_tcp_usec_ts
            req.ts_off = af_ops.init_ts_off(pkt);
        }

        int isn = af_ops.init_seq(pkt);

        req.snt_isn = isn;
        // Store TOS for IPv4 (0 for IPv6)
        req.syn_tos = (pkt instanceof Tcp4PacketBuf) ? ((Tcp4PacketBuf) pkt).tos() & 0xFF : 0;

        tcp_openreq_init_rwin(parent, output, req, pkt);

        req.timeout = multiplexer.tcp_timeout_init(req);
        if (!inet_csk_reqsk_queue_hash_add(parent, req, req.timeout)) {
            return null;
        }

        final InetAddress dstAddr = req.ir_loc_addr;
        final int dstPort = req.ir_num;
        final String dstHostname;
        if (dnsEngine.isFakeAddress(dstAddr.getAddress())) {
            dstHostname = dnsEngine.getHostByAddress(dstAddr.getAddress());
            if (null == dstHostname || dstHostname.isEmpty()) {
                log.warn(logFormat("[TCP] [ERROR]", pkt, "Could not resolve FAKE-IP: {}"), dstAddr.getHostAddress());
                return null;
            }
        } else {
            dstHostname = null;
        }

        final InetSocketAddress resolved = null != dstHostname
                ? InetSocketAddress.createUnresolved(dstHostname, dstPort)
                : new InetSocketAddress(dstAddr, dstPort);


        final long sinceMs = System.currentTimeMillis();
        req.childCloseListener = new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                try {
                    // for TCP_NEW_SYN_RECV waiting SYN-ACK -> ACK
                    log.debug(logFormat("[TCP] [STATE]", pkt, "Connection to {}:{} has been disconnected"), resolved.getHostString(), resolved.getPort());

                    // FIXME RESET set reset.
                    rsk_ops.send_reset(net, parent, pkt, -100);
                    // output.tcp_send_active_reset(net, req, "Abort");
                    // FIXME clean queue
                    multiplexer.inet_csk_destroy_sock(req);
                } finally {
                    // 对应 connect 成功分支内为 listener 额外持有的那份 retain。
                    pkt.release();
                }
            }
        };


        pkt.retain();
        log.info(logFormat("[TCP] [STATE]", pkt, "ESTABLISHING connection to {}:{}"), resolved.getHostString(), resolved.getPort());
        req.child = socketChannelFactory.open(resolved, connTimeoutMs, false, childGroup, new ChannelInboundHandlerAdapter() {
        }).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                try {
                    if (future.isSuccess()) {
                        log.debug(logFormat("[TCP] [STATE]", pkt, "Connection ESTABLISHED to {}:{}"), resolved.getHostString(), resolved.getPort());

                        log.debug(logFormat(
                                "[TCP] [HANDSHAKE]",
                                pkt.dstAddr(), pkt.tcpDstPort(),
                                pkt.srcAddr(), pkt.tcpSrcPort(),
                                "Connection handshake 2/3: SYN-ACK"
                        ));
                        af_ops.send_synack(net, parent, req, pkt);
                        // childCloseListener 会晚于本回调触发,届时 connect 链上的 retain 已释放,
                        // 上游 handler 也早已归零 pkt。为 listener 独立 retain 一份,触发末尾 release。
                        pkt.retain();
                        future.channel().closeFuture().addListener(req.childCloseListener);
                    } else {
                        // FIXME conflict and child close listener send two RESET.
                        // FIXME RESET
                        log.info(logFormat("[TCP] [STATE]", pkt, "Unable to connect to {}:{}"), resolved.getHostString(), resolved.getPort());
                        log.debug(logFormat("[TCP] [HANDSHAKE]", pkt, "Connection handshake 3/3: ABORT"));
                        rsk_ops.send_reset(net, parent, pkt, -88);
                        // FIXME clean queue
                        multiplexer.inet_csk_destroy_sock(req);
                    }
                } finally {
                    pkt.release();
                }
            }
        });

        af_ops.addToHalfQueue(parent, req);

        return req;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/inet_connection_sock.c#L1190">inet_csk_reqsk_queue_hash_add</a>
     */
    private static boolean inet_csk_reqsk_queue_hash_add(TcpSock sk, tcp_request_sock req, long timeout) {
        if (!reqsk_queue_hash_req(req, timeout)) {
            return false;
        }

        // inet_csk_reqsk_queue_added(sk);
        return true;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/inet_connection_sock.c#L1170">reqsk_queue_hash_req</a>
     */
    private static boolean reqsk_queue_hash_req(tcp_request_sock req, long timeout) {
        // Timer is NOT started here. Because backend connection is async, the initial SYN-ACK
        // is sent only after the backend connects. The retransmission timer is started inside
        // tcp_v4_send_synack() once the first SYN-ACK write succeeds.
        req.timeout = timeout;
        req.num_retrans = 0;
        req.rsk_timer = null;
        return true;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/inet_connection_sock.c#L1027"></a>
     */
    private static void reqsk_timer_handler(tcp_request_sock req) {
    }



    /**
     * https://github.com/torvalds/linux/blob/master/net/ipv4/inet_connection_sock.c#L891.
     */
    private static tcp_request_sock inet_reqsk_alloc(
                                                     DnsEngine dnsEngine,
                                                     SocketChannelFactory socketChannelFactory,
                                                     int connTimeoutMs, EventLoopGroup childGroup,
                                                     TcpSock skListener,
                                                     boolean attachListener) {
        tcp_request_sock req = reqsk_alloc(dnsEngine, socketChannelFactory, connTimeoutMs, childGroup, skListener, attachListener);
        if (null != req) {
            req.state(TCP_NEW_SYN_RECV);
            req.timeout = TCP_TIMEOUT_INIT;
        }

        return req;
    }

    private static tcp_request_sock reqsk_alloc(
                                                DnsEngine dnsEngine,
                                                SocketChannelFactory socketChannelFactory,
                                                int connTimeoutMs, EventLoopGroup childGroup, TcpSock skListener,
                                                boolean attachListener) {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/inet_connection_sock.c#L891

        final tcp_request_sock req = new tcp_request_sock();
        req.skc_listener = null;
        if (attachListener) {
            req.skc_listener = skListener;
        }

        return req;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L7068">tcp_openreq_init</a>
     * @see <a href="https://www.cnblogs.com/wanpengcoder/p/11751292.html">TCP MSS</a>
     */
    private static void tcp_openreq_init(tcp_request_sock req, tcp_options_received rx_opt, final TcpPacketBuf pkt) {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L7068
        req.rsk_rcv_wnd = 0;
        req.rcv_isn = pkt.tcpSeq();
        req.rcv_nxt = pkt.tcpSeq() + 1;
        // req.snt_synack = 0;
        // req.last_oow_ack_time = 0;

        req.mss = rx_opt.mss_clamp;
        req.snd_wscale = rx_opt.snd_wscale;
        req.wscale_ok = rx_opt.wscale_ok;
        req.tstamp_ok = rx_opt.tstamp_ok;
        req.ts_recent = rx_opt.rcv_tsval;

        req.ir_rmt_port = pkt.tcpSrcPort();
        req.ir_num = pkt.tcpDstPort();
        req.snd_wnd = pkt.tcpWindow();
    }

    private static void tcp_openreq_init_rwin(TcpSock pSock, TcpOutput output, tcp_request_sock req, TcpPacketBuf pkt) {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c#L422
        int full_space = output.tcp_full_space(pSock);
        final int mss = pSock.tcp_mss_clamp(pSock, pSock.dst_metric_advmss());

        // FIXME
        final int window_clamp = pSock.window_clamp;

        AtomicInteger req_rsk_window_clamp_ref = new AtomicInteger(window_clamp > 0 ? window_clamp : pSock.dst_metric(TcpConstants.RTAX_WINDOW));
        AtomicInteger req_rsk_rcv_wnd_ref = new AtomicInteger();


        int rcv_wnd = 0; //...
        if (rcv_wnd == 0) {
            rcv_wnd = pSock.dst_metric(TcpConstants.RTAX_INITRWND);
        } else if (full_space < rcv_wnd * mss) {
            full_space = rcv_wnd * mss;
        }
        // ...

        AtomicInteger rcv_wscale_ref = new AtomicInteger();
        output.tcp_select_initial_window(
                pSock,
                full_space,
                mss, // - stamp
                req_rsk_rcv_wnd_ref,
                req_rsk_window_clamp_ref,
                req.wscale_ok,
                rcv_wscale_ref,
                rcv_wnd
        );

        req.rcv_wscale = rcv_wscale_ref.get();
        req.rsk_rcv_wnd = req_rsk_rcv_wnd_ref.get();
        req.rsk_window_clamp = req_rsk_window_clamp_ref.get();
    }

    private static void tcp_parse_options(TcpSock tp, tcp_options_received opt_rx, final TcpPacketBuf pkt, final boolean estab) {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L4183
        if (!pkt.isSyn() || estab) {
            return;
        }
        final io.netty.buffer.ByteBuf opts = pkt.tcpOptionsSlice();
        final int mss = TcpOptionCodec.parseMss(opts);
        if (mss > 0) {
            int user_mss = opt_rx.user_mss;
            opt_rx.mss_clamp = user_mss > 0 && user_mss < mss ? user_mss : mss;
        }
        if (SysctlOptions.ipv4_sysctl_tcp_window_scaling) {
            final int wscale = TcpOptionCodec.parseWindowScale(opts);
            if (wscale >= 0) {
                opt_rx.wscale_ok = true;
                opt_rx.snd_wscale = (byte) Math.min(wscale, TCP_MAX_WSCALE);
            }
        }
        if (SysctlOptions.ipv4_sysctl_tcp_timestamps) {
            final long[] ts = TcpOptionCodec.parseTimestamp(opts);
            if (ts != null) {
                opt_rx.saw_tstmap = 1;
                opt_rx.rcv_tsval = ts[0];
                opt_rx.rcv_tsecr = ts[1];
            }
        }
    }
}
