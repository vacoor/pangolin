package com.github.pangolin.routing.server.tun.net.handler.tcp;

import com.google.common.collect.Maps;
import io.netty.util.concurrent.Future;
import org.pcap4j.packet.IpPacket;

import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import static com.github.pangolin.routing.server.tun.net.handler.tcp.TcpConnection.*;
import static com.github.pangolin.routing.server.tun.net.handler.tcp.TcpConstants.HZ;
import static com.github.pangolin.routing.server.tun.net.handler.tcp.TcpUtils.*;

class TcpTimer<T extends IpPacket> {
    private final TcpConnection<T> tp;

    TcpTimer(final TcpConnection<T> tp) {
        this.tp = tp;
    }

    /**
     * https://github.com/torvalds/linux/blob/master/include/net/inet_connection_sock.h#L144
     */
    /* Retransmit timer */
    static final int ICSK_TIME_RETRANS = 1;
    /* Delayed ack timer */
    static final int ICSK_TIME_DACK = 2;
    /* Zero window probe timer */
    static final int ICSK_TIME_PROBE0 = 3;
    /* Tail loss probe timer */
    static final int ICSK_TIME_LOSS_PROBE = 5;
    /* Reordering timer */
    static final int ICSK_TIME_REO_TIMEOUT = 6;

    Runnable icsk_retransmit_timer;
    Runnable icsk_delack_timer;
    private Runnable sk_timer;


    void tcp_init_xmit_timers() {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L883
        inet_csk_init_xmit_timers(
                this::tcp_write_timer,
                this::tcp_delack_timer,
                this::tcp_keepalive_timer
        );
    }

    // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L702
    void tcp_clear_xmit_timers() {
        inet_csk_clear_xmit_timers();
    }

    private void inet_csk_init_xmit_timers(Runnable icsk_retransmit_handler,
                                           Runnable icsk_delack_handler,
                                           Runnable keepalive_handler) {
        icsk_retransmit_timer = icsk_retransmit_handler;
        icsk_delack_timer = icsk_delack_handler;
        sk_timer = keepalive_handler;
        tp.icsk_pending = tp.icsk_ack_pending = 0;
    }

    // https://github.com/torvalds/linux/blob/master/net/ipv4/inet_connection_sock.c#L785
    private void inet_csk_clear_xmit_timers() {
        tp.icsk_pending = 0;
        tp.icsk_ack_pending = 0;

        sk_stop_timer(icsk_retransmit_timer);
        sk_stop_timer(icsk_delack_timer);
        sk_stop_timer(sk_timer);
    }


    // https://github.com/torvalds/linux/blob/master/include/net/inet_connection_sock.h#L218
    void inet_csk_reset_xmit_timer(final int what, long when, long max_when) {
        if (when > max_when) {
            when = max_when;
        }
        if (what == ICSK_TIME_RETRANS || what == ICSK_TIME_PROBE0 ||
                what == ICSK_TIME_LOSS_PROBE || what == ICSK_TIME_REO_TIMEOUT) {
            tp.icsk_pending = what;
            tp.icsk_timeout = jiffies() + when;

            sk_reset_timer(icsk_retransmit_timer, tp.icsk_timeout);
        } else if (what == ICSK_TIME_DACK) {
            tp.icsk_ack_pending |= ICSK_ACK_TIMER;
            tp.icsk_ack_timeout = jiffies() + when;
            sk_reset_timer(icsk_delack_timer, tp.icsk_ack_timeout);
        }
    }


    void sk_stop_timer(Runnable timer) {
        Future<?> future = timers.remove(timer);
        if (null != future && !future.isDone() && !future.isCancelled()) {
            future.cancel(false);
        }
    }

    void sk_reset_timer(Runnable timer, long expires) {
        // https://github.com/torvalds/linux/blob/master/net/core/sock.c#L3539
        mod_timer(timer, expires);
    }

    private int mod_timer(Runnable timer, long expires) {
        // https://github.com/torvalds/linux/blob/master/kernel/time/timer.c#L1235
        return __mod_timer(timer, expires, 0);
    }

    private final ConcurrentMap<Runnable, Future<?>> timers = Maps.newConcurrentMap();

    private int __mod_timer(Runnable timer, long expires, int options) {
//        io.netty.util.concurrent.ScheduledFuture<?> nf = parent.eventLoop().schedule(timer, expires - jiffies(), TimeUnit.MILLISECONDS);
        io.netty.util.concurrent.ScheduledFuture<?> nf = tp.child.eventLoop().schedule(timer, expires - jiffies(), TimeUnit.MILLISECONDS);
//        final ScheduledFuture<?> nf = scheduler.schedule(timer, expires - jiffies(), TimeUnit.MILLISECONDS);
        Future<?> future = timers.put(timer, nf);
        if (null != future && !future.isDone() && !future.isCancelled()) {
            future.cancel(false);
        }
        return 0;
    }

    /* *********** DELAY ACK [[ ************** */

    static final int TCP_DELACK_MIN = HZ / 25;
    static final int TCP_DELACK_MAX = HZ / 5;

    /**
     * <a href="https://github.com/torvalds/linux/blob/master/include/net/inet_connection_sock.h#L161">inet_csk_ack_state_t</a>
     */
    static final int ICSK_ACK_SCHED = 1;
    static final int ICSK_ACK_TIMER = 1 << 1;
    static final int ICSK_ACK_PUSHED = 1 << 2;
    static final int ICSK_ACK_PUSHED2 = 1 << 3;
    static final int ICSK_ACK_NOW = 1 << 4;
    static final int ICSK_ACK_NOMEM = 1 << 5;



    private void tcp_delack_timer() {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L358
        tcp_delack_timer_handler();
    }

    /**
     * @see <a href="https://www.cnblogs.com/wanpengcoder/p/11749449.html">TCP定时器 之 延迟确认定时器</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L307">tcp_delack_timer_handler</a>
     */
    private void tcp_delack_timer_handler() {
        final int state = tp.state.get().ordinal();
        if (((1 << state) & (TcpConstants.TCPF_CLOSE | TcpConstants.TCPF_LISTEN)) != 0) {
            return;
        }

        if ((tp.icsk_ack_pending & ICSK_ACK_TIMER) == 0) {
            return;
        }

        if (time_after(tp.icsk_ack_timeout, jiffies())) {
            sk_reset_timer(icsk_delack_timer, tp.icsk_ack_timeout);
            return;
        }

        tp.icsk_ack_pending &= ~ICSK_ACK_TIMER;
        if (tp.inet_csk_ack_scheduled()) {
            if (!tp.inet_csk_in_pingpong_model()) {
                /* Delayed ACK missed: inflate ATO. */
                tp.icsk_ack_ato = Math.min(tp.icsk_ack_ato << 1, tp.icsk_rto);
            } else {
                /*-
                 * Delayed ACK missed: leave pingpong mode and deflate ATO.
                 * ping-pong 模式触发了延迟ACK, 说明ATO周期内没有数据要发送, 退出 ping-pong 模式.
                 */
                tp.inet_csk_exit_pingpong_mode();
                tp.icsk_ack_ato = TCP_ATO_MIN;
            }

            tp.output.tcp_mstamp_refresh(tp);
            tp.output.tcp_send_ack(tp);
        }
    }

    /* *********** ]] DELAY ACK ************** */

    private void tcp_keepalive_timer() {

    }

    /* *********** WRITE TIMER [[ ************** */


    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L723">tcp_write_timer</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L883">tcp_init_xmit_timers</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/inet_connection_sock.c#L760">inet_csk_init_xmit_timers</a>
     */
    private void tcp_write_timer() {
        if (tp.icsk_pending <= 0) {
            return;
        }
        tcp_write_timer_handler();
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L688">tcp_write_timer_handler</a>
     */
    private void tcp_write_timer_handler() {
        if (0 != ((1 << tp.state.get().ordinal()) & (TcpConstants.TCPF_CLOSE | TcpConstants.TCPF_LISTEN))
                || tp.icsk_pending <= 0) {
            return;
        }
        if (tp.icsk_timeout > jiffies()) {
            sk_reset_timer(icsk_retransmit_timer, tp.icsk_timeout);
            return;
        }

        tp.output.tcp_mstamp_refresh(tp);

        int event = tp.icsk_pending;
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
                tp.icsk_pending = 0;
                tcp_retransmit_timer();
                break;
            case ICSK_TIME_PROBE0:
                tp.icsk_pending = 0;
                tcp_probe_timer();
                break;
        }
    }

    /* *********** ]] WRITE TIMER ************** */

    /* *********** RETRANSMIT TIMER [[ ************** */



    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L529">tcp_retransmit_timer</a>
     */
    private void tcp_retransmit_timer() {
        if (tp.packets_out <= 0) {
            return;
        }

        TcpBuffer skb = tp.tcp_rtx_queue_head();
        if (null == skb) {
            return;
        }

        // if ....
//        if (false) {
        if (tp.snd_wnd > 0
                // && !sock_flag(sk, SOCK_DEAD)
                && ((1 << tp.state.get().ordinal()) & (TcpConstants.TCPF_SYN_SENT | TcpConstants.TCPF_SYN_RECV)) != 0) {

            long us_or_ms1 = tp.tcp_time_stamp_ts();
            long retrans_stamp0 = tp.retrans_stamp != 0 ? tp.retrans_stamp : tp.tcp_skb_timestamp_ts(tp.tcp_usec_ts, skb);
            long rtx_delta = us_or_ms1 - retrans_stamp0;

            if (tp.tcp_usec_ts != 0) {
                // rtx_delta /= 1000;  // ms
                rtx_delta = TimeUnit.MICROSECONDS.toMillis(rtx_delta);
            }


            if (tcp_rtx_probe0_timed_out(rtx_delta)) {
                tp.logError("RETRANSMIT_WRITE_ERROR");
                tp.tcp_write_err();
                return;
            }

            tp.tcp_enter_loss();
            tp.output.tcp_retransmit_skb(tp, skb, 1);
        } else {
            //....
            if (0 != tcp_write_timeout()) {
                return;
            }

            if (tp.icsk_retransmits == 0) {
                // ignore
            }

            tp.tcp_enter_loss();
            tcp_update_rto_stats();
            if (tp.output.tcp_retransmit_skb(tp, tp.tcp_rtx_queue_head(), 1) > 0) {
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
        if (0 == tp.icsk_retransmits) {
            tp.total_rto_recoveries++;
            tp.rto_stamp = tp.tcp_time_stamp_ms();
        }
        tp.icsk_retransmits++;
        tp.total_rto++;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L213">retransmits_timed_out</a>
     */
    private boolean retransmits_timed_out(int boundary, long timeout) {
        if (tp.icsk_retransmits == 0) {
            return false;
        }

        long start_ts = tp.retrans_stamp;
        if (timeout == 0) {
            long rto_base = TCP_RTO_MIN;
            if (0 != ((1 << tp.state.get().ordinal()) & (TcpConstants.TCPF_SYN_SENT | TcpConstants.TCPF_SYN_RECV))) {
                rto_base = tcp_timeout_init();
            }
            timeout = tcp_model_timeout(boundary, rto_base);
        }

        if (tp.tcp_usec_ts != 0) {
            long delta = tp.tcp_mstamp - start_ts + jiffies_to_usecs(1);
            return delta - TimeUnit.MILLISECONDS.toMicros(timeout) >= 0;
        }
        return tp.tcp_time_stamp_ts() - start_ts - timeout >= 0;
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
        long user_timeout = tp.icsk_user_timeout;
        if (user_timeout == 0) {
            return tp.icsk_rto;
        }
        long elapsed = tp.tcp_time_stamp_ts() - tp.retrans_stamp;
        if (tp.tcp_usec_ts != 0) {
            elapsed = TimeUnit.MICROSECONDS.toMillis(elapsed);
        }
        long remaining = user_timeout - elapsed;
        if (remaining <= 0) {
            /* user timeout has passed; fire ASAP */
            return 1;
        }
        return Math.min(tp.icsk_rto, msecs_to_jiffies(remaining));
    }

    // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L241
    private int tcp_write_timeout() {
        boolean expired = false;
        int retry_until = sysctl_tcp_retries2;
        if (0 != ((1 << tp.state.get().ordinal()) & (TcpConstants.TCPF_SYN_SENT | TcpConstants.TCPF_SYN_RECV))) {
            // FIXME
        } else {
            // ...
            retry_until = sysctl_tcp_retries2;
            // ...
        }

        if (!expired) {
            expired = retransmits_timed_out(retry_until, tp.icsk_user_timeout);
        }

        if (expired) {
            /* Has it gone just too far? */
            tp.tcp_write_err();
            return 1;
        }
        return 0;
    }

    /**
     * @return
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L488">tcp_rtx_probe0_timed_out</a>
     */
    private boolean tcp_rtx_probe0_timed_out(long rtx_delta) {
        int user_timeout = tp.icsk_user_timeout;
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
        long rcv_delta = tp.icsk_timeout - tp.rcv_tstamp;
        if (rcv_delta <= timeout) {
            return false;
        }
        return rtx_delta > timeout;
    }


    /* *********** ]] RETRANSMIT TIMER ************** */

    /* *********** ZERO WINDOW PROBE [[ ************** */


    void tcp_check_probe_timer() {
        // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L1454
        // https://www.cnblogs.com/aiwz/p/6333260.html
        if (tp.packets_out <= 0 && tp.icsk_pending <= 0) {
            tp.tcp_reset_xmit_timer(ICSK_TIME_PROBE0, tp.tcp_probe0_base(), TCP_RTO_MAX);
        }
    }


    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L386">tcp_probe_timer</a>
     */
    private void tcp_probe_timer() {
        final TcpBuffer skb = tp.tcp_send_head();
        if (tp.packets_out > 0 || null == skb) {
            tp.icsk_probes_out = 0;
            tp.icsk_probes_tstamp = 0;
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
        if (tp.icsk_probes_tstamp == 0) {
            tp.icsk_probes_tstamp = tcp_jiffies32();
        } else {
            final int user_timeout = tp.icsk_user_timeout;
            if (user_timeout > 0 && tcp_jiffies32() - tp.icsk_probes_tstamp >= user_timeout) {
                tp.logWarn("PROBE_WRITE_ERROR 1");
                tp.tcp_write_err();
                return;
            }
        }

        int max_probes = sysctl_tcp_retries2;

        // ...

        if (tp.icsk_probes_out >= max_probes) {
            tp.logWarn("PROBE_WRITE_ERROR 2");
            tp.tcp_write_err();
        } else {
            /* Only send another probe if we didn't close things up. */
            tp.output.tcp_send_probe0(tp);
        }
    }





    /* *********** ]] ZERO WINDOW PROBE ************** */
}