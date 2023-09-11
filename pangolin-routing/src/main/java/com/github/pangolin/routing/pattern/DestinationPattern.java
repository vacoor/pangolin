package com.github.pangolin.routing.pattern;

import java.net.InetSocketAddress;

/**
 *
 */
public interface DestinationPattern {

    boolean matches(final InetSocketAddress destionation);

}
