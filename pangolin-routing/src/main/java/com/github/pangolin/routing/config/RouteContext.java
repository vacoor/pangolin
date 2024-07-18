package com.github.pangolin.routing.config;

import com.github.pangolin.routing.upstream.UpstreamServerRegistry;
import com.github.pangolin.routing.route.RouteRegistry;

public interface RouteContext extends UpstreamServerRegistry, RouteRegistry {
}