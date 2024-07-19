package com.github.pangolin.routing.v2.support;

import com.github.pangolin.routing.config.ConfigurationException;
import com.github.pangolin.routing.config.Ini;
import com.github.pangolin.routing.route.predicate.RoutePredicate;
import com.github.pangolin.routing.v2.context.RouteContext;
import com.github.pangolin.routing.v2.context.SimpleRouteContext;
import com.github.pangolin.routing.v2.route.predicate.RoutePredicateFactory;
import com.github.pangolin.routing.v2.upstream.UpstreamServerCombiner;
import com.github.pangolin.routing.v2.upstream.UpstreamServerFactory;
import com.google.common.collect.Maps;
import com.netflix.loadbalancer.LoadBalancerStats;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Slf4j
public class DefaultServerReader extends ReaderSupport {

    public DefaultServerReader(final LoadBalancerStats stats,
                               final Iterable<UpstreamServerFactory> factories,
                               final Iterable<UpstreamServerCombiner> combiners,
                               final Iterable<RoutePredicateFactory<InetSocketAddress, String>> predicates) {
        super(stats, factories, combiners, predicates);
    }

    public RouteContext load(final URL url, final RouteContext parent) throws IOException, ConfigurationException {
        final Ini ini = new Ini();
        ini.load(url.openStream());

        final Ini.Section external = ini.getSection("External");
        RouteContext parentToUse = parent;
        if (null != external) {
            for (String urlToUse : external.values()) {
                parentToUse = load(urlToUse, parentToUse);
            }
        }

        final SimpleRouteContext registry = new SimpleRouteContext(parentToUse);
        final Ini.Section proxy = ini.getSection("Proxy");
        if (null != proxy) {
            proxy.forEach((k, v) -> registry.addUpstream(k, apply(k, v)));
        }

        final Ini.Section proxyGroups = ini.getSection("Proxy Group");
        if (null != proxyGroups) {
            for (final Map.Entry<String, String> entry : proxyGroups.entrySet()) {
                final String name = entry.getKey();
                final String value = entry.getValue();
                final String[] segments = value.split("\\s*,\\s*");
                final String type = segments[0];
                final List<String> proxies = Arrays.asList(Arrays.copyOfRange(segments, 1, segments.length));

                registry.addUpstream(name, apply(name, type, proxies, registry));
            }
        }

        Map<RoutePredicate, String> rules = Maps.newLinkedHashMap();
        Ini.Section rule = ini.getSection("Rule");
        if (null != rule) {
            rule.keySet().stream().map(route -> apply(route, url)).forEach(registry::addRoute);
        }
        return registry;
    }


    private RouteContext load(final String url, final RouteContext parent) throws IOException, ConfigurationException {
        return new ExternalServerReader(stats, factories, combiners.values(), predicates.values()).load(new URL(url), parent);
//        return new CachingUpstreamServerRegistry(new ExternalServerReader(stats), new URL(url), parent).refresh();
    }

}