package com.github.pangolin.routing.beta.tun;

import com.sun.jna.WString;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import org.drasyl.channel.tun.Tun4Packet;
import org.drasyl.channel.tun.Tun6Packet;
import org.drasyl.channel.tun.TunAddress;
import org.drasyl.channel.tun.TunChannel;
import org.drasyl.channel.tun.TunPacket;
import org.drasyl.channel.tun.jna.windows.WindowsTunDevice;
import org.pcap4j.core.PcapHandle;
import org.pcap4j.packet.IcmpV6CommonPacket;
import org.pcap4j.packet.IpV4Packet;
import org.pcap4j.packet.IpV6Packet;
import org.pcap4j.packet.Packet;
import org.pcap4j.packet.TcpPacket;
import org.pcap4j.packet.UdpPacket;
import org.pcap4j.packet.UnknownPacket;
import org.pcap4j.packet.factory.PacketFactories;
import org.pcap4j.packet.namednumber.DataLinkType;
import org.pcap4j.packet.namednumber.IpNumber;

import java.lang.reflect.Field;
import java.net.Inet4Address;
import java.net.Inet6Address;

/**
 *
 */
public class TunTest {
    public static void main(String[] args) throws Exception {
        /*
        final Field innerString = WString.class.getDeclaredField("string");
        if (innerString.trySetAccessible()) {
            innerString.set(WindowsTunDevice.TUNNEL_TYPE, "TEST");
        }
        */

        EventLoopGroup group = new DefaultEventLoopGroup(1);
        try {
            final Bootstrap b = new Bootstrap()
                    .group(group)
                    .channel(TunChannel.class)
                    .handler(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
                            final TunPacket packet = (TunPacket) msg;
                            final int version = packet.version();
                            final byte[] ba = ByteBufUtil.getBytes(packet.content());

                            if (6 == version) {
                                final IpV6Packet pcapPacket = IpV6Packet.newPacket(ba, 0, ba.length);
                                final IpV6Packet.IpV6Header header = pcapPacket.getHeader();
                                final Inet6Address srcAddr = header.getSrcAddr();
                                final Inet6Address dstAddr = header.getDstAddr();
                                final IpNumber protocol = header.getProtocol();
                                if (IpNumber.TCP.equals(protocol)) {
                                    pcapPacket.getPayload();
                                    System.out.println("TCP: " + srcAddr + " -> " + dstAddr);
                                } else if (IpNumber.UDP.equals(protocol)) {
                                    final UdpPacket udpPacket = (UdpPacket) pcapPacket.getPayload();
                                    System.out.println("UDP: " + srcAddr + " -> " + dstAddr);
                                } else if (IpNumber.ICMPV6.equals(protocol)) {
                                    IcmpV6CommonPacket payload = (IcmpV6CommonPacket) pcapPacket.getPayload();
                                    System.out.println("ICMPv6: " + srcAddr + " -> " + dstAddr);
                                } else {
                                    System.out.println(protocol.valueAsString());
                                }
                            } else {
                                final IpV4Packet pcapPacket = IpV4Packet.newPacket(ba, 0, ba.length);
                                final IpV4Packet.IpV4Header header = pcapPacket.getHeader();
                                final Inet4Address srcAddr = header.getSrcAddr();
                                final Inet4Address dstAddr = header.getDstAddr();
                                final IpNumber protocol = header.getProtocol();
                                if (IpNumber.TCP.equals(protocol)) {
                                    final TcpPacket tcpPacket = (TcpPacket) pcapPacket.getPayload();
                                    final TcpPacket.TcpHeader tcpHeader = tcpPacket.getHeader();
//                                    tcpHeader.getSyn()
                                    System.out.println("TCP: " + srcAddr + " -> " + dstAddr);
                                } else if (IpNumber.UDP.equals(protocol)) {
                                    final UdpPacket udpPacket = (UdpPacket) pcapPacket.getPayload();
                                    final UdpPacket.UdpHeader udpHeader = udpPacket.getHeader();

                                    System.out.println("UDP: " + srcAddr + " -> " + dstAddr);
                                } else if (IpNumber.ICMPV6.equals(protocol)) {
                                    IcmpV6CommonPacket payload = (IcmpV6CommonPacket) pcapPacket.getPayload();
                                    System.out.println("ICMPv6: " + srcAddr + " -> " + dstAddr);
                                } else {
                                    System.out.println(protocol.valueAsString());
                                }
                            }
                            super.channelRead(ctx, msg);
                        }
                    });
            final Channel ch = b.bind(new TunAddress("utun99")).sync().channel();
            // send/receive messages of type TunPacket...
            ch.closeFuture().sync();
        } finally {
            group.shutdownGracefully();
        }
    }
}
