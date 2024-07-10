package com.github.pangolin.routing.context;

import com.github.pangolin.routing.config.ProxyGroupDefinition;
import com.github.pangolin.routing.proxy.ProxyServer;
import com.github.pangolin.routing.proxy.ServerFactory;
import com.github.pangolin.routing.proxy.ServerProvider;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 *
 */
@Slf4j
public class DefinitionUtils {

    public static ProxyServer parseServer(final String name,
                                          final String serverUrl,
                                          final ServerFactory serverFactory) {
        return serverFactory.resolve(name, serverUrl);
    }


    public static Map<String, ProxyServer> parseServerGroups(final List<ProxyGroupDefinition> definitions,
                                                             final ServerProvider serverProvider, final ServerFactory factory) {
        final Map<String, ProxyServer> nameToGroupMap = Maps.newHashMap();
        final Map<String, ProxyGroupDefinition> definitionMap = definitions.stream()
                .collect(Collectors.toMap(ProxyGroupDefinition::getName, Function.identity(), (prev, next) -> next));
        for (final Map.Entry<String, ProxyGroupDefinition> entry : Maps.newHashMap(definitionMap).entrySet()) {
            if (!nameToGroupMap.containsKey(entry.getKey())) {
                parseServerGroup(entry.getValue(), serverProvider, nameToGroupMap, definitionMap, factory);
            }
        }
        return nameToGroupMap;
    }

    private static ProxyServer parseServerGroup(final ProxyGroupDefinition definition,
                                                final ServerProvider serverProvider,
                                                final Map<String, ProxyServer> nameToGroupMap,
                                                final Map<String, ProxyGroupDefinition> definitionMap,
                                                final ServerFactory factory) {
        final List<String> referenceNames = nvl(definition.getProxies(), Collections.emptyList());
        final List<ProxyServer> referencesToUse = Lists.newArrayList();
        for (final String referenceName : referenceNames) {
            ProxyServer reference = serverProvider.getServer(referenceName);
            if (null == reference) {
                final ProxyGroupDefinition definitionRef = definitionMap.remove(referenceName);
                if (null == definitionRef) {
                    continue;
                }
                reference = parseServerGroup(definitionRef, serverProvider, nameToGroupMap, definitionMap, factory);
                nameToGroupMap.put(referenceName, reference);
            }
            referencesToUse.add(reference);
        }

        final String name = definition.getName();
        final String type = definition.getType();
        final String url = definition.getUrl();

        // UrlTestHealthChecker urlTestHealthChecker = new UrlTestHealthChecker(url, 3000, )
        final ProxyServer group = factory.createServerGroup(name, type, url, referencesToUse);
        nameToGroupMap.put(name, group);
        return group;
    }


    private static <T> T nvl(final T val, final T def) {
        return null != val ? val : def;
    }

}
