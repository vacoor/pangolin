package com.github.pangolin.routing;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.*;
import org.drasyl.channel.tun.TunAddress;
import org.drasyl.channel.tun.TunChannel;
import org.drasyl.channel.tun.TunPacket;
import org.pcap4j.packet.*;
import org.pcap4j.packet.namednumber.IpNumber;
import org.pcap4j.packet.namednumber.TcpPort;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

/**
 *
 */
public class TunTest {
    private static final int INET4 = 4;
    private static final int INET6 = 6;


    static IpPacket parsePacket(final TunPacket packet) throws IllegalRawDataException {
        final byte[] bytes = ByteBufUtil.getBytes(packet.content());
        return INET6 == packet.version()
                ? IpV6Packet.newPacket(bytes, 0, bytes.length)
                : IpV4Packet.newPacket(bytes, 0, bytes.length);
    }

    public static void channelRead0(final ChannelHandlerContext ctx, final IpPacket ipPacket) {
        final IpPacket.IpHeader ipHeader = ipPacket.getHeader();
        final InetAddress srcAddr = ipHeader.getSrcAddr();
        final InetAddress dstAddr = ipHeader.getDstAddr();
        final IpNumber protocol = ipHeader.getProtocol();

        if (IpNumber.TCP.equals(protocol)) {
            final TcpPacket tcpPacket = (TcpPacket) ipPacket.getPayload();
            final TcpPacket.TcpHeader tcpHeader = tcpPacket.getHeader();
            final TcpPort tcpSrcPort = tcpHeader.getSrcPort();
            if (tcpHeader.getSyn()) {

            } else if (tcpHeader.getFin()){

            }
            System.out.println("TCP: " + srcAddr + " -> " + dstAddr);
        } else if (IpNumber.UDP.equals(protocol)) {
            final UdpPacket udpPacket = (UdpPacket) ipPacket.getPayload();
            System.out.println("UDP: " + srcAddr + " -> " + dstAddr);
        } else if (IpNumber.ICMPV6.equals(protocol)) {
            IcmpV6CommonPacket payload = (IcmpV6CommonPacket) ipPacket.getPayload();
            System.out.println("ICMPv6: " + srcAddr + " -> " + dstAddr);
        } else {
            System.out.println(protocol.valueAsString());
        }
    }

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
                            final IpPacket ipPacket = parsePacket(packet);
                            channelRead0(ctx, ipPacket);
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
