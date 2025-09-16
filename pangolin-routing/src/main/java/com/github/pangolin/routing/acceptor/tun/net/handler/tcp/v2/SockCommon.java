package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.v2;

import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpState;
import io.netty.channel.Channel;
import org.pcap4j.packet.namednumber.TcpPort;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicReference;

// https://github.com/torvalds/linux/blob/master/include/net/sock.h#L150
public class SockCommon {
    public final AtomicReference<TcpState> state = new AtomicReference<>(TcpState.TCP_CLOSE);
    public InetAddress srcAddr;
    public InetAddress dstAddr;

    public TcpPort srcPort;
    public TcpPort dstPort;
    public Channel child;

    public TcpState state() {
        return state.get();
    }

    public void state(final TcpState state) {
        this.state.set(state);
    }
}
