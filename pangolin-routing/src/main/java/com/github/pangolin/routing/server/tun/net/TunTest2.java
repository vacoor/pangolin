package com.github.pangolin.routing.server.tun.net;

import com.github.pangolin.routing.handler.internal.server.support.StandardSocketChannelFactory;
import com.github.pangolin.routing.server.tun.net.channel.TunAddress;
import com.github.pangolin.routing.server.tun.net.channel.TunChannel;
import com.github.pangolin.routing.server.tun.net.handler.IpPacketCodec;
import com.github.pangolin.routing.server.tun.net.handler.Tcp4PacketHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import lombok.extern.slf4j.Slf4j;

/**
 *
 */
@Slf4j
public class TunTest2 {

    public static void main(String[] args) throws Exception {

        final String ifname = args.length > 0 ? args[0] : "utun8";
        EventLoopGroup group = new DefaultEventLoopGroup(1);
        try {
            final Bootstrap b = new Bootstrap()
                    .group(group)
                    .channel(TunChannel.class)
                    .handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(final Channel ch) throws Exception {
                            ch.pipeline().addLast(new IpPacketCodec());
                            ch.pipeline().addLast(new Tcp4PacketHandler(null, new StandardSocketChannelFactory(null)));
                        }
                    });
            final Channel ch = b.bind(new TunAddress(ifname)).sync().channel();
            ch.closeFuture().sync();
        } finally {
            group.shutdownGracefully();
        }
    }

}
