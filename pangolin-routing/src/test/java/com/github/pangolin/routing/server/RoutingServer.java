package com.github.pangolin.routing.server;

import com.github.pangolin.routing.Socks5RoutingServer;
import com.github.pangolin.routing.config.ClashRuleResolver;
import com.github.pangolin.routing.config.PatternResolver;
import com.github.pangolin.routing.config.RulesetResolver;
import com.github.pangolin.routing.config.clash.Configuration;
import com.github.pangolin.routing.node.HealthService;
import com.github.pangolin.routing.node.HealthServiceFactory;
import com.github.pangolin.routing.node.LbServiceInstance;
import com.github.pangolin.routing.node.LoadBalancer;
import com.github.pangolin.routing.node.ServiceInstance;
import com.github.pangolin.routing.node.UrlTestHealthChecker;
import com.github.pangolin.routing.node.spi.ProxyInstance;
import com.github.pangolin.routing.node.spi.ServerResolver;
import com.github.pangolin.routing.pattern.DestinationPattern;
import com.github.pangolin.routing.util.IOUtils;
import com.google.common.collect.Maps;
import freework.codec.Base64;
import freework.net.Http;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

//import com.github.pangolin.routing.pattern.GeoIpPattern;

/**
 *
 */
public class RoutingServer {

    private static ProxyInstance resolve(final String url) {
        final ServiceLoader<ServerResolver> resolvers = ServiceLoader.load(ServerResolver.class);
        for (final ServerResolver resolver : resolvers) {
            if (!resolver.acceptsUrl(url)) {
                continue;
            }
            final ProxyInstance resolved = resolver.resolve(url, null);
            if (null != resolved) {
                return resolved;
            }
        }
        throw new IllegalStateException();
    }

    public static List<ProxyInstance> load(String url) throws Exception {
        final List<ProxyInstance> servers = new ArrayList<>();
        HttpURLConnection httpUrlConnection = null;
        try {
            httpUrlConnection = Http.get(url);
            httpUrlConnection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/107.0.0.0 Safari/537.36");
            final int responseCode = httpUrlConnection.getResponseCode();
            final InputStream in = Base64.wrap(httpUrlConnection.getInputStream(), false);
            final List<String> read = IOUtils.read(in);
            for (String line : read) {
                servers.add(resolve(line));
            }
        } finally {
            Http.close(httpUrlConnection);
        }
        return servers;
    }

    private static String urlEncode(final String text) {
        return Http.urlEncode(text, StandardCharsets.UTF_8.name());
    }

    private static boolean isProxyRule(final String proxyType) {
        return (!"DIRECT".equalsIgnoreCase(proxyType) && !"REJECT".equalsIgnoreCase(proxyType));
    }

    private static final ServiceInstance DIRECT = new ServiceInstance() {
        @Override
        public String name() {
            return "DIRECT";
        }

        @Override
        public ChannelHandler newProxyHandler() {
            return null;
        }

        @Override
        public String toString() {
            return "DIRECT";
        }
    };

    private static final ServiceInstance REJECT = new ServiceInstance() {
        @Override
        public String name() {
            return "REJECT";
        }

        @Override
        public ChannelHandler newProxyHandler() {
            return new ChannelDuplexHandler() {
                @Override
                public void channelRegistered(final ChannelHandlerContext ctx) throws Exception {
                    ctx.close();
                }

                @Override
                public void channelActive(final ChannelHandlerContext ctx) throws Exception {
                    throw new IllegalStateException("reject a channel: " + ctx.channel());
                }

            };
        }

        @Override
        public String toString() {
            return "REJECT";
        }
    };

    static Map<String, HealthService> parseProxies(final List<Configuration.ProxyDefinition> proxyDefinitions, final HealthServiceFactory healthServiceFactory) {
        final Map<String, HealthService> proxies = new HashMap<>();
        for (final Configuration.ProxyDefinition proxyDefinition : proxyDefinitions) {
            final String uri = String.format("%s://%s@%s:%s#%s", proxyDefinition.getType(), urlEncode(proxyDefinition.getPassword()), proxyDefinition.getServer(), proxyDefinition.getPort(), urlEncode(proxyDefinition.getName()));
            final ProxyInstance server = resolve(uri);
            proxies.put(server.getName(), healthServiceFactory.getInstance(server));
        }
        return proxies;
    }

    static void parseProxyGroups(final List<Configuration.ProxyGroupDefinition> proxyGroupDefinitions, final Map<String, ServiceInstance> proxies, final EventLoopGroup group) {
        final Map<String, Configuration.ProxyGroupDefinition> proxyGroupDefinitionMap = new HashMap<>();
        for (Configuration.ProxyGroupDefinition proxyGroupDefinition : proxyGroupDefinitions) {
            proxyGroupDefinitionMap.put(proxyGroupDefinition.getName(), proxyGroupDefinition);
        }
        for (Configuration.ProxyGroupDefinition proxyGroupDefinition : proxyGroupDefinitions) {
            parseProxyGroup(proxyGroupDefinition, proxyGroupDefinitionMap, proxies, group);
        }
    }

    private static ServiceInstance parseProxyGroup(final Configuration.ProxyGroupDefinition proxyGroupDefinition,
                                                   final Map<String, Configuration.ProxyGroupDefinition> proxyGroupDefinitions,
                                                   final Map<String, ServiceInstance> proxies, final EventLoopGroup group) {
        final String name = proxyGroupDefinition.getName();
        final List<String> proxyNames = proxyGroupDefinition.getProxies();
        final List<ServiceInstance> proxiesInGroup = new LinkedList<>();
        for (final String proxyName : proxyNames) {
            final ServiceInstance dependency = proxies.get(proxyName);
            if (proxyName.equals(name)) {
                continue;
            }
            if (null == dependency) {
                final Configuration.ProxyGroupDefinition dependencyGroupDefinition = proxyGroupDefinitions.get(proxyName);
                if (null == dependencyGroupDefinition) {
                    throw new IllegalStateException("Proxy[Group] not found: " + proxyName);
                }
                final ServiceInstance dependencyGroup = parseProxyGroup(dependencyGroupDefinition, proxyGroupDefinitions, proxies, group);
                proxiesInGroup.add(dependencyGroup);
            } else {
                proxiesInGroup.add(dependency);
            }
        }
        final LbServiceInstance proxyGroup = new LbServiceInstance(name, new LoadBalancer(group, proxiesInGroup));
        proxies.put(name, proxyGroup);
        return proxyGroup;
    }

    public static void main(String[] args) throws Exception {
        final NioEventLoopGroup group = new NioEventLoopGroup();
        final UrlTestHealthChecker healthCheck = new UrlTestHealthChecker();
        final HealthServiceFactory healthServiceFactory = new HealthServiceFactory(group, healthCheck);

        final InputStream in = RoutingServer.class.getResourceAsStream("/1695102217152.yml");
        final Configuration conf = Configuration.load(in);
        final List<Configuration.ProxyDefinition> proxyDefinitions = null != conf.getProxies() ? conf.getProxies() : Collections.emptyList();
        final List<Configuration.ProxyGroupDefinition> proxyGroupDefinitions = null != conf.getProxyGroups() ? conf.getProxyGroups() : Collections.emptyList();
        final Map<String, HealthService> proxies = parseProxies(proxyDefinitions, healthServiceFactory);
        final Map<String, ServiceInstance> allProxies = Maps.newHashMap(proxies);
        // parseProxyGroups(proxyGroupDefinitions, allProxies, group);


        final Map<DestinationPattern, ServiceInstance> routingRules = new LinkedHashMap<>();
        final PatternResolver patternResolver = new ClashRuleResolver();
        final RulesetResolver rulesetResolver = new RulesetResolver(patternResolver);

        final ServiceInstance udf = new LbServiceInstance("UDF", new LoadBalancer(group, new ArrayList<>(proxies.values())));
        final Set<DestinationPattern> patterns = rulesetResolver.parseClassPathResource("rule/video.list");
        for (DestinationPattern pattern : patterns) {
            routingRules.put(pattern, udf);
        }

        final List<String> ruleDefinitions = conf.getRules();
        for (String ruleDefinition : ruleDefinitions) {
            final int i = ruleDefinition.lastIndexOf(",");
            if (-1 < i) {
                final String pattern = ruleDefinition.substring(0, i);
                final DestinationPattern p = patternResolver.resolve(pattern);
                if (null == p) {
                    continue;
                }
                final String proxyType = ruleDefinition.substring(i + 1);
                if ("DIRECT".equals(proxyType)) {
                    routingRules.put(p, DIRECT);
                } else if ("REJECT".equals(proxyType)) {
                    routingRules.put(p, REJECT);
                } else if (isProxyRule(proxyType)) {
                    routingRules.put(p, udf);
                }
            }
        }

        final Socks5RoutingServer server = new Socks5RoutingServer(1080);
        server.start(routingRules).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future) throws Exception {
                if (future.isSuccess()) {
                    final InetSocketAddress localAddress = (InetSocketAddress) future.channel().localAddress();
                    System.out.println(String.format("Server started on %s:%s", localAddress.getHostString(), localAddress.getPort()));
                } else {
                    future.cause().printStackTrace();
                }
            }
        }).sync().channel().closeFuture().sync();
    }

}
