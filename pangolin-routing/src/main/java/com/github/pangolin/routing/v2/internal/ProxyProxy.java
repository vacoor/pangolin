package com.github.pangolin.routing.v2.internal;

import com.github.pangolin.routing.v2.Pattern;
import com.github.pangolin.routing.v2.Proxy;

import java.util.Set;

class ProxyProxy {
    private final Registry registry;
    private String name;
    private Proxy proxy;
    private Configurer configurer;

    ProxyProxy(final Registry registry) {
        this.registry = registry;
    }

    public String getName() {
        return name;
    }

    public Proxy getProxy() {
        return proxy;
    }

    public Configurer getConfigurer() {
        if (null == configurer) {
            configurer = new Configurer();
        }
        return configurer;
    }

    protected class Configurer implements Proxy.Configurer {
        @Override
        public Set<Pattern> addRouting(final Pattern... patterns) {
            return registry.addRouting(new Routing(patterns, ProxyProxy.this));
        }
    }

}