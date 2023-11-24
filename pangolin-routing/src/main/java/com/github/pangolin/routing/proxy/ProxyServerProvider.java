package com.github.pangolin.routing.proxy;

import java.util.Collection;

public interface ProxyServerProvider {

    Collection<ProxyServer> getInstances();

    ProxyServer getInstance(final String name);

}
