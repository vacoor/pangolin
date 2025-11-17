package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal;

import com.github.pangolin.routing.acceptor.tun.fakedns.DnsEngine;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.v2.InetConnectionSock;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.v2.SockCommon;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.v2.TcpSock;
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
import java.util.Map;

import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpClock.*;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpConstants.*;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpDropReason.SKB_DROP_REASON_TCP_ABORT_ON_DATA;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpHandshaker.tcpLogError;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpState.*;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpUtils.*;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.v2.TcpSock.tcp_initialize_rcv_mss;

@Slf4j
public abstract class TcpDemultiplexer<T extends IpPacket> {

    // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L943
    public static final int TCPCB_SACKED_ACKED = (1 << 0);    /* SKB ACK'd by a SACK block	*/
    public static final int TCPCB_SACKED_RETRANS = (1 << 1);    /* SKB retransmitted		*/
    public static final int TCPCB_LOST = (1 << 2);    /* SKB is lost			*/
    public static final int TCPCB_TAGBITS = (TCPCB_SACKED_ACKED | TCPCB_SACKED_RETRANS | TCPCB_LOST);    /* All tag bits			*/
    public static final int TCPCB_REPAIRED = (1 << 4);    /* SKB repaired (no skb_mstamp_ns)	*/
    public static final int TCPCB_EVER_RETRANS = (1 << 7);    /* Ever retransmitted frame	*/
    public static final int TCPCB_RETRANS = (TCPCB_SACKED_RETRANS | TCPCB_EVER_RETRANS | TCPCB_REPAIRED);

    protected TcpSock listenSock = TcpDemultiplexer.tcp_init_sock(new TcpSock());

    /**
     *
     */
    protected final Channel net;
    final DnsEngine dnsEngine;
    final EventLoopGroup childGroup;
    final SocketChannelFactory socketChannelFactory;

    int connTimeoutMs = 10 * 1000;


    public TcpOutput output = new TcpOutput(this);
    public TcpInput input = new TcpInput(this, output);
    public TcpTimer timer = new TcpTimer(this);

    protected Map<String, tcp_request_sock> requestSockMap;
    protected Map<String, TcpSock> establishedMap;

    protected TcpDemultiplexer(
            Map<String, tcp_request_sock> requestMap,
            Map<String, TcpSock> establishedMap,
            final Channel net, final EventLoopGroup childGroup, final DnsEngine dnsEngine, final SocketChannelFactory socketChannelFactory) {
        super();
        this.requestSockMap = requestMap;
        this.establishedMap = establishedMap;
        this.net = net;
        this.childGroup = childGroup;
        this.dnsEngine = dnsEngine;
        this.socketChannelFactory = socketChannelFactory;
        init();
    }

    protected void init() {
//        tcp_init_sock(this);
    }

    public abstract void tcp_rcv(final T ipHeader, final TcpPacket tcpPacket);

    // ...


    // https://www.cnblogs.com/wanpengcoder/p/11751763.html


    private int TCPOLEN_TSTAMP_ALIGNED = 12;

    /* ************** Initialize Connection Request [[ ************ */

    protected abstract tcp_request_sock conn_request(TcpSock listenSock, final T ipPacket, final TcpPacket tcpPacket);


    protected abstract void send_reset(final IpHeader ipHeader, final TcpPacket tcpPacket, int err);


    /* ************** ]] Initialize Connection Request ************ */

    /* **************** Open Connection Request [[ *************/

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L2179">tcp_v4_rcv</a> TCP_NEW_SYN_RECV
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c#L660">tcp_check_req</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L1742">tcp_v4_syn_recv_sock</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c#L518">tcp_create_openreq_child</a> <==
     */
    public TcpSock tcp_check_req(tcp_request_sock request, final TcpPacket skb) {
        TcpSock nsk = tcp_v4_syn_recv_sock(request, skb);
        return nsk;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L2179">tcp_v4_rcv</a> TCP_NEW_SYN_RECV
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c#L660">tcp_check_req</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L1742">tcp_v4_syn_recv_sock</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c#L518">tcp_create_openreq_child</a> <==
     */
    private TcpSock tcp_v4_syn_recv_sock(tcp_request_sock req, final TcpPacket skb) {
        TcpSock parent = req.parentSock;
        TcpSock newsk = tcp_create_openreq_child(parent, req, skb);

        newsk.icsk_ext_hdr_len = 0;
        output.tcp_sync_mss(newsk, parent.dst_mtu());
        newsk.advmss = parent.tcp_mss_clamp(newsk, parent.dst_metric_advmss());

        tcp_initialize_rcv_mss(newsk);
        return newsk;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L2179">tcp_v4_rcv</a> TCP_NEW_SYN_RECV
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c#L660">tcp_check_req</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L1742">tcp_v4_syn_recv_sock</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c#L518">tcp_create_openreq_child</a> <==
     */
    private <T extends IpPacket> TcpSock tcp_create_openreq_child(TcpSock sk, tcp_request_sock req, final TcpPacket skb) {
        /*-
         * 第一步调用 <code>inet_csk_clone_lock<code/> 基于原 TCP_NEW_SYN_RECV sock clone时会将状态设置为 TCP_SYN_RECV.
         * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/inet_connection_sock.c#L1247"></a>
         */
        TcpSock newsk = inet_csk_clone_lock(sk, req);
        TcpSock newtp = newsk;

        // FIXME
        // rcv_isn = req.rcv_isn;

        int _seq = req.rcv_isn + 1;

        newtp.rcv_wup = _seq;
        newtp.copied_seq = _seq;
        newtp.rcv_nxt = _seq;
        // rcv_wup = copied_seq = rcv_nxt = _seq;
        // newtp.segs_in = 1;

        _seq = req.snt_isn + 1;
        newtp.snd_sml = newtp.snd_una = _seq;
        newtp.snd_nxt = _seq;
        newtp.snd_up = _seq;

        newtp.tcp_init_wl(req.rcv_isn);

        // ...
        newsk.icsk_ack.lrcvtime = tcp_jiffies32();

        newsk.lsndtime = tcp_jiffies32();
        // newsk.total_retrans = req->num_retrans;

        timer.tcp_init_xmit_timers(newsk);
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
            newtp.window_clamp = Math.min(sk.window_clamp, TcpConstants.U16_MAX);
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

        newtp.child = req.child;
        newtp.destroy = req.destroy;
        newtp.INDIRECT_CALL_INET = req.INDIRECT_CALL_INET;
        return newtp;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/inet_connection_sock.c#L1216">inet_csk_clone_lock</a>
     */
    private static TcpSock inet_csk_clone_lock(final TcpSock sk, tcp_request_sock req) {
        // final T newsk = sk; // sk_clone_lock
        final TcpSock newsk = new TcpSock();
        tcp_init_sock(newsk);

//         newsk.inet_dport = req....
//         newsk.inet_sport = ...
//        tcp_init_sock();

        newsk.rawIpHeader = req.rawIpHeader;
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
    public static TcpSock tcp_init_sock(final TcpSock sk) {
        // ...

//        timer.tcp_init_xmit_timers(sk);

        sk.icsk_rto = TcpConstants.TCP_TIMEOUT_INIT;

        final int rto_max_ms = TcpSock.TCP_RTO_MAX; //sk.ipv4_sysctl_tcp_rto_max_ms;
        sk.icsk_rto_max = (int) msecs_to_jiffies(rto_max_ms);

        final int rto_min_ms = TcpSock.TCP_RTO_MIN; //sk.ipv4_sysctl_tcp_rto_min_ms;
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
        return sk;
    }

    private static void tcp_scaling_ratio_init(final TcpSock sk) {
//        sk.scaling_ratio = TCP_DEFAULT_SCALING_RATIO;
    }


    private void tcp_init_metrics() {
    }


    private void tcp_init_congestion_control() {
    }


    /* **************** ]] Open Connection Request *************/

    static final int EPIPE = 32;

    public static final int ECONNREFUSED = 61;
    public static final int ECONNRESET = 104;
    public static final int ETIMEOUT = 110;


    /* *************** */
    /* *************** */
    /* *************** */


    public static final int TCP_STATE_MASK = 0xF;
    public static final int TCP_ACTION_FIN = 1 << (TcpState.TCP_CLOSE.ordinal());
    public static final int[] NEW_STATE = new int[16];

    {
//        new_state[0 /* (Invalid) */] = State.TCP_CLOSE.ordinal();
        NEW_STATE[TcpState.TCP_ESTABLISHED.ordinal() + 1] = TcpState.TCP_FIN_WAIT1.ordinal() | TCP_ACTION_FIN;
        NEW_STATE[TcpState.TCP_SYN_SENT.ordinal() + 1] = TcpState.TCP_CLOSE.ordinal();
        NEW_STATE[TcpState.TCP_SYN_RECV.ordinal() + 1] = TcpState.TCP_FIN_WAIT1.ordinal() | TCP_ACTION_FIN;
        NEW_STATE[TcpState.TCP_FIN_WAIT1.ordinal() + 1] = TcpState.TCP_FIN_WAIT1.ordinal();
        NEW_STATE[TcpState.TCP_FIN_WAIT2.ordinal() + 1] = TcpState.TCP_FIN_WAIT2.ordinal();
        NEW_STATE[TcpState.TCP_TIME_WAIT.ordinal() + 1] = TcpState.TCP_CLOSE.ordinal();
        NEW_STATE[TcpState.TCP_CLOSE.ordinal() + 1] = TcpState.TCP_CLOSE.ordinal();
        NEW_STATE[TcpState.TCP_CLOSE_WAIT.ordinal() + 1] = TcpState.TCP_LAST_ACK.ordinal() | TCP_ACTION_FIN;
        NEW_STATE[TcpState.TCP_LAST_ACK.ordinal() + 1] = TcpState.TCP_LAST_ACK.ordinal();
        NEW_STATE[TCP_LISTEN.ordinal() + 1] = TcpState.TCP_CLOSE.ordinal();
        NEW_STATE[TcpState.TCP_CLOSING.ordinal() + 1] = TcpState.TCP_CLOSING.ordinal();
        NEW_STATE[TcpState.TCP_NEW_SYN_RECV.ordinal() + 1] = TcpState.TCP_CLOSE.ordinal(); /* should not happen ! */
    }



    /*       MSS    */


    private void tcp_rcv_established(final TcpPacket skb) throws IOException {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L6110

        // step5
//        input.tcp_ack(this, skb, 0);

        /* step 7: process the segment text */
//        input.tcp_data_queue(this, skb);

//        input.tcp_data_snd_check(this);
        // tcp_ack_snd_check();
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L6299">tcp_init_transfer</a>
     */
    private void tcp_init_transfer(TcpSock tp, TcpPacket skb) {
        output.tcp_mtup_init();
        tcp_init_metrics();

        tp.tcp_snd_cwnd_set(tp.tcp_init_cwnd());

        tp.snd_cwnd_stamp = tcp_jiffies32();

        tcp_init_congestion_control();

        // child.
        innerChannel(tp).pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
                try {
//                    logTrace("Read from {}", resolved);
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

                    final int mss = output.tcp_current_mss(tp);
                    for (int offset = 0; offset < payload.length; ) {
                        final int len = payload.length - offset;
                        if (len <= mss) {
                            final UnknownPacket.Builder builder = UnknownPacket.newPacket(payload, offset, len).getBuilder();
                            tcp_sendmsg2(tp, new TcpBuffer().ack(true)
                                    //.psh(true)
                                    .payloadBuilder(builder), true);
                            offset += len;
                        } else {
                            UnknownPacket.Builder builder = UnknownPacket.newPacket(payload, offset, mss).getBuilder();
                            tcp_sendmsg2(tp, new TcpBuffer().ack(true)
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
                tcpLogError(null, tp.srcAddr, tp.srcPort.valueAsInt(), tp.dstAddr, tp.dstPort.valueAsInt(), "Exception caught: {}", cause.getMessage(), cause);
                send_reset(tp.rawIpHeader, new TcpPacket.Builder()
                        .srcAddr(tp.srcAddr)
                        .dstAddr(tp.dstAddr)
                        .srcPort(tp.srcPort)
                        .dstPort(tp.dstPort)
                        .ack(true)
                        .acknowledgmentNumber(tp.rcv_nxt)
                        .build(), -1);
                try {
                    if (ctx.channel().isOpen()) {
                        ctx.channel().close();
                    }
                } finally {
                    tcp_done(tp);
                }
            }
        });

        // CHECK child close.

        innerChannel(tp).closeFuture().addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture channelFuture) throws Exception {
                TcpHandshaker.tcpLogInfo(null, tp.srcAddr, tp.srcPort.valueAsInt(), tp.dstAddr, tp.dstPort.valueAsInt(), "DISCONNECTED: {}", tp.dstAddr.getHostAddress());
                if (tcp_close_state(tp)) {
                    output.tcp_send_fin(tp);
                }
            }

        });
        innerChannel(tp).config().setAutoRead(true);
    }

    public static Channel innerChannel(SockCommon sock) {
        return sock.child.channel();
    }

    protected void addToHalfQueue(final TcpSock listenSock, final tcp_request_sock sock) {
//        sk.state(TcpState.TCP_NEW_SYN_RECV);
//        this.request_sock = sock;

        sock.state(TcpState.TCP_NEW_SYN_RECV);
        InetAddress srcAddr = sock.srcAddr;
        TcpPort tcpSrcPort = sock.srcPort;
        InetAddress dstAddr = sock.dstAddr;
        TcpPort tcpDstPort = sock.dstPort;
//        final String sockKey = srcAddr.toString() + ":" + tcpSrcPort.valueAsInt() + " => " + dstAddr + ":" + tcpDstPort.valueAsInt();
        final String sockKey = srcAddr.getHostAddress() + ":" + tcpSrcPort.valueAsInt() + " => " + dstAddr.getHostAddress() + ":" + tcpDstPort.valueAsInt();
        requestSockMap.putIfAbsent(sockKey, sock);
    }

    protected void moveToEstablished(final tcp_request_sock req, final TcpSock sock) {
        InetAddress srcAddr = req.srcAddr;
        TcpPort tcpSrcPort = req.srcPort;
        InetAddress dstAddr = req.dstAddr;
        TcpPort tcpDstPort = req.dstPort;
        final String sockKey = srcAddr.getHostAddress() + ":" + tcpSrcPort.valueAsInt() + " => " + dstAddr.getHostAddress() + ":" + tcpDstPort.valueAsInt();
        requestSockMap.remove(sockKey, req);
        establishedMap.put(sockKey, sock);
    }

    /**
     * @param tcpPacket
     * @return error code
     * @throws IOException
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L6676">tcp_rcv_state_process</a>
     */
    protected int tcp_rcv_state_process(SockCommon sk, final T ipPacket, final TcpPacket tcpPacket) throws IOException {
        final TcpHeader th = tcpPacket.getHeader();

        /*-
         * 握手处理.
         */
        switch (sk.state()) {
            case TCP_CLOSE:
                log.warn("[TCP_CLOSE]");
                return discard(tcpPacket, TcpDropReason.SKB_DROP_REASON_TCP_CLOSE);
            case TCP_LISTEN:
                if (th.getAck()) {
                    log.warn("TCP_LISTEN ACK");
                    // Send one RST
                    return TcpDropReason.SKB_DROP_REASON_TCP_FLAGS;
                }
                if (th.getRst()) {
                    log.warn("TCP_LISTEN RST");
                    return discard(tcpPacket, TcpDropReason.SKB_DROP_REASON_TCP_RESET);
                }

                /* handshake */
                if (th.getSyn()) {
                    if (th.getFin()) {
                        log.warn("TCP_LISTEN SYN FIN");
                        return discard(tcpPacket, TcpDropReason.SKB_DROP_REASON_TCP_FLAGS);
                    }

                    /*-
                     * Linux此处为创建状态为TCP_NEW_SYN_RECV的请求套接字(request_sock)放入半连接队列即可结束,
                     * 此处调整为直接创建连接.
                     */
                    // for log
//                    sk.ipHeader = ipPacket.getHeader();
//                    sk.srcPort = tcpPacket.getHeader().getSrcPort();
//                    sk.dstPort = tcpPacket.getHeader().getDstPort();

                    tcp_request_sock tcpRequestSock = conn_request((TcpSock) sk, ipPacket, tcpPacket);
                    if (null == tcpRequestSock) {
                        return TcpDropReason.SKB_DROP_REASON_NO_SOCKET;
                    }

//                    tcpRequestSock.state.set(TCP_NEW_SYN_RECV);
//                    addToHalfQueue((TcpSock) sk, tcpRequestSock);
                    return TcpDropReason.SKB_DROP_REASON_NOT_SPECIFIED;
                }

                return discard(tcpPacket, TcpDropReason.SKB_DROP_REASON_TCP_FLAGS);
            case TCP_SYN_SENT:
                /*-
                 * XXX client mode not supported.
                 */
                return TcpDropReason.SKB_DROP_REASON_NO_SOCKET;
            // 临时处理.
            case TCP_NEW_SYN_RECV:
                /*-
                 * 原本应该是收到第三次握手的ACK请求后，查找半连接队列, 如果查找到状态为 TCP_NEW_SYN_RECV 的请求套接字,
                 * 调用 tcp_check_req, 转换为 TCP_SYN_RECV 的子套接字, 并完成握手后迁移到全连接队列.
                 * 这里暂时没有采用父子关系, 上面直接建立了连接所以直接转换为 TCP_SYNC_RECV.
                 */
                tcp_request_sock request_sock = (tcp_request_sock) sk;
                TcpSock tcpSock = tcp_check_req(request_sock, tcpPacket);
                // TODO add to established.
                tcpSock.state.set(TcpState.TCP_SYN_RECV);
                moveToEstablished(request_sock, tcpSock);
                sk = tcpSock;
                return discard(tcpPacket, TcpDropReason.SKB_DROP_REASON_NOT_SPECIFIED);
        }

        TcpSock tp = (TcpSock) sk;
        InetConnectionSock icsk = (InetConnectionSock) tp;
        /*-
         * 刷新最近发送/接收时间戳.
         */
        output.tcp_mstamp_refresh(tp);
        tp.rx_opt.saw_tstmap = 0;

        if (!th.getAck() && !th.getRst() && !th.getSyn()) {
            return discard(tcpPacket, TcpDropReason.SKB_DROP_REASON_TCP_FLAGS);
        }

        if (!input.tcp_validate_incoming(tp, tcpPacket)) {
            return TcpDropReason.SKB_DROP_REASON_NOT_SPECIFIED;
        }

        /* step 5: check the ACK field */
        int reason = input.tcp_ack(tp, tcpPacket, TcpInput.FLAG_SLOWPATH | TcpInput.FLAG_UPDATE_TS_RECENT | TcpInput.FLAG_NO_CHALLENGE_ACK);
        if (reason <= 0) {
            if (TcpState.TCP_SYN_RECV.equals(sk.state())) {
                // send one RST
                return 0 == reason ? TcpDropReason.SKB_DROP_REASON_TCP_OLD_ACK : -reason;
            }

            /* accept old ack during closing */
            if (reason < 0) {
                tp.tcp_send_challenge_ack();
                reason = -reason;
                return discard(tcpPacket, reason);
            }
        }

        boolean queued = false;
        reason = TcpDropReason.SKB_DROP_REASON_NOT_SPECIFIED;
        switch (sk.state()) {
            case TCP_SYN_RECV:
                tp.delivered++; /* SYN-ACK delivery isn't tracked in tcp_ack */
                tcp_init_transfer(tp, tcpPacket);

                sk.state(TcpState.TCP_ESTABLISHED);

                tp.snd_una = th.getAcknowledgmentNumber();
                tp.snd_wnd = th.getWindowAsInt() << tp.rx_opt.snd_wscale;
                tp.tcp_init_wl(th.getSequenceNumber());

                // ...

                /* Prevent spurious tcp_cwnd_restart() on first data packet */
                tp.lsndtime = tcp_jiffies32();
                tcp_initialize_rcv_mss(tp);

                break;
            case TCP_FIN_WAIT1:
                // ... fastopen

                if (tp.snd_una != tp.write_seq) {
                    break;
                }
                sk.state(TCP_FIN_WAIT2);
                tp.sk_shutdown |= TcpConstants.SEND_SHUTDOWN;

//                if (!sock_flag(sk, SOCK_DEAD) {
//                   sk_state_change
//                   break;
//                }

                if (tp.linger2 < 0) {
                    tcp_done(tp);
                    return SKB_DROP_REASON_TCP_ABORT_ON_DATA;
                }

                final int seq = th.getSequenceNumber();
                final int end_seq = determineEndSeq(tcpPacket);
                if (end_seq != seq && after(end_seq - (th.getFin() ? 1 : 0), tp.rcv_nxt)) {
                    tcp_done(tp);
                    return SKB_DROP_REASON_TCP_ABORT_ON_DATA;
                }

                final int tmo = tp.tcp_fin_time();
                if (tmo > TcpConstants.TCP_TIMEWAIT_LEN) {
                    /*-
                     * FIN_WAIT2 开始的总超时时间 > TIME_WAIT 的 2MSL, 则在进入 TIME_WAIT 前保证连接存活.
                     */
                    timer.tcp_reset_keepalive_timer(tp, tmo - TcpConstants.TCP_TIMEWAIT_LEN);
                } else if (th.getFin()) {
                    /* Bad case. We could lose such FIN otherwise.
                     * It is not a big problem, but it looks confusing
                     * and not so rare event. We still can lose it now,
                     * if it spins in bh_lock_sock(), but it is really
                     * marginal case.
                     */
                    timer.tcp_reset_keepalive_timer(tp, tmo);
                } else {
                    tcp_time_wait(tp, TCP_FIN_WAIT2, tmo);
                    return TcpDropReason.SKB_DROP_REASON_NOT_SPECIFIED;
                }
                break;
            case TCP_CLOSING:
                if (tp.snd_una == tp.write_seq) {
                    tcp_time_wait(tp, TCP_TIME_WAIT, 0);
                    return TcpDropReason.SKB_DROP_REASON_NOT_SPECIFIED;
                }
                break;
            case TCP_LAST_ACK:
                if (tp.snd_una == tp.write_seq) {
                    // tcp_update_metrics
                    tcp_done(tp);
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
                if (!before(th.getSequenceNumber(), tp.rcv_nxt)) {
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
                if (0 != (tp.sk_shutdown & TcpConstants.RCV_SHUTDOWN)) {
                    int seq = th.getSequenceNumber();
                    int end_seq = determineEndSeq(tcpPacket);
                    if (end_seq != seq && after(end_seq - (th.getFin() ? 1 : 0), tp.rcv_nxt)) {
                        input.tcp_reset(tp, tcpPacket);
                        return SKB_DROP_REASON_TCP_ABORT_ON_DATA;
                    }
                }
                // fallthrough
            case TCP_ESTABLISHED:
                input.tcp_data_queue(tp, tcpPacket);
                queued = true;
                break;
        }

        /* tcp_data could move socket to TIME-WAIT */
        if (!TcpState.TCP_CLOSE.equals(sk.state())) {
            input.tcp_data_snd_check(tp);
            input.tcp_ack_snd_check(tp);

            if (TcpState.TCP_CLOSE_WAIT.equals(sk.state())) {
                // FIXME
                if (null != sk.child) {
                    innerChannel(sk).close();
                } else if (tcp_close_state(sk)) {
                    output.tcp_send_fin(tp);
                }

            }
        }

        if (!queued) {
            tcp_drop_reason(tcpPacket, reason);
        }

        return TcpDropReason.SKB_DROP_REASON_NOT_SPECIFIED;
    }


    private int discard(final TcpPacket skb, final int reason) {
        tcp_drop_reason(skb, reason);
        return 0;
    }

    private void tcp_drop_reason(final TcpPacket skb, final int reason) {

    }

    /**
     * Shutdown the sending side of a connection. Much like close except
     * that we don't receive shut down or sock_set_flag(sk, SOCK_DEAD).
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp.c#L2979">tcp_shutdown</a>
     */
    void tcp_shutdown(final TcpSock sk, final int how) {
        if (0 == (how & TcpConstants.SEND_SHUTDOWN)) {
            return;
        }

        /* If we've already sent a FIN, or it's a closed state, skip this. */
        if (0 != ((1 << sk.state.get().ordinal()) & (TcpConstants.TCPF_ESTABLISHED | TcpConstants.TCPF_CLOSE_WAIT))) {
            /* Clear out any half completed packets.  FIN if needed. */
            if (tcp_close_state(sk)) {
                output.tcp_send_fin(sk);
            }
        }
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp.c#L4939">tcp_done</a>
     */
    public void tcp_done(TcpSock tp) {
        // ... fastopen

        tp.state.set(TcpState.TCP_CLOSE);
        timer.tcp_clear_xmit_timers(tp);

        // ... fastopen...

        tp.sk_shutdown = TcpConstants.SHUTDOWN_MASK;

//        if (!sock_flag(tp, SOCK_DEAD)) {
//            sk->sk_state_change(tp);
//        } else
        //
        inet_csk_destroy_sock(tp);
    }

    public void inet_csk_destroy_sock(SockCommon sk) {
        if (!TCP_CLOSE.equals(sk.state.get())) {
            // ...
        }

        if (null != sk.child) {
            innerChannel(sk).close();
        }
        if (null != sk.destroy) {
            sk.destroy.run();
        }
    }

    public synchronized void tcp_sendmsg2(final TcpSock tp, TcpBuffer skb, boolean flush) {
        skb.sequenceNumber(tp.write_seq);
        skb.dstPort(tp.srcPort).srcPort(tp.dstPort);
        tcp_skb_entail(tp, skb);

        final Packet.Builder payload = skb.payloadBuilder();
        if (null != payload) {
            tp.write_seq += payload.build().length();
        }
        if (flush) {
            tcp_push_pending_frames(tp);
        }
    }

    private void tcp_skb_entail(TcpSock sk, TcpBuffer skb) {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp.c#L676
        skb.sequenceNumber(sk.write_seq);
        skb.ack(true);
        sk.sk_write_queue.offer(skb);
    }

    public boolean tcp_close_state(SockCommon sk) {
        int next = TcpDemultiplexer.NEW_STATE[sk.state.get().ordinal() + 1];
        int ns = next & TcpDemultiplexer.TCP_STATE_MASK;

        sk.state.set(TcpState.values()[ns]);
        return 0 != (next & TcpDemultiplexer.TCP_ACTION_FIN);
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
        sk.sk_shutdown = TcpConstants.SHUTDOWN_MASK;

        TcpState state = sk.state.get();
        if (TCP_LISTEN.equals(state)) {
            sk.state.set(TCP_CLOSE);
            adjudge_to_death(sk);
            return;
        }

        // ...

        /* If socket has been already reset (e.g. in tcp_reset()) - kill it. */
        if (TCP_CLOSE.equals(state)) {
            adjudge_to_death(sk);
            return;
        }

        if (tcp_close_state(sk)) {
            output.tcp_send_fin(sk);
        }

        adjudge_to_death(sk);
    }

    private void adjudge_to_death(TcpSock sk) {
        final TcpState state = sk.state.get();
        if (TCP_FIN_WAIT2.equals(state)) {
            final int tmo = sk.tcp_fin_time();
            if (tmo > TcpConstants.TCP_TIMEWAIT_LEN) {
                timer.tcp_reset_keepalive_timer(sk, tmo - TcpConstants.TCP_TIMEWAIT_LEN);
            } else {
                tcp_time_wait(sk, TCP_FIN_WAIT2, tmo);
                return;
            }
        }

        if (!TcpState.TCP_CLOSE.equals(state)) {
            // TODO
        }

        // ...
    }

    public void tcp_time_wait(TcpSock tp, TcpState state, long timeout) {
        tp.state.set(state);

        if (TCP_TIME_WAIT.equals(state)) {
            tp.state.set(TCP_CLOSE);
            tcp_done(tp);
        }

    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L2102">tcp_push_pending_frames</a>
     */
    public void tcp_push_pending_frames(final TcpSock tp) {
        if (null != tp.tcp_send_head()) {
            output.__tcp_push_pending_frames(tp, output.tcp_current_mss(tp), tp.nonagle);
        }
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L75">tcp_write_err</a>
     */
    public void tcp_write_err(TcpSock sk) {
        log.warn("TCP WRITE ERROR");
        input.tcp_done_with_error(sk, sk.sk_err_soft != 0 ? sk.sk_err_soft : ETIMEOUT);
    }

    void sk_data_ready() {

    }

    public void consume(final TcpSock sk, final TcpPacket skb) {
        if (null != sk.child) {
            final TcpPacket.TcpHeader hdr = skb.getHeader();
            final byte[] bytes = skb.getPayload().getRawData();

            final int offset = sk.rcv_nxt - hdr.getSequenceNumber();
            final int length = Math.min(output.tcp_receive_window(sk), bytes.length - offset);
            innerChannel(sk).writeAndFlush(Unpooled.wrappedBuffer(bytes, offset, length));
        }
    }

    private void tcp_sendmsg(TcpSock sk) {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp.c#L1353
        // lock
        tcp_sendmsg_locked(sk);
        // unlock
    }

    private void tcp_sendmsg_locked(TcpSock sk) {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp.c#L1052

        // restart:
        final int mss_now = tcp_send_mss(sk);
    }

    int tcp_send_mss(final TcpSock tp) {
        return output.tcp_current_mss(tp);
    }

}