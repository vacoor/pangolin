package com.github.pangolin.routing.server;

import com.github.pangolin.routing.Socks5RoutingServer;
import com.github.pangolin.routing.config.ClashRuleResolver;
import com.github.pangolin.routing.config.PatternResolver;
import com.github.pangolin.routing.config.RulesetResolver;
import com.github.pangolin.routing.config.clash.Configuration;
import com.github.pangolin.routing.node.HealthProxyServer;
import com.github.pangolin.routing.node.HealthProxyServerImpl;
import com.github.pangolin.routing.node.LbHealthProxyServer;
import com.github.pangolin.routing.node.heath.UrlTestHealthCheck;
import com.github.pangolin.routing.node.spi.ProxyServer;
import com.github.pangolin.routing.node.spi.ServerResolver;
import com.github.pangolin.routing.pattern.DestinationPattern;
import com.github.pangolin.routing.pattern.GeoIpPattern;
import com.github.pangolin.routing.util.IOUtils;
import com.maxmind.db.CHMCache;
import com.maxmind.db.DatabaseRecord;
import com.maxmind.db.Metadata;
import com.maxmind.db.Reader;
import freework.codec.Base64;
import freework.net.Http;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.nio.NioEventLoopGroup;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 *
 */
public class RoutingServer {

    private static ProxyServer resolve(final String url) {
        final ServiceLoader<ServerResolver> resolvers = ServiceLoader.load(ServerResolver.class);
        for (final ServerResolver resolver : resolvers) {
            if (!resolver.acceptsUrl(url)) {
                continue;
            }
            final ProxyServer resolved = resolver.resolve(url, null);
            if (null != resolved) {
                return resolved;
            }
        }
        throw new IllegalStateException();
    }

    public static List<ProxyServer> load(String url) throws Exception {
        final List<ProxyServer> servers = new ArrayList<>();
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

    private static final HealthProxyServer DIRECT = new HealthProxyServer() {
        @Override
        public String name() {
            return "DIRECT";
        }

        @Override
        public boolean isPassingCheck() {
            return true;
        }

        @Override
        public ChannelHandler newProxyHandler() {
            return null;
        }
    };

    public static void main(String[] args) throws Exception {
        final InputStream db = RoutingServer.class.getResourceAsStream("/Country.mmdb");
        final Reader reader = new Reader(db, new CHMCache());
        Metadata metadata = reader.getMetadata();
        new GeoIpPattern(reader, "CN").matches(new InetSocketAddress("180.101.50.242", 80));
        /*
         */

        final InputStream in = RoutingServer.class.getResourceAsStream("/1695102217152.yml");
        final Configuration conf = Configuration.load(in);
        final Map<String, HealthProxyServer> servers = new LinkedHashMap<>();

        final NioEventLoopGroup group = new NioEventLoopGroup();
        final UrlTestHealthCheck healthCheck = new UrlTestHealthCheck();

        final List<Configuration.ProxyDefinition> proxyDefinitions = null != conf.getProxies() ? conf.getProxies() : Collections.emptyList();
        for (final Configuration.ProxyDefinition proxyDefinition : proxyDefinitions) {
            final String uri = String.format("%s://%s@%s:%s#%s", proxyDefinition.getType(), urlEncode(proxyDefinition.getPassword()), proxyDefinition.getServer(), proxyDefinition.getPort(), urlEncode(proxyDefinition.getName()));
            final ProxyServer server = resolve(uri);
            servers.put(server.name(), new HealthProxyServerImpl(server, group, healthCheck).start());
        }

        final List<Configuration.ProxyGroupDefinition> proxyGroupDefinitions = null != conf.getProxyGroups() ? conf.getProxyGroups() : Collections.emptyList();
        final Map<String, Configuration.ProxyGroupDefinition> lazyGroupDefinitions = new HashMap<>();
        for (Configuration.ProxyGroupDefinition proxyGroupDefinition : proxyGroupDefinitions) {
            final String name = proxyGroupDefinition.getName();
            final List<String> proxiesInGroup = proxyGroupDefinition.getProxies();
            final List<HealthProxyServer> serversInGroup = new ArrayList<>();
            boolean lazy = false;
            for (final String proxy : proxiesInGroup) {
                final HealthProxyServer dependency = servers.get(proxy);
                if (null == dependency) {
                    lazy = true;
                    break;
                }
                serversInGroup.add(dependency);
            }
            if (lazy) {
                lazyGroupDefinitions.put(name, proxyGroupDefinition);
                continue;
            }
            LbHealthProxyServer lb = new LbHealthProxyServer(name, serversInGroup);
            lb.startHeathCheck();
            servers.put(name, lb);
        }
        while (!lazyGroupDefinitions.isEmpty()) {
            for (String name : lazyGroupDefinitions.keySet()) {
                final List<HealthProxyServer> serversInGroup = new ArrayList<>();
                Configuration.ProxyGroupDefinition proxyGroupDefinition = lazyGroupDefinitions.get(name);
                final List<String> proxies1 = proxyGroupDefinition.getProxies();
                boolean lazy = false;
                for (String s : proxies1) {
                    HealthProxyServer dependency = servers.get(s);
                    if (null == dependency) {
                        if (!lazyGroupDefinitions.containsKey(s) || s.equalsIgnoreCase(name)) {
                            // WARN
                            continue;
                        }
                        lazy = true;
                        break;
                    }
                    serversInGroup.add(dependency);
                }
                if (!lazy) {
                    LbHealthProxyServer lb = new LbHealthProxyServer(name, serversInGroup);
                    lb.startHeathCheck();
                    servers.put(name, lb);
                    lazyGroupDefinitions.remove(name);
                }
            }
        }

        servers.put(DIRECT.name(), DIRECT);
        servers.put("REJECT", DIRECT);

        final Map<DestinationPattern, HealthProxyServer> routingRules = new LinkedHashMap<>();

        final PatternResolver patternResolver = new ClashRuleResolver();
        final RulesetResolver rulesetResolver = new RulesetResolver(patternResolver);
        final HealthProxyServer lb = new LbHealthProxyServer("udf", new ArrayList<>(servers.values())).startHeathCheck();

        final Set<DestinationPattern> patterns = rulesetResolver.parseClassPathResource("rule/video.list");
        for (DestinationPattern pattern : patterns) {
            routingRules.put(pattern, lb);
        }

        final List<String> ruleDefinitions = conf.getRules();
        for (String ruleDefinition : ruleDefinitions) {
            final int i = ruleDefinition.lastIndexOf(",");
            if (-1 < i) {
                final String pattern = ruleDefinition.substring(0, i);
                final String proxyType = ruleDefinition.substring(i + 1);
                if (isProxyRule(proxyType)) {
//                    final HealthProxyServer proxyServer = lb;
                    final HealthProxyServer proxyServer = servers.get(proxyType);
                    DestinationPattern p = patternResolver.resolve(pattern);
                    if (null != p && null != proxyServer) {
                        routingRules.put(p, proxyServer);
                    }
                }
            }
        }

//        final String subscribeUrl = "https://sub1.smallstrawberry.com/api/v1/client/subscribe?token=1ab79cc4b202d916cdc8e375c7b0326";
//        List<ProxyServer> servers = load(subscribeUrl);
//        LbHealthProxyServer lb = new LbHealthProxyServer("lb", new LinkedList<>(servers.values()));

//        final List<RoutingRule> routingRules = new LinkedList<>();
//        for (String proxyPattern : proxyPatterns) {
//            routingRules.add(new RoutingRule(resolvePattern(proxyPattern), lb::newProxyHandler));
//        }


//        servers = servers.subList(servers.size() - 1, servers.size());

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
