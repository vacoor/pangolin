package com.github.pangolin.routing.config.resolver;

import com.github.pangolin.routing.upstream.UpstreamServer;
import com.github.pangolin.routing.upstream.UpstreamServerFactory;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.ServiceLoader;

public class ProxyResolver extends AbstractPrefixRuleResolver<UpstreamServer> {

    public ProxyResolver() {
        super("PROXY,");
    }

    @Override
    protected List<UpstreamServer> doResolve(final String rule, final URL url) throws IOException {
        final String[] segment = rule.split("\\s*=\\s*", 2);
        final String name = segment.length > 1 ? segment[0] : null;
        final String proxyUrl = segment.length > 1 ? segment[1] : segment[0];
        return Collections.singletonList(
                doResolve(name, proxyUrl)
        );
    }

    private static UpstreamServer doResolve(final String name, final String url) {
        final ServiceLoader<UpstreamServerFactory> resolvers = ServiceLoader.load(UpstreamServerFactory.class);
        for (final UpstreamServerFactory resolver : resolvers) {
            if (!resolver.accept(url)) {
                continue;
            }
            final Properties props = new Properties();
            if (null != name) {
                props.setProperty("name", name);
            }
            final UpstreamServer resolved = resolver.create(url, props);
            if (null != resolved) {
                return resolved;
            }
        }
        throw new IllegalStateException("NOT found provider, url: " + url);
    }
}