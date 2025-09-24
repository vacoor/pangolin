package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal;

import com.github.pangolin.routing.acceptor.tun.fakedns.DnsEngine;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.v2.*;
import com.github.pangolin.routing.support.SocketChannelFactory;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.*;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;
import org.pcap4j.packet.IpPacket;
import org.pcap4j.packet.IpPacket.IpHeader;
import org.pcap4j.packet.TcpPacket;
import org.pcap4j.packet.TcpPacket.TcpHeader;
import org.pcap4j.packet.UnknownPacket;

import java.io.IOException;

import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpClock.*;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpConstants.*;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpDropReason.SKB_DROP_REASON_TCP_ABORT_ON_DATA;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpHandshaker.tcpLogError;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpState.*;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpUtils.*;

@Slf4j
public abstract class TcpDemultiplexer<T extends IpPacket> extends TcpSock {

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
//    TcpPort tcpSrcPort;
//    TcpPort tcpDstPort;


    /**
     *
     */
    protected final Channel parent;
    final DnsEngine dnsEngine;
    final EventLoopGroup childGroup;
    final SocketChannelFactory socketChannelFactory;

    //    volatile Channel child;
    int connTimeoutMs = 10 * 1000;

    private tcp_request_sock request_sock;

    protected TcpDemultiplexer(final Channel parent, final EventLoopGroup childGroup, final DnsEngine dnsEngine, final SocketChannelFactory socketChannelFactory) {
        super();
        this.parent = parent;
        this.childGroup = childGroup;
        this.dnsEngine = dnsEngine;
        this.socketChannelFactory = socketChannelFactory;
        init();
        state.set(TCP_LISTEN);
    }

    protected void init() {
        tcp_init_sock(this);
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

    protected abstract tcp_request_sock conn_request(TcpSock p, final T ih, final TcpPacket skb);


    protected abstract void send_reset(IpHeader request, TcpPacket skb, int err);


    /* ************** ]] Initialize Connection Request ************ */

    /* **************** Open Connection Request [[ *************/

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L2179">tcp_v4_rcv</a> TCP_NEW_SYN_RECV
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c#L660">tcp_check_req</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L1742">tcp_v4_syn_recv_sock</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c#L518">tcp_create_openreq_child</a> <==
     */
    protected TcpDemultiplexer<T> tcp_check_req(tcp_request_sock req, final TcpPacket skb) {
        TcpDemultiplexer<T> nsk = tcp_v4_syn_recv_sock(req, skb);
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
        newsk.advmss = tcp_mss_clamp(newsk, dst_metric_advmss());

        tcp_initialize_rcv_mss(newsk);
        return newsk;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L2179">tcp_v4_rcv</a> TCP_NEW_SYN_RECV
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c#L660">tcp_check_req</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L1742">tcp_v4_syn_recv_sock</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c#L518">tcp_create_openreq_child</a> <==
     */
    private <T extends IpPacket> TcpDemultiplexer<T> tcp_create_openreq_child(TcpDemultiplexer<T> sk, tcp_request_sock req, final TcpPacket skb) {
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

        _seq = req.snt_isn + 1;
        newtp.snd_sml = newtp.snd_una = _seq;
        newtp.snd_nxt = _seq;
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

        newtp.child = req.child;
        newtp.destroy = req.destroy;
        newtp.INDIRECT_CALL_INET = req.INDIRECT_CALL_INET;
        return newtp;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/inet_connection_sock.c#L1216">inet_csk_clone_lock</a>
     */
    private static <T extends TcpSock> T inet_csk_clone_lock(final T sk, tcp_request_sock req) {
        final T newsk = sk; // sk_clone_lock

//         newsk.inet_dport = req....
//         newsk.inet_sport = ...

        newsk.ipHeader = req.ipHeader;
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
    protected static void tcp_init_sock(final TcpSock sk) {
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
        input.tcp_ack(this, skb, 0);

        /* step 7: process the segment text */
        input.tcp_data_queue(this, skb);

        input.tcp_data_snd_check(this);
        // tcp_ack_snd_check();
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L6299">tcp_init_transfer</a>
     */
    private void tcp_init_transfer(TcpDemultiplexer<T> tp, TcpPacket skb) {
        output.tcp_mtup_init();
        tcp_init_metrics();

        tp.tcp_snd_cwnd_set(tcp_init_cwnd());

        tp.snd_cwnd_stamp = tcp_jiffies32();

        tcp_init_congestion_control();

        // child.
        tp.child.pipeline().addLast(new ChannelInboundHandlerAdapter() {
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
                send_reset(ipHeader, new TcpPacket.Builder()
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

        tp.child.closeFuture().addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture channelFuture) throws Exception {
                TcpHandshaker.tcpLogInfo(null, tp.srcAddr, tp.srcPort.valueAsInt(), tp.dstAddr, tp.dstPort.valueAsInt(), "DISCONNECTED: {}", tp.dstAddr.getHostAddress());
                if (tcp_close_state(tp)) {
                    output.tcp_send_fin(tp);
                }
            }

        });
        tp.child.config().setAutoRead(true);
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
                    // for log
                    this.ipHeader = ipPacket.getHeader();
                    this.srcPort = skb.getHeader().getSrcPort();
                    this.dstPort = skb.getHeader().getDstPort();

                    tcp_request_sock tcpRequestSock = conn_request(tp, ipPacket, skb);
                    if (null == tcpRequestSock) {
                        return TcpDropReason.SKB_DROP_REASON_NO_SOCKET;
                    }

                    this.request_sock = tcpRequestSock;
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
                /*-
                 * 原本应该是收到第三次握手的ACK请求后，查找半连接队列, 如果查找到状态为 TCP_NEW_SYN_RECV 的请求套接字,
                 * 调用 tcp_check_req, 转换为 TCP_SYN_RECV 的子套接字, 并完成握手后迁移到全连接队列.
                 * 这里暂时没有采用父子关系, 上面直接建立了连接所以直接转换为 TCP_SYNC_RECV.
                 */
                tcp_check_req(request_sock, skb);

                state.set(TcpState.TCP_SYN_RECV);
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
                tp.tcp_send_challenge_ack();
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
                    tcp_done(tp);
                    return SKB_DROP_REASON_TCP_ABORT_ON_DATA;
                }

                final int seq = th.getSequenceNumber();
                final int end_seq = determineEndSeq(skb);
                if (end_seq != seq && after(end_seq - (th.getFin() ? 1 : 0), rcv_nxt)) {
                    tcp_done(tp);
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
                    tcp_time_wait(tp, TCP_FIN_WAIT2, tmo);
                    return TcpDropReason.SKB_DROP_REASON_NOT_SPECIFIED;
                }
                break;
            case TCP_CLOSING:
                if (snd_una == write_seq) {
                    tcp_time_wait(tp, TCP_TIME_WAIT, 0);
                    return TcpDropReason.SKB_DROP_REASON_NOT_SPECIFIED;
                }
                break;
            case TCP_LAST_ACK:
                if (snd_una == write_seq) {
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
                } else if (tcp_close_state(sk)) {
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


}