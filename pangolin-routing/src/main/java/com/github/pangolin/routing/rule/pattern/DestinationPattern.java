package com.github.pangolin.routing.rule.pattern;

import java.net.InetSocketAddress;

/**
 *
 */
public interface DestinationPattern {

    boolean matches(final InetSocketAddress destination);

}
