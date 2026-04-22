package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.core;

import com.github.pangolin.routing.acceptor.tun.fakedns.DnsEngine;
import com.github.pangolin.routing.acceptor.tun.net.codec.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.*;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.sock.Sock;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.sock.SockCommon;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.sock.TcpSock;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.sock.tcp_request_sock;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpClock;
import com.github.pangolin.routing.support.SocketChannelFactory;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpConstant.TCP_RTO_MAX;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpClock.*;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpConstants.*;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpState.*;

@Slf4j
public abstract class TcpMultiplexer {

    // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L943
    public static final int TCPCB_SACKED_ACKED = (1 << 0);    /* SKB ACK'd by a SACK block	*/
    public static final int TCPCB_SACKED_RETRANS = (1 << 1);    /* SKB retransmitted		*/
    public static final int TCPCB_LOST = (1 << 2);    /* SKB is lost			*/
    public static final int TCPCB_TAGBITS = (TCPCB_SACKED_ACKED | TCPCB_SACKED_RETRANS | TCPCB_LOST);    /* All tag bits			*/
    public static final int TCPCB_REPAIRED = (1 << 4);    /* SKB repaired (no skb_mstamp_ns)	*/
    public static final int TCPCB_EVER_RETRANS = (1 << 7);    /* Ever retransmitted frame	*/
    public static final int TCPCB_RETRANS = (TCPCB_SACKED_RETRANS | TCPCB_EVER_RETRANS | TCPCB_REPAIRED);

    public static final int DEFAULT_MAX_SYN_BACKLOG = 1024;

    private final int maxSynBacklog;

    protected TcpSock listenSock;

    /**
     *
     */
    final DnsEngine dnsEngine;
    final EventLoopGroup childGroup;
    final SocketChannelFactory socketChannelFactory;

    int connTimeoutMs = 5 * 1000;


    public TcpOutput output = new TcpOutput(this);
    public TcpInput input = new TcpInput(this, output);
    public TcpTimer timer = new TcpTimer(this);

    protected Map<String, tcp_request_sock> synRegistry;
    protected Map<String, TcpSock> establishedRegistry;

    protected request_sock_ops requestSockOps;
    protected tcp_request_sock_ops tcpRequestSockOps;

    protected TcpMultiplexer(
            Map<String, tcp_request_sock> synRegistry,
            Map<String, TcpSock> establishedRegistry,
            final EventLoopGroup childGroup,
            final DnsEngine dnsEngine,
            final SocketChannelFactory socketChannelFactory,
            final request_sock_ops requestSockOps/*,
            final tcp_request_sock_ops tcpRequestSockOps*/) {
        super();
        this.synRegistry = synRegistry;
        this.establishedRegistry = establishedRegistry;
        this.maxSynBacklog = DEFAULT_MAX_SYN_BACKLOG;

        this.childGroup = childGroup;
        this.dnsEngine = dnsEngine;
        this.socketChannelFactory = socketChannelFactory;
        this.requestSockOps = requestSockOps;
//        this.tcpRequestSockOps = tcpRequestSockOps;
        init();
    }

    protected void init() {
        listenSock = init(new TcpSock());
        listenSock.state(TcpState.TCP_LISTEN);
    }

    protected abstract TcpSock init(final TcpSock sk);

    public abstract void tcp_rcv(final Channel net, final TcpPacketBuf pkt);

    // ...


    // https://www.cnblogs.com/wanpengcoder/p/11751763.html


    private int TCPOLEN_TSTAMP_ALIGNED = 12;

    /* ************** Initialize Connection Request [[ ************ */

//    protected abstract tcp_request_sock conn_request(final Channel net, TcpSock listenSock, final T ipPacket, final TcpPacket tcpPacket);


    public abstract void send_reset(final Channel net, final TcpPacketBuf pkt, int err);

    /**
     * Retransmits a SYN-ACK for the given half-open connection.
     * Called by {@link TcpTimer#reqsk_timer_handler} on each retransmit tick.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/inet_connection_sock.c#L758">inet_rtx_syn_ack</a>
     */
    public abstract void inet_rtx_syn_ack(Channel net, TcpSock listenSock, tcp_request_sock req);


    /* ************** ]] Initialize Connection Request ************ */

    /* **************** Open Connection Request [[ *************/

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L2179">tcp_v4_rcv</a> TCP_NEW_SYN_RECV
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c#L660">tcp_check_req</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L1742">tcp_v4_syn_recv_sock</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c#L518">tcp_create_openreq_child</a>，对应 child sock 创建点
     */
    public TcpSock tcp_check_req(final Channel net, TcpSock listenSock, TcpPacketBuf pkt, tcp_request_sock req) {
        // Step 1: RST — validate seq, then clean up the half-open connection
        if (pkt.isRst()) {
            if (pkt.tcpSeq() == req.rcv_nxt) {
                inet_csk_destroy_sock(req);
            }
            // out-of-window RST: drop silently
            return null;
        }

        // Step 2: SYN retransmit — only retransmit SYN-ACK if it was already sent
        // (rsk_timer != null means scheduleReqskTimer fired after a successful SYN-ACK write)
        // If backend is still connecting, SYN-ACK has not been sent yet — drop silently
        if (pkt.isSyn()) {
            if (pkt.tcpSeq() == req.rcv_isn && req.rsk_timer != null) {
                inet_rtx_syn_ack(net, listenSock, req);
                req.num_retrans++;
            }
            return null;
        }

        // Step 3: Only ACK can complete the handshake
        if (!pkt.isAck()) {
            return null;
        }

        // Step 4: ACK number must acknowledge exactly our SYN-ACK (snt_isn + 1)
        if (pkt.tcpAckNum() != req.snt_isn + 1) {
            send_reset(net, pkt, -1);
            return null;
        }

        // Step 5: All checks passed — create the child socket
        return listenSock.icsk_af_ops.syn_recv_sock(net, listenSock, pkt, req);
    }


    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L2179">tcp_v4_rcv</a> TCP_NEW_SYN_RECV
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c#L660">tcp_check_req</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L1742">tcp_v4_syn_recv_sock</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c#L518">tcp_create_openreq_child</a>，对应 child sock 创建点
     */
    @SuppressWarnings("deprecation")
    protected TcpSock tcp_create_openreq_child(Channel net, TcpSock sk, tcp_request_sock req) {
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

        tcp_init_wl(newtp, req.rcv_isn);

        // ...
        newsk.icsk_ack.lrcvtime = tcp_jiffies32();

        newsk.lsndtime = tcp_jiffies32();
        // newsk.total_retrans = req->num_retrans;

        timer.tcp_init_xmit_timers(net, newsk);
        newtp.write_seq = newtp.pushed_seq = req.snt_isn + 1;

        // ... keepopen

        newtp.rx_opt.tstamp_ok = req.tstamp_ok;
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

        newtp.snd_wnd = req.snd_wnd << newtp.rx_opt.snd_wscale;
        newtp.max_window = newtp.snd_wnd;

        boolean rx_opt_tstamp = newtp.rx_opt.tstamp_ok;
        if (rx_opt_tstamp) {
            newtp.tcp_usec_ts = req.req_usec_ts ? 1 : 0;
            newtp.rx_opt.ts_recent = req.ts_recent;
            newtp.rx_opt.ts_recent_stamp = (int) (System.currentTimeMillis() / 1000);
            newtp.tcp_header_len = SIZE_OF_TCP_HDR + TCPOLEN_TSTAMP_ALIGNED;
        } else {
            newtp.tcp_usec_ts = 0;
            newtp.rx_opt.ts_recent_stamp = 0;
            newtp.tcp_header_len = SIZE_OF_TCP_HDR;
        }

        // ...

        newtp.tsoffset = req.ts_off;

        newtp.rx_opt.mss_clamp = req.mss;

        newtp.child = req.child;
        newtp.childCloseListener = req.childCloseListener;
//        newtp.INDIRECT_CALL_INET = req.INDIRECT_CALL_INET;
        return newtp;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/inet_connection_sock.c#L1216">inet_csk_clone_lock</a>
     */
    private TcpSock inet_csk_clone_lock(final TcpSock sk, tcp_request_sock req) {
        // final T newsk = sk; // sk_clone_lock
        final TcpSock newsk = init(new TcpSock());

        newsk.sk_err = 0;
        newsk.sk_err_soft = 0;

        newsk.ir_rmt_addr = req.ir_rmt_addr;
        newsk.ir_loc_addr = req.ir_loc_addr;

        newsk.ir_rmt_port = req.ir_rmt_port;
        newsk.ir_num = req.ir_num;


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

        final int rto_max_ms = TcpConstant.TCP_RTO_MAX; //sk.ipv4_sysctl_tcp_rto_max_ms;
        sk.icsk_rto_max = (int) msecs_to_jiffies(rto_max_ms);

        final int rto_min_ms = TcpConstant.TCP_RTO_MIN; //sk.ipv4_sysctl_tcp_rto_min_ms;
        sk.icsk_rto_min = (int) msecs_to_jiffies(rto_min_ms);

        sk.icsk_delack_max = TcpConstant.TCP_DELACK_MAX;
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


    void tcp_init_metrics(final Sock sk) {
    }


    void tcp_init_congestion_control(final Sock sk) {
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


    public static Channel innerChannel(SockCommon sock) {
        if (sock == null || sock.child == null) {
//            throw new NullPointerException("sock or sock.child is null");
            return null;
        }
//        if (!sock.child.isDone()) {
//            throw new IllegalStateException("child channel future is not done yet");
//        }
//        if (sock.child.cause() != null) {
//            throw new RuntimeException("child channel future failed", sock.child.cause());
//        }
        return sock.child.channel();
    }

    protected void addToHalfQueue(final TcpSock listenSock, final tcp_request_sock sock) {
//        sk.state(TcpState.TCP_NEW_SYN_RECV);
//        this.request_sock = sock;

        sock.state(TcpState.TCP_NEW_SYN_RECV);
        synRegistry.putIfAbsent(sock.uniqueKey(), sock);
    }

    protected void moveToEstablished(final tcp_request_sock req, final TcpSock sock) {
        final String sockKey = req.uniqueKey();
        synRegistry.remove(sockKey, req);
        establishedRegistry.put(sockKey, sock);
    }


    /**
     * Shutdown the sending side of a connection. Much like close except
     * that we don't receive shut down or sock_set_flag(sk, SOCK_DEAD).
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp.c#L2979">tcp_shutdown</a>
     */
    void tcp_shutdown(final Channel net, final TcpSock sk, final int how) {
        if (0 == (how & TcpConstants.SEND_SHUTDOWN)) {
            return;
        }

        /* If we've already sent a FIN, or it's a closed state, skip this. */
        if (0 != ((1 << sk.state().ordinal()) & (TcpConstants.TCPF_ESTABLISHED | TcpConstants.TCPF_CLOSE_WAIT))) {
            /* Clear out any half completed packets.  FIN if needed. */
            if (tcp_close_state(sk)) {
                output.tcp_send_fin(net, sk);
            }
        }
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp.c#L4939">tcp_done</a>
     */
    public void tcp_done(TcpSock tp) {
        // ... fastopen

        tp.state(TcpState.TCP_CLOSE);
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
        if (!TCP_CLOSE.equals(sk.state())) {
            // ...
        }

        // Cancel SYN-ACK retransmission timer if this is a half-open connection
        if (sk instanceof tcp_request_sock) {
            final tcp_request_sock req = (tcp_request_sock) sk;
            if (req.rsk_timer != null) {
                timer.sk_stop_timer(req.rsk_timer);
                req.rsk_timer = null;
            }
        }

        // Release OFO queue ByteBufs for established connections
        if (sk instanceof TcpSock) {
            tcp_ofo_queue_release((TcpSock) sk);
        }

        if (null != sk.child) {
            innerChannel(sk).close();
            sk.child = null;
        }

        synRegistry.remove(sk.uniqueKey());
        establishedRegistry.remove(sk.uniqueKey());
    }

    public void tcp_sendmsg2(final Channel net, final TcpSock tp, TcpBuffer skb, boolean flush) {
        // Lock on tp rather than `this` so that different connections can send concurrently.
        // All state mutated here (write_seq, sk_write_queue, send path) is per-connection;
        // there is no shared state between distinct TcpSock instances in this method.
        synchronized (tp) {
            skb.sequenceNumber(tp.write_seq);

            tcp_skb_entail(tp, skb);

            if (skb.payloadLength() > 0) {
                // only for build.
                skb.dstPort(tp.ir_rmt_port).srcPort(tp.ir_num);
                tp.write_seq += skb.payloadLength();
            }
            if (flush) {
                tcp_push_pending_frames(net, tp);
            }
        }
    }

    private void tcp_skb_entail(TcpSock sk, TcpBuffer skb) {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp.c#L676
        skb.sequenceNumber(sk.write_seq);
        skb.ack(true);
        sk.sk_write_queue.offer(skb);
    }

    public boolean tcp_close_state(SockCommon sk) {
        int next = TcpMultiplexer.NEW_STATE[sk.state().ordinal() + 1];
        int ns = next & TcpMultiplexer.TCP_STATE_MASK;

        sk.state(TcpState.values()[ns]);
        return 0 != (next & TcpMultiplexer.TCP_ACTION_FIN);
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp.c#L3240">tcp_close</a>
     */
    private void tcp_close(Channel net, TcpSock sk, long timeout) {
        __tcp_close(net, sk, timeout);
        // release_sock();
        // ...
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp.c#L3066">__tcp_close</a>
     */
    private void __tcp_close(Channel net, TcpSock sk, long timeout) {
        // FIXME
        sk.sk_shutdown = TcpConstants.SHUTDOWN_MASK;

        TcpState state = sk.state();
        if (TCP_LISTEN.equals(state)) {
            sk.state(TCP_CLOSE);
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
            output.tcp_send_fin(net, sk);
        }

        adjudge_to_death(sk);
    }

    private void adjudge_to_death(TcpSock sk) {
        final TcpState state = sk.state();
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

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c#L328">tcp_time_wait</a>
     */
    public void tcp_time_wait(TcpSock tp, TcpState state, long timeout) {

        tp.state(state);

        // FIXME
        if (TCP_TIME_WAIT.equals(state)) {
            tp.state(TCP_CLOSE);
            tcp_done(tp);
        }

    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L2102">tcp_push_pending_frames</a>
     */
    public void tcp_push_pending_frames(final Channel net, final TcpSock tp) {
        if (null != tp.tcp_send_head()) {
            output.__tcp_push_pending_frames(net, tp, output.tcp_current_mss(tp), tp.nonagle);
        }
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L75">tcp_write_err</a>
     */
    public void tcp_write_err(TcpSock sk) {
        log.info("TCP WRITE ERROR");
        input.tcp_done_with_error(sk, sk.sk_err_soft != 0 ? sk.sk_err_soft : ETIMEOUT);
    }

    void sk_data_ready() {

    }

    public void consume(final TcpSock sk, final TcpPacketBuf pkt) {
        if (null != sk.child) {
            final int offset = sk.rcv_nxt - pkt.tcpSeq();
            final int payloadLen = pkt.tcpPayloadLength();
            final int length = Math.min(output.tcp_receive_window(sk), payloadLen - offset);
            if (length <= 0) {
                return;
            }
            innerChannel(sk).writeAndFlush(pkt.tcpPayloadSlice().retainedSlice(offset, length));
        }
    }

    /**
     * Delivers a raw payload ByteBuf (from the OOO queue) to the tunnel layer.
     * The caller owns the ByteBuf's reference count; this method retains it before
     * passing to writeAndFlush and does NOT release the original.
     */
    public void consumeRaw(final TcpSock sk, final ByteBuf data) {
        if (sk.child != null && data.isReadable()) {
            innerChannel(sk).writeAndFlush(data.retain());
        }
    }

    /**
     * Releases all ByteBufs held in the OOO queue of the given socket.
     * Must be called when the connection is torn down to prevent memory leaks.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L4393">tcp_fin — skb_rbtree_purge(&amp;tp-&gt;out_of_order_queue)</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/linux/skbuff.h">skb_rbtree_purge</a>
     */
    private void tcp_ofo_queue_release(final TcpSock tp) {
        if (tp.out_of_order_queue.isEmpty()) {
            return;
        }
        for (final OfoEntry entry : tp.out_of_order_queue.values()) {
            entry.release();
        }
        tp.out_of_order_queue.clear();
        tp.ofo_queue_bytes = 0;
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

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L1478">tcp_init_wl</a>
     */
    public static void tcp_init_wl(TcpSock tp, int seq) {
        tp.snd_wl1 = seq;
    }

    /**
     * Compute the actual rto_min value.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L783">tcp_rto_min</a>
     */
    public static int tcp_rto_min(final TcpSock sk) {
        // TODO
        return sk.icsk_rto_min;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L759">tcp_bound_rto</a>
     */
    public static void tcp_bound_rto(final TcpSock sk) {
        if (sk.icsk_rto > TCP_RTO_MAX) {
            sk.icsk_rto = TCP_RTO_MAX;
        }
    }


    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L765">__tcp_set_rto</a>
     */
    public static long __tcp_set_rto(final TcpSock sk) {
        return TcpClock.usecs_to_jiffies((sk.srtt_us >> 3) + sk.rttvar_us);
    }

    public static long tcp_rto_min_us(final TcpSock sk) {
        return jiffies_to_usecs(tcp_rto_min(sk));
    }


    /**
     * @param sk
     * @return
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L2702">tcp_timeout_init</a>
     */
    public long tcp_timeout_init(SockCommon sk) {
        return Math.min(TCP_TIMEOUT_INIT, TCP_RTO_MAX);
    }

    public boolean sk_acceptq_is_full() {
        return synRegistry.size() >= maxSynBacklog;
    }
}
