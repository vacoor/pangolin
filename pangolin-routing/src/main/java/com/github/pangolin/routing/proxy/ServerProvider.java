package com.github.pangolin.routing.proxy;

import java.util.Collection;
import java.util.List;

/**
 *
 */
public interface ServerProvider {

    Collection<String> names();

    ProxyServer getServer(final String name);

    List<ProxyServer> getServers();

}
