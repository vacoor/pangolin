package com.github.pangolin.routing.beta.tun;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import lombok.extern.slf4j.Slf4j;
import org.drasyl.channel.tun.Tun4Packet;
import org.drasyl.channel.tun.TunAddress;
import org.drasyl.channel.tun.TunChannel;
import org.drasyl.channel.tun.TunPacket;
import org.pcap4j.packet.*;
import org.pcap4j.packet.namednumber.IpNumber;
import org.pcap4j.packet.namednumber.IpV4TosPrecedence;
import org.pcap4j.packet.namednumber.IpVersion;
import org.pcap4j.packet.namednumber.TcpPort;
import org.pcap4j.util.ByteArrays;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 *
 */
@Slf4j
public class TunTest {
    private static final int INET4 = 4;
    private static final int INET6 = 6;

    static IpPacket parsePacket(final TunPacket packet) throws IllegalRawDataException {
        final byte[] bytes = ByteBufUtil.getBytes(packet.content());
        return INET6 == packet.version()
                ? IpV6Packet.newPacket(bytes, 0, bytes.length)
                : IpV4Packet.newPacket(bytes, 0, bytes.length);
    }

    private static final ThreadLocal<Integer> sequence = new ThreadLocal<Integer>() {
        @Override
        protected Integer initialValue() {
            return 0;
        }
    };

    private static TcpPacket.Builder ack(final TcpPacket.TcpHeader header, final InetAddress srcAddr, final InetAddress dstAddr) {
        final int seq = header.getAcknowledgmentNumber() != 0 ? header.getAcknowledgmentNumber() : 1;
        return new TcpPacket.Builder()
                .srcAddr(dstAddr)
                .dstAddr(srcAddr)
                .srcPort(header.getDstPort())
                .dstPort(header.getSrcPort())
                .options(Arrays.asList()) // FIXME
                .options(header.getOptions())
                .sequenceNumber(seq)
                .acknowledgmentNumber(header.getSequenceNumber() + 1)
//                .ack(true)
//                .syn(true)
                .paddingAtBuild(true)
                .correctLengthAtBuild(true)
                .correctChecksumAtBuild(true);
    }

    private static IpPacket.Builder ack(final IpPacket.Header ipHeader) {
        return new IpV4Packet.Builder()
                .version(IpVersion.IPV4)
                .tos(((IpV4Packet.IpV4Header) ipHeader).getTos())
                .ttl(((IpV4Packet.IpV4Header) ipHeader).getTtl())
                .identification(((IpV4Packet.IpV4Header) ipHeader).getIdentification())
                .fragmentOffset(((IpV4Packet.IpV4Header) ipHeader).getFragmentOffset())
                .srcAddr(((IpV4Packet.IpV4Header) ipHeader).getDstAddr())
                .dstAddr(((IpV4Packet.IpV4Header) ipHeader).getSrcAddr())
                .protocol(IpNumber.TCP)
                .paddingAtBuild(true)
                .correctLengthAtBuild(true)
                .correctChecksumAtBuild(true);
    }

    public static void channelRead0(final ChannelHandlerContext ctx, final IpPacket ipPacket) {
        final IpPacket.IpHeader ipHeader = ipPacket.getHeader();
        final InetAddress srcAddr = ipHeader.getSrcAddr();
        final InetAddress dstAddr = ipHeader.getDstAddr();
        final IpNumber protocol = ipHeader.getProtocol();

        if (IpNumber.TCP.equals(protocol)) {
            final TcpPacket tcpPacket = (TcpPacket) ipPacket.getPayload();
            final TcpPacket.TcpHeader tcpHeader = tcpPacket.getHeader();
            final int acknowledgmentNumber = tcpHeader.getAcknowledgmentNumber();
            final TcpPort tcpSrcPort = tcpHeader.getSrcPort();
            final TcpPort tcpDstPort = tcpHeader.getDstPort();

            log(tcpHeader, ipHeader, true);

            if (!tcpHeader.getUrg() && !tcpHeader.getAck()
                    && !tcpHeader.getPsh() && !tcpHeader.getRst()
                    && tcpHeader.getSyn() && !tcpHeader.getFin()) {
                // C -- [SYN] --> S (handshake)
                // C <-- [SYN & ACK] -- S (handshake)
                TcpPacket.Builder outTcpPayload = ack(tcpHeader, srcAddr, dstAddr).ack(true).syn(true);
                if (ipPacket instanceof IpV4Packet) {
                    IpV4Packet out = (IpV4Packet) ack(ipHeader).payloadBuilder(outTcpPayload).build();
                    log(((TcpPacket)out.getPayload()).getHeader(), out.getHeader(), false);
                    ctx.writeAndFlush(new Tun4Packet(Unpooled.wrappedBuffer(out.getRawData())));
                }
            } else if (!tcpHeader.getUrg() && tcpHeader.getAck()
                    && !tcpHeader.getPsh() && !tcpHeader.getRst()
                    && !tcpHeader.getSyn() && !tcpHeader.getFin()) {
                // ONLY ACK
                // C -- [ACK] --> S (handshake & disconnect)
//                log.info("[ACK] {}:{} -> {}:{}", srcAddr, tcpSrcPort, dstAddr, tcpDstPort);
                Packet payload = tcpPacket.getPayload();
                System.out.println(payload);

                TcpPacket.Builder outTcpPayload = ack(tcpHeader, srcAddr, dstAddr).ack(true);
                if (ipPacket instanceof IpV4Packet) {
                    IpV4Packet out = (IpV4Packet) ack(ipHeader).payloadBuilder(outTcpPayload).build();
                    log(((TcpPacket)out.getPayload()).getHeader(), out.getHeader(), false);
                    ctx.writeAndFlush(new Tun4Packet(Unpooled.wrappedBuffer(out.getRawData())));
                }


            } else if (!tcpHeader.getUrg() && tcpHeader.getAck()
                    && !tcpHeader.getPsh() && !tcpHeader.getRst()
                    && !tcpHeader.getSyn() && tcpHeader.getFin()) {
                // C -- [ACK & FIN] --> S (disconnect)
//                log.info("[ACK & FIN] {}:{} -> {}:{}", srcAddr, tcpSrcPort, dstAddr, tcpDstPort);

                // C <-- [ACK] -- S (disconnect)
                TcpPacket.Builder outTcpPayload = ack(tcpHeader, srcAddr, dstAddr).ack(true);
                if (ipPacket instanceof IpV4Packet) {
                    IpV4Packet build1 = (IpV4Packet) ack(ipHeader).payloadBuilder(outTcpPayload).build();

                    IpV4Packet out = (IpV4Packet) ack(ipHeader).payloadBuilder(outTcpPayload).build();
                    log(((TcpPacket)out.getPayload()).getHeader(), out.getHeader(), false);
                    ctx.writeAndFlush(new Tun4Packet(Unpooled.wrappedBuffer(build1.getRawData())));
                }
            } else {
//                System.out.println(f + " TCP: " + srcAddr + " -> " + dstAddr);
            }
        } else if (IpNumber.UDP.equals(protocol)) {
            final UdpPacket udpPacket = (UdpPacket) ipPacket.getPayload();
//            System.out.println("UDP: " + srcAddr + " -> " + dstAddr);
        } else if (IpNumber.ICMPV6.equals(protocol)) {
            IcmpV6CommonPacket payload = (IcmpV6CommonPacket) ipPacket.getPayload();
//            System.out.println("ICMPv6: " + srcAddr + " -> " + dstAddr);
        } else {
//            System.out.println(protocol.valueAsString());
        }
    }

    private static void log(final TcpPacket.TcpHeader tcpHeader, final IpPacket.IpHeader ipHeader, boolean inbound) {
        String type = "";
        if (tcpHeader.getUrg()) {
            type += "[URG]";
        }
        if (tcpHeader.getAck()) {
            type += "[ACK]";
        }
        if (tcpHeader.getPsh()) {
            type += "[PSH]";
        }
        if (tcpHeader.getRst()) {
            type += "[RST]";
        }
        if (tcpHeader.getSyn()) {
            type += "[SYN]";
        }
        if (tcpHeader.getFin()) {
            type += "[FIN]";
        }
        if (inbound) {
            log.info("{}:{} - {} -> {}:{}", ipHeader.getSrcAddr(), tcpHeader.getSrcPort().valueAsInt(), type, ipHeader.getDstAddr(), tcpHeader.getDstPort().valueAsInt());
        } else {
            log.info("{}:{} <- {} - {}:{}", ipHeader.getDstAddr(), tcpHeader.getDstPort().valueAsInt(), type, ipHeader.getSrcAddr(), tcpHeader.getSrcPort().valueAsInt());
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
