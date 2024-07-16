package com.github.pangolin.routing;

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
import org.pcap4j.packet.Packet;
import org.pcap4j.packet.UnknownPacket;
import org.pcap4j.packet.factory.PacketFactories;
import org.pcap4j.packet.namednumber.DataLinkType;

/**
 *
 */
public class TunTest {
    public static void main(String[] args) throws Exception {
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
                            if (6 == version) {
                                final Tun6Packet packet6 = (Tun6Packet) packet;
                                System.out.println(packet6);
                            } else {
                                final Tun4Packet packet4 = (Tun4Packet) packet;
                                System.out.println(packet4);
                            }
                            final byte[] ba = ByteBufUtil.getBytes(packet.content());
                            UnknownPacket packets = UnknownPacket.newPacket(ba, 0, ba.length);
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
