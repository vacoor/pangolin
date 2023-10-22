package com.github.pangolin.routing.server;

import com.github.pangolin.routing.RoutingSocks5ServerHandler;
import com.github.pangolin.routing.config.LocalProxyServerProviderLoader;
import com.github.pangolin.routing.config.clash.ClashProxyServerProviderFactory;
import com.github.pangolin.routing.config.clash.ClashRuleFactory;
import com.github.pangolin.routing.config.clash.ClashRuleResolver;
import com.github.pangolin.routing.pattern.DestinationPattern;
import com.github.pangolin.routing.proxy.ComposedProxyServerProvider;
import com.github.pangolin.routing.proxy.ProxyServerProvider;
import com.github.pangolin.routing.proxy.RuleBasedRoutingProxyServer;
import com.github.pangolin.server.NettyServer;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.nio.NioEventLoopGroup;

import java.net.InetSocketAddress;
import java.net.URL;
import java.util.Map;

/**
 *
 */
public class ServerMain {

    public static void main(String[] args) throws Exception {
        final NioEventLoopGroup group = new NioEventLoopGroup();

        final ProxyServerProvider localProxyServerProvider = LocalProxyServerProviderLoader.load(LocalProxyServerProviderLoader.class.getResource("/conf/proxies.conf"));
        final ClashProxyServerProviderFactory factory = ClashProxyServerProviderFactory.create("https://sub1.smallstrawberry.com/api/v1/client/subscribe?token=1ab79cc4b202d916cdc8e375c7b0326");
        final ProxyServerProvider remoteProxyServerProvider = factory.getProxyServerProvider(group);

        final ProxyServerProvider proxyServerProvider = new ComposedProxyServerProvider(remoteProxyServerProvider, localProxyServerProvider);
        final RuleBasedRoutingProxyServer router = new RuleBasedRoutingProxyServer("RuleBasedRouter", proxyServerProvider);

        final URL url = ServerMain.class.getResource("/conf/default.conf");
        Map<DestinationPattern, String> rules = ClashRuleFactory.parseRules(
                url,
                ClashRuleResolver.DOMAIN,
                ClashRuleResolver.DOMAIN_SUFFIX,
                ClashRuleResolver.DOMAIN_KEYWORD,
                ClashRuleResolver.IP_CIDR,
                ClashRuleResolver.IP_CIDR_6,
                ClashRuleResolver.RULE_SET
        );
        for (Map.Entry<DestinationPattern, String> entry : rules.entrySet()) {
            System.out.println(String.format("%s -> %s", entry.getKey(), entry.getValue()));
            router.addRouting(entry.getKey(), entry.getValue());
        }

//        Forwarder forwarder = new Forwarder(proxyServerProvider, new NioEventLoopGroup(), new NioEventLoopGroup());
//        forwarder.addForwarding(3389, "TUNNEL", InetSocketAddress.createUnresolved("10.188.71.3", 3389));

        final NettyServer server = new NettyServer(1080);
        server.start(true, new RoutingSocks5ServerHandler(router)).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future) throws Exception {
                if (future.isSuccess()) {
                    final InetSocketAddress localAddress = (InetSocketAddress) future.channel().localAddress();
                    System.out.println(String.format("ProxyServer started on %s:%s", localAddress.getHostString(), localAddress.getPort()));
                } else {
                    future.cause().printStackTrace();
                }
            }
        }).sync().channel().closeFuture().sync();
    }

}
