package com.github.pangolin.server.v11;

import com.github.pangolin.handler.SocketOverWebSocketDecodeHandler;
import com.github.pangolin.handler.SocketOverWebSocketEncodeHandler;
import com.github.pangolin.proxy.server.socks.v5.Socks5ProxyServerHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.socksx.v5.*;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;

@Slf4j
class WebSocketBackhaulTunnelSock5ServerHandler extends Socks5ProxyServerHandler {
    private WebSocketBackhaulTunnelEngine webSocketBackhaulTunnelEngine;
    private String agentKey;

    public WebSocketBackhaulTunnelSock5ServerHandler(final WebSocketBackhaulTunnelEngine webSocketBackhaulTunnelEngine, final String agent) {
        this.webSocketBackhaulTunnelEngine = webSocketBackhaulTunnelEngine;
        this.agentKey = agent;
    }

    @Override
    protected void connect(final ChannelHandlerContext accessCtx, final Socks5CommandRequest request) throws Exception {
        final int port = request.dstPort();
        final String address = request.dstAddr();
        final Socks5AddressType addressType = request.dstAddrType();
        final String target = "tcp://" + address + ":" + port;

        accessCtx.channel().config().setAutoRead(false);

        final String id = accessCtx.channel().id().toString();
        webSocketBackhaulTunnelEngine.tunnelRequested(id, agentKey, URI.create(target), accessCtx).addListener(new FutureListener<ChannelHandlerContext>() {

            @Override
            public void operationComplete(final Future<ChannelHandlerContext> backhaulFuture) throws Exception {
                if (backhaulFuture.isSuccess()) {
                    log.info("Connection established: {}:{}", address, port);
                    final ChannelHandlerContext backhaulCtx = backhaulFuture.getNow();
                    backhaulCtx.channel().config().setAutoRead(false);

                    accessCtx.pipeline().replace(accessCtx.name(), null, new SocketOverWebSocketEncodeHandler(backhaulCtx));
                    backhaulCtx.pipeline().replace(backhaulCtx.name(), null, new SocketOverWebSocketDecodeHandler(accessCtx));

                    accessCtx.channel().config().setAutoRead(true);
                    backhaulCtx.channel().config().setAutoRead(true);

                    accessCtx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, addressType)).addListener(g -> accessCtx.pipeline().remove(Socks5ServerEncoder.DEFAULT));
                } else {
                    log.warn("Failed to Connect to {}:{}: {}", address, port, backhaulFuture.cause().getMessage(), backhaulFuture.cause());
                    accessCtx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.HOST_UNREACHABLE, addressType)).addListener(ChannelFutureListener.CLOSE);
                }
            }

        });
    }
}