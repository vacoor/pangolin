package com.github.pangolin.routing.config.clash;

import com.github.pangolin.routing.proxy.group.lb.LoadBalanceProxyServer;
import com.github.pangolin.routing.proxy.ProxyServer;
import com.github.pangolin.routing.proxy.ProxyServerProvider;
import com.github.pangolin.routing.proxy.health.UrlTestHealthChecker;
import com.google.common.base.Preconditions;
import freework.net.Http;
import io.netty.channel.EventLoopGroup;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;

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
            final Map<String, ProxyServer> proxyRegistry = ClashRuleFactory.parseProxies(proxyDefinitions);
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
