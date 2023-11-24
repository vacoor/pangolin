package com.github.pangolin.routing.config;

import com.github.pangolin.routing.pattern.DestinationPattern;

import java.io.IOException;
import java.net.URL;
import java.util.List;

public interface PatternResolver {

    boolean isSupported(final String pattern);

    List<DestinationPattern> resolve(final String pattern, final URL url) throws IOException;

}