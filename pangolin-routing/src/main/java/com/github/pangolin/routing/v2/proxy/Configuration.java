package com.github.pangolin.routing.v2.proxy;

import com.github.pangolin.routing.config.clash.ClashConfiguration;
import com.github.pangolin.routing.config.clash.ClashProxyServerProviderFactory;
import com.github.pangolin.routing.proxy.ProxyServer;
import com.github.pangolin.routing.proxy.spi.ServerResolver;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import freework.net.Http;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
public class Configuration {
    private final URL url;

    private volatile Map<String, ProxyServer> nameToProxyMap;
    private volatile Map<String, ProxyServer> nameToProxyGroupMap;

    public Configuration(final URL url) {
        this.url = url;
    }

    public void refresh() throws IOException {
        refresh(loadClashConfiguration(url));
    }

    Map<String, ProxyServer> refresh(final ClashConfiguration conf) {
        final List<ClashConfiguration.ProxyDefinition> proxyDefinitions = nvl(conf.getProxies(), Collections.emptyList());
        final List<ClashConfiguration.ProxyGroupDefinition> proxyGroupDefinitions = nvl(conf.getProxyGroups(), Collections.emptyList());
        final List<String> rules = nvl(conf.getRules(), Collections.emptyList());

        final Map<String, ProxyServer> nameToProxyMap = parseProxies(proxyDefinitions);
        final Map<String, ProxyServer> nameToProxyGroupMap = parseProxyGroups(proxyGroupDefinitions, nameToProxyMap);

        this.nameToProxyMap = nameToProxyMap;
        this.nameToProxyGroupMap = nameToProxyGroupMap;
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
        final ProxyServer group = new ServerGroup(name, referencesToUse);
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
        return resolve(uri);
    }

    private static String urlEncode(final String text) {
        return Http.urlEncode(text, StandardCharsets.UTF_8.name());
    }

    private static final ServiceLoader<ServerResolver> RESOLVERS = ServiceLoader.load(ServerResolver.class);

    private static ProxyServer resolve(final String url) {
        for (final ServerResolver resolver : RESOLVERS) {
            if (!resolver.acceptsUrl(url)) {
                continue;
            }
            final ProxyServer resolved = resolver.resolve(url, new Properties());
            if (null != resolved) {
                return resolved;
            }
        }
        throw new IllegalStateException();
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

    public static void main(String[] args) {
        InputStream in = ClashProxyServerProviderFactory.class.getResourceAsStream("/FlyingBird_1720166597.yaml");
        Configuration config = new Configuration(null);
        config.refresh(ClashConfiguration.load(in));

        Collection<ProxyServer> values = config.nameToProxyMap.values();

    }
}