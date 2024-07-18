package com.github.pangolin.routing.config.resolver;

import com.github.pangolin.routing.route.predicate.RoutePredicate;
import com.google.common.collect.Maps;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 *
 */
public class RouteParser {

    public static Map<RoutePredicate, String> parseRoutes(final Collection<String> rules, final URL url) throws IOException {
        final Map<RoutePredicate, String> rulesToUse = Maps.newHashMap();
        for (final String rule : rules) {
            parseRoutes(rule, url, rulesToUse);
        }
        return rulesToUse;
    }

    private static void parseRoutes(final String line, final URL url, final Map<RoutePredicate, String> collector) {
        final String lineToUse = line.replaceAll(",no-resolve$", "");
        final int i = lineToUse.lastIndexOf(",");
        if (-1 < i) {
            final String predicateDefinition = lineToUse.substring(0, i);
            final List<RoutePredicate> predicates = RoutePredicateResolvers.resolve(predicateDefinition, url);
            if (null != predicates) {
                for (RoutePredicate predicate : predicates) {
                    final String proxyType = lineToUse.substring(i + 1);
                    collector.put(predicate, proxyType);
                }
            }
        }
    }

    /**
     *
     */
    @Slf4j
    public static class RoutePredicateResolvers {
        public static final DomainRuleResolver DOMAIN = new DomainRuleResolver();
        public static final DomainSuffixRuleResolver DOMAIN_SUFFIX = new DomainSuffixRuleResolver();
        public static final DomainKeywordRuleResolver DOMAIN_KEYWORD = new DomainKeywordRuleResolver();
        public static final IpCidrRuleResolver IP_CIDR = new IpCidrRuleResolver();
        public static final IpCidr6RuleResolver IP_CIDR6 = new IpCidr6RuleResolver();
        public static final RuleSetResolver<RoutePredicate> RULE_SET = new RuleSetResolver<>(
                DOMAIN, DOMAIN_SUFFIX, DOMAIN_KEYWORD,
                IP_CIDR, IP_CIDR6
        );

        public static final Resolver<RoutePredicate>[] ALL = new Resolver[]{
                DOMAIN, DOMAIN_SUFFIX, DOMAIN_KEYWORD,
                IP_CIDR, IP_CIDR6, RULE_SET
        };

        public static List<RoutePredicate> resolve(final String rule, final URL url) {
            try {
                for (final Resolver<RoutePredicate> resolver : ALL) {
                    if (resolver.matches(rule)) {
                        return resolver.resolve(rule, url);
                    }
                }
                return Collections.emptyList();
            } catch (final IOException e) {
                RoutePredicateResolvers.log.error("Resolve error:  {} in {} -> {}", rule, url, e.getMessage(), e);
                return Collections.emptyList();
            }
        }
    }
}
