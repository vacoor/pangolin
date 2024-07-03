package com.github.pangolin.routing.handler.extra;

import com.github.pangolin.routing.rule.RulesProvider;
import com.github.pangolin.routing.rule.pattern.DestinationPattern;
import com.github.pangolin.routing.rule.pattern.DomainPattern;
import com.github.pangolin.routing.rule.pattern.SubnetPattern;
import com.sun.net.httpserver.HttpServer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;

/**
 * Chrome Switchy/SwitchyOmega rule list handler.
 */
@Slf4j
public class SwitchyRuleConfigurationServerHandler extends ChannelInboundHandlerAdapter {
    private static final int MAX_HTTP_CONTENT_LENGTH = 8 * 1024 * 1024;
    private static final String DEFAULT_SWITCHY_SORL_PATH = "/switchy.sorl";

    private final String switchySorlPath;
    private final RulesProvider rulesProvider;

    public SwitchyRuleConfigurationServerHandler(final RulesProvider rulesProvider) {
        this(DEFAULT_SWITCHY_SORL_PATH, rulesProvider);
    }

    public SwitchyRuleConfigurationServerHandler(final String switchySorlPath, final RulesProvider rulesProvider) {
        this.switchySorlPath = switchySorlPath;
        this.rulesProvider = rulesProvider;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
        final ChannelPipeline cp = ctx.pipeline();
        if (null == cp.get(HttpServerCodec.class)) {
            cp.addBefore(ctx.name(), HttpServer.class.getName(), new HttpServerCodec());
        }
        if (null == cp.get(HttpObjectAggregator.class)) {
            cp.addBefore(ctx.name(), HttpObjectAggregator.class.getName(), new HttpObjectAggregator(MAX_HTTP_CONTENT_LENGTH));
        }
    }

    @Override
    public void handlerRemoved(final ChannelHandlerContext ctx) throws Exception {
        final ChannelPipeline cp = ctx.pipeline();
        if (null != cp.get(HttpObjectAggregator.class)) {
            cp.remove(HttpObjectAggregator.class);
        }
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        try {
            if (msg instanceof FullHttpRequest) {
                final FullHttpRequest httpRequest = (FullHttpRequest) msg;
                final String requestPath = new QueryStringDecoder(httpRequest.uri()).path();
                if (requestPath.equals(this.switchySorlPath)) {
                    final String generatedDate = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
                    final StringBuilder buff = new StringBuilder();
                    buff.append("; SwitchyOmega Rule List\r\n")
                            .append(";\r\n")
                            .append("; Require: SwitchyOmega >= 2.3.2\r\n")
                            .append("; Date: ").append(generatedDate).append("\r\n")
                            .append("; Usage: https://github.com/FelisCatus/SwitchyOmega/wiki/RuleListUsage\r\n\r\n");

                    final Map<DestinationPattern, String> rules = rulesProvider.getRules();
                    for (Map.Entry<DestinationPattern, String> entry : rules.entrySet()) {
                        final DestinationPattern destinationPattern = entry.getKey();
                        if (!"DIRECT".equalsIgnoreCase(entry.getValue())) {
                            buff.append(toSwitchyRule(destinationPattern)).append("\r\n");
                        }
                    }

                    final ByteBuf body = Unpooled.copiedBuffer(buff.toString(), StandardCharsets.UTF_8);
                    final DefaultFullHttpResponse httpResponse = new DefaultFullHttpResponse(httpRequest.protocolVersion(), HttpResponseStatus.OK, body);
                    httpResponse.headers().add("Content-Length", body.readableBytes());
                    ctx.writeAndFlush(httpResponse);
                    return;
                }
            }
            ctx.fireChannelRead(ReferenceCountUtil.retain(msg));
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }


    private static String toSwitchyRule(final DestinationPattern pattern) {
        if (pattern instanceof DomainPattern) {
            final String prefixWildcard = "**.";
            final String suffixWildcard = ".**";
            final DomainPattern dp = (DomainPattern) pattern;

            String patternStr = dp.toString();
            final boolean isPrefixWildcard = patternStr.startsWith(prefixWildcard);
            final boolean isSuffixWildcard = patternStr.endsWith(suffixWildcard);
            if (isPrefixWildcard && isSuffixWildcard) {
                patternStr = patternStr.replace("**.", "").replace(".**", "");
                if (patternStr.startsWith("*") && patternStr.endsWith("*")) {
                    patternStr = patternStr.substring("*".length(), patternStr.length() - 1);
                    return "*." + patternStr + ".*";
                }
            } else if (isPrefixWildcard) {
                patternStr = patternStr.replace("**.", "");
                return "*." + patternStr;
            } else {
                return patternStr;
            }
        } else if (pattern instanceof SubnetPattern) {
            final SubnetPattern p = (SubnetPattern) pattern;
            final int prefixLength = p.getPrefixLength();
            final InetAddress sa = p.getNetworkAddress();
            return String.format("Ip: %s/%s", sa.getHostAddress(), prefixLength);
            /*
            DestinationPattern delegate = p.getDelegate();
            if (delegate instanceof SubnetPattern.Inet4SubnetPattern) {
                SubnetPattern.Inet4SubnetPattern i4sn = (SubnetPattern.Inet4SubnetPattern) delegate;
                String networkAddress = i4sn.getNetworkAddress();
                String subnetMask = i4sn.getSubnetMask();
                return String.format("%s/%s", networkAddress, subnetMask);
            }
            */
        }
        return String.format("! NOT SUPPORTED: %s", pattern);
    }
}