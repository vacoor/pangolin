package com.github.pangolin.routing.context.spi;

import com.github.pangolin.routing.acceptor.mixin.MixinAcceptor;
import com.github.pangolin.routing.acceptor.tun.TunAcceptor;
import com.github.pangolin.routing.acceptor.tun.adapter.InterfaceAddressEx;
import com.github.pangolin.routing.acceptor.tun.fakedns.FakeDnsAcceptor;
import com.github.pangolin.routing.context.InheritableRouteContext;
import com.github.pangolin.routing.context.RouteContext;
import com.github.pangolin.routing.support.AliasRegistry;
import com.github.pangolin.routing.upstream.DirectUpstream;
import com.github.pangolin.routing.upstream.Upstream;
import com.sun.jna.Platform;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.net.URL;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
public class DefaultRouteContextFactory extends AbstractRouteContextFactory {

    @Override
    public RouteContext create(final URL url, final RouteContext parent) throws Exception {
        return load(url, parent);
    }

    public RouteContext load(final URL configLocation, final RouteContext parent) throws Exception {
        final Ini ini = new Ini();

        log.info("Load config: {}", configLocation);
        ini.load(configLocation.openStream());


        final InheritableRouteContext iniContext = new InheritableRouteContext(configLocation, parent);
        final AliasRegistry aliasRegistry = iniContext;

        final Ini.Section proxy = ini.getSection("Proxy");
        if (null != proxy) {
            proxy.forEach((k, v) -> iniContext.addUpstream(k, apply(k, v)));
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
                    aliasRegistry.registerAlias(DirectUpstream.INSTANCE.name(), name);
                } else if (1 == proxies.size()) {
                    aliasRegistry.registerAlias(proxies.iterator().next(), name);
                } else {
                    final Upstream combine = combine(name, type, proxies, iniContext);
                    if (null != combine) {
                        iniContext.addUpstream(name, combine);
                    } else {
                        log.warn("Unable upstream combine type {}", type);
                    }
                }
            }
        }

        Ini.Section rule = ini.getSection("Rule");
        if (null != rule) {
            rule.keySet().stream().map(route -> apply(route, configLocation, aliasRegistry, true)).forEach(iniContext::addRoute);
        }

        // TODO
        Ini.Section listen = ini.getSection("Listen");
        if (null != listen) {
            /*-
             * [Listen]
             * listen-port = upstream, protocol1, protocol2, ...
             */
            for (final Map.Entry<String, String> entry : listen.entrySet()) {
                final String port = entry.getKey();
                final String definition = entry.getValue();
                final int listenPort = Integer.parseInt(port);
                final String[] segments = definition.split("\\s*,\\s*");
                if (segments.length < 2) {
                    throw new IllegalArgumentException("Unable to create Acceptor with definition " + definition);
                }
                iniContext.addAcceptors(new MixinAcceptor(listenPort, segments[0], Arrays.copyOfRange(segments, 1, segments.length)));
            }
        }


        final Ini.Section fakeDns = ini.getSection("Fake DNS");
        if (null != fakeDns) {
            /*-
             * [Fake DNS]
             * INET4 = 198.18.0.1/24
             * INET6 = 2001:2::/48
             * LEASE-TIME = 60
             */
            final String inet4 = fakeDns.get("INET4");
            final String inet6 = fakeDns.get("INET6");
            if (StringUtils.hasText(inet4) || StringUtils.hasText(inet6)) {
                final String inet4subnet = StringUtils.hasText(inet4) ? inet4 : "198.18.0.1/24";
                final String inet6subnet = StringUtils.hasText(inet6) ? inet6 : "2001:2::/48";

                final String leaseTime = fakeDns.get("LEASE-TIME");
                final int leaseTimeSeconds = StringUtils.hasText(leaseTime) ? Integer.parseInt(leaseTime) : 60;

                iniContext.addAcceptors(new FakeDnsAcceptor(inet4subnet, inet6subnet, leaseTimeSeconds));
            }
        }

        Ini.Section tun = ini.getSection("Tun");
        if (null != tun) {
            /*-
             * [Tun]
             * 198.18.0.1/24 = upstream
             */
            Map.Entry<String, String> entry = tun.entrySet().iterator().next();
            final String binding = entry.getKey();
            final String[] segments = binding.split("/");
            final String addr = segments[0];
            final int len = segments.length > 1 ? Integer.parseInt(segments[1]) : 32;
            final InterfaceAddressEx bindingToUse = InterfaceAddressEx.of(addr, len);

            final String ifname = Platform.isWindows() ? "Pangolin" : null;
            iniContext.addAcceptors(new TunAcceptor(ifname, new InterfaceAddressEx[]{bindingToUse}, entry.getValue()));
        }

        final Ini.Section external = ini.getSection("External");
        RouteContext externalCtx = iniContext;
        if (null != external) {
            for (String urlToUse : external.values()) {
                externalCtx = loadExternal(urlToUse, externalCtx);
            }
//            return externalCtx;
        }
        // return iniContext;
        return externalCtx;
    }


    private RouteContext loadExternal(final String url, final RouteContext parent) throws Exception {
        final ExternalRouteContextFactory reader = new ExternalRouteContextFactory();
        reader.setUpstreamFactories(upstreamFactories);
        reader.setUpstreamCombiners(upstreamCombiners);
        reader.setRoutePredicateFactories(predicateFactories);
        return reader.load(URI.create(url).toURL(), parent);
    }

}
