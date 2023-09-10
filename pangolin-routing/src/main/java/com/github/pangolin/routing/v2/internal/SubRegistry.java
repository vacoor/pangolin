package com.github.pangolin.routing.v2.internal;

import com.github.pangolin.routing.v2.Proxy;

public class SubRegistry {
    private Registry registry;

    Proxy.Configurer addProxy(final String name, Proxy proxy) {
        ProxyProxy proxyProxy = registry.newProxy();
        return proxyProxy.getConfigurer();
    }

}