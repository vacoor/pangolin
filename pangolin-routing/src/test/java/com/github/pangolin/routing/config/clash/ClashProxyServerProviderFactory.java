package com.github.pangolin.routing.config.clash;

import com.github.pangolin.routing.internal.node.LoadBalanceProxyServer;
import com.github.pangolin.routing.internal.node.ProxyServer;
import com.github.pangolin.routing.internal.node.health.UrlTestHealthChecker;
import com.github.pangolin.routing.internal.proxy.ProxyServerProvider;
import com.google.common.base.Preconditions;
import freework.net.Http;
import io.netty.channel.EventLoopGroup;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 *
 */
public class ClashProxyServerProviderFactory {
    private final URL url;

    public ClashProxyServerProviderFactory(final URL url) {
        this.url = url;
    }

    public ProxyServerProvider getProxyServerProvider(final EventLoopGroup group) {
        try {
            final Configuration conf = fetch(url);
            final List<Configuration.ProxyDefinition> proxyDefinitions = null != conf.getProxies() ? conf.getProxies() : Collections.emptyList();
            final Map<String, ProxyServer> proxyRegistry = ClashRuleFactory.parseProxies(proxyDefinitions);
            final List<Configuration.ProxyGroupDefinition> proxyGroups = conf.getProxyGroups();
            final List<Configuration.ProxyGroupDefinition> proxyGroupsToUse = null != proxyGroups ? proxyGroups : Collections.emptyList();

            for (Configuration.ProxyGroupDefinition proxyGroup : proxyGroupsToUse) {
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
            for (final Configuration.ProxyGroupDefinition proxyGroup : proxyGroupsToUse) {
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

    private static Configuration fetch(final URL url) throws IOException {
        HttpURLConnection httpUrlConnection = null;
        try {
            httpUrlConnection = (HttpURLConnection) url.openConnection();
            httpUrlConnection.setRequestProperty("Accept", "application/json, text/plain, */*");
            httpUrlConnection.setRequestProperty("pragma", "No-Cache");
            httpUrlConnection.setRequestProperty("User-Agent", "ClashforWindows/0.19.25");
            final int responseCode = httpUrlConnection.getResponseCode();
            Preconditions.checkState(HttpURLConnection.HTTP_OK == responseCode, "responseCode = %s", responseCode);
            return Configuration.load(httpUrlConnection.getInputStream());
        } finally {
            Http.close(httpUrlConnection);
        }
    }

    public static ClashProxyServerProviderFactory create(final String url) throws MalformedURLException {
        return new ClashProxyServerProviderFactory(new URL(url));
    }

}
