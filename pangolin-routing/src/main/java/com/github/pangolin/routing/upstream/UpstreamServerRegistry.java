package com.github.pangolin.routing.upstream;

import java.util.Collection;

/**
 *
 */
public interface UpstreamServerRegistry {

    Collection<String> names();

//    void registerServer(final UpstreamServer upstream);

    UpstreamServer getServer(final String name);

    Collection<UpstreamServer> getServers();

}
