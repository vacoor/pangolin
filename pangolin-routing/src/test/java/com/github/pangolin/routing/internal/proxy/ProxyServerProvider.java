package com.github.pangolin.routing.internal.proxy;

import java.util.Collection;

public interface ProxyServerProvider {

    Collection<ProxyServer2> getInstances();

    ProxyServer2 getInstance(final String name);

}
