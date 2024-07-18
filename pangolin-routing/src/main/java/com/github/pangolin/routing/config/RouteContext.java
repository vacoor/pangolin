package com.github.pangolin.routing.config;

import com.github.pangolin.routing.upstream.UpstreamServerProvider;
import com.github.pangolin.routing.route.RouteProvider;

public interface RouteContext extends UpstreamServerProvider, RouteProvider {
}