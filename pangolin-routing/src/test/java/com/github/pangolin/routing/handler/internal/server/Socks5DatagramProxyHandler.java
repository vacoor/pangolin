package com.github.pangolin.routing.handler.internal.server;

import com.github.pangolin.routing.handler.codec.socks5.Socks5DatagramPacketCodec;
import com.github.pangolin.routing.handler.internal.server.support.SocketChannelFactory;
import com.github.pangolin.routing.handler.internal.server.support.StandardSocketChannelFactory;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.socksx.v5.Socks5CommandResponse;
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 */
public class Socks5DatagramProxyHandler extends ChannelDuplexHandler {
    private final InetSocketAddress proxyAddress;
    private final SocketChannelFactory socketChannelFactory;

    public Socks5DatagramProxyHandler(final InetSocketAddress proxyAddress) {
        this(proxyAddress, new StandardSocketChannelFactory());
    }

    public Socks5DatagramProxyHandler(final InetSocketAddress proxyAddress, final SocketChannelFactory socketChannelFactory) {
        this.proxyAddress = proxyAddress;
        this.socketChannelFactory = socketChannelFactory;
    }

    @Override
    public void bind(final ChannelHandlerContext udpCtx, final SocketAddress localAddress, final ChannelPromise promise) throws Exception {
//        super.bind(ctx, localAddress, promise);
        socketChannelFactory.open(
                proxyAddress,
                udpCtx.channel().config().getConnectTimeoutMillis(),
                true,
                udpCtx.channel().eventLoop(),
                new Socks5ProxyHandler2(proxyAddress) {
                    @Override
                    protected boolean handshakeRead(final ChannelHandlerContext tcpCtx, final Object msg) throws Exception {
                        if (msg instanceof Socks5CommandResponse) {
                            final Socks5CommandResponse socks5CommandResponse = (Socks5CommandResponse) msg;
                            if (Socks5CommandStatus.SUCCESS.equals(socks5CommandResponse.status())) {
                                final String udpServerAddr = socks5CommandResponse.bndAddr();
                                final int udpServerPort = socks5CommandResponse.bndPort();
                                promise.addListener(new GenericFutureListener<Future<? super Void>>() {
                                    @Override
                                    public void operationComplete(final Future<? super Void> future) throws Exception {
                                        if (future.isSuccess()) {
                                            // FIXME 直接移动到当前类处理.
                                            udpCtx.pipeline().addBefore(udpCtx.name(), null, new Socks5DatagramPacketCodec(new InetSocketAddress(udpServerAddr, udpServerPort)));
                                        }
                                    }
                                });
                            }
                        }
                        return super.handshakeRead(tcpCtx, msg);
                    }
                }
        ).addListener(new GenericFutureListener<Future<? super Void>>() {
            @Override
            public void operationComplete(final Future<? super Void> future) throws Exception {
                if (!future.isSuccess()) {
                    promise.tryFailure(future.cause());
                } else {
                    Socks5DatagramProxyHandler.super.bind(udpCtx, localAddress, promise);
                }
            }
        });
    }
}
