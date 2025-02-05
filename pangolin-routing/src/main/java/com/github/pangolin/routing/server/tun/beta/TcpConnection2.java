package com.github.pangolin.routing.server.tun.beta;

import com.github.pangolin.routing.handler.internal.server.support.SocketChannelFactory;
import com.github.pangolin.routing.server.fakedns.DnsEngine;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;
import org.pcap4j.packet.IpPacket.IpHeader;
import org.pcap4j.packet.*;
import org.pcap4j.packet.TcpPacket.TcpHeader;
import org.pcap4j.packet.TcpPacket.TcpOption;
import org.pcap4j.packet.namednumber.IpNumber;
import org.pcap4j.packet.namednumber.IpVersion;
import org.pcap4j.packet.namednumber.TcpPort;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
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

    private volatile long bytes_acked;
    private volatile long bytes_received;

    private volatile long max_window;


    private void tcp_ack_update_window(final TcpHeader tcpHdr, final int ack) {
        int nwin = tcpHdr.getWindowAsInt();
        if (!tcpHdr.getSyn()) {
            nwin <<= snd_wscale;
            if (nwin != snd_wnd) {
                snd_wnd = nwin;
            }
        }

        tcp_snd_una_update(ack);

        /*-
         * 慢启动阶段(slow-start phase): cwnd = cwnd + 1 (SMSS)
         * 拥塞避免阶段(congestion-avoidance phase): cwnd = cwnd + 1 (SMSS) / cwnd
         */
        cwnd = cwnd < ssthresh ? cwnd + sndMss : cwnd + sndMss / cwnd;

    }

    /* If we update tp->snd_una, also update tp->bytes_acked */
    private void tcp_snd_una_update(final int ack) {
        final int delta = ack - snd_una;
        bytes_acked += delta;
        snd_una = ack;
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

    private int user_mss;
    private int mss_clamp;
    private boolean wscale_ok;

    private int rcv_isn;
    private int rcv_wnd = 65535;
    private int rcv_wup;
    private int rcv_nxt;
    private int copied_seq;

    private byte rcv_wscale = 6;

    private byte snd_wscale;
    private int snt_isn;

    private int snd_una;
    private int snd_wnd;
    private int snd_nxt;
    private int snd_up;
    private int snd_sml;

    private int write_seq;
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
        tcp_ack(skb.getHeader(), 0);
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
        __tcp_push_pending_frames();
    }

    private void __tcp_push_pending_frames() {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L3005
        if (State.TCP_CLOSE.equals(state.get())) {
            return;
        }
        // FIXME
        if (tcp_write_xmit(1460, 0)) {
            tcp_check_probe_timer();
        }
    }

    private void tcp_check_probe_timer() {
        // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L1454
        if (packets_out <= 0 && icsk_pending <= 0) {
            tcp_reset_xmit_timer(ICSK_TIME_PROBE0, tcp_probe0_base(), TCP_RTO_MAX);
        }
    }

    private int icsk_rto;

    private long tcp_probe0_base() {
        // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L1438
        return Math.max(icsk_rto, TCP_RTO_MIN);
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

            int missing_bytes = cwnd_quota * mss_now - skb.build().length();
            if (missing_bytes > 0) {

            }

            //
            if (th.getSequenceNumber() == determineEndSeq(build)) {
                break;
            }
            if (!tcp_transmit_skb(build)) {
                break;
            }
            tcp_event_new_data_sent(build);
        }

        return packets_out <= 0 && !sk_write_queue.isEmpty();
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
     * @return
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1486">tcp_transmit_skb</a>
     */
    private boolean tcp_transmit_skb(final TcpPacket skb) {
        __tcp_transmit_skb(skb, rcv_nxt);
        return true;
    }

    private void __tcp_transmit_skb(final TcpPacket skb, int rcv_nxt) {
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
            sk_stop_timer(this::tcp_retransmit_timer);
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
            sk_reset_timer(this::tcp_retransmit_timer, icsk_timeout);
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
        final ScheduledFuture<?> nf = scheduler.schedule(timer, expires - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
        Future<?> future = timers.put(timer, nf);
        if (null != future && !future.isDone() && !future.isCancelled()) {
            future.cancel(false);
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
        // FIXME sk_reset_timer
        sk_reset_timer(this::tcp_delack_timer, timeout);
    }

    private void tcp_delack_timer() {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L358
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
            log.warn("NO TIME");
            return;
        }

        icsk_ack_pending &= ~ICSK_ACK_TIMER;
        if (inet_csk_ack_scheduled()) {
            // ...
            tcp_send_ack();
            // ...
        }
    }

    private void tcp_retransmit_timer() {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L529
        if (packets_out <= 0) {
            return;
        }
    }

    /*-

     */
    void inet_csk_schedule_ack() {
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

        __tcp_transmit_skb(current.build(), rcv_nxt);
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

    private void tcp_initialize_rcv_mss() {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L622
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
            child.closeFuture().addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(final ChannelFuture future) throws Exception {
                    if (future.isSuccess()) {
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
            e.printStackTrace();
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

    private void tcp_v4_send_synack(final IpHeader ipHdr, final TcpPacket skb) {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L1174
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L3708
        TcpPacket.Builder current = newPacket(skb.getHeader(), ipHdr.getSrcAddr(), ipHdr.getDstAddr())
                .syn(true).ack(true)
                .sequenceNumber(snt_isn)
                .acknowledgmentNumber(rcv_nxt)
                .window((short) Math.min(rcv_wnd, 65535));


        final List<TcpOption> options = Lists.newArrayList();
        options.add(new TcpMaximumSegmentSizeOption.Builder()
                .maxSegSize((short) (1500 - IP_HEADER_SIZE - TCP_HEADER_SIZE))
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

                .payloadBuilder(current)
                .build();

        trace(ipPacket.getHeader(), current.build(), false);

        parent.writeAndFlush(ipPacket);
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
     */
    void tcp_openreq_init(final TcpPacket skb) {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L7068
        final TcpHeader hdr = skb.getHeader();
        rcv_isn = hdr.getSequenceNumber();
        rcv_nxt = hdr.getSequenceNumber() + 1;
    }


    private void tcp_rcv_established(final TcpPacket skb) throws IOException {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L5292

        // step5
        tcp_ack(skb.getHeader(), 0);

        /* step 7: process the segment text */
        tcp_data_queue(skb);

        tcp_data_snd_check();
        // tcp_ack_snd_check();
    }

    private int icsk_retransmits;

    private void tcp_ack(final TcpHeader tcpHdr, final int flag) {
        int prior_snd_una = snd_una;
        int prior_packets_out = packets_out;
        final int ack = tcpHdr.getAcknowledgmentNumber();
        int prior_fack = snd_una; // FIXME

        if (ack < prior_snd_una) {
            // OLD ACK
            return;
        }

        /*-
         * If the ack includes data we haven't sent yet, discard
         * this segment (RFC793 Section 3.9).
         */
        else if (ack > snd_nxt) {
            //
            return;
        } else if (ack > prior_snd_una) {
//            flag |= FLAG_SND_UNA_ADVANCED;
            icsk_retransmits = 0;
        }

        tcp_ack_update_window(tcpHdr, ack);

        if (prior_packets_out <= 0) {
            // tcp_ack_probe();
            return;
        }

        // See if we can take anything off of the retransmit queue.
        tcp_clean_rtx_queue(prior_snd_una);
    }

    private int tcp_clean_rtx_queue(int prior_snd_una) {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3340
        TcpPacket.Builder skb;
        while (null != (skb = sk_rtx_queue.peek())) {
            TcpPacket tp = skb.build();
            final TcpHeader th = tp.getHeader();
            final int seq = th.getSequenceNumber();
            final int end_seq = determineEndSeq(tp);
            int acked_pcount  = 1;
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
                shutdown(SEND_SHUTDOWN);
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
        __tcp_push_pending_frames();
    }

    private void tcp_queue_skb(TcpPacket.Builder skb) {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1498
        // only for end enq
        skb.dstPort(tcpSrcPort).srcPort(tcpDstPort).srcAddr(ipHeader.getDstAddr()).dstAddr(ipHeader.getSrcAddr());
        write_seq = determineEndSeq(skb.build());
        sk_write_queue.offer(skb);
    }
}