package com.github.pangolin.routing.config.resolver;

import com.github.pangolin.routing.rule.pattern.DestinationPattern;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.List;

/**
 *
 */
@Slf4j
public class RuleResolvers {
    public static final DomainRuleResolver DOMAIN = new DomainRuleResolver();
    public static final DomainSuffixRuleResolver DOMAIN_SUFFIX = new DomainSuffixRuleResolver();
    public static final DomainKeywordRuleResolver DOMAIN_KEYWORD = new DomainKeywordRuleResolver();
    public static final IpCidrRuleResolver IP_CIDR = new IpCidrRuleResolver();
    public static final IpCidr6RuleResolver IP_CIDR6 = new IpCidr6RuleResolver();
    public static final RuleSetResolver<DestinationPattern> RULE_SET = new RuleSetResolver<>(
            DOMAIN, DOMAIN_SUFFIX, DOMAIN_KEYWORD,
            IP_CIDR, IP_CIDR6
    );

    public static final RuleResolver<DestinationPattern>[] ALL = new RuleResolver[]{
            DOMAIN, DOMAIN_SUFFIX, DOMAIN_KEYWORD,
            IP_CIDR, IP_CIDR6, RULE_SET
    };

    public static List<DestinationPattern> resolve(final String rule, final URL url) {
        try {
            for (final RuleResolver<DestinationPattern> resolver : ALL) {
                if (resolver.matches(rule)) {
                    return resolver.resolve(rule, url);
                }
            }
            return Collections.emptyList();
        } catch (final IOException e) {
            log.error("Resolve error:  {} in {} -> {}", rule, url, e.getMessage(), e);
            return Collections.emptyList();
        }
    }
}
