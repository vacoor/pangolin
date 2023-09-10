package com.github.pangolin.routing.v2.internal;

import com.github.pangolin.routing.v2.Pattern;

public class Routing {
    private final Pattern[] patterns;
    private final ProxyProxy proxy;

    public Routing(final Pattern[] patterns, final ProxyProxy proxy) {
        this.patterns = patterns;
        this.proxy = proxy;
    }

    public Pattern[] getPatterns() {
        return patterns;
    }

    public ProxyProxy getProxy() {
        return proxy;
    }
}