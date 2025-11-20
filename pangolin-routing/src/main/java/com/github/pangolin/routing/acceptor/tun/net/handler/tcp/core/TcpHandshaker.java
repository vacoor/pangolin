package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.core;

import com.github.pangolin.routing.acceptor.tun.fakedns.DnsEngine;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.*;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpSock;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.tcp_options_received;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.tcp_request_sock;
import com.github.pangolin.routing.support.SocketChannelFactory;
import io.netty.channel.*;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;
import org.pcap4j.packet.IpPacket;
import org.pcap4j.packet.TcpMaximumSegmentSizeOption;
import org.pcap4j.packet.TcpPacket;
import org.pcap4j.packet.TcpWindowScaleOption;
import org.pcap4j.packet.namednumber.TcpPort;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpConstants.TCP_MAX_WSCALE;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpState.TCP_NEW_SYN_RECV;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpUtils.logPrefix;

@Slf4j
public class TcpHandshaker {

    /**
     * @param tcpPacket
     * @return
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L7195">tcp_conn_request</a>
     */
    public static tcp_request_sock tcp_conn_request(tcp_request_sock_ops rsk_ops,
                                                    tcp_request_sock_ipv4_ops af_ops,
                                                    TcpSock listenSock,
                                                    final IpPacket ipPacket,
                                                    final TcpPacket tcpPacket,
                                                    DnsEngine dnsEngine,
                                                    SocketChannelFactory socketChannelFactory,
                                                    int connTimeoutMs, EventLoopGroup childGroup,
                                                    TcpOutput output) {
        final IpPacket.IpHeader ipHdr = ipPacket.getHeader();
        /*-
         * 这里创建的 request_sock 状态是 TCP_NEW_SYN_RECV.
         * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/inet_connection_sock.c#L950">inet_reqsk_alloc</a>
         */
        tcp_request_sock req = inet_reqsk_alloc(ipHdr, tcpPacket, dnsEngine, socketChannelFactory, connTimeoutMs, childGroup);
        req.parentSock = listenSock;
        if (null == req) {
            return null;
        }
        req.INDIRECT_CALL_INET = af_ops::INDIRECT_CALL_INET;

        req.ts_off = 0;
        req.req_usec_ts = false;


        final int user_mss = 0; // FIXME parent.rx_opt.user_mss;// setsockopt(sockfd, IPPROTO_TCP, TCP_MAXSEG, &mss, sizeof(mss))

        final tcp_options_received opt_rx = new tcp_options_received();
        opt_rx.mss_clamp = af_ops.mss_clamp;
        opt_rx.user_mss = user_mss;

        tcp_parse_options(listenSock, opt_rx, tcpPacket, false);
        tcp_openreq_init(req, opt_rx, tcpPacket);

        // ...

        // FIXME
        final boolean opt_tstamp_ok = opt_rx.tstamp_ok;
        if (opt_tstamp_ok) {
            req.req_usec_ts = false;    // FIXME dst_tcp_usec_ts
            req.ts_off = af_ops.init_ts_off(tcpPacket);
        }

        int isn = af_ops.init_seq(ipHdr, tcpPacket.getHeader());
        req.snt_isn = isn;

        // init rwin
        tcp_openreq_init_rwin(listenSock, output, req, tcpPacket);

        req.state(TCP_NEW_SYN_RECV);

        req.child.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture channelFuture) throws Exception {
                if (channelFuture.isSuccess()) {
                    af_ops.send_synack(listenSock, req, ipHdr, tcpPacket);
                } else {
                    rsk_ops.send_reset(listenSock, ipPacket, -88);
                }

            }
        });

        af_ops.addToHalfQueue(listenSock, req);


        // send_synack
        // af_ops.send_synack(listenSock, req, ipHdr, skb);

        return req;
    }

    private static tcp_request_sock inet_reqsk_alloc(IpPacket.IpHeader ipHeader, TcpPacket skb,
                                                     DnsEngine dnsEngine,
                                                     SocketChannelFactory socketChannelFactory,
                                                     int connTimeoutMs, EventLoopGroup childGroup) {
        final InetAddress srcAddr = ipHeader.getSrcAddr();
        final InetAddress dstAddr = ipHeader.getDstAddr();
        final TcpPort tcpSrcPort = skb.getHeader().getSrcPort();
        final TcpPort tcpDstPort = skb.getHeader().getDstPort();
        final int srcPort = tcpSrcPort.valueAsInt();
        final int dstPort = tcpDstPort.valueAsInt();


        final tcp_request_sock req = new tcp_request_sock();
        req.rawIpHeader = ipHeader;
        req.srcAddr = srcAddr;
        req.dstAddr = dstAddr;
        req.srcPort = tcpSrcPort;
        req.dstPort = tcpDstPort;

        final String dstHostname;
        if (dnsEngine.isFakeAddress(dstAddr.getAddress())) {
            dstHostname = dnsEngine.getHostByAddress(dstAddr.getAddress());
            if (null == dstHostname || dstHostname.isEmpty()) {
                tcpLogError(null, srcAddr, srcPort, dstAddr, dstPort, "Can't resolve fake IP: {}", dstAddr.getHostAddress());
                return null;
            }
        } else {
            dstHostname = null;
        }

        final InetSocketAddress resolved = null != dstHostname
                ? InetSocketAddress.createUnresolved(dstHostname, dstPort)
                : new InetSocketAddress(dstAddr, dstPort);

        final long sinceMs = System.currentTimeMillis();

        tcpLogInfo(null, srcAddr, srcPort, dstAddr, dstPort, "ESTABLISHING -> {}", resolved);

        final AtomicBoolean initialized = new AtomicBoolean(false);
        final ChannelFuture cf = socketChannelFactory.open(resolved, connTimeoutMs, false, childGroup, new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
                try {
                    if (initialized.compareAndSet(false, true)) {
                        tcpLogInfo(null, srcAddr, srcPort, dstAddr, dstPort, "First Response elapsed: {}ms", System.currentTimeMillis() - sinceMs);
                    }
                    ctx.fireChannelRead(ReferenceCountUtil.retain(msg));
                } finally {
                    ReferenceCountUtil.release(msg);
                }
            }
        });

        try {
            req.child = cf;
//            state.set(TcpState.TCP_SYN_RECV);

//            final long elapsedMs = System.currentTimeMillis() - sinceMs;
//            tcpLogInfo(null, srcAddr, srcPort, dstAddr, dstPort, "ESTABLISHED: {} elapsed: {}ms", resolved, elapsedMs);


            return req;
        } catch (Exception e) {
            tcpLogError(null, srcAddr, srcPort, dstAddr, dstPort, "CONNECTION RESET by {}: {}", e.getMessage(), resolved, e);
            return null;
        }
    }

    /**
     * @param skb
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L7068">tcp_openreq_init</a>
     * @see <a href="https://www.cnblogs.com/wanpengcoder/p/11751292.html">TCP MSS</a>
     */
    private static void tcp_openreq_init(tcp_request_sock req, tcp_options_received rx_opt, final TcpPacket skb) {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L7068
        final TcpPacket.TcpHeader hdr = skb.getHeader();
        req.rsk_rcv_wnd = 0;
        req.rcv_isn = hdr.getSequenceNumber();
        req.rcv_nxt = hdr.getSequenceNumber() + 1;

        req.mss = rx_opt.mss_clamp;
        req.snd_wscale = rx_opt.snd_wscale;
        req.wscale_ok = rx_opt.wscale_ok;

        req.srcPort = hdr.getSrcPort();
        req.dstPort = hdr.getDstPort();
    }

    private static void tcp_openreq_init_rwin(TcpSock pSock, TcpOutput output, tcp_request_sock req, TcpPacket skb) {
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

    static void tcpLogInfo(String traceId,
                           InetAddress srcAddr, int srcPort,
                           InetAddress dstAddr, int dstPort,
                           String message, final Object... args) {
        final String prefix = logPrefix(traceId, srcAddr.getHostAddress(), srcPort, dstAddr.getHostAddress(), dstPort);
        log.error(prefix + " " + message, args);
    }

    public static void tcpLogError(String traceId,
                                   InetAddress srcAddr, int srcPort,
                                   InetAddress dstAddr, int dstPort,
                                   String message, Object... args) {
        final String prefix = logPrefix(traceId, srcAddr.getHostAddress(), srcPort, dstAddr.getHostAddress(), dstPort);
        log.error(prefix + " " + message, args);
    }

    private static void tcp_parse_options(TcpSock tp, tcp_options_received opt_rx, final TcpPacket skb, final boolean estab) {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L4183
        final TcpPacket.TcpHeader hdr = skb.getHeader();
        for (final TcpPacket.TcpOption option : hdr.getOptions()) {
            if (option instanceof TcpMaximumSegmentSizeOption && hdr.getSyn() && !estab) {
                int inMss = ((TcpMaximumSegmentSizeOption) option).getMaxSegSizeAsInt();
                if (inMss > 0) {
                    int user_mss = opt_rx.user_mss;
                    inMss = user_mss > 0 && user_mss < inMss ? user_mss : inMss;
                    opt_rx.mss_clamp = inMss;
                }
            } else if (option instanceof TcpWindowScaleOption && hdr.getSyn() && !estab && SysctlOptions.sysctl_tcp_window_scaling) {
                final byte wscale = ((TcpWindowScaleOption) option).getShiftCount();
                opt_rx.wscale_ok = true;
                opt_rx.snd_wscale = wscale > TCP_MAX_WSCALE ? TCP_MAX_WSCALE : wscale;
            }
        }
    }
}
