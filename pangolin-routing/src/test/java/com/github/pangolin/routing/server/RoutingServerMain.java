package com.github.pangolin.routing.server;

import com.github.pangolin.routing.Socks5RoutingServer;
import com.github.pangolin.routing.config.PatternResolver;
import com.github.pangolin.routing.config.clash.ClashRuleFactory;
import com.github.pangolin.routing.config.clash.ClashRuleResolver;
import com.github.pangolin.routing.config.clash.Configuration;
import com.github.pangolin.routing.config.clash.RulesetResolver;
import com.github.pangolin.routing.internal.node.LoadBalanceProxyServer;
import com.github.pangolin.routing.internal.node.ProxyServer;
import com.github.pangolin.routing.internal.node.UrlTestHealthChecker;
import com.github.pangolin.routing.pattern.DestinationPattern;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.nio.NioEventLoopGroup;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 *
 */
public class RoutingServerMain {

    public static void main(String[] args) throws Exception {
        final NioEventLoopGroup group = new NioEventLoopGroup();
        final UrlTestHealthChecker healthCheck = new UrlTestHealthChecker(group);

        final InputStream in = RoutingServerMain.class.getResourceAsStream("/1695102217152.yml");
        final Configuration conf = Configuration.load(in);
        final List<Configuration.ProxyDefinition> proxyDefinitions = null != conf.getProxies() ? conf.getProxies() : Collections.emptyList();
        final List<Configuration.ProxyGroupDefinition> proxyGroupDefinitions = null != conf.getProxyGroups() ? conf.getProxyGroups() : Collections.emptyList();

        final Map<String, ProxyServer> proxies = ClashRuleFactory.parseProxies(proxyDefinitions);
        final ProxyServer extranetProxy = new LoadBalanceProxyServer("extranet", healthCheck, new ArrayList<>(proxies.values()), group);
        // parseProxyGroups(proxyGroupDefinitions, allProxies, group);

        final Map<DestinationPattern, ProxyServer> routingRules = new LinkedHashMap<>();
        final PatternResolver patternResolver = new ClashRuleResolver();
        final RulesetResolver rulesetResolver = new RulesetResolver(patternResolver);
        final Set<DestinationPattern> patterns = rulesetResolver.resolveClassPathResource("rule/video.list");
        for (DestinationPattern pattern : patterns) {
            routingRules.put(pattern, extranetProxy);
        }
        final List<String> ruleDefinitions = conf.getRules();
        routingRules.putAll(ClashRuleFactory.parseRules(ruleDefinitions, patternResolver, extranetProxy));

        final Socks5RoutingServer server = new Socks5RoutingServer(1080);
        server.start(routingRules).addListener(new ChannelFutureListener() {
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
