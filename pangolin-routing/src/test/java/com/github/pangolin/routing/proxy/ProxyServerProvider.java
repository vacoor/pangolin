package com.github.pangolin.routing.proxy;

import com.github.pangolin.routing.ProxyServer;

import java.util.Collection;

public interface ProxyServerProvider {

    Collection<ProxyServer> getInstances();

    ProxyServer getInstance(final String name);

}
