package com.github.pangolin.routing.context.spi;

import com.github.pangolin.routing.context.InMemoryRouteContext;
import com.github.pangolin.routing.context.RouteContext;
import com.github.pangolin.routing.server.acceptor.Acceptor;
import com.github.pangolin.routing.server.acceptor.AcceptorFactory;
import com.github.pangolin.routing.server.acceptor.MixinAcceptorFactory;
import com.github.pangolin.routing.support.AliasRegistry;
import lombok.extern.slf4j.Slf4j;

import java.net.URL;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

@Slf4j
public class DefaultRouteContextFactory extends AbstractRouteContextFactory {
    private final AcceptorFactory acceptorFactory = createAcceptorFactory();

    protected AcceptorFactory createAcceptorFactory() {
        final Iterable<AcceptorFactory> factories = ServiceLoader.load(AcceptorFactory.class);
        final Iterator<AcceptorFactory> it = factories.iterator();
        return it.hasNext() ? it.next() : new MixinAcceptorFactory();
    }

    @Override
    public RouteContext createContext(final URL url, final RouteContext parent) throws Exception {
        return load(url, parent);
    }

    public RouteContext load(final URL configLocation, final RouteContext parent) throws Exception {
        final Ini ini = new Ini();
        ini.load(configLocation.openStream());

        final Ini.Section external = ini.getSection("External");

        RouteContext externalCtx = parent;
        if (null != external) {
            for (String urlToUse : external.values()) {
                externalCtx = loadExternal(urlToUse, externalCtx);
            }
        }

        final InMemoryRouteContext registry = new InMemoryRouteContext(externalCtx);
        final AliasRegistry aliasRegistry = registry;

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
                final List<String> proxies = Arrays.stream(Arrays.copyOfRange(segments, 1, segments.length))
                        .map(aliasRegistry::canonicalName).collect(Collectors.toList());

                proxies.remove("DIRECT");

                if (proxies.isEmpty()) {
                    aliasRegistry.registerAlias("DIRECT", name);
                } else if (1 == proxies.size()) {
                    aliasRegistry.registerAlias(proxies.iterator().next(), name);
                } else {
                    registry.addUpstream(name, apply(name, type, proxies, registry));
                }
            }
        }

        Ini.Section rule = ini.getSection("Rule");
        if (null != rule) {
            rule.keySet().stream().map(route -> apply(route, configLocation, aliasRegistry)).forEach(registry::addRoute);
        }


        // TODO
        Ini.Section listen = ini.getSection("Listen");
        if (null == listen) {
            listen = ini.addSection("Listen");
//            listen.put("1080", "DEFAULT, SOCKS5, SOCKS4, HTTP");
        }

        for (final Map.Entry<String, String> entry : listen.entrySet()) {
            final String port = entry.getKey();
            final String definition = entry.getValue();
            final int listenPort = Integer.parseInt(port);
            final String[] segments = definition.split("\\s*,\\s*");
            if (segments.length < 2) {
                throw new IllegalArgumentException("Unable to create Acceptor with definition " + definition);
            }

            final Acceptor acceptor = acceptorFactory.apply(listenPort, segments);
            if (null != acceptor) {
                registry.addAcceptors(acceptor);
            }
        }

        return registry;
    }


    private RouteContext loadExternal(final String url, final RouteContext parent) throws Exception {
        final ExternalRouteContextFactory reader = new ExternalRouteContextFactory();
        reader.setUpstreamFactories(upstreamFactories);
        reader.setUpstreamCombiners(upstreamCombiners);
        reader.setRoutePredicateFactories(predicateFactories);
        return reader.load(new URL(url), parent);
    }

}