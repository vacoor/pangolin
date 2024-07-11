package com.github.pangolin.routing.config;

import com.github.pangolin.routing.proxy.ServerProvider;
import com.github.pangolin.routing.rule.RulesProvider;

public interface RouteletContext extends ServerProvider, RulesProvider {
}