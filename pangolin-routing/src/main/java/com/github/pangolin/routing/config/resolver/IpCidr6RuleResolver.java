package com.github.pangolin.routing.config.resolver;

import com.github.pangolin.routing.rule.pattern.DestinationPattern;
import com.github.pangolin.routing.rule.pattern.SubnetPattern;
import io.netty.util.NetUtil;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.List;

/**
 *
 */
public class IpCidr6RuleResolver extends AbstractPrefixRuleResolver<DestinationPattern> {

    public IpCidr6RuleResolver() {
        super("IP-CIDR6,");
    }

    @Override
    public boolean matches(final String rule) {
        if (super.matches(rule)) {
            final String[] segments = rule.substring(prefix.length()).split("/", 2);
            return segments.length == 2 && Utils.isDigit(segments[1]) && NetUtil.isValidIpV6Address(segments[0]);
        }
        return false;
    }

    @Override
    protected List<DestinationPattern> doResolve(final String rule, final URL url) throws IOException {
        final String[] segments = rule.split("/", 2);
        final int cidrPrefix = Integer.parseInt(segments[1]);
        return Collections.singletonList(
                new SubnetPattern(segments[0], cidrPrefix)
        );
    }

}
