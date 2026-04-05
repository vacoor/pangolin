package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.*;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.sock.TcpSock;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.sock.tcp_request_sock;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpClock;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpOptionCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.core.TcpDemultiplexer.*;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.core.TcpInput.tcp_rearm_rto;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.core.TcpTimer.*;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpClock.tcp_clock_ms;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpClock.tcp_clock_ns;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpClock.tcp_clock_us;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpClock.tcp_jiffies32;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpConstants.*;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpState.TCP_CLOSE;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpUtils.*;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.sock.TcpSock.IP_HEADER_SIZE;
import static com.sun.jna.platform.linux.ErrNo.EAGAIN;
import static com.sun.jna.platform.linux.ErrNo.EINVAL;

/**
 * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c">tcp_output.c</a>
 */
@Slf4j
public class TcpOutput {

    private final TcpDemultiplexer demultiplexer;

    public TcpOutput(TcpDemultiplexer demultiplexer) {
        this.demultiplexer = demultiplexer;
    }

    /**
     * Refresh clocks of a TCP socket,
     * ensuring monotically increasing values.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L55">tcp_mstamp_refresh</a>
     */
    static void tcp_mstamp_refresh(final TcpSock tp) {
        final long ns = tcp_clock_ns();
        tp.tcp_clock_cache = ns;
        tp.tcp_mstamp = TimeUnit.NANOSECONDS.toMicros(ns);
    }

    /**
     * Account for new data that has been sent to the network.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L67">tcp_event_new_data_sent</a>
     */
    private void tcp_event_new_data_sent(final Channel net, final TcpSock tp, TcpBuffer skb) {
        final int prior_packets = tp.packets_out;

        tp.snd_nxt = determineEndSeq(skb);

        // __skb_unlink(skb, &sk->sk_write_queue);
        // tcp_rbtree_insert(&sk->tcp_rtx_queue, skb);

        tp.sk_write_queue.remove(skb);
        tp.tcp_rtx_queue.offer(skb);

        tp.packets_out += tcp_skb_pcount(skb);

        if (prior_packets <= 0 || tp.icsk_pending == ICSK_TIME_LOSS_PROBE) {
            tcp_rearm_rto(tp, demultiplexer.timer);
        }

        // tp.input.tcp_check_space();
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
    private int tcp_acceptable_seq(final TcpSock tp) {
        if (!before(tp.tcp_wnd_end(), tp.snd_nxt)
                || (tp.rx_opt.wscale_ok && tp.snd_nxt - tp.tcp_wnd_end() < (1 << tp.rx_opt.rcv_wscale))) {
            return tp.snd_nxt;
        }
        return tp.tcp_wnd_end();
    }


    /**
     * Congestion state accounting after a packet has been sent.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L163">tcp_event_data_sent</a>
     */
    private void tcp_event_data_sent(final TcpSock tp) {
        long now = tcp_jiffies32();

        tp.lsndtime = now;

        /*-
         * If it is a reply for ato after last received
         * packet, increase pingpong count.
         *
         * 如果本地对最后收到数据包的回复在ATO(ACK超时)之前,
         * 本地可能在快速发送数据, 增加 ping-pong 次数,
         * (后续延迟ACK使用最大容忍度的超时时间, 以便减少不必要的ACK发送更多数据).
         */
        if (now - tp.icsk_ack.lrcvtime < tp.icsk_ack.ato) {
            tp.inet_csk_inc_pingpong_cnt();
        }
    }


    /**
     * Account for an ACK we sent.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L182">tcp_event_ack_sent</a>
     */
    private void tcp_event_ack_sent(final TcpSock tp, final int rcv_nxt) {
        if (rcv_nxt != tp.rcv_nxt) {
            return;
        }

        /*-
         * 如果最后一个收到的包也 ACK 了, 快速ACK次数 - 1, 不需要再执行延迟 ACK.
         */
        tp.tcp_dec_quickack_mode();
        tp.inet_csk_clear_xmit_timer(demultiplexer.timer, TcpTimer.ICSK_TIME_DACK);
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
    void tcp_select_initial_window(final TcpSock tp,
                                   final int __space, final int mss,
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
        if (SysctlOptions.ipv4_sysctl_tcp_workaround_signed_windows) {
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
            space = Math.max(space, SysctlOptions.ipv4_sysctl_tcp_rmem_2);
            space = Math.max(space, SysctlOptions.sysctl_rmem_max);
            space = Math.min(space, window_clamp);
            rcv_wscale.set(clamp(_ilog2(space) - 15, 0, TCP_MAX_WSCALE));
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
    int tcp_select_window(final TcpSock tp) {
        int old_win = tp.rcv_wnd;
        int cur_win = tcp_receive_window(tp);
        int new_win = __tcp_select_window(tp);

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
            if (!SysctlOptions.ipv4_sysctl_tcp_shrink_window || 0 != tp.rx_opt.rcv_wscale) {
                /* Never shrink the offered window */
                new_win = align(cur_win, 1 << tp.rx_opt.rcv_wscale);
            }
        }

        tp.rcv_wnd = new_win;
        tp.rcv_wup = tp.rcv_nxt;

        /*-
         * Make sure we do not exceed the maximum possible
         * scaled window.
         */
        if (tp.rx_opt.rcv_wscale == 0 && SysctlOptions.ipv4_sysctl_tcp_workaround_signed_windows) {
            new_win = Math.min(new_win, TCP_MAX_WINDOW);
        } else {
            new_win = Math.min(new_win, U16_MAX << tp.rx_opt.rcv_wscale);
        }

        /* RFC1323 scaling applied */
        new_win >>= tp.rx_opt.rcv_wscale;

        /* If we advertise zero window, disable fast path. */
        if (new_win == 0) {
            // TODO
            // tp->pred_flags = 0;
            if (0 != old_win) {
                // NET_INC_STATS(net, LINUX_MIB_TCPTOZEROWINDOWADV);
                log.warn("LINUX_MIB_TCPTOZEROWINDOWADV");
            }
        } else if (old_win == 0) {
            // NET_INC_STATS(net, LINUX_MIB_TCPFROMZEROWINDOWADV);
            log.warn("LINUX_MIB_TCPFROMZEROWINDOWADV");
        }
        return new_win;
    }

    private TcpBuffer tcp_init_nondata_skb(TcpSock tp, int seq, int flags) {
        TcpBuffer skb = new TcpBuffer();
        skb.srcAddr(tp.ir_loc_addr);
        skb.dstAddr(tp.ir_rmt_addr);
        skb.srcPort(tp.ir_num);       // int
        skb.dstPort(tp.ir_rmt_port);  // int
        skb.sequenceNumber(seq);
        skb.syn(0 != (flags & TcpConstants.SYN));
        skb.ack(0 != (flags & TcpConstants.ACK));
        skb.fin(0 != (flags & TcpConstants.FIN));
        return skb;
    }

    /**
     * Build raw TCP options for SYN packets (client mode — currently unsupported).
     */
    private byte[] tcp_syn_options() {
        return null;
    }

    /**
     * Build raw TCP options for SYN-ACK.
     * Returns serialised byte[] ready for {@link TcpBuffer#rawOptions(byte[])}.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L902">tcp_synack_options</a>
     */
    private byte[] tcp_synack_options(TcpSock tp, tcp_request_sock req, int mss) {
        ByteBuf optBuf = Unpooled.buffer(24);
        try {
            int wscale = req.wscale_ok ? req.rcv_wscale : -1;
            TcpOptionCodec.writeSynOptions(optBuf, mss, wscale);
            if (req.tstamp_ok) {
                long tsval = tcp_time_stamp_req(req);
                long tsecr = req.ts_recent;
                TcpOptionCodec.writeTimestampOption(optBuf, tsval, tsecr);
            }
            byte[] bytes = new byte[optBuf.readableBytes()];
            optBuf.readBytes(bytes);
            return bytes;
        } finally {
            optBuf.release();
        }
    }

    /**
     * Compute the TSval for a SYN-ACK: local clock plus ts_off (conceals uptime).
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L771">tcp_time_stamp_raw</a>
     */
    private static long tcp_time_stamp_req(tcp_request_sock req) {
        long base = req.req_usec_ts ? tcp_clock_us() : tcp_clock_ms();
        return (base + req.ts_off) & 0xFFFFFFFFL;
    }

    /**
     * Compute the TSval for an established socket: local clock plus tsoffset.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L771">tcp_time_stamp_raw</a>
     */
    static long tcp_time_stamp(TcpSock tp) {
        long base = tp.tcp_usec_ts > 0 ? tcp_clock_us() : tcp_clock_ms();
        return (base + tp.tsoffset) & 0xFFFFFFFFL;
    }

    /**
     * Build raw TCP options for ESTABLISHED sockets.
     * Returns null if no options (e.g. Timestamps not negotiated).
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L978">tcp_established_options</a>
     */
    private byte[] tcp_established_options(TcpSock tp) {
        if (!tp.rx_opt.tstamp_ok) {
            return null;
        }
        ByteBuf optBuf = Unpooled.buffer(12);
        try {
            long tsval = tcp_time_stamp(tp);
            long tsecr = tp.rx_opt.ts_recent;
            TcpOptionCodec.writeTimestampOption(optBuf, tsval, tsecr);
            byte[] bytes = new byte[optBuf.readableBytes()];
            optBuf.readBytes(bytes);
            return bytes;
        } finally {
            optBuf.release();
        }
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
    private int __tcp_transmit_skb(final Channel net, final TcpSock tp, final TcpBuffer skb, final boolean clone, int rcv_nxt) {
        BUG_ON(null == skb || tcp_skb_pcount(skb) == 0);

        long prior_wstamp = tp.tcp_wstamp_ns;
        tp.tcp_wstamp_ns = Math.max(tp.tcp_wstamp_ns, tp.tcp_clock_cache);

        tp.skb_set_delivery_time(skb, tp.tcp_wstamp_ns, "SKB_CLOCK_MONOTONIC");

        if (clone) {
            // TODO
        }

        byte[] rawOptions;
        if (skb.syn()) {
            rawOptions = tcp_syn_options();
        } else {
            rawOptions = tcp_established_options(tp);
            /*-
             * Force a PSH flag on all (GSO) packets to expedite GRO flush
             * at receiver : This slightly improve GRO performance.
             * Note that we do not force the PSH flag for non GSO packets,
             * because they might be sent under high congestion events,
             * and in this case it is better to delay the delivery of 1-MSS
             * packets and thus the corresponding ACK packet that would
             * release the following packet.
             */
            if (tcp_skb_pcount(skb) > 1) {
                // TODO tcb->tcp_flags |= TCPHDR_PSH;
                skb.psh(true);
            }
        }

        // TODO

        // skb_set_dst_pending_confirm(skb, READ_ONCE(sk->sk_dst_pending_confirm));

        skb.srcAddr(tp.ir_loc_addr);
        skb.dstAddr(tp.ir_rmt_addr);

        skb.srcPort(tp.ir_num);
        skb.dstPort(tp.ir_rmt_port);

        skb.acknowledgmentNumber(rcv_nxt);

        // TODO URG ...

        if (!skb.syn()) {
            skb.window((short) tcp_select_window(tp));
        } else {
            /*
             * RFC1323: The window in SYN & SYN/ACK segments
             * is never scaled.
             */
            skb.window((short) Math.min(tp.rcv_wnd, U16_MAX));
        }

        skb.rawOptions(rawOptions);



        // tp.icsk_af_ops.send_check();
        Tcp4Demultiplexer._INDIRECT_CALL_INET(net, tp, skb);
        // tp.INDIRECT_CALL_INET.accept(skb);

        if (skb.ack()) {
            tcp_event_ack_sent(tp, rcv_nxt);
        }

        int len = skb.payloadLength();
        if (len > 0) {
            tcp_event_data_sent(tp);
            tp.data_segs_out += tcp_skb_pcount(skb);
            tp.bytes_sent += len;
        }

        tp.segs_out += tcp_skb_pcount(skb);

        /* Leave earliest departure time in skb->tstamp (skb->skb_mstamp_ns) */

        // TODO

        return 0;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1486">tcp_transmit_skb</a>
     */
    private int tcp_transmit_skb(final Channel net, final TcpSock tp, final TcpBuffer skb, final boolean clone) {
        return __tcp_transmit_skb(net, tp, skb, clone, tp.rcv_nxt);
    }

    /**
     * This routine just queues the buffer for sending.
     * <p>
     * NOTE: probe0 timer is not checked, do not forget tcp_push_pending_frames,
     * otherwise socket can stall.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1498">tcp_queue_skb</a>
     */
    private void tcp_queue_skb(final TcpSock tp, TcpBuffer skb) {
        tp.write_seq = determineEndSeq(skb);
        tp.sk_write_queue.offer(skb);
    }

    /**
     * Calculate MSS not accounting any TCP options.
     *
     * @param pmtu Path MTU
     * @return mss to use
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1755">__tcp_mtu_to_mss</a>
     */
    private int __tcp_mtu_to_mss(final TcpSock tp, final int pmtu) {
        // XXX icsk->icsk_af_ops->net_header_len
        final int net_header_len = IP_HEADER_SIZE;

        /*-
         * Calculate base mss without TCP options:
         * It is MMS_S - sizeof(tcphdr) of rfc1122
         */
        int mss_now = pmtu - net_header_len - SIZE_OF_TCP_HDR;

        /* Clamp it (mss_clamp does not include tcp options) */
        if (mss_now > tp.rx_opt.mss_clamp) {
            mss_now = tp.rx_opt.mss_clamp;
        }

        /* Now subtract optional transport overhead */
        mss_now -= tp.icsk_ext_hdr_len;

        /* Then reserve room for full set of TCP options and 8 bytes of data */
        // mss_now = max(mss_now, READ_ONCE(sock_net(sk)->ipv4.sysctl_tcp_min_snd_mss));
        mss_now = Math.max(mss_now, SysctlOptions.ipv4_sysctl_tcp_min_snd_mss);
        return mss_now;
    }

    /**
     * Calculate MSS. Not accounting for SACKs here.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1780">tcp_mtu_to_mss</a>
     */
    private int tcp_mtu_to_mss(final TcpSock tp, final int pmtu) {
        /* Subtract TCP options size, not including SACKs */
        return __tcp_mtu_to_mss(tp, pmtu) - (tp.tcp_header_len - SIZE_OF_TCP_HDR);
    }

    /**
     * MTU probing init per socket.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1802">tcp_output.c</a>
     */
    void tcp_mtup_init(final TcpSock sk) {
        // TODO
    }

    /**
     * This function synchronize snd mss to current pmtu/exthdr set.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1840"></a>
     */
    int tcp_sync_mss(TcpSock tp, int pmtu) {
        // XXX icsk->icsk_mtup.search_high

        int mss_now = tcp_mtu_to_mss(tp, pmtu);
        mss_now = tp.tcp_bound_to_half_wnd(mss_now);

        /* And store cached results */
        tp.icsk_pmtu_cookie = pmtu;

        // ... XXX icsk->icsk_mtup.enabled

        tp.mss_cache = mss_now;
        return mss_now;
    }

    /**
     * Compute the current effective MSS, taking SACKs and IP options,
     * and even PMTU discovery events into account.
     *
     * @return
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L1865">tcp_current_mss</a>
     */
    public int tcp_current_mss(final TcpSock tp) {
        int mss_now = tp.mss_cache;
        int mtu = tp.dst_mtu();
        if (mtu != tp.icsk_pmtu_cookie) {
            mss_now = tcp_sync_mss(tp, mtu);
        }

        byte[] opts = tcp_established_options(tp);
        int optLen = opts != null ? opts.length : 0;
        int header_len = optLen + SIZE_OF_TCP_HDR;
        if (header_len != tp.tcp_header_len) {
            int delta = header_len - tp.tcp_header_len;
            mss_now -= delta;
        }
        return mss_now;
    }

    /* Update snd_sml if this skb is under mss
     * Note that a TSO packet might end with a sub-mss segment
     * The test is really :
     * if ((skb->len % mss) != 0)
     *        tp->snd_sml = TCP_SKB_CB(skb)->end_seq;
     * But we can avoid doing the divide again given we already have
     *  skb_pcount = skb->len / mss_now
     */
    protected void tcp_minshall_update(TcpSock tp, int mss_now, TcpBuffer skb) {
        final int skbLen = skb.payloadLength();
        if (skbLen < tcp_skb_pcount(skb) * mss_now) {
            tp.snd_sml = determineEndSeq(skb);
        }
    }

    /**
     * Can at least one segment of SKB be sent right now, according to the
     * congestion window rules?  If so, return how many segments are allowed.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2086">tcp_cwnd_test</a>
     */
    private int tcp_cwnd_test(final TcpSock tp) {
        int in_flight = tp.tcp_packets_in_flight();
        int cwnd = tp.tcp_snd_cwnd();
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
    private boolean tcp_write_xmit(Channel net, TcpSock tp, int mss_now, final int nonagle, final int push_one) {
        int sent_pkts = 0;
        boolean is_cwnd_limited = false;
        boolean is_rwnd_limited = false;

        tcp_mstamp_refresh(tp);

        if (0 == push_one) {
            /* Do MTU probing. */
            final int mtu = tcp_mtu_probe();
            if (0 == mtu) {
                return false;
            } else if (mtu > 0) {
                sent_pkts = 1;
            }
        }

        TcpBuffer skb;
        final int max_segs = tcp_tso_segs(tp, mss_now);
        while (null != (skb = tp.tcp_send_head())) {
            // XXX tp->repair

            if (tcp_pacing_check(tp)) {
                break;
            }

            int cwnd_quota = tcp_cwnd_test(tp);
            if (0 == cwnd_quota) {
                if (push_one == 2) {
                    /* Force out a loss probe pkt. */
                    cwnd_quota = 1;
                } else {
                    break;
                }
            }

            cwnd_quota = Math.min(cwnd_quota, max_segs);

            skb.srcAddr(tp.ir_loc_addr);
            skb.dstAddr(tp.ir_rmt_addr);
            skb.srcPort(tp.ir_num);
            skb.dstPort(tp.ir_rmt_port);

            final int skbLen = skb.payloadLength();
            final int missing_bytes = cwnd_quota * mss_now - skbLen;
            if (missing_bytes > 0) {
                tcp_grow_skb(skb, missing_bytes);
            }

            final int tso_segs = tcp_set_skb_tso_segs(skb, mss_now);
            if (!tcp_snd_wnd_test(tp, skb, mss_now)) {
                is_rwnd_limited = true;
                break;
            }

            if (1 == tso_segs) {
                if (!tcp_nagle_test(skb, mss_now, tcp_skb_is_last(skb) ? nonagle : TCP_NAGLE_PUSH)) {
                    break;
                }
            } else {
                // FIXME ...
                if (0 == push_one && tcp_tso_should_defer(skb, is_cwnd_limited, is_rwnd_limited, max_segs)) {
                    break;
                }
            }

//            if (!tp.sk_write_queue.remove(skb)) {
//                continue;
//            }

            int limit = mss_now;
            if (tso_segs > 1 && !tcp_urg_mode(tp)) {
                limit = tcp_mss_split_point(skb, mss_now, cwnd_quota, nonagle);
            }

            if (skbLen > limit && tso_fragment(skb, limit, mss_now)) {
                break;
            }
            if (tcp_small_queue_check(skb, 0)) {
                break;
            }

            /*-
             * Argh, we hit an empty skb(), presumably a thread
             * is sleeping in sendmsg()/sk_stream_wait_memory().
             * We do not want to send a pure-ack packet and have
             * a strange looking rtx queue with empty packet(s).
             */
            if (skb.sequenceNumber() == determineEndSeq(skb)) {
                break;
            }

            // FIXME
            if (0 != tcp_transmit_skb(net, tp, skb, true)) {
                break;
            }

            /*-
             * Advance the send_head.  This one is sent out.
             * This call will increment packets_out.
             */
            tcp_event_new_data_sent(net, tp, skb);
            tcp_minshall_update(tp, mss_now, skb);
            sent_pkts += tcp_skb_pcount(skb);

            if (push_one != 0) {
                break;
            }
        }

        // XXX ...

        is_cwnd_limited |= (tp.tcp_packets_in_flight() >= tp.tcp_snd_cwnd());
        if (sent_pkts != 0 || is_cwnd_limited) {
            tcp_cwnd_validate(is_cwnd_limited);
        }
        if (sent_pkts != 0) {
            if (tcp_in_cwnd_reduction(tp)) {
                // tp.prr_out += sent_pkts;
            }
            /* Send one loss probe per tail loss episode. */
            if (push_one != 2) {
                // tcp_schedule_loss_probe(sk, false);
            }
            return false;
        }
        return tp.packets_out == 0 && !tp.sk_write_queue.isEmpty();
    }

    protected boolean tcp_in_cwnd_reduction(TcpSock tp) {
        return false;
    }

    private void tcp_cwnd_validate(boolean isCwndLimited) {

    }

    private boolean tcp_small_queue_check(TcpBuffer skb, int i) {
        return false;
    }

    private boolean tso_fragment(TcpBuffer skb, int limit, int mssNow) {
        return false;
    }

    private int tcp_mss_split_point(TcpBuffer skb, int mssNow, int cwndQuota, int nonagle) {
        return mssNow;
    }

    private boolean tcp_urg_mode(TcpSock tp) {
        return false;
    }

    private boolean tcp_tso_should_defer(TcpBuffer skb, boolean isCwndLimited, boolean isRwndLimited, int maxSegs) {
        return false;
    }

    private boolean tcp_skb_is_last(TcpBuffer skb) {
        // FIXME
        return false;
    }

    private boolean tcp_nagle_test(TcpBuffer skb, int mssNow, int nonagle) {
        /*-
         * Nagle rule does not apply to frames, which sit in the middle of the
         * write_queue (they have no chances to get new data).
         *
         * This is implemented in the callers, where they modify the 'nonagle'
         * argument based upon the location of SKB in the send queue.
         */
        if (0 != (nonagle & TCP_NAGLE_PUSH)) {
            return true;
        }

        // ...
        return true;
    }

    private int tcp_set_skb_tso_segs(TcpBuffer skb, int mssNow) {
        return 1;
    }

    private void tcp_grow_skb(final TcpBuffer skb, final int len) {

    }

    /* Does at least the first segment of SKB fit into the send window? */
    boolean tcp_snd_wnd_test(TcpSock tp, TcpBuffer skb, int cur_mss) {
        int end_seq = determineEndSeq(skb);
        int skb_len = skb.payloadLength();
        if (skb_len > cur_mss)
            end_seq = skb.sequenceNumber() + cur_mss;

        return !after(end_seq, tp.tcp_wnd_end());
    }

    private int tcp_tso_segs(TcpSock tp, int mss) {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2040
        // TODO
        return Integer.MAX_VALUE;
    }

    private boolean tcp_pacing_check(TcpSock tp) {
        if (tp.tcp_wstamp_ns <= tp.tcp_clock_cache) {
            return false;
        }
        return true;
    }

    /**
     * 接收窗口可用大小.
     * Compute the actual receive window we are currently advertising.
     * Rcv_nxt can be after the window if our peer push more data
     * than the offered window.
     *
     * @return the actual receive window
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L813">tcp_receive_window</a>
     */
    public int tcp_receive_window(TcpSock tp) {
        return Math.max(tp.rcv_wup + tp.rcv_wnd - tp.rcv_nxt, 0);
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L3084">__tcp_select_window</a>
     */
    int __tcp_select_window(final TcpSock sock) {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L3084
        TcpSock icsk = sock;
        TcpSock tp = sock;
        /*-
         * MSS for the peer's data.  Previous versions used mss_clamp
         * here.  I don't know if the value based on our guesses
         * of peer's MSS is better for the performance.  It's more correct
         * but may be worse for the performance because of rcv_mss
         * fluctuations.  --SAW  1998/11/1
         */
        int mss = icsk.icsk_ack.rcv_mss;
        int free_space = tcp_space(tp);
        int allowed_space = tcp_full_space(tp);

        int full_space = Math.min(tp.window_clamp, allowed_space);
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
            icsk.icsk_ack.quick = 0;

            if (tcp_under_memory_pressure()) {
                tcp_adjust_rcv_ssthresh(tp);
            }

            /*-
             * free_space might become our new window, make sure we don't
             * increase it due to wscale.
             *
             * FIXME 1. rounddown --> round_down, 2. 和 free_space >> rcv_wscale << rcv_wscale 什么区别 ??
             * free_space = round_down(free_space, 1 << rcv_wscale);
             */
            free_space = free_space >> tp.rx_opt.rcv_wscale << tp.rx_opt.rcv_wscale;

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

        if (free_space > tp.rcv_ssthresh) {
            free_space = tp.rcv_ssthresh;
        }

        /*-
         * Don't do rounding if we are using window scaling, since the
         * scaled window will not line up with the MSS boundary anyway.
         */
        int window;
        if (tp.rx_opt.rcv_wscale > 0) {
            window = free_space;

            /* Advertise enough space so that it won't get scaled away.
             * Import case: prevent zero window announcement if
             * 1<<rcv_wscale > mss.
             */
            window = align(window, (1 << tp.rx_opt.rcv_wscale));
        } else {
            window = tp.rcv_wnd;
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


    private boolean tcp_under_memory_pressure() {
        // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L274
        // Returning false eliminates premature receive-window suppression caused by
        // the old hard-coded true (which forced quick-ACK off and called
        // tcp_adjust_rcv_ssthresh on every packet).
        return false;
    }

    // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L1583
    private void tcp_adjust_rcv_ssthresh(final TcpSock tp) {
        __tcp_adjust_rcv_ssthresh(tp, tp.advmss << 2);
    }

    // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L1572
    private void __tcp_adjust_rcv_ssthresh(final TcpSock tp, int new_ssthresh) {
        tp.rcv_ssthresh = Math.min(tp.rcv_ssthresh, new_ssthresh);
        // ...
    }

    /**
     * Note: caller must be prepared to deal with negative returns.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L1560">tcp_space</a>
     */
    private int tcp_space(TcpSock tp) {
        /*
        接收缓冲区大小 - 待接收 - 已使用.
        return tcp_win_from_space(sk, READ_ONCE(sk->sk_rcvbuf) -
				  READ_ONCE(sk->sk_backlog.len) -
				  atomic_read(&sk->sk_rmem_alloc));
         */
        // OFO 队列是代理中唯一真实占用接收缓冲区的数据，对应 Linux sk_rmem_alloc。
        // ofo_queue_bytes 在 tcp_data_queue_ofo / tcp_ofo_queue / tcp_prune_ofo_queue 中维护。
        return Math.max(0, tcp_full_space(tp) - tp.ofo_queue_bytes);
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L1567">tcp_full_space</a>
     */
    int tcp_full_space(TcpSock tp) {
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
    public static final int TCP_DEFAULT_SCALING_RATIO = (1 << (TCP_RMEM_TO_WIN_SCALE - 1));

    private int __tcp_win_from_space(int scaling_ratio, int space) {
        long scaled_space = (long) space * scaling_ratio;
        return (int) (scaled_space >> TCP_RMEM_TO_WIN_SCALE);
    }


    private static void BUG_ON(final boolean expr) {
        if (expr) {
            throw new IllegalStateException("BUG ON");
        }
    }


    private int tcp_skb_pcount(TcpBuffer skb) {
        return 1;
    }

    /**
     * Push out any pending frames which were held back due to
     * TCP_CORK or attempt at coalescing tiny packets.
     * The socket must be locked by the caller.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L3005">__tcp_push_pending_frames</a>
     */
    public void __tcp_push_pending_frames(final Channel net, final TcpSock tp, int mss, int nonagle) {
        /*-
         * If we are closed, the bytes will have to remain here.
         * In time closedown will finish, we empty the write queue and
         * all will be happy.
         */
        if (TCP_CLOSE.equals(tp.state())) {
            return;
        }
        if (tcp_write_xmit(net, tp, mss, nonagle, 0)) {
            tp.tcp_check_probe_timer(demultiplexer.timer);
        }
    }


    /**
     * This retransmits one SKB.  Policy decisions and retransmit queue
     * state updates are done by the caller.  Returns non-zero if an
     * error occurred which prevented the send.
     *
     * @param skb
     * @param segs
     * @return
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L3321">__tcp_retransmit_skb</a>
     */
    private int __tcp_retransmit_skb(final Channel net, final TcpSock tp, final TcpBuffer skb, int segs) {
        // ...
        // start
        int seq = skb.sequenceNumber();

        if (before(seq, tp.snd_una) && skb.syn()) {
            skb.syn(false);
            skb.sequenceNumber(++seq);
        }

        if (before(seq, tp.snd_una)) {
            int end_seq = determineEndSeq(skb);
            if (before(end_seq, tp.snd_una)) {
                return -EINVAL;
            }
            // ... tcp_trim_head
        }

        // ... rebuild_header

        int cur_mss = tcp_current_mss(tp);
        int avail_wnd = tp.tcp_wnd_end() - skb.sequenceNumber();

        /* If receiver has shrunk his window, and skb is out of
         * new window, do not retransmit it. The exception is the
         * case, when window is shrunk to zero. In this case
         * our retransmit of one segment serves as a zero window probe.
         */
        if (avail_wnd <= 0) {
            if (skb.sequenceNumber() != tp.snd_una) {
                return -EAGAIN;
            }
            avail_wnd = cur_mss;
        }

        int len = cur_mss * segs;
        if (len > avail_wnd) {
            len = rounddown(avail_wnd, cur_mss);
            if (0 == len) {
                len = avail_wnd;
            }
        }

        int skbLen = skb.payloadLength();
        if (skbLen > len) {
            // TODO
            // fragment
        } else {
            // TODO
        }

        // ...

        segs = tcp_skb_pcount(skb);

        tp.total_retrans += segs;
        tp.bytes_retrans += skbLen;

        int err;
        if (false) {
            // ...
        } else {
            err = tcp_transmit_skb(net , tp, skb, true);
        }

        /* To avoid taking spuriously low RTT samples based on a timestamp
         * for a transmit that never happened, always mark EVER_RETRANS
         */
        skb.sacked |= TCPCB_EVER_RETRANS;

        return err;
    }

    // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L3448
    int tcp_retransmit_skb(final Channel net, final TcpSock tp, final TcpBuffer skb, int segs) {
        int err = __tcp_retransmit_skb(net, tp, skb, segs);
        if (0 == err) {
            skb.sacked |= TCPCB_RETRANS;
            tp.retrans_out += tcp_skb_pcount(skb);
        }

        /* Save stamp of the first (attempted) retransmit. */
        if (tp.retrans_stamp == 0) {
            tp.retrans_stamp = tp.tcp_skb_timestamp_ts(tp.tcp_usec_ts, skb);
        }

        if (tp.undo_retrans < 0) {
            tp.undo_retrans = 0;
        }
        tp.undo_retrans += tcp_skb_pcount(skb);
        return err;
    }

    /**
     * Send a FIN. The caller locks the socket for us.
     * We should try to send a FIN packet really hard, but eventually give up.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L3578">tcp_send_fin</a>
     */
    public void tcp_send_fin(final Channel net, final TcpSock tp) {
        final TcpBuffer skb = tcp_init_nondata_skb(tp, tp.write_seq, TcpConstants.ACK | TcpConstants.FIN);
        tcp_queue_skb(tp, skb);
        __tcp_push_pending_frames(net, tp, tcp_current_mss(tp), TCP_NAGLE_OFF);
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L3640">tcp_send_active_reset</a>
     */
    public void tcp_send_active_reset(final Channel net, final TcpSock tp, final String reason) {
        TcpBuffer skb = tcp_init_nondata_skb(tp, tcp_acceptable_seq(tp), TcpConstants.ACK | TcpConstants.RST);
        tcp_mstamp_refresh(tp);

        /* Send it off. */
        if (0 < tcp_transmit_skb(net, tp, skb, false)) {
            log.warn("LINUX_MIB_TCPABORTFAILED");
        }
        /* skb of trace_tcp_send_reset() keeps the skb that caused RST,
         * skb here is different to the troublesome skb, so use NULL
         */
        // trace_tcp_send_reset(null, reason);
    }

    // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L3708
    protected TcpBuffer tcp_make_synack(final TcpSock listeSock,
                                        final tcp_request_sock req,
                                        final TcpPacketBuf syn) {
        int mss = listeSock.tcp_mss_clamp(listeSock, listeSock.dst_metric_advmss());
        long now = tcp_clock_ns();

        final TcpBuffer current = new TcpBuffer()
                .syn(true).ack(true)
                .sequenceNumber(req.snt_isn)
                .acknowledgmentNumber(req.rcv_nxt)
                .window((short) Math.min(req.rsk_rcv_wnd, U16_MAX));

        listeSock.skb_set_delivery_time(current, now, "SKB_CLOCK_MONOTONIC");

        current.rawOptions(tcp_synack_options(listeSock, req, mss));

        return current;
    }

    private int tcp_delack_max(final TcpSock tp) {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L4172
        int _tcp_rto_min = tcp_rto_min(tp);
        int icsk_delack_max = tp.icsk_delack_max;
        int delack_from_rto_min = Math.max(_tcp_rto_min, 2) - 1;
        return Math.min(icsk_delack_max, delack_from_rto_min);
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L4183">tcp_send_delayed_ack</a>
     */
    void tcp_send_delayed_ack(final Channel net, final TcpSock tp) {
        long ato = tp.icsk_ack.ato;
        if (ato > TcpConstant.TCP_DELACK_MIN) {
            int max_ato = TcpConstants.HZ / 2;

            /*-
             * ping-pong 模式应该使用最大容忍度的超时时间.
             */
            if (tp.inet_csk_in_pingpong_model(tp) || 0 != (tp.icsk_ack.pending & TcpTimer.ICSK_ACK_PUSHED)) {
                max_ato = TcpConstant.TCP_DELACK_MAX;
            }

            /* Slow path, intersegment interval is "high". */

            /*-
             * If some rtt estimate is known, use it to bound delayed ack.
             * Do not use inet_csk(sk)->icsk_rto here, use results of rtt measurements
             * directly.
             * 如果存在 RTT 测量结果, 使用平滑 RTT (srtt_us >> 3) 作为超时时间.
             * Note: 为了避免浮点运算 srtt_us 是实际 SRTT 8 倍.
             */
            if (tp.srtt_us != 0) {
                int rtt = (int) Math.max(TcpClock.usecs_to_jiffies(tp.srtt_us >> 3), TcpConstant.TCP_DELACK_MIN);
                if (rtt < max_ato) {
                    max_ato = rtt;
                }
            }
            ato = Math.min(ato, max_ato);
        }

        ato = Math.min(ato, tcp_delack_max(tp));

        log.trace("Delay ACK Timeout = {}ms", ato);

        /* Stay within the limit we were given */
        long timeout = TcpClock.jiffies() + ato;

        /* Use new timeout only if there wasn't a older one earlier. */
        if ((tp.icsk_ack.pending & TcpTimer.ICSK_ACK_TIMER) != 0) {
            /* If delack timer is about to expire, send ACK now. */
            long icsk_delack_timeout = tp.icsk_delack_timeout();
            if (time_before_eq(icsk_delack_timeout, TcpClock.jiffies() + (ato >> 2))) {
                // send now.
                tcp_send_ack(net, tp);
                return;
            }

            if (!time_before(timeout, icsk_delack_timeout)) {
                timeout = icsk_delack_timeout;
            }
        }

        // XXX smp_store_release
        tp.icsk_ack.pending |= TcpTimer.ICSK_ACK_SCHED | TcpTimer.ICSK_ACK_TIMER;
        tp.icsk_ack.timeout = timeout;
        demultiplexer.timer.sk_reset_timer(tp, tp.icsk_delack_timer, timeout);
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L4237">__tcp_send_ack</a>
     */
    private void __tcp_send_ack(final Channel net, final TcpSock tp, final int rcv_nxt, int flags) {
        /* If we have been reset, we may not send again. */
        if (TCP_CLOSE.equals(tp.state())) {
            return;
        }

        /*-
         * We are not putting this on the write queue, so
         * tcp_transmit_skb() will set the ownership to this
         * sock.
         */
        // FIXME

        // ...
        TcpBuffer buf = tcp_init_nondata_skb(tp, tcp_acceptable_seq(tp), TcpConstants.ACK);

        /* Send it off, this clears delayed acks for us. */
        __tcp_transmit_skb(net, tp, buf, false, rcv_nxt);
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L4279">tcp_send_ack</a>
     */
    protected void tcp_send_ack(final Channel net, final TcpSock tp) {
        __tcp_send_ack(net, tp, tp.rcv_nxt, 0);
    }


    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L4295">tcp_xmit_probe_skb</a>
     */
    private int tcp_xmit_probe_skb(final Channel net, final TcpSock tp, int urgent, int mib) {
        /*-
         * Use a previous sequence.  This should cause the other
         * end to send an ack.  Don't queue or clone SKB, just
         * send it.
         */
        final int seq = tp.snd_una - (0 == urgent ? 1 : 0);
        final TcpBuffer skb = tcp_init_nondata_skb(tp, seq, ACK);
        return tcp_transmit_skb(net, tp, skb, false);
    }

    /**
     * Initiate keepalive or window probe from timer.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L4328">tcp_write_wakeup</a>
     */
    protected int tcp_write_wakeup(final Channel net, TcpSock tp, int mib) {
        if (TCP_CLOSE.equals(tp.state())) {
            return -1;
        }

        final TcpBuffer skb = tp.tcp_send_head();
        if (null != skb && skb.sequenceNumber() < tp.tcp_wnd_end()) {
            final int seq = skb.sequenceNumber();
            final int mss = tcp_current_mss(tp);
            int seg_size = tp.tcp_wnd_end() - seq;

            int end_seq = determineEndSeq(skb);
            if (before(tp.pushed_seq, end_seq)) {
                tp.pushed_seq = end_seq;
            }

            /* We are probing the opening of a window
             * but the window size is != 0
             * must have been a result SWS avoidance ( sender )
             */
            final int len = skb.payloadLength();
            if (seg_size < end_seq - seq || len > mss) {
                seg_size = Math.min(seg_size, mss);
                skb.psh(true);
                // FIXME
            } else if (0 == tcp_skb_pcount(skb)) {
                tcp_set_skb_tso_segs(skb, mss);
            }

            skb.psh(true);

            int err = tcp_transmit_skb(net, tp, skb, true);
            if (0 == err) {
                tcp_event_new_data_sent(net, tp, skb);
            }
            return err;
        } else {
            // XXX ????
            if (between(tp.snd_up, tp.snd_una + 1, tp.snd_una + 0xFFFF)) {
                tcp_xmit_probe_skb(net, tp, 1, mib);
            }
            return tcp_xmit_probe_skb(net, tp, 0, mib);
        }
    }

    /**
     * A window probe timeout has occurred.  If window is not closed send
     * a partial packet else a zero probe.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L4374">tcp_send_probe0</a>
     */
    protected void tcp_send_probe0(final Channel net, final TcpSock tp) {
        // LINUX_MIB_TCPWINPROBE
        int err = tcp_write_wakeup(net, tp, 0);

        if (tp.packets_out > 0 || tp.tcp_write_queue_empty()) {
            /* Cancel probe timer, if it is not required. */
            tp.icsk_backoff = 0;
            tp.icsk_probes_out = 0;
            tp.icsk_probes_tstamp = 0;
            return;
        }

        tp.icsk_probes_out++;
        long timeout;
        if (err <= 0) {
            if (tp.icsk_backoff < SysctlOptions.ipv4_sysctl_tcp_retries2) {
                tp.icsk_backoff++;
            }
            timeout = tp.tcp_probe0_when(tp.tcp_rto_max());
        } else {
            /* If packet was not sent due to local congestion,
             * Let senders fight for local resources conservatively.
             */
            timeout = TCP_RESOURCE_PROBE_INTERVAL;
        }

        timeout = tp.tcp_clamp_probe0_to_user_timeout(tp, timeout);
        tp.tcp_reset_xmit_timer(demultiplexer.timer, TcpTimer.ICSK_TIME_PROBE0, timeout, true);
    }


    public void tcp_send_loss_probe() {

    }

}