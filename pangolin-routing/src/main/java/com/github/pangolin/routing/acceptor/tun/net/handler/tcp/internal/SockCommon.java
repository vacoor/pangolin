package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal;

import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpUtils;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import org.pcap4j.packet.IpPacket;
import org.pcap4j.packet.namednumber.TcpPort;

import java.net.InetAddress;

import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpClock.tcp_jiffies32;

/**
 * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/sock.h#L150">struct sock_common</a>
 */
public class SockCommon {
    public IpPacket.IpHeader rawIpHeader;

    /**
     * Foreign IPv4 addr.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/sock.h#L150">sock_common->skc_daddr</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/sock.h#L150">sock->sk_daddr</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/inet_sock.h#L69">inet_request_sock->ir_rmt_addr</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/inet_sock.h#L212">inet_sock->inet_daddr</a>
     */
    public InetAddress ir_rmt_addr;

    /**
     * Bound local IPv4 addr.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/sock.h#L150">sock_common->skc_rcv_saddr</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/sock.h#L150">sock->sk_rcv_saddr</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/inet_sock.h#L69">inet_request_sock->ir_loc_addr</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/inet_sock.h#L212">inet_sock->inet_rcv_saddr</a>
     */
    public InetAddress ir_loc_addr;

    /**
     * Placeholder for inet_dport/tw_dport.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/sock.h#L150">sock_common->skc_dport</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/sock.h#L150">sock->sk_dport</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/inet_sock.h#L69">inet_request_sock->ir_rmt_port</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/inet_sock.h#L212">inet_sock->inet_dport</a>
     */
    public TcpPort ir_rmt_port;

    /**
     * Placeholder for inet_num/tw_num.
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/sock.h#L150">sock_common->skc_num</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/sock.h#L150">sock->sk_num</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/inet_sock.h#L69">inet_request_sock->ir_num</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/inet_sock.h#L212">inet_sock->inet_num</a>
     */
    public TcpPort ir_num;

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/sock.h#L150">sock_common->skc_state</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/sock.h#L150">sock->sk_state</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/inet_sock.h#L69">inet_request_sock->ireq_state</a>
     */
    private volatile TcpState state = TcpState.TCP_CLOSE;

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/sock.h#L150">sock_common->skc_state</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/request_sock.h#L51">request_sock->rsk_rcv_wnd</a>
     */
    public int rsk_rcv_wnd;

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/sock.h#L150">sock_common->skc_window_clamp</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/request_sock.h#L51">request_sock->rsk_window_clamp</a>
     */
    public int rsk_window_clamp;

    /**
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/sock.h#L150">skc_listener</a>
     * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/request_sock.h#L51">rsk_listener</a>
     */
    public Sock skc_listener;

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
