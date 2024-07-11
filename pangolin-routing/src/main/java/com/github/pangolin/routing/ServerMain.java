package com.github.pangolin.routing;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import com.github.pangolin.routing.config.ConfigurationException;
import com.github.pangolin.routing.config.DefaultServerReader;
import com.github.pangolin.routing.config.RefreshableServerRegistry;
import com.github.pangolin.routing.handler.extra.ProxyAutoConfigurationServerHandler;
import com.github.pangolin.routing.handler.extra.SwitchyRuleConfigurationServerHandler;
import com.github.pangolin.routing.handler.internal.server.HttpProxyServerHandler;
import com.github.pangolin.routing.handler.internal.server.Socks4ProxyServerHandler;
import com.github.pangolin.routing.handler.internal.server.Socks5ProxyServerHandler;
import com.github.pangolin.routing.handler.internal.server.support.SocketChannelFactory;
import com.github.pangolin.routing.handler.mixin.MixinServerInitializer;
import com.github.pangolin.routing.handler.mixin.support.HttpMixinServerHandshaker;
import com.github.pangolin.routing.handler.mixin.support.Socks4MixinServerHandshaker;
import com.github.pangolin.routing.handler.mixin.support.Socks5MixinServerHandshaker;
import com.github.pangolin.routing.proxy.ProxySocketChannelFactory;
import com.github.pangolin.routing.proxy.RuleBasedProxyServer;
import com.github.pangolin.server.NettyServer;
import com.netflix.loadbalancer.LoadBalancerStats;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.system.ApplicationHome;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.Arrays;
import java.util.List;

/**
 *
 */
@Slf4j
public class ServerMain {

    public static void main(String[] args) throws Exception, ConfigurationException {
        final ApplicationHome home = new ApplicationHome(ServerMain.class);
        final File conf = new File(home.getDir(), "conf/default.conf");
        final URL url = conf.toURI().toURL();

        final LoadBalancerStats stats = new LoadBalancerStats();
        final RefreshableServerRegistry config = new RefreshableServerRegistry(new DefaultServerReader(stats), url).refresh();

        final List<String> bypass = Arrays.asList("::1", "127.0.0.1", "localhost");
        final RuleBasedProxyServer ruleProxy = new RuleBasedProxyServer("ROUTING-PROXY", config, config);
        final SocketChannelFactory factory = new ProxySocketChannelFactory(ruleProxy, bypass);

//        final SmartProxySocketChannelFactory factory = new SmartProxySocketChannelFactory(modedRulesProvider, proxyServerProvider, bypass);
//        final StandardSocketChannelFactory factory = new StandardSocketChannelFactory();

//        Forwarder forwarder = new Forwarder(factory, new NioEventLoopGroup(), new NioEventLoopGroup());
        // forwarder.addForwarding(3389, "TUNNEL", InetSocketAddress.createUnresolved("10.188.71.3", 3389));


        final String hostStr = System.getProperty("server.host");
        final String portStr = System.getProperty("server.port", "1082");
        final int proxyServerPort = Integer.parseInt(portStr);
        final NettyServer server = new NettyServer(hostStr, proxyServerPort);
        ChannelFuture proxyServerChannel = server.start(true, new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
                final Socks5MixinServerHandshaker socks5Handshaker = Socks5MixinServerHandshaker.of(new Socks5ProxyServerHandler(null, null, factory));
                final Socks4MixinServerHandshaker socks4Handshaker = Socks4MixinServerHandshaker.of(new Socks4ProxyServerHandler(null, factory));
                final HttpMixinServerHandshaker httpHandshaker = HttpMixinServerHandshaker.of(
                        new ProxyAutoConfigurationServerHandler(config, proxyServerPort),
                        new SwitchyRuleConfigurationServerHandler(config),
                        new HttpProxyServerHandler(null, null, factory)
                );
                ch.pipeline().addLast(new MixinServerInitializer(socks5Handshaker, socks4Handshaker, httpHandshaker));
            }
        });

        ChannelFuture pacChannel = new NettyServer(hostStr, 8098).start(true, new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
                ch.pipeline().addLast(
                        new SwitchyRuleConfigurationServerHandler(config),
                        new ProxyAutoConfigurationServerHandler(config, proxyServerPort)
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
