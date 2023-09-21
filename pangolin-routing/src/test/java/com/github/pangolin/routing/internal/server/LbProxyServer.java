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
import io.netty.handler.codec.http.*;
import io.netty.resolver.NoopAddressResolverGroup;
import io.netty.util.concurrent.*;
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
//        for (ProxyServer aliveServer : aliveServers) {
//            servers.put(aliveServer.name(), aliveServer);
//        }
        this.aliveServers = aliveServers;
//        updateAlive();
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
                        HeathCheckPromise p = new HeathCheckPromise();
                        p.addListener(new GenericFutureListener<Future<? super ProxyServer>>() {
                            @Override
                            public void operationComplete(final Future<? super ProxyServer> future) throws Exception {
                                if (future.isSuccess()) {
                                    System.out.println("OK: " + entry.getValue());
                                } else {
                                    System.out.println("FAIL: " + entry.getValue());
                                }
                            }
                        });
                        checkAlive(g, entry.getValue(), p);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }, 0, TimeUnit.SECONDS);
        }
    }

    public static class HeathCheckPromise extends DefaultPromise<ProxyServer> {

        @Override
        protected EventExecutor executor() {
            EventExecutor executor = super.executor();
            return null != executor ? executor : GlobalEventExecutor.INSTANCE;
        }
    }

    public static void checkAlive(final EventLoopGroup g, final ProxyServer s, Promise<ProxyServer> promise) {
        final ChannelHandler transport = s.newProxyHandler();
        final Bootstrap b = new Bootstrap();
        b.option(ChannelOption.AUTO_READ, true);
        b.option(ChannelOption.TCP_NODELAY, true);
        b.option(ChannelOption.SO_KEEPALIVE, false);
        b.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000);
        b.resolver(NoopAddressResolverGroup.INSTANCE);
        b.group(g).channel(NioSocketChannel.class).handler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel channel) throws Exception {
                channel.pipeline().addLast(transport);
                channel.pipeline().addLast(new HttpClientCodec());
                channel.pipeline().addLast(new HttpObjectAggregator(1024));
                channel.pipeline().addLast(new SimpleChannelInboundHandler<HttpResponse>() {
                    @Override
                    protected void channelRead0(final ChannelHandlerContext channelHandlerContext, final HttpResponse httpResponse) throws Exception {
                        log.info("{} Response: {}", s, httpResponse.status());
                        if (HttpResponseStatus.NO_CONTENT.equals(httpResponse.status())) {
                            promise.trySuccess(s);
                        } else {
                            promise.tryFailure(new IllegalStateException("ResponseCode: " + httpResponse.status()));
                        }
                        channelHandlerContext.close();
                    }

                    @Override
                    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) throws Exception {
                        log.info("{} Connect exception: {}", s, cause.getMessage());
                        promise.tryFailure(cause);
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
        httpRequest.headers().set(HttpHeaderNames.ACCEPT, "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7");
        httpRequest.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        httpRequest.headers().set(HttpHeaderNames.ACCEPT_LANGUAGE, "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7");
        httpRequest.headers().set("Upgrade-Insecure-Requests", "1");
        httpRequest.headers().set(HttpHeaderNames.USER_AGENT, "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36");
        b.connect(host, port).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future) throws Exception {
                if (future.isSuccess()) {
                    log.info("{} Connected", s);
                    future.channel().eventLoop().execute(new Runnable() {
                        @Override
                        public void run() {
                            future.channel().writeAndFlush(httpRequest);
                        }
                    });
                } else {
                    log.info("{} Connect failed", s);
                }
            }
        }).channel().closeFuture().addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture channelFuture) throws Exception {
                log.info("{} Closed", s);
            }
        });
    }

}