package com.github.pangolin.routing;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import org.drasyl.channel.tun.TunAddress;
import org.drasyl.channel.tun.TunChannel;

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
                            super.channelRead(ctx, msg);
                        }
                    });
            final Channel ch = b.bind(new TunAddress()).sync().channel();
            // send/receive messages of type TunPacket...
            ch.closeFuture().sync();
        } finally {
            group.shutdownGracefully();
        }
    }
}
