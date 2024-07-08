package com.github.pangolin.routing.proxy;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * @deprecated
 */
@Deprecated
public class ComposedProxyServerProvider implements ProxyServerProvider {
    private final ProxyServerProvider[] providers;

    public ComposedProxyServerProvider(final ProxyServerProvider... providers) {
        this.providers = providers;
    }

    @Override
    public Collection<ProxyServer> getInstances() {
        final Set<ProxyServer> instances = new HashSet<>();
        for (final ProxyServerProvider provider : providers) {
            instances.addAll(provider.getInstances());
        }
        return instances;
    }

    @Override
    public ProxyServer getInstance(final String name) {
        for (final ProxyServerProvider provider : providers) {
            final ProxyServer instance = provider.getInstance(name);
            if (null != instance) {
                return instance;
            }
        }
        return null;
    }
}
