package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.*;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpClock;
import com.google.common.collect.Maps;
import io.netty.channel.Channel;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ScheduledFuture;
import lombok.extern.slf4j.Slf4j;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpConstants.TCPF_CLOSE;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpConstants.TCPF_LISTEN;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpState.TCP_FIN_WAIT2;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.inet_connection_sock.TCP_RTO_MAX;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.inet_connection_sock.TCP_RTO_MIN;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpLogUtils.logFormat;
import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpUtils.time_after;

@Slf4j
public class TcpTimer {
    /**
     * https://github.com/torvalds/linux/blob/master/include/net/inet_connection_sock.h#L144
     */
    /* Retransmit timer */
    public static final int ICSK_TIME_RETRANS = 1;
    /* Delayed ack timer */
    public static final int ICSK_TIME_DACK = 2;
    /* Zero window probe timer */
    public static final int ICSK_TIME_PROBE0 = 3;
    /* Tail loss probe timer */
    public static final int ICSK_TIME_LOSS_PROBE = 5;
    /* Reordering timer */
    public static final int ICSK_TIME_REO_TIMEOUT = 6;

    private final TcpDemultiplexer demultiplexer;

    public TcpTimer(TcpDemultiplexer demultiplexer) {
        this.demultiplexer = demultiplexer;
    }

    /**
     * Using different timers for retransmit, delayed acks and probes
     * We may wish use just one timer maintaining a list of expire jiffies
     * to optimize.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/inet_connection_sock.h#L165">inet_csk_init_xmit_timers</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/inet_connection_sock.c#L755">inet_csk_init_xmit_timers</a>
     */
    private void inet_csk_init_xmit_timers(final TcpSock tp,
                                           final Runnable icsk_retransmit_handler,
                                           final Runnable icsk_delack_handler,
                                           final Runnable keepalive_handler) {
        tp.icsk_retransmit_timer = icsk_retransmit_handler;
        tp.icsk_delack_timer = icsk_delack_handler;
        tp.sk_timer = keepalive_handler;
        tp.icsk_pending = tp.icsk_ack.pending = 0;
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/inet_connection_sock.c">inet_csk_clear_xmit_timers</a>
     */
    private void inet_csk_clear_xmit_timers(final TcpSock tp) {
        tp.icsk_pending = 0;
        tp.icsk_ack.pending = 0;

        sk_stop_timer(tp.icsk_retransmit_timer);
        sk_stop_timer(tp.icsk_delack_timer);
        sk_stop_timer(tp.sk_timer);
    }


    void tcp_init_xmit_timers(Channel net, TcpSock tp) {
        // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L883
        inet_csk_init_xmit_timers(tp, () -> this.tcp_write_timer(net, tp), () -> this.tcp_delack_timer(net, tp), () -> this.tcp_keepalive_timer(net, tp));
    }

    // https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L702
    public void tcp_clear_xmit_timers(TcpSock tp) {
        inet_csk_clear_xmit_timers(tp);
    }


    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/inet_connection_sock.h#L218">inet_csk_reset_xmit_timer</a>
     */
    public void inet_csk_reset_xmit_timer(final TcpSock tp, final int what, long when, long max_when) {
        if (when > max_when) {
            when = max_when;
        }

        when += TcpClock.jiffies();
        if (what == ICSK_TIME_RETRANS || what == ICSK_TIME_PROBE0 ||
                what == ICSK_TIME_LOSS_PROBE || what == ICSK_TIME_REO_TIMEOUT) {
            tp.icsk_pending = what;
            tp.icsk_timeout = when; // FIXME
            sk_reset_timer(tp, tp.icsk_retransmit_timer, when);
        } else if (what == ICSK_TIME_DACK) {
            tp.icsk_ack.pending |= ICSK_ACK_TIMER;
            tp.icsk_ack.timeout = when; // FIXME
            sk_reset_timer(tp, tp.icsk_delack_timer, when);
        } else {
            log.warn("BUG: unknown timer value");
        }
    }


    public void sk_stop_timer(Runnable timer) {
        Future<?> future = timers.remove(timer);
        if (null != future && !future.isDone() && !future.isCancelled()) {
            future.cancel(false);
        }
    }

    void sk_reset_timer(TcpSock tp, Runnable timer, long expires) {
        // https://github.com/torvalds/linux/blob/master/net/core/sock.c#L3539
        mod_timer(tp, timer, expires);
    }

    private int mod_timer(TcpSock tp, Runnable timer, long expires) {
        // https://github.com/torvalds/linux/blob/master/kernel/time/timer.c#L1235
        return __mod_timer(tp, timer, expires, 0);
    }

    private final ConcurrentMap<Runnable, Future<?>> timers = Maps.newConcurrentMap();

    private int __mod_timer(final TcpSock tp, Runnable timer, long expires, int options) {
//        io.netty.util.concurrent.ScheduledFuture<?> nf = parent.eventLoop().schedule(timer, expires - jiffies(), TimeUnit.MILLISECONDS);
        final long delay = expires - TcpClock.jiffies();
        final Future<?> future = timers.get(timer);
        if (null != future && !future.isDone() && !future.isCancelled()) {
            future.cancel(true);
        }
        timers.put(timer, schedule(tp.child.channel(), delay, TimeUnit.MILLISECONDS, timer));
        /*
        if (null == future || future.isDone() || future.isCancelled() || future.cancel(true)) {
            timers.put(timer, schedule(tp.child.channel(), delay, TimeUnit.MILLISECONDS, timer));
        } else {
            log.warn(logFormat(
                    "TCP",
                    tp.ir_loc_addr, tp.ir_num.valueAsInt(),
                    tp.ir_rmt_addr, tp.ir_rmt_port.valueAsInt(),
                    "CANCEL FAILED: {}"
            ), tp.icsk_pending);
        }
         */
        return 0;
    }

    private ScheduledFuture<?> schedule(Channel channel, long delay, TimeUnit unit, Runnable timer) {
        return channel.eventLoop().schedule(timer, delay, unit);
    }

    /* *********** DELAY ACK [[ ************** */

    static final int TCP_DELACK_MIN = TcpConstants.HZ / 25;
    static final int TCP_DELACK_MAX = TcpConstants.HZ / 5;

    /**
     * <a href="https://github.com/torvalds/linux/blob/master/include/net/inet_connection_sock.h#L161">inet_csk_ack_state_t</a>
     */
    public static final int ICSK_ACK_SCHED = 1;
    public static final int ICSK_ACK_TIMER = 1 << 1;
    public static final int ICSK_ACK_PUSHED = 1 << 2;
    public static final int ICSK_ACK_PUSHED2 = 1 << 3;
    public static final int ICSK_ACK_NOW = 1 << 4;
    public static final int ICSK_ACK_NOMEM = 1 << 5;

    /**
     * @see <a href="https://www.cnblogs.com/wanpengcoder/p/11749449.html">TCP定时器 之 延迟确认定时器</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L307">tcp_delack_timer_handler</a>
     */
    private void tcp_delack_timer_handler(Channel net, TcpSock tp) {
        final int state = tp.state().ordinal();
        if (((1 << state) & (TCPF_CLOSE | TCPF_LISTEN)) != 0) {
            return;
        }

        /* Handling the sack compression case */
        if (tp.compressed_ack) {
            demultiplexer.output.tcp_mstamp_refresh(tp);
            demultiplexer.input.tcp_sack_compress_send_ack(tp);
            return;
        }

        if ((tp.icsk_ack.pending & ICSK_ACK_TIMER) == 0) {
            return;
        }

        if (time_after(tp.icsk_delack_timeout(), TcpClock.jiffies())) {
            sk_reset_timer(tp, tp.icsk_delack_timer, tp.icsk_delack_timeout());
            return;
        }

        tp.icsk_ack.pending &= ~ICSK_ACK_TIMER;
        if (tp.inet_csk_ack_scheduled()) {
            if (!tp.inet_csk_in_pingpong_model(tp)) {
                /* Delayed ACK missed: inflate ATO. */
                tp.icsk_ack.ato = Math.min(tp.icsk_ack.ato << 1, tp.icsk_rto);
            } else {
                /*-
                 * Delayed ACK missed: leave pingpong mode and deflate ATO.
                 * ping-pong 模式触发了延迟ACK, 说明ATO周期内没有数据要发送, 退出 ping-pong 模式.
                 */
                tp.inet_csk_exit_pingpong_mode(tp);
                tp.icsk_ack.ato = TcpConstants.TCP_ATO_MIN;
            }

            demultiplexer.output.tcp_mstamp_refresh(tp);
            demultiplexer.output.tcp_send_ack(net, tp);
        }
    }


    /**
     * tcp_delack_timer() - The TCP delayed ACK timeout handler
     *
     * @t: Pointer to the timer. (gets casted to struct sock *)
     * <p>
     * This function gets (indirectly) called when the kernel timer for a TCP packet
     * of this socket expires. Calls tcp_delack_timer_handler() to do the actual work.
     * <p>
     * Returns: Nothing (void)
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L359">tcp_delack_timer</a>
     */
    private void tcp_delack_timer(Channel net, TcpSock tp) {
        // FIXME
        tcp_delack_timer_handler(net, tp);
    }

    /* *********** ]] DELAY ACK ************** */


    /* *********** WRITE TIMER [[ ************** */

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L723">tcp_write_timer</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L883">tcp_init_xmit_timers</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/inet_connection_sock.c#L760">inet_csk_init_xmit_timers</a>
     */
    private void tcp_write_timer(Channel net, TcpSock tp) {
        if (tp.icsk_pending <= 0) {
            return;
        }
        tcp_write_timer_handler(net, tp);
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L688">tcp_write_timer_handler</a>
     */
    private void tcp_write_timer_handler(Channel net, TcpSock tp) {
        if (0 != ((1 << tp.state().ordinal()) & (TCPF_CLOSE | TCPF_LISTEN))
                || tp.icsk_pending <= 0) {
            return;
        }
        if (tp.icsk_timeout > TcpClock.jiffies()) {
            sk_reset_timer(tp, tp.icsk_retransmit_timer, tp.icsk_timeout);
            return;
        }

        demultiplexer.output.tcp_mstamp_refresh(tp);

        int event = tp.icsk_pending;
        switch (event) {
            case ICSK_TIME_REO_TIMEOUT:
                // FIXME
                // tcp_rack_reo_timeout(sk);
                break;
            case ICSK_TIME_LOSS_PROBE:
                demultiplexer.output.tcp_send_loss_probe();
                break;
            case ICSK_TIME_RETRANS:
                tp.icsk_pending = 0;
                tcp_retransmit_timer(net, tp);
                break;
            case ICSK_TIME_PROBE0:
                tp.icsk_pending = 0;
                tcp_probe_timer(net, tp);
                break;
        }
    }

    /* *********** ]] WRITE TIMER ************** */

    /* *********** RETRANSMIT TIMER [[ ************** */


    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L529">tcp_retransmit_timer</a>
     */
    private void tcp_retransmit_timer(Channel net, TcpSock tp) {
        if (tp.packets_out <= 0) {
            return;
        }

        TcpBuffer skb = tp.tcp_rtx_queue_head();
        if (null == skb) {
            return;
        }

        if (tp.snd_wnd > 0
                // && !sock_flag(sk, SOCK_DEAD)
                && ((1 << tp.state().ordinal()) & (TcpConstants.TCPF_SYN_SENT | TcpConstants.TCPF_SYN_RECV)) != 0) {

            long us_or_ms1 = tp.tcp_time_stamp_ts();
            long retrans_stamp0 = tp.retrans_stamp != 0 ? tp.retrans_stamp : tp.tcp_skb_timestamp_ts(tp.tcp_usec_ts, skb);
            long rtx_delta = us_or_ms1 - retrans_stamp0;

            if (tp.tcp_usec_ts != 0) {
                // rtx_delta /= 1000;  // ms
                rtx_delta = TimeUnit.MICROSECONDS.toMillis(rtx_delta);
            }

            if (tcp_rtx_probe0_timed_out(tp, rtx_delta)) {
//                tp.logError("[RETRANSMIT] RTX PROBE0 TIMEOUT");
                log.info(logFormat(
                        "TCP",
                        tp.ir_loc_addr, tp.ir_num.valueAsInt(),
                        tp.ir_rmt_addr, tp.ir_rmt_port.valueAsInt(),
                        "RTX PROBE0 TIMEOUT"
                ));
                demultiplexer.tcp_write_err(tp);
                return;
            }

            demultiplexer.input.tcp_enter_loss();
            demultiplexer.output.tcp_retransmit_skb(net, tp, skb, 1);
            // __sk_dst_reset
        } else {
            //....
            if (0 != tcp_write_timeout(tp)) {
                return;
            }

            if (tp.icsk_retransmits == 0) {
                // ...
            }

            demultiplexer.input.tcp_enter_loss();
            tcp_update_rto_stats(tp);

            if (demultiplexer.output.tcp_retransmit_skb(net, tp, tp.tcp_rtx_queue_head(), 1) > 0) {
                /* Retransmission failed because of local congestion,
                 * Let senders fight for local resources conservatively.
                 */
                tp.tcp_reset_xmit_timer(this, ICSK_TIME_RETRANS, TcpConstants.TCP_RESOURCE_PROBE_INTERVAL, false);
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

        long l = tcp_clamp_rto_to_user_timeout(tp);
        if (log.isTraceEnabled()) {
            log.trace(logFormat(
                    "TCP",
                    tp.ir_loc_addr, tp.ir_num.valueAsInt(),
                    tp.ir_rmt_addr, tp.ir_rmt_port.valueAsInt(),
                    "next timeout: {}"
            ), new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date(System.currentTimeMillis() + l)));
        }
        tp.tcp_reset_xmit_timer(this, ICSK_TIME_RETRANS, l, false);
//        if (retransmits_timed_out(sysctl_tcp_retries1 + 1, 0)) {
        // 重置路由缓存
        // __sk_dst_reset(sk);
//        }
    }


    private void tcp_update_rto_stats(TcpSock tp) {
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
    private boolean retransmits_timed_out(TcpSock tp, int boundary, long timeout) {
        if (tp.icsk_retransmits == 0) {
            return false;
        }

        long start_ts = tp.retrans_stamp;
        if (timeout == 0) {
            long rto_base = TCP_RTO_MIN;
            if (0 != ((1 << tp.state().ordinal()) & (TcpConstants.TCPF_SYN_SENT | TcpConstants.TCPF_SYN_RECV))) {
                rto_base = demultiplexer.tcp_timeout_init(tp);
            }
            timeout = tcp_model_timeout(boundary, rto_base);
        }

        if (tp.tcp_usec_ts != 0) {
            long delta = tp.tcp_mstamp - start_ts + TcpClock.jiffies_to_usecs(1);
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
        return TcpClock.jiffies_to_msecs(timeout);
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L27">tcp_clamp_rto_to_user_timeout</a>
     */
    private long tcp_clamp_rto_to_user_timeout(TcpSock tp) {
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
        return Math.min(tp.icsk_rto, TcpClock.msecs_to_jiffies(remaining));
    }

    // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L241
    private int tcp_write_timeout(TcpSock tp) {
        boolean expired = false;
        int retry_until = SysctlOptions.sysctl_tcp_retries2;
        int max_retransmits;

        if (0 != ((1 << tp.state().ordinal()) & (TcpConstants.TCPF_SYN_SENT | TcpConstants.TCPF_SYN_RECV))) {
            retry_until = tp.icsk_syn_retries > 0 ? tp.icsk_syn_retries : SysctlOptions.sysctl_tcp_syn_retries;

            max_retransmits = retry_until;

            // ...

            expired = tp.icsk_retransmits >= max_retransmits;
        } else {
//            if (retransmits_timed_out(sysctl_tcp_retries1, 0)) {
//                /* Black hole detection */
//                tcp_mtu_probing(icsk, sk);
//            }

            // ...
            retry_until = SysctlOptions.sysctl_tcp_retries2;
            // ...
        }

        if (!expired) {
            expired = retransmits_timed_out(tp, retry_until, tp.icsk_user_timeout);
        }

        // ...

        if (expired) {
            /* Has it gone just too far? */
//            tp.logError("[RETRANSMIT] WRITE TIMEOUT");
            log.info(logFormat(
                    "TCP",
                    tp.ir_loc_addr, tp.ir_num.valueAsInt(),
                    tp.ir_rmt_addr, tp.ir_rmt_port.valueAsInt(),
                    "RETRANSMIT WRITE TIMEOUT"
            ));
            demultiplexer.tcp_write_err(tp);
            return 1;
        }
        return 0;
    }

    /**
     * @return
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L488">tcp_rtx_probe0_timed_out</a>
     */
    private boolean tcp_rtx_probe0_timed_out(TcpSock tp, long rtx_delta) {
        int user_timeout = tp.icsk_user_timeout;
        int timeout = tp.tcp_rto_max() << 1;

        if (user_timeout > 0) {
            /* If user application specified a TCP_USER_TIMEOUT,
             * it does not want win 0 packets to 'reset the timer'
             * while retransmits are not making progress.
             */
            if (rtx_delta > user_timeout) {
                return true;
            }
            timeout = Math.min(timeout, (int) TcpClock.msecs_to_jiffies(user_timeout));
        }

        /* Note: timer interrupt might have been delayed by at least one jiffy,
         * and tp->rcv_tstamp might very well have been written recently.
         * rcv_delta can thus be negative.
         */
        long icsk_timeout = tp.icsk_timeout();
        long rcv_delta = icsk_timeout - tp.rcv_tstamp;
        if (rcv_delta <= timeout) {
            return false;
        }
        return TcpClock.msecs_to_jiffies(rtx_delta) > timeout;
    }


    /* *********** ]] RETRANSMIT TIMER ************** */

    /* *********** ZERO WINDOW PROBE [[ ************** */


    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L386">tcp_probe_timer</a>
     */
    private void tcp_probe_timer(Channel net, TcpSock tp) {
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
            tp.icsk_probes_tstamp = TcpClock.tcp_jiffies32();
        } else {
            final int user_timeout = tp.icsk_user_timeout;
            if (user_timeout > 0 && TcpClock.tcp_jiffies32() - tp.icsk_probes_tstamp >= TcpClock.msecs_to_jiffies(user_timeout)) {
//                tp.logWarn("PROBE TIMEOUT");
                log.info(logFormat(
                        "TCP",
                        tp.ir_loc_addr, tp.ir_num.valueAsInt(),
                        tp.ir_rmt_addr, tp.ir_rmt_port.valueAsInt(),
                        "PROBE TIMEOUT"
                ));
                demultiplexer.tcp_write_err(tp);
                return;
            }
        }

        int max_probes = SysctlOptions.sysctl_tcp_retries2;

        // ...

        if (tp.icsk_probes_out >= max_probes) {
//            tp.logWarn("TOO MANY PROBES");
            log.info(logFormat(
                    "TCP",
                    tp.ir_loc_addr, tp.ir_num.valueAsInt(),
                    tp.ir_rmt_addr, tp.ir_rmt_port.valueAsInt(),
                    "TOO MANY PROBES"
            ));
            demultiplexer.tcp_write_err(tp);
        } else {
            /* Only send another probe if we didn't close things up. */
            demultiplexer.output.tcp_send_probe0(net, tp);
        }
    }

    /* *********** ]] ZERO WINDOW PROBE ************** */

    public void tcp_reset_keepalive_timer(TcpSock tp, long len) {
        sk_reset_timer(tp, tp.sk_timer, TcpClock.jiffies() + len);
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L779">tcp_keepalive_timer</a>
     */
    private void tcp_keepalive_timer(Channel net, TcpSock tp) {
        final TcpState state = tp.state();
        if (TcpState.TCP_LISTEN.equals(state)) {
            log.error("Hmm... keepalive on a LISTEN ???");
            return;
        }

        demultiplexer.output.tcp_mstamp_refresh(tp);

        if (TCP_FIN_WAIT2.equals(state)) {
            if (tp.linger2 >= 0) {
                final int tmo = tp.tcp_fin_time() - TcpConstants.TCP_TIMEWAIT_LEN;
                if (tmo > 0) {
                    demultiplexer.tcp_time_wait(tp, TCP_FIN_WAIT2, tmo);
                    return;
                }
            }
            log.warn("TCP_FIN_WAIT2 TIMEOUT RESET");
            demultiplexer.output.tcp_send_active_reset(net, tp, "SK_RST_REASON_TCP_STATE");
            demultiplexer.tcp_done(tp);
            return;
        }

        //...

        int elapsed = keepalive_time_when(tp);

        /* It is alive without keepalive 8) */
        if (tp.packets_out > 0 || !tp.tcp_write_queue_empty()) {
            tcp_reset_keepalive_timer(tp, elapsed);
            return;
        }

        elapsed = keepalive_time_elapsed(tp);
        if (elapsed >= keepalive_time_when(tp)) {
            final int user_timeout = tp.icsk_user_timeout;
            /*-
             * If the TCP_USER_TIMEOUT option is enabled, use that
             * to determine when to timeout instead.
             */
            if ((user_timeout != 0 && elapsed >= TcpClock.msecs_to_jiffies(user_timeout) && tp.icsk_probes_out > 0)
                    || (user_timeout == 0 && tp.icsk_probes_out >= keepalive_probes(tp))) {
                log.info(logFormat(
                        "TCP",
                        tp.ir_loc_addr, tp.ir_num.valueAsInt(),
                        tp.ir_rmt_addr, tp.ir_rmt_port.valueAsInt(),
                        "KEEPALIVE TIMEOUT"
                ));
                demultiplexer.output.tcp_send_active_reset(net, tp, "SK_RST_REASON_TCP_KEEPALIVE_TIMEOUT");
                demultiplexer.tcp_write_err(tp);
                return;
            }

            if (demultiplexer.output.tcp_write_wakeup(net, tp, 1) <= 0) {
                tp.icsk_probes_out++;
                elapsed = keepalive_intvl_when(tp);
            } else {
                /* If keepalive was lost due to local congestion,
                 * try harder.
                 */
                elapsed = TcpConstants.TCP_RESOURCE_PROBE_INTERVAL;
            }
        } else {
            /* It is tp->rcv_tstamp + keepalive_time_when(tp) */
            elapsed = keepalive_time_when(tp) - elapsed;
        }
        tcp_reset_keepalive_timer(tp, elapsed);
    }

    private int keepalive_probes(TcpSock tp) {
        int val;

        /* Paired with WRITE_ONCE() in tcp_sock_set_keepcnt()
         * and do_tcp_setsockopt().
         */
        val = tp.keepalive_probes;

        return 0 != val ? val : SysctlOptions.sysctl_tcp_keepalive_probes;
    }

    int keepalive_time_when(final TcpSock tp) {
        // tp.keepalive_time;
        return SysctlOptions.sysctl_tcp_keepalive_time;
    }

    int keepalive_intvl_when(final TcpSock tp) {
        /*-
         * Paired with WRITE_ONCE() in tcp_sock_set_keepintvl()
         * and do_tcp_setsockopt().
         */
        int val = tp.keepalive_intvl;
        return 0 != val ? val : SysctlOptions.sysctl_tcp_keepalive_intvl;
    }

    int keepalive_time_elapsed(final TcpSock tp) {
        return (int) Math.min(TcpClock.tcp_jiffies32() - tp.icsk_ack.lrcvtime, TcpClock.tcp_jiffies32() - tp.rcv_tstamp);
    }
}