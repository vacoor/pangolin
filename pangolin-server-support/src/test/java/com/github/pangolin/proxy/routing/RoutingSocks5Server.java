package com.github.pangolin.proxy.routing;

import com.github.pangolin.proxy.routing.config.RoutingRuleProvider;
import com.github.pangolin.server.NettyServer;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;

import java.util.List;

/**
 *
 */
public class RoutingSocks5Server {

    public static void main(String[] args) throws Exception {
        final List<RoutingRule> routingRules = RoutingRuleProvider.loadRoutingRules();

        new NettyServer(1080).start(true, new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel channel) throws Exception {
                channel.pipeline().addLast(new Socks5ProxyRoutingServerHandler(routingRules));
            }
        }).sync().channel().closeFuture().sync();
    }

}
