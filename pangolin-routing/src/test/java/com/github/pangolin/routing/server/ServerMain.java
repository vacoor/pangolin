package com.github.pangolin.routing.server;

import com.alibaba.fastjson.JSONObject;
import com.github.pangolin.routing.config.LocalProxyServerProviderLoader;
import com.github.pangolin.routing.config.clash.ClashProxyServerProviderFactory;
import com.github.pangolin.routing.config.clash.ClashRuleFactory;
import com.github.pangolin.routing.config.clash.ClashRuleResolver;
import com.github.pangolin.routing.pattern.DestinationPattern;
import com.github.pangolin.routing.pattern.DomainPattern;
import com.github.pangolin.routing.pattern.SubnetPattern;
import com.github.pangolin.routing.proxy.ComposedProxyServerProvider;
import com.github.pangolin.routing.proxy.ProxyServerProvider;
import com.github.pangolin.routing.proxy.RoutingSocks5ServerHandler;
import com.github.pangolin.routing.proxy.RuleBasedRoutingProxyServer;
import com.github.pangolin.server.NettyServer;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.ipfilter.IpSubnetFilterRule;

import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 *
 */
public class ServerMain {

    public static void main(String[] args) throws Exception {
        final NioEventLoopGroup group = new NioEventLoopGroup();

        final ProxyServerProvider localProxyServerProvider = LocalProxyServerProviderLoader.load(LocalProxyServerProviderLoader.class.getResource("/conf/proxies.conf"));

        final ProxyServerProvider proxyServerProvider = new ComposedProxyServerProvider(localProxyServerProvider);
//        final ClashProxyServerProviderFactory factory = ClashProxyServerProviderFactory.create("https://sub3.smallstrawberry.com/api/v1/client/subscribe?token=1ab79cc4b202d916cdc8e375c7b03266");
//        final ProxyServerProvider remoteProxyServerProvider = factory.getProxyServerProvider(group);
//        final ProxyServerProvider proxyServerProvider = new ComposedProxyServerProvider(remoteProxyServerProvider, localProxyServerProvider);

        final RuleBasedRoutingProxyServer router = new RuleBasedRoutingProxyServer("RuleBasedRouter", proxyServerProvider);

        final URL url = ServerMain.class.getResource("/conf/default.conf");
        Map<DestinationPattern, String> rules = ClashRuleFactory.parseRules(
                url,
                ClashRuleResolver.DOMAIN,
                ClashRuleResolver.DOMAIN_SUFFIX,
                ClashRuleResolver.DOMAIN_KEYWORD,
                ClashRuleResolver.IP_CIDR,
                ClashRuleResolver.IP_CIDR_6,
                ClashRuleResolver.RULE_SET
        );
        for (Map.Entry<DestinationPattern, String> entry : rules.entrySet()) {
            System.out.println(String.format("%s -> %s", entry.getKey(), entry.getValue()));
            router.addRouting(entry.getKey(), entry.getValue());
        }

//        Forwarder forwarder = new Forwarder(proxyServerProvider, new NioEventLoopGroup(), new NioEventLoopGroup());
        // forwarder.addForwarding(3389, "TUNNEL", InetSocketAddress.createUnresolved("10.188.71.3", 3389));
        new NettyServer(8088).start(true, new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
                ch.pipeline().addLast(new PacServerHandler(toPac(rules.keySet())));
            }
        }).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future) throws Exception {
                if (future.isSuccess()) {
                    final InetSocketAddress localAddress = (InetSocketAddress) future.channel().localAddress();
                    System.out.println(String.format("PacServer started on %s:%s", localAddress.getHostString(), localAddress.getPort()));
                } else {
                    future.cause().printStackTrace();
                }
            }
        });

        final NettyServer server = new NettyServer(1080);
        server.start(true, new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
                ch.pipeline().addLast(new RoutingSocks5ServerHandler(router));
            }
        }).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future) throws Exception {
                if (future.isSuccess()) {
                    final InetSocketAddress localAddress = (InetSocketAddress) future.channel().localAddress();
                    System.out.println(String.format("ProxyServer started on %s:%s", localAddress.getHostString(), localAddress.getPort()));
                } else {
                    future.cause().printStackTrace();
                }
            }
        }).sync().channel().closeFuture().sync();

    }

    private static String toPac(final Set<DestinationPattern> patterns) {
        final StringBuilder buff = new StringBuilder();
        buff.append("function FindProxyForURL(url, host) {\r\n");
        buff.append("  ").append("var $PROXY = 'SOCKS 127.0.0.1:1080';\r\n");
        for (DestinationPattern destinationPattern : patterns) {
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
