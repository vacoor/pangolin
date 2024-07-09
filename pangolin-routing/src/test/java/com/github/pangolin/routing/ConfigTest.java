package com.github.pangolin.routing;

import com.github.pangolin.routing.config.RulesParser;
import com.github.pangolin.routing.config.resolver.ProxyResolver;
import com.github.pangolin.routing.config.resolver.ProxySetResolver;
import com.github.pangolin.routing.config.resolver.Resolver;
import com.github.pangolin.routing.config.resolver.Utils;
import com.github.pangolin.routing.proxy.ProxyServer;
import com.github.pangolin.routing.rule.pattern.DestinationPattern;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ConfigTest {
    public static void main(String[] args) throws IOException {
        final URL url = ConfigTest.class.getResource("/conf/pangolin.conf");


        final List<ProxyServer> proxies = Lists.newLinkedList();
        final Map<DestinationPattern, String> rules = Maps.newLinkedHashMap();
        Utils.lines(url, StandardCharsets.UTF_8)
                .forEach(line -> {
                    try {
                        proxies.addAll(parseProxies(line, url));
                        rules.putAll(parseRules(line, url));
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
        System.out.println(proxies);
    }

    private static List<ProxyServer> parseProxies(final String line, final URL url) throws IOException {
        final Resolver<ProxyServer>[] proxyResolvers = new Resolver[]{
                new ProxyResolver(), new ProxySetResolver()
        };
        final List<ProxyServer> servers = Lists.newLinkedList();
        for (Resolver<ProxyServer> proxyResolver : proxyResolvers) {
            if (proxyResolver.matches(line)) {
                servers.addAll(proxyResolver.resolve(line, url));
            }
        }
        return servers;
    }

    private static Map<DestinationPattern, String> parseRules(final String line, final URL url) throws IOException {
        return RulesParser.parseRules(Collections.singleton(line), url);
    }
}