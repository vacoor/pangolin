package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal;

import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpUtils;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import org.pcap4j.packet.IpPacket;
import org.pcap4j.packet.namednumber.TcpPort;

import java.net.InetAddress;
import java.util.function.Consumer;

import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpClock.tcp_jiffies32;

// https://github.com/torvalds/linux/blob/master/include/net/sock.h#L150
public class SockCommon {
    private TcpState state = TcpState.TCP_CLOSE;
    public IpPacket.IpHeader rawIpHeader;

    /**
     * skc_daddr, Foreign IPv4 addr.
     * https://github.com/torvalds/linux/blob/master/include/net/sock.h#L150
     *
     * ir_rmt_addr;
     * https://github.com/torvalds/linux/blob/master/include/net/inet_sock.h#L69
     */
    public InetAddress ir_rmt_addr;
    /**
     * skc_rcv_saddr, Bound local IPv4 addr.
     * https://github.com/torvalds/linux/blob/master/include/net/sock.h#L150
     *
     * ir_loc_addr;
     * https://github.com/torvalds/linux/blob/master/include/net/inet_sock.h#L69
     */
    public InetAddress ir_loc_addr;

    /**
     * placeholder for inet_dport/tw_dport
     * https://github.com/torvalds/linux/blob/master/include/net/sock.h#L150
     *
     * sk_dport
     * https://github.com/torvalds/linux/blob/master/include/net/inet_sock.h#L69
     */
    public TcpPort ir_rmt_port;
    /**
     * placeholder for inet_num/tw_num.
     * https://github.com/torvalds/linux/blob/master/include/net/sock.h#L150
     *
     * sk_num
     * https://github.com/torvalds/linux/blob/master/include/net/inet_sock.h#L69
     */
    public TcpPort ir_num;
    public ChannelFuture child;
    public ChannelFutureListener childCloseListener;

    public String uniqueKey() {
        return TcpUtils.uniqueKey(ir_rmt_addr.getHostAddress(), ir_rmt_port.valueAsInt(), ir_loc_addr.getHostAddress(), ir_num.valueAsInt());
    }

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_timer.c#L49">tcp_clamp_probe0_to_user_timeout</a>
     */
    public static long tcp_clamp_probe0_to_user_timeout(TcpSock tp, long when) {
        int user_timeout = tp.icsk_user_timeout;
        if (0 == user_timeout || 0 == tp.icsk_probes_tstamp) {
            return when;
        }
        long elapsed = tcp_jiffies32() - tp.icsk_probes_tstamp;
        if (elapsed < 0) {
            elapsed = 0;
        }
        long remaining = user_timeout - elapsed;
        remaining = Math.max(remaining, TcpConstants.TCP_TIMEOUT_MIN);
        return Math.min(remaining, when);
    }

    public TcpState state() {
        return state;
    }

    public void state(final TcpState state) {
        this.state = state;
    }
}
