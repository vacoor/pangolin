package com.github.pangolin.routing.context;

import com.github.pangolin.routing.config.ConfigurationException;
import com.github.pangolin.routing.config.Ini;
import com.github.pangolin.routing.config.ProxyGroupDefinition;
import com.github.pangolin.routing.config.clash.SubConfiguration;
import com.github.pangolin.routing.proxy.ProxyServer;
import com.github.pangolin.routing.proxy.ServerFactory;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.netflix.loadbalancer.LoadBalancerStats;

import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 */
public class IniTest {

    public static void main(String[] args) throws ConfigurationException, IOException {
        final LoadBalancerStats stats = new LoadBalancerStats();
        final ServerFactory serverFactory = new ServerFactory(stats);

        final Ini ini = new Ini();
        ini.load(IniTest.class.getResourceAsStream("/conf/default.conf"));

//        final SubConfiguration external = parseExternal(ini.getSection("External"), serverFactory);

        final Ini.Section proxy = ini.getSection("Proxy");
        final Map<String, ProxyServer> nameToProxyMap = Maps.newLinkedHashMap();

        for (final Map.Entry<String, String> entry : proxy.entrySet()) {
            final ProxyServer server = DefinitionUtils.parseServer(entry.getKey(), entry.getValue(), serverFactory);
            if (null != server) {
                 nameToProxyMap.put(server.getName(), server);
            }
        }

        final Ini.Section proxyGroups = ini.getSection("Proxy Group");
        final List<ProxyGroupDefinition> groups = Lists.newLinkedList();
        for (final Map.Entry<String, String> entry : proxyGroups.entrySet()) {
            final String name = entry.getKey();
            final String value = entry.getValue();
            final String[] segments = value.split("\\s*,\\s*");
            final String type = segments[0];

            ProxyGroupDefinition def = new ProxyGroupDefinition();
            def.setName(name);
            def.setType(type);
            def.setProxies(Arrays.asList(Arrays.copyOfRange(segments, 1, segments.length)));
            groups.add(def);
        }

        final Map<String, ProxyServer> nameToGroupMap = SubConfiguration.parseProxyGroups(groups, nameToProxyMap, serverFactory);

        System.out.println(ini);
    }

    private static SubConfiguration parseExternal(final Ini.Section external, final ServerFactory factory) throws IOException {
        if (null != external) {
            SubConfiguration conf = null;
            for (final String url : external.keySet()) {
                conf = parseExternal(conf, url, factory);
            }
            return conf;
        }
        return null;
    }

    private static SubConfiguration parseExternal(final SubConfiguration parent, final String url, final ServerFactory factory) throws IOException {
        return new SubConfiguration(parent, new URL(url), factory).refresh();
    }

    private static <T> Set<T> nvl(final Set<T> value) {
        return nvl(value, Collections.emptySet());
    }
    private static <T> List<T> nvl(final List<T> value) {
        return nvl(value, Collections.emptyList());
    }

    private static <T> T nvl(final T value, final T def) {
        return null != value ? value : def;
    }

}
