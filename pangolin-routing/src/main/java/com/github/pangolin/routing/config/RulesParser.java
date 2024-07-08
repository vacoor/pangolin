package com.github.pangolin.routing.config;

import com.github.pangolin.routing.rule.pattern.DestinationPattern;
import com.github.pangolin.routing.config.resolver.RuleResolvers;
import com.github.pangolin.routing.config.resolver.Utils;
import com.google.common.collect.Maps;
import freework.util.StringUtils2;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * TODO DOC ME!.
 *
 * @author changhe.yang
 * @since 20240410
 */
public class RulesParser {

    /*
    public static Map<DestinationPattern, String> parseRules(final URL url) throws IOException {
        return ClashProxiesParser.parseRules(
                url,
                ClashRuleResolver.DOMAIN,
                ClashRuleResolver.DOMAIN_SUFFIX,
                ClashRuleResolver.DOMAIN_KEYWORD,
                ClashRuleResolver.IP_CIDR,
                ClashRuleResolver.IP_CIDR_6,
                ClashRuleResolver.RULE_SET
        );
    }
    */

    public static Map<DestinationPattern, String> parseRules(final URL url) throws IOException {
        final Map<DestinationPattern, String> rules = Maps.newHashMap();
        Utils.lines(url, StandardCharsets.UTF_8)
                .filter(StringUtils2::hasText)
                .forEach(lineToUse -> {
                    parseRule(lineToUse, url, rules);
                });
        return rules;
    }

    public static Map<DestinationPattern, String> parseRules(final Collection<String> rules, final URL url) throws IOException {
        final Map<DestinationPattern, String> rulesToUse = Maps.newHashMap();
        for (final String rule : rules) {
            parseRule(rule, url, rulesToUse);
        }
        return rulesToUse;
    }

    private static void parseRule(final String lineToUse, final URL url, final Map<DestinationPattern, String> collector) {
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
