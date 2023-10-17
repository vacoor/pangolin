package com.github.pangolin.routing.internal.node;

import java.util.Collection;

public interface ProxyProvider {

    Collection<ProxyServer> getInstances();

    ProxyServer getInstance(final String name);

}
