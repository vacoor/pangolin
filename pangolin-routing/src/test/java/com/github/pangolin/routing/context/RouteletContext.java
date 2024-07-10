package com.github.pangolin.routing.context;

import com.github.pangolin.routing.proxy.ProxyServerProvider;
import com.github.pangolin.routing.proxy.ServerProvider;
import com.github.pangolin.routing.rule.RulesProvider;

public interface RouteletContext extends ServerProvider, RulesProvider, ProxyServerProvider {
}