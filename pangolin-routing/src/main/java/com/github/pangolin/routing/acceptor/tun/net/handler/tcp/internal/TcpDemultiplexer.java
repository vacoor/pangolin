package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal;

import com.github.pangolin.routing.acceptor.tun.fakedns.DnsEngine;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.v2.*;
import com.github.pangolin.routing.support.SocketChannelFactory;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;
import org.pcap4j.packet.IpPacket;
import org.pcap4j.packet.IpPacket.IpHeader;
import org.pcap4j.packet.Packet;
import org.pcap4j.packet.TcpPacket;
import org.pcap4j.packet.TcpPacket.TcpHeader;
import org.pcap4j.packet.UnknownPacket;
import org.pcap4j.packet.namednumber.TcpPort;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpClock.*;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpConstants.*;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpDropReason.SKB_DROP_REASON_TCP_ABORT_ON_DATA;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpState.*;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpTimer.*;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpUtils.*;

@Slf4j
public abstract class TcpDemultiplexer<T extends IpPacket> extends TcpSock {

    static final short IP_HEADER_SIZE = 20;
    private static final short TCP_HEADER_SIZE = 20;

    // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L943
    public static final int TCPCB_SACKED_ACKED = (1 << 0);    /* SKB ACK'd by a SACK block	*/
    public static final int TCPCB_SACKED_RETRANS = (1 << 1);    /* SKB retransmitted		*/
    public static final int TCPCB_LOST = (1 << 2);    /* SKB is lost			*/
    public static final int TCPCB_TAGBITS = (TCPCB_SACKED_ACKED | TCPCB_SACKED_RETRANS | TCPCB_LOST);    /* All tag bits			*/
    public static final int TCPCB_REPAIRED = (1 << 4);    /* SKB repaired (no skb_mstamp_ns)	*/
    public static final int TCPCB_EVER_RETRANS = (1 << 7);    /* Ever retransmitted frame	*/
    public static final int TCPCB_RETRANS = (TCPCB_SACKED_RETRANS | TCPCB_EVER_RETRANS | TCPCB_REPAIRED);

    public long ipv4_sysctl_tcp_invalid_ratelimit = HZ / 2;
    public int ipv4_sysctl_tcp_challenge_ack_limit = HZ / 2;
    public long ipv4_tcp_challenge_timestamp;
    public int ipv4_tcp_challenge_count;


    IpHeader ipHeader;
    TcpPort tcpSrcPort;
    TcpPort tcpDstPort;


    /**
     *
     */
    protected final Channel parent;
    private final DnsEngine dnsEngine;
    private final EventLoopGroup childGroup;
    private final SocketChannelFactory socketChannelFactory;

//    volatile Channel child;
    private int connTimeoutMs = 10 * 1000;

    TcpInput<T> input;
    TcpOutput<T> output;

    protected TcpDemultiplexer(final Channel parent, final EventLoopGroup childGroup, final DnsEngine dnsEngine, final SocketChannelFactory socketChannelFactory) {
        super();
        this.parent = parent;
        this.childGroup = childGroup;
        this.dnsEngine = dnsEngine;
        this.socketChannelFactory = socketChannelFactory;
        this.output = new TcpOutput<T>();
        this.input = new TcpInput<>(this.output);
        init();
        this.listen();
    }

    protected void init() {
        tcp_init_sock(this);
    }

    private TcpDemultiplexer<T> listen() {
        inet_listen(100);
        return this;
    }

    /**
     * Move a socket into listening state.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/af_inet.c#L230">inet_listen</a>
     */
    private int inet_listen(int backlog) {
        return __inet_listen_sk(backlog);
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/af_inet.c#L191">__inet_listen_sk</a>
     */
    private int __inet_listen_sk(int backlog) {
        TcpState s = this.state.get();
        int st = s.ordinal();
        if (0 == ((1 << st) & (TcpConstants.TCPF_CLOSE | TcpConstants.TCPF_LISTEN))) {
            // return -EINVAL;
            return -1;
        }

        /* Really, if the socket is already in listen state
         * we can only allow the backlog to be adjusted.
         */
        if (!TCP_LISTEN.equals(s)) {
            // ...
            int err = inet_csk_listen_start();
            if (err != 0) {
                return err;
            }
            // ...
        }

        return 0;
    }

    /**
     * https://github.com/torvalds/linux/blob/master/net/ipv4/inet_connection_sock.c#L1342.
     */
    private int inet_csk_listen_start() {
        // reqsk_queue_alloc(&icsk->icsk_accept_queue);

        // inet_csk_delack_init
        state.compareAndSet(TcpState.TCP_CLOSE, TCP_LISTEN);
        return 0;
    }

    public void handler(final T ipHeader, final TcpPacket tcpPacket) {
        if (null != child && child.isOpen()) {
            child.eventLoop().execute(() -> tcp_rcv(ipHeader, tcpPacket));
        } else {
            tcp_rcv(ipHeader, tcpPacket);
        }
    }

    protected abstract void tcp_rcv(final T ipHeader, final TcpPacket tcpPacket);

    // ...


    // https://www.cnblogs.com/wanpengcoder/p/11751763.html


    private int TCPOLEN_TSTAMP_ALIGNED = 12;

    /* ************** Initialize Connection Request [[ ************ */

    protected tcp_request_sock conn_request(TcpDemultiplexer<T> p, final T ih, final TcpPacket skb) {
        return tcp_conn_request(new tcp_request_sock_ops(), new tcp_request_sock_ipv4_ops(), p, ih, skb);
    }

    /**
     * @param skb
     * @return
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L7195">tcp_conn_request</a>
     */
    private tcp_request_sock tcp_conn_request(tcp_request_sock_ops rsk_ops, tcp_request_sock_ipv4_ops af_ops,
                                              TcpDemultiplexer<T> pSock,
                                              final T pkg, final TcpPacket skb) {
        final IpHeader ipHdr = pkg.getHeader();
        /*-
         * 这里创建的 request_sock 状态是 TCP_NEW_SYN_RECV.
         * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/inet_connection_sock.c#L950">inet_reqsk_alloc</a>
         */
        tcp_request_sock req = inet_reqsk_alloc(ipHeader, skb);
        if (null == req) {
            return null;
        }

        req.ts_off = 0;
        req.req_usec_ts = false;


        final int user_mss = 0; // FIXME parent.rx_opt.user_mss;// setsockopt(sockfd, IPPROTO_TCP, TCP_MAXSEG, &mss, sizeof(mss))

        final tcp_options_received opt_rx = new tcp_options_received();
        opt_rx.mss_clamp = af_ops.mss_clamp;
        opt_rx.user_mss = user_mss;

        input.tcp_parse_options(pSock, opt_rx, skb, false);
        tcp_openreq_init(req, opt_rx, skb);

        ipHeader = ipHdr;
        tcpSrcPort = skb.getHeader().getSrcPort();
        tcpDstPort = skb.getHeader().getDstPort();
        // ...

        // FIXME
        final boolean opt_tstamp_ok = opt_rx.tstamp_ok;
        if (opt_tstamp_ok) {
            req.req_usec_ts = false;    // FIXME dst_tcp_usec_ts
            req.ts_off = init_ts_off(skb);
        }

        int isn = af_ops.init_seq(ipHdr, skb.getHeader());

//        req_snt_isn_ref.set(isn = initSeq(ipHdr, skb.getHeader()));
//        isn = initSeq(ipHdr, skb.getHeader());

        req.snt_isn = isn;

        // init rwin
        tcp_openreq_init_rwin(pSock, req, skb);

        // send_synack
        af_ops.send_synack(pSock, req, ipHdr, skb);

        return req;
    }


    private tcp_request_sock inet_reqsk_alloc(IpHeader ipHeader, TcpPacket skb) {
        final InetAddress dstAddr = ipHeader.getDstAddr();
        final int dstPort = skb.getHeader().getDstPort().valueAsInt();

        final String dstHostname;
        if (dnsEngine.isFakeAddress(dstAddr.getAddress())) {
            dstHostname = dnsEngine.getHostByAddress(dstAddr.getAddress());
            if (null == dstHostname || dstHostname.isEmpty()) {
                logError("Can't resolve fake IP: {}", dstAddr.getHostAddress());
                return null;
            }
        } else {
            dstHostname = null;
        }

        final InetSocketAddress resolved = null != dstHostname
                ? InetSocketAddress.createUnresolved(dstHostname, dstPort)
                : new InetSocketAddress(dstAddr, dstPort);

        final long sinceMs = System.currentTimeMillis();

        logInfo("ESTABLISHING -> {}", resolved);

        final AtomicBoolean initialized = new AtomicBoolean(false);
        final ChannelFuture cf = socketChannelFactory.open(resolved, connTimeoutMs, true, childGroup, new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
                try {
                    if (initialized.compareAndSet(false, true)) {
                        logInfo("First Response elapsed: {}ms", System.currentTimeMillis() - sinceMs);
                    }

                    logTrace("Read from {}", resolved);
                    final ByteBuf buf = (ByteBuf) msg;
                    final byte[] payload = ByteBufUtil.getBytes(buf);

                    // tcp data len = tcp snd.mss - tcp options.len
                    // 超过 tcp data len 不切割, 会使用TSO功能通过网卡来分段.
                    /*
                    tcp_sendmsg2(new TcpBuffer()
                            .ack(true)
                            .psh(true)
                            .payloadBuilder(
                                    UnknownPacket.newPacket(payload, 0, payload.length).getBuilder()
                            ), true);
                            */

                    final int mss = output.tcp_current_mss(TcpDemultiplexer.this);
                    for (int offset = 0; offset < payload.length; ) {
                        final int len = payload.length - offset;
                        if (len <= mss) {
                            final UnknownPacket.Builder builder = UnknownPacket.newPacket(payload, offset, len).getBuilder();
                            tcp_sendmsg2(new TcpBuffer().ack(true)
                                    //.psh(true)
                                    .payloadBuilder(builder), true);
                            offset += len;
                        } else {
                            UnknownPacket.Builder builder = UnknownPacket.newPacket(payload, offset, mss).getBuilder();
                            tcp_sendmsg2(new TcpBuffer().ack(true)
                                    // .psh(true)
                                    .payloadBuilder(builder), false);
                            offset += mss;
                        }
                    }
                } finally {
                    ReferenceCountUtil.release(msg);
                }
            }

            @Override
            public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) throws Exception {
                logError("Exception caught: {}", cause.getMessage(), cause);
                send_reset(ipHeader, new TcpPacket.Builder()
                        .srcAddr(ipHeader.getSrcAddr())
                        .dstAddr(ipHeader.getDstAddr())
                        .srcPort(tcpSrcPort)
                        .dstPort(tcpDstPort)
                        .ack(true)
                        .acknowledgmentNumber(rcv_nxt)
                        .build(), -1);
                try {
                    if (ctx.channel().isOpen()) {
                        ctx.channel().close();
                    }
                } finally {
                    tcp_done();
                }
            }
        });

        try {
            child = cf.sync().channel();
            state.set(TcpState.TCP_SYN_RECV);
            final long elapsedMs = System.currentTimeMillis() - sinceMs;
            logInfo("ESTABLISHED: {} elapsed: {}ms", resolved, elapsedMs);
            child.closeFuture().addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(final ChannelFuture future) throws Exception {
                    logInfo("DISCONNECTED: {}", resolved);
                    if (tcp_close_state()) {
                        output.tcp_send_fin(TcpDemultiplexer.this);
                    }
//                    shutdown(SEND_SHUTDOWN);
                }
            });


            final tcp_request_sock req = new tcp_request_sock();
            req.srcAddr = ipHeader.getSrcAddr();
            req.dstAddr = ipHeader.getDstAddr();

            return req;
        } catch (Exception e) {
            logInfo("CONNECTION RESET by {}: {}", e.getMessage(), resolved, e);
            return null;
        }
    }


    protected abstract void send_reset(IpHeader request, TcpPacket skb, int err);

    /**
     * @param skb
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L7068">tcp_openreq_init</a>
     * @see <a href="https://www.cnblogs.com/wanpengcoder/p/11751292.html">TCP MSS</a>
     */
    private void tcp_openreq_init(tcp_request_sock req, tcp_options_received rx_opt, final TcpPacket skb) {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L7068
        final TcpHeader hdr = skb.getHeader();
        req.rsk_rcv_wnd = 0;
        req.rcv_isn = hdr.getSequenceNumber();
        req.rcv_nxt = hdr.getSequenceNumber() + 1;

        req.mss = rx_opt.mss_clamp;
        req.snd_wscale = rx_opt.snd_wscale;
        req.wscale_ok = rx_opt.wscale_ok;

        req.srcPort = hdr.getSrcPort();
        req.dstPort = hdr.getDstPort();

//        req_rsk_rcv_wnd_ref.set(0);
//        req_rcv_isn_ref.set(hdr.getSequenceNumber());
//        req_rcv_nxt_ref.set(hdr.getSequenceNumber() + 1);
//
//        req_mss_ref.set(rx_opt.mss_clamp);
//
//        ireq_wscale_ok_ref.set(rx_opt.wsacle_ok);
//        ireq_snd_wscale_ref.set(rx_opt.snd_wscacle);
    }

    /**
     * @param ipHdr
     * @param tcpHdr
     * @return
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L103">tcp_v4_init_seq</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/core/secure_seq.c#L136">secure_tcp_seq</a>
     */
    protected int initSeq(final IpHeader ipHdr, final TcpHeader tcpHdr) {
        // return tcpHdr.getSequenceNumber();
        return secureSeq(
                ipHdr.getSrcAddr().getAddress(), tcpHdr.getSrcPort().value(),
                ipHdr.getDstAddr().getAddress(), tcpHdr.getDstPort().value()
        );
    }

    private void tcp_openreq_init_rwin(TcpDemultiplexer<T> pSock, tcp_request_sock req, TcpPacket skb) {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c#L422
        int full_space = output.tcp_full_space(pSock);
        final int mss = tcp_mss_clamp(pSock, dst_metric_advmss());

        // FIXME
        final int window_clamp = pSock.window_clamp;

        AtomicInteger req_rsk_window_clamp_ref = new AtomicInteger(window_clamp > 0 ? window_clamp : dst_metric(TcpConstants.RTAX_WINDOW));
        AtomicInteger req_rsk_rcv_wnd_ref = new AtomicInteger();


        int rcv_wnd = 0; //...
        if (rcv_wnd == 0) {
            rcv_wnd = dst_metric(TcpConstants.RTAX_INITRWND);
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


    protected abstract void send_synack(final TcpDemultiplexer<T> p, final tcp_request_sock req, final IpHeader ipHdr, final TcpPacket syn_skb);


    /* ************** ]] Initialize Connection Request ************ */

    /* **************** Open Connection Request [[ *************/

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L2179">tcp_v4_rcv</a> TCP_NEW_SYN_RECV
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c#L660">tcp_check_req</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L1742">tcp_v4_syn_recv_sock</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c#L518">tcp_create_openreq_child</a> <==
     */
    private TcpDemultiplexer tcp_check_req(tcp_request_sock req, final TcpPacket skb) {
        TcpDemultiplexer nsk = tcp_v4_syn_recv_sock(req, skb);
        return nsk;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L2179">tcp_v4_rcv</a> TCP_NEW_SYN_RECV
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c#L660">tcp_check_req</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L1742">tcp_v4_syn_recv_sock</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c#L518">tcp_create_openreq_child</a> <==
     */
    private TcpDemultiplexer<T> tcp_v4_syn_recv_sock(tcp_request_sock req, final TcpPacket skb) {
        TcpDemultiplexer<T> newsk = tcp_create_openreq_child(this, req, skb);

        newsk.icsk_ext_hdr_len = 0;
        output.tcp_sync_mss(this, dst_mtu());
        advmss = tcp_mss_clamp(newsk, dst_metric_advmss());
        tcp_initialize_rcv_mss(newsk);
        return newsk;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L2179">tcp_v4_rcv</a> TCP_NEW_SYN_RECV
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c#L660">tcp_check_req</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L1742">tcp_v4_syn_recv_sock</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c#L518">tcp_create_openreq_child</a> <==
     */
    private TcpDemultiplexer<T> tcp_create_openreq_child(TcpDemultiplexer<T> sk, tcp_request_sock req, final TcpPacket skb) {
        /*-
         * 第一步调用 <code>inet_csk_clone_lock<code/> 基于原 TCP_NEW_SYN_RECV sock clone时会将状态设置为 TCP_SYN_RECV.
         * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/inet_connection_sock.c#L1247"></a>
         */
        TcpDemultiplexer<T> newsk = inet_csk_clone_lock(sk, req);
        TcpDemultiplexer<T> newtp = newsk;

        // FIXME
        // rcv_isn = req.rcv_isn;

        int _seq = req.rcv_isn + 1;

        newtp.rcv_wup = _seq;
        newtp.copied_seq = _seq;
        newtp.rcv_nxt = _seq;
        // rcv_wup = copied_seq = rcv_nxt = _seq;
        // newtp.segs_in = 1;

        // FIXME
        // snt_isn = req.snt_isn;

        _seq = req.snt_isn + 1;
        newtp.snd_sml = newtp.snd_una = _seq;
        newtp.snd_nxt = _seq;
//        snd_sml = snd_una = snd_nxt = snd_up = _seq;
        newtp.snd_up = _seq;

        newtp.tcp_init_wl(req.rcv_isn);

        // ...
        newsk.icsk_ack.lrcvtime = tcp_jiffies32();

        newsk.lsndtime = tcp_jiffies32();
        // newsk.total_retrans = req->num_retrans;

        timer.tcp_init_xmit_timers(sk);
        newtp.write_seq = newtp.pushed_seq = req.snt_isn + 1;

        // ... keepopen

        // newtp.rx_opt.tstamp_ok = req.tstamp_ok;
        newtp.window_clamp = req.rsk_window_clamp;
        newtp.rcv_ssthresh = req.rsk_rcv_wnd;
        newtp.rcv_wnd = req.rsk_rcv_wnd;
        newtp.rx_opt.wscale_ok = req.wscale_ok;
        if (newtp.rx_opt.wscale_ok) {
            newtp.rx_opt.snd_wscale = (byte) req.snd_wscale;
            newtp.rx_opt.rcv_wscale = (byte) req.rcv_wscale;
        } else {
            newtp.rx_opt.snd_wscale = 0;
            newtp.rx_opt.rcv_wscale = 0;
            newtp.window_clamp = Math.min(window_clamp, TcpConstants.U16_MAX);
        }

        newtp.snd_wnd = skb.getHeader().getWindow() << newtp.rx_opt.snd_wscale;
        newtp.max_window = newtp.snd_wnd;

        boolean rx_opt_tstamp = newtp.rx_opt.tstamp_ok;
        if (rx_opt_tstamp) {
            newtp.tcp_usec_ts = req.req_usec_ts ? 1 : 0;
            // ts_recent = req_ts_recent ;
            // ts_recent_stamp = ktime_get_seconds();
            newtp.tcp_header_len = SIZE_OF_TCP_HDR + TCPOLEN_TSTAMP_ALIGNED;
        } else {
            newtp.tcp_usec_ts = 0;
            // ts_recent_stamp = 0;
            newtp.tcp_header_len = SIZE_OF_TCP_HDR;
        }

        // ...

        // newtp.tsoffset = req.ts_off;

        newtp.rx_opt.mss_clamp = req.mss;
        return newtp;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/inet_connection_sock.c#L1216">inet_csk_clone_lock</a>
     */
    private TcpDemultiplexer<T> inet_csk_clone_lock(final TcpDemultiplexer<T> sk, tcp_request_sock req) {
        final TcpDemultiplexer<T> newsk = sk; // sk_clone_lock

//         newsk.inet_dport = req....
//         newsk.inet_sport = ...

        newsk.srcAddr = req.srcAddr;
        newsk.dstAddr = req.dstAddr;
        newsk.srcPort = req.srcPort;
        newsk.dstPort = req.dstPort;


        newsk.icsk_retransmits = 0;
        newsk.icsk_backoff = 0;
        newsk.icsk_probes_out = 0;
        newsk.icsk_probes_tstamp = 0;

        newsk.state(TCP_SYN_RECV);

        return newsk;
    }


    // FIXME TODO tcp_init_sock https://github.com/torvalds/linux/blob/master/net/ipv4/tcp.c#L422
    // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L2492
    protected void tcp_init_sock(final TcpDemultiplexer<T> sk) {
        // ...

//        timer.tcp_init_xmit_timers(sk);

        sk.icsk_rto = TcpConstants.TCP_TIMEOUT_INIT;

        final int rto_max_ms = TCP_RTO_MAX; //sk.ipv4_sysctl_tcp_rto_max_ms;
        sk.icsk_rto_max = (int) msecs_to_jiffies(rto_max_ms);

        final int rto_min_ms = TCP_RTO_MIN; //sk.ipv4_sysctl_tcp_rto_min_ms;
        sk.icsk_rto_min = (int) msecs_to_jiffies(rto_min_ms);

        sk.icsk_delack_max = TcpTimer.TCP_DELACK_MAX;
        sk.mdev_us = (int) jiffies_to_usecs(TCP_TIMEOUT_INIT);


        sk.tcp_snd_cwnd_set(TcpConstants.TCP_INIT_CWND);

        /*-
         * See draft-stevens-tcpca-spec-01 for discussion of the
         * initialization of these values.
         */
        sk.snd_ssthresh = TcpConstants.TCP_INFINITE_SSTHRESH;
        // sk.snd_cwnd_clamp = ~0;
        sk.snd_cwnd_clamp = Integer.MAX_VALUE;
        sk.mss_cache = TCP_MSS_DEFAULT;

        // sk.tsoffset= 0;

//        sk->sk_write_space = sk_stream_write_space;
//        sock_set_flag(sk, SOCK_USE_WRITE_QUEUE);

        // icsk->icsk_sync_mss = tcp_sync_mss;

//        WRITE_ONCE(sk->sk_sndbuf, READ_ONCE(sock_net(sk)->ipv4.sysctl_tcp_wmem[1]));
//        WRITE_ONCE(sk->sk_rcvbuf, READ_ONCE(sock_net(sk)->ipv4.sysctl_tcp_rmem[1]));

        tcp_scaling_ratio_init(sk);
//        scaling_ratio = TCP_DEFAULT_SCALING_RATIO;
    }

    private void tcp_scaling_ratio_init(final TcpSock sk) {
//        sk.scaling_ratio = TCP_DEFAULT_SCALING_RATIO;
    }


    private void tcp_init_metrics() {
    }



    private void tcp_init_congestion_control() {
    }


    /* **************** ]] Open Connection Request *************/

    static final int EPIPE = 32;

    static final int ECONNREFUSED = 61;
    static final int ECONNRESET = 104;
    static final int ETIMEOUT = 110;


    protected void inet_csk_destroy_sock() {
        if (!TCP_CLOSE.equals(state.get())) {
            // ...
        }

        if (null != child && child.isOpen()) {
            child.close();
        }
        destroy0();
    }

    protected void destroy0() {

    }


    /* *************** */
    /* *************** */
    /* *************** */

    void tcp_measure_rcv_mss(TcpPacket skb) {
        // FIXME
        final int lss = icsk_ack.last_seg_size;

        icsk_ack.last_seg_size = 0;
        final int len = skb.length() - skb.getHeader().length();
        if (len >= icsk_ack.rcv_mss) {
            /*
            if (len != icsk_ack.rcv_mss) {
                len << TCP_RMEM_TO_WIN_SCALE‎;
            }
            */

            icsk_ack.rcv_mss = Math.min(len, advmss);
        }
    }


    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L622">tcp_initialize_rcv_mss</a>
     */
    private void tcp_initialize_rcv_mss(final TcpSock tp) {
        int hint = Math.min(tp.advmss, tp.mss_cache);
        hint = Math.min(hint, tp.rcv_wnd / 2);
        hint = Math.min(hint, TcpConstants.TCP_MSS_DEFAULT);
        hint = Math.max(hint, TcpConstants.TCP_MIN_MSS);
        tp.icsk_ack.rcv_mss = hint;
    }

    /**
     * Receiver "autotuning" code.
     * <p>
     * The algorithm for RTT estimation w/o timestamps is based on
     * Dynamic Right-Sizing (DRS) by Wu Feng and Mike Fisk of LANL.
     * <https://public.lanl.gov/radiant/pubs.html#DRS>
     * <p>
     * More detail on this code can be found at
     * <http://staff.psc.edu/jheffner/>,
     * though this reference is out of date.  A new paper
     * is pending.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L652">tcp_rcv_rtt_update</a>
     */
    private void tcp_rcv_rtt_update(long sample, int win_dep) {
        long new_sample = rcv_rtt_est.rtt_us;
        long m = sample;

        if (new_sample != 0) {
            /* If we sample in larger samples in the non-timestamp
             * case, we could grossly overestimate the RTT especially
             * with chatty applications or bulk transfer apps which
             * are stalled on filesystem I/O.
             *
             * Also, since we are only going for a minimum in the
             * non-timestamp case, we do not smooth things out
             * else with timestamps disabled convergence takes too
             * long.
             */
            if (0 == win_dep) {
                m -= (new_sample >> 3);
                new_sample += m;
            } else {
                m <<= 3;
                if (m < new_sample) {
                    new_sample = m;
                }
            }
        } else {
            /* No previous measure. */
            new_sample = m << 3;
        }

        rcv_rtt_est.rtt_us = new_sample;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L684">tcp_rcv_rtt_measure</a>
     */
    void tcp_rcv_rtt_measure() {
        if (rcv_rtt_est.time != 0) {
            if (before(rcv_nxt, rcv_rtt_est.seq)) {
                return;
            }
            long delta_us = tcp_stamp_us_delta(tcp_mstamp, rcv_rtt_est.time);
            if (delta_us == 0) {
                delta_us = 1;
            }
            tcp_rcv_rtt_update(delta_us, 1);
        }

        rcv_rtt_est.seq = rcv_nxt + rcv_wnd;
        rcv_rtt_est.time = tcp_mstamp;
    }


    /* Called to compute a smoothed rtt estimate. The data fed to this
     * routine either comes from timestamps, or from segments that were
     * known _not_ to have been retransmitted [see Karn/Partridge
     * Proceedings SIGCOMM 87]. The algorithm is from the SIGCOMM 88
     * piece by Van Jacobson.
     * NOTE: the next three routines used to be one big routine.
     * To save cycles in the RFC 1323 implementation it was better to break
     * it up into three procedures. -- erics
     */
    private void tcp_rtt_estimator(long mrtt_us) {
        long m = mrtt_us; /* RTT */
        long srtt = srtt_us;

        /*	The following amusing code comes from Jacobson's
         *	article in SIGCOMM '88.  Note that rtt and mdev
         *	are scaled versions of rtt and mean deviation.
         *	This is designed to be as fast as possible
         *	m stands for "measurement".
         *
         *	On a 1990 paper the rto value is changed to:
         *	RTO = rtt + 4 * mdev
         *
         * Funny. This algorithm seems to be very broken.
         * These formulae increase RTO, when it should be decreased, increase
         * too slowly, when it should be increased quickly, decrease too quickly
         * etc. I guess in BSD RTO takes ONE value, so that it is absolutely
         * does not matter how to _calculate_ it. Seems, it was trap
         * that VJ failed to avoid. 8)
         */
        if (srtt != 0) {
            m -= (srtt >> 3);    /* m is now error in rtt est */
            srtt += m;        /* rtt = 7/8 rtt + 1/8 new */
            if (m < 0) {
                m = -m;        /* m is now abs(error) */
                m -= (mdev_us >> 2);   /* similar update on mdev */
                /* This is similar to one of Eifel findings.
                 * Eifel blocks mdev updates when rtt decreases.
                 * This solution is a bit different: we use finer gain
                 * for mdev in this case (alpha*beta).
                 * Like Eifel it also prevents growth of rto,
                 * but also it limits too fast rto decreases,
                 * happening in pure Eifel.
                 */
                if (m > 0) {
                    m >>= 3;
                }
            } else {
                m -= (mdev_us >> 2);   /* similar update on mdev */
            }
            mdev_us += m;        /* mdev = 3/4 mdev + 1/4 new */
            if (mdev_us > mdev_max_us) {
                mdev_max_us = mdev_us;
                if (mdev_max_us > rttvar_us) {
                    rttvar_us = mdev_max_us;
                }
            }
            if (after(snd_una, rtt_seq)) {
                if (mdev_max_us < rttvar_us) {
                    rttvar_us -= (rttvar_us - mdev_max_us) >> 2;
                }
                rtt_seq = snd_nxt;
                mdev_max_us = tcp_rto_min_us();

                // tcp_bpf_rtt(sk, mrtt_us, srtt);
            }
        } else {
            /* no previous measure. */
            srtt = m << 3;        /* take the measured time to be rtt */
            mdev_us = m << 1;    /* make sure rto = 3*rtt */
            rttvar_us = Math.max(mdev_us, tcp_rto_min_us());
            mdev_max_us = rttvar_us;
            rtt_seq = snd_nxt;

            // tcp_bpf_rtt(sk, mrtt_us, srtt);
        }
        srtt_us = Math.max(1, srtt);
        logTrace("[RTT] Compute a smoothed rtt: {}us", srtt_us >> 3);
    }

    /**
     * Calculate rto without backoff.  This is the second half of Van Jacobson's
     * routine referred to above.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L980">tcp_set_rto</a>
     */
    private void tcp_set_rto() {
        /* Old crap is replaced with new one. 8)
         *
         * More seriously:
         * 1. If rtt variance happened to be less 50msec, it is hallucination.
         *    It cannot be less due to utterly erratic ACK generation made
         *    at least by solaris and freebsd. "Erratic ACKs" has _nothing_
         *    to do with delayed acks, because at cwnd>2 true delack timeout
         *    is invisible. Actually, Linux-2.4 also generates erratic
         *    ACKs in some circumstances.
         */
        icsk_rto = (int) __tcp_set_rto();

        /* 2. Fixups made earlier cannot be right.
         *    If we do not estimate RTO correctly without them,
         *    all the algo is pure shit and should be replaced
         *    with correct one. It is exactly, which we pretend to do.
         */

        /* NOTE: clamping at TCP_RTO_MIN is not required, current algo
         * guarantees that rto is higher.
         */
        tcp_bound_rto();
        logTrace("[RTO] Set retransmission timeout: {}ms", icsk_rto);
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L1001">tcp_init_cwnd</a>
     */
    private int tcp_init_cwnd() {
        // __u32 cwnd = (dst ? dst_metric(dst, RTAX_INITCWND) : 0);
        int cwnd = 0;

        if (0 == cwnd) {
            cwnd = TcpConstants.TCP_INIT_CWND;
        }
        return Math.min(cwnd, snd_cwnd_clamp);
    }

    // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp.c#L665
    private void tcp_mark_push(final TcpPacket.Builder skb) {
        skb.psh(true);
        pushed_seq = write_seq;
    }

    // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp.c#L671
    private boolean forced_push() {
        // ???
        return after(write_seq, pushed_seq + (max_window >> 1));
    }

    // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp.c#L676
    void tcp_skb_entail(TcpBuffer skb) {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp.c#L676
        skb.sequenceNumber(write_seq);
        skb.ack(true);
        sk_write_queue.offer(skb);
    }

    // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp.c#L719
    void tcp_push(int flags, int mss_now, int nonagle, int size_goal) {
        // FIXME ....
    }

    private void tcp_sendmsg() {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp.c#L1353
        // lock
        tcp_sendmsg_locked();
        // unlock
    }

    private void tcp_sendmsg_locked() {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp.c#L1052

        // restart:
        final int mss_now = tcp_send_mss(this);
    }

    private synchronized void tcp_sendmsg2(TcpBuffer skb, boolean flush) {
        skb.sequenceNumber(write_seq);
        skb.dstPort(tcpSrcPort).srcPort(tcpDstPort);
        tcp_skb_entail(skb);

        final Packet.Builder payload = skb.payloadBuilder();
        if (null != payload) {
            write_seq += payload.build().length();
        }
        if (flush) {
            tcp_push_pending_frames();
        }
    }

    private static final int TCP_STATE_MASK = 0xF;
    private static final int TCP_ACTION_FIN = 1 << (TcpState.TCP_CLOSE.ordinal());
    private static final int[] new_state = new int[16];

    {
//        new_state[0 /* (Invalid) */] = State.TCP_CLOSE.ordinal();
        new_state[TcpState.TCP_ESTABLISHED.ordinal() + 1] = TcpState.TCP_FIN_WAIT1.ordinal() | TCP_ACTION_FIN;
        new_state[TcpState.TCP_SYN_SENT.ordinal() + 1] = TcpState.TCP_CLOSE.ordinal();
        new_state[TcpState.TCP_SYN_RECV.ordinal() + 1] = TcpState.TCP_FIN_WAIT1.ordinal() | TCP_ACTION_FIN;
        new_state[TcpState.TCP_FIN_WAIT1.ordinal() + 1] = TcpState.TCP_FIN_WAIT1.ordinal();
        new_state[TcpState.TCP_FIN_WAIT2.ordinal() + 1] = TcpState.TCP_FIN_WAIT2.ordinal();
        new_state[TcpState.TCP_TIME_WAIT.ordinal() + 1] = TcpState.TCP_CLOSE.ordinal();
        new_state[TcpState.TCP_CLOSE.ordinal() + 1] = TcpState.TCP_CLOSE.ordinal();
        new_state[TcpState.TCP_CLOSE_WAIT.ordinal() + 1] = TcpState.TCP_LAST_ACK.ordinal() | TCP_ACTION_FIN;
        new_state[TcpState.TCP_LAST_ACK.ordinal() + 1] = TcpState.TCP_LAST_ACK.ordinal();
        new_state[TCP_LISTEN.ordinal() + 1] = TcpState.TCP_CLOSE.ordinal();
        new_state[TcpState.TCP_CLOSING.ordinal() + 1] = TcpState.TCP_CLOSING.ordinal();
        new_state[TcpState.TCP_NEW_SYN_RECV.ordinal() + 1] = TcpState.TCP_CLOSE.ordinal(); /* should not happen ! */
    }

    /**
     * Shutdown the sending side of a connection. Much like close except
     * that we don't receive shut down or sock_set_flag(sk, SOCK_DEAD).
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp.c#L2979">tcp_shutdown</a>
     */
    void tcp_shutdown(final int how) {
        if (0 == (how & TcpConstants.SEND_SHUTDOWN)) {
            return;
        }

        /* If we've already sent a FIN, or it's a closed state, skip this. */
        if (0 != ((1 << state.get().ordinal()) & (TcpConstants.TCPF_ESTABLISHED | TcpConstants.TCPF_CLOSE_WAIT))) {
            /* Clear out any half completed packets.  FIN if needed. */
            if (tcp_close_state()) {
                output.tcp_send_fin(this);
            }
        }
    }

    private boolean tcp_close_state() {
        int next = new_state[state.get().ordinal() + 1];
        int ns = next & TCP_STATE_MASK;

        state.set(TcpState.values()[ns]);
        return 0 != (next & TCP_ACTION_FIN);
    }


    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp.c#L3240">tcp_close</a>
     */
    private void tcp_close(TcpSock sk, long timeout) {
        __tcp_close(sk, timeout);
        // release_sock();
        // ...
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp.c#L3066">__tcp_close</a>
     */
    private void __tcp_close(TcpSock sk, long timeout) {
        // FIXME
        sk_shutdown = TcpConstants.SHUTDOWN_MASK;

        TcpState state = this.state.get();
        if (TCP_LISTEN.equals(state)) {
            this.state.set(TCP_CLOSE);
            adjudge_to_death(sk);
            return;
        }

        // ...

        /* If socket has been already reset (e.g. in tcp_reset()) - kill it. */
        if (TCP_CLOSE.equals(state)) {
            adjudge_to_death(sk);
            return;
        }

        if (tcp_close_state()) {
            output.tcp_send_fin(this);
        }

        adjudge_to_death(sk);
    }

    private void adjudge_to_death(TcpSock sk) {
        final TcpState state = this.state.get();
        if (TCP_FIN_WAIT2.equals(state)) {
            final int tmo = sk.tcp_fin_time();
            if (tmo > TcpConstants.TCP_TIMEWAIT_LEN) {
                timer.tcp_reset_keepalive_timer(this, tmo - TcpConstants.TCP_TIMEWAIT_LEN);
            } else {
                tcp_time_wait(TCP_FIN_WAIT2, tmo);
                return;
            }
        }

        if (!TcpState.TCP_CLOSE.equals(state)) {
            // TODO
        }

        // ...
    }

    void tcp_time_wait(TcpState state, long timeout) {
        this.state.set(state);

        if (TCP_TIME_WAIT.equals(state)) {
            this.state.set(TCP_CLOSE);
            tcp_done();
        }

    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L2102">tcp_push_pending_frames</a>
     */
    protected void tcp_push_pending_frames() {
        if (null != tcp_send_head()) {
            output.__tcp_push_pending_frames(this, output.tcp_current_mss(this), this.nonagle);
        }
    }



    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L75">tcp_write_err</a>
     */
    void tcp_write_err() {
        log.warn("TCP WRITE ERROR");
        input.tcp_done_with_error(this, sk_err_soft != 0 ? sk_err_soft : ETIMEOUT);
    }


    private long init_ts_off(TcpPacket skb) {
        // return TimeUnit.NANOSECONDS.toMicros(System.nanoTime());
        return 0;
    }


    void sk_data_ready() {

    }

    void consume(final TcpPacket skb) {
        if (null != child && child.isOpen()) {
            final TcpHeader hdr = skb.getHeader();
            final byte[] bytes = skb.getPayload().getRawData();

            final int offset = rcv_nxt - hdr.getSequenceNumber();
            final int length = Math.min(output.tcp_receive_window(this), bytes.length - offset);
            child.writeAndFlush(Unpooled.wrappedBuffer(bytes, offset, length));
        }
    }


    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp.c#L4939">tcp_done</a>
     */
    void tcp_done() {
        // ... fastopen

        state.set(TcpState.TCP_CLOSE);
        timer.tcp_clear_xmit_timers(this);

        // ... fastopen...

        sk_shutdown = TcpConstants.SHUTDOWN_MASK;

//        if (!sock_flag(tp, SOCK_DEAD)) {
//            sk->sk_state_change(tp);
//        } else
        //
        inet_csk_destroy_sock();
    }


    /*       MSS    */


    // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L2190
    void tcp_enter_loss() {

    }


    int tcp_skb_pcount(TcpBuffer skb) {
        return 1;
    }

    long tcp_skb_timestamp_ts(int usec_ts, TcpBuffer skb) {
        // FIXME
        long skb_mstamp_ns = skb.skb_mstamp_ns;
        if (usec_ts != 0) {
            // skb_mstamp_ns / NSEC_PER_USEC;
            return TimeUnit.NANOSECONDS.toMicros(skb_mstamp_ns);
        }
        // skb_mstamp_ns / NSEC_PER_MSEC
        return TimeUnit.NANOSECONDS.toMillis(skb_mstamp_ns);
    }

    // https://github.com/torvalds/linux/blob/v6.13/include/linux/skbuff.h#L4322
    void skb_set_delivery_time(TcpBuffer skb, long kt, String tstamp_type) {
        // FIXME
//        skb.tstamp = kt;
        skb.skb_mstamp_ns = kt;
        skb.tstamp = kt;
    }


    /* *********** [[ ************** */





    /* *********** ]] ************** */


    int dst_metric_advmss() {
        // https://github.com/torvalds/linux/blob/master/include/net/dst.h#L182
        return 1500 - IP_HEADER_SIZE - TCP_HEADER_SIZE;
    }

    int dst_metric(int metric) {
        return 0;
    }


    long tcp_stamp_us_delta(long t1, long t0) {
        return Math.max(t1 - t0, 0);
    }

    /* ****************** */
    /* ****************** */
    /* ****************** */
    /* ****************** */


    /* ************* */
    /* ************* */
    /* ************* */

    // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3186
    void tcp_update_rtt_min(long rtt_us, int flag) {
        // int wlen = READ_ONCE(sock_net(sk)->ipv4.sysctl_tcp_min_rtt_wlen) * HZ;
        int wlen = 1 * HZ;

//        if ((flag & FLAG_ACK_MAYBE_DELAYED) && rtt_us > tcp_min_rtt()) {
        /* If the remote keeps returning delayed ACKs, eventually
         * the min filter would pick it up and overestimate the
         * prop. delay when it expires. Skip suspected delayed ACKs.
         */
//            return;
//        }

        // FIXME
        // minmax_running_min(rtt_min, wlen, tcp_jiffies32(), 0 != rtt_us ? rtt_us : jiffies_to_usecs(1));
    }

    // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3202
    boolean tcp_ack_update_rtt(int flag, long seq_rtt_us,
                               long sack_rtt_us, long ca_rtt_us/*,
                                       struct rate_sample *rs*/) {

        /* Prefer RTT measured from ACK's timing to TS-ECR. This is because
         * broken middle-boxes or peers may corrupt TS-ECR fields. But
         * Karn's algorithm forbids taking RTT if some retransmitted data
         * is acked (RFC6298).
         */
        if (seq_rtt_us < 0) {
            seq_rtt_us = sack_rtt_us;
        }

        /* RTTM Rule: A TSecr value received in a segment is used to
         * update the averaged RTT measurement only if the segment
         * acknowledges some new data, i.e., only if it advances the
         * left edge of the send window.
         * See draft-ietf-tcplw-high-performance-00, section 3.3.
         */
//        if (seq_rtt_us < 0 && tp->rx_opt.saw_tstamp && tp->rx_opt.rcv_tsecr && flag & FLAG_ACKED)
//            seq_rtt_us = ca_rtt_us = tcp_rtt_tsopt_us(tp);

        // rs->rtt_us = ca_rtt_us; /* RTT of last (S)ACKed packet (or -1) */
        if (seq_rtt_us < 0) {
            return false;
        }

        /* ca_rtt_us >= 0 is counting on the invariant that ca_rtt_us is
         * always taken together with ACK, SACK, or TS-opts. Any negative
         * values will be skipped with the seq_rtt_us < 0 check above.
         */
        tcp_update_rtt_min(ca_rtt_us, flag);
        tcp_rtt_estimator(seq_rtt_us);

        // 116.228.111.118 180.168.255.18
        // TODO OPEN ME
        tcp_set_rto();

        /* RFC6298: only reset backoff on valid RTT measurement. */
        icsk_backoff = 0;
        return true;
    }

    /**
     * Restart timer after forward progress on connection.
     * RFC2988 recommends to restart timer to now+rto.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3147">tcp_rearm_rto</a>
     */
    protected void tcp_rearm_rto() {

        // ...

        if (packets_out <= 0) {
            inet_csk_clear_xmit_timer(ICSK_TIME_RETRANS);
        } else {
            int rto = icsk_rto;

            /* Offset the time elapsed after installing regular RTO */
            if (icsk_pending == ICSK_TIME_REO_TIMEOUT
                    || icsk_pending == ICSK_TIME_LOSS_PROBE) {
                final long delta_us = tcp_rto_delta_us();
                /* delta_us may not be positive if the socket is locked
                 * when the retrans timer fires and is rescheduled.
                 */
                rto = (int) usecs_to_jiffies(Math.max(delta_us, 1));
            }
            tcp_reset_xmit_timer(ICSK_TIME_RETRANS, rto, true);
        }
    }

    void tcp_ack_tstamp() {

    }


    private void tcp_rcv_established(final TcpPacket skb) throws IOException {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L6110

        // step5
        input.tcp_ack(this, skb, 0);

        /* step 7: process the segment text */
        input.tcp_data_queue(this, skb);

        input.tcp_data_snd_check(this);
        // tcp_ack_snd_check();
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L6299">tcp_init_transfer</a>
     */
    private void tcp_init_transfer(TcpSock tp, TcpPacket skb) {
        output.tcp_mtup_init();
        tcp_init_metrics();

        tp.tcp_snd_cwnd_set(tcp_init_cwnd());

        tp.snd_cwnd_stamp = tcp_jiffies32();

        tcp_init_congestion_control();

        child.config().setAutoRead(true);
    }

    /**
     * @param skb
     * @return error code
     * @throws IOException
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L6676">tcp_rcv_state_process</a>
     */
    protected int tcp_rcv_state_process(final TcpDemultiplexer<T> tp, final T ipPacket, final TcpPacket skb) throws IOException {
        Sock sk = tp;
        InetConnectionSock icsk = tp;
        final TcpHeader th = skb.getHeader();
        ipHeader = ipPacket.getHeader();
        tcpSrcPort = th.getSrcPort();
        tcpDstPort = th.getDstPort();

        /*-
         * 握手处理.
         */
        switch (sk.state()) {
            case TCP_CLOSE:
                return discard(skb, TcpDropReason.SKB_DROP_REASON_TCP_CLOSE);
            case TCP_LISTEN:
                if (th.getAck()) {
                    // Send one RST
                    return TcpDropReason.SKB_DROP_REASON_TCP_FLAGS;
                }
                if (th.getRst()) {
                    return discard(skb, TcpDropReason.SKB_DROP_REASON_TCP_RESET);
                }

                /* handshake */
                if (th.getSyn()) {
                    if (th.getFin()) {
                        return discard(skb, TcpDropReason.SKB_DROP_REASON_TCP_FLAGS);
                    }

                    /*-
                     * Linux此处为创建状态为TCP_NEW_SYN_RECV的请求套接字(request_sock)放入半连接队列即可结束,
                     * 此处调整为直接创建连接.
                     */
                    sk.state(TcpState.TCP_NEW_SYN_RECV);
                    tcp_request_sock tcpRequestSock = conn_request(tp, ipPacket, skb);
                    if (null == tcpRequestSock) {
                        return TcpDropReason.SKB_DROP_REASON_NO_SOCKET;
                    }

                    /*-
                     * 原本应该是收到第三次握手的ACK请求后，查找半连接队列, 如果查找到状态为 TCP_NEW_SYN_RECV 的请求套接字,
                     * 调用 tcp_check_req, 转换为 TCP_SYN_RECV 的子套接字, 并完成握手后迁移到全连接队列.
                     * 这里暂时没有采用父子关系, 上面直接建立了连接所以直接转换为 TCP_SYNC_RECV.
                     */
                    tcp_check_req(tcpRequestSock, skb);

                    // FIXME 移动到连接打开时
//                    state.set(State.TCP_SYN_RECV);
                    return TcpDropReason.SKB_DROP_REASON_NOT_SPECIFIED;
                }

                return discard(skb, TcpDropReason.SKB_DROP_REASON_TCP_FLAGS);
            case TCP_SYN_SENT:
                /*-
                 * XXX client mode not supported.
                 */
                return TcpDropReason.SKB_DROP_REASON_NO_SOCKET;
            // 临时处理.
            case TCP_NEW_SYN_RECV:
                return discard(skb, TcpDropReason.SKB_DROP_REASON_NOT_SPECIFIED);
        }

        /*-
         * 刷新最近发送/接收时间戳.
         */
        output.tcp_mstamp_refresh(tp);
        tp.rx_opt.saw_tstmap = 0;

        if (!th.getAck() && !th.getRst() && !th.getSyn()) {
            return discard(skb, TcpDropReason.SKB_DROP_REASON_TCP_FLAGS);
        }

        if (!input.tcp_validate_incoming(this, skb)) {
            return TcpDropReason.SKB_DROP_REASON_NOT_SPECIFIED;
        }

        /* step 5: check the ACK field */
        int reason = input.tcp_ack(tp, skb, TcpInput.FLAG_SLOWPATH | TcpInput.FLAG_UPDATE_TS_RECENT | TcpInput.FLAG_NO_CHALLENGE_ACK);
        if (reason <= 0) {
            if (TcpState.TCP_SYN_RECV.equals(sk.state())) {
                // send one RST
                return 0 == reason ? TcpDropReason.SKB_DROP_REASON_TCP_OLD_ACK : -reason;
            }

            /* accept old ack during closing */
            if (reason < 0) {
                tcp_send_challenge_ack();
                reason = -reason;
                return discard(skb, reason);
            }
        }

        boolean queued = false;
        reason = TcpDropReason.SKB_DROP_REASON_NOT_SPECIFIED;
        switch (sk.state()) {
            case TCP_SYN_RECV:
                delivered++; /* SYN-ACK delivery isn't tracked in tcp_ack */
                tcp_init_transfer(tp, skb);

                sk.state(TcpState.TCP_ESTABLISHED);

                snd_una = th.getAcknowledgmentNumber();
                snd_wnd = th.getWindowAsInt() << rx_opt.snd_wscale;
                tp.tcp_init_wl(th.getSequenceNumber());

                // ...

                /* Prevent spurious tcp_cwnd_restart() on first data packet */
                lsndtime = tcp_jiffies32();
                tcp_initialize_rcv_mss(this);

                break;
            case TCP_FIN_WAIT1:
                // ... fastopen

                if (snd_una != write_seq) {
                    break;
                }
                sk.state(TCP_FIN_WAIT2);
                sk_shutdown |= TcpConstants.SEND_SHUTDOWN;

//                if (!sock_flag(sk, SOCK_DEAD) {
//                   sk_state_change
//                   break;
//                }

                if (linger2 < 0) {
                    tcp_done();
                    return SKB_DROP_REASON_TCP_ABORT_ON_DATA;
                }

                final int seq = th.getSequenceNumber();
                final int end_seq = determineEndSeq(skb);
                if (end_seq != seq && after(end_seq - (th.getFin() ? 1 : 0), rcv_nxt)) {
                    tcp_done();
                    return SKB_DROP_REASON_TCP_ABORT_ON_DATA;
                }

                final int tmo = tcp_fin_time();
                if (tmo > TcpConstants.TCP_TIMEWAIT_LEN) {
                    /*-
                     * FIN_WAIT2 开始的总超时时间 > TIME_WAIT 的 2MSL, 则在进入 TIME_WAIT 前保证连接存活.
                     */
                    timer.tcp_reset_keepalive_timer(this, tmo - TcpConstants.TCP_TIMEWAIT_LEN);
                } else if (th.getFin()) {
                    /* Bad case. We could lose such FIN otherwise.
                     * It is not a big problem, but it looks confusing
                     * and not so rare event. We still can lose it now,
                     * if it spins in bh_lock_sock(), but it is really
                     * marginal case.
                     */
                    timer.tcp_reset_keepalive_timer(this, tmo);
                } else {
                    tcp_time_wait(TCP_FIN_WAIT2, tmo);
                    return TcpDropReason.SKB_DROP_REASON_NOT_SPECIFIED;
                }
                break;
            case TCP_CLOSING:
                if (snd_una == write_seq) {
                    tcp_time_wait(TCP_TIME_WAIT, 0);
                    return TcpDropReason.SKB_DROP_REASON_NOT_SPECIFIED;
                }
                break;
            case TCP_LAST_ACK:
                if (snd_una == write_seq) {
                    // tcp_update_metrics
                    tcp_done();
                    return TcpDropReason.SKB_DROP_REASON_NOT_SPECIFIED;
                }
                break;
        }

        /* step 6: check the URG bit */
        // tcp_urg(sk, skb, th);

        /* step 7: process the segment text */
        switch (sk.state()) {
            case TCP_CLOSE_WAIT:
            case TCP_CLOSING:
            case TCP_LAST_ACK:
                if (!before(th.getSequenceNumber(), rcv_nxt)) {
                    /* If a subflow has been reset, the packet should not
                     * continue to be processed, drop the packet.
                     */
                    // ... sk_is_mptcp
                    break;
                }
                // fallthrough
            case TCP_FIN_WAIT1:
            case TCP_FIN_WAIT2:
                /* RFC 793 says to queue data in these states,
                 * RFC 1122 says we MUST send a reset.
                 * BSD 4.4 also does reset.
                 */
                if (0 != (sk_shutdown & TcpConstants.RCV_SHUTDOWN)) {
                    int seq = th.getSequenceNumber();
                    int end_seq = determineEndSeq(skb);
                    if (end_seq != seq && after(end_seq - (th.getFin() ? 1 : 0), rcv_nxt)) {
                        input.tcp_reset(this, skb);
                        return SKB_DROP_REASON_TCP_ABORT_ON_DATA;
                    }
                }
                // fallthrough
            case TCP_ESTABLISHED:
                input.tcp_data_queue(this, skb);
                queued = true;
                break;
        }

        /* tcp_data could move socket to TIME-WAIT */
        if (!TcpState.TCP_CLOSE.equals(sk.state())) {
            input.tcp_data_snd_check(this);
            input.tcp_ack_snd_check(this);

            if (TcpState.TCP_CLOSE_WAIT.equals(sk.state())) {
                // FIXME
                if (null != child && child.isOpen()) {
                    child.close();
                } else if (tcp_close_state()) {
                    output.tcp_send_fin(TcpDemultiplexer.this);
                }

            }
        }

        if (!queued) {
            tcp_drop_reason(skb, reason);
        }

        return TcpDropReason.SKB_DROP_REASON_NOT_SPECIFIED;
    }


    private int discard(final TcpPacket skb, final int reason) {
        tcp_drop_reason(skb, reason);
        return 0;
    }

    private void tcp_drop_reason(final TcpPacket skb, final int reason) {

    }

    protected abstract void INDIRECT_CALL_INET(final TcpBuffer skb);


    int tcp_send_mss(final TcpDemultiplexer<T> tp) {
        return output.tcp_current_mss(tp);
    }

    void logTrace(final String format, final Object... args) {
        log.trace(format(format), args);
    }

    void logDebug(final String format, final Object... args) {
        log.debug(format(format), args);
    }

    void logInfo(final String format, final Object... args) {
        log.info(format(format), args);
    }

    void logWarn(final String format, final Object... args) {
        log.warn(format(format), args);
    }

    void logError(final String format, final Object... args) {
        log.error(format(format), args);
    }

    private String format(final String format) {
        final InetAddress srcAddr = ipHeader.getSrcAddr();
        final InetAddress dstAddr = ipHeader.getDstAddr();

        final int srcPort = tcpSrcPort.valueAsInt();
        final int dstPort = tcpDstPort.valueAsInt();

        final String srcHostAddr = srcAddr.getHostAddress();
        final String dstHostAddr = dstAddr.getHostAddress();


        final StringBuilder buff = new StringBuilder();
        buff.append(TcpUtils.logPrefix(null != child ? child.id() : null, srcHostAddr, srcPort, dstHostAddr, dstPort));
        buff.append(" ");

        buff.append(format);
        return buff.toString();
    }

    protected void debug(final IpHeader ipHeader, final TcpPacket tcpPacket, boolean inbound) {
        final String message = TcpUtils.logify(null != child ? child.id() : null, ipHeader, tcpPacket, inbound ? rx_opt.rcv_wscale : rx_opt.snd_wscale);
        log.debug(message);
    }


    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L49">tcp_clamp_probe0_to_user_timeout</a>
     */
    protected long tcp_clamp_probe0_to_user_timeout(long when) {
        int user_timeout = icsk_user_timeout;
        if (0 == user_timeout || 0 == icsk_probes_tstamp) {
            return when;
        }
        long elapsed = tcp_jiffies32() - icsk_probes_tstamp;
        if (elapsed < 0) {
            elapsed = 0;
        }
        long remaining = user_timeout - elapsed;
        remaining = Math.max(remaining, TcpConstants.TCP_TIMEOUT_MIN);
        return Math.min(remaining, when);
    }


    void tcp_send_challenge_ack() {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3649
    }

    public long icsk_delack_timeout() {
        // FIXME
        return icsk_ack.timeout;
    }

    // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L1705
    class tcp_request_sock_ops {
        public void send_ack() {

        }

        public void send_reset() {

        }
    }

    // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L1714
    class tcp_request_sock_ipv4_ops {
        public int mss_clamp = TCP_MSS_DEFAULT;

        public int init_seq(IpHeader ipHdr, TcpHeader header) {
            return initSeq(ipHdr, header);
        }

        public void send_synack(TcpDemultiplexer<T> p, tcp_request_sock req, IpHeader ipHdr, TcpPacket skb) {
            p.send_synack(p, req, ipHdr, skb);
        }
    }

    public static class request_sock extends SockCommon {
        public int rsk_rcv_wnd;
        public int rsk_window_clamp;

    }

    // https://github.com/torvalds/linux/blob/master/include/net/inet_sock.h#L69
    public static class inet_request_sock extends request_sock {
        public int snd_wscale;
        public int rcv_wscale;
        public boolean wscale_ok;
    }


    // https://github.com/torvalds/linux/blob/master/include/linux/tcp.h#L149
    public static class tcp_request_sock extends inet_request_sock {
        int mss;
        boolean req_usec_ts;
        int rcv_isn;
        int snt_isn;
        long ts_off;
        int snt_tsval_first;
        int snt_tsval_last;
        int last_oow_ack_time;
        int rcv_nxt;
        int syn_tos;
    }
}