package com.github.pangolin.routing.server.tun.beta;

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
import java.net.UnknownHostException;
import java.util.Arrays;
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
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class TcpConnection2 {
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

    private final short mtu = DEFAULT_MTU;

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
     *          (rcv.wup)                    (rcv.up + rcv.wnd)
     *
     */

    /**
     * Receive - window.
     */
    private int rcvWnd = 65535;

    /**
     * Receive - initialize sequence number.
     */
    private int rcvIsn;

    /**
     * Receive - next sequence number.
     */
    private int rcvNxt;

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

    /**
     * Send - initialize sequence number.
     */
    private int sndIsn;

    /**
     * Send - unacknowledged sequence number.
     */
    private int sndUna;

    /**
     * Send - next sequence number.
     */
    private int sndNxt;

    /**
     *
     */
    private int sndWnd;

    private byte sndWscale;

    private byte rcvWndShiftCount;

    private short sndMss = 1460;

    private int cwnd;

    /**
     * slow start threshold.
     */
    private int ssthresh = 65535;

    private boolean windowScaleEnabled = true;

    private IpHeader ipHeader;
    private TcpPort tcpSrcPort;
    private TcpPort tcpDstPort;

    private final AtomicReference<State> state = new AtomicReference<>(State.TCP_CLOSE);


    private ConcurrentLinkedQueue<TcpPacket.Builder> sk_write_queue = new ConcurrentLinkedQueue<>();
    private ConcurrentLinkedQueue<TcpPacket.Builder> sk_rtx_queue = new ConcurrentLinkedQueue<>();

    private ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, new ThreadFactory() {
        @Override
        public Thread newThread(final Runnable r) {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        }
    });


    private final Channel parent;
    private final DnsEngine dnsEngine;
    private final SocketChannelFactory socketChannelFactory;

    volatile Channel child;
    int connTimeoutMs = 30 * 1000;
    EventLoopGroup childGroup;

    protected TcpConnection2(final Channel parent, final EventLoopGroup childGroup, final DnsEngine dnsEngine, final SocketChannelFactory socketChannelFactory) {
        this.parent = parent;
        this.childGroup = childGroup;
        this.dnsEngine = dnsEngine;
        this.socketChannelFactory = socketChannelFactory;
        this.listen();
    }

    private void listen() {
        state.compareAndSet(State.TCP_CLOSE, State.TCP_LISTEN);
    }

    public synchronized void receive(final IpHeader ipHeader, final TcpPacket tcpPacket) {
        try {
//            receive0(ipHeader, tcpPacket);
            tcp_rcv_state_process(ipHeader, tcpPacket);
        } catch (final Throwable cause) {
//            exceptionCaught(tcpPacket.getHeader(), cause);
            cause.printStackTrace();
        }
    }


    /**
     * Compute the actual receive window we are currently advertising.
     * Rcv_nxt can be after the window if our peer push more data
     * than the offered window.
     *
     * @return
     */
    private int tcp_receive_window() {
        // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L813
        return (int) Math.max(rcv_wup + rcv_wnd - rcv_nxt, 0);
//        final long rcv_wup_u32 = 0xFFFFFFFFL & rcv_wup;
//        final long rcv_nxt_u32 = 0xFFFFFFFFL & rcv_nxt;
//        return (int) Math.max(rcv_wup_u32 + rcv_wnd - rcv_nxt_u32, 0);
    }

    private long bytes_acked;
    private long bytes_received;

    private volatile int max_window;


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
            if (nwin != snd_wnd) {
                log.warn("[Window Update] {} -> {}", snd_wnd, nwin);
                snd_wnd = nwin;
            }

            if (nwin > max_window) {
                max_window = nwin;
                tcp_sync_mss(icsk_pmtu_cookie);
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

    /**
     * @param ack
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3629">tcp_snd_sne_update</a>
     */
    private void tcp_snd_sne_update(int ack) {

    }

    private void tcp_rcv_nxt_update(final int seq) {
        final int delta = seq - rcv_nxt;
        bytes_received += delta;
        rcv_nxt = seq;
    }


    private TcpPacket.Builder newPacket(final TcpHeader header, final InetAddress srcAddr, final InetAddress dstAddr) {
        final TcpPacket.Builder builder = new TcpPacket.Builder();
        builder.srcAddr(dstAddr)
                .dstAddr(srcAddr)
                .srcPort(header.getDstPort())
                .dstPort(header.getSrcPort())
                .window((short) 65535)
//                .window((short)1)
                .paddingAtBuild(true)
                .correctLengthAtBuild(true)
                .correctChecksumAtBuild(true);

        return builder;
    }


    private void trace(final IpHeader ipHeader, final TcpPacket tcpPacket, boolean inbound) {
        final InetAddress srcAddr = ipHeader.getSrcAddr();
        final InetAddress dstAddr = ipHeader.getDstAddr();
        final TcpHeader tcpHeader = tcpPacket.getHeader();
        final String srcHostName = srcAddr.getHostAddress();
        final String dstHostName = dstAddr.getHostAddress();
        final int srcPort = tcpHeader.getSrcPort().valueAsInt();
        final int dstPort = tcpHeader.getDstPort().valueAsInt();

        final StringBuilder buff = new StringBuilder()
                .append(inbound ? srcHostName : dstHostName).append(":").append(srcPort)
                .append(" => ")
                .append(inbound ? dstHostName : srcHostName).append(":").append(dstPort);

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

        final int payloadLen = tcpPacket.length() - tcpHeader.length();
        buff.append(" Len=").append(payloadLen);

        /*
        final Packet payload = tcpPacket.getPayload();
        if (null != payload) {
            buff.append(" ").append(Bytes.toString(payload.getRawData()));
        }
        */

        log.info(buff.toString());
    }


    enum SkbDropReason {
        SKB_DROP_REASON_NOT_SPECIFIED,
        SKB_DROP_REASON_TCP_FLAGS,
        SKB_DROP_REASON_TCP_RESET,
        SKB_DROP_REASON_TCP_ZEROWINDOW,
        SKB_DROP_REASON_TCP_OLD_DATA,
        SKB_DROP_REASON_TCP_OVERWINDOW;


    }

    // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L243
    /* TCP initial congestion window as per rfc6928 */
    private static final int TCP_INIT_CWND = 10;

    private static final byte TCP_MAX_WSCALE = 14;

    private final boolean sysctl_tcp_window_scaling = true;

    /**
     * 用户定义的 MSS.
     * >= TCP_MIN_MSS
     * <= MAX_TCP_WINDOW
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

    private int rcv_isn;
    private int rcv_wnd = 65535;
    private int rcv_wup;
    private int rcv_nxt;
    private int copied_seq;

    private byte rcv_wscale = 6;

    private byte snd_wscale;
    private int snt_isn;

    /*-
     * https://github.com/torvalds/linux/blob/master/include/linux/tcp.h#L192
     */
    private int snd_una;
    /**
     * The window we expect to receive.
     */
    private int snd_wnd;
    private int snd_nxt;
    private int snd_up;
    private int snd_sml;

    /**
     * Sequence for window update.
     */
    private int snd_wl1;

    private int write_seq;

    /**
     * Last pushed seq, required to talk to windows.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/linux/tcp.h#L264">pushed_seq</a>
     */
    private int pushed_seq;

    private int packets_out;

    private int sk_shutdown;

    // https://www.cnblogs.com/wanpengcoder/p/11751763.html

    void tcp_v4_do_rcv(TcpPacket skb) throws IOException {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c
        // https://www.cnblogs.com/wanpengcoder/p/11750747.html

        if (State.TCP_ESTABLISHED.equals(state.get())) {
            tcp_rcv_established(skb);
            return;
        }

        tcp_rcv_state_process(null, skb);
    }

    /**
     * https://github.com/torvalds/linux/blob/master/include/net/dropreason-core.h.
     */
    public static final int SKB_NOT_DROPPED_YET = 0;

    private int tcp_header_len;

    /**
     * @param skb
     * @return
     * @throws IOException
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L6743">tcp_rcv_state_process</a>
     */
    boolean tcp_rcv_state_process(final IpHeader ipHdr, final TcpPacket skb) throws IOException {
        trace(ipHdr, skb, true);
        final TcpHeader th = skb.getHeader();

        switch (state.get()) {
            case TCP_CLOSE:
                // goto discard.
                break;
            case TCP_LISTEN:
                if (th.getAck()) {
                    return false;
                }

                if (th.getRst()) {
                    // goto discard
                    discard(skb, SkbDropReason.SKB_DROP_REASON_TCP_RESET);
                    return true;
                }

                if (th.getSyn()) {
                    if (th.getFin()) {
                        // goto discard.
                        discard(skb, SkbDropReason.SKB_DROP_REASON_TCP_FLAGS);
                        return true;
                    }

                    final boolean accept = conn_request(ipHdr, skb);
                    // 进入连接请求

                    // ???
                    state.set(State.TCP_SYN_RECV);
                    //
                    return true;
                }

                // goto discard;
                discard(skb, SkbDropReason.SKB_DROP_REASON_TCP_FLAGS);
                return true;
        }


        if (!th.getAck() && !th.getRst() && !th.getSyn()) {
            // discard
            discard(skb, SkbDropReason.SKB_DROP_REASON_TCP_FLAGS);
            return true;
        }


        if (!tcp_validate_incoming(skb)) {
            return false;
        }

        /* step 5: check the ACK field */
        tcp_ack(skb, 0);
//        tcp_ack(skb.getHeader(), FLAG_SLOWPATH);
        // check reason

        switch (state.get()) {
            case TCP_SYN_RECV:

                tcp_init_transfer(skb);

                // tcp_init_transfer (mtu, 拥塞控制)
                state.set(State.TCP_ESTABLISHED);

                /*-
                 * FIXME
                 *
                 * <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L6743">tcp_rcv_state_process</a> bushizheli
                 * <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L2179">tcp_v4_rcv‎</a> TCP_NEW_SYN_RECV
                 * <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c#L660">tcp_check_req</a>
                 * <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L1742">tcp_v4_syn_recv_sock</a>
                 * <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c#L518">tcp_create_openreq_child</a> <==
                 */
                rcv_wup = copied_seq = rcv_nxt = rcv_isn + 1;
                /* snd_up = */
                snd_sml = snd_una = snd_nxt = snd_up = snt_isn + 1;


                // tcp_init_xmit_timers

                write_seq = pushed_seq = snt_isn + 1;

//                snd_una = th.getAcknowledgmentNumber();
                snd_wnd = th.getWindow() << sndWscale;
                max_window = snd_wnd;

                tcp_header_len = 20;
                // mss_clamp =

                tcp_initialize_rcv_mss();
                child.config().setAutoRead(true);
                break;
            case TCP_FIN_WAIT1:
                if (snd_una != write_seq) {
                    break;
                }
                state.set(State.TCP_FIN_WAIT2);

                // if fin 重置 keepalive timer
                // else 等待超时切换到 WAIT2 ??
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
                    return true;
                }
                break;
        }

        /* step 6: check the URG bit */
//        tcp_urg(sk, skb, th);

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
            tcp_ack_snd_check(ipHdr, skb);
        }

        return true;
    }

    private void discard(final TcpPacket skb, final SkbDropReason reason) {
        //
    }

    private void tcp_init_transfer(TcpPacket skb) {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L6299
        tcp_snd_cwnd_set(tcp_init_cwnd());
        tcp_init_congestion_control();
    }

    private int snd_cwnd;

    private void tcp_snd_cwnd_set(final int cwnd) {
        // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L1317
        snd_cwnd = cwnd;
    }

    private int tcp_init_cwnd() {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L1001
        return TCP_INIT_CWND;
    }

    private void tcp_init_congestion_control() {
    }


    private static final int EPIPE = 32;
    private static final int ECONNRESET = 104;
    private static final int ETIMEOUT = 110;

    /**
     * @param skb
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L4530">tcp_reset</a>
     */
    void tcp_reset(final TcpPacket skb) {
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

    private void tcp_done_with_error(int err) {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L4515
        tcp_done();
    }

    private void tcp_done() {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp.c#L4848
        state.set(State.TCP_CLOSE);
        log.warn("DONE");
        // clear timer
        // destroy
        onDestroy();
    }

    protected void onDestroy() {

    }

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

    private boolean tcp_reset_check(final TcpPacket skb) {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L5939
        return false;
    }

    private void tcp_data_snd_check() {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L5745
        tcp_push_pending_frames();
        tcp_check_space();
    }

    private void tcp_push_pending_frames() {
        // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L2102
        __tcp_push_pending_frames(tcp_current_mss());
    }

    private void __tcp_push_pending_frames(int mss) {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L3005
        if (State.TCP_CLOSE.equals(state.get())) {
            return;
        }
        // FIXME
        if (tcp_write_xmit(mss, 0)) {
            tcp_check_probe_timer();
        }
    }

    private void tcp_check_probe_timer() {
        // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L1454
        // https://www.cnblogs.com/aiwz/p/6333260.html
        if (packets_out <= 0 && icsk_pending <= 0) {
            tcp_reset_xmit_timer(ICSK_TIME_PROBE0, tcp_probe0_base(), TCP_RTO_MAX);
        }
    }

    private int icsk_rto;

    private long tcp_probe0_base() {
        // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L1438
        return Math.max(icsk_rto, TCP_RTO_MIN);
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

    private void tcp_check_space() {
    }

    /**
     * @param mss_now
     * @param push_one
     * @return overflow window (continue push)
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2739">tcp_write_xmit</a>
     */
    private boolean tcp_write_xmit(int mss_now, final int push_one) {
        TcpPacket.Builder skb;
        while (null != (skb = sk_write_queue.peek())) {

            if (push_one != 0) {
                int mtu = tcp_mtu_probe();
                if (0 == mtu) {
                    return false;
                } else if (mtu > 0) {
//                    sent_pkts = 1;
                }
            }

            TcpPacket build = skb.build();
            TcpHeader th = build.getHeader();

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

            log.info("transmit MSS: {}", mss_now);
            int missing_bytes = cwnd_quota * mss_now - skb.build().length();
            if (missing_bytes > 0) {

            }

            //
            if (th.getSequenceNumber() == determineEndSeq(build)) {
                break;
            }
            if (0 != tcp_transmit_skb(build, true)) {
                break;
            }
            tcp_event_new_data_sent(build);
        }

        return packets_out <= 0 && !sk_write_queue.isEmpty();
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2434">tcp_mtu_probe</a>
     */
    private int tcp_mtu_probe() {
        // XXX NOT IMPLEMENTED.
        return -1;
    }

    /**
     * @return
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2086">tcp_cwnd_test</a>
     */
    private int tcp_cwnd_test() {
        int in_flight = tcp_packets_in_flight();
        int cwnd = tcp_snd_cwnd();
        if (in_flight >= cwnd) {
            return 0;
        }

        int halfcwnd = Math.max(cwnd >> 1, 1);
        return Math.min(halfcwnd, cwnd - in_flight);
    }

    private int tcp_packets_in_flight() {
        return 0;
    }

    /**
     * @param skb
     * @return err
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1486">tcp_transmit_skb</a>
     */
    private int tcp_transmit_skb(final TcpPacket skb, final boolean clone) {
        return __tcp_transmit_skb(skb, clone, rcv_nxt);
    }

    /**
     * @param skb
     * @param rcv_nxt
     * @return err code
     */
    private int __tcp_transmit_skb(final TcpPacket skb, final boolean clone, int rcv_nxt) {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1290
        // append options
        final IpHeader ipHdr = ipHeader;
        TcpHeader th = skb.getHeader();
        TcpPacket.Builder buf = skb.getBuilder()
                .srcAddr(ipHdr.getDstAddr())
                .dstAddr(ipHdr.getSrcAddr())
                .acknowledgmentNumber(rcv_nxt)
                .paddingAtBuild(true)
                .correctLengthAtBuild(true)
                .correctChecksumAtBuild(true);
        ;
        if (th.getSyn()) {
            /*
             * RFC1323: The window in SYN & SYN/ACK segments
             * is never scaled.
             */
            buf.window((short) Math.min(rcv_wnd, 65535));
            final List<TcpOption> options = Lists.newArrayList();
//            if (wscale_ok) {
            // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L902
            options.add(new TcpWindowScaleOption.Builder()
                    .shiftCount(rcv_wscale)
                    .correctLengthAtBuild(true)
                    .build());
//            }
            buf.options(options);
        } else {
            buf.window((short) tcp_select_window());
        }

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

        if (th.getAck()) {
            tcp_event_ack_sent(rcv_nxt);
        }
        return 0;
    }

    private void tcp_event_ack_sent(int rcv_nxt) {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L182
        if (rcv_nxt != this.rcv_nxt) {
            return;
        }

        inet_csk_clear_xmit_timer(ICSK_TIME_DACK);
    }

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

    private void inet_csk_clear_xmit_timer(int what) {
        // https://github.com/torvalds/linux/blob/master/include/net/inet_connection_sock.h#L195
        if (ICSK_TIME_RETRANS == what || ICSK_TIME_PROBE0 == what) {
            icsk_pending = 0;
            // stop icsk_retransmit_timer
            sk_stop_timer(this::tcp_write_timer);
        }
        if (ICSK_TIME_DACK == what) {
            icsk_ack_pending = 0;
            icsk_ack_retry = 0;
            sk_stop_timer(this::tcp_delack_timer);
        }
    }

    private void inet_csk_reset_xmit_timer(final int what, long when, long max_when) {
        if (when > max_when) {
            when = max_when;
        }
        if (what == ICSK_TIME_RETRANS || what == ICSK_TIME_PROBE0 ||
                what == ICSK_TIME_LOSS_PROBE || what == ICSK_TIME_REO_TIMEOUT) {
            icsk_pending = what;
            icsk_timeout = System.currentTimeMillis() + when;

            // FIXME reset icsk_retransmit_timer  timer
            sk_reset_timer(this::tcp_write_timer, icsk_timeout);
        } else if (what == ICSK_TIME_DACK) {
            icsk_ack_pending |= ICSK_ACK_TIMER;
            icsk_ack_timeout = System.currentTimeMillis() + when;
            // FIXME reset icsk_delack_timer  timer
            sk_reset_timer(this::tcp_delack_timer, icsk_ack_timeout);
        }
    }

    private void sk_stop_timer(Runnable timer) {
        Future<?> future = timers.remove(timer);
        if (null != future && !future.isDone() && !future.isCancelled()) {
            future.cancel(true);
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
        final ScheduledFuture<?> nf = scheduler.schedule(timer, expires - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
        Future<?> future = timers.put(timer, nf);
        if (null != future && !future.isDone() && !future.isCancelled()) {
            future.cancel(true);
        }
        return 0;
    }


    private int tcp_select_window() {
//https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L260
        int old_win = rcv_wnd;
        int cur_win = tcp_receive_window();
        int new_win = __tcp_select_window();

        if (new_win < cur_win) {
            // FIXME ...
        }

        rcv_wnd = new_win;
        rcv_wup = rcv_nxt;

        if (rcv_wscale == 0) {
            new_win = Math.min(new_win, 65535);
        } else {
            new_win = Math.min(new_win, 65535 << rcv_wscale);
        }

        /* RFC1323 scaling applied */
        new_win >>= rcv_wscale;
        return new_win;
    }

    private int __tcp_select_window() {
        return rcv_wnd;
    }

    private static final int HZ = 1000;

    // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L146
    private static final int TCP_RTO_MAX = 120 * HZ;
    private static final int TCP_RTO_MIN = HZ / 5;
    private int icsk_pending;
    private long icsk_timeout;

    private void tcp_event_new_data_sent(TcpPacket skb) {
        int prior_packets = packets_out;

        snd_nxt = determineEndSeq(skb);

        // unlink
        sk_rtx_queue.offer(skb.getBuilder());

        packets_out += 1;

        /*-
         *
         */
        if (prior_packets <= 0 || icsk_pending == ICSK_TIME_LOSS_PROBE) {
            tcp_rearm_rto();
        }
    }

    void tcp_rearm_rto() {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3261
        if (packets_out <= 0) {
            inet_csk_clear_xmit_timer(ICSK_TIME_RETRANS);
        } else {
            int rto = 100; // FIXME
            if (icsk_pending == ICSK_TIME_REO_TIMEOUT
                    || icsk_pending == ICSK_TIME_LOSS_PROBE) {
                // ???
            }
            tcp_reset_xmit_timer(ICSK_TIME_RETRANS, rto, TCP_RTO_MAX);
        }
    }

    private void tcp_reset_xmit_timer(final int what, long when, long max_when) {
        // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L1423
        long tcp_pacing_delay = 0; // FIXME
        inet_csk_reset_xmit_timer(what, when + tcp_pacing_delay, max_when);
    }


    private boolean tcp_tso_should_defer(final TcpPacket skb, int max_segs) {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2214

        TcpHeader hdr = skb.getHeader();
        int in_flight = 0;
        int mss_cache = 1460;
        int send_win = tcp_wnd_end() - hdr.getSequenceNumber();
        int cong_win = (tcp_snd_cwnd() - in_flight) * mss_cache;

        int limit = Math.min(send_win, cong_win);
        if (limit >= max_segs * mss_cache) {
            return false;
        }
        if (limit >= skb.length() - hdr.length()) {
            return false;
        }
        return true;
    }

    /**
     * @return
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L1312">tcp_snd_cwnd</a>
     */
    private int tcp_snd_cwnd() {
        // FIXME
        return snd_cwnd;
    }

    private int tcp_wnd_end() {
        return snd_una + snd_wnd;
    }

    private void tcp_ack_snd_check(final IpHeader ipHdr, final TcpPacket skb) {
        if (!inet_csk_ack_scheduled()) {
            /* We sent a data segment already. */
            return;
        }
        __tcp_ack_snd_check();
    }

    private void __tcp_ack_snd_check() {
        tcp_send_delayed_ack();
    }

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

    // ACK Timeout Offset.
    private long icsk_ack_ato = TCP_DELACK_MAX;
    private int icsk_ack_pending;
    private long icsk_ack_timeout;
    private int icsk_ack_retry;

    private static final int sysctl_tcp_pingpong_thresh = 1;
    private int icsk_ack_pingpong;

    private void tcp_send_delayed_ack() {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L4183
        long ato = icsk_ack_ato;
        if (ato > TCP_DELACK_MIN) {
            int max_ato = HZ / 2;

            // XXX
            if (0 != (icsk_ack_pending & ICSK_ACK_PUSHED)) {
                max_ato = TCP_DELACK_MAX;
            }

            ato = Math.min(ato, max_ato);
        }

        ato = Math.min(ato, tcp_delack_max());

        /* Stay within the limit we were given */
        long timeout = System.currentTimeMillis() + ato;

//        System.out.println(ato + " => " + timeout);

        /* Use new timeout only if there wasn't a older one earlier. */
        if ((icsk_ack_pending & ICSK_ACK_TIMER) != 0) {
            /* If delack timer is about to expire, send ACK now. */
            if (icsk_ack_timeout <= System.currentTimeMillis() + (ato >> 2)) {
                // send now.
                tcp_send_ack();
                return;
            }
            if (timeout > icsk_ack_timeout) {
                timeout = icsk_ack_timeout;
            }
        }

        // FIXME smp_store_release
        icsk_ack_pending |= ICSK_ACK_TIMER | ICSK_ACK_SCHED;

        icsk_ack_timeout = timeout;
        sk_reset_timer(this::tcp_delack_timer, timeout);
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
        if (icsk_ack_timeout > System.currentTimeMillis()) {
            // reset ??.
//            log.warn("RESCHEDULE");
            return;
        }

        icsk_ack_pending &= ~ICSK_ACK_TIMER;
        if (inet_csk_ack_scheduled()) {
            // ...
            tcp_send_ack();
            // ...
        }
    }

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
        if (icsk_timeout > System.currentTimeMillis()) {
            sk_reset_timer(this::tcp_write_timer, icsk_timeout);
        }

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

    private void tcp_retransmit_timer() {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L529
        if (packets_out <= 0) {
            return;
        }
    }

    private int sysctl_tcp_retries2 = 5;

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L386">tcp_probe_timer</a>
     */
    private void tcp_probe_timer() {
        final TcpPacket.Builder skb = tcp_send_head();
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
            icsk_probes_tstamp = System.currentTimeMillis();
        } else {
            final int user_timeout = icsk_user_timeout;
            if (user_timeout > 0 && System.currentTimeMillis() - icsk_probes_tstamp >= user_timeout) {
                tcp_write_err();
                return;
            }
        }

        int max_probes = sysctl_tcp_retries2;

        // ...

        if (icsk_probes_out >= max_probes) {
            tcp_write_err();
        } else {
            /* Only send another probe if we didn't close things up. */
            tcp_send_probe0();
        }

    }

    private int sk_err_soft;

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L75">tcp_write_err</a>
     */
    private void tcp_write_err() {
        tcp_done_with_error(sk_err_soft != 0 ? sk_err_soft : ETIMEOUT);
    }

    // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L160
    private static final long TCP_RESOURCE_PROBE_INTERVAL = HZ / 2;

    /**
     * A window probe timeout has occurred.  If window is not closed send
     * a partial packet else a zero probe.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L4374">tcp_send_probe0</a>
     */
    private void tcp_send_probe0() {
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

    private boolean tcp_write_queue_empty() {
        return sk_write_queue.isEmpty();
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

        final TcpPacket.Builder buf = tcp_send_head();
        final TcpPacket skb = null != buf ? buf.build() : null;
        if (null != skb && skb.getHeader().getSequenceNumber() < tcp_wnd_end()) {
            final int seq = skb.getHeader().getSequenceNumber();
            final int mss = tcp_current_mss();
            int seg_size = tcp_wnd_end() - seq;

            int end_seq = determineEndSeq(skb);
            if (pushed_seq < end_seq) {
                pushed_seq = end_seq;
            }

            /* We are probing the opening of a window
             * but the window size is != 0
             * must have been a result SWS avoidance ( sender )
             */
            final int len = skb.length() - skb.getHeader().length();
            if (seg_size < end_seq - seq || len > mss) {
                seg_size = Math.min(seg_size, mss);
                buf.psh(true);

                //
            }

            // ...

            buf.psh(true);

            TcpPacket build = buf.build();
            int err = tcp_transmit_skb(build, true);
            if (0 == err) {
                tcp_event_new_data_sent(build);
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
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L4295">tcp_xmit_probe_skb</a>
     */
    private int tcp_xmit_probe_skb(int urgent, int mib) {
        /*-
         * Use a previous sequence.  This should cause the other
         * end to send an ack.  Don't queue or clone SKB, just
         * send it.
         */
        final int seq = snd_una - (0 == urgent ? 1 : 0);
        TcpPacket skb = new TcpPacket.Builder()
                .sequenceNumber(seq)
                .ack(true)
                .srcAddr(ipHeader.getDstAddr())
                .dstAddr(ipHeader.getSrcAddr())
                .srcPort(tcpDstPort)
                .dstPort(tcpSrcPort)
                .build();
        return tcp_transmit_skb(skb, false);
    }

    /*-

     */
    private void inet_csk_schedule_ack() {
        // https://github.com/torvalds/linux/blob/master/include/net/inet_connection_sock.h#L177
        // 确保在适当的时候发送 ACK，以确认接收到数据
        icsk_ack_pending |= ICSK_ACK_SCHED;
    }

    private boolean inet_csk_ack_scheduled() {
        // https://github.com/torvalds/linux/blob/master/include/net/inet_connection_sock.h
        return 0 != (icsk_ack_pending & ICSK_ACK_SCHED);
    }

    private void inet_csk_enter_pingpong_mode() {
        // https://github.com/torvalds/linux/blob/master/include/net/inet_connection_sock.h#L329
        icsk_ack_pingpong = sysctl_tcp_pingpong_thresh;
    }

    private void inet_csk_exit_pingpong_mode() {
        // https://github.com/torvalds/linux/blob/master/include/net/inet_connection_sock.h#L335
        icsk_ack_pingpong = 0;
    }


    void tcp_send_ack() {
        __tcp_send_ack(rcv_nxt);
    }

    /**
     * @param rcv_nxt
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L4237">__tcp_send_ack</a>
     */
    void __tcp_send_ack(final int rcv_nxt) {
        //
        if (State.TCP_CLOSE.equals(state.get())) {
            return;
        }

        final int sndSeq = tcp_acceptable_seq();
        TcpPacket.Builder current = new TcpPacket.Builder()
                .ack(true)
                .sequenceNumber(sndSeq)
                .dstAddr(ipHeader.getSrcAddr())
                .srcAddr(ipHeader.getDstAddr())
                .dstPort(tcpSrcPort)
                .srcPort(tcpDstPort);

        __tcp_transmit_skb(current.build(), false, rcv_nxt);
    }

    /**
     * @return
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L97">tcp_acceptable_seq</a>
     */
    private int tcp_acceptable_seq() {
        if (tcp_wnd_end() >= snd_nxt
                || (wscale_ok && snd_nxt - tcp_wnd_end() < (1 << rcv_wscale))) {
            return snd_nxt;
        }
        return tcp_wnd_end();
    }

    private int tcp_delack_max() {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L4172
        int tcp_rto_min = 100;
        int icsk_delack_max = 500;
        int delack_from_rto_min = Math.max(tcp_rto_min, 2) - 1;
        return Math.min(icsk_delack_max, delack_from_rto_min);
    }

    private static final int TCP_MSS_DEFAULT = 536;
    private static final int TCP_MSS_MIN = 88;

    private int icsk_ack_rcv_mss;

    private void tcp_initialize_rcv_mss() {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L622
        int hint = Math.min(advmss, mss_cache);
        hint = Math.min(hint, rcv_wnd / 2);
        hint = Math.min(hint, TCP_MSS_DEFAULT);
        hint = Math.max(hint, TCP_MSS_MIN);
        icsk_ack_rcv_mss = hint;
    }


    /**
     * @param skb
     * @return
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L1722">tcp_v4_conn_request</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L7195">tcp_conn_request</a>
     */
    private boolean conn_request(final IpHeader ipHdr, final TcpPacket skb) {
        inet_reqsk_alloc(ipHdr, skb);

        tcp_parse_options(skb, false);

        tcp_openreq_init(skb);

        //  init_req
        ipHeader = ipHdr;
        tcpSrcPort = skb.getHeader().getSrcPort();
        tcpDstPort = skb.getHeader().getDstPort();
        // ...

        snt_isn = initSeq(null, skb.getHeader());

        // init rwin
        tcp_openreq_init_rwin(skb);

        // send_synack
        tcp_v4_send_synack(ipHdr, skb);

        return true;
    }

    private void inet_reqsk_alloc(IpHeader ipHeader, TcpPacket skb) {
//        if (true) {
//            return;
//        }
        final InetSocketAddress resolved = resolve(ipHeader.getDstAddr(), skb.getHeader().getDstPort().valueAsInt());
        log.info("{} Connecting...", resolved);
        final ChannelFuture cf = socketChannelFactory.open(resolved, connTimeoutMs, false, childGroup, new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
                try {
                    final ByteBuf buf = (ByteBuf) msg;
                    final byte[] payload = ByteBufUtil.getBytes(buf);

                    // tcp data len = tcp snd.mss - tcp options.len
                    // 超过 tcp data len 不切割, 会使用TSO功能通过网卡来分段.
                    final int dataMaxLen = sndMss;
                    for (int i = 0; i < payload.length - 1; i += dataMaxLen) {
                        final int maxEndIndex = i + dataMaxLen;
                        if (maxEndIndex >= payload.length) {
                            final UnknownPacket.Builder builder = UnknownPacket.newPacket(payload, i, payload.length - i).getBuilder();
                            tcp_sendmsg2(new TcpPacket.Builder().ack(true).psh(true).payloadBuilder(builder), true);
                        } else {
                            UnknownPacket.Builder builder = UnknownPacket.newPacket(payload, i, dataMaxLen).getBuilder();
                            tcp_sendmsg2(new TcpPacket.Builder().ack(true).psh(true).payloadBuilder(builder), false);
                        }
                    }
                    tcp_push_pending_frames();
//                    UnknownPacket.Builder builder = UnknownPacket.newPacket(payload, 0, payload.length).getBuilder();
//                    write(newPacket(header, src.getAddress(), dst.getAddress()).ack(true).psh(true).payloadBuilder(builder), true);
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
                        if (State.TCP_ESTABLISHED.equals(state.get())) {
                            // write now ??
//                            write0(newPacket(header, src.getAddress(), dst.getAddress()).rst(true), true);
//                            onDestroy();
                        }
                    }
                }
            });
        } catch (InterruptedException e) {
            log.info("{} Connection reset.", resolved, e.getMessage(), e);
        }
    }

    private InetSocketAddress resolve(InetAddress dst, final int port) {
        String addr = dst.getHostAddress();
        if ("198.18.0.201".equalsIgnoreCase(addr) || "198.18.0.1".equalsIgnoreCase(addr)) {
            try {
                return new InetSocketAddress(InetAddress.getByName("139.196.84.154"), port);
            } catch (UnknownHostException e) {
                e.printStackTrace();
            }
        }
        if (null != dnsEngine) {
            final String host = dnsEngine.resolve(dst.getAddress());
            if (null != host) {
                return InetSocketAddress.createUnresolved(host, port);
            }
        }
        return new InetSocketAddress(dst, port);
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

    private synchronized void tcp_sendmsg2(TcpPacket.Builder skb, boolean flush) {
        skb.sequenceNumber(write_seq);
        skb.dstPort(tcpSrcPort).srcPort(tcpDstPort);
        tcp_skb_entail(skb);
        final Packet.Builder builder = skb.getPayloadBuilder();
        if (null != builder) {
            write_seq += builder.build().length();
        }
        if (flush) {
            tcp_push_pending_frames();
        }
    }

    void tcp_skb_entail(TcpPacket.Builder skb) {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp.c#L676
        skb.sequenceNumber(write_seq);
        skb.ack(true);
        sk_write_queue.offer(skb);
    }

    //

    private void tcp_openreq_init_rwin(TcpPacket skb) {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_minisocks.c#L422
        // tcp_mss_clamp();
        int mss = 1460;

        tcp_select_initial_window();
    }

    private void tcp_select_initial_window() {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L207
//        rcv_wscale = 0;
//        if (wscale_ok) {
//            rcv_wscale =
//        }
    }

    private void tcp_v4_send_synack(final IpHeader ipHdr, final TcpPacket syn_skb) {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L1174
        final TcpPacket.Builder skb = tcp_make_synack(ipHdr, syn_skb);

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

                .payloadBuilder(skb)
                .build();

        trace(ipPacket.getHeader(), skb.build(), false);

        parent.writeAndFlush(ipPacket);
    }

    // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L3708
    private TcpPacket.Builder tcp_make_synack(final IpHeader ipHdr, final TcpPacket skb) {

        int mss = tcp_mss_clamp(dst_metric_advmss());

        TcpPacket.Builder current = newPacket(skb.getHeader(), ipHdr.getSrcAddr(), ipHdr.getDstAddr())
                .syn(true).ack(true)
                .sequenceNumber(snt_isn)
                .acknowledgmentNumber(rcv_nxt)
                .window((short) Math.min(rcv_wnd, 65535));


        final List<TcpOption> options = Lists.newArrayList();
        options.add(new TcpMaximumSegmentSizeOption.Builder()
                .maxSegSize((short) mss)
                .correctLengthAtBuild(true).build());

        if (wscale_ok) {
            // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L902
            options.add(TcpNoOperationOption.getInstance());
            options.add(new TcpWindowScaleOption.Builder()
                    .shiftCount(rcv_wscale)
                    .correctLengthAtBuild(true)
                    .build());
        }
        current.options(options);

        return current;
    }

    // https://github.com/torvalds/linux/blob/master/include/linux/tcp.h#L597
    int tcp_mss_clamp(final int mss) {
        return user_mss > 0 && user_mss < mss ? user_mss : mss;
    }

    int dst_metric_advmss() {
        // https://github.com/torvalds/linux/blob/master/include/net/dst.h#L182
        return 1500 - IP_HEADER_SIZE - TCP_HEADER_SIZE;
    }


    void tcp_parse_options(final TcpPacket skb, final boolean estab) {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L4183
        final TcpHeader hdr = skb.getHeader();
        for (final TcpOption option : hdr.getOptions()) {
            if (option instanceof TcpMaximumSegmentSizeOption && hdr.getSyn() && !estab) {
                int inMss = ((TcpMaximumSegmentSizeOption) option).getMaxSegSizeAsInt();
                if (inMss > 0) {
                    inMss = user_mss > 0 && user_mss < inMss ? user_mss : inMss;
                    mss_clamp = inMss;
                }
            } else if (option instanceof TcpWindowScaleOption && hdr.getSyn() && !estab && sysctl_tcp_window_scaling) {
                final byte wscale = ((TcpWindowScaleOption) option).getShiftCount();
                wscale_ok = true;
                snd_wscale = wscale > TCP_MAX_WSCALE ? TCP_MAX_WSCALE : wscale;
            }
        }
    }

    /**
     * @param skb
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L7068">tcp_openreq_init</a>
     * @see <a href="https://www.cnblogs.com/wanpengcoder/p/11751292.html">TCP MSS</a>
     */
    void tcp_openreq_init(final TcpPacket skb) {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L7068
        final TcpHeader hdr = skb.getHeader();
        rcv_isn = hdr.getSequenceNumber();
        rcv_nxt = hdr.getSequenceNumber() + 1;
        // req.mss = mss_clamp;
        //
    }


    private void tcp_rcv_established(final TcpPacket skb) throws IOException {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L5292

        // step5
        tcp_ack(skb, 0);

        /* step 7: process the segment text */
        tcp_data_queue(skb);

        tcp_data_snd_check();
        // tcp_ack_snd_check();
    }

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

    /**
     * Snd_una was changed (!= FLAG_DATA_ACKED).
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L97">FLAG_SND_UNA_ADVANCED</a>
     */
    private static final int FLAG_SND_UNA_ADVANCED = 0x400;
    private int icsk_retransmits;

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3904">tcp_ack</a>
     */
    private void tcp_ack(final TcpPacket skb, int flag) {
        final TcpHeader tcpHdr = skb.getHeader();
        final int prior_snd_una = snd_una;
        final int prior_packets_out = packets_out;
        final int ack_seq = tcpHdr.getSequenceNumber();
        final int ack = tcpHdr.getAcknowledgmentNumber();

        /*-
         * If the ack is older than previous acks
         * then we can probably ignore it.
         */
        if (ack < prior_snd_una) {
            return;
        }

        /*-
         * If the ack includes data we haven't sent yet, discard
         * this segment (RFC793 Section 3.9).
         */
        if (ack > snd_nxt) {
            // return -SKB_DROP_REASON_TCP_ACK_UNSENT_DATA;
            return;
        }

        if (ack > prior_snd_una) {
            flag |= FLAG_SND_UNA_ADVANCED;
            icsk_retransmits = 0;
        }

        // ...

        if (ack_seq != determineEndSeq(skb)) {
            flag |= FLAG_DATA;
        }

        flag |= tcp_ack_update_window(tcpHdr, ack, ack_seq);

        if (prior_packets_out <= 0) {
            /*-
             * If this ack opens up a zero window, clear backoff.  It was
             * being used to time the probes, and is probably far higher than
             * it needs to be for normal retransmission.
             */
            tcp_ack_probe();
            return;
        }

        // See if we can take anything off of the retransmit queue.
        flag |= tcp_clean_rtx_queue(prior_snd_una);
    }

    private int icsk_backoff;
    private int icsk_probes_out;
    private long icsk_probes_tstamp;

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3546">tcp_ack_probe</a>
     */
    private void tcp_ack_probe() {
        final TcpPacket.Builder head = tcp_send_head();

        /* Was it a usable window open? */
        if (null == head) {
            return;
        }
        if (determineEndSeq(head.build()) <= tcp_wnd_end()) {
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

    private static final long TCP_TIMEOUT_MIN = 2;
    private int icsk_user_timeout;

    /**
     * @param when
     * @return
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L49">tcp_clamp_probe0_to_user_timeout</a>
     */
    private long tcp_clamp_probe0_to_user_timeout(long when) {
        int user_timeout = icsk_user_timeout;
        if (0 == user_timeout || 0 == icsk_probes_tstamp) {
            return when;
        }
        long elapsed = System.currentTimeMillis() - icsk_probes_tstamp;
        if (elapsed < 0) {
            elapsed = 0;
        }
        long remaining = user_timeout - elapsed;
        remaining = Math.max(remaining, TCP_TIMEOUT_MIN);
        return Math.min(remaining, when);
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L2021">tcp_send_head</a>
     */
    private TcpPacket.Builder tcp_send_head() {
        return sk_write_queue.peek();
    }

    private int tcp_clean_rtx_queue(int prior_snd_una) {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3340
        TcpPacket.Builder skb;
        while (null != (skb = sk_rtx_queue.peek())) {
            TcpPacket tp = skb.build();
            final TcpHeader th = tp.getHeader();
            final int seq = th.getSequenceNumber();
            final int end_seq = determineEndSeq(tp);
            int acked_pcount = 1;
            if (end_seq > snd_una) {
                // FIXME
                break;
            }

            packets_out -= acked_pcount;
            /*
            if (!th.getSyn()) {
                flag |= FLAG_DATA_ACKED;
            } else {
                flag |= FLAG_SYN_ACKED;
            }
            */
            sk_rtx_queue.remove(skb);
        }

        if (snd_up <= prior_snd_una && prior_snd_una <= snd_una) {
            snd_up = snd_una;
        }
        return 0;
    }

    /**
     * @param skb
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L4572">tcp_input.c</a>
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
                    out_of_window(skb, SkbDropReason.SKB_DROP_REASON_TCP_ZEROWINDOW);
                    return;
                }
            }

            /* Ok. In sequence. In window. */
            queue_and_out(skb);
        } else if (endSeq <= rcv_nxt) {
            tcp_rcv_spurious_retrans(skb);
            /* A retransmit, 2nd most common case.  Force an immediate ack. */
            out_of_window(skb, SkbDropReason.SKB_DROP_REASON_TCP_OLD_DATA);
        } else if (seq >= rcv_nxt + tcp_receive_window()) {
            /* Out of window. F.e. zero window probe. */
            out_of_window(skb, SkbDropReason.SKB_DROP_REASON_TCP_OVERWINDOW);
        } else if (seq < rcv_nxt) {
            /* Partial packet, seq < rcv_next < end_seq */
            if (tcp_receive_window() == 0) {
                out_of_window(skb, SkbDropReason.SKB_DROP_REASON_TCP_ZEROWINDOW);
            } else {
                // goto queue_and_out
                queue_and_out(skb);
            }
        } else {
            tcp_data_queue_ofo(skb);
        }
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

    private void out_of_window(final TcpPacket skb, final SkbDropReason reason) {
//        tcp_enter_quickack_mode(sk, TCP_MAX_QUICKACKS);
        inet_csk_schedule_ack();
        drop(skb, reason);
    }

    private void drop(final TcpPacket skb, final SkbDropReason reason) {
        // tcp_drop_reason(sk, skb, reason);
    }


    private void tcp_queue_rcv(final TcpPacket skb) {
        // https://www.cnblogs.com/wanpengcoder/p/11752122.html
        tcp_rcv_nxt_update(determineEndSeq(skb));
    }

    private void tcp_event_data_recv(final TcpPacket skb) throws IOException {
    }

    private void tcp_fin() {
        inet_csk_schedule_ack();

        final State state = this.state.get();
        switch (state) {
            case TCP_SYN_RECV:
            case TCP_ESTABLISHED:
                /* Move to CLOSE_WAIT */
                this.state.set(State.TCP_CLOSE_WAIT);
                inet_csk_enter_pingpong_mode();
                // FIXME
                if (null != child || child.isOpen()) {
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
        }
    }

    void tcp_rcv_spurious_retrans(final TcpPacket skb) {

    }

    void tcp_data_queue_ofo(final TcpPacket skb) {

    }


    void sk_data_ready() {

    }

    void consume(final TcpPacket skb) throws IOException {
        final TcpHeader hdr = skb.getHeader();
        final int seq = hdr.getSequenceNumber();
        byte[] bytes = skb.getPayload().getRawData();

        final int from = rcv_nxt - seq;
        final int to = Math.min(from + tcp_receive_window(), bytes.length);
        if (0 != from || to != bytes.length) {
            bytes = Arrays.copyOfRange(bytes, from, to);
        }
        connectionRead(bytes);
    }

    private void connectionRead(final byte[] bytes) throws IOException {
        if (null == child || !child.isOpen()) {
            throw new IOException("Remote already closed");
        }
        child.writeAndFlush(Unpooled.wrappedBuffer(bytes));
    }

    /**
     * @param ipHdr
     * @param tcpHdr
     * @return
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L103">tcp_v4_init_seq</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/core/secure_seq.c#L136">secure_tcp_seq</a>
     */
    private int initSeq(final IpHeader ipHdr, final TcpHeader tcpHdr) {
//        new SecureRandom().nextInt();
        return tcpHdr.getSequenceNumber();
    }


    /**
     * @param skb
     * @return
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c">tcp_ipv4.c</a>
     */
    private int determineEndSeq(final TcpPacket skb) {
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

    private boolean before(final int seq1, final int seq2) {
        return seq1 < seq2;
    }

    private boolean after(final int seq2, final int seq1) {
        return before(seq1, seq2);
    }

    // https://github.com/torvalds/linux/blob/master/include/net/sock.h#L1472
    private static final int RCV_SHUTDOWN = 1;
    private static final int SEND_SHUTDOWN = 2;
    private static final int SHUTDOWN_MASK = 2;

    private static final int TCPF_ESTABLISHED = 1 << State.TCP_ESTABLISHED.ordinal();
    private static final int TCPF_CLOSE_WAIT = 1 << State.TCP_CLOSE_WAIT.ordinal();
    private static final int TCPF_CLOSE = 1 << State.TCP_CLOSE.ordinal();
    private static final int TCPF_LISTEN = 1 << State.TCP_LISTEN.ordinal();

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

    private boolean tcp_close_state() {
        int next = new_state[state.get().ordinal() + 1];
        int ns = next & TCP_STATE_MASK;

        state.set(State.values()[ns]);
        return 0 != (next & TCP_ACTION_FIN);
    }


    void tcp_send_fin() {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L3578
        TcpPacket.Builder skb = new TcpPacket.Builder()
                .sequenceNumber(write_seq)
                .ack(true).fin(true);
        tcp_queue_skb(skb);
        __tcp_push_pending_frames(tcp_current_mss());
    }

    private void tcp_queue_skb(TcpPacket.Builder skb) {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1498
        // only for end enq
        skb.dstPort(tcpSrcPort).srcPort(tcpDstPort).srcAddr(ipHeader.getDstAddr()).dstAddr(ipHeader.getSrcAddr());
        write_seq = determineEndSeq(skb.build());
        sk_write_queue.offer(skb);
    }

    // No options.
    private static final int SIZE_OF_TCP_HDR = 20;


    private int icsk_pmtu_cookie;

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

    private List<TcpOption> tcp_established_options() {
        return Collections.emptyList();
    }


    // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1840
    private int tcp_sync_mss(int pmtu) {
        int mss_now;

        mss_now = tcp_mtu_to_mss(pmtu);
        mss_now = tcp_bound_to_half_wnd(mss_now);

        icsk_pmtu_cookie = pmtu;
        mss_cache = mss_now;
        return mss_now;
    }

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
     * Calculate MSS not accounting any TCP options.
     *
     * @param pmtu Path MTU
     * @return mss to use
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1755">__tcp_mtu_to_mss</a>
     */
    private int __tcp_mtu_to_mss(final int pmtu) {
        // FIXME icsk->icsk_af_ops->net_header_len
        final int net_header_len = 20;

        /*-
         * Calculate base mss without TCP options:
         * It is MMS_S - sizeof(tcphdr) of rfc1122
         */
        int mss_now = pmtu - net_header_len - SIZE_OF_TCP_HDR;
        if (mss_now > mss_clamp) {
            mss_now = mss_clamp;
        }

        /* FIXME Now subtract optional transport overhead */
        // mss_now -= icsk->icsk_ext_hdr_len;;

        /* Then reserve room for full set of TCP options and 8 bytes of data */
        // mss_now = max(mss_now, READ_ONCE(sock_net(sk)->ipv4.sysctl_tcp_min_snd_mss));
        return mss_now;
    }

    private int dst_mtu() {
        return 1500;
    }
}