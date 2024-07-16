package com.github.pangolin.routing.config;

import com.github.pangolin.routing.upstream.UpstreamServer;
import com.github.pangolin.routing.rule.pattern.DestinationPattern;

import java.io.IOException;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class RefreshableUpstreamServerRegistry implements RouteletContext {
    private final RouteletContext parent;
    private final ServerReader reader;
    private final URL conf;

    private volatile UpstreamServerRegistry snapshot;

    public RefreshableUpstreamServerRegistry(final ServerReader reader, final URL url) {
        this(reader, url, null);
    }

    public RefreshableUpstreamServerRegistry(final ServerReader reader, final URL url, final RouteletContext parent) {
        this.reader = reader;
        this.conf = url;
        this.parent = parent;
    }

    public RefreshableUpstreamServerRegistry refresh() throws ConfigurationException, IOException {
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
    public Map<DestinationPattern, String> getRules() {
        return null != snapshot? snapshot.getRules() : Collections.emptyMap();
    }

}