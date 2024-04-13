package com.github.pangolin.routing.handler;

import com.github.pangolin.routing.rule.RulesProvider;
import com.github.pangolin.routing.rule.pattern.DestinationPattern;
import com.github.pangolin.routing.rule.pattern.DomainPattern;
import com.github.pangolin.routing.rule.pattern.SubnetPattern;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.Set;

/**
 * Proxy Auto-Configuration File Server Handler.
 *
 * @see <a href="https://developer.mozilla.org/zh-CN/docs/Web/HTTP/Proxy_servers_and_tunneling/Proxy_Auto-Configuration_PAC_file">代理自动配置文件（PAC）</a>
 * @see <a href="https://github.com/manugarg/pacparser">PAC parser</a>
 */
@Slf4j
public class ProxyAutoConfigurationServerHandler extends ChannelInboundHandlerAdapter {
    private static final int MAX_HTTP_CONTENT_LENGTH = 8 * 1024 * 1024;
    private static final String DEFAULT_PATH = "/proxy.pac";

    private final String path;
    private final RulesProvider rulesProvider;

    public ProxyAutoConfigurationServerHandler(final RulesProvider rulesProvider) {
      this(DEFAULT_PATH, rulesProvider);
    }

    public ProxyAutoConfigurationServerHandler(final String path, final RulesProvider rulesProvider) {
        this.path = path;
        this.rulesProvider = rulesProvider;
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
                    final String hostname = getHttpRequestAddress(httpRequest).getHostString();
                    final String pac = toPac(rulesProvider.getRules());
                    final String pacToUse = pac.replace("127.0.0.1", hostname);
                    final ByteBuf body = Unpooled.copiedBuffer(pacToUse, StandardCharsets.UTF_8);

                    final DefaultFullHttpResponse httpResponse = new DefaultFullHttpResponse(httpRequest.protocolVersion(), HttpResponseStatus.OK, body);
                    httpResponse.headers().add("Content-Length", body.readableBytes());
                    // httpResponse.headers().add("Content-Type", "application/x-ns-proxy-autoconfig");
                    ctx.writeAndFlush(httpResponse);
                    return;
                }
            }
            ctx.fireChannelRead(ReferenceCountUtil.retain(msg));
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }


    private InetSocketAddress getHttpRequestAddress(final HttpRequest httpRequest) {
        final HttpHeaders headers = httpRequest.headers();
        final String host = headers.get(HttpHeaderNames.HOST);
        if (null != host && !host.isEmpty()) {
            final String[] segments = host.split(":");
            final int port = segments.length > 1 ? Integer.parseInt(segments[1]) : determinePort(0, httpRequest.uri());
            return new InetSocketAddress(segments[0], port);
        } else {
            final URI uri = URI.create(httpRequest.uri());
            final int port = determinePort(uri.getPort(), httpRequest.uri());
            return new InetSocketAddress(uri.getHost(), port);
        }
    }

    private int determinePort(final int port, final String uri) {
        return port > 0 ? port : uri.toLowerCase().startsWith("https://") ? 443 : 80;
    }

    private static String toPac(final Map<DestinationPattern, String> rules) {
        final StringBuilder buff = new StringBuilder();
        final String now = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        buff.append("/**\r\n")
                .append(" * Proxy Auto-Configuration (PAC) file.\r\n")
                .append(" *\r\n")
                .append(" * Date: ").append(now).append("\r\n")
                .append(" * Link: https://developer.mozilla.org/zh-CN/docs/Web/HTTP/Proxy_servers_and_tunneling/Proxy_Auto-Configuration_PAC_file\r\n")
                .append(" * Link: https://github.com/manugarg/pacparser\r\n")
                .append(" */\r\n");

        buff.append("function FindProxyForURL(url, host) {\r\n");
        buff.append("  ").append("var $PROXY = 'SOCKS5 127.0.0.1:1080; SOCKS 127.0.0.1:1080; PROXY 127.0.0.1:1080';\r\n");
        for (final Map.Entry<DestinationPattern, String> entry : rules.entrySet()) {
            DestinationPattern destinationPattern = entry.getKey();
            String s = toPacStatement(destinationPattern);
            buff.append("  ").append(s).append("\r\n");
        }
        buff.append("  if (!isResolvable(host)) return $PROXY + '; DIRECT';\r\n");
        buff.append("  return 'DIRECT';\r\n");
        buff.append("}");
        System.out.println(buff);
        return buff.toString();
    }

    private static String toPacStatement(final DestinationPattern pattern) {
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
                    return String.format("if (shExpMatch(host, '%s')) return $PROXY;", s1);
                } else {
                    System.out.println("Unsupported");
                }
            } else if (isPrefixWildcard) {
                s1 = s1.replace("**.", "");
                return String.format("if (dnsDomainIs(host, '.%s')) return $PROXY;", s1);
            } else {
                return String.format("if (shExpMatch(host, '%s')) return $PROXY;", s1);
            }
        } else if (pattern instanceof SubnetPattern) {
            final SubnetPattern p = (SubnetPattern) pattern;
            DestinationPattern delegate = p.getDelegate();
            if (delegate instanceof SubnetPattern.Inet4SubnetPattern) {
                SubnetPattern.Inet4SubnetPattern i4sn = (SubnetPattern.Inet4SubnetPattern) delegate;
                String networkAddress = i4sn.getNetworkAddress();
                String subnetMask = i4sn.getSubnetMask();
                return String.format("if (isInNet(host, '%s', '%s')) return $PROXY;", networkAddress, subnetMask);
            }
        }
        return String.format("/* NOT SUPPORTED: %s */", pattern);
    }
}