package com.github.pangolin.routing.upstream.spi;

import com.github.pangolin.routing.support.ProxySocketChannelFactory;
import com.github.pangolin.routing.support.handler.client.TrojanDatagramProxyHandler;
import com.github.pangolin.routing.support.handler.client.TrojanProxyHandler;
import com.github.pangolin.routing.support.SocketChannelFactory;
import com.github.pangolin.routing.support.StandardSocketChannelFactory;
import com.github.pangolin.routing.upstream.Upstream;
import com.github.pangolin.routing.upstream.AbstractUpstream;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.*;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Collections;

/**
 * Trojan Proxy Server resolver.
 * <p>
 * URL format: trojan://{password}@{hostname}:{port}?{parameters}#{name}
 */
public class TrojanUpstreamFactory extends AbstractUpstreamFactory {
    private static final int DEFAULT_TROJAN_PORT = 443;

    public TrojanUpstreamFactory() {
        super(new String[]{"trojan://"});
    }

    @Override
    protected Upstream apply0(final String name, final String url) {
        // FIXME
        return resolve(name, url, new StandardSocketChannelFactory(null));
    }

    private Upstream resolve(final String name, final String serverUrl, final SocketChannelFactory factory) {
        /*-
         * URL format: trojan://{password}@{hostname}:{port}?{parameters}#{name}
         */
        final URI uri = URI.create(serverUrl);
        final String nameToUse = null != name ? name : uri.getFragment();
        final String password = uri.getUserInfo();

        final InetSocketAddress address = toSocketAddress(
                uri.getHost(),
                determinePort(uri.getPort())
        );

        final SocketChannelFactory factoryToUse = null != factory ? factory : new StandardSocketChannelFactory(null);
        return new TrojanUpstream(nameToUse, address, password, factoryToUse);
    }

    private int determinePort(final int port) {
        return port > 0 ? port : DEFAULT_TROJAN_PORT;
    }

    private static class TrojanUpstream extends AbstractUpstream {
        private final InetSocketAddress address;
        private final String password;
        private final SocketChannelFactory socketChannelFactory;

        TrojanUpstream(final String name, final InetSocketAddress address, final String password, final SocketChannelFactory socketChannelFactory) {
            super(name);
            this.address = address;
            this.password = password;
            this.socketChannelFactory = socketChannelFactory;
        }

        @Override
        public SocketAddress address() {
            return address;
        }

        @Override
        public boolean isVirtual() {
            return false;
        }

        @Override
        public boolean isAvailable() {
            return !address.isUnresolved();
        }

        @Override
        public ChannelHandler newSocketProxyHandler(InetSocketAddress destination) {
            return new TrojanProxyHandler(address, password);
        }

        @Override
        public ChannelHandler newDatagramProxyHandler(final InetSocketAddress destination) {
            // FIXME
            return new TrojanDatagramProxyHandler(address, password, socketChannelFactory);
        }

        @Override
        public String toString() {
            return name + "/" + address;
        }
    }

    public static void main(String[] args) throws InterruptedException {
        TrojanUpstream upstream = new TrojanUpstream(
                "xx",
                new InetSocketAddress("fbnode-all.6pzfwf.com", 56240),
                "04abed89-4d1a-340a-82a6-137cd70ca289",
                new StandardSocketChannelFactory(null)
        );

        final ProxySocketChannelFactory factory = new ProxySocketChannelFactory(upstream, Collections.emptyList(), null);
        ChannelFuture open = factory.open(
                InetSocketAddress.createUnresolved("youtube.com", 443),
                10 * 1000,
                true,
                new NioEventLoopGroup(),
                new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel sc) throws Exception {
                        sc.pipeline().addLast(new HttpClientCodec());
                        sc.pipeline().addLast(new HttpObjectAggregator(8 * 1024 * 1024));
                        sc.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                super.channelRead(ctx, msg);
                            }
                        });
                    }

                }
        );
        open.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                if (future.isSuccess()) {
                    future.channel().writeAndFlush(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));
                }
            }
        });
        open.sync().channel().closeFuture().sync();
    }
}
