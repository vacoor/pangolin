package com.github.pangolin.routing.v2.internal;

import com.github.pangolin.routing.v2.Pattern;

import java.net.SocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class Registry {
    private final Map<String, ProxyProxy> proxyMap = new ConcurrentHashMap<>();
    private final List<Routing> routings = new CopyOnWriteArrayList<>();

    ProxyProxy newProxy() {
        return new ProxyProxy(this);
    }

    void addProxy(final ProxyProxy proxy) {
        if (null == proxy) {
            return;
        }
        proxyMap.put(proxy.getName(), proxy);
    }

    ProxyProxy getProxy(final String name) {
        return proxyMap.get(name);
    }

    boolean removeProxy(String name) {
        final ProxyProxy removed = proxyMap.remove(name);
        if (null != removed) {
            for (final Iterator<Routing> it = routings.iterator(); it.hasNext();) {
                final Routing routing = it.next();
                if (removed.equals(routing.getProxy())) {
                    routings.remove(routing);
                }
            }
            // destroy(removed);
        }
        return null != removed;
    }


    Set<Pattern> addRouting(Routing routing) {
        if (null == routing) {
            return Collections.emptySet();
        }
        routings.add(routing);
        return null;
    }

    protected void executeRouting(final SocketAddress sa) {
        Routing routing = getRouting(sa);
    }

    protected Routing getRouting(final SocketAddress sa) {
        if (routings.isEmpty()) {
            return null;
        }

        final List<Pattern> matchingPatterns = new ArrayList<>();
        final Map<Pattern, Routing> routingMap = new HashMap<>();
        for (final Routing routing : routings) {
            for (final Pattern pattern : routing.getPatterns()) {
                if (matches(sa, pattern)) {
                    matchingPatterns.add(pattern);
                    routingMap.put(pattern, routing);
                }
            }
        }

        Pattern bestPattern = null;
        Routing bestPatternMatch = null;
        if (!matchingPatterns.isEmpty()) {
            Collections.sort(matchingPatterns, patternComparator(sa));
            bestPattern = matchingPatterns.get(0);
            bestPatternMatch = routingMap.get(bestPattern);
        }
        return bestPatternMatch;
    }

    private boolean matches(final SocketAddress sa, final Pattern pattern) {
        return false;
    }

    protected Comparator<Pattern> patternComparator(final SocketAddress sa) {
        return null;
    }
}