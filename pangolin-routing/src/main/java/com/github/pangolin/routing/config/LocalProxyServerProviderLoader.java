package com.github.pangolin.routing.config;

import com.github.pangolin.routing.proxy.ProxyServer;
import com.github.pangolin.routing.proxy.ProxyServerProvider;
import com.github.pangolin.routing.config.spi.ServerResolver;
import io.netty.util.internal.ObjectUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;

/**
 */
public class LocalProxyServerProviderLoader {

    public static ProxyServerProvider load(final URL url) throws IOException {
        final Map<String, ProxyServer> proxies = resolve(new InputStreamReader(url.openStream(), StandardCharsets.UTF_8));
        return new ProxyServerProvider() {
            @Override
            public Collection<ProxyServer> getInstances() {
                return Collections.unmodifiableCollection(proxies.values());
            }

            @Override
            public ProxyServer getInstance(final String name) {
                return proxies.get(name);
            }
        };
    }

    public static Map<String, ProxyServer> resolve(final Reader reader) throws IOException {
        ObjectUtil.checkNotNull(reader, "reader");
        final Map<String, ProxyServer> servers = new LinkedHashMap<>();
        final BufferedReader r = reader instanceof BufferedReader ? (BufferedReader) reader : new BufferedReader(reader);
        String line;
        while (null != (line = r.readLine())) {
//            final int index = line.indexOf('#');
//            final String lineToUse = -1 < index ? line.substring(0, index).trim() : line.trim();
            final String lineToUse = line.startsWith("#") ? "" : line;
            if (lineToUse.isEmpty()) {
                continue;
            }
            final ProxyServer proxyServer = resolve(lineToUse);
            servers.put(proxyServer.getName(), proxyServer);
        }
        return servers;
    }

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
}
