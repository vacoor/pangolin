package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal;

import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpUtils;
import io.netty.channel.ChannelFuture;
import org.pcap4j.packet.IpPacket;
import org.pcap4j.packet.namednumber.TcpPort;

import java.net.InetAddress;
import java.util.function.Consumer;

import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.util.TcpClock.tcp_jiffies32;

// https://github.com/torvalds/linux/blob/master/include/net/sock.h#L150
public class SockCommon {
    private TcpState state = TcpState.TCP_CLOSE;
    public IpPacket.IpHeader rawIpHeader;
    public InetAddress srcAddr;
    public InetAddress dstAddr;

    public TcpPort ir_rmt_port;
    public TcpPort ir_num;
    public ChannelFuture child;
    public Consumer<TcpBuffer> INDIRECT_CALL_INET;

    public String uniqueKey() {
        return TcpUtils.uniqueKey(srcAddr.getHostAddress(), ir_rmt_port.valueAsInt(), dstAddr.getHostAddress(), ir_num.valueAsInt());
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
