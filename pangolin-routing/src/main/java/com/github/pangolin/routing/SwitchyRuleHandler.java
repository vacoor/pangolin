package com.github.pangolin.routing;

import com.github.pangolin.routing.pattern.DestinationPattern;
import com.github.pangolin.routing.pattern.DomainPattern;
import com.github.pangolin.routing.pattern.SubnetPattern;
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

import java.nio.charset.StandardCharsets;
import java.util.Map;

@Slf4j
public class SwitchyRuleHandler extends ChannelInboundHandlerAdapter {
    private static final int MAX_HTTP_CONTENT_LENGTH = 8 * 1024 * 1024;
    private final String path = "/switchy.sorl";
    private final Map<DestinationPattern, String> rules;

    public SwitchyRuleHandler(final Map<DestinationPattern, String> rules) {
      this.rules = rules;
    }

    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
        final ChannelPipeline cp = ctx.pipeline();
        if (null == cp.get(HttpServerCodec.class)) {
            cp.addBefore(ctx.name(), null, new HttpServerCodec());
        }
        if (null == cp.get(HttpObjectAggregator.class)) {
            cp.addBefore(ctx.name(), null, new HttpObjectAggregator(MAX_HTTP_CONTENT_LENGTH));
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
                  final String path = new QueryStringDecoder(httpRequest.uri()).path();
                  if (path.equals(this.path)) {
                      final StringBuilder buff = new StringBuilder();
                      for (DestinationPattern destinationPattern : rules.keySet()) {
                          buff.append(toSwitchyRule(destinationPattern)).append("\r\n");
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
            String s1 = dp.toString();
            final boolean isPrefixWildcard = s1.startsWith(prefixWildcard);
            final boolean isSuffixWildcard = s1.endsWith(suffixWildcard);
            if (isPrefixWildcard && isSuffixWildcard) {
                s1 = s1.replace("**.", "").replace(".**", "");
                if (s1.startsWith("*") && s1.endsWith("*")) {
                    s1 = s1.substring("*".length(), s1.length() - 1);
                    return "*." + s1 + ".*";
                }
            } else if (isPrefixWildcard) {
                s1 = s1.replace("**.", "");
                return "*." + s1;
            } else {
                return s1;
            }
        } else if (pattern instanceof SubnetPattern) {
            final SubnetPattern p = (SubnetPattern) pattern;
            DestinationPattern delegate = p.getDelegate();
            if (delegate instanceof SubnetPattern.Inet4SubnetPattern) {
                SubnetPattern.Inet4SubnetPattern i4sn = (SubnetPattern.Inet4SubnetPattern) delegate;
                String networkAddress = i4sn.getNetworkAddress();
                String subnetMask = i4sn.getSubnetMask();
                return String.format("%s/%s", networkAddress, subnetMask);
            }
        }
        return String.format("! NOT SUPPORTED: %s", pattern);
    }
}