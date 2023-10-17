package com.github.pangolin.routing.internal.node;

import com.github.pangolin.routing.internal.node.ProxyProvider;
import com.github.pangolin.routing.internal.node.ProxyServer;
import freework.reflect.proxy.ProxyFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class ProxyServerRegistry implements ProxyProvider {
    private final ConcurrentMap<String, ProxyServer> registeredInstanceMap = new ConcurrentHashMap<>();

    public void register(final ProxyServer instance) {
        if (null != registeredInstanceMap.putIfAbsent(instance.getName(), instance)) {
            throw new IllegalStateException(String.format("Instance '%s' already registered", instance.getName()));
        }
    }

    public void unregister(final ProxyServer instance) {
        registeredInstanceMap.remove(instance.getName(), instance);
    }

    @Override
    public ProxyServer getInstance(String name) {
        return registeredInstanceMap.get(name);
    }

    public Collection<ProxyServer> getInstances() {
        return Collections.unmodifiableCollection(registeredInstanceMap.values());
    }

}
