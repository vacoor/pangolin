package com.github.pangolin.routing.server.tun.beta.handler;

import com.github.pangolin.routing.handler.internal.server.support.SocketChannelFactory;
import com.github.pangolin.routing.server.fakedns.DnsEngine;
import com.github.pangolin.routing.server.tun.beta.Tcp4Connection;
import com.github.pangolin.routing.server.tun.beta.TcpConnection;
import com.github.pangolin.routing.server.tun.beta.TcpConnection2;
import com.google.common.collect.Maps;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import lombok.extern.slf4j.Slf4j;
import org.pcap4j.packet.IpPacket;
import org.pcap4j.packet.IpPacket.IpHeader;
import org.pcap4j.packet.IpV4Packet;
import org.pcap4j.packet.TcpPacket;
import org.pcap4j.packet.TcpPacket.TcpHeader;
import org.pcap4j.packet.namednumber.IpNumber;
import org.pcap4j.packet.namednumber.IpVersion;
import org.pcap4j.packet.namednumber.TcpPort;

import java.net.InetAddress;
import java.util.Map;

@Slf4j
public class Tcp4PacketHandler extends TcpPacketHandler<IpV4Packet> {

    public Tcp4PacketHandler(final DnsEngine dnsEngine, final SocketChannelFactory factory) {
        super(dnsEngine, factory);
    }

    @Override
    protected TcpConnection<IpV4Packet> create(final Channel parent, final EventLoopGroup childGroup, final DnsEngine dnsEngine, final SocketChannelFactory socketChannelFactory, final Runnable destroyCallback) {
        return new Tcp4Connection(parent, childGroup, dnsEngine, socketChannelFactory) {
            @Override
            protected void onDestroy() {
                destroyCallback.run();
            }
        };
    }

}
