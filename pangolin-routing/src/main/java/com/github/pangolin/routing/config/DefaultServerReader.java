package com.github.pangolin.routing.config;

import com.github.pangolin.routing.rule.pattern.DestinationPattern;
import com.google.common.collect.Maps;
import com.netflix.loadbalancer.LoadBalancerStats;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Slf4j
public class DefaultServerReader implements ServerReader {
    private final LoadBalancerStats stats;

    public DefaultServerReader(final LoadBalancerStats stats) {
        this.stats = stats;
    }

    public ServerRegistry load(final URL url, final RouteletContext parent) throws IOException, ConfigurationException {
        final Ini ini = new Ini();
        ini.load(url.openStream());

        final Ini.Section external = ini.getSection("External");
        RouteletContext parentToUse = parent;
        if (null != external) {
            for (String urlToUse : external.values()) {
                parentToUse = load(urlToUse, parentToUse);
            }
        }

        final ServerRegistry registry = new ServerRegistry(parentToUse, stats);
        final Ini.Section proxy = ini.getSection("Proxy");
        if (null != proxy) {
            proxy.forEach(registry::register);
        }

        final Ini.Section proxyGroups = ini.getSection("Proxy Group");
        if (null != proxyGroups) {
            for (final Map.Entry<String, String> entry : proxyGroups.entrySet()) {
                final String name = entry.getKey();
                final String value = entry.getValue();
                final String[] segments = value.split("\\s*,\\s*");
                final String type = segments[0];
                final List<String> proxies = Arrays.asList(Arrays.copyOfRange(segments, 1, segments.length));

                registry.register(name, type, proxies);
            }
        }

        Map<DestinationPattern, String> rules = Maps.newLinkedHashMap();
        Ini.Section rule = ini.getSection("Rule");
        if (null != rule) {
            rules = RulesParser.parseRules(rule.keySet(), url);
            for (Map.Entry<DestinationPattern, String> entry : rules.entrySet()) {
                registry.register(entry.getKey(), entry.getValue());
            }
        }
        return registry;
    }


    private RouteletContext load(final String url, final RouteletContext parent) throws IOException, ConfigurationException {
        return new RefreshableServerRegistry(new ExternalServerReader(stats), new URL(url), parent).refresh();
    }

}