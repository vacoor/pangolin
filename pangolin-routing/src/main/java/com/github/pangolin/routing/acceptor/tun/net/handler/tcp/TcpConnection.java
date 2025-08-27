package com.github.pangolin.routing.acceptor.tun.net.handler.tcp;

import com.github.pangolin.routing.acceptor.tun.fakedns.DnsEngine;
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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public abstract class TcpConnection<T extends IpPacket> {
    private static final int DEFAULT_MTU = 1500;
    private static final int MINIMUM_MTU = 576;

    static final short IP_HEADER_SIZE = 20;
    private static final short TCP_HEADER_SIZE = 20;

    /**
     * https://github.com/torvalds/linux/blob/master/include/net/dropreason-core.h#L127.
     */
    public static final int SKB_DROP_REASON_NOT_SPECIFIED = 0;
    public static final int SKB_DROP_REASON_TCP_FLAGS = 1;
    public static final int SKB_DROP_REASON_TCP_RESET = 2;
    public static final int SKB_DROP_REASON_TCP_CLOSE = 6;
    public static final int SKB_DROP_REASON_TCP_ZEROWINDOW = 3;
    public static final int SKB_DROP_REASON_TCP_OLD_DATA = 4;
    public static final int SKB_DROP_REASON_TCP_OVERWINDOW = 5;
    public static final int SKB_DROP_REASON_NO_SOCKET = 7;
    public static final int SKB_DROP_REASON_TCP_ABORT_ON_DATA = 8;
    public static final int SKB_DROP_REASON_TCP_ACK_UNSENT_DATA = 9;
    public static final int SKB_DROP_REASON_TCP_OLD_ACK = 10;

    // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L943
    public static final int TCPCB_SACKED_ACKED = (1 << 0);    /* SKB ACK'd by a SACK block	*/
    public static final int TCPCB_SACKED_RETRANS = (1 << 1);    /* SKB retransmitted		*/
    public static final int TCPCB_LOST = (1 << 2);    /* SKB is lost			*/
    public static final int TCPCB_TAGBITS = (TCPCB_SACKED_ACKED | TCPCB_SACKED_RETRANS | TCPCB_LOST);    /* All tag bits			*/
    public static final int TCPCB_REPAIRED = (1 << 4);    /* SKB repaired (no skb_mstamp_ns)	*/
    public static final int TCPCB_EVER_RETRANS = (1 << 7);    /* Ever retransmitted frame	*/
    public static final int TCPCB_RETRANS = (TCPCB_SACKED_RETRANS | TCPCB_EVER_RETRANS | TCPCB_REPAIRED);


    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp_states.h">tcp_states.h</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.c">tcp.c</a>
     */
    enum State {

        /**
         * connection established.
         */
        TCP_ESTABLISHED,

        /**
         * sent a connection request, waiting for ack.
         */
        TCP_SYN_SENT,

        /**
         * received a connection request, sent ack,
         * waiting for final ack in three-way handshake.
         */
        TCP_SYN_RECV,

        /**
         * our side has shutdown, waiting to complete
         * transmission of remaining buffered data.
         */
        TCP_FIN_WAIT1,

        /**
         * all buffered data sent, waiting for remote
         * to shutdown.
         */
        TCP_FIN_WAIT2,

        /**
         * timeout to catch resent junk before entering
         * closed, can only be entered from FIN_WAIT2
         * or CLOSING.  Required because the other end
         * may not have gotten our last ACK causing it
         * to retransmit the data packet (which we ignore).
         */
        TCP_TIME_WAIT,

        /**
         * socket is finished.
         */
        TCP_CLOSE,

        /**
         * remote side has shutdown and is waiting for
         * us to finish writing our data and to shutdown
         * (we have to close() to move on to LAST_ACK).
         */
        TCP_CLOSE_WAIT,

        /**
         * out side has shutdown after remote has
         * shutdown.  There may still be data in our
         * buffer that we have to finish sending.
         */
        TCP_LAST_ACK,

        TCP_LISTEN,

        /**
         * both sides have shutdown but we still have
         * data we have to finish sending.
         */
        TCP_CLOSING,


        TCP_NEW_SYN_RECV,

        TCP_BOUND_INACTIVE,

        TCP_MAX_STATES

    }


    IpHeader ipHeader;
    TcpPort tcpSrcPort;
    TcpPort tcpDstPort;

    final AtomicReference<State> state = new AtomicReference<>(State.TCP_CLOSE);


    // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L243
    /* TCP initial congestion window as per rfc6928 */
    private static final int TCP_INIT_CWND = 10;
    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L1327">TCP_INFINITE_SSTHRESH</a>
     */
    private static final int TCP_INFINITE_SSTHRESH = 0x7fffffff;

    static final byte TCP_MAX_WSCALE = 14;

    static final int HZ = 1000;
    static final boolean sysctl_tcp_window_scaling = true;
    static final int sysctl_tcp_retries1 = 5;

    /**
     * 用户定义的 MSS.
     */
    private int user_mss;

    /**
     * 对端通告 MSS.
     */
    int mss_clamp;

    /**
     * 本端能接收的最大MSS, 通告对端的MSS.
     */
    int advmss;

    /**
     * 缓存发送方当前有效的MSS, 根据pmtu变动.
     */
    int mss_cache;
    /**
     * 由最近接收到的段估算的对端mss，主要用来确定是否执行延迟确认.
     */
    private int rcv_mss;

    boolean wscale_ok;

    /*-
     *              |<------- TCP recv window ------->|
     *              |            (rcv.wnd)            |
     *  --------------------------------------------------------------------
     * | .. | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 | 12 | 13 | 14 |  15  | ...
     *  --------------------------------------------------------------------
     * |  sent and  | sent and not  |                 | can't receive until |
     * |acknowledged| acknowledged  |                 |    window moves     |
     *              ^               ^                 ^
     *              |-closes->   rcv.nxt    <-shrinks-|-opens->
     *          left edge                        right edge
     *          (rcv.wup)                    (rcv.wup + rcv.wnd)
     *
     */

    int rcv_isn;
    int rcv_wup;
    int rcv_nxt;
    int rcv_wnd;

    private int copied_seq;

    byte rcv_wscale = 6;

    int icsk_ack_rcv_mss;


    int bytes_acked;
    long bytes_received;
    int bytes_sent;
    int data_segs_out;
    int segs_out;


    /*-
     *              |<------- TCP send window ------->|
     *              |            (snd.wnd)            |
     *              |               |<-Usable window->|
     *  --------------------------------------------------------------------
     * | .. | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 | 12 | 13 | 14 |  15  | ...
     *  --------------------------------------------------------------------
     * |  sent and  | sent and not  |    being sent   |   can't send until  |
     * |acknowledged| acknowledged  |                 |     window moves    |
     *              ^               ^                 ^
     *              |-closes->    snd.nxt   <-shrinks-|-opens->
     *          left edge                        right edge
     *          (snd.una)                    (snd.una + snd.wnd)
     *
     * Usable window = snd.una + snd.wnd - snd.nxt
     */

    int snt_isn;
    int snd_una;
    int snd_wnd;
    int snd_nxt;
    /**
     * Urgent pointer.
     *
     * @deprecated
     */
    @Deprecated
    int snd_up;
    int snd_sml;

    byte snd_wscale;

    /**
     * 最大窗口.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/linux/tcp.h#L192">tcp_sock</a>
     */
    int max_window;

    private int snd_cwnd;
    private long snd_cwnd_stamp;


    /**
     * 触发窗口更新的序号.
     * Sequence for window update.
     */
    int snd_wl1;

    /**
     * 下一个写入发送队列的序号.
     */
    int write_seq;

    /**
     * Last pushed seq, required to talk to windows.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/linux/tcp.h#L264">pushed_seq</a>
     */
    int pushed_seq;

    /**
     * 已发出待接收ACK的数据包数量.
     */
    int packets_out;

    ConcurrentLinkedQueue<TcpBuffer> sk_write_queue = new ConcurrentLinkedQueue<>();
    ConcurrentLinkedQueue<TcpBuffer> tcp_rtx_queue = new ConcurrentLinkedQueue<>();

    protected final Channel parent;
    private final DnsEngine dnsEngine;
    private final EventLoopGroup childGroup;
    private final SocketChannelFactory socketChannelFactory;

    volatile Channel child;
    private int connTimeoutMs = 10 * 1000;

    TcpInput<T> input;
    TcpOutput<T> output;
    TcpTimer<T> timer;

    protected TcpConnection(final Channel parent, final EventLoopGroup childGroup, final DnsEngine dnsEngine, final SocketChannelFactory socketChannelFactory) {
        this.parent = parent;
        this.childGroup = childGroup;
        this.dnsEngine = dnsEngine;
        this.socketChannelFactory = socketChannelFactory;
        this.output = new TcpOutput<T>(this);
        this.input = new TcpInput<>(this.output);
        this.timer = new TcpTimer<>(this);
        init();
        this.listen();
    }

    protected void init() {
        tcp_init_sock();
    }

    private TcpConnection<T> listen() {
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
        State s = this.state.get();
        int st = s.ordinal();
        if (0 == ((1 << st) & (TcpConstants.TCPF_CLOSE | TcpConstants.TCPF_LISTEN))) {
            // return -EINVAL;
            return -1;
        }

        /* Really, if the socket is already in listen state
         * we can only allow the backlog to be adjusted.
         */
        if (!State.TCP_LISTEN.equals(s)) {
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
        state.compareAndSet(State.TCP_CLOSE, State.TCP_LISTEN);
        return 0;
    }

    public void handler(final T ipHeader, final TcpPacket tcpPacket) {
        if (null != child) {
            child.eventLoop().execute(() -> handler0(ipHeader, tcpPacket));
        } else {
            handler0(ipHeader, tcpPacket);
        }
    }

    protected abstract void handler0(final T ipHeader, final TcpPacket tcpPacket);


    /**
     * 发送可用/拥塞窗口大小.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L1312">tcp_snd_cwnd</a>
     */
    int tcp_snd_cwnd() {
        // FIXME
        return snd_cwnd;
    }

    /**
     * 发送窗口右边界.
     */
    int tcp_wnd_end() {
        return snd_una + snd_wnd;
    }

    int sk_shutdown;


    // https://www.cnblogs.com/wanpengcoder/p/11751763.html


    /**
     * https://github.com/torvalds/linux/blob/master/include/net/dropreason-core.h.
     */
    public static final int SKB_NOT_DROPPED_YET = 0;

    int tcp_header_len;
    int icsk_ext_hdr_len;
    private int TCPOLEN_TSTAMP_ALIGNED = 12;

    int window_clamp;
    int rcv_ssthresh;

    /* ************** Initialize Connection Request [[ ************ */

    AtomicInteger tmp_opt_rx_user_mss = new AtomicInteger();
    AtomicInteger tmp_opt_rx_mss_clamp = new AtomicInteger();
    AtomicBoolean tmp_opt_wscale_ok = new AtomicBoolean();
    AtomicInteger tmp_opt_snd_wscale = new AtomicInteger();

    final AtomicInteger req_snt_isn_ref = new AtomicInteger();
    final AtomicInteger req_rcv_isn_ref = new AtomicInteger();
    final AtomicInteger req_rcv_nxt_ref = new AtomicInteger();
    private final AtomicInteger req_mss_ref = new AtomicInteger();
    final AtomicInteger req_rsk_rcv_wnd_ref = new AtomicInteger();
    private final AtomicInteger req_rsk_window_clamp_ref = new AtomicInteger();

    final AtomicBoolean ireq_wscale_ok_ref = new AtomicBoolean();
    private final AtomicInteger ireq_snd_wscale_ref = new AtomicInteger();
    final AtomicInteger ireq_rcv_wscale_ref = new AtomicInteger();

    protected boolean conn_request(final T ih, final TcpPacket skb) {
        return tcp_conn_request(ih, skb);
    }

    /**
     * @param skb
     * @return
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L7195">tcp_conn_request</a>
     */
    private boolean tcp_conn_request(final T pkg, final TcpPacket skb) {
        final IpHeader ipHdr = pkg.getHeader();
        /*-
         * 这里创建的 request_sock 状态是 TCP_NEW_SYN_RECV.
         * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/inet_connection_sock.c#L950">inet_reqsk_alloc</a>
         */
        if (!inet_reqsk_alloc(ipHdr, skb)) {
            return false;
        }

        ts_off = 0;
        tcp_usec_ts = 0;

        tmp_opt_rx_user_mss.set(user_mss);
        tmp_opt_rx_mss_clamp.set(mss_clamp);

        input.tcp_parse_options(this, skb, false);
        tcp_openreq_init(skb);

        ipHeader = ipHdr;
        tcpSrcPort = skb.getHeader().getSrcPort();
        tcpDstPort = skb.getHeader().getDstPort();
        // ...

        // FIXME
        final boolean opt_tstamp_ok = false;
        if (opt_tstamp_ok) {
            tcp_usec_ts = 0;
            ts_off = init_ts_off(skb);
        }

        req_snt_isn_ref.set(initSeq(null, skb.getHeader()));

        // init rwin
        tcp_openreq_init_rwin(skb);

        // send_synack
        send_synack(ipHdr, skb);

        return true;
    }


    private boolean inet_reqsk_alloc(IpHeader ipHeader, TcpPacket skb) {
        final InetSocketAddress resolved = resolve(ipHeader.getDstAddr(), skb.getHeader().getDstPort().valueAsInt());
        if (null == resolved) {
            // no socket.
            return false;
        }

        final long sinceMs = System.currentTimeMillis();
        logInfo("ESTABLISHING -> {}", resolved);
        final ChannelFuture cf = socketChannelFactory.open(resolved, connTimeoutMs, true, childGroup, new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
                try {
                    logInfo("[TCP] Read from Upstream");
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

                    final int mss = output.tcp_current_mss(TcpConnection.this);
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
                input.tcp_done_with_error(TcpConnection.this, -1);
            }
        });

        try {
            child = cf.sync().channel();
            state.set(State.TCP_SYN_RECV);
            final long elapsedMs = System.currentTimeMillis() - sinceMs;
            logInfo("ESTABLISHED: {}, elapsed: {}ms", resolved, elapsedMs);
            child.closeFuture().addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(final ChannelFuture future) throws Exception {
                    logInfo("DISCONNECTED: {}", resolved);
                    shutdown(SEND_SHUTDOWN);
                }
            });
            return true;
        } catch (InterruptedException e) {
            logInfo("CONNECTION RESET by {}: {}", e.getMessage(), resolved, e);
            return false;
        }
    }

    /**
     * @param skb
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L7068">tcp_openreq_init</a>
     * @see <a href="https://www.cnblogs.com/wanpengcoder/p/11751292.html">TCP MSS</a>
     */
    private void tcp_openreq_init(final TcpPacket skb) {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L7068
        final TcpHeader hdr = skb.getHeader();
        req_rsk_rcv_wnd_ref.set(0);
        req_rcv_isn_ref.set(hdr.getSequenceNumber());
        req_rcv_nxt_ref.set(hdr.getSequenceNumber() + 1);

        req_mss_ref.set(tmp_opt_rx_mss_clamp.get());

        ireq_wscale_ok_ref.set(tmp_opt_wscale_ok.get());
        ireq_snd_wscale_ref.set(tmp_opt_snd_wscale.get());
    }

    /**
     * @param ipHdr
     * @param tcpHdr
     * @return
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L103">tcp_v4_init_seq</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/core/secure_seq.c#L136">secure_tcp_seq</a>
     */
    protected int initSeq(final IpHeader ipHdr, final TcpHeader tcpHdr) {
//        new SecureRandom().nextInt();
        return tcpHdr.getSequenceNumber();
    }

    private void tcp_openreq_init_rwin(TcpPacket skb) {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c#L422
        int full_space = output.tcp_full_space(this);
        final int mss = tcp_mss_clamp(dst_metric_advmss());

        req_rsk_window_clamp_ref.set(dst_metric(RTAX_WINDOW));

        int rcv_wnd = 0; //...
        if (rcv_wnd == 0) {
            rcv_wnd = dst_metric(RTAX_INITRWND);
        } else if (full_space < rcv_wnd * mss) {
            full_space = rcv_wnd * mss;
        }
        // ...

        AtomicInteger rcv_wscale_ref = new AtomicInteger();
        output.tcp_select_initial_window(
                this,
                full_space,
                mss, // - stamp
                req_rsk_rcv_wnd_ref,
                req_rsk_window_clamp_ref,
                ireq_wscale_ok_ref.get(),
                rcv_wscale_ref,
                rcv_wnd
        );
        ireq_rcv_wscale_ref.set(rcv_wscale_ref.get());
    }


    protected abstract void send_synack(final IpHeader ipHdr, final TcpPacket syn_skb);


    /* ************** ]] Initialize Connection Request ************ */

    /* **************** Open Connection Request [[ *************/

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L2179">tcp_v4_rcv‎</a> TCP_NEW_SYN_RECV
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c#L660">tcp_check_req</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L1742">tcp_v4_syn_recv_sock</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c#L518">tcp_create_openreq_child</a> <==
     */
    private TcpConnection tcp_check_req(final TcpPacket skb) {
        TcpConnection nsk = tcp_v4_syn_recv_sock(skb);
        return nsk;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L2179">tcp_v4_rcv‎</a> TCP_NEW_SYN_RECV
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c#L660">tcp_check_req</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L1742">tcp_v4_syn_recv_sock</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c#L518">tcp_create_openreq_child</a> <==
     */
    private TcpConnection tcp_v4_syn_recv_sock(final TcpPacket skb) {
        TcpConnection newsk = tcp_create_openreq_child(skb);
        icsk_ext_hdr_len = 0;
        output.tcp_sync_mss(this, dst_mtu());
        advmss = tcp_mss_clamp(dst_metric_advmss());
        tcp_initialize_rcv_mss();
        return newsk;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L2179">tcp_v4_rcv‎</a> TCP_NEW_SYN_RECV
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c#L660">tcp_check_req</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L1742">tcp_v4_syn_recv_sock</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c#L518">tcp_create_openreq_child</a> <==
     */
    private TcpConnection tcp_create_openreq_child(final TcpPacket skb) {
        /*-
         * 第一步调用 <code>inet_csk_clone_lock<code/> 基于原 TCP_NEW_SYN_RECV sock clone时会将状态设置为 TCP_SYN_RECV.
         * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/inet_connection_sock.c#L1247"></a>
         */
        inet_csk_clone_lock();

        rcv_isn = req_rcv_isn_ref.get();
        int _seq = req_rcv_isn_ref.get() + 1;
        rcv_wup = copied_seq = rcv_nxt = _seq;

        snt_isn = req_snt_isn_ref.get();
        _seq = req_snt_isn_ref.get() + 1;
        snd_sml = snd_una = snd_nxt = snd_up = _seq;

        // total_retrans = req->num_retrans;

        timer.tcp_init_xmit_timers();
        write_seq = pushed_seq = req_snt_isn_ref.get() + 1;

        window_clamp = req_rsk_window_clamp_ref.get();
        rcv_ssthresh = req_rsk_rcv_wnd_ref.get();
        rcv_wnd = req_rsk_rcv_wnd_ref.get();
        wscale_ok = ireq_wscale_ok_ref.get();
        if (wscale_ok) {
            snd_wscale = (byte) ireq_snd_wscale_ref.get();
            rcv_wscale = (byte) ireq_rcv_wscale_ref.get();
        } else {
            snd_wscale = 0;
            rcv_wscale = 0;
            window_clamp = Math.min(window_clamp, U16_MAX);
        }

        snd_wnd = skb.getHeader().getWindow() << snd_wscale;
        max_window = snd_wnd;

        boolean rx_opt_tstamp = false;
        if (rx_opt_tstamp) {
            tcp_usec_ts = 1;// req_use_ts;
            // ts_recent = req_ts_recent ;
            // ts_recent_stamp = ktime_get_seconds();
            tcp_header_len = SIZE_OF_TCP_HDR + TCPOLEN_TSTAMP_ALIGNED;
        } else {
            tcp_usec_ts = 0;
            // ts_recent_stamp = 0;
            tcp_header_len = SIZE_OF_TCP_HDR;
        }

        // ...
        mss_clamp = req_mss_ref.get();
        return this;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/inet_connection_sock.c#L1247">inet_csk_clone_lock</a>
     */
    private void inet_csk_clone_lock() {

    }

    private long mdev_us;
    private long mdev_max_us;
    private long rttvar_us;
    long srtt_us;
    private int rtt_seq;

    private int snd_ssthresh;
    private int snd_cwnd_clamp;

    // FIXME TODO tcp_init_sock https://github.com/torvalds/linux/blob/master/net/ipv4/tcp.c#L422
    // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L2492
    protected void tcp_init_sock() {
        // ...
        icsk_rto = TCP_TIMEOUT_INIT;
        icsk_delack_max = TcpTimer.TCP_DELACK_MAX;

        mdev_us = (int) TcpUtils.jiffies_to_usecs(TCP_TIMEOUT_INIT);

        tcp_snd_cwnd_set(TCP_INIT_CWND);

        /*-
         * See draft-stevens-tcpca-spec-01 for discussion of the
         * initialization of these values.
         */
        snd_ssthresh = TCP_INFINITE_SSTHRESH;
        // FIXME
//        snd_cwnd_clamp = ~0;
        snd_cwnd_clamp = Integer.MAX_VALUE;
        mss_cache = TCP_MSS_DEFAULT;

//        tsoffset= 0;

//        sk->sk_write_space = sk_stream_write_space;
//        sock_set_flag(sk, SOCK_USE_WRITE_QUEUE);

        // icsk->icsk_sync_mss = tcp_sync_mss;
//        WRITE_ONCE(sk->sk_sndbuf, READ_ONCE(sock_net(sk)->ipv4.sysctl_tcp_wmem[1]));
//        WRITE_ONCE(sk->sk_rcvbuf, READ_ONCE(sock_net(sk)->ipv4.sysctl_tcp_rmem[1]));
//        scaling_ratio = TCP_DEFAULT_SCALING_RATIO;
    }


    private void tcp_init_metrics() {
    }

    private void tcp_snd_cwnd_set(final int cwnd) {
        // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L1317
        snd_cwnd = cwnd;
    }

    private void tcp_init_congestion_control() {
    }


    /* **************** ]] Open Connection Request *************/

    static final int EPIPE = 32;
    static final int ECONNRESET = 104;
    static final int ETIMEOUT = 110;


    protected void destroy() {
        if (null != child && child.isOpen()) {
            child.close();
        }
        destroy0();
    }

    protected void destroy0() {

    }


    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L1478">tcp_init_wl</a>
     */
    private void tcp_init_wl(int seq) {
        snd_wl1 = seq;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L1483">tcp_update_wl</a>
     */
    void tcp_update_wl(int ack_seq) {
        snd_wl1 = ack_seq;
    }


    // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L86
    static final int TCP_MAX_QUICKACKS = 16;
    int icsk_ack_quick;


    /* *************** */
    /* *************** */
    /* *************** */

    // FIXME
    private int icsk_ack_last_seg_size;

    void tcp_measure_rcv_mss(TcpPacket skb) {
        // FIXME
        final int lss = icsk_ack_last_seg_size;

        icsk_ack_last_seg_size = 0;
        final int len = skb.length() - skb.getHeader().length();
        if (len >= icsk_ack_rcv_mss) {
            /*
            if (len != icsk_ack_rcv_mss) {
                len << TCP_RMEM_TO_WIN_SCALE‎;
            }
            */

            icsk_ack_rcv_mss = Math.min(len, advmss);
        }
    }


    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L622">tcp_initialize_rcv_mss</a>
     */
    private void tcp_initialize_rcv_mss() {
        int hint = Math.min(advmss, mss_cache);
        hint = Math.min(hint, rcv_wnd / 2);
        hint = Math.min(hint, TCP_MSS_DEFAULT);
        hint = Math.max(hint, TCP_MIN_MSS);
        icsk_ack_rcv_mss = hint;
    }

    private int rcv_rtt_est_rtt_us;

    private int rcv_rtt_est_seq;
    private int rcv_rtt_est_time;

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
    private void tcp_rcv_rtt_update(int sample, int win_dep) {
        int new_sample = rcv_rtt_est_rtt_us;
        int m = sample;

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

        rcv_rtt_est_rtt_us = new_sample;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L684">tcp_rcv_rtt_measure</a>
     */
    void tcp_rcv_rtt_measure() {
        if (rcv_rtt_est_time != 0) {
            if (TcpUtils.before(rcv_nxt, rcv_rtt_est_seq)) {
                return;
            }
            int delta_us = tcp_stamp_us_delta(tcp_mstamp, rcv_rtt_est_time);
            if (delta_us == 0) {
                delta_us = 1;
            }
            tcp_rcv_rtt_update(delta_us, 1);
        }

        rcv_rtt_est_seq = rcv_nxt + rcv_wnd;
        rcv_rtt_est_time = tcp_mstamp;
    }

    /**
     * 最后一次收到数据的时间.
     */
    long icsk_ack_lrcvtime;


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
            if (TcpUtils.after(snd_una, rtt_seq)) {
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
        logTrace("Compute a smoothed rtt: {}us", srtt_us >> 3);
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
        logTrace("Set retransmission timeout: {}ms", icsk_rto);
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L759">tcp_bound_rto</a>
     */
    private void tcp_bound_rto() {
        if (icsk_rto > TCP_RTO_MAX) {
            icsk_rto = TCP_RTO_MAX;
        }
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L765">__tcp_set_rto</a>
     */
    private long __tcp_set_rto() {
        return TcpUtils.usecs_to_jiffies((srtt_us >> 3) + rttvar_us);
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L1001">tcp_init_cwnd</a>
     */
    private int tcp_init_cwnd() {
        // __u32 cwnd = (dst ? dst_metric(dst, RTAX_INITCWND) : 0);
        int cwnd = 0;

        if (0 == cwnd) {
            cwnd = TCP_INIT_CWND;
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
        return TcpUtils.after(write_seq, pushed_seq + (max_window >> 1));
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
    private static final int TCP_ACTION_FIN = 1 << (State.TCP_CLOSE.ordinal());
    private static final int[] new_state = new int[16];

    {
//        new_state[0 /* (Invalid) */] = State.TCP_CLOSE.ordinal();
        new_state[State.TCP_ESTABLISHED.ordinal() + 1] = State.TCP_FIN_WAIT1.ordinal() | TCP_ACTION_FIN;
        new_state[State.TCP_SYN_SENT.ordinal() + 1] = State.TCP_CLOSE.ordinal();
        new_state[State.TCP_SYN_RECV.ordinal() + 1] = State.TCP_FIN_WAIT1.ordinal() | TCP_ACTION_FIN;
        new_state[State.TCP_FIN_WAIT1.ordinal() + 1] = State.TCP_FIN_WAIT1.ordinal();
        new_state[State.TCP_FIN_WAIT2.ordinal() + 1] = State.TCP_FIN_WAIT2.ordinal();
        new_state[State.TCP_TIME_WAIT.ordinal() + 1] = State.TCP_CLOSE.ordinal();
        new_state[State.TCP_CLOSE.ordinal() + 1] = State.TCP_CLOSE.ordinal();
        new_state[State.TCP_CLOSE_WAIT.ordinal() + 1] = State.TCP_LAST_ACK.ordinal() | TCP_ACTION_FIN;
        new_state[State.TCP_LAST_ACK.ordinal() + 1] = State.TCP_LAST_ACK.ordinal();
        new_state[State.TCP_LISTEN.ordinal() + 1] = State.TCP_CLOSE.ordinal();
        new_state[State.TCP_CLOSING.ordinal() + 1] = State.TCP_CLOSING.ordinal();
        new_state[State.TCP_NEW_SYN_RECV.ordinal() + 1] = State.TCP_CLOSE.ordinal(); /* should not happen ! */
    }

    // https://github.com/torvalds/linux/blob/master/include/net/sock.h#L1472
    static final int RCV_SHUTDOWN = 1;
    static final int SEND_SHUTDOWN = 2;
    private static final int SHUTDOWN_MASK = 2;

    /**
     * Shutdown the sending side of a connection. Much like close except
     * that we don't receive shut down or sock_set_flag(sk, SOCK_DEAD).
     */
    void shutdown(final int how) {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp.c#L2979
        if (0 == (how & SEND_SHUTDOWN)) {
            return;
        }
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

        state.set(State.values()[ns]);
        return 0 != (next & TCP_ACTION_FIN);
    }


    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp.c#L3240">tcp_close</a>
     */
    private void tcp_close() {
        __tcp_close();
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp.c#L3066">__tcp_close</a>
     */
    private void __tcp_close() {
        // FIXME
    }


    void tcp_push_pending_frames() {
        // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L2102
        output.__tcp_push_pending_frames(this, output.tcp_current_mss(this));
    }


    int sk_err_soft;

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L75">tcp_write_err</a>
     */
    void tcp_write_err() {
        log.warn("TCP WRITE ERROR");
        input.tcp_done_with_error(this, sk_err_soft != 0 ? sk_err_soft : ETIMEOUT);
    }


    boolean tcp_write_queue_empty() {
        return sk_write_queue.isEmpty();
    }


    private static final int TCP_MSS_DEFAULT = 536;
    private static final int TCP_MIN_MSS = 88;


    private long init_ts_off(TcpPacket skb) {
        // return TimeUnit.NANOSECONDS.toMicros(System.nanoTime());
        return 0;
    }

    private InetSocketAddress resolve(InetAddress dst, final int port) {
        /*-
         * FakeIP is only resolved through FakeDNS.
         */
        if (null != dnsEngine && dnsEngine.isFakeAddress(dst.getAddress())) {
            final String hostname = dnsEngine.getHostByAddress(dst.getAddress());
            log.info("[TCP] {} -> {}", dst, hostname);
            return null != hostname ? InetSocketAddress.createUnresolved(hostname, port) : null;
        }
        return new InetSocketAddress(dst, port);
    }

    // https://github.com/torvalds/linux/blob/master/include/linux/tcp.h#L597
    int tcp_mss_clamp(final int mss) {
        return user_mss > 0 && user_mss < mss ? user_mss : mss;
    }


    private static final int U8_MAX = 255;
    static final int U16_MAX = 65535;


    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L2021">tcp_send_head</a>
     */
    TcpBuffer tcp_send_head() {
        return sk_write_queue.peek();
    }


    void sk_data_ready() {

    }

    void consume(final TcpPacket skb) throws IOException {
        if (null != child && child.isOpen()) {
            final TcpHeader hdr = skb.getHeader();
            final byte[] bytes = skb.getPayload().getRawData();

            final int offset = rcv_nxt - hdr.getSequenceNumber();
            final int length = Math.min(output.tcp_receive_window(this), bytes.length - offset);
            child.writeAndFlush(Unpooled.wrappedBuffer(bytes, offset, length));
            logInfo("[TCP] Write to Upstream");
        }
    }


    /**
     *
     */
    void tcp_done() {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp.c#L4867
        state.set(State.TCP_CLOSE);
        timer.tcp_clear_xmit_timers();

        sk_shutdown = SHUTDOWN_MASK;


        destroy();
    }


    // No options.
    static final int SIZE_OF_TCP_HDR = 20;


    /*       MSS    */


    int tcp_bound_to_half_wnd(int pktsize) {
        // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L704
        int cutoff;
        if (max_window > TCP_MSS_DEFAULT) {
            cutoff = max_window >> 1;
        } else {
            cutoff = max_window;
        }
        if (cutoff > 0 && pktsize > cutoff) {
            return Math.max(cutoff, 68 - tcp_header_len);
        }
        return pktsize;
    }


    int dst_mtu() {
        return 1500;
    }


    int icsk_retransmits;


    void inet_csk_schedule_ack() {
        // https://github.com/torvalds/linux/blob/master/include/net/inet_connection_sock.h#L177
        // 确保在适当的时候发送 ACK，以确认接收到数据
        icsk_ack_pending |= TcpTimer.ICSK_ACK_SCHED;
    }

    boolean inet_csk_ack_scheduled() {
        // https://github.com/torvalds/linux/blob/master/include/net/inet_connection_sock.h
        return 0 != (icsk_ack_pending & TcpTimer.ICSK_ACK_SCHED);
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/inet_connection_sock.h#L329">inet_csk_enter_pingpong_mode</a>
     */
    void inet_csk_enter_pingpong_mode() {
        logTrace("[PING-PONG] enter PING-PONG mode: PING-PONG threshold = {}", sysctl_tcp_pingpong_thresh);
        icsk_ack_pingpong = sysctl_tcp_pingpong_thresh;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/inet_connection_sock.h#L335">inet_csk_exit_pingpong_mode</a>
     */
    void inet_csk_exit_pingpong_mode() {
        if (inet_csk_in_pingpong_model()) {
            logTrace("[PING-PONG] exit PING-PONG mode");
        }
        icsk_ack_pingpong = 0;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/inet_connection_sock.h#L340">inet_csk_in_pingpong_model</a>
     */
    boolean inet_csk_in_pingpong_model() {
        return icsk_ack_pingpong >= sysctl_tcp_pingpong_thresh;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/inet_connection_sock.h#L346">inet_csk_inc_pingpong_cnt</a>
     */
    void inet_csk_inc_pingpong_cnt() {
        if (icsk_ack_pingpong < U8_MAX) {
            logTrace("[PING-PONG] increment PING-PONG count: {} -> {}", icsk_ack_pingpong, icsk_ack_pingpong + 1);
            icsk_ack_pingpong++;
        }
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L357">tcp_dec_quickack_mode</a>
     */
    void tcp_dec_quickack_mode() {
        if (icsk_ack_quick != 0) {
            /* How many ACKs S/ACKing new data have we sent? */
            final int pkts = inet_csk_ack_scheduled() ? 1 : 0;

            if (pkts >= icsk_ack_quick) {
                logTrace("[QUICK-ACK] decrement QUICK-ARK count: {} -> {}", icsk_ack_quick, 0);
                icsk_ack_quick = 0;
                /* Leaving quickack mode we deflate ATO. */
                icsk_ack_ato = TCP_ATO_MIN;
            } else {
                logTrace("[QUICK-ACK] decrement QUICK-ARK count: {} -> {}", icsk_ack_quick, icsk_ack_quick - pkts);
                icsk_ack_quick -= pkts;
            }
        }
    }


    // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L2190
    void tcp_enter_loss() {

    }


    int tcp_skb_pcount(TcpBuffer skb) {
        return 1;
    }

    long tcp_skb_timestamp_ts(int usec_ts, TcpBuffer skb) {
        // FIXME
        long skb_mstamp_ns = 0; //skb.skb_mstamp_ns;
        if (usec_ts != 0) {
            // skb_mstamp_ns / NSEC_PER_USEC;
            return TimeUnit.NANOSECONDS.toMicros(skb_mstamp_ns);
        }
        // skb_mstamp_ns / NSEC_PER_MSEC
        return TimeUnit.NANOSECONDS.toMillis(skb_mstamp_ns);
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L905">tcp_skb_timestamp_us</a>
     * https://github.com/torvalds/linux/blob/v6.13/include/linux/skbuff.h#L867
     */
    int tcp_skb_timestamp_us(final TcpBuffer skb) {
        // skb_mstamp_ns <==> skb->tstamp
        // return div_u64(skb->skb_mstamp_ns, NSEC_PER_USEC);
        // FIXME
        return (int) (skb.tstamp / 1000);
    }

    // https://github.com/torvalds/linux/blob/v6.13/include/linux/skbuff.h#L4322
    void skb_set_delivery_time(TcpBuffer skb, long kt, String tstamp_type) {
        // FIXME
//        skb.tstamp = kt;
        skb.tstamp = kt;
    }

    // TSval values in usec (使用微妙还是毫秒)
    int tcp_usec_ts;
    long ts_off;

    /*-
     * most recent packet received/sent.
     * us (micro seconds).
     */
    int tcp_mstamp;

    /*-
     * timestamp of last received ACK (for keepalives).
     */
    long rcv_tstamp;
    long retrans_stamp;
    int retrans_out;
    long undo_retrans;

    long tcp_time_stamp_ts() {
        // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L873
        // ???
        if (tcp_usec_ts > 0) {
            return tcp_mstamp;
        }
        return tcp_time_stamp_ms();
    }

    long tcp_time_stamp_ms() {
        return TimeUnit.MICROSECONDS.toMillis(tcp_mstamp);
    }

    static final int TCP_ATO_MIN = HZ / 25;

    int icsk_pending;
    int icsk_ack_pending;
    long icsk_ack_ato = 0;
    int icsk_rto;

    // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L146
    static final int TCP_RTO_MAX = 120 * HZ;
    static final int TCP_RTO_MIN = HZ / 5;
    long icsk_timeout;


    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L2003">tcp_rtx_queue_head</a>
     */
    TcpBuffer tcp_rtx_queue_head() {
        return tcp_rtx_queue.peek();
    }

    /* *********** [[ ************** */

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L1286">tcp_left_out</a>
     */
    private int tcp_left_out() {
        // return tp->sacked_out + tp->lost_out;
        return 0;
    }

    /**
     * This determines how many packets are "in the network" to the best
     * of our knowledge.  In many cases it is conservative, but where
     * detailed information is available from the receiver (via SACK
     * blocks etc.) we can make more aggressive calculations.
     * <p>
     * Use this for decisions involving congestion control, use just
     * tp->packets_out to determine if the send queue is empty or not.
     * <p>
     * Read this equation as:
     * <p>
     * "Packets sent once on transmission queue" MINUS
     * "Packets left network, but not honestly ACKed yet" PLUS
     * "Packets fast retransmitted"
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L1305">tcp_packets_in_flight</a>
     */
    int tcp_packets_in_flight() {
        return packets_out - tcp_left_out() + retrans_out;
    }



    /* *********** ]] ************** */


    int RTAX_WINDOW = 1;
    int RTAX_INITRWND = 2;


    int dst_metric_advmss() {
        // https://github.com/torvalds/linux/blob/master/include/net/dst.h#L182
        return 1500 - IP_HEADER_SIZE - TCP_HEADER_SIZE;
    }

    int dst_metric(int metric) {
        return 0;
    }


    int tcp_stamp_us_delta(int t1, int t0) {
        return Math.max(t1 - t0, 0);
    }

    /* ****************** */
    /* ****************** */
    /* ****************** */
    /* ****************** */

    /**
     * Never offer a window over 32767 without using window scaling. Some
     * poor stacks do signed 16bit maths!
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L68">TCP_MAX_WINDOW</a>
     */
    static final int TCP_MAX_WINDOW = 32767;


    long tcp_clock_cache;
    long tcp_wstamp_ns;
    long lsndtime;
    int icsk_pmtu_cookie;




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
    boolean tcp_ack_update_rtt(int flag, int seq_rtt_us,
                               int sack_rtt_us, int ca_rtt_us/*,
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

    /* Restart timer after forward progress on connection.
     * RFC2988 recommends to restart timer to now+rto.
     */
    void tcp_rearm_rto() {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3261
        if (packets_out <= 0) {
            inet_csk_clear_xmit_timer(TcpTimer.ICSK_TIME_RETRANS);
        } else {
            long rto = icsk_rto;

            /* Offset the time elapsed after installing regular RTO */
            if (icsk_pending == TcpTimer.ICSK_TIME_REO_TIMEOUT
                    || icsk_pending == TcpTimer.ICSK_TIME_LOSS_PROBE) {
                long delta_us = tcp_rto_delta_us();
                /* delta_us may not be positive if the socket is locked
                 * when the retrans timer fires and is rescheduled.
                 */
                rto = TcpUtils.usecs_to_jiffies(Math.max(delta_us, 1));
            }
            tcp_reset_xmit_timer(TcpTimer.ICSK_TIME_RETRANS, rto, TCP_RTO_MAX);
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
    private void tcp_init_transfer(TcpPacket skb) {
        output.tcp_mtup_init();
        tcp_init_metrics();

        tcp_snd_cwnd_set(tcp_init_cwnd());

        snd_cwnd_stamp = TcpUtils.tcp_jiffies32();

        tcp_init_congestion_control();

        child.config().setAutoRead(true);
    }

    /**
     * @param skb
     * @return error code
     * @throws IOException
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L6743">tcp_rcv_state_process</a>
     */
    protected int tcp_rcv_state_process(final T ipPacket, final TcpPacket skb) throws IOException {
        final TcpHeader th = skb.getHeader();
        ipHeader = ipPacket.getHeader();
        tcpSrcPort = th.getSrcPort();
        tcpDstPort = th.getDstPort();

        /*-
         * 握手处理.
         */
        switch (state.get()) {
            case TCP_CLOSE:
                return discard(skb, SKB_DROP_REASON_TCP_CLOSE);
            case TCP_LISTEN:
                /*-
                 * LISTEN 不允许接收 ACK 包.
                 */
                if (th.getAck()) {
                    return SKB_DROP_REASON_NOT_SPECIFIED;
                }

                /*-
                 * RST 包应该被忽略.
                 */
                if (th.getRst()) {
                    return discard(skb, SKB_DROP_REASON_TCP_RESET);
                }

                /*-
                 * 第一次握手.
                 */
                if (th.getSyn()) {
                    /*-
                     * SYN-FIN 应该被忽略.
                     */
                    if (th.getFin()) {
                        return discard(skb, SKB_DROP_REASON_TCP_FLAGS);
                    }

                    /*-
                     * Linux此处为创建状态为TCP_NEW_SYN_RECV的请求套接字(request_sock)放入半连接队列即可结束,
                     * 此处调整为直接创建连接.
                     */
                    state.set(State.TCP_NEW_SYN_RECV);
                    final boolean accept = conn_request(ipPacket, skb);
                    if (!accept) {
                        return SKB_DROP_REASON_NO_SOCKET;
                    }

                    /*-
                     * 原本应该是收到第三次握手的ACK请求后，查找半连接队列, 如果查找到状态为 TCP_NEW_SYN_RECV 的请求套接字,
                     * 调用 tcp_check_req, 转换为 TCP_SYN_RECV 的子套接字, 并完成握手后迁移到全连接队列.
                     * 这里暂时没有采用父子关系, 上面直接建立了连接所以直接转换为 TCP_SYNC_RECV.
                     */
                    tcp_check_req(skb);

                    // FIXME 移动到连接打开时
//                    state.set(State.TCP_SYN_RECV);
                    return SKB_DROP_REASON_NOT_SPECIFIED;
                }

                /*-
                 * !ACK & !RST & !SYN.
                 */
                return discard(skb, SKB_DROP_REASON_TCP_FLAGS);
            case TCP_SYN_SENT:
                /*-
                 * XXX client mode not supported.
                 */
                return SKB_DROP_REASON_NOT_SPECIFIED;
            // 临时处理.
            case TCP_NEW_SYN_RECV:
                return discard(skb, SKB_DROP_REASON_NOT_SPECIFIED);
        }

        /*-
         * 刷新最近发送/接收时间戳.
         */
        output.tcp_mstamp_refresh(this);

        if (!th.getAck() && !th.getRst() && !th.getSyn()) {
            return discard(skb, SKB_DROP_REASON_TCP_FLAGS);
        }

        if (!input.tcp_validate_incoming(this, skb)) {
            return SKB_DROP_REASON_NOT_SPECIFIED;
        }

        if (State.TCP_SYN_RECV.equals(state.get())) {
//            tcp_check_req(skb);
        }

        /* step 5: check the ACK field */
        int reason = input.tcp_ack(this, skb, TcpInput.FLAG_SLOWPATH | TcpInput.FLAG_UPDATE_TS_RECENT | TcpInput.FLAG_NO_CHALLENGE_ACK);
        if (reason <= 0) {
            if (State.TCP_SYN_RECV.equals(state.get())) {
                return 0 == reason ? SKB_DROP_REASON_TCP_OLD_ACK : -reason;
            }
            if (reason < 0) {
                reason = -reason;
                return discard(skb, reason);
            }
            /* accept old ack during closing */
        }

        reason = SKB_DROP_REASON_NOT_SPECIFIED;
        switch (state.get()) {
            case TCP_SYN_RECV:
                tcp_init_transfer(skb);

                state.set(State.TCP_ESTABLISHED);

                snd_una = th.getAcknowledgmentNumber();
                snd_wnd = th.getWindowAsInt() << snd_wscale;
                tcp_init_wl(th.getSequenceNumber());

                // ...

                /* Prevent spurious tcp_cwnd_restart() on first data packet */
                lsndtime = TcpUtils.tcp_jiffies32();
                tcp_initialize_rcv_mss();

                break;
            case TCP_FIN_WAIT1:
                if (snd_una != write_seq) {
                    break;
                }
                state.set(State.TCP_FIN_WAIT2);
                sk_shutdown |= SEND_SHUTDOWN;

                int seq = th.getSequenceNumber();
                int end_seq = TcpUtils.determineEndSeq(skb);
                if (end_seq != seq && TcpUtils.after(end_seq - (th.getSyn() ? 1 : 0), rcv_nxt)) {
                    /* Receive out of order FIN after close() */
                    tcp_done();
                    return SKB_DROP_REASON_TCP_ABORT_ON_DATA;
                }

                // if fin 重置 keepalive timer
                // else 等待超时切换到 WAIT2 ??
                break;
            case TCP_CLOSING:
                if (snd_una == write_seq) {
                    //
                    state.set(State.TCP_TIME_WAIT);
                }
                break;
            case TCP_LAST_ACK:
                if (snd_una == write_seq) {
                    // tcp_update_metrics
                    tcp_done();
                    return SKB_DROP_REASON_NOT_SPECIFIED;
                }
                break;
        }

        /* step 6: check the URG bit */
        // tcp_urg(sk, skb, th);

        /* step 7: process the segment text */
        switch (state.get()) {
            case TCP_CLOSE_WAIT:
            case TCP_CLOSING:
            case TCP_LAST_ACK:
                if (!TcpUtils.before(th.getSequenceNumber(), rcv_nxt)) {
                    break;
                }
                // fallthrough
            case TCP_FIN_WAIT1:
            case TCP_FIN_WAIT2:
                // XXX
                // fallthrough
            case TCP_ESTABLISHED:
                input.tcp_data_queue(this, skb);
                break;
        }

        if (!State.TCP_CLOSE.equals(state.get())) {
            input.tcp_data_snd_check(this);
            input.tcp_ack_snd_check(this);
        }

        return SKB_DROP_REASON_NOT_SPECIFIED;
    }


    private int discard(final TcpPacket skb, final int reason) {
        return 0;
    }

    protected abstract void INDIRECT_CALL_INET(final TcpBuffer skb);


    int tcp_send_mss(final TcpConnection<T> tp) {
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

        final StringBuilder buff = new StringBuilder("[TCP] ");
        buff.append(srcHostAddr).append(":").append(srcPort)
                .append(" -> ")
                .append(dstHostAddr).append(":").append(dstPort);
        if (null != child) {
            buff.append(" [").append(child.id()).append("]");
        }
        buff.append(" ");

        buff.append(format);
        return buff.toString();
    }

    protected void debug(final IpHeader ipHeader, final TcpPacket tcpPacket, boolean inbound) {
//        if (true) {
//            return;
//        }
        final InetAddress srcAddr = ipHeader.getSrcAddr();
        final InetAddress dstAddr = ipHeader.getDstAddr();
        final TcpHeader tcpHeader = tcpPacket.getHeader();
        final String srcHostName = srcAddr.getHostAddress();
        final String dstHostName = dstAddr.getHostAddress();
        final int srcPort = tcpHeader.getSrcPort().valueAsInt();
        final int dstPort = tcpHeader.getDstPort().valueAsInt();

        /*
        String dstHostNameToUse = resolve(dstAddr);
        if (null != dstHostNameToUse) {
            dstHostNameToUse = dstHostName + "(" + dstHostNameToUse +  ")";
        } else {
            dstHostNameToUse = dstHostName;
        }
        */
        String dstHostNameToUse = dstHostName;

        final StringBuilder buff = new StringBuilder()
                .append(srcHostName).append(":").append(srcPort)
                .append(" => ")
                .append(dstHostNameToUse).append(":").append(dstPort);

        final int len = buff.length();
        if (tcpHeader.getFin()) {
            buff.append("FIN,");
        }
        if (tcpHeader.getSyn()) {
            buff.append("SYN,");
        }
        if (tcpHeader.getRst()) {
            buff.append("RST,");
        }
        if (tcpHeader.getPsh()) {
            buff.append("PSH,");
        }
        if (tcpHeader.getAck()) {
            buff.append("ACK,");
        }
        if (tcpHeader.getUrg()) {
            buff.append("URG,");
        }

        if (buff.length() > len) {
            buff.replace(buff.length() - 1, buff.length(), "] ").insert(len, " [");
        }

        final boolean useRelative = true;
        long sequence = tcpHeader.getSequenceNumberAsLong();
        long acknowledgment = tcpHeader.getAcknowledgmentNumberAsLong();
        if (useRelative) {
            final long rcv_isn_l = rcv_isn & 0xFFFFFFFFL;
            final long snt_isn_l = snt_isn & 0xFFFFFFFFL;
            final boolean syn = tcpHeader.getSyn();
            sequence -= !syn ? rcv_isn_l : sequence;
            acknowledgment -= !syn ? snt_isn_l : acknowledgment - 1;
        }

        buff.append("Seq=").append(sequence);
        if (tcpHeader.getAck()) {
            buff.append(" Ack=").append(acknowledgment);
        }

        final int window = tcpHeader.getWindowAsInt() << (inbound ? rcv_wscale : snd_wscale);
        buff.append(" Win=").append(window);

        final int payloadLen = tcpPacket.length() - tcpHeader.length();
        buff.append(" Len=").append(payloadLen);

        if (tcpHeader.getSyn()) {

        }

        /*
        final Packet payload = tcpPacket.getPayload();
        if (null != payload) {
            buff.append(" ").append(Bytes.toString(payload.getRawData()));
        }
        */

        log.debug(buff.toString());
    }

    static final int sysctl_tcp_retries2 = 5;
    // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L160
    static final long TCP_RESOURCE_PROBE_INTERVAL = HZ / 2;

    /*
    RFC6298 2.1 initial RTO value.
    https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L152
    */
    static final int TCP_TIMEOUT_INIT = 1 * HZ;
    static final int TCP_TIMEOUT_MIN = 2;

    int icsk_probes_out;
    long icsk_probes_tstamp;
    int icsk_user_timeout;
    int icsk_backoff;
    int icsk_ack_retry;
    /**
     * ACK 超时时间(offset).
     * <p>
     * //     * @see #tcp_event_data_recv(TcpPacket)
     *
     * @see TcpOutput#tcp_event_data_sent
     */
    long icsk_ack_timeout;
    int icsk_rto_min = TCP_RTO_MIN;//40;
    int icsk_delack_max;

    static final int sysctl_tcp_pingpong_thresh = 1;
    int icsk_ack_pingpong;

    long tcp_rto_min_us() {
        return TcpUtils.jiffies_to_usecs(tcp_rto_min());
    }

    int tcp_rto_min() {
        // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L783
        return icsk_rto_min;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L1444">tcp_probe0_when</a>
     */
    long tcp_probe0_when(int max_when) {
        // u8 backoff = ilog2(TCP_RTO_MAX / TCP_RTO_MIN) + 1
        int backoff = (int) (Math.log(TCP_RTO_MAX / TCP_RTO_MIN) / Math.log(2)) + 1;
        backoff = Math.min(backoff, icsk_backoff);

        final long when = tcp_probe0_base() << backoff;
        return Math.min(when, max_when);
    }

    long tcp_probe0_base() {
        // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L1438
        return Math.max(icsk_rto, TCP_RTO_MIN);
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L49">tcp_clamp_probe0_to_user_timeout</a>
     */
    long tcp_clamp_probe0_to_user_timeout(long when) {
        int user_timeout = icsk_user_timeout;
        if (0 == user_timeout || 0 == icsk_probes_tstamp) {
            return when;
        }
        long elapsed = TcpUtils.tcp_jiffies32() - icsk_probes_tstamp;
        if (elapsed < 0) {
            elapsed = 0;
        }
        long remaining = user_timeout - elapsed;
        remaining = Math.max(remaining, TCP_TIMEOUT_MIN);
        return Math.min(remaining, when);
    }

    void tcp_reset_xmit_timer(final int what, long when, long max_when) {
        // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L1423
        long tcp_pacing_delay = 0; // FIXME
        timer.inet_csk_reset_xmit_timer(what, when + tcp_pacing_delay, max_when);
    }

    void inet_csk_clear_xmit_timer(int what) {
        // https://github.com/torvalds/linux/blob/master/include/net/inet_connection_sock.h#L195
        if (TcpTimer.ICSK_TIME_RETRANS == what || TcpTimer.ICSK_TIME_PROBE0 == what) {
            icsk_pending = 0;
            // stop icsk_retransmit_timer
            timer.sk_stop_timer(timer.icsk_retransmit_timer);
        }
        if (TcpTimer.ICSK_TIME_DACK == what) {
            icsk_ack_pending = 0;
            icsk_ack_retry = 0;
            timer.sk_stop_timer(timer.icsk_delack_timer);
        }
    }

    // FIXME
    // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp.c#L422
    int total_rto_recoveries;
    long rto_stamp;
    int total_rto;


    // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L2448
    private long tcp_rto_delta_us() {
        return TCP_RTO_MAX;
    }

}