package com.github.pangolin.routing.config;

import com.github.pangolin.routing.pattern.DestinationPattern;

public interface PatternResolver {

    boolean isSupported(final String pattern);

    DestinationPattern resolve(final String pattern);

}