package com.github.pangolin.routing.config;

import com.github.pangolin.routing.route.predicate.RoutePredicate;
import com.github.pangolin.routing.upstream.UpstreamServer;

import java.io.IOException;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class CachingUpstreamServerRegistry implements RouteContext {
    private final RouteContext parent;
    private final ServerReader reader;
    private final URL conf;

    private volatile SimpleRouteRegistry snapshot;

    public CachingUpstreamServerRegistry(final ServerReader reader, final URL url) {
        this(reader, url, null);
    }

    public CachingUpstreamServerRegistry(final ServerReader reader, final URL url, final RouteContext parent) {
        this.reader = reader;
        this.conf = url;
        this.parent = parent;
    }

    public CachingUpstreamServerRegistry refresh() throws ConfigurationException, IOException {
        snapshot = reader.load(conf, parent);
        return this;
    }

    @Override
    public Collection<String> names() {
        return null != snapshot ? snapshot.getServerNames() : Collections.emptyList();
    }

    @Override
    public UpstreamServer getServer(final String name) {
        return null != snapshot ? snapshot.getServer(name) : null;
    }

    @Override
    public List<UpstreamServer> getServers() {
        return null != snapshot ? snapshot.getServers() : null;
    }

    @Override
    public Map<RoutePredicate, String> getRoutes() {
        return null != snapshot? snapshot.getRoutes() : Collections.emptyMap();
    }

}