package com.github.pangolin.routing.acceptor.tun.net.handler.tcp;

import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.Tcp4Connection;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpConnection;
import com.github.pangolin.routing.support.SocketChannelFactory;
import com.github.pangolin.routing.acceptor.tun.fakedns.DnsEngine;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import lombok.extern.slf4j.Slf4j;
import org.pcap4j.packet.IpV4Packet;

@Slf4j
public class Tcp4PacketHandler extends TcpPacketHandler<IpV4Packet> {

    public Tcp4PacketHandler(final DnsEngine dnsEngine, final SocketChannelFactory factory) {
        super(dnsEngine, factory);
    }

    /*
    @Override
    protected IpV4Packet prepare(IpV4Packet ipPacket) throws UnknownHostException {
        final Inet4Address dstAddr = ipPacket.getHeader().getDstAddr();
        return ipPacket.getBuilder().dstAddr((Inet4Address) resolveDstAddress(dstAddr)).build();
    }
    */

    @Override
    protected TcpConnection<IpV4Packet> create(final Channel parent, final EventLoopGroup childGroup, final DnsEngine dnsEngine, final SocketChannelFactory socketChannelFactory, final Runnable destroyCallback) {
        return new Tcp4Connection(parent, childGroup, dnsEngine, socketChannelFactory) {
            @Override
            protected void destroy0() {
                destroyCallback.run();
            }
        };
    }

}
