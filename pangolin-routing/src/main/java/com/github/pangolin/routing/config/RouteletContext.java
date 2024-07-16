package com.github.pangolin.routing.config;

import com.github.pangolin.routing.upstream.UpstreamServerProvider;
import com.github.pangolin.routing.rule.RulesProvider;

public interface RouteletContext extends UpstreamServerProvider, RulesProvider {
}