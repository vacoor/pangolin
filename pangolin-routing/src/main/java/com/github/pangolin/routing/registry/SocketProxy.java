package com.github.pangolin.routing.registry;

import com.github.pangolin.routing.pattern.DestinationPattern;

public class SocketProxy {
    private final String name;
    private final Object proxy;
    private final ServiceRegistry context;

    public SocketProxy(final String name, final Object proxy, final ServiceRegistry context) {
        this.name = name;
        this.proxy = proxy;
        this.context = context;
    }

    public String getName() {
        return name;
    }

    public Object getProxy() {
        return proxy;
    }

    public void addMapping(final DestinationPattern... patterns) {
        final SocketMapping m = new SocketMapping(this);
        m.setPatterns(patterns);
        context.addSocketMapping(m);
    }

}