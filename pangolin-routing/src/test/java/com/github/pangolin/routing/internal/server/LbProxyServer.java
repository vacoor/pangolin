package com.github.pangolin.routing.internal.server;

import com.github.pangolin.util.Channels;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.resolver.NoopAddressResolverGroup;
import lombok.extern.slf4j.Slf4j;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

@Slf4j
public class LbProxyServer implements ProxyServer {
    private final String name;
    private final Map<String, ProxyServer> servers = new ConcurrentHashMap<>();
    private List<ProxyServer> aliveServers;
    private List<ProxyServer> deadServers = new CopyOnWriteArrayList<>();

    public LbProxyServer(final String name, final List<ProxyServer> aliveServers) {
        this.name = name;
        for (ProxyServer aliveServer : aliveServers) {
            servers.put(aliveServer.name(), aliveServer);
        }
        updateAlive();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public ChannelHandler newProxyHandler() {
        return aliveServers.get(new Random().nextInt(aliveServers.size())).newProxyHandler();
    }

    private void updateAlive() {
        synchronized (servers) {
            aliveServers = new LinkedList<>(servers.values());
        }
    }

    final EventLoopGroup g = new NioEventLoopGroup();

    public void check() {
        for (Map.Entry<String, ProxyServer> entry : servers.entrySet()) {
            final String name = entry.getKey();
            g.schedule(new Runnable() {
                @Override
                public void run() {
                    try {
                        checkAlive(g, entry.getValue());
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }, 0, TimeUnit.SECONDS);
        }
    }

    public static void checkAlive(final EventLoopGroup g, final ProxyServer s) {
        try {
            final ChannelHandler transport = s.newProxyHandler();
            final Bootstrap b = new Bootstrap();
            b.option(ChannelOption.AUTO_READ, true);
            b.option(ChannelOption.TCP_NODELAY, true);
            b.option(ChannelOption.SO_KEEPALIVE, true);
            b.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000);
            b.resolver(NoopAddressResolverGroup.INSTANCE).group(g).channel(NioSocketChannel.class).handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(final SocketChannel channel) throws Exception {
                    channel.pipeline().addLast(transport);
                    channel.pipeline().addLast(new HttpClientCodec());
                    channel.pipeline().addLast(new HttpObjectAggregator(1024));
                    channel.pipeline().addLast(new SimpleChannelInboundHandler<HttpResponse>() {
                        @Override
                        protected void channelRead0(final ChannelHandlerContext channelHandlerContext, final HttpResponse httpResponse) throws Exception {
                            System.out.println(httpResponse.status());
                        }

                        @Override
                        public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) throws Exception {
                            System.err.println("Heath-Check3: " + s + ", " + cause.getMessage());
                            ctx.close();
                        }
                    });
                }
            });
            final URI uri = URI.create("http://www.gstatic.com/generate_204");
            String scheme = uri.getScheme() == null ? "http" : uri.getScheme();
            String host = uri.getHost() == null ? "127.0.0.1" : uri.getHost();
            int port = uri.getPort();
            if (port == -1) {
                if ("http".equalsIgnoreCase(scheme)) {
                    port = 80;
                } else if ("https".equalsIgnoreCase(scheme)) {
                    port = 443;
                }
            }
            final FullHttpRequest httpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri.getRawPath());
            httpRequest.headers().set(HttpHeaderNames.HOST, host);
            httpRequest.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            b.connect(host, port).sync().channel().writeAndFlush(httpRequest).channel().closeFuture().addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(final ChannelFuture channelFuture) throws Exception {
                    if (!channelFuture.isSuccess()) {
                        System.err.println("Heath-Check2: " + s + ", " + channelFuture.cause().getMessage());
                    }
                }
            });
        } catch (final InterruptedException e) {
            System.err.println("Heath-Check: " + s + ", " + e.getMessage());
        }
    }

}