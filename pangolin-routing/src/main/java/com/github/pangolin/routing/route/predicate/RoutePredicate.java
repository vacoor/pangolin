package com.github.pangolin.routing.route.predicate;

import java.net.InetSocketAddress;

/**
 *
 */
public interface RoutePredicate {

    boolean matches(final InetSocketAddress destination);

}
