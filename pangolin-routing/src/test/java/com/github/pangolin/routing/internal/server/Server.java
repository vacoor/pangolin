package com.github.pangolin.routing.internal.server;

import com.github.pangolin.routing.RoutingRule;
import com.github.pangolin.routing.Socks5RoutingServer;
import com.github.pangolin.routing.internal.server.clash.Configuration;
import com.github.pangolin.routing.pattern.DestinationPattern;
import com.github.pangolin.routing.pattern.DomainPattern;
import com.github.pangolin.routing.pattern.InetSubnetPattern;
import freework.codec.Base64;
import freework.net.Http;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.util.NetUtil;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

/**
 *
 */
public class Server {

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

    private static DestinationPattern resolvePattern(final String destination) {
        // return new DomainPattern("**");
        final String[] segments = destination.split("/", 2);
        if (segments.length == 2 && isDigit(segments[1]) && (NetUtil.isValidIpV4Address(segments[0]) || NetUtil.isValidIpV6Address(segments[0]))) {
            final int cidrPrefix = Integer.parseInt(segments[1]);
            return new InetSubnetPattern(segments[0], cidrPrefix);
        }
        return new DomainPattern(destination);
    }

    private static boolean isDigit(final String text) {
        if (null != text && 0 < text.length()) {
            for (int i = 0; i < text.length(); i++) {
                if (!Character.isDigit(text.charAt(i))) {
                    return false;
                }
            }
            return true;
        }
        return false;
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

    private static final ProxyServer DIRECT = new ProxyServer() {
        @Override
        public String name() {
            return "DIRECT";
        }

        @Override
        public ChannelHandler newProxyHandler() {
            return null;
        }
    };


    public static void main(String[] args) throws Exception {
        final InputStream in = Server.class.getResourceAsStream("/1695102217152.yml");
        final Configuration conf = Configuration.load(in);
        final Map<String, ProxyServer> servers = new LinkedHashMap<>();

        final List<Configuration.Proxy> proxies = null != conf.getProxies() ? conf.getProxies() : Collections.emptyList();
        for (final Configuration.Proxy proxy : proxies) {
            final String uri = String.format("%s://%s@%s:%s#%s", proxy.getType(), urlEncode(proxy.getPassword()), proxy.getServer(), proxy.getPort(), urlEncode(proxy.getName()));
            final ProxyServer server = resolve(uri);
            servers.put(server.name(), server);
        }

        /*
        final List<Configuration.ProxyGroup> proxyGroups = null != conf.getProxyGroups() ? conf.getProxyGroups() : Collections.emptyList();
        for (Configuration.ProxyGroup proxyGroup : proxyGroups) {
            final List<String> proxiesInGroup = proxyGroup.getProxies();
            final List<ProxyServer> serversInGroup = new ArrayList<>();
            for (final String proxy : proxiesInGroup) {
                ProxyServer server = servers.get(proxy);
                if (null != server) {
                    serversInGroup.add(server);
                }
            }
            final String name = proxyGroup.getName();
            LbProxyServer lb = new LbProxyServer(name, serversInGroup);
            lb.startHeathCheck();
            servers.put(name, lb);
        }
        */

        servers.put(DIRECT.name(), DIRECT);
        servers.put("REJECT", DIRECT);

        final ProxyServer lb = new LbProxyServer("lb", new ArrayList<>(servers.values()));
        ((LbProxyServer) lb).startHeathCheck();
        final List<RoutingRule> routingRules = new LinkedList<>();
        final RulePatternParser rulePatternParser = new RulePatternParser();
        final List<String> rules = conf.getRules();
        for (String rule : rules) {
            final int i = rule.lastIndexOf(",");
            if (-1 < i) {
                final String pattern = rule.substring(0, i);
                final String proxyType = rule.substring(i + 1);
                if (isProxyRule(proxyType)) {
                    // final ProxyServer proxyServer = servers.get(proxyType);
                    final ProxyServer proxyServer = lb;
                    String p = rulePatternParser.parse(rule);
                    if (null != p && null != proxyServer) {
                        routingRules.add(new RoutingRule(resolvePattern(pattern), proxyServer::newProxyHandler));
                    }
                }
            }
        }
        final InputStream ruleSetIn = Server.class.getResourceAsStream("/rule/video.list");
        final InputStreamReader ruleSetReader = new InputStreamReader(ruleSetIn);
        try {
            Set<String> patterns = rulePatternParser.parse(ruleSetReader);
            for (String pattern : patterns) {
                routingRules.add(new RoutingRule(resolvePattern(pattern), lb::newProxyHandler));
            }
        } finally {
            ruleSetReader.close();
            ruleSetIn.close();
        }

//        final String subscribeUrl = "https://sub1.smallstrawberry.com/api/v1/client/subscribe?token=1ab79cc4b202d916cdc8e375c7b0326";
//        List<ProxyServer> servers = load(subscribeUrl);
//        LbProxyServer lb = new LbProxyServer("lb", new LinkedList<>(servers.values()));

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
        /*-
        Connect through local to http://bing.com/ failed.
        Error: Connection refused
         */
    }

}
