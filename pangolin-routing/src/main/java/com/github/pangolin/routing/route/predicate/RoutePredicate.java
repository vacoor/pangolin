package com.github.pangolin.routing.route.predicate;

import java.net.InetSocketAddress;

/**
 *
 */
public interface RoutePredicate {

    boolean test(final InetSocketAddress destination);

}
