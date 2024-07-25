package com.github.pangolin.routing.handler.extra;

import com.github.pangolin.routing.route.Route;
import com.github.pangolin.routing.route.RouteRegistry;
import com.github.pangolin.routing.route.predicate.DomainPatternRoutePredicate;
import com.github.pangolin.routing.route.predicate.RoutePredicate;
import com.github.pangolin.routing.route.predicate.SubnetRoutePredicate;
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

/**
 * Chrome Switchy/SwitchyOmega rule list handler.
 */
@Slf4j
public class SwitchyRuleConfigurationServerHandler extends ChannelInboundHandlerAdapter {
    private static final int MAX_HTTP_CONTENT_LENGTH = 8 * 1024 * 1024;
    private static final String DEFAULT_SWITCHY_SORL_PATH = "/switchy.sorl";

    private final String switchySorlPath;
    private final RouteRegistry routeRegistry;

    public SwitchyRuleConfigurationServerHandler(final RouteRegistry routeRegistry) {
        this(DEFAULT_SWITCHY_SORL_PATH, routeRegistry);
    }

    public SwitchyRuleConfigurationServerHandler(final String switchySorlPath, final RouteRegistry routeRegistry) {
        this.switchySorlPath = switchySorlPath;
        this.routeRegistry = routeRegistry;
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

                    final Iterable<Route> routes = routeRegistry.routes();
                    for (final Route route : routes) {
                        if (!"DIRECT".equalsIgnoreCase(route.getUpstream())) {
                            final Iterable<RoutePredicate> predicates = route.getPredicates();
                            for (RoutePredicate predicate : predicates) {
                                buff.append(toSwitchyRule(predicate)).append("\r\n");
                            }
                        }
                    }
                    /*
                    final Map<RoutePredicate, String> rules = routeRegistry.getRoutes();
                    for (Map.Entry<RoutePredicate, String> entry : rules.entrySet()) {
                        final RoutePredicate predicate = entry.getKey();
                        if (!"DIRECT".equalsIgnoreCase(entry.getValue())) {
                            buff.append(toSwitchyRule(predicate)).append("\r\n");
                        }
                    }
                    */

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


    private static String toSwitchyRule(final RoutePredicate pattern) {
        if (pattern instanceof DomainPatternRoutePredicate) {
            final String prefixWildcard = "**.";
            final String suffixWildcard = ".**";
            final DomainPatternRoutePredicate dp = (DomainPatternRoutePredicate) pattern;

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
        } else if (pattern instanceof SubnetRoutePredicate) {
            final SubnetRoutePredicate p = (SubnetRoutePredicate) pattern;
            final int prefixLength = p.getCidrPrefix();
            final InetAddress sa = p.getNetworkAddress();
            return String.format("Ip: %s/%s", sa.getHostAddress(), prefixLength);
            /*
            RoutePredicate delegate = p.getDelegate();
            if (delegate instanceof SubnetRoutePredicate.Inet4SubnetPattern) {
                SubnetRoutePredicate.Inet4SubnetPattern i4sn = (SubnetRoutePredicate.Inet4SubnetPattern) delegate;
                String networkAddress = i4sn.getNetworkAddress();
                String subnetMask = i4sn.getSubnetMask();
                return String.format("%s/%s", networkAddress, subnetMask);
            }
            */
        }
        return String.format("! NOT SUPPORTED: %s", pattern);
    }
}