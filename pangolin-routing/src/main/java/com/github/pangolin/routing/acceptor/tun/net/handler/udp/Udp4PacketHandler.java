package com.github.pangolin.routing.acceptor.tun.net.handler.udp;

import com.github.pangolin.routing.acceptor.tun.net.codec.IpPacketHandler;
import com.github.pangolin.routing.acceptor.tun.net.codec.UdpPacketBuf;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;

import java.net.Inet4Address;
import java.net.InetSocketAddress;

/**
 *
 */
public class Udp4PacketHandler extends IpPacketHandler<UdpPacketBuf> {

    private static final byte PROTO_UDP = 17;

    public Udp4PacketHandler() {
        super(PROTO_UDP);
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final UdpPacketBuf pkt) throws Exception {
        final Inet4Address srcAddr = (Inet4Address) pkt.srcAddr();
        final Inet4Address dstAddr = (Inet4Address) pkt.dstAddr();
        final int srcPort = pkt.udpSrcPort();
        final int dstPort = pkt.udpDstPort();

        if (dstPort == 53 && "198.18.0.254".equals(dstAddr.getHostAddress())) {
            final InetSocketAddress sender = new InetSocketAddress(srcAddr, srcPort);
            final InetSocketAddress recipient = new InetSocketAddress(dstAddr, dstPort);
            final ByteBuf data = pkt.udpPayloadSlice().retainedSlice();

            ctx.fireChannelRead(new DatagramPacket(data, recipient, sender));
        }
    }

}
