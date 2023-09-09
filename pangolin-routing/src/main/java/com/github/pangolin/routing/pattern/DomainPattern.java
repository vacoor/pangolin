package com.github.pangolin.routing.pattern;

import java.net.InetSocketAddress;

/**
 *
 */
public class DomainPattern implements Pattern<InetSocketAddress> {
    private static final AntPathMatcher MATCHER = new AntPathMatcher(".");

    private final String pattern;

    public DomainPattern(final String pattern) {
        this.pattern = pattern;
    }

    @Override
    public boolean matches(final InetSocketAddress sa) {
        if (sa.isUnresolved()) {
            return MATCHER.matches(pattern, sa.getHostString());
        } else {
            final String hostname = sa.getAddress().getHostName();
            return MATCHER.matches(pattern, hostname);
        }
    }

}
