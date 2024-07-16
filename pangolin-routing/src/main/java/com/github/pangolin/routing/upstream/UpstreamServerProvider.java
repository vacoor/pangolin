package com.github.pangolin.routing.upstream;

import java.util.Collection;

/**
 *
 */
public interface UpstreamServerProvider {

    Collection<String> names();

    UpstreamServer getServer(final String name);

    Collection<UpstreamServer> getServers();

}
