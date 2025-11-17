package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.v2;

import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpBuffer;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpConstants;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpState;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpUtils;
import io.netty.channel.ChannelFuture;
import org.pcap4j.packet.IpPacket;
import org.pcap4j.packet.namednumber.TcpPort;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpClock.tcp_jiffies32;

// https://github.com/torvalds/linux/blob/master/include/net/sock.h#L150
public class SockCommon {
    public final AtomicReference<TcpState> state = new AtomicReference<>(TcpState.TCP_CLOSE);
    public IpPacket.IpHeader rawIpHeader;
    public InetAddress srcAddr;
    public InetAddress dstAddr;

    public TcpPort srcPort;
    public TcpPort dstPort;
    public ChannelFuture child;
    public volatile Runnable destroy;
    public Consumer<TcpBuffer> INDIRECT_CALL_INET;

    public String uniqueKey() {
        return TcpUtils.uniqueKey(srcAddr.getHostAddress(), srcPort.valueAsInt(), dstAddr.getHostAddress(), dstPort.valueAsInt());
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
        return state.get();
    }

    public void state(final TcpState state) {
        this.state.set(state);
    }
}
