package com.github.pangolin.routing;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import com.github.pangolin.routing.config.ConfigurationException;
import com.github.pangolin.routing.config.DefaultServerReader;
import com.github.pangolin.routing.config.Ini;
import com.github.pangolin.routing.config.RefreshableUpstreamServerRegistry;
import com.github.pangolin.routing.handler.extra.ProxyAutoConfigurationServerHandler;
import com.github.pangolin.routing.handler.extra.SwitchyRuleConfigurationServerHandler;
import com.github.pangolin.routing.handler.internal.server.HttpProxyServerHandler;
import com.github.pangolin.routing.handler.internal.server.Socks4ProxyServerHandler;
import com.github.pangolin.routing.handler.internal.server.Socks5DatagramServerHandler;
import com.github.pangolin.routing.handler.internal.server.Socks5ProxyServerHandler;
import com.github.pangolin.routing.handler.internal.server.support.DatagramChannelFactory;
import com.github.pangolin.routing.handler.internal.server.support.SocketChannelFactory;
import com.github.pangolin.routing.handler.mixin.MixinServerHandshaker;
import com.github.pangolin.routing.handler.mixin.MixinServerInitializer;
import com.github.pangolin.routing.handler.mixin.support.HttpMixinServerHandshaker;
import com.github.pangolin.routing.handler.mixin.support.Socks4MixinServerHandshaker;
import com.github.pangolin.routing.handler.mixin.support.Socks5MixinServerHandshaker;
import com.github.pangolin.routing.rule.RuleBasedUpstreamServer;
import com.github.pangolin.routing.upstream.AbstractServer;
import com.github.pangolin.routing.upstream.ProxyDatagramChannelFactory;
import com.github.pangolin.routing.upstream.UpstreamServer;
import com.github.pangolin.routing.upstream.ProxySocketChannelFactory;
import com.github.pangolin.server.NettyServer;
import com.google.common.collect.Lists;
import com.netflix.loadbalancer.LoadBalancerStats;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.system.ApplicationHome;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 *
 */
@Slf4j
public class ServerMain {

    private static MixinServerHandshaker createHandshaker(final String type,
                                                          final SocketChannelFactory socketChannelFactory,
                                                          final Socks5DatagramServerHandler h) {
        if ("SOCKS5".equalsIgnoreCase(type)) {
            return Socks5MixinServerHandshaker.of(new Socks5ProxyServerHandler(null, null, socketChannelFactory, h));
        } else if ("SOCKS4".equalsIgnoreCase(type)) {
            return Socks4MixinServerHandshaker.of(new Socks4ProxyServerHandler(null, socketChannelFactory));
        } else if ("HTTP".equalsIgnoreCase(type)){
            return HttpMixinServerHandshaker.of(new HttpProxyServerHandler(null, null, socketChannelFactory));
        }
        throw new IllegalStateException(String.format("ILLEGAL_TYPE: %s", type));
    }

    public static void main(String[] args) throws Exception, ConfigurationException {
        final ApplicationHome home = new ApplicationHome(ServerMain.class);
        final File conf = new File(home.getDir(), "conf/default.conf");
        final URL url = conf.toURI().toURL();

        final Ini ini = new Ini();
        ini.load(url.openStream());

        Ini.Section listen = ini.getSection("Listen");
        if (null == listen) {
            listen = ini.addSection("Listen");
            listen.put("1080", "DEFAULT,SOCKS5,SOCKS4,HTTP");
        }

        final LoadBalancerStats stats = new LoadBalancerStats();
        final RefreshableUpstreamServerRegistry config = new RefreshableUpstreamServerRegistry(new DefaultServerReader(stats), url).refresh();

        final List<String> bypass = Arrays.asList("::1", "127.0.0.1", "localhost");
        final RuleBasedUpstreamServer defaultProxy = new RuleBasedUpstreamServer("ROUTING-PROXY", config, config);

//        final SmartProxySocketChannelFactory factory = new SmartProxySocketChannelFactory(modedRulesProvider, proxyServerProvider, bypass);
//        final StandardSocketChannelFactory factory = new StandardSocketChannelFactory();

//        Forwarder forwarder = new Forwarder(factory, new NioEventLoopGroup(), new NioEventLoopGroup());
        // forwarder.addForwarding(3389, "TUNNEL", InetSocketAddress.createUnresolved("10.188.71.3", 3389));

        final List<ChannelFuture> futures = Lists.newLinkedList();
        int defaultPort = 0;
        for (final Map.Entry<String, String> entry : listen.entrySet()) {
            final int listenPort = Integer.parseInt(entry.getKey());
            final String value = entry.getValue();
            final String[] segments = value.split("\\s*,\\s*");
            final String proxy = segments[0];
            defaultPort = "DEFAULT".equals(proxy) ? listenPort : defaultPort;
            final UpstreamServer upstreamServer = "DEFAULT".equals(proxy) ? defaultProxy : new AbstractServer(proxy) {
                @Override
                public ChannelHandler newSocketProxyHandler(final InetSocketAddress sa) {
                    UpstreamServer server = config.getServer(proxy);
                    return null != server ? server.newSocketProxyHandler(sa) : null;
                }

                @Override
                public ChannelHandler newDatagramProxyHandler(final InetSocketAddress destination) {
                    UpstreamServer server = config.getServer(proxy);
                    return null != server ? server.newDatagramProxyHandler(destination) : null;
                }
            };

            final SocketChannelFactory socketChannelFactory = new ProxySocketChannelFactory(upstreamServer, bypass);
            final DatagramChannelFactory datagramChannelFactory = new ProxyDatagramChannelFactory(upstreamServer, bypass);

            final NettyServer server = new NettyServer(listenPort);
            final List<String> protocols = Arrays.asList(segments).subList(1, segments.length);
            Socks5DatagramServerHandler h = null;
            if (protocols.contains("SOCKS5")) {
                h = new Socks5DatagramServerHandler(datagramChannelFactory);
                Socks5DatagramServerHandler udpServerHandler = h;
                final Bootstrap udpBootstrap = new Bootstrap();
                udpBootstrap.group(new NioEventLoopGroup());
                udpBootstrap.channel(NioDatagramChannel.class);
                udpBootstrap.option(ChannelOption.SO_BROADCAST, false);
                udpBootstrap.handler(new ChannelInitializer<DatagramChannel>() {
                    @Override
                    protected void initChannel(final DatagramChannel ch) throws Exception {
                        ch.pipeline().addLast(udpServerHandler);
                    }
                });
                udpBootstrap.bind(listenPort);
            }

            final Socks5DatagramServerHandler socks5UdpServerHandler = h;
            ChannelFuture f = server.start(true, new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(final SocketChannel channel) throws Exception {
                    channel.pipeline().addLast(new MixinServerInitializer(protocols
                            .stream()
                            .map(type -> createHandshaker(type, socketChannelFactory, socks5UdpServerHandler)).toArray(MixinServerHandshaker[]::new)));
                }
            }).addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(final ChannelFuture future) throws Exception {
                    if (future.isSuccess()) {
                        final InetSocketAddress localAddress = (InetSocketAddress) future.channel().localAddress();
                        log.info("Mixed upstream {} started on port {} ({})", proxy, localAddress.getPort(), localAddress);
                    } else {
                        future.cause().printStackTrace();
                    }
                }
            });
            futures.add(f);
        }


        final int proxyPort = defaultPort;
        ChannelFuture pacChannel = new NettyServer(9080).start(true, new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
                ch.pipeline().addLast(
                        new SwitchyRuleConfigurationServerHandler(config),
                        new ProxyAutoConfigurationServerHandler(config, proxyPort)
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

        for (ChannelFuture future : futures) {
            future.sync().channel().closeFuture().sync();
        }
        pacChannel.channel().closeFuture().sync();
    }

}
