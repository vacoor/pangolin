package com.github.pangolin.routing.server.tun.net;

import com.github.pangolin.routing.handler.internal.server.support.SocketChannelFactory;
import com.github.pangolin.routing.server.fakedns.DnsEngine;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;
import org.pcap4j.packet.IpPacket;
import org.pcap4j.packet.IpPacket.IpHeader;
import org.pcap4j.packet.IpV4Packet;
import org.pcap4j.packet.Packet;
import org.pcap4j.packet.TcpMaximumSegmentSizeOption;
import org.pcap4j.packet.TcpNoOperationOption;
import org.pcap4j.packet.TcpPacket;
import org.pcap4j.packet.TcpPacket.TcpHeader;
import org.pcap4j.packet.TcpPacket.TcpOption;
import org.pcap4j.packet.TcpWindowScaleOption;
import org.pcap4j.packet.UnknownPacket;
import org.pcap4j.packet.namednumber.IpNumber;
import org.pcap4j.packet.namednumber.IpVersion;
import org.pcap4j.packet.namednumber.TcpPort;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public abstract class TcpConnection<P extends IpPacket> extends InetConnectionSock {
    private static final byte FIN = 0x0001;
    private static final byte SYN = 0x0002;
    private static final byte RST = 0x0004;
    private static final byte PSH = 0x0008;
    private static final byte ACK = 0x0010;
    private static final byte URG = 0x0020;

    private static final int DEFAULT_MTU = 1500;
    private static final int MINIMUM_MTU = 576;

    private static final short IP_HEADER_SIZE = 20;
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
     * Incoming frame contained data.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L87">FLAG_DATA</a>
     */
    private static final int FLAG_DATA = 0x01;

    /**
     * Incoming ACK was a window update.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L87">FLAG_WIN_UPDATE</a>
     */
    private static final int FLAG_WIN_UPDATE = 0x02;

    /* "" "" some of which was retransmitted.	*/
    private static final int FLAG_RETRANS_DATA_ACKED = 0x08;

    /**
     * Do not skip RFC checks for window update.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L97">FLAG_SLOWPATH</a>
     */
    private static final int FLAG_SLOWPATH = 0x100;

    /**
     * Snd_una was changed (!= FLAG_DATA_ACKED).
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L97">FLAG_SND_UNA_ADVANCED</a>
     */
    private static final int FLAG_SND_UNA_ADVANCED = 0x400;

    private static final int FLAG_UPDATE_TS_RECENT = 0x4000;

    private static final int FLAG_NO_CHALLENGE_ACK = 0x8000;

    // FIXME
    private static final int CA_ACK_WIN_UPDATE = FLAG_WIN_UPDATE;
    private static final int CA_ACK_SLOWPATH = FLAG_SLOWPATH;

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


    private IpHeader ipHeader;
    private TcpPort tcpSrcPort;
    private TcpPort tcpDstPort;

    private final AtomicReference<State> state = new AtomicReference<>(State.TCP_CLOSE);


    // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L243
    /* TCP initial congestion window as per rfc6928 */
    private static final int TCP_INIT_CWND = 10;
    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L1327">TCP_INFINITE_SSTHRESH</a>
     */
    private static final int TCP_INFINITE_SSTHRESH = 0x7fffffff;

    private static final byte TCP_MAX_WSCALE = 14;

    private static final int HZ = 1000;
    private static final boolean sysctl_tcp_window_scaling = true;
    private static final int sysctl_tcp_retries1 = 5;
    private static final int sysctl_tcp_retries2 = 5;

    /**
     * 用户定义的 MSS.
     */
    private int user_mss;

    /**
     * 对端通告 MSS.
     */
    private int mss_clamp;

    /**
     * 本端能接收的最大MSS, 通告对端的MSS.
     */
    private int advmss;

    /**
     * 缓存发送方当前有效的MSS, 根据pmtu变动.
     */
    private int mss_cache;
    /**
     * 由最近接收到的段估算的对端mss，主要用来确定是否执行延迟确认.
     */
    private int rcv_mss;

    private boolean wscale_ok;

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

    private int rcv_isn;
    private int rcv_wup;
    private int rcv_nxt;
    private int rcv_wnd;

    private int copied_seq;

    private byte rcv_wscale = 6;

    private int icsk_ack_rcv_mss;


    private int bytes_acked;
    private long bytes_received;
    private int bytes_sent;
    private int data_segs_out;
    private int segs_out;


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

    private int snt_isn;
    private int snd_una;
    private int snd_wnd;
    private int snd_nxt;
    /**
     * Urgent pointer.
     *
     * @deprecated
     */
    @Deprecated
    private int snd_up;
    private int snd_sml;

    private byte snd_wscale;

    /**
     * 最大窗口.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/linux/tcp.h#L192">tcp_sock</a>
     */
    private int max_window;

    private int snd_cwnd;
    private long snd_cwnd_stamp;


    /**
     * 触发窗口更新的序号.
     * Sequence for window update.
     */
    private int snd_wl1;

    /**
     * 下一个写入发送队列的序号.
     */
    private int write_seq;

    /**
     * Last pushed seq, required to talk to windows.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/linux/tcp.h#L264">pushed_seq</a>
     */
    private int pushed_seq;

    /**
     * 已发出待接收ACK的数据包数量.
     */
    private int packets_out;

    private ConcurrentLinkedQueue<TcpBuffer> sk_write_queue = new ConcurrentLinkedQueue<>();
    private ConcurrentLinkedQueue<TcpBuffer> tcp_rtx_queue = new ConcurrentLinkedQueue<>();

    private ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, new ThreadFactory() {
        @Override
        public Thread newThread(final Runnable r) {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        }
    });


    protected final Channel parent;
    private final DnsEngine dnsEngine;
    private final EventLoopGroup childGroup;
    private final SocketChannelFactory socketChannelFactory;

    private volatile Channel child;
    private int connTimeoutMs = 10 * 1000;

    protected TcpConnection(final Channel parent, final EventLoopGroup childGroup, final DnsEngine dnsEngine, final SocketChannelFactory socketChannelFactory) {
        this.parent = parent;
        this.childGroup = childGroup;
        this.dnsEngine = dnsEngine;
        this.socketChannelFactory = socketChannelFactory;
        tcp_v4_init_sock();
        this.listen();
    }

    private void listen() {
        inet_listen(100);
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
        if (0 == ((1 << st) & (TCPF_CLOSE | TCPF_LISTEN))) {
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

    public abstract void handler(final P ipHeader, final TcpPacket tcpPacket);

    /**
     * 接收窗口可用大小.
     * Compute the actual receive window we are currently advertising.
     * Rcv_nxt can be after the window if our peer push more data
     * than the offered window.
     *
     * @return the actual receive window
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L813">tcp_receive_window</a>
     */
    private int tcp_receive_window() {
        return Math.max(rcv_wup + rcv_wnd - rcv_nxt, 0);
    }

    /**
     * 发送可用/拥塞窗口大小.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L1312">tcp_snd_cwnd</a>
     */
    private int tcp_snd_cwnd() {
        // FIXME
        return snd_cwnd;
    }

    /**
     * 发送窗口右边界.
     */
    private int tcp_wnd_end() {
        return snd_una + snd_wnd;
    }

    private int sk_shutdown;


    // https://www.cnblogs.com/wanpengcoder/p/11751763.html


    /**
     * https://github.com/torvalds/linux/blob/master/include/net/dropreason-core.h.
     */
    public static final int SKB_NOT_DROPPED_YET = 0;

    private int tcp_header_len;
    private int icsk_ext_hdr_len;
    private int TCPOLEN_TSTAMP_ALIGNED = 12;

    private int window_clamp;
    private int rcv_ssthresh;

    /* ************** Initialize Connection Request [[ ************ */

    private AtomicInteger tmp_opt_rx_user_mss = new AtomicInteger();
    private AtomicInteger tmp_opt_rx_mss_clamp = new AtomicInteger();
    private AtomicBoolean tmp_opt_wscale_ok = new AtomicBoolean();
    private AtomicInteger tmp_opt_snd_wscale = new AtomicInteger();

    private final AtomicInteger req_snt_isn_ref = new AtomicInteger();
    private final AtomicInteger req_rcv_isn_ref = new AtomicInteger();
    private final AtomicInteger req_rcv_nxt_ref = new AtomicInteger();
    private final AtomicInteger req_mss_ref = new AtomicInteger();
    private final AtomicInteger req_rsk_rcv_wnd_ref = new AtomicInteger();
    private final AtomicInteger req_rsk_window_clamp_ref = new AtomicInteger();

    private final AtomicBoolean ireq_wscale_ok_ref = new AtomicBoolean();
    private final AtomicInteger ireq_snd_wscale_ref = new AtomicInteger();
    private final AtomicInteger ireq_rcv_wscale_ref = new AtomicInteger();

    protected boolean conn_request(final IpHeader ih, final TcpPacket skb) {
        return tcp_conn_request(ih, skb);
    }

    /**
     * @param skb
     * @return
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L7195">tcp_conn_request</a>
     */
    private boolean tcp_conn_request(final IpHeader ipHdr, final TcpPacket skb) {
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

        tcp_parse_options(skb, false);
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
            return false;
        }
        log.info("{} Connecting...", resolved);
        final ChannelFuture cf = socketChannelFactory.open(resolved, connTimeoutMs, true, childGroup, new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
                try {
                    final ByteBuf buf = (ByteBuf) msg;
                    final byte[] payload = ByteBufUtil.getBytes(buf);

                    // tcp data len = tcp snd.mss - tcp options.len
                    // 超过 tcp data len 不切割, 会使用TSO功能通过网卡来分段.
                    final int dataMaxLen = tcp_current_mss();
                    for (int i = 0; i < payload.length - 1; i += dataMaxLen) {
                        final int maxEndIndex = i + dataMaxLen;
                        if (maxEndIndex >= payload.length) {
                            final UnknownPacket.Builder builder = UnknownPacket.newPacket(payload, i, payload.length - i).getBuilder();
                            tcp_sendmsg2(new TcpBuffer().ack(true).psh(true).payloadBuilder(builder), true);
                        } else {
                            UnknownPacket.Builder builder = UnknownPacket.newPacket(payload, i, dataMaxLen).getBuilder();
                            tcp_sendmsg2(new TcpBuffer().ack(true).psh(true).payloadBuilder(builder), false);
                        }
                    }
                } finally {
                    ReferenceCountUtil.release(msg);
                }
            }
        });

        try {
            child = cf.sync().channel();
            log.info("{} Connected.", resolved);
            child.closeFuture().addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(final ChannelFuture future) throws Exception {
                    if (future.isSuccess()) {
                        log.info("{} Disconnect.", resolved);
                        shutdown(SEND_SHUTDOWN);
                    } else {
                        log.warn("{} Connect error", resolved, future.cause());
                    }
                }
            });
            return true;
        } catch (InterruptedException e) {
            log.info("{} Connection reset.", resolved, e.getMessage(), e);
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
        int full_space = tcp_full_space();
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
        tcp_select_initial_window(
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
        tcp_sync_mss(dst_mtu());
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

        tcp_init_xmit_timers();
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

    private void tcp_v4_init_sock() {
        tcp_init_sock();
    }

    private long mdev_us;
    private long mdev_max_us;
    private long rttvar_us;
    private long srtt_us;
    private int rtt_seq;

    private int snd_ssthresh;
    private int snd_cwnd_clamp;

    // FIXME TODO tcp_init_sock https://github.com/torvalds/linux/blob/master/net/ipv4/tcp.c#L422
    // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L2492
    private void tcp_init_sock() {
        // ...
        icsk_rto = TCP_TIMEOUT_INIT;
        icsk_delack_max = TCP_DELACK_MAX;

        mdev_us = (int) jiffies_to_usecs(TCP_TIMEOUT_INIT);

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

    private static final int EPIPE = 32;
    private static final int ECONNRESET = 104;
    private static final int ETIMEOUT = 110;


    private void destroy() {
        if (child.isOpen()) {
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
    private void tcp_update_wl(int ack_seq) {
        snd_wl1 = ack_seq;
    }


    // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L86
    private static final int TCP_MAX_QUICKACKS = 16;
    private int icsk_ack_quick;


    /* *************** */
    /* *************** */
    /* *************** */

    // FIXME
    private int icsk_ack_last_seg_size;

    private void tcp_measure_rcv_mss(TcpPacket skb) {
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
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L357">tcp_dec_quickack_mode</a>
     */
    private void tcp_dec_quickack_mode() {
        if (icsk_ack_quick != 0) {
            /* How many ACKs S/ACKing new data have we sent? */
            final int pkts = inet_csk_ack_scheduled() ? 1 : 0;

            if (pkts >= icsk_ack_quick) {
                icsk_ack_quick = 0;
                /* Leaving quickack mode we deflate ATO. */
                icsk_ack_ato = TCP_ATO_MIN;
            } else
                icsk_ack_quick -= pkts;
        }
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L300">tcp_incr_quickack</a>
     */
    private void tcp_incr_quickack(int max_quickacks) {
        int quickacks = rcv_wnd / (2 * icsk_ack_rcv_mss);
        if (0 == quickacks) {
            quickacks = 2;
        }
        quickacks = Math.min(quickacks, max_quickacks);
        if (quickacks > icsk_ack_quick) {
            icsk_ack_quick = quickacks;
        }
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L318">tcp_enter_quickack_mode</a>
     */
    private void tcp_enter_quickack_mode(int max_quickacks) {
        tcp_incr_quickack(max_quickacks);
        inet_csk_exit_pingpong_mode();
        icsk_ack_ato = TCP_ATO_MIN;
    }

    /*-
     * Send ACKs quickly, if "quick" count is not exhausted
     * and the session is not interactive.
     */

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L318">tcp_in_quickack_mode</a>
     */
    private boolean tcp_in_quickack_mode() {
        //const struct dst_entry *dst = __sk_dst_get(sk);

        /*
        return (dst && dst_metric(dst, RTAX_QUICKACK)) ||
                (icsk->icsk_ack.quick && !inet_csk_in_pingpong_mode(sk));
                */
        return icsk_ack_quick > 0 && !inet_csk_in_pingpong_model();
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
                if (m < new_sample)
                    new_sample = m;
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
    private void tcp_rcv_rtt_measure() {
        if (rcv_rtt_est_time != 0) {
            if (before(rcv_nxt, rcv_rtt_est_seq)) {
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
    private long icsk_ack_lrcvtime;

    private void tcp_event_data_recv(final TcpPacket skb) throws IOException {
        inet_csk_schedule_ack();

        tcp_measure_rcv_mss(skb);
        tcp_rcv_rtt_measure();

        long now = tcp_jiffies32();
        if (0 == icsk_ack_ato) {
            /*
             * The _first_ data packet received, initialize
             * delayed ACK engine.
             */
            tcp_incr_quickack(TCP_MAX_QUICKACKS);
            icsk_ack_ato = TCP_ATO_MIN;
        } else {
            long m = now - icsk_ack_lrcvtime;
            if (m <= TCP_ATO_MIN / 2) {
                /* The fastest case is the first. */
                icsk_ack_ato = (icsk_ack_ato >> 1) + TCP_ATO_MIN / 2;
            } else if (m < icsk_ack_ato) {
                icsk_ack_ato = (icsk_ack_ato >> 1) + m;
                if (icsk_ack_ato > icsk_rto) {
                    icsk_ack_ato = icsk_rto;
                }
            } else if (m > icsk_ack_ato) {
                /*-
                 * Too long gap. Apparently sender failed to
                 * restart window, so that we send ACKs quickly.
                 */
                tcp_incr_quickack(TCP_MAX_QUICKACKS);
            }
        }
        icsk_ack_lrcvtime = now;

        // ...
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
                if (m > 0)
                    m >>= 3;
            } else {
                m -= (mdev_us >> 2);   /* similar update on mdev */
            }
            mdev_us += m;        /* mdev = 3/4 mdev + 1/4 new */
            if (mdev_us > mdev_max_us) {
                mdev_max_us = mdev_us;
                if (mdev_max_us > rttvar_us)
                    rttvar_us = mdev_max_us;
            }
            if (after(snd_una, rtt_seq)) {
                if (mdev_max_us < rttvar_us)
                    rttvar_us -= (rttvar_us - mdev_max_us) >> 2;
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
        log.info("SRTT = {}", srtt_us);
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
        log.info("ICSK_RTO = {}", icsk_rto);
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
        return usecs_to_jiffies((srtt_us >> 3) + rttvar_us);
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
    private static final int RCV_SHUTDOWN = 1;
    private static final int SEND_SHUTDOWN = 2;
    private static final int SHUTDOWN_MASK = 2;

    private static final int TCPF_ESTABLISHED = 1 << State.TCP_ESTABLISHED.ordinal();
    private static final int TCPF_CLOSE_WAIT = 1 << State.TCP_CLOSE_WAIT.ordinal();
    private static final int TCPF_CLOSE = 1 << State.TCP_CLOSE.ordinal();
    private static final int TCPF_LISTEN = 1 << State.TCP_LISTEN.ordinal();
    private static final int TCPF_SYN_SENT = 1 << State.TCP_SYN_SENT.ordinal();
    private static final int TCPF_SYN_RECV = 1 << State.TCP_SYN_RECV.ordinal();
    private static final int TCPF_LAST_ACK = 1 << State.TCP_LAST_ACK.ordinal();
    private static final int TCPF_CLOSING = 1 << State.TCP_CLOSING.ordinal();

    /**
     * Shutdown the sending side of a connection. Much like close except
     * that we don't receive shut down or sock_set_flag(sk, SOCK_DEAD).
     */
    private void shutdown(final int how) {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp.c#L2979
        if (0 == (how & SEND_SHUTDOWN)) {
            return;
        }
        if (0 != ((1 << state.get().ordinal()) & (TCPF_ESTABLISHED | TCPF_CLOSE_WAIT))) {
            /* Clear out any half completed packets.  FIN if needed. */
            if (tcp_close_state()) {
                tcp_send_fin();
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


    private void tcp_push_pending_frames() {
        // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L2102
        __tcp_push_pending_frames(tcp_current_mss());
    }


    private int sk_err_soft;

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L75">tcp_write_err</a>
     */
    private void tcp_write_err() {
        log.warn("TCP WRITE ERROR");
        tcp_done_with_error(sk_err_soft != 0 ? sk_err_soft : ETIMEOUT);
    }


    private boolean tcp_write_queue_empty() {
        return sk_write_queue.isEmpty();
    }


    private static final int TCP_MSS_DEFAULT = 536;
    private static final int TCP_MIN_MSS = 88;


    private long init_ts_off(TcpPacket skb) {
        // return TimeUnit.NANOSECONDS.toMicros(System.nanoTime());
        return 0;
    }

    private InetSocketAddress resolve(InetAddress dst, final int port) {
        String host = resolve(dst);
        if (null != host) {
            return InetSocketAddress.createUnresolved(host, port);
        }
        return null;
    }

    private String resolve(InetAddress dst) {
        if (null != dnsEngine) {
            final String host = dnsEngine.resolve(dst.getAddress());
            if (null != host) {
                return host;
            } else if (dnsEngine.isFake(dst.getAddress())) {
                return null;
            }
        }
        return dst.getHostAddress();
    }

    // https://github.com/torvalds/linux/blob/master/include/linux/tcp.h#L597
    int tcp_mss_clamp(final int mss) {
        return user_mss > 0 && user_mss < mss ? user_mss : mss;
    }


    private static final int U8_MAX = 255;
    private static final int U16_MAX = 65535;


    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L2021">tcp_send_head</a>
     */
    private TcpBuffer tcp_send_head() {
        return sk_write_queue.peek();
    }


    void tcp_data_queue_ofo(final TcpPacket skb) {

    }


    void sk_data_ready() {

    }

    private void consume(final TcpPacket skb) throws IOException {
        if (null != child && child.isOpen()) {
            final TcpHeader hdr = skb.getHeader();
            final byte[] bytes = skb.getPayload().getRawData();

            final int offset = rcv_nxt - hdr.getSequenceNumber();
            final int length = Math.min(tcp_receive_window(), bytes.length - offset);
            child.writeAndFlush(Unpooled.wrappedBuffer(bytes, offset, length));
        }
    }

    /**
     * @param skb
     * @return
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c">tcp_ipv4.c</a>
     */
    protected int determineEndSeq(final TcpPacket skb) {
        final TcpHeader hdr = skb.getHeader();
        int endSeq = hdr.getSequenceNumber();
        if (hdr.getSyn()) {
            endSeq++;
        }
        if (hdr.getFin()) {
            endSeq++;
        }
        return endSeq + skb.length() - hdr.length();
    }

    protected int determineEndSeq(final TcpBuffer skb) {
        int endSeq = skb.sequenceNumber();
        if (skb.syn()) {
            endSeq++;
        }
        if (skb.fin()) {
            endSeq++;
        }
        final Packet.Builder b = skb.payloadBuilder();
        final int len = null != b ? b.build().length() : 0;
        return endSeq + len;
    }

    private boolean before(final int seq1, final int seq2) {
        /*-
         * eg: Integer.MAX_VALUE - (Integer.MAX_VALUE + 1) => -1 < 0
         */
        return seq1 - seq2 < 0;
    }

    private boolean after(final int seq2, final int seq1) {
        return before(seq1, seq2);
    }

    private boolean between(final int seq1, final int seq2, final int seq3) {
        return seq3 - seq2 >= seq1 - seq2;
    }

    /**
     *
     */
    private void tcp_done() {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp.c#L4867
        state.set(State.TCP_CLOSE);
        tcp_clear_xmit_timers();

        sk_shutdown = SHUTDOWN_MASK;


        destroy();
    }


    // No options.
    private static final int SIZE_OF_TCP_HDR = 20;


    /*       MSS    */


    private int tcp_bound_to_half_wnd(int pktsize) {
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


    private int dst_mtu() {
        return 1500;
    }


    private int icsk_retransmits;


    /**
     * https://github.com/torvalds/linux/blob/master/include/net/inet_connection_sock.h#L144
     */
    /* Retransmit timer */
    private static final int ICSK_TIME_RETRANS = 1;
    /* Delayed ack timer */
    private static final int ICSK_TIME_DACK = 2;
    /* Zero window probe timer */
    private static final int ICSK_TIME_PROBE0 = 3;
    /* Tail loss probe timer */
    private static final int ICSK_TIME_LOSS_PROBE = 5;
    /* Reordering timer */
    private static final int ICSK_TIME_REO_TIMEOUT = 6;

    private Runnable icsk_retransmit_timer;
    private Runnable icsk_delack_timer;
    private Runnable sk_timer;

    private void tcp_init_xmit_timers() {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L883
        inet_csk_init_xmit_timers(
                this::tcp_write_timer,
                this::tcp_delack_timer,
                this::tcp_keepalive_timer
        );
    }

    // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L702
    private void tcp_clear_xmit_timers() {
        inet_csk_clear_xmit_timers();
    }

    private void inet_csk_init_xmit_timers(Runnable icsk_retransmit_handler,
                                           Runnable icsk_delack_handler,
                                           Runnable keepalive_handler) {
        icsk_retransmit_timer = icsk_retransmit_handler;
        icsk_delack_timer = icsk_delack_handler;
        sk_timer = keepalive_handler;
        icsk_pending = icsk_ack_pending = 0;
    }

    // https://github.com/torvalds/linux/blob/master/net/ipv4/inet_connection_sock.c#L785
    private void inet_csk_clear_xmit_timers() {
        icsk_pending = 0;
        icsk_ack_pending = 0;

        sk_stop_timer(icsk_retransmit_timer);
        sk_stop_timer(icsk_delack_timer);
        sk_stop_timer(sk_timer);
    }

    private void tcp_reset_xmit_timer(final int what, long when, long max_when) {
        // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L1423
        long tcp_pacing_delay = 0; // FIXME
        inet_csk_reset_xmit_timer(what, when + tcp_pacing_delay, max_when);
    }

    private void inet_csk_clear_xmit_timer(int what) {
        // https://github.com/torvalds/linux/blob/master/include/net/inet_connection_sock.h#L195
        if (ICSK_TIME_RETRANS == what || ICSK_TIME_PROBE0 == what) {
            icsk_pending = 0;
            // stop icsk_retransmit_timer
            sk_stop_timer(icsk_retransmit_timer);
        }
        if (ICSK_TIME_DACK == what) {
            icsk_ack_pending = 0;
            icsk_ack_retry = 0;
            sk_stop_timer(icsk_delack_timer);
        }
    }

    // https://github.com/torvalds/linux/blob/master/include/net/inet_connection_sock.h#L218
    private void inet_csk_reset_xmit_timer(final int what, long when, long max_when) {
        if (when > max_when) {
            when = max_when;
        }
        if (what == ICSK_TIME_RETRANS || what == ICSK_TIME_PROBE0 ||
                what == ICSK_TIME_LOSS_PROBE || what == ICSK_TIME_REO_TIMEOUT) {
            icsk_pending = what;
            icsk_timeout = jiffies() + when;

            sk_reset_timer(icsk_retransmit_timer, icsk_timeout);
        } else if (what == ICSK_TIME_DACK) {
            icsk_ack_pending |= ICSK_ACK_TIMER;
            icsk_ack_timeout = jiffies() + when;
            sk_reset_timer(icsk_delack_timer, icsk_ack_timeout);
        }
    }


    private void sk_stop_timer(Runnable timer) {
        Future<?> future = timers.remove(timer);
        if (null != future && !future.isDone() && !future.isCancelled()) {
            future.cancel(false);
        }
    }

    private void sk_reset_timer(Runnable timer, long expires) {
        // https://github.com/torvalds/linux/blob/master/net/core/sock.c#L3539
        mod_timer(timer, expires);
    }

    private int mod_timer(Runnable timer, long expires) {
        // https://github.com/torvalds/linux/blob/master/kernel/time/timer.c#L1235
        return __mod_timer(timer, expires, 0);
    }

    private final ConcurrentMap<Runnable, Future<?>> timers = Maps.newConcurrentMap();

    private int __mod_timer(Runnable timer, long expires, int options) {
        final ScheduledFuture<?> nf = scheduler.schedule(timer, expires - jiffies(), TimeUnit.MILLISECONDS);
        Future<?> future = timers.put(timer, nf);
        if (null != future && !future.isDone() && !future.isCancelled()) {
            future.cancel(false);
        }
        return 0;
    }

    /* *********** DELAY ACK [[ ************** */

    private static final int TCP_DELACK_MIN = HZ / 25;
    private static final int TCP_DELACK_MAX = HZ / 5;

    /**
     * <a href="https://github.com/torvalds/linux/blob/master/include/net/inet_connection_sock.h#L161">inet_csk_ack_state_t</a>
     */
    private static final int ICSK_ACK_SCHED = 1;
    private static final int ICSK_ACK_TIMER = 1 << 1;
    private static final int ICSK_ACK_PUSHED = 1 << 2;
    private static final int ICSK_ACK_PUSHED2 = 1 << 3;
    private static final int ICSK_ACK_NOW = 1 << 4;
    private static final int ICSK_ACK_NOMEM = 1 << 5;
    private static final int TCP_ATO_MIN = HZ / 25;

    /**
     * ACK 超时时间(offset).
     *
     * @see #tcp_event_data_recv(TcpPacket)
     * @see #tcp_event_data_sent()
     */
    private long icsk_ack_ato = 0;
    private int icsk_ack_pending;
    private long icsk_ack_timeout;
    private int icsk_ack_retry;
    private int icsk_rto_min = TCP_RTO_MIN;//40;
    private int icsk_delack_max;

    private static final int sysctl_tcp_pingpong_thresh = 1;
    private int icsk_ack_pingpong;

    private long tcp_rto_min_us() {
        return jiffies_to_usecs(tcp_rto_min());
    }

    private int tcp_rto_min() {
        // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L783
        return icsk_rto_min;
    }


    private void tcp_delack_timer() {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L358
        tcp_delack_timer_handler();
    }

    private void tcp_delack_timer_handler() {
        //  https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L307
        // https://www.cnblogs.com/wanpengcoder/p/11749449.html
        if (State.TCP_CLOSE.equals(state.get())) {
            return;
        }

        if (0 == (icsk_ack_pending & ICSK_ACK_TIMER)) {
            return;
        }
        if (icsk_ack_timeout > jiffies()) {
            // reset ??.
            sk_reset_timer(icsk_delack_timer, icsk_ack_timeout);
//            log.warn("RESCHEDULE");
            return;
        }

        icsk_ack_pending &= ~ICSK_ACK_TIMER;
        if (inet_csk_ack_scheduled()) {
            // ...
            if (!inet_csk_in_pingpong_model()) {
                /* Delayed ACK missed: inflate ATO. */
                icsk_ack_ato = Math.min(icsk_ack_ato << 1, icsk_rto);
            } else {
                /* Delayed ACK missed: leave pingpong mode and
                 * deflate ATO.
                 */
                inet_csk_exit_pingpong_mode();
                icsk_ack_ato = TCP_ATO_MIN;
            }

            tcp_mstamp_refresh();
            tcp_send_ack();
            // ...
        }
    }

    private void inet_csk_schedule_ack() {
        // https://github.com/torvalds/linux/blob/master/include/net/inet_connection_sock.h#L177
        // 确保在适当的时候发送 ACK，以确认接收到数据
        icsk_ack_pending |= ICSK_ACK_SCHED;
    }

    private boolean inet_csk_ack_scheduled() {
        // https://github.com/torvalds/linux/blob/master/include/net/inet_connection_sock.h
        return 0 != (icsk_ack_pending & ICSK_ACK_SCHED);
    }

    private void inet_csk_inc_pingpong_cnt() {
        if (icsk_ack_pingpong < U8_MAX) {
            icsk_ack_pingpong++;
        }
    }

    private boolean inet_csk_in_pingpong_model() {
        return icsk_ack_pingpong >= sysctl_tcp_pingpong_thresh;
    }

    private void inet_csk_enter_pingpong_mode() {
        // https://github.com/torvalds/linux/blob/master/include/net/inet_connection_sock.h#L329
        icsk_ack_pingpong = sysctl_tcp_pingpong_thresh;
    }

    private void inet_csk_exit_pingpong_mode() {
        // https://github.com/torvalds/linux/blob/master/include/net/inet_connection_sock.h#L335
        icsk_ack_pingpong = 0;
    }

    /* *********** ]] DELAY ACK ************** */

    private void tcp_keepalive_timer() {

    }

    /* *********** WRITE TIMER [[ ************** */

    // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L146
    private static final int TCP_RTO_MAX = 120 * HZ;
    private static final int TCP_RTO_MIN = HZ / 5;
    private int icsk_pending;
    private long icsk_timeout;

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L723">tcp_write_timer</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L883">tcp_init_xmit_timers</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/inet_connection_sock.c#L760">inet_csk_init_xmit_timers</a>
     */
    private void tcp_write_timer() {
        if (icsk_pending <= 0) {
            return;
        }
        tcp_write_timer_handler();
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L688">tcp_write_timer_handler</a>
     */
    private void tcp_write_timer_handler() {
        if (0 != ((1 << state.get().ordinal()) & (TCPF_CLOSE | TCPF_LISTEN))
                || icsk_pending <= 0) {
            return;
        }
        if (icsk_timeout > jiffies()) {
            sk_reset_timer(icsk_retransmit_timer, icsk_timeout);
            return;
        }

        tcp_mstamp_refresh();

        int event = icsk_pending;
        switch (event) {
            case ICSK_TIME_REO_TIMEOUT:
                // FIXME
                // tcp_rack_reo_timeout(sk);
                break;
            case ICSK_TIME_LOSS_PROBE:
                // FIXME
                // tcp_send_loss_probe(sk);
                break;
            case ICSK_TIME_RETRANS:
                icsk_pending = 0;
                tcp_retransmit_timer();
                break;
            case ICSK_TIME_PROBE0:
                icsk_pending = 0;
                tcp_probe_timer();
                break;
        }
    }

    /* *********** ]] WRITE TIMER ************** */

    /* *********** RETRANSMIT TIMER [[ ************** */

    private int icsk_backoff;

    // FIXME
    // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp.c#L422
    private int icsk_rto;
    private int total_rto_recoveries;
    private long rto_stamp;
    private int total_rto;


    // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L2448
    private long tcp_rto_delta_us() {
        return TCP_RTO_MAX;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L529">tcp_retransmit_timer</a>
     */
    private void tcp_retransmit_timer() {
        if (packets_out <= 0) {
            return;
        }

        TcpBuffer skb = tcp_rtx_queue_head();
        if (null == skb) {
            return;
        }

        // if ....
        if (false) {
            long us_or_ms1 = tcp_time_stamp_ts();
            long retrans_stamp0 = retrans_stamp != 0 ? retrans_stamp : tcp_skb_timestamp_ts(tcp_usec_ts, skb);
            long rtx_delta = us_or_ms1 - retrans_stamp0;
            if (tcp_usec_ts != 0) {
                // rtx_delta /= 1000;  // ms
                rtx_delta = TimeUnit.MICROSECONDS.toMillis(rtx_delta);
            }


            if (tcp_rtx_probe0_timed_out(rtx_delta)) {
                log.warn("RETRANSMIT_WRITE_ERROR");
                tcp_write_err();
                return;
            }

            tcp_enter_loss();
            tcp_retransmit_skb(skb, 1);
        } else {
            //....
            if (0 != tcp_write_timeout()) {
                return;
            }

            if (icsk_retransmits == 0) {
                // ignore
            }

            tcp_enter_loss();
            tcp_update_rto_stats();
            if (tcp_retransmit_skb(tcp_rtx_queue_head(), 1) > 0) {
                /* Retransmission failed because of local congestion,
                 * Let senders fight for local resources conservatively.
                 */
                inet_csk_reset_xmit_timer(ICSK_TIME_RETRANS, TCP_RESOURCE_PROBE_INTERVAL, TCP_RTO_MAX);
                return;
            }
        }

        /* Increase the timeout each time we retransmit.  Note that
         * we do not increase the rtt estimate.  rto is initialized
         * from rtt, but increases here.  Jacobson (SIGCOMM 88) suggests
         * that doubling rto each time is the least we can get away with.
         * In KA9Q, Karn uses this for the first few times, and then
         * goes to quadratic.  netBSD doubles, but only goes up to *64,
         * and clamps at 1 to 64 sec afterwards.  Note that 120 sec is
         * defined in the protocol as the maximum possible RTT.  I guess
         * we'll have to use something other than TCP to talk to the
         * University of Mars.
         *
         * PAWS allows us longer timeouts and large windows, so once
         * implemented ftp to mars will work nicely. We will have to fix
         * the 120 second clamps though!
         */

        // ...
        // FIXME
        inet_csk_reset_xmit_timer(ICSK_TIME_RETRANS, tcp_clamp_rto_to_user_timeout(), TCP_RTO_MAX);
        if (retransmits_timed_out(sysctl_tcp_retries1 + 1, 0)) {
            // 重置路由缓存
            // __sk_dst_reset(sk);
        }
    }


    private void tcp_update_rto_stats() {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L436
        if (0 == icsk_retransmits) {
            total_rto_recoveries++;
            rto_stamp = tcp_time_stamp_ms();
        }
        icsk_retransmits++;
        total_rto++;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L213">retransmits_timed_out</a>
     */
    private boolean retransmits_timed_out(int boundary, long timeout) {
        if (icsk_retransmits == 0) {
            return false;
        }

        long start_ts = retrans_stamp;
        if (timeout == 0) {
            long rto_base = TCP_RTO_MIN;
            if (0 != ((1 << state.get().ordinal()) & (TCPF_SYN_SENT | TCPF_SYN_RECV))) {
                rto_base = tcp_timeout_init();
            }
            timeout = tcp_model_timeout(boundary, rto_base);
        }

        if (tcp_usec_ts != 0) {
            long delta = tcp_mstamp - start_ts + jiffies_to_usecs(1);
            return delta - TimeUnit.MILLISECONDS.toMicros(timeout) >= 0;
        }
        return tcp_time_stamp_ts() - start_ts - timeout >= 0;
    }

    // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L186
    private long tcp_model_timeout(int boundary, long rto_base) {
        int linear_backoff_thresh = (int) (Math.log(TCP_RTO_MAX / rto_base) / Math.log(2));
        long timeout;
        if (boundary <= linear_backoff_thresh) {
            timeout = ((2 << boundary) - 1) * rto_base;
        } else {
            timeout = ((2 << linear_backoff_thresh) - 1) * rto_base + (boundary - linear_backoff_thresh) * TCP_RTO_MAX;
        }
        return jiffies_to_msecs(timeout);
    }

    /**
     * @return
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L2702">tcp_timeout_init</a>
     */
    private long tcp_timeout_init() {
        return Math.min(TCP_TIMEOUT_INIT, TCP_RTO_MAX);
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L27">tcp_clamp_rto_to_user_timeout</a>
     */
    private long tcp_clamp_rto_to_user_timeout() {
        long user_timeout = icsk_user_timeout;
        if (user_timeout == 0) {
            return icsk_rto;
        }
        long elapsed = tcp_time_stamp_ts() - retrans_stamp;
        if (tcp_usec_ts != 0) {
            elapsed = TimeUnit.MICROSECONDS.toMillis(elapsed);
        }
        long remaining = user_timeout - elapsed;
        if (remaining <= 0) {
            /* user timeout has passed; fire ASAP */
            return 1;
        }
        return Math.min(icsk_rto, msecs_to_jiffies(remaining));
    }

    // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L241
    private int tcp_write_timeout() {
        boolean expired = false;
        int retry_until = sysctl_tcp_retries2;
        if (0 != ((1 << state.get().ordinal()) & (TCPF_SYN_SENT | TCPF_SYN_RECV))) {
            // FIXME
        } else {
            // ...
            retry_until = sysctl_tcp_retries2;
            // ...
        }

        if (!expired) {
            expired = retransmits_timed_out(retry_until, icsk_user_timeout);
        }

        if (expired) {
            /* Has it gone just too far? */
            tcp_write_err();
            return 1;
        }
        return 0;
    }

    // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L2190
    private void tcp_enter_loss() {

    }


    private int rounddown(int a, int b) {
        return a - (a % b);
    }

    private int tcp_skb_pcount(TcpBuffer skb) {
        return 1;
    }

    private long tcp_skb_timestamp_ts(int usec_ts, TcpBuffer skb) {
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
    private int tcp_skb_timestamp_us(final TcpBuffer skb) {
        // skb_mstamp_ns <==> skb->tstamp
        // return div_u64(skb->skb_mstamp_ns, NSEC_PER_USEC);
        // FIXME
        return (int) (skb.tstamp / 1000);
    }

    // https://github.com/torvalds/linux/blob/v6.13/include/linux/skbuff.h#L4322
    private void skb_set_delivery_time(TcpBuffer skb, long kt, String tstamp_type) {
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

    private long tcp_time_stamp_ts() {
        // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L873
        // ???
        if (tcp_usec_ts > 0) {
            return tcp_mstamp;
        }
        return tcp_time_stamp_ms();
    }

    private long tcp_time_stamp_ms() {
        return TimeUnit.MICROSECONDS.toMillis(tcp_mstamp);
    }


    /**
     * @return
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L488">tcp_rtx_probe0_timed_out</a>
     */
    private boolean tcp_rtx_probe0_timed_out(long rtx_delta) {
        int user_timeout = icsk_user_timeout;
        int timeout = TCP_RTO_MAX << 1;
        if (user_timeout > 0) {
            /* If user application specified a TCP_USER_TIMEOUT,
             * it does not want win 0 packets to 'reset the timer'
             * while retransmits are not making progress.
             */
            if (rtx_delta > user_timeout) {
                return true;
            }
            timeout = Math.min(timeout, user_timeout);
        }

        /* Note: timer interrupt might have been delayed by at least one jiffy,
         * and tp->rcv_tstamp might very well have been written recently.
         * rcv_delta can thus be negative.
         */
        long rcv_delta = icsk_timeout - rcv_tstamp;
        if (rcv_delta <= timeout) {
            return false;
        }
        return rtx_delta > timeout;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L2003">tcp_rtx_queue_head</a>
     */
    private TcpBuffer tcp_rtx_queue_head() {
        return tcp_rtx_queue.peek();
    }

    /* *********** ]] RETRANSMIT TIMER ************** */

    /* *********** ZERO WINDOW PROBE [[ ************** */

    // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L160
    private static final long TCP_RESOURCE_PROBE_INTERVAL = HZ / 2;

    /*
    RFC6298 2.1 initial RTO value.
    https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L152
    */
    private static final int TCP_TIMEOUT_INIT = 1 * HZ;
    private static final int TCP_TIMEOUT_MIN = 2;

    private int icsk_probes_out;
    private long icsk_probes_tstamp;
    private int icsk_user_timeout;

    private void tcp_check_probe_timer() {
        // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L1454
        // https://www.cnblogs.com/aiwz/p/6333260.html
        if (packets_out <= 0 && icsk_pending <= 0) {
            tcp_reset_xmit_timer(ICSK_TIME_PROBE0, tcp_probe0_base(), TCP_RTO_MAX);
        }
    }


    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L386">tcp_probe_timer</a>
     */
    private void tcp_probe_timer() {
        final TcpBuffer skb = tcp_send_head();
        if (packets_out > 0 || null == skb) {
            icsk_probes_out = 0;
            icsk_probes_tstamp = 0;
            return;
        }

        /* RFC 1122 4.2.2.17 requires the sender to stay open indefinitely as
         * long as the receiver continues to respond probes. We support this by
         * default and reset icsk_probes_out with incoming ACKs. But if the
         * socket is orphaned or the user specifies TCP_USER_TIMEOUT, we
         * kill the socket when the retry count and the time exceeds the
         * corresponding system limit. We also implement similar policy when
         * we use RTO to probe window in tcp_retransmit_timer().
         */
        if (icsk_probes_tstamp == 0) {
            icsk_probes_tstamp = tcp_jiffies32();
        } else {
            final int user_timeout = icsk_user_timeout;
            if (user_timeout > 0 && tcp_jiffies32() - icsk_probes_tstamp >= user_timeout) {
                log.warn("PROBE_WRITE_ERROR 1");
                tcp_write_err();
                return;
            }
        }

        int max_probes = sysctl_tcp_retries2;

        // ...

        if (icsk_probes_out >= max_probes) {
            log.warn("PROBE_WRITE_ERROR 2");
            tcp_write_err();
        } else {
            /* Only send another probe if we didn't close things up. */
            tcp_send_probe0();
        }
    }


    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L49">tcp_clamp_probe0_to_user_timeout</a>
     */
    private long tcp_clamp_probe0_to_user_timeout(long when) {
        int user_timeout = icsk_user_timeout;
        if (0 == user_timeout || 0 == icsk_probes_tstamp) {
            return when;
        }
        long elapsed = tcp_jiffies32() - icsk_probes_tstamp;
        if (elapsed < 0) {
            elapsed = 0;
        }
        long remaining = user_timeout - elapsed;
        remaining = Math.max(remaining, TCP_TIMEOUT_MIN);
        return Math.min(remaining, when);
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L1444">tcp_probe0_when</a>
     */
    private long tcp_probe0_when(int max_when) {
        // u8 backoff = ilog2(TCP_RTO_MAX / TCP_RTO_MIN) + 1
        int backoff = (int) (Math.log(TCP_RTO_MAX / TCP_RTO_MIN) / Math.log(2)) + 1;
        backoff = Math.min(backoff, icsk_backoff);

        final long when = tcp_probe0_base() << backoff;
        return Math.min(when, max_when);
    }

    private long tcp_probe0_base() {
        // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L1438
        return Math.max(icsk_rto, TCP_RTO_MIN);
    }


    /* *********** ]] ZERO WINDOW PROBE ************** */


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
    private int tcp_packets_in_flight() {
        return packets_out - tcp_left_out() + retrans_out;
    }


    private boolean tcp_under_memory_pressure() {
        // FIXME
        // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L274
        return true;
    }

    // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L1583
    private void tcp_adjust_rcv_ssthresh() {
        __tcp_adjust_rcv_ssthresh(advmss << 2);
    }

    // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L1572
    private void __tcp_adjust_rcv_ssthresh(int new_ssthresh) {
        rcv_ssthresh = Math.min(rcv_ssthresh, new_ssthresh);
        // ...
    }

    /**
     * Note: caller must be prepared to deal with negative returns.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L1560">tcp_space</a>
     */
    private int tcp_space() {
        /*
        接收缓冲区大小 - 待接收 - 已使用.
        return tcp_win_from_space(sk, READ_ONCE(sk->sk_rcvbuf) -
				  READ_ONCE(sk->sk_backlog.len) -
				  atomic_read(&sk->sk_rmem_alloc));
         */
        return tcp_full_space();
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L1567">tcp_full_space</a>
     */
    private int tcp_full_space() {
        // FIXME
        final int sk_rcvbuf = U16_MAX << 6;
        return tcp_win_from_space(sk_rcvbuf);
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L1530">tcp_win_from_space</a>
     */
    private int tcp_win_from_space(int space) {
        // FIXME
        final int scaling_ratio = TCP_DEFAULT_SCALING_RATIO;
        return __tcp_win_from_space(scaling_ratio, space);
    }

    private static final int TCP_RMEM_TO_WIN_SCALE = 8;

    /**
     * Assume a 50% default for skb->len/skb->truesize ratio.
     * This may be adjusted later in tcp_measure_rcv_mss().
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L1552">TCP_DEFAULT_SCALING_RATIO</a>
     */
    private static final int TCP_DEFAULT_SCALING_RATIO = (1 << (TCP_RMEM_TO_WIN_SCALE - 1));

    private int __tcp_win_from_space(int scaling_ratio, int space) {
        int scaled_space = space * scaling_ratio;
        return scaled_space >> TCP_RMEM_TO_WIN_SCALE;
    }

    /* *********** ]] ************** */


    protected void trace(final IpHeader ipHeader, final TcpPacket tcpPacket, boolean inbound) {
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
                .append(inbound ? srcHostName : dstHostNameToUse).append(":").append(srcPort)
                .append(" => ")
                .append(inbound ? dstHostNameToUse : srcHostName).append(":").append(dstPort);

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
            if (inbound) {
                sequence -= !syn ? rcv_isn_l : sequence;
                acknowledgment -= !syn ? snt_isn_l : acknowledgment - 1;
            } else {
                sequence -= !syn ? snt_isn_l : sequence;
                acknowledgment -= !syn ? rcv_isn_l : acknowledgment - 1;
            }
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

        log.info(buff.toString());
    }

    private long tcp_jiffies32() {
        return jiffies();
    }

    /*-
     * 定时器相关使用.
     */
    private long jiffies() {
        return msecs_to_jiffies(System.currentTimeMillis());
    }

    private long usecs_to_jiffies(long us) {
        return msecs_to_jiffies(TimeUnit.MICROSECONDS.toMillis(us));
    }

    private long msecs_to_jiffies(long ms) {
        int MSEC_PER_SEC = 1000;
        if (0 == (HZ % MSEC_PER_SEC)) {
            return (HZ / MSEC_PER_SEC) * ms;
        }
        return (long) ((HZ * 1F / MSEC_PER_SEC) * ms);
    }

    private long jiffies_to_usecs(long jiffies) {
        long USEC_PER_SEC = 1000 * 1000;
        if (0 == (USEC_PER_SEC % HZ)) {
            return USEC_PER_SEC / HZ * jiffies;
        }
        return (long) ((1000F * 1000 / HZ) * jiffies);
    }

    private long jiffies_to_msecs(long jiffies) {
        return TimeUnit.MICROSECONDS.toMillis(jiffies_to_usecs(jiffies));
    }

    private long toUint32(final int value) {
        return value & 0xFFFFFFFFL;
    }

    private int ilog2(int a) {
        return (int) (Math.log(a) / Math.log(2));
    }

    private int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

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
    private static final int TCP_MAX_WINDOW = 32767;


    private long tcp_clock_cache;
    /**
     * 有符号Window.
     */
    private boolean ipv4_sysctl_tcp_workaround_signed_windows;
    private int ipv4_sysctl_tcp_rmem_2;
    private int sysctl_rmem_max;
    private boolean ipv4_sysctl_tcp_shrink_window;
    private int ipv4_sysctl_tcp_min_snd_mss;
    private long tcp_wstamp_ns;
    private long lsndtime;
    private int icsk_pmtu_cookie;

    private void tcp_mstamp_refresh() {
        long ns = tcp_clock_ns();
        tcp_clock_cache = ns;
        tcp_mstamp = (int) TimeUnit.NANOSECONDS.toMicros(ns);
    }

    /**
     * Account for new data that has been sent to the network.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L67">tcp_event_new_data_sent</a>
     */
    private void tcp_event_new_data_sent(TcpBuffer skb) {
        final int prior_packets = packets_out;

        snd_nxt = determineEndSeq(skb);

        // XXX unlink skb from sk_write_queue and append to tcp_rtx_queue.
        tcp_rtx_queue.offer(skb);

        packets_out += tcp_skb_pcount(skb);

        if (prior_packets <= 0 || icsk_pending == ICSK_TIME_LOSS_PROBE) {
            tcp_rearm_rto();
        }

        tcp_check_space();
    }


    /**
     * SND.NXT, if window was not shrunk or the amount of shrunk was less than one
     * window scaling factor due to loss of precision.
     * If window has been shrunk, what should we make? It is not clear at all.
     * Using SND.UNA we will fail to open window, SND.NXT is out of window. :-(
     * Anything in between SND.UNA...SND.UNA+SND.WND also can be already
     * invalid. OK, let's make this for now:
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L97">tcp_acceptable_seq</a>
     */
    private int tcp_acceptable_seq() {
        if (tcp_wnd_end() >= snd_nxt
                || (wscale_ok && snd_nxt - tcp_wnd_end() < (1 << rcv_wscale))) {
            return snd_nxt;
        }
        return tcp_wnd_end();
    }

    /**
     * Congestion state accounting after a packet has been sent.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L163">tcp_event_data_sent</a>
     */
    private void tcp_event_data_sent() {
        long now = tcp_jiffies32();

        lsndtime = now;
        if (now - icsk_ack_lrcvtime < icsk_ack_ato) {
            inet_csk_inc_pingpong_cnt();
        }
    }

    /**
     * Account for an ACK we sent.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L182">tcp_event_ack_sent</a>
     */
    private void tcp_event_ack_sent(int rcv_nxt) {
        if (rcv_nxt != this.rcv_nxt) {
            return;
        }
        tcp_dec_quickack_mode();
        inet_csk_clear_xmit_timer(ICSK_TIME_DACK);
    }

    /**
     * Determine a window scaling and initial window to offer.
     * Based on the assumption that the given amount of space
     * will be offered. Store the results in the tp structure.
     * NOTE: for smooth operation initial space offering should
     * be a multiple of mss if possible. We assume here that mss >= 1.
     * This MUST be enforced by all callers.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L207">tcp_select_initial_window</a>
     */
    private void tcp_select_initial_window(final int __space, final int mss,
                                           final AtomicInteger rcv_wnd, final AtomicInteger __window_clamp,
                                           final boolean wscale_ok,
                                           final AtomicInteger rcv_wscale, final int init_rcv_wnd) {
        int space = __space < 0 ? 0 : __space;
        int window_clamp = __window_clamp.get();

        /* If no clamp set the clamp to the max possible scaled window */
        if (window_clamp == 0) {
            window_clamp = (U16_MAX << TCP_MAX_WSCALE);
        }
        space = Math.min(window_clamp, space);

        /* Quantize space offering to a multiple of mss if possible. */
        if (space > mss) {
            space = rounddown(space, mss);
        }

        /*-
         * NOTE: offering an initial window larger than 32767
         * will break some buggy TCP stacks. If the admin tells us
         * it is likely we could be speaking with such a buggy stack
         * we will truncate our initial window offering to 32K-1
         * unless the remote has sent us a window scaling option,
         * which we interpret as a sign the remote TCP is not
         * misinterpreting the window field as a signed quantity.
         */
        if (ipv4_sysctl_tcp_workaround_signed_windows) {
            rcv_wnd.set(Math.min(space, TCP_MAX_WINDOW));
        } else {
            rcv_wnd.set(space);
        }

        if (0 != init_rcv_wnd) {
            rcv_wnd.set(Math.min(rcv_wnd.get(), init_rcv_wnd * mss));
        }

        rcv_wscale.set(0);
        if (wscale_ok) {
            /* Set window scaling on max possible window */
            space = Math.max(space, ipv4_sysctl_tcp_rmem_2);
            space = Math.max(space, sysctl_rmem_max);
            space = Math.min(space, window_clamp);
            rcv_wscale.set(clamp(ilog2(space) - 15, 0, TCP_MAX_WSCALE));
        }
        /* Set the clamp no higher than max representable value */
        __window_clamp.set(Math.min(U16_MAX << rcv_wscale.get(), window_clamp));
    }

    /**
     * Chose a new window to advertise, update state in tcp_sock for the
     * socket, and return result with RFC1323 scaling applied.  The return
     * value can be stuffed directly into th->window for an outgoing
     * frame.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L260">tcp_select_window</a>
     */
    private int tcp_select_window() {
        int old_win = rcv_wnd;
        int cur_win = tcp_receive_window();
        int new_win = __tcp_select_window();

        /*-
         * Make the window 0 if we failed to queue the data because we
         * are out of memory.
         */
        // XXX NOMEM ?

        if (new_win < cur_win) {
            /*-
             * Danger Will Robinson!
             * Don't update rcv_wup/rcv_wnd here or else
             * we will not be able to advertise a zero
             * window in time.  --DaveM
             *
             * Relax Will Robinson.
             */
            if (!ipv4_sysctl_tcp_shrink_window || 0 != rcv_wscale) {
                /* Never shrink the offered window */
                new_win = ALIGN(cur_win, 1 << rcv_wscale);
            }
        }

        this.rcv_wnd = new_win;
        // XXX  放在这里一点也不合适.
        this.rcv_wup = this.rcv_nxt;

        /*-
         * Make sure we do not exceed the maximum possible
         * scaled window.
         */
        if (rcv_wscale == 0 && ipv4_sysctl_tcp_workaround_signed_windows) {
            new_win = Math.min(new_win, TCP_MAX_WINDOW);
        } else {
            new_win = Math.min(new_win, U16_MAX << rcv_wscale);
        }

        /* RFC1323 scaling applied */
        new_win >>= rcv_wscale;

        /* If we advertise zero window, disable fast path. */
        if (new_win == 0) {
            // TODO
//            tp->pred_flags = 0;
            if (0 != old_win) {
//                NET_INC_STATS(net, LINUX_MIB_TCPTOZEROWINDOWADV);
            }
        } else if (old_win == 0) {
//            log.trace();
//            NET_INC_STATS(net, LINUX_MIB_TCPFROMZEROWINDOWADV);
        }

        return new_win;
    }

    /**
     * Compute TCP options for SYN packets. This is not the final
     * network wire format yet.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L817">tcp_syn_options</a>
     */
    private List<TcpOption> tcp_syn_options() {
        // TODO
        return Collections.emptyList();
    }

    /**
     * Set up TCP options for SYN-ACKs.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L902">tcp_synack_options</a>
     */
    private List<TcpOption> tcp_synack_options(int mss) {

        final List<TcpOption> options = Lists.newArrayList();

        // TODO

        /* We always send an MSS option. */
        options.add(new TcpMaximumSegmentSizeOption.Builder()
                .maxSegSize((short) mss)
                .correctLengthAtBuild(true).build());

        if (ireq_wscale_ok_ref.get()) {
            // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L902
            options.add(TcpNoOperationOption.getInstance());
            options.add(new TcpWindowScaleOption.Builder()
                    .shiftCount((byte) ireq_rcv_wscale_ref.get())
                    .correctLengthAtBuild(true)
                    .build());
        }

        // TODO ...

        return options;
    }

    /**
     * Compute TCP options for ESTABLISHED sockets. This is not the
     * final wire format yet.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L978">tcp_established_options</a>
     */
    private List<TcpOption> tcp_established_options() {
        // TODO
        return Collections.emptyList();
    }

    /**
     * This routine actually transmits TCP packets queued in by
     * tcp_do_sendmsg().  This is used by both the initial
     * transmission and possible later retransmissions.
     * All SKB's seen here are completely headerless.  It is our
     * job to build the TCP header, and pass the packet down to
     * IP so it can do the same plus pass the packet off to the
     * device.
     * <p>
     * We are working here with either a clone of the original
     * SKB, or a fresh unique copy made by the retransmit engine.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1290">__tcp_transmit_skb</a>
     */
    private int __tcp_transmit_skb(final TcpBuffer skb, final boolean clone, int rcv_nxt) {
        long prior_wstamp = tcp_wstamp_ns;
        tcp_wstamp_ns = Math.max(tcp_wstamp_ns, tcp_clock_cache);

        skb_set_delivery_time(skb, tcp_wstamp_ns, "SKB_CLOCK_MONOTONIC");

        List<TcpOption> options;
        if (skb.syn()) {
            options = tcp_syn_options();
        } else {
            options = tcp_established_options();
            if (tcp_skb_pcount(skb) > 1) {
                // FIXME tcb->tcp_flags |= TCPHDR_PSH;
                skb.psh(true);
            }
        }

        // FIXME ....
        // skb_set_dst_pending_confirm(skb, READ_ONCE(sk->sk_dst_pending_confirm));

        skb.acknowledgmentNumber(rcv_nxt);

        // ...


        if (!skb.syn()) {
            skb.window((short) tcp_select_window());
        } else {
            /*
             * RFC1323: The window in SYN & SYN/ACK segments
             * is never scaled.
             */
            skb.window((short) Math.min(rcv_wnd, U16_MAX));
        }

        skb.options(options);


        INDIRECT_CALL_INET(skb);


        if (skb.ack()) {
            tcp_event_ack_sent(rcv_nxt);
        }

        Packet.Builder p = skb.payloadBuilder();
        int len = null != p ? p.build().length() : 0;
        if (len > 0) {
            tcp_event_data_sent();
            // TODO
            data_segs_out += tcp_skb_pcount(skb);
            bytes_sent += len;
        }

        segs_out += tcp_skb_pcount(skb);

        /* Leave earliest departure time in skb->tstamp (skb->skb_mstamp_ns) */

        return 0;
    }

    private void INDIRECT_CALL_INET(final TcpBuffer skb) {
        final IpHeader ipHdr = ipHeader;

        TcpPacket.Builder buf = skb
                .srcAddr(ipHdr.getDstAddr())
                .dstAddr(ipHdr.getSrcAddr())
                .dstPort(tcpSrcPort)
                .srcPort(tcpDstPort)
                //
                .asBuilder()
                .paddingAtBuild(true)
                .correctLengthAtBuild(true)
                .correctChecksumAtBuild(true);

        final IpV4Packet ipPacket = new IpV4Packet.Builder()
                .version(IpVersion.IPV4)
                .tos(((IpV4Packet.IpV4Header) ipHdr).getTos())
                .ttl(((IpV4Packet.IpV4Header) ipHdr).getTtl())
                .identification(((IpV4Packet.IpV4Header) ipHdr).getIdentification())
                .fragmentOffset(((IpV4Packet.IpV4Header) ipHdr).getFragmentOffset())
                .srcAddr(((IpV4Packet.IpV4Header) ipHdr).getDstAddr())
                .dstAddr(((IpV4Packet.IpV4Header) ipHdr).getSrcAddr())
                .protocol(IpNumber.TCP)
                .paddingAtBuild(true)
                .correctLengthAtBuild(true)
                .correctChecksumAtBuild(true)
                .payloadBuilder(buf)
                .build();

        trace(ipPacket.getHeader(), buf.build(), false);
        parent.writeAndFlush(ipPacket);
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1486">tcp_transmit_skb</a>
     */
    private int tcp_transmit_skb(final TcpBuffer skb, final boolean clone) {
        return __tcp_transmit_skb(skb, clone, rcv_nxt);
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1498">tcp_queue_skb</a>
     */
    private void tcp_queue_skb(TcpBuffer skb) {
        write_seq = determineEndSeq(skb);
        sk_write_queue.offer(skb);
    }

    /**
     * Calculate MSS not accounting any TCP options.
     *
     * @param pmtu Path MTU
     * @return mss to use
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1755">__tcp_mtu_to_mss</a>
     */
    private int __tcp_mtu_to_mss(final int pmtu) {
        // XXX icsk->icsk_af_ops->net_header_len
        final int net_header_len = IP_HEADER_SIZE;

        /*-
         * Calculate base mss without TCP options:
         * It is MMS_S - sizeof(tcphdr) of rfc1122
         */
        int mss_now = pmtu - net_header_len - SIZE_OF_TCP_HDR;
        if (mss_now > mss_clamp) {
            mss_now = mss_clamp;
        }

        /* XXX Now subtract optional transport overhead */
        mss_now -= icsk_ext_hdr_len;

        /* Then reserve room for full set of TCP options and 8 bytes of data */
        // mss_now = max(mss_now, READ_ONCE(sock_net(sk)->ipv4.sysctl_tcp_min_snd_mss));
        mss_now = Math.max(mss_now, ipv4_sysctl_tcp_min_snd_mss);
        return mss_now;
    }

    /**
     * Calculate MSS. Not accounting for SACKs here.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1780">tcp_mtu_to_mss</a>
     */
    private int tcp_mtu_to_mss(final int pmtu) {
        /* Subtract TCP options size, not including SACKs */
        return __tcp_mtu_to_mss(pmtu) - (tcp_header_len - SIZE_OF_TCP_HDR);
    }

    /**
     * MTU probing init per socket.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1802">tcp_output.c</a>
     */
    private void tcp_mtup_init() {
        // TODO
    }

    /**
     * This function synchronize snd mss to current pmtu/exthdr set.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1840"></a>
     */
    private int tcp_sync_mss(int pmtu) {
        // XXX icsk->icsk_mtup.search_high

        int mss_now = tcp_mtu_to_mss(pmtu);
        mss_now = tcp_bound_to_half_wnd(mss_now);

        /* And store cached results */
        icsk_pmtu_cookie = pmtu;

        // ... XXX icsk->icsk_mtup.enabled

        mss_cache = mss_now;
        return mss_now;
    }

    /**
     * Compute the current effective MSS, taking SACKs and IP options,
     * and even PMTU discovery events into account.
     *
     * @return
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1865">tcp_current_mss</a>
     */
    private int tcp_current_mss() {
        int mss_now = mss_cache;
        int mtu = dst_mtu();
        if (mtu != icsk_pmtu_cookie) {
            mss_now = tcp_sync_mss(mtu);
        }

        int optLen = tcp_established_options().stream().mapToInt(TcpOption::length).reduce(Integer::sum).orElse(0);
        int header_len = optLen + SIZE_OF_TCP_HDR;
        if (header_len != tcp_header_len) {
            int delta = header_len - tcp_header_len;
            mss_now -= delta;
        }
        return mss_now;
    }

    /**
     * Can at least one segment of SKB be sent right now, according to the
     * congestion window rules?  If so, return how many segments are allowed.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2086">tcp_cwnd_test</a>
     */
    private int tcp_cwnd_test() {
        int in_flight = tcp_packets_in_flight();
        int cwnd = tcp_snd_cwnd();
        if (in_flight >= cwnd) {
            return 0;
        }

        /*-
         * For better scheduling, ensure we have at least
         * 2 GSO packets in flight.
         */
        int halfcwnd = Math.max(cwnd >> 1, 1);
        return Math.min(halfcwnd, cwnd - in_flight);
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2434">tcp_mtu_probe</a>
     */
    private int tcp_mtu_probe() {
        // XXX NOT IMPLEMENTED.
        return -1;
    }

    /**
     * @param mss_now
     * @param push_one
     * @return overflow window (continue push)
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2739">tcp_write_xmit</a>
     */
    private boolean tcp_write_xmit(int mss_now, final int push_one) {

        tcp_mstamp_refresh();
        if (push_one == 0) {
            int mtu = tcp_mtu_probe();
            if (0 == mtu) {
                return false;
            } else if (mtu > 0) {
//                    sent_pkts = 1;
            }
        }


        TcpBuffer skb;
        while (null != (skb = sk_write_queue.peek())) {
//            TcpPacket build = skb.asBuilder().build();
//            TcpHeader th = build.getHeader();

            int cwnd_quota = tcp_cwnd_test();
//            log.warn("cwnd_quota={}", cwnd_quota);
            if (cwnd_quota == 0) {
                if (push_one == 2) {
                    /* Force out a loss probe pkt. */
                    cwnd_quota = 1;
                } else {
                    break;
                }
            }

            if (!sk_write_queue.remove(skb)) {
                continue;
            }

            // cwnd_quota = min(cwnd_quota, tso_max_segs);

//            log.info("transmit MSS: {}", mss_now);
//            int missing_bytes = cwnd_quota * mss_now - build.length();
//            if (missing_bytes > 0) {
//            }

            //
            if (skb.sequenceNumber() == determineEndSeq(skb)) {
                break;
            }
            if (0 != tcp_transmit_skb(skb, true)) {
                break;
            }
            tcp_event_new_data_sent(skb);
        }

        return packets_out <= 0 && !sk_write_queue.isEmpty();
    }

    /**
     * Push out any pending frames which were held back due to
     * TCP_CORK or attempt at coalescing tiny packets.
     * The socket must be locked by the caller.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L3005">__tcp_push_pending_frames</a>
     */
    private void __tcp_push_pending_frames(int mss) {
        /*-
         * If we are closed, the bytes will have to remain here.
         * In time closedown will finish, we empty the write queue and
         * all will be happy.
         */
        if (State.TCP_CLOSE.equals(state.get())) {
            return;
        }
        if (tcp_write_xmit(mss, 0)) {
            tcp_check_probe_timer();
        }
    }

    private int __tcp_select_window() {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L3084
        /*-
         * MSS for the peer's data.  Previous versions used mss_clamp
         * here.  I don't know if the value based on our guesses
         * of peer's MSS is better for the performance.  It's more correct
         * but may be worse for the performance because of rcv_mss
         * fluctuations.  --SAW  1998/11/1
         */
        int mss = icsk_ack_rcv_mss;
        int free_space = tcp_space();
        int allowed_space = tcp_full_space();

        int full_space = Math.min(window_clamp, allowed_space);
        if (mss > full_space) {
            mss = full_space;
            if (mss <= 0) {
                return 0;
            }
        }

        /*-
         * Only allow window shrink if the sysctl is enabled and we have
         * a non-zero scaling factor in effect.
         */
        // XXX ....

        /* do not allow window to shrink */
        if (free_space < (full_space >> 1)) {
            // 窗口已使用一半, 认为将要变紧张.
            icsk_ack_quick = 0;

            if (tcp_under_memory_pressure()) {
                tcp_adjust_rcv_ssthresh();
            }

            /*-
             * free_space might become our new window, make sure we don't
             * increase it due to wscale.
             * FIXME 1. rounddown --> round_down, 2. 和 free_space >> rcv_wscale << rcv_wscale 什么区别 ??
             * free_space = round_down(free_space, 1 << rcv_wscale);
             */
            free_space = free_space >> rcv_wscale << rcv_wscale;

            /*-
             * if free space is less than mss estimate, or is below 1/16th
             * of the maximum allowed, try to move to zero-window, else
             * tcp_clamp_window() will grow rcv buf up to tcp_rmem[2], and
             * new incoming data is dropped due to memory limits.
             * With large window, mss test triggers way too late in order
             * to announce zero window in time before rmem limit kicks in.
             */
            if (free_space < (allowed_space >> 4) || free_space < mss) {
                return 0;
            }
        }

        if (free_space > rcv_ssthresh) {
            free_space = rcv_ssthresh;
        }

        /*-
         * Don't do rounding if we are using window scaling, since the
         * scaled window will not line up with the MSS boundary anyway.
         */
        int window;
        if (rcv_wscale > 0) {
            window = free_space;

            /* Advertise enough space so that it won't get scaled away.
             * Import case: prevent zero window announcement if
             * 1<<rcv_wscale > mss.
             */
            // FIXME
            window = ALIGN(window, (1 << rcv_wscale));
        } else {
            window = rcv_wnd;
            /* Get the largest window that is a nice multiple of mss.
             * Window clamp already applied above.
             * If our current window offering is within 1 mss of the
             * free space we just keep it. This prevents the divide
             * and multiply from happening most of the time.
             * We also don't do any window rounding when the free space
             * is too small.
             */
            if (window <= free_space - mss || window > free_space) {
                window = rounddown(free_space, mss);
            } else if (mss == full_space && free_space > window + (full_space >> 1)) {
                window = free_space;
            }
        }
        return window;
    }

    // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L3321
    private int __tcp_retransmit_skb(final TcpBuffer skb, int segs) {
        // start
        int seq = skb.sequenceNumber();

        if (before(seq, snd_una) && skb.syn()) {
            skb.syn(false);
            skb.sequenceNumber(++seq);
//            b = skb.build();
//            th = b.getHeader();
        }

//        TcpPacket b = skb.asBuilder().build();
//        TcpHeader th = b.getHeader();
        if (before(skb.sequenceNumber(), snd_una)) {
            int end_seq = determineEndSeq(skb);
            if (before(end_seq, snd_una)) {
                // ACKED.
                // return -EINVAL;
//                return -1;
            }
            // ...
        }

        // ...
        int cur_mss = tcp_current_mss();
        int avail_wnd = tcp_wnd_end() - skb.sequenceNumber();

        /* If receiver has shrunk his window, and skb is out of
         * new window, do not retransmit it. The exception is the
         * case, when window is shrunk to zero. In this case
         * our retransmit of one segment serves as a zero window probe.
         */
        if (avail_wnd <= 0) {
            if (skb.sequenceNumber() != snd_una) {
                // return -EAGAIN;
                return -1;
            }
            avail_wnd = cur_mss;
        }

        // FIXME
        int len = cur_mss * segs;
        if (len > avail_wnd) {
            len = rounddown(avail_wnd, cur_mss);
            if (0 == len) {
                len = avail_wnd;
            }
        }

        // FIXME
        log.warn("TCP_RETRNSMIT_SKB");

//        int plen = b.length();
        int plen = skb.asBuilder()
                .srcAddr(ipHeader.getDstAddr())
                .dstAddr(ipHeader.getSrcAddr())
                .build().length();
        if (plen > avail_wnd) {
            // FIXME
            // fragment
            return -1;
        } else {
            // FIXME
        }

        segs = tcp_skb_pcount(skb);

//        total_retrans += segs;
//        bytes_retrans += plen;

        if (false) {

        } else {
            return tcp_transmit_skb(skb, true);
        }

        /* To avoid taking spuriously low RTT samples based on a timestamp
         * for a transmit that never happened, always mark EVER_RETRANS
         */
        // TCP_SKB_CB(skb)->sacked |= TCPCB_EVER_RETRANS;

        return 0;
    }

    // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L3448
    private int tcp_retransmit_skb(final TcpBuffer skb, int segs) {
        int err = __tcp_retransmit_skb(skb, segs);
        if (0 == err) {
            //skb.sacked |= TCPCB_RETRANS;
            retrans_out += tcp_skb_pcount(skb);
        }

        /* Save stamp of the first (attempted) retransmit. */
        if (retrans_stamp == 0) {
            retrans_stamp = tcp_skb_timestamp_ts(tcp_usec_ts, skb);
        }

        if (undo_retrans < 0) {
            undo_retrans = 0;
        }
        undo_retrans += tcp_skb_pcount(skb);
        return err;
    }

    private void tcp_send_fin() {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L3578
        TcpBuffer skb = new TcpBuffer()
                .sequenceNumber(write_seq)
                .ack(true).fin(true);
        tcp_queue_skb(skb);
        __tcp_push_pending_frames(tcp_current_mss());
    }

    // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L3708
    protected TcpBuffer tcp_make_synack(final IpHeader ipHdr, final TcpPacket skb) {
        int mss = tcp_mss_clamp(dst_metric_advmss());
        long now = tcp_clock_ns();

        final TcpBuffer current = new TcpBuffer()
                .srcAddr(ipHdr.getDstAddr())
                .dstAddr(ipHdr.getSrcAddr())
                .srcPort(skb.getHeader().getDstPort())
                .dstPort(skb.getHeader().getSrcPort())
                .syn(true).ack(true)
                .sequenceNumber(req_snt_isn_ref.get())
                .acknowledgmentNumber(req_rcv_nxt_ref.get())
                .window((short) Math.min(req_rsk_rcv_wnd_ref.get(), U16_MAX));

        skb_set_delivery_time(current, now, "SKB_CLOCK_MONOTONIC");

        current.options(tcp_synack_options(mss));


        return current;
    }

    private int tcp_delack_max() {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L4172
        int tcp_rto_min = tcp_rto_min();
        int icsk_delack_max = this.icsk_delack_max;
        int delack_from_rto_min = Math.max(tcp_rto_min, 2) - 1;
        return Math.min(icsk_delack_max, delack_from_rto_min);
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L4183">tcp_send_delayed_ack</a>
     */
    private void tcp_send_delayed_ack() {
        long ato = icsk_ack_ato;
        if (ato > TCP_DELACK_MIN) {
            int max_ato = HZ / 2;

            if (inet_csk_in_pingpong_model() || 0 != (icsk_ack_pending & ICSK_ACK_PUSHED)) {
                max_ato = TCP_DELACK_MAX;
            }

            /* Slow path, intersegment interval is "high". */

            /* If some rtt estimate is known, use it to bound delayed ack.
             * Do not use inet_csk(sk)->icsk_rto here, use results of rtt measurements
             * directly.
             */
            if (srtt_us != 0) {
                int rtt = (int) Math.min(usecs_to_jiffies(srtt_us >> 3), TCP_DELACK_MIN);
                if (rtt < max_ato) {
                    max_ato = rtt;
                }
            }

            ato = Math.min(ato, max_ato);
        }

        ato = Math.min(ato, tcp_delack_max());

        /* Stay within the limit we were given */
        long timeout = jiffies() + ato;

        /* Use new timeout only if there wasn't a older one earlier. */
        if ((icsk_ack_pending & ICSK_ACK_TIMER) != 0) {

            /* If delack timer is about to expire, send ACK now. */
            if (time_before_eq(icsk_ack_timeout, jiffies() + (ato >> 2))) {
                // send now.
                tcp_send_ack();
                return;
            }

            if (!time_before(timeout, icsk_ack_timeout)) {
                timeout = icsk_ack_timeout;
            }
        }

        // XXX smp_store_release
        icsk_ack_pending |= ICSK_ACK_SCHED | ICSK_ACK_TIMER;

        if (icsk_ack_timeout != timeout) {
            icsk_ack_timeout = timeout;
            sk_reset_timer(icsk_delack_timer, timeout);
        }
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L4237">__tcp_send_ack</a>
     */
    private void __tcp_send_ack(final int rcv_nxt) {
        /* If we have been reset, we may not send again. */
        if (State.TCP_CLOSE.equals(state.get())) {
            return;
        }

        final int sndSeq = tcp_acceptable_seq();

        TcpBuffer current = new TcpBuffer().ack(true).sequenceNumber(sndSeq);
        __tcp_transmit_skb(current, false, rcv_nxt);
    }

    private void tcp_send_ack() {
        __tcp_send_ack(rcv_nxt);
    }


    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L4295">tcp_xmit_probe_skb</a>
     */
    private int tcp_xmit_probe_skb(int urgent, int mib) {
        /*-
         * Use a previous sequence.  This should cause the other
         * end to send an ack.  Don't queue or clone SKB, just
         * send it.
         */
        final int seq = snd_una - (0 == urgent ? 1 : 0);
        TcpBuffer skb = new TcpBuffer().sequenceNumber(seq).ack(true);
        return tcp_transmit_skb(skb, false);
    }

    /**
     * Initiate keepalive or window probe from timer.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L4328">tcp_write_wakeup</a>
     */
    private int tcp_write_wakeup(int mib) {
        if (State.TCP_CLOSE.equals(state.get())) {
            return -1;
        }

        final TcpBuffer buf = tcp_send_head();
        final TcpBuffer skb = null != buf ? buf : null;
        if (null != skb && skb.sequenceNumber() < tcp_wnd_end()) {
            final int seq = skb.sequenceNumber();
            final int mss = tcp_current_mss();
            int seg_size = tcp_wnd_end() - seq;

            int end_seq = determineEndSeq(skb);
            if (before(pushed_seq, end_seq)) {
                // XXX ???
                pushed_seq = end_seq;
            }

            /* We are probing the opening of a window
             * but the window size is != 0
             * must have been a result SWS avoidance ( sender )
             */
            // FIXME
//            final int len = skb.asBuilder().length();
//            final int len = skb.length() - skb.getHeader().length();
//            if (seg_size < end_seq - seq || len > mss) {
//                seg_size = Math.min(seg_size, mss);
//                buf.psh(true);

            // FIXME
//            }

            // ...

            buf.psh(true);

//            TcpPacket build = buf.build();
            int err = tcp_transmit_skb(skb, true);
            if (0 == err) {
                tcp_event_new_data_sent(skb);
            }
            return err;
        } else {
            // XXX ????
            if (snd_up <= snd_una + 1 && snd_una + 1 <= snd_una + 0xFFFF) {
                tcp_xmit_probe_skb(1, mib);
            }
            return tcp_xmit_probe_skb(0, mib);
        }
    }

    /**
     * A window probe timeout has occurred.  If window is not closed send
     * a partial packet else a zero probe.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L4374">tcp_send_probe0</a>
     */
    private void tcp_send_probe0() {
        // LINUX_MIB_TCPWINPROBE
        int err = tcp_write_wakeup(0);

        if (packets_out > 0 || tcp_write_queue_empty()) {
            /* Cancel probe timer, if it is not required. */
            icsk_backoff = 0;
            icsk_probes_out = 0;
            icsk_probes_tstamp = 0;
            return;
        }

        icsk_probes_out++;
        long timeout;
        if (err <= 0) {
            if (icsk_backoff < sysctl_tcp_retries2) {
                icsk_backoff++;
            }
            timeout = tcp_probe0_when(TCP_RTO_MAX);
        } else {
            /* If packet was not sent due to local congestion,
             * Let senders fight for local resources conservatively.
             */
            timeout = TCP_RESOURCE_PROBE_INTERVAL;
        }

        timeout = tcp_clamp_probe0_to_user_timeout(timeout);
        tcp_reset_xmit_timer(ICSK_TIME_PROBE0, timeout, TCP_RTO_MAX);
    }


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
    private boolean tcp_ack_update_rtt(int flag, int seq_rtt_us,
                                       int sack_rtt_us, int ca_rtt_us/*,
                                       struct rate_sample *rs*/) {

        /* Prefer RTT measured from ACK's timing to TS-ECR. This is because
         * broken middle-boxes or peers may corrupt TS-ECR fields. But
         * Karn's algorithm forbids taking RTT if some retransmitted data
         * is acked (RFC6298).
         */
        if (seq_rtt_us < 0)
            seq_rtt_us = sack_rtt_us;

        /* RTTM Rule: A TSecr value received in a segment is used to
         * update the averaged RTT measurement only if the segment
         * acknowledges some new data, i.e., only if it advances the
         * left edge of the send window.
         * See draft-ietf-tcplw-high-performance-00, section 3.3.
         */
//        if (seq_rtt_us < 0 && tp->rx_opt.saw_tstamp && tp->rx_opt.rcv_tsecr && flag & FLAG_ACKED)
//            seq_rtt_us = ca_rtt_us = tcp_rtt_tsopt_us(tp);

        // rs->rtt_us = ca_rtt_us; /* RTT of last (S)ACKed packet (or -1) */
        if (seq_rtt_us < 0)
            return false;

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
    private void tcp_rearm_rto() {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3261
        if (packets_out <= 0) {
            inet_csk_clear_xmit_timer(ICSK_TIME_RETRANS);
        } else {
            long rto = icsk_rto;

            /* Offset the time elapsed after installing regular RTO */
            if (icsk_pending == ICSK_TIME_REO_TIMEOUT
                    || icsk_pending == ICSK_TIME_LOSS_PROBE) {
                long delta_us = tcp_rto_delta_us();
                /* delta_us may not be positive if the socket is locked
                 * when the retrans timer fires and is rescheduled.
                 */
                rto = usecs_to_jiffies(Math.max(delta_us, 1));
            }
            tcp_reset_xmit_timer(ICSK_TIME_RETRANS, rto, TCP_RTO_MAX);
        }
    }

    private void tcp_ack_tstamp() {

    }

    private int tcp_clean_rtx_queue(int prior_snd_una) {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3340

        int first_ackt = 0;
        int last_ackt = 0;
        int flag = 0;
        boolean fully_acked = true;
        int seq_rtt_us = 0;
        int ca_rtt_us = 0;

        TcpBuffer skb;
        while (null != (skb = tcp_rtx_queue.peek())) {
//            TcpPacket tp = skb.asBuilder().build();
//            final TcpHeader th = tp.getHeader();
            final int seq = skb.sequenceNumber();
            final int end_seq = determineEndSeq(skb);
            int acked_pcount = 1;
            int sacked = 0; // FIXME

            /* Determine how many packets and what bytes were acked, tso and else */
            if (after(end_seq, snd_una)) {
                if (tcp_skb_pcount(skb) == 1 || !after(snd_una, seq)) {
                    break;
                }

                // FIXME
                acked_pcount = 1;
                // acked_pcount = tcp_tso_acked(sk, skb);
                //			if (!acked_pcount)
                //				break;
                fully_acked = false;
            } else {
                acked_pcount = tcp_skb_pcount(skb);
            }

            /*-
             * 如果是重传过的包.
             */
            if (0 != (sacked & TCPCB_RETRANS)) {
                // 已发出去的重传.
                if (0 != (sacked & TCPCB_SACKED_RETRANS)) {
                    retrans_out -= acked_pcount;
                }

                flag |= FLAG_RETRANS_DATA_ACKED;
            } else if (0 == (sacked & TCPCB_SACKED_ACKED)) {
                /*-
                 * 不是重传过的包且不是被 SACKED 过的.
                 */
                last_ackt = tcp_skb_timestamp_us(skb);
                if (0 == first_ackt) {
                    first_ackt = last_ackt;
                }
            }


            packets_out -= acked_pcount;

            /*
            if (!th.getSyn()) {
                flag |= FLAG_DATA_ACKED;
            } else {
                flag |= FLAG_SYN_ACKED;
                retrans_stamp = 0;
            }
            */


            tcp_rtx_queue.remove(skb);

            tcp_ack_tstamp();
        }

        if (between(snd_up, prior_snd_una, snd_una)) {
            snd_up = snd_una;
        }

        if (first_ackt != 0 && (0 == (flag & FLAG_RETRANS_DATA_ACKED))) {
            /*-
             * 有开始时间, 且不是重传数据的ACK.
             */
            seq_rtt_us = tcp_stamp_us_delta(tcp_mstamp, first_ackt);
            ca_rtt_us = tcp_stamp_us_delta(tcp_mstamp, last_ackt);

            log.info("SEQ RTT = {}", seq_rtt_us);
        }

        /*-
         * 更新 RTT, RTO.
         */

        // FIXME
        tcp_ack_update_rtt(flag, seq_rtt_us, 0, ca_rtt_us);

        return 0;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3546">tcp_ack_probe</a>
     */
    private void tcp_ack_probe() {
        final TcpBuffer head = tcp_send_head();

        /* Was it a usable window open? */
        if (null == head) {
            return;
        }
        if (!after(determineEndSeq(head), tcp_wnd_end())) {
            icsk_backoff = 0;
            icsk_probes_tstamp = 0;
            inet_csk_clear_xmit_timer(ICSK_TIME_PROBE0);
            /* Socket must be waked up by subsequent tcp_data_snd_check().
             * This function is not for random using!
             */
        } else {
            long when = tcp_probe0_when(TCP_RTO_MAX);
            when = tcp_clamp_probe0_to_user_timeout(when);
            tcp_reset_xmit_timer(ICSK_TIME_PROBE0, when, TCP_RTO_MAX);
        }
    }

    /**
     * @param ack
     * @param ack_seq
     * @param nwin
     * @return
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3620">tcp_may_update_window</a>
     */
    private boolean tcp_may_update_window(final int ack, final int ack_seq, final int nwin) {
        return ack > snd_una
                || ack_seq > snd_wl1
                || (ack_seq == snd_wl1 && (nwin > snd_wnd || nwin == 0));
    }

    /**
     * @param ack
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3629">tcp_snd_sne_update</a>
     */
    private void tcp_snd_sne_update(int ack) {

    }

    /**
     * If we update tp->snd_una, also update tp->bytes_acked.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3647">tcp_snd_una_update</a>
     */
    private void tcp_snd_una_update(final int ack) {
        final int delta = ack - snd_una;
        bytes_acked += delta;
        tcp_snd_sne_update(ack);
        snd_una = ack;
    }

    private void tcp_rcv_nxt_update(final int seq) {
        final int delta = seq - rcv_nxt;
        bytes_received += delta;
        // tcp_rcv_sne_update(seq)
        rcv_nxt = seq;
    }


    /**
     * Update our send window.
     * <p>
     * Window update algorithm, described in RFC793/RFC1122 (used in linux-2.2
     * and in FreeBSD. NetBSD's one is even worse.) is wrong.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3696">tcp_ack_update_window</a>
     */
    private int tcp_ack_update_window(final TcpHeader tcpHdr, final int ack, final int ack_seq) {
        int flag = 0;
        int nwin = tcpHdr.getWindowAsInt();

        if (!tcpHdr.getSyn()) {
            nwin <<= snd_wscale;
        }

        /*-
         * 如果允许改ACK更新窗口.
         */
        if (tcp_may_update_window(ack, ack_seq, nwin)) {
            flag |= FLAG_WIN_UPDATE;
            tcp_update_wl(ack_seq);

            if (nwin != snd_wnd) {
//                log.warn("[Window Update] {} -> {}", snd_wnd, nwin);
                snd_wnd = nwin;

                /* Note, it is the only place, where
                 * fast path is recovered for sending TCP.
                 * TODO
                 */
//                tp->pred_flags = 0;
//                tcp_fast_path_check(sk);

                if (nwin > max_window) {
                    max_window = nwin;
                    tcp_sync_mss(icsk_pmtu_cookie);
                }
            }
        }

        tcp_snd_una_update(ack);

        /*-
         * 慢启动阶段(slow-start phase): cwnd = cwnd + 1 (SMSS)
         * 拥塞避免阶段(congestion-avoidance phase): cwnd = cwnd + 1 (SMSS) / cwnd
         */
        // cwnd = cwnd < ssthresh ? cwnd + sndMss : cwnd + sndMss / cwnd;
        return flag;
    }

    private void tcp_in_ack_event(int ack_env_flags) {

    }

    /**
     * This routine deals with incoming acks, but not outgoing ones.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3904">tcp_ack</a>
     */
    private int tcp_ack(final TcpPacket skb, int flag) {
        final TcpHeader tcpHdr = skb.getHeader();
        final int prior_snd_una = snd_una;
        final int prior_packets_out = packets_out;
        final int ack_seq = tcpHdr.getSequenceNumber();
        final int ack = tcpHdr.getAcknowledgmentNumber();

        /*-
         * If the ack is older than previous acks then we can probably ignore it.
         */
        if (before(ack, prior_snd_una)) {
            /* do not accept ACK for bytes we never sent. */
            int max_window = Math.min(this.max_window, bytes_acked);
            /* RFC 5961 5.2 [Blind Data Injection Attack].[Mitigation] */
            if (before(ack, prior_snd_una - max_window)) {
                // TODO
            }

            log.warn("{} < {}", ack, prior_snd_una);
            return 0;
        }

        /*-
         * If the ack includes data we haven't sent yet,
         * discard this segment (RFC793 Section 3.9).
         */
        if (after(ack, snd_nxt)) {
            return -SKB_DROP_REASON_TCP_ACK_UNSENT_DATA;
        }

        if (after(ack, prior_snd_una)) {
            flag |= FLAG_SND_UNA_ADVANCED;
            icsk_retransmits = 0;
        }

        if ((flag & (FLAG_SLOWPATH | FLAG_SND_UNA_ADVANCED)) == FLAG_SND_UNA_ADVANCED) {
            /*-
             * Window is constant, pure forward advance.
             * No more checks are required.
             * Note, we use the fact that SND.UNA>=SND.WL2.
             */
            tcp_update_wl(ack_seq);
            tcp_snd_una_update(ack);
            flag |= FLAG_WIN_UPDATE;

            tcp_in_ack_event(CA_ACK_WIN_UPDATE);
        } else {
            int ack_ev_flags = CA_ACK_SLOWPATH;
            if (ack_seq != determineEndSeq(skb)) {
                flag |= FLAG_DATA;
            }

            flag |= tcp_ack_update_window(tcpHdr, ack, ack_seq);


            if (0 != (flag & FLAG_WIN_UPDATE)) {
                ack_ev_flags |= CA_ACK_WIN_UPDATE;
            }
            tcp_in_ack_event(ack_ev_flags);
        }

        sk_err_soft = 0;
        icsk_probes_out = 0;
        rcv_tstamp = tcp_jiffies32();

        if (prior_packets_out == 0) {
            // no_queue.
            /*-
             * If this ack opens up a zero window, clear backoff.  It was
             * being used to time the probes, and is probably far higher than
             * it needs to be for normal retransmission.
             */
            tcp_ack_probe();
            return 1;
        }


        // See if we can take anything off of the retransmit queue.
        flag |= tcp_clean_rtx_queue(prior_snd_una);
        return 1;
    }


    private void tcp_parse_options(final TcpPacket skb, final boolean estab) {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L4183
        final TcpHeader hdr = skb.getHeader();
        for (final TcpOption option : hdr.getOptions()) {
            if (option instanceof TcpMaximumSegmentSizeOption && hdr.getSyn() && !estab) {
                int inMss = ((TcpMaximumSegmentSizeOption) option).getMaxSegSizeAsInt();
                if (inMss > 0) {
                    int user_mss = tmp_opt_rx_user_mss.get();
                    inMss = user_mss > 0 && user_mss < inMss ? user_mss : inMss;
                    tmp_opt_rx_mss_clamp.set(inMss);
                }
            } else if (option instanceof TcpWindowScaleOption && hdr.getSyn() && !estab && sysctl_tcp_window_scaling) {
                final byte wscale = ((TcpWindowScaleOption) option).getShiftCount();
                tmp_opt_wscale_ok.set(true);
                tmp_opt_snd_wscale.set(wscale > TCP_MAX_WSCALE ? TCP_MAX_WSCALE : wscale);
            }
        }
    }

    // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L4521
    private void tcp_done_with_error(int err) {
        log.error("TCP DONW WITH ERROR: {}", err);
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L4515
        tcp_done();
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L4530">tcp_reset</a>
     */
    private void tcp_reset(final TcpPacket skb) {
        int err;
        switch (state.get()) {
            case TCP_CLOSE_WAIT:
                err = EPIPE;
                break;
            case TCP_CLOSE:
                return;
            default:
                err = ECONNRESET;
        }
        tcp_done_with_error(err);
    }

    /**
     * Process the FIN bit.
     */
    private void tcp_fin() {
        inet_csk_schedule_ack();

        sk_shutdown |= RCV_SHUTDOWN;

        final State state = this.state.get();
        switch (state) {
            case TCP_SYN_RECV:
            case TCP_ESTABLISHED:
                /* Move to CLOSE_WAIT */
                this.state.set(State.TCP_CLOSE_WAIT);
                inet_csk_enter_pingpong_mode();

                // FIXME
                if (null != child && child.isOpen()) {
                    child.disconnect();
                } else {
                    shutdown(SEND_SHUTDOWN);
                }

                break;
            case TCP_CLOSE_WAIT:
            case TCP_CLOSING:
                /* Received a retransmission of the FIN, do nothing. */
                break;
            case TCP_LAST_ACK:
                /* RFC793: Remain in the LAST-ACK state. */
                break;
            case TCP_FIN_WAIT1:
                /*-
                 * This case occurs when a simultaneous close
                 * happens, we must ack the received FIN and
                 * enter the CLOSING state.
                 */
                tcp_send_ack();
                this.state.set(State.TCP_CLOSING);
                break;
            case TCP_FIN_WAIT2:
                /* Received a FIN -- send ACK and enter TIME_WAIT. */
                tcp_send_ack();
                // tcp_time_wait(sk, State.TCP_TIME_WAIT, 0);
                break;
            default:
                /* Only TCP_LISTEN and TCP_CLOSE are left, in these
                 * cases we should never reach this piece of code.
                 */
                log.error("tcp_fin(): Impossible, sk->sk_state={}", state);
                break;
        }

        // TODO
    }

    void tcp_rcv_spurious_retrans(final TcpPacket skb) {

    }

    private void tcp_queue_rcv(final TcpPacket skb) {
        // https://www.cnblogs.com/wanpengcoder/p/11752122.html
        tcp_rcv_nxt_update(determineEndSeq(skb));
    }

    /**
     * @param skb
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L5229">tcp_input.c</a>
     */
    private void tcp_data_queue(final TcpPacket skb) throws IOException {
        final TcpHeader hdr = skb.getHeader();
        final int seq = hdr.getSequenceNumber();
        final int endSeq = determineEndSeq(skb);

        if (seq == endSeq) {
            return;
        }

        if (seq == rcv_nxt) {
            if (tcp_receive_window() == 0) {
                final int len = skb.length() - hdr.length();
                if (!(0 >= len && hdr.getFin())) {
                    out_of_window(skb, SKB_DROP_REASON_TCP_ZEROWINDOW);
                    return;
                }
            }

            /* Ok. In sequence. In window. */
            queue_and_out(skb);
        } else if (!after(endSeq, rcv_nxt)) {
            tcp_rcv_spurious_retrans(skb);
            /* A retransmit, 2nd most common case.  Force an immediate ack. */
            out_of_window(skb, SKB_DROP_REASON_TCP_OLD_DATA);
        } else if (!before(seq, rcv_nxt + tcp_receive_window())) {
            /* Out of window. F.e. zero window probe. */
            out_of_window(skb, SKB_DROP_REASON_TCP_OVERWINDOW);
        } else if (before(seq, rcv_nxt)) {
            /* Partial packet, seq < rcv_next < end_seq */
            if (tcp_receive_window() == 0) {
                out_of_window(skb, SKB_DROP_REASON_TCP_ZEROWINDOW);
            } else {
                // goto queue_and_out
                queue_and_out(skb);
            }
        } else {
            tcp_data_queue_ofo(skb);
        }
    }

    // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L5739
    private void tcp_check_space() {
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L5745">tcp_data_snd_check</a>
     */
    private void tcp_data_snd_check() {
        tcp_push_pending_frames();
        tcp_check_space();
    }

    private void __tcp_ack_snd_check() {
        if (tcp_in_quickack_mode() || 0 != (icsk_ack_pending & ICSK_ACK_NOW)) {
            tcp_send_ack();
            return;
        }

        tcp_send_delayed_ack();
    }

    private void tcp_ack_snd_check(final IpHeader ipHdr, final TcpPacket skb) {
        if (!inet_csk_ack_scheduled()) {
            /* We sent a data segment already. */
            return;
        }
        __tcp_ack_snd_check();
    }

    private boolean tcp_reset_check(final TcpPacket skb) {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L5939
        int seq = skb.getHeader().getSequenceNumber();
        return seq == rcv_nxt - 1 && 0 != ((1 << state.get().ordinal()) | (TCPF_CLOSE_WAIT | TCPF_LAST_ACK | TCPF_CLOSING));
    }

    // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L5957
    private boolean tcp_validate_incoming(final TcpPacket skb) {
        final TcpHeader hdr = skb.getHeader();
        if (hdr.getRst()) {
            if (hdr.getSequenceNumber() == rcv_nxt || tcp_reset_check(skb)) {
                // reset
                tcp_reset(skb);
                return false;
            }
        }
        return true;
    }

    private void tcp_rcv_established(final TcpPacket skb) throws IOException {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L6110

        // step5
        tcp_ack(skb, 0);

        /* step 7: process the segment text */
        tcp_data_queue(skb);

        tcp_data_snd_check();
        // tcp_ack_snd_check();
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L6299">tcp_init_transfer</a>
     */
    private void tcp_init_transfer(TcpPacket skb) {
        tcp_mtup_init();
        tcp_init_metrics();

        tcp_snd_cwnd_set(tcp_init_cwnd());

        snd_cwnd_stamp = tcp_jiffies32();

        tcp_init_congestion_control();

        child.config().setAutoRead(true);
    }

    /**
     * @param skb
     * @return error code
     * @throws IOException
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L6743">tcp_rcv_state_process</a>
     */
    protected int tcp_rcv_state_process(final IpHeader ih, final TcpPacket skb) throws IOException {
        final TcpHeader th = skb.getHeader();

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

                if (th.getSyn()) {
                    /*-
                     * SYN-FIN 应该被忽略.
                     */
                    if (th.getFin()) {
                        return discard(skb, SKB_DROP_REASON_TCP_FLAGS);
                    }

                    /*-
                     * 创建状态为TCP_NEW_SYN_RECV的请求套接字(request_sock)放入半连接队列.
                     */
                    final boolean accept = conn_request(ih, skb);
                    if (!accept) {
                        return SKB_DROP_REASON_NO_SOCKET;
                    }

                    /*-
                     * 收到ACK请求后，查找半连接队列, 如果查找到状态为 , 调用 tcp_check_req, 转换为 TCP_SYN_RECV, 完成握手, 迁移到全连接队列.
                     */
                    tcp_check_req(skb);

                    // FIXME 移动到连接打开时
                    state.set(State.TCP_SYN_RECV);
                    return SKB_DROP_REASON_NOT_SPECIFIED;
                }

                /*-
                 * !ACK & !RST & !SYN.
                 */
                return discard(skb, SKB_DROP_REASON_TCP_FLAGS);
            case TCP_SYN_SENT:
                /*-
                 * not-supported FIXME.
                 */
                return SKB_DROP_REASON_TCP_FLAGS;
        }

        /*-
         * 刷新最近发送/接收时间戳.
         */
        tcp_mstamp_refresh();

        if (!th.getAck() && !th.getRst() && !th.getSyn()) {
            return discard(skb, SKB_DROP_REASON_TCP_FLAGS);
        }

        if (!tcp_validate_incoming(skb)) {
            return SKB_DROP_REASON_NOT_SPECIFIED;
        }

        if (State.TCP_SYN_RECV.equals(state.get())) {
//            tcp_check_req(skb);
        }

        /* step 5: check the ACK field */
        int reason = tcp_ack(skb, FLAG_SLOWPATH | FLAG_UPDATE_TS_RECENT | FLAG_NO_CHALLENGE_ACK);
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
                lsndtime = tcp_jiffies32();
                tcp_initialize_rcv_mss();

                break;
            case TCP_FIN_WAIT1:
                if (snd_una != write_seq) {
                    break;
                }
                state.set(State.TCP_FIN_WAIT2);
                sk_shutdown |= SEND_SHUTDOWN;

                int seq = th.getSequenceNumber();
                int end_seq = determineEndSeq(skb);
                if (end_seq != seq && after(end_seq - (th.getSyn() ? 1 : 0), rcv_nxt)) {
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
                if (!before(th.getSequenceNumber(), rcv_nxt)) {
                    break;
                }
                // fallthrough
            case TCP_FIN_WAIT1:
            case TCP_FIN_WAIT2:
                // XXX
                // fallthrough
            case TCP_ESTABLISHED:
                tcp_data_queue(skb);
                break;
        }

        if (!State.TCP_CLOSE.equals(state.get())) {
            tcp_data_snd_check();
            tcp_ack_snd_check(ih, skb);
        }

        return SKB_DROP_REASON_NOT_SPECIFIED;
    }

    private void queue_and_out(final TcpPacket skb) throws IOException {
        final TcpHeader hdr = skb.getHeader();
        // queue_and_out;

        inet_csk_schedule_ack();

        sk_data_ready();
        if (skb.length() - hdr.length() > 0) {
            consume(skb);
        }

        tcp_queue_rcv(skb);

        if (skb.length() - hdr.length() > 0) {
            tcp_event_data_recv(skb);
        }

        if (hdr.getFin()) {
            tcp_fin();
        }
    }

    private void out_of_window(final TcpPacket skb, final int reason) {
        tcp_enter_quickack_mode(TCP_MAX_QUICKACKS);
        inet_csk_schedule_ack();
        drop(skb, reason);
    }

    private void drop(final TcpPacket skb, final int reason) {
        // tcp_drop_reason(sk, skb, reason);
    }

    private int discard(final TcpPacket skb, final int reason) {
        return 0;
    }


    private long tcp_clock_ns() {
        return System.nanoTime();
    }

    private long tcp_clock_us() {
        return TimeUnit.NANOSECONDS.toMicros(tcp_clock_ns());
    }

    private long tcp_clock_ms() {
        return TimeUnit.NANOSECONDS.toMillis(tcp_clock_ns());
    }

    private int ALIGN(int x, int a) {
        return (((x) + ((a) - 1)) & ~((a) - 1));
    }

    private boolean time_after(long a, long b) {
        return (b - a) < 0;
    }

    private boolean time_after_eq(long a, long b) {
        return (a - b) >= 0;
    }

    private boolean time_before(long a, long b) {
        return time_after(b, a);
    }

    private boolean time_before_eq(long a, long b) {
        return time_after_eq(b, a);
    }
}