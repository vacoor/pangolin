package com.github.pangolin.routing.server;

import com.github.pangolin.routing.Socks5RoutingServer;
import com.github.pangolin.routing.config.clash.ClashRuleFactory;
import com.github.pangolin.routing.config.clash.ClashRuleResolver;
import com.github.pangolin.routing.config.clash.RulesetResolver;
import com.github.pangolin.routing.internal.node.health.UrlTestHealthChecker;
import com.github.pangolin.routing.config.clash.ClashProxyServerProviderFactory;
import com.github.pangolin.routing.internal.proxy.ComposedProxyServerProvider;
import com.github.pangolin.routing.internal.proxy.LocalProxyServerProviderLoader;
import com.github.pangolin.routing.internal.proxy.ProxyServerProvider;
import com.github.pangolin.routing.internal.proxy.rule.RuleBasedRoutingProxyServer;
import com.github.pangolin.routing.pattern.DestinationPattern;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.nio.NioEventLoopGroup;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URL;
import java.util.Map;
import java.util.Set;

/**
 *
 */
public class RoutingServerMain {

    public static void main(String[] args) throws Exception {
        ProxySelector aDefault = ProxySelector.getDefault();


        final NioEventLoopGroup group = new NioEventLoopGroup();

        final ProxyServerProvider localProxyServerProvider = LocalProxyServerProviderLoader.load(LocalProxyServerProviderLoader.class.getResource("/proxies.conf"));
        final ClashProxyServerProviderFactory factory = ClashProxyServerProviderFactory.create("https://sub1.smallstrawberry.com/api/v1/client/subscribe?token=1ab79cc4b202d916cdc8e375c7b0326");
        final ProxyServerProvider remoteProxyServerProvider = factory.getProxyServerProvider(group);

        final ProxyServerProvider proxyServerProvider = new ComposedProxyServerProvider(remoteProxyServerProvider, localProxyServerProvider);
        final RuleBasedRoutingProxyServer router = new RuleBasedRoutingProxyServer("RuleBasedRouter", proxyServerProvider);

        /*
        final String[] ruleSets = {
                "rule/video.list",
                "rule/twitter.list",
                "rule/github.list"
        };

        final RulesetResolver rulesetResolver = new RulesetResolver(new ClashRuleResolver());
        for (final String ruleSet : ruleSets) {
            final Set<DestinationPattern> patterns = rulesetResolver.resolveClassPathResource(ruleSet);
            for (DestinationPattern pattern : patterns) {
                router.addRouting(pattern, "一元机场");
            }
        }
        */
        final URL url = RoutingServerMain.class.getResource("/default.conf");
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

        final Socks5RoutingServer server = new Socks5RoutingServer(1080);
        server.start(router).addListener(new ChannelFutureListener() {
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
