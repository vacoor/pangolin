package com.github.pangolin.routing.config;

import com.github.pangolin.routing.config.resolver.RuleResolvers;
import com.github.pangolin.routing.rule.pattern.DestinationPattern;
import com.google.common.collect.Maps;

import java.io.IOException;
import java.net.URL;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 *
 */
public class RulesParser {

    public static Map<DestinationPattern, String> parseRules(final Collection<String> rules, final URL url) throws IOException {
        final Map<DestinationPattern, String> rulesToUse = Maps.newHashMap();
        for (final String rule : rules) {
            parseRule(rule, url, rulesToUse);
        }
        return rulesToUse;
    }

    private static void parseRule(final String line, final URL url, final Map<DestinationPattern, String> collector) {
        final String lineToUse = line.replaceAll(",no-resolve$", "");
        final int i = lineToUse.lastIndexOf(",");
        if (-1 < i) {
            final String patternDefinition = lineToUse.substring(0, i);
            final List<DestinationPattern> patterns = RuleResolvers.resolve(patternDefinition, url);
            if (null != patterns) {
                for (DestinationPattern pattern : patterns) {
                    final String proxyType = lineToUse.substring(i + 1);
                    collector.put(pattern, proxyType);
                }
            }
        }
    }

}
