package com.github.pangolin.routing.config;

import com.github.pangolin.routing.config.clash.ClashRuleFactory;
import com.github.pangolin.routing.config.clash.ClashRuleResolver;
import com.github.pangolin.routing.rule.pattern.DestinationPattern;

import java.io.IOException;
import java.net.URL;
import java.util.Map;

/**
 * TODO DOC ME!.
 *
 * @author changhe.yang
 * @since 20240410
 */
public class RulesParser {

  public static Map<DestinationPattern, String> parseRules(final URL url) throws IOException {
    return ClashRuleFactory.parseRules(
        url,
        ClashRuleResolver.DOMAIN,
        ClashRuleResolver.DOMAIN_SUFFIX,
        ClashRuleResolver.DOMAIN_KEYWORD,
        ClashRuleResolver.IP_CIDR,
        ClashRuleResolver.IP_CIDR_6,
        ClashRuleResolver.RULE_SET
    );
  }

}
