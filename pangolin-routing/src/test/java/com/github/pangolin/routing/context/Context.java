package com.github.pangolin.routing.context;

import com.github.pangolin.routing.proxy.ProxyServer;
import com.github.pangolin.routing.rule.pattern.DestinationPattern;
import com.google.common.collect.Maps;

import java.net.InetSocketAddress;
import java.util.Map;

/**
 */
class Context {
    private Context parent;
    private final Map<String, ProxyServer> proxies = Maps.newLinkedHashMap();
    private final Map<DestinationPattern, String> rules = Maps.newLinkedHashMap();

    public Context() {
        this(null);
    }

    public Context(final Context parent) {
        this.parent = parent;
    }

    public Context addProxy(final ProxyServer proxy) {
        proxies.put(proxy.getName(), proxy);
        return this;
    }

    public ProxyServer getProxy(final String proxy) {
        ProxyServer proxyServer = proxies.get(proxy);
        if (null != proxyServer) {
            return proxyServer;
        }
        return null != parent ? parent.getProxy(proxy) : null;
    }

    public Context addRule(final DestinationPattern pattern, final String proxy) {
        rules.put(pattern, proxy);
        return this;
    }

    public ProxyServer choose(final InetSocketAddress sa) {
        for (final Map.Entry<DestinationPattern, String> rule : rules.entrySet()) {
            if (rule.getKey().matches(sa)) {
                return getProxy(rule.getValue());
            }
        }
        return null != parent ? parent.choose(sa) : null;
    }
}
