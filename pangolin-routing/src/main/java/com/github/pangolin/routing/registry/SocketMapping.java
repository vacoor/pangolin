package com.github.pangolin.routing.registry;

import com.github.pangolin.routing.pattern.DestinationPattern;

public class SocketMapping {
    private final Object socketProxy;
    private DestinationPattern[] patterns;

    public SocketMapping(final Object socketProxy) {
        this.socketProxy = socketProxy;
    }

    public Object getSocketProxy() {
        return socketProxy;
    }

    public DestinationPattern[] getPatterns() {
        return patterns;
    }

    public void setPatterns(final DestinationPattern[] patterns) {
        this.patterns = patterns;
    }
}