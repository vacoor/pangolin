package com.github.pangolin.routing.acceptor.tun.net.v2.tcp;

import com.github.pangolin.routing.acceptor.tun.adapter.InterfaceAddressEx;
import com.github.pangolin.routing.acceptor.tun.fakedns.DnsEngine;
import com.github.pangolin.routing.acceptor.tun.net.channel.TunAddress;
import com.github.pangolin.routing.acceptor.tun.net.channel.TunChannel;
import com.github.pangolin.routing.acceptor.tun.net.handler.support.IpPacketCodec;
import com.github.pangolin.routing.support.StandardSocketChannelFactory;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import lombok.extern.slf4j.Slf4j;

/**
 *
 */
@Slf4j
public class TunTest2 {

    public static void main(String[] args) throws Exception {
        final String ifname = args.length > 0 ? args[0] : "TCP-0";

        // TUN EventLoop (single thread — owns the TunChannel and packet demux)
        EventLoopGroup tunGroup    = new DefaultEventLoopGroup(1);
        try {
            final DnsEngine dnsEngine = new DnsEngine() {
                @Override
                public boolean isFakeAddress(byte[] address) {
                    return false;
                }

                @Override
                public String getHostByAddress(byte[] address) {
                    return null;
                }

                @Override
                public io.netty.handler.codec.dns.DatagramDnsResponse lookup(io.netty.handler.codec.dns.DatagramDnsQuery query) {
                    return null;
                }
            };

            final Bootstrap b = new Bootstrap()
                    .group(tunGroup)
                    .channel(TunChannel.class)
                    .handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            ch.pipeline().addLast(new IpPacketCodec());
                            ch.pipeline().addLast(new TcpMultiplexHandler(dnsEngine, new StandardSocketChannelFactory(null)));
                        }
                    });

            final InterfaceAddressEx ifa = InterfaceAddressEx.of("172.16.0.1", 24);
            final Channel ch = b.bind(new TunAddress(ifname, ifa)).sync().channel();
            ch.closeFuture().sync();
        } finally {
            tunGroup.shutdownGracefully();
        }
    }

}
