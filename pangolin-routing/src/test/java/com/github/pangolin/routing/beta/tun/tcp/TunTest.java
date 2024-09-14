package com.github.pangolin.routing.beta.tun.tcp;

import com.github.pangolin.routing.beta.tun.tcp.RawSocket;
import com.github.pangolin.routing.beta.InterfaceAddressEx;
import com.github.pangolin.routing.beta.tun.windows.win32.WindowsNetworkInterfaceEx;
import com.google.common.collect.Maps;
import com.sun.jna.WString;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import lombok.extern.slf4j.Slf4j;
import org.drasyl.channel.tun.TunAddress;
import org.drasyl.channel.tun.TunChannel;
import org.drasyl.channel.tun.TunPacket;
import org.drasyl.channel.tun.jna.windows.WindowsTunDevice;
import org.pcap4j.packet.IcmpV6CommonPacket;
import org.pcap4j.packet.IllegalRawDataException;
import org.pcap4j.packet.IpPacket;
import org.pcap4j.packet.IpSelector;
import org.pcap4j.packet.TcpPacket;
import org.pcap4j.packet.UdpPacket;
import org.pcap4j.packet.namednumber.IpNumber;
import org.pcap4j.packet.namednumber.TcpPort;
import org.pcap4j.packet.namednumber.UdpPort;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.util.Map;

/**
 *
 */
@Slf4j
public class TunTest {

    static IpPacket parsePacket(final TunPacket packet) throws IllegalRawDataException {
        final byte[] bytes = ByteBufUtil.getBytes(packet.content());
        return (IpPacket) IpSelector.newPacket(bytes, 0, bytes.length);
        /*
        return INET6 == packet.version()
                ? IpV6Packet.newPacket(bytes, 0, bytes.length)
                : IpV4Packet.newPacket(bytes, 0, bytes.length);
                */
    }

    private static final Map<String, RawSocket> socketMap = Maps.newConcurrentMap();

    public static void channelRead0(final ChannelHandlerContext ctx, final IpPacket ipPacket) {
        final IpPacket.IpHeader ipHeader = ipPacket.getHeader();
        final InetAddress srcAddr = ipHeader.getSrcAddr();
        final InetAddress dstAddr = ipHeader.getDstAddr();
        final IpNumber protocol = ipHeader.getProtocol();

        if (IpNumber.TCP.equals(protocol)) {
            final TcpPacket tcpPacket = (TcpPacket) ipPacket.getPayload();
            final TcpPacket.TcpHeader tcpHeader = tcpPacket.getHeader();
            final TcpPort tcpSrcPort = tcpHeader.getSrcPort();
            final TcpPort tcpDstPort = tcpHeader.getDstPort();

            String key = srcAddr.toString() + tcpSrcPort + dstAddr + tcpDstPort;
            if (!tcpHeader.getAck() && tcpHeader.getSyn()) {
                socketMap.putIfAbsent(key, new RawSocket(ctx) {
                    @Override
                    protected void onClosed() {
                        socketMap.remove(key);
                    }
                });
            }
            RawSocket rawSocket = socketMap.get(key);
            if (null != rawSocket) {
                rawSocket.receive(tcpPacket, ipHeader);
            } else {
                // RST
                throw new IllegalStateException();
            }
            return;
        } else if (IpNumber.UDP.equals(protocol)) {
            final UdpPacket udpPacket = (UdpPacket) ipPacket.getPayload();
            final UdpPort dstPort = udpPacket.getHeader().getDstPort();
            if (dstPort.valueAsInt() == 5353) {
                return;
            }
//            System.out.println("UDP: " + srcAddr + " -> " + dstAddr + ": " + ByteBufUtil.hexDump(udpPacket.getRawData()));
        } else if (IpNumber.ICMPV6.equals(protocol)) {
            IcmpV6CommonPacket payload = (IcmpV6CommonPacket) ipPacket.getPayload();
//            System.out.println("ICMPv6: " + srcAddr + " -> " + dstAddr);
        } else {
//            System.out.println(protocol.valueAsString());
        }
    }

    public static void main(String[] args) throws Exception {
        final Field innerString = WString.class.getDeclaredField("string");
        innerString.setAccessible(true);
        innerString.set(WindowsTunDevice.TUNNEL_TYPE, "PAN");

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
            final Channel ch = b.bind(new TunAddress("iTCP")).sync().channel();
            // int code = new ProcessBuilder().command("netsh", "interface", "ipv4", "set", "address", "name=\"utun99\"", "source=static", "address=192.168.1.1", "mask=255.255.255.0").start().waitFor();
            // send/receive messages of type TunPacket...
            WindowsNetworkInterfaceEx nix = WindowsNetworkInterfaceEx.getByAlias("iTCP");
            nix.setInterfaceAddress(InterfaceAddressEx.of(InetAddress.getByName("192.168.1.1"), (short) 24));
            ch.closeFuture().sync();
        } finally {
            group.shutdownGracefully();
        }
    }
}
