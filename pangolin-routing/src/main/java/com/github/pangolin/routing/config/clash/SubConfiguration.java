package com.github.pangolin.routing.config.clash;

import com.github.pangolin.routing.config.RulesParser;
import com.github.pangolin.routing.handler.internal.client.ss.SsProxyHandler;
import com.github.pangolin.routing.handler.internal.client.ss.crypto.CipherAlgorithm;
import com.github.pangolin.routing.handler.internal.client.ss.crypto.spi.CipherAlgorithmSpi;
import com.github.pangolin.routing.handler.internal.server.Socks5ProxyServerHandler;
import com.github.pangolin.routing.proxy.ProxyServer;
import com.github.pangolin.routing.proxy.ProxyServerProvider;
import com.github.pangolin.routing.proxy.ProxySocketChannelFactory;
import com.github.pangolin.routing.proxy.group.lb.ServerFactory;
import com.github.pangolin.routing.proxy.group.rule.RuleBasedProxyServer;
import com.github.pangolin.routing.rule.RulesProvider;
import com.github.pangolin.routing.rule.pattern.DestinationPattern;
import com.github.pangolin.server.NettyServer;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.netflix.loadbalancer.LoadBalancerStats;
import freework.net.Http;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
public class SubConfiguration {
    private final URL url;
    private final ServerFactory factory;

    private volatile Map<String, ProxyServer> nameToProxyMap;
    private volatile Map<String, ProxyServer> nameToProxyGroupMap;
    private volatile Map<DestinationPattern, String> rulesMap;

    public SubConfiguration(final URL url, final ServerFactory factory) {
        this.url = url;
        this.factory = factory;
    }

    public RulesProvider getRulesProvider() {
        return new RulesProvider() {
            @Override
            public Map<DestinationPattern, String> getRules() {
                return Collections.unmodifiableMap(rulesMap);
            }
        };
    }

    public ProxyServerProvider getServerProvider() {
        return new ProxyServerProvider() {
            @Override
            public Collection<ProxyServer> getInstances() {
                List<ProxyServer> ret = Lists.newArrayList(nameToProxyMap.values());
                ret.addAll(nameToProxyGroupMap.values());
                return ret;
            }

            @Override
            public ProxyServer getInstance(final String name) {
                ProxyServer proxyServer = nameToProxyMap.get(name);
                proxyServer = null != proxyServer ? proxyServer : nameToProxyGroupMap.get(name);
                return proxyServer;
            }
        };
    }

    public SubConfiguration refresh() throws IOException {
        refresh(loadClashConfiguration(url));
        return this;
    }

    Map<String, ProxyServer> refresh(final ClashConfiguration conf) throws IOException {
        final List<ClashConfiguration.ProxyDefinition> proxyDefinitions = nvl(conf.getProxies(), Collections.emptyList());
        final List<ClashConfiguration.ProxyGroupDefinition> proxyGroupDefinitions = nvl(conf.getProxyGroups(), Collections.emptyList());
        final List<String> rules = nvl(conf.getRules(), Collections.emptyList());

        final Map<String, ProxyServer> nameToProxyMap = parseProxies(proxyDefinitions);
        final Map<String, ProxyServer> nameToProxyGroupMap = parseProxyGroups(proxyGroupDefinitions, nameToProxyMap);
        final Map<DestinationPattern, String> rulesMap = RulesParser.parseRules(rules, url);

        this.nameToProxyMap = nameToProxyMap;
        this.nameToProxyGroupMap = nameToProxyGroupMap;
        this.rulesMap = rulesMap;
        return nameToProxyMap;
    }

    private Map<String, ProxyServer> parseProxyGroups(final List<ClashConfiguration.ProxyGroupDefinition> definitions,
                                                      final Map<String, ProxyServer> nameToProxyMap) {
        final Map<String, ProxyServer> nameToGroupMap = Maps.newHashMap();
        final Map<String, ClashConfiguration.ProxyGroupDefinition> definitionMap = definitions.stream()
                .collect(Collectors.toMap(ClashConfiguration.ProxyGroupDefinition::getName, Function.identity(), (prev, next) -> next));
        for (final Map.Entry<String, ClashConfiguration.ProxyGroupDefinition> entry : Maps.newHashMap(definitionMap).entrySet()) {
            if (!nameToGroupMap.containsKey(entry.getKey())) {
                parseProxyGroup(entry.getValue(), nameToProxyMap, nameToGroupMap, definitionMap);
            }
        }
        return nameToGroupMap;
    }

    private ProxyServer parseProxyGroup(final ClashConfiguration.ProxyGroupDefinition definition,
                                        final Map<String, ProxyServer> nameToProxyMap,
                                        final Map<String, ProxyServer> nameToGroupMap,
                                        final Map<String, ClashConfiguration.ProxyGroupDefinition> definitionMap) {
        final List<String> referenceNames = nvl(definition.getProxies(), Collections.emptyList());
        final List<ProxyServer> referencesToUse = Lists.newArrayList();
        for (final String referenceName : referenceNames) {
            ProxyServer reference = nameToProxyMap.get(referenceName);
            if (null == reference) {
                final ClashConfiguration.ProxyGroupDefinition definitionRef = definitionMap.remove(referenceName);
                if (null == definitionRef) {
                    continue;
                }
                reference = parseProxyGroup(definitionRef, nameToProxyMap, nameToGroupMap, definitionMap);
                nameToGroupMap.put(referenceName, reference);
            }
            referencesToUse.add(reference);
        }

        final String name = definition.getName();
        final String type = definition.getType();
        final String url = definition.getUrl();

        // UrlTestHealthChecker urlTestHealthChecker = new UrlTestHealthChecker(url, 3000, )
        final ProxyServer group = factory.createServerGroup(name, type, url, referencesToUse);
        nameToGroupMap.put(name, group);
        return group;
    }

    private Map<String, ProxyServer> parseProxies(final List<ClashConfiguration.ProxyDefinition> definitions) {
        return definitions.stream()
                .filter(definition -> !"0.0.0.0".equals(definition.getServer()))
                .map(this::parseProxy)
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(ProxyServer::getName, Function.identity(), (prev, next) -> next));
    }

    private ProxyServer parseProxy(final ClashConfiguration.ProxyDefinition definition) {
        final String cipher = definition.getCipher();
        final String password = definition.getPassword();
        final String userInfo = StringUtils.hasText(cipher) ? String.format("%s:%s", cipher, password) : password;

        final String uri = String.format("%s://%s@%s:%s#%s", definition.getType(), urlEncode(userInfo), definition.getServer(), definition.getPort(), urlEncode(definition.getName()));
        log.debug("resolve uri: {}", uri);
        log.info("Parse proxy uri: {}", uri);
        return factory.resolve(null, uri);
    }

    private static String urlEncode(final String text) {
        return Http.urlEncode(text, StandardCharsets.UTF_8.name());
    }

    private <T> T nvl(final T val, final T def) {
        return null != val ? val : def;
    }

    private static ClashConfiguration loadClashConfiguration(final URL subscribeUrl) throws IOException {
        log.debug("Load Clash configuration from '{}'...", subscribeUrl);
        HttpURLConnection httpUrlConnection = null;
        try {
            httpUrlConnection = (HttpURLConnection) subscribeUrl.openConnection();
            httpUrlConnection.setRequestProperty("Accept", "application/json, text/plain, */*");
            httpUrlConnection.setRequestProperty("pragma", "No-Cache");
            httpUrlConnection.setRequestProperty("User-Agent", "ClashforWindows/0.19.25");
            final int responseCode = httpUrlConnection.getResponseCode();
            Preconditions.checkState(HttpURLConnection.HTTP_OK == responseCode, "responseCode = %s", responseCode);
            return ClashConfiguration.load(httpUrlConnection.getInputStream());
        } finally {
            Http.close(httpUrlConnection);
            log.debug("Load Clash configuration from '{}' completed", subscribeUrl);
        }
    }

    public static void main(String[] args) throws Exception {
        final LoadBalancerStats stats = new LoadBalancerStats();
        final ServerFactory serverFactory = new ServerFactory(stats);
        SubConfiguration config = new SubConfiguration(new URL(""), serverFactory);
        config.refresh();

        final RuleBasedProxyServer proxyServer = new RuleBasedProxyServer("R", config.getRulesProvider(), config.getServerProvider());
        final ProxySocketChannelFactory factory = new ProxySocketChannelFactory(proxyServer, Arrays.asList("127.0.0.1", "localhost"));

        final String hostStr = System.getProperty("server.host");
        final String portStr = System.getProperty("server.port", "1082");
        final int proxyServerPort = Integer.parseInt(portStr);
        final NettyServer server = new NettyServer(hostStr, proxyServerPort);
        ChannelFuture proxyServerChannel = server.start(true, new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
                /*
                final Socks5MixinServerHandshaker socks5Handshaker = new Socks5MixinServerHandshaker(new Socks5ProxyServerHandler(null, null, factory));
                final Socks4MixinServerHandshaker socks4Handshaker = new Socks4MixinServerHandshaker(new Socks4ProxyServerHandler(null, factory));
                final HttpMixinServerHandshaker httpHandshaker = new HttpMixinServerHandshaker(
                        new HttpProxyServerHandler(null, null, factory)
                );
                ch.pipeline().addLast(new MixinServerInitializer(socks5Handshaker, socks4Handshaker, httpHandshaker));
                */
                ch.pipeline().addLast(new Socks5ProxyServerHandler(null, null, factory));
            }
        });
        proxyServerChannel.channel().closeFuture().sync();
    }
}