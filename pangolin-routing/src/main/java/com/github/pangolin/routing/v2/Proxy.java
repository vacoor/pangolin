package com.github.pangolin.routing.v2;

import java.util.Set;

public interface Proxy {

    interface Configurer {

        Set<Pattern> addRouting(final Pattern... patterns);

    }
}
