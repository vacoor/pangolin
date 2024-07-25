package com.github.pangolin.routing.context.spi;

import com.github.pangolin.routing.context.AbstractRouteContextFactory;
import com.github.pangolin.routing.context.InMemoryRouteContext;
import com.github.pangolin.routing.context.Ini;
import com.github.pangolin.routing.context.RouteContext;
import com.github.pangolin.routing.server.AcceptorFactory;
import com.github.pangolin.routing.server.MixinAcceptorFactory;
import com.google.common.collect.Maps;
import lombok.extern.slf4j.Slf4j;

import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Slf4j
public class DefaultRouteContextFactory extends AbstractRouteContextFactory {
    private final AcceptorFactory acceptorFactory = new MixinAcceptorFactory();

    @Override
    public RouteContext createContext(final URL url, final RouteContext parent) throws Exception {
        return load(url, parent);
    }

    public RouteContext load(final URL url, final RouteContext parent) throws Exception {
        final Ini ini = new Ini();
        ini.load(url.openStream());

        final Ini.Section external = ini.getSection("External");
        RouteContext parentToUse = parent;
        if (null != external) {
            for (String urlToUse : external.values()) {
                parentToUse = load(urlToUse, parentToUse);
            }
        }

        final InMemoryRouteContext registry = new InMemoryRouteContext(parentToUse);
        final Ini.Section proxy = ini.getSection("Proxy");
        if (null != proxy) {
            proxy.forEach((k, v) -> registry.addUpstream(k, apply(k, v)));
        }

        final Ini.Section proxyGroups = ini.getSection("Proxy Group");
        final Map<String, String> nameMapping = Maps.newLinkedHashMap();
        if (null != proxyGroups) {
            for (final Map.Entry<String, String> entry : proxyGroups.entrySet()) {
                final String name = entry.getKey();
                final String value = entry.getValue();
                final String[] segments = value.split("\\s*,\\s*");
                final String type = segments[0];
                final List<String> proxies = Arrays.asList(Arrays.copyOfRange(segments, 1, segments.length));

                if (proxies.isEmpty() || (1 == proxies.size() && proxies.contains("DIRECT"))) {
                    nameMapping.put(name, "DIRECT");
                }
                registry.addUpstream(name, apply(name, type, proxies, registry));
            }
        }

        Ini.Section rule = ini.getSection("Rule");
        if (null != rule) {
            rule.keySet().stream().map(route -> apply(route, url, nameMapping)).forEach(registry::addRoute);
        }


        // TODO
        Ini.Section listen = ini.getSection("Listen");
        if (null == listen) {
            listen = ini.addSection("Listen");
            listen.put("1080", "DEFAULT, SOCKS5, SOCKS4, HTTP");
        }

        for (final Map.Entry<String, String> entry : listen.entrySet()) {
            final String port = entry.getKey();
            final String definition = entry.getValue();
            final int listenPort = Integer.parseInt(port);
            final String[] segments = definition.split("\\s*,\\s*");
            if (segments.length < 2) {
                throw new IllegalArgumentException("Unable to create Acceptor with definition " + definition);
            }

            registry.addAcceptors(acceptorFactory.apply(listenPort, segments));
        }
        ;

        return registry;
    }


    private RouteContext load(final String url, final RouteContext parent) throws Exception {
        final ExternalRouteContextFactory reader = new ExternalRouteContextFactory();
        reader.setUpstreamFactories(upstreamFactories);
        reader.setUpstreamCombiners(upstreamCombiners);
        reader.setRoutePredicateFactories(predicateFactories);
        return reader.load(new URL(url), parent);
    }

}