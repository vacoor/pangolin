package com.github.pangolin.client;

import io.netty.channel.*;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

class WebSocketTunnelHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
    private final String name;

    WebSocketTunnelHandler(final String name) {
        this.name = name;
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext webSocketContext, final WebSocketFrame frame) throws Exception {
    }

    @Override
    public void handlerAdded(final ChannelHandlerContext webSocketContext) {
        /*
        final ChannelPipeline cp = webSocketContext.pipeline();
        if (null == cp.get(WebSocketTunnelHandshakeHandler.class)) {
            final WebSocketTunnelHandshakeHandler handler = new WebSocketTunnelHandshakeHandler(name);
            cp.addBefore(webSocketContext.name(), WebSocketTunnelHandshakeHandler.class.getName(), handler);
        }
        */
    }

}