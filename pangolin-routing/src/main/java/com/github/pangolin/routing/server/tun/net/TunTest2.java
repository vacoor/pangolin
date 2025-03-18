package com.github.pangolin.routing.server.tun.net;

import com.github.pangolin.routing.server.fakedns.DnsEngine;
import com.github.pangolin.routing.server.fakedns.FakeNameService;
import com.github.pangolin.routing.server.fakedns.handler.DatagramFakeDnsServerHandler;
import com.github.pangolin.routing.server.tun.adapter.InterfaceAddressEx;
import com.github.pangolin.routing.server.tun.net.channel.TunAddress;
import com.github.pangolin.routing.server.tun.net.channel.TunChannel;
import com.github.pangolin.routing.server.tun.net.handler.tcp.Tcp4PacketHandler;
import com.github.pangolin.routing.server.tun.net.handler.udp.DatagramPacketCodec;
import com.github.pangolin.routing.server.tun.net.handler.IpPacketCodec;
import com.github.pangolin.routing.support.StandardSocketChannelFactory;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import lombok.extern.slf4j.Slf4j;

/**
 *
 */
@Slf4j
public class TunTest2 {

    public static void main(String[] args) throws Exception {
        final DnsEngine dnsEngine = FakeNameService.create("172.16.0.1/24", "2001:2::/48", 24);

        final String ifname = args.length > 0 ? args[0] : "以太网";
        EventLoopGroup group = new DefaultEventLoopGroup(1);
        try {
            final Bootstrap b = new Bootstrap()
                    .group(group)
                    .channel(TunChannel.class)
                    .handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(final Channel ch) throws Exception {
                            ch.pipeline().addLast(new IpPacketCodec());
//                            ch.pipeline().addLast(new DatagramPacketCodec());
//                            ch.pipeline().addLast(new DatagramFakeDnsServerHandler(dnsEngine, a -> true));
                            ch.pipeline().addLast(new Tcp4PacketHandler(null, new StandardSocketChannelFactory(null)));
                        }
                    });
            final InterfaceAddressEx ifa = InterfaceAddressEx.of("172.16.0.1", 24);
            final Channel ch = b.bind(new TunAddress(ifname, ifa)).sync().channel();
            ch.closeFuture().sync();
        } finally {
            group.shutdownGracefully();
        }
    }

}
