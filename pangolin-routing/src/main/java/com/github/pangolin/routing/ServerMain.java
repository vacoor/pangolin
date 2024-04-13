package com.github.pangolin.routing;

import com.github.pangolin.routing.config.ProxiesParser;
import com.github.pangolin.routing.config.RulesParser;
import com.github.pangolin.routing.handler.ProxyAutoConfigurationServerHandler;
import com.github.pangolin.routing.handler.SwitchyRuleConfigurationServerHandler;
import com.github.pangolin.routing.handler.internal.server.HttpProxyServerHandler;
import com.github.pangolin.routing.handler.internal.server.Socks4ProxyServerHandler;
import com.github.pangolin.routing.handler.internal.server.Socks5ProxyServerHandler;
import com.github.pangolin.routing.handler.mixin.MixinServerInitializer;
import com.github.pangolin.routing.handler.mixin.support.HttpMixinServerHandshaker;
import com.github.pangolin.routing.handler.mixin.support.Socks4MixinServerHandshaker;
import com.github.pangolin.routing.handler.mixin.support.Socks5MixinServerHandshaker;
import com.github.pangolin.routing.proxy.ComposedProxyServerProvider;
import com.github.pangolin.routing.proxy.ProxyServerProvider;
import com.github.pangolin.routing.proxy.SmartProxySocketChannelFactory;
import com.github.pangolin.routing.rule.RulesProvider;
import com.github.pangolin.routing.rule.pattern.DestinationPattern;
import com.github.pangolin.server.NettyServer;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.FileInputStream;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 *
 */
public class ServerMain {

    public static void main(String[] args) throws Exception {
        final NioEventLoopGroup group = new NioEventLoopGroup();

        final ApplicationHome home = new ApplicationHome(ServerMain.class);
        final File homeFile = home.getDir();
        final File proxiesConf = new File(homeFile, "conf/proxies2.conf");
        final File rulesConf = new File(homeFile, "conf/default.conf");

        System.out.println("Proxies config: " + proxiesConf.getAbsolutePath());
        System.out.println("Rules config: " + rulesConf.getAbsolutePath());

        final ProxyServerProvider proxyServerProvider = proxiesConf.exists() ? ProxiesParser.parse(new FileInputStream(proxiesConf), group) : new ComposedProxyServerProvider();
        final Map<DestinationPattern, String> rules = rulesConf.exists() ? RulesParser.parseRules(rulesConf.toURI().toURL()) : Collections.emptyMap();

        for (Map.Entry<DestinationPattern, String> entry : rules.entrySet()) {
            System.out.println(String.format("%s -> %s", entry.getKey(), entry.getValue()));
        }

        final String MODE_RULE = null;
        final String MODE_GLOBAL = "一元机场";
        final String MODE_DIRECT = "DIRECT";

        final String fixedProxy = MODE_RULE;
        final RulesProvider rulesProvider = () -> rules;
        final RulesProvider modedRulesProvider = () -> {
            if (StringUtils.hasText(fixedProxy)) {
                return Collections.singletonMap((DestinationPattern) destination -> true, fixedProxy);
            }
            return rulesProvider.getRules();
        };

        final List<String> bypass = Arrays.asList("::1", "127.0.0.1", "localhost");
        final SmartProxySocketChannelFactory factory = new SmartProxySocketChannelFactory(modedRulesProvider, proxyServerProvider, bypass);
//        final StandardSocketChannelFactory factory = new StandardSocketChannelFactory();

        final ProxyAutoConfigurationServerHandler pacHandler = new ProxyAutoConfigurationServerHandler(rulesProvider);
        final SwitchyRuleConfigurationServerHandler switchyRuleHandler = new SwitchyRuleConfigurationServerHandler(rulesProvider);

//        Forwarder forwarder = new Forwarder(factory, new NioEventLoopGroup(), new NioEventLoopGroup());
        // forwarder.addForwarding(3389, "TUNNEL", InetSocketAddress.createUnresolved("10.188.71.3", 3389));



        new NettyServer(8088).start(true, new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
                ch.pipeline().addLast(pacHandler, switchyRuleHandler);
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
                final Socks5MixinServerHandshaker socks5Handshaker = new Socks5MixinServerHandshaker(new Socks5ProxyServerHandler(null, null, factory));
                final Socks4MixinServerHandshaker socks4Handshaker = new Socks4MixinServerHandshaker(new Socks4ProxyServerHandler(null, factory));
                final HttpMixinServerHandshaker httpHandshaker = new HttpMixinServerHandshaker(
                        new ProxyAutoConfigurationServerHandler(rulesProvider),
                        new SwitchyRuleConfigurationServerHandler(rulesProvider),
                        new HttpProxyServerHandler(null, null, factory)
                );
//                ch.pipeline().addLast(new MixinServerInitializer(httpHandshaker));
                ch.pipeline().addLast(new MixinServerInitializer(socks5Handshaker, socks4Handshaker, httpHandshaker));
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

}
