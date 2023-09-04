package com.github.pangolin.proxy.routing;

import java.net.SocketAddress;
import java.net.URI;

public interface RoutingRule {

    boolean matches(final SocketAddress address);

    URI nextHop(final SocketAddress address);

}