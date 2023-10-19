package com.github.pangolin.routing.internal.proxy;

import com.github.pangolin.routing.internal.node.ProxyServer;

import java.util.Collection;

public interface ProxyServerProvider {

    Collection<ProxyServer> getInstances();

    ProxyServer getInstance(final String name);

}
