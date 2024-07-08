package com.github.pangolin.routing.config.clash;

import static com.github.pangolin.routing.config.clash.ClashConfiguration.ProxyDefinition;
import static com.github.pangolin.routing.config.clash.ClashConfiguration.ProxyGroupDefinition;

import com.github.pangolin.routing.proxy.LoadBalanceProxyServer;
import com.github.pangolin.routing.proxy.ProxyServer;
import com.github.pangolin.routing.proxy.ProxyServerProvider;
import com.github.pangolin.routing.proxy.health.UrlTestHealthChecker;
import com.github.pangolin.routing.proxy.spi.ServerResolver;
import com.google.common.base.Preconditions;
import freework.net.Http;
import io.netty.channel.EventLoopGroup;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 *
 */
@Slf4j
public class ClashProxyServerProviderFactory {
    private final URL subscribeUrl;

    public ClashProxyServerProviderFactory(final URL subscribeUrl) {
        this.subscribeUrl = subscribeUrl;
    }

    public ProxyServerProvider getProxyServerProvider(final EventLoopGroup group) {
        try {
            final ClashConfiguration conf = loadClashConfiguration(subscribeUrl);
            final List<ClashConfiguration.ProxyDefinition> proxyDefinitions = null != conf.getProxies() ? conf.getProxies() : Collections.emptyList();

            log.debug("Parse proxies starting");
            final Map<String, ProxyServer> proxyRegistry = ClashProxiesParser.parseProxies(proxyDefinitions);
            log.debug("Parse proxies completed");

            final List<ClashConfiguration.ProxyGroupDefinition> proxyGroups = conf.getProxyGroups();
            final List<ClashConfiguration.ProxyGroupDefinition> proxyGroupsToUse = null != proxyGroups ? proxyGroups : Collections.emptyList();

            log.debug("Parse proxyGroup starting");
            for (ClashConfiguration.ProxyGroupDefinition proxyGroup : proxyGroupsToUse) {
                if ("url-test".equals(proxyGroup.getType())) {
                    final List<String> proxies = proxyGroup.getProxies();
                    final List<ProxyServer> servers = new ArrayList<>(proxies.size());
                    for (String proxy : proxies) {
                        final ProxyServer server = proxyRegistry.get(proxy);
                        if (null != server) {
                            servers.add(server);
                        }
                    }
                    final String url = proxyGroup.getUrl();
                    final UrlTestHealthChecker urlTestHealthChecker = new UrlTestHealthChecker(url, 3000, group);
                    final ProxyServer urlTestProxyGroup = new LoadBalanceProxyServer(proxyGroup.getName(), urlTestHealthChecker, servers, group);
                    proxyRegistry.put(urlTestProxyGroup.getName(), urlTestProxyGroup);
                }
            }
            log.debug("Parse proxyGroup completed");

            for (final ClashConfiguration.ProxyGroupDefinition proxyGroup : proxyGroupsToUse) {
                if ("select".equals(proxyGroup.getType())) {
                    final List<String> proxies = proxyGroup.getProxies();
                    if (null != proxies && !proxies.isEmpty()) {
                        final ProxyServer select = proxyRegistry.get(proxies.iterator().next());
                        if (null != select) {
                            proxyRegistry.put(proxyGroup.getName(), select);
                        }
                    }
                }
            }

            return new ProxyServerProvider() {
                @Override
                public Collection<ProxyServer> getInstances() {
                    return Collections.unmodifiableCollection(proxyRegistry.values());
                }

                @Override
                public ProxyServer getInstance(final String name) {
                    return proxyRegistry.get(name);
                }
            };
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public void refresh() throws IOException {
        final ClashConfiguration conf = loadClashConfiguration(subscribeUrl);
        final List<ProxyDefinition> proxyDefinitions = nvl(conf.getProxies(), Collections.emptyList());
        final List<ProxyGroupDefinition> proxyGroupDefinitions = nvl(conf.getProxyGroups(), Collections.emptyList());
        final List<String> rules = nvl(conf.getRules(), Collections.emptyList());

        final Map<String, ProxyServer> nameToProxies = parseProxies(proxyDefinitions);
    }

    /*
    private Map<String, ProxyServer> parseProxyGroups(final List<ProxyGroupDefinition> definitions,
                                                      final Map<String, ProxyServer> nameToProxyMap) {
        final Map<String, ProxyServer> nameToGroupMap = Maps.newHashMap();
        final Map<String, ProxyGroupDefinition> definitionMap = definitions.stream()
                .collect(Collectors.toMap(ProxyGroupDefinition::getName, Function.identity(), (prev, next) -> next));
        for (final Map.Entry<String, ProxyGroupDefinition> entry : definitionMap.entrySet()) {
            if (!nameToGroupMap.containsKey(entry.getKey())) {
                parseProxyGroup(entry.getValue(), nameToProxyMap, nameToGroupMap, definitionMap);
            }
        }
        return nameToGroupMap;
    }

    private ProxyServer parseProxyGroup(final ProxyGroupDefinition definition,
                                 final Map<String, ProxyServer> nameToProxyMap,
                                 final Map<String, ProxyServer> nameToGroupMap,
                                 final Map<String, ProxyGroupDefinition> definitionMap) {
        final List<String> referenceNames = nvl(definition.getProxies(), Collections.emptyList());
        final List<ProxyServer> referencesToUse = Lists.newArrayList();
        for (final String referenceName : referenceNames) {
            ProxyServer reference = nameToProxyMap.get(referenceName);
            if (null == reference) {
                final ProxyGroupDefinition definitionRef = definitionMap.remove(referenceName);
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

        // nameToGroupMap.put(name, )
    }
    */

    private Map<String, ProxyServer> parseProxies(final List<ProxyDefinition> definitions) {
        return definitions.stream()
                .filter(definition -> !"0.0.0.0".equals(definition.getServer()))
                .map(this::parseProxy)
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(ProxyServer::getName, Function.identity(), (prev, next) -> next));
    }

    private ProxyServer parseProxy(final ProxyDefinition definition) {
        final String cipher = definition.getCipher();
        final String password = definition.getPassword();
        final String userInfo = StringUtils.hasText(cipher) ? String.format("%s:%s", cipher, password) : password;

        final String uri = String.format("%s://%s@%s:%s#%s", definition.getType(), urlEncode(userInfo), definition.getServer(), definition.getPort(), urlEncode(definition.getName()));
        log.debug("resolve uri: {}", uri);
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

    public static ClashProxyServerProviderFactory create(final String url) throws MalformedURLException {
        return new ClashProxyServerProviderFactory(new URL(url));
    }

}
