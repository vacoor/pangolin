package com.github.pangolin.server;

import com.github.pangolin.handler.SocketOverWebSocketDecodeHandler;
import com.github.pangolin.handler.SocketOverWebSocketEncodeHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.socksx.v5.*;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class WebSocketBackhaulAgentSock5ProxyServerHandler extends Socks5ProxyServerHandler {
    private WebSocketBackhaulProxyServer server;
    private String agentKey;

    public WebSocketBackhaulAgentSock5ProxyServerHandler(final EventLoopGroup proxyGroup, final WebSocketBackhaulProxyServer server, final String agent) {
        super(proxyGroup);
        this.server = server;
        this.agentKey = agent;
    }

    // open tunnel


    @Override
    protected void connect(final ChannelHandlerContext accessLink, final Socks5CommandRequest request, final EventLoopGroup proxyGroup) throws Exception {
        final int port = request.dstPort();
        final String address = request.dstAddr();
        Socks5AddressType addressType = request.dstAddrType();
        final String target = "tcp://" + address + ":" + port;
//        accessLink.channel().config().setAutoRead(false);
        server.tunnelRequested(accessLink.channel().id().toString(), accessLink, agentKey, target).addListener(new FutureListener<ChannelHandlerContext>() {

            @Override
            public void operationComplete(final Future<ChannelHandlerContext> backhaulFuture) throws Exception {
                if (backhaulFuture.isSuccess()) {
                    final ChannelHandlerContext backhaulLink = backhaulFuture.getNow();
                    backhaulLink.channel().config().setAutoRead(false);

                    accessLink.pipeline().replace(accessLink.name(), null, new SocketOverWebSocketEncodeHandler(backhaulLink));
                    backhaulLink.pipeline().replace(backhaulLink.name(), null, new SocketOverWebSocketDecodeHandler(accessLink));

                    accessLink.channel().config().setAutoRead(true);
                    backhaulLink.channel().config().setAutoRead(true);

                    if (log.isDebugEnabled()) {
                        log.debug("{} Native tunnel open success: {}", accessLink.channel(), backhaulLink.channel());
                    }
                    log.info("Connection to {}:{}: Connected", address, port);
                    accessLink.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, addressType)).addListener(g -> accessLink.pipeline().remove(Socks5ServerEncoder.DEFAULT));
                } else {
                    log.warn("Failed to Connect to {}:{}: {}", address, port, backhaulFuture.cause());
                    accessLink.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.HOST_UNREACHABLE, addressType)).addListener(ChannelFutureListener.CLOSE);
                }
            }

        });
    }
}