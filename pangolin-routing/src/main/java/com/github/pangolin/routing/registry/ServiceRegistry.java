package com.github.pangolin.routing.registry;

import com.github.pangolin.routing.pattern.DestinationPattern;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class ServiceRegistry {
    private final Map<String, SocketProxy> proxyMap = new ConcurrentHashMap<>();
    private final List<SocketMapping> proxyMappings = new CopyOnWriteArrayList<>();

    public SocketProxy addProxy(final String name, final Object proxy) {
        SocketProxy socketProxy = new SocketProxy(name, proxy, this);
        proxyMap.put(socketProxy.getName(), socketProxy);
        return socketProxy;
    }

    public SocketProxy getProxy(final String name) {
        return proxyMap.get(name);
    }

    public Collection<SocketProxy> getProxies() {
        return Collections.unmodifiableCollection(proxyMap.values());
    }

    public List<SocketMapping> getProxyMappings() {
        return Collections.unmodifiableList(proxyMappings);
    }

    void addSocketMapping(final SocketMapping mapping) {
        if (null == mapping) {
            return;
        }
        proxyMappings.add(mapping);
    }

    public void newHandler(final InetSocketAddress destination) {
        final List<Object> proxies = new LinkedList<>();
        for (final SocketMapping mapping : proxyMappings) {
            for (DestinationPattern registeredPattern : mapping.getPatterns()) {
                if (registeredPattern.matches(destination)) {
                    proxies.add(mapping.getSocketProxy());
                }
            }
        }
        //
        System.out.println();
    }

}