package com.github.pangolin.routing;

import com.github.pangolin.routing.config.ProxiesParser;
import com.github.pangolin.routing.config.RulesParser;
import com.github.pangolin.routing.handler.extra.ProxyAutoConfigurationServerHandler;
import com.github.pangolin.routing.handler.extra.SwitchyRuleConfigurationServerHandler;
import com.github.pangolin.routing.handler.internal.server.HttpProxyServerHandler;
import com.github.pangolin.routing.handler.internal.server.Socks4ProxyServerHandler;
import com.github.pangolin.routing.handler.internal.server.Socks5ProxyServerHandler;
import com.github.pangolin.routing.handler.mixin.MixinServerInitializer;
import com.github.pangolin.routing.handler.mixin.support.HttpMixinServerHandshaker;
import com.github.pangolin.routing.handler.mixin.support.Socks4MixinServerHandshaker;
import com.github.pangolin.routing.handler.mixin.support.Socks5MixinServerHandshaker;
import com.github.pangolin.routing.proxy.*;
import com.github.pangolin.routing.rule.RulesProvider;
import com.github.pangolin.routing.rule.pattern.DestinationPattern;
import com.github.pangolin.server.NettyServer;
import com.google.common.collect.Lists;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.FileInputStream;
import java.net.InetSocketAddress;
import java.util.*;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 *
 */
@Slf4j
public class ServerMain {

    public static void main(String[] args) throws Exception {
        final NioEventLoopGroup group = new NioEventLoopGroup();

        final ApplicationHome home = new ApplicationHome(ServerMain.class);
        final File homeFile = home.getDir();
        final File proxiesConf = new File(homeFile, "conf/proxies.conf");
        final File rulesConf = new File(homeFile, "conf/rule.conf");

        log.info("Rules config: " + rulesConf.getAbsolutePath());
        log.info("Proxies config: " + proxiesConf.getAbsolutePath());

        ProxyServerProvider proxyServerProvider = proxiesConf.exists() ? ProxiesParser.parse(new FileInputStream(proxiesConf), group) : new ComposedProxyServerProvider();

        final Map<DestinationPattern, String> rules = rulesConf.exists() ? RulesParser.parseRules(rulesConf.toURI().toURL()) : Collections.emptyMap();

        for (Map.Entry<DestinationPattern, String> entry : rules.entrySet()) {
            log.debug(String.format("%s -> %s", entry.getKey(), entry.getValue()));
        }

        final String MODE_RULE = null;
        final String MODE_GLOBAL = "一元机场";
        final String MODE_DIRECT = "DIRECT";

        final String fixedProxy = MODE_RULE;
        final RulesProvider rulesProvider = () -> rules;
        /*-
         * 策略
         *   目标 --> 代理
         *   目标集 --> 代理
         * eg:
         * 规则模式
         *   目标 --> 代理
         *   默认 --> DIRECT
         * 全局模式
         *   目标 --> 代理
         *   默认 --> 代理
         * 直连模式
         *   默认 --> DIRECT
         */

        final RulesProvider global = () -> {
            return Collections.singletonMap((DestinationPattern) destionation -> true, fixedProxy);
        };

        final RulesProvider modedRulesProvider = () -> {
            if (StringUtils.hasText(fixedProxy)) {
                return Collections.singletonMap((DestinationPattern) destination -> true, fixedProxy);
            }
            return rulesProvider.getRules();
        };

        final List<String> bypass = Arrays.asList("::1", "127.0.0.1", "localhost");

        final ProxyServer tunnelNextHop = proxyServerProvider.getInstance("TUNNEL-NEXT-HOP");
        if (null != tunnelNextHop) {
            final ProxyServer tunnel = proxyServerProvider.getInstance("TUNNEL");
            final ProxyChainServer proxyChainServer = new ProxyChainServer("TUNNEL-DIRECT-HOP", tunnel, tunnelNextHop);
            final ProxyServerProvider origProvider = proxyServerProvider;
            proxyServerProvider = new ProxyServerProvider() {
                @Override
                public Collection<ProxyServer> getInstances() {
                    final List<ProxyServer> servers = Lists.newArrayList(origProvider.getInstances());
                    servers.add(proxyChainServer);
                    return servers;
                }

                @Override
                public ProxyServer getInstance(final String name) {
                    return proxyChainServer.getName().equals(name) ? proxyChainServer : origProvider.getInstance(name);
                }
            };
        }

        final SmartProxySocketChannelFactory factory = new SmartProxySocketChannelFactory(modedRulesProvider, proxyServerProvider, bypass);
//        final StandardSocketChannelFactory factory = new StandardSocketChannelFactory();

//        Forwarder forwarder = new Forwarder(factory, new NioEventLoopGroup(), new NioEventLoopGroup());
        // forwarder.addForwarding(3389, "TUNNEL", InetSocketAddress.createUnresolved("10.188.71.3", 3389));


        final String hostStr = System.getProperty("server.host");
        final String portStr = System.getProperty("server.port", "1081");
        final int proxyServerPort = Integer.parseInt(portStr);
        final NettyServer server = new NettyServer(hostStr, proxyServerPort);
        ChannelFuture proxyServerChannel = server.start(true, new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
                final Socks5MixinServerHandshaker socks5Handshaker = new Socks5MixinServerHandshaker(new Socks5ProxyServerHandler(null, null, factory));
                final Socks4MixinServerHandshaker socks4Handshaker = new Socks4MixinServerHandshaker(new Socks4ProxyServerHandler(null, factory));
                final HttpMixinServerHandshaker httpHandshaker = new HttpMixinServerHandshaker(
                        new ProxyAutoConfigurationServerHandler(rulesProvider, proxyServerPort),
                        new SwitchyRuleConfigurationServerHandler(rulesProvider),
                        new HttpProxyServerHandler(null, null, factory)
                );
                ch.pipeline().addLast(new MixinServerInitializer(socks5Handshaker, socks4Handshaker, httpHandshaker));
            }
        });

        ChannelFuture pacChannel = new NettyServer(hostStr, 8088).start(true, new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
                ch.pipeline().addLast(
                        new SwitchyRuleConfigurationServerHandler(rulesProvider),
                        new ProxyAutoConfigurationServerHandler(rulesProvider, proxyServerPort)
                );
                ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
                        ctx.writeAndFlush(new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.NOT_FOUND)).addListener(ChannelFutureListener.CLOSE);
                    }
                });
            }
        }).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future) throws Exception {
                if (future.isSuccess()) {
                    final InetSocketAddress localAddress = (InetSocketAddress) future.channel().localAddress();
                    log.info("Web interface started on port: {} ({})", localAddress.getPort(), localAddress);
                } else {
                    future.cause().printStackTrace();
                }
            }
        });

        proxyServerChannel.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future) throws Exception {
                if (future.isSuccess()) {
                    final InetSocketAddress localAddress = (InetSocketAddress) future.channel().localAddress();
                    log.info("Mixed proxy started on port {} ({})", localAddress.getPort(), localAddress);
                } else {
                    future.cause().printStackTrace();
                }
            }
        }).sync().channel().closeFuture().sync();
        pacChannel.channel().close();
    }

}
