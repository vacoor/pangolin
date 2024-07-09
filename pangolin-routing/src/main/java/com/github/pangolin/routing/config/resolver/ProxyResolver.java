package com.github.pangolin.routing.config.resolver;

import com.github.pangolin.routing.proxy.ProxyServer;
import com.github.pangolin.routing.proxy.spi.ServerResolver;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.ServiceLoader;

public class ProxyResolver extends AbstractPrefixRuleResolver<ProxyServer> {

    public ProxyResolver() {
        super("PROXY,");
    }

    @Override
    protected List<ProxyServer> doResolve(final String rule, final URL url) throws IOException {
        final String[] segment = rule.split("\\s*=\\s*", 2);
        final String name = segment.length > 1 ? segment[0] : null;
        final String proxyUrl = segment.length > 1 ? segment[1] : segment[0];
        return Collections.singletonList(
                doResolve(name, proxyUrl)
        );
    }

    private static ProxyServer doResolve(final String name, final String url) {
        final ServiceLoader<ServerResolver> resolvers = ServiceLoader.load(ServerResolver.class);
        for (final ServerResolver resolver : resolvers) {
            if (!resolver.acceptsUrl(url)) {
                continue;
            }
            final Properties props = new Properties();
            if (null != name) {
                props.setProperty("name", name);
            }
            final ProxyServer resolved = resolver.resolve(url, props);
            if (null != resolved) {
                return resolved;
            }
        }
        throw new IllegalStateException("NOT found provider, url: " + url);
    }
}