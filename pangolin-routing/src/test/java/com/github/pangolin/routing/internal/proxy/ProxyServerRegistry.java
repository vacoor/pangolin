package com.github.pangolin.routing.internal.proxy;

import com.github.pangolin.routing.internal.proxy.health.HealthCheckScheduler;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class ProxyServerRegistry implements ProxyServerProvider {
    private final HealthCheckScheduler healthCheckScheduler;
    private final ConcurrentMap<String, ProxyServer2> registeredInstanceMap = new ConcurrentHashMap<>();

    public ProxyServerRegistry(final HealthCheckScheduler healthCheckScheduler) {
        this.healthCheckScheduler = healthCheckScheduler;
    }

    public void register(final ProxyServer2 instance) {
        if (null != registeredInstanceMap.putIfAbsent(instance.getName(), instance)) {
            throw new IllegalStateException(String.format("Instance '%s' already registered", instance.getName()));
        }
        healthCheckScheduler.add(instance);
    }

    public void unregister(final ProxyServer2 instance) {
        if (registeredInstanceMap.remove(instance.getName(), instance)) {
            healthCheckScheduler.remove(instance);
        }
    }

    @Override
    public ProxyServer2 getInstance(final String name) {
        return registeredInstanceMap.get(name);
    }

    public Collection<ProxyServer2> getInstances() {
        return Collections.unmodifiableCollection(registeredInstanceMap.values());
    }

}
