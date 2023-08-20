package com.github.pangolin.client.v2;

import io.netty.channel.*;
import com.github.pangolin.client.v2.codec.WebSocketBackhaulRequest;

public class WebSocketBackhaulHandler extends SimpleChannelInboundHandler<WebSocketBackhaulRequest> {
    private final String name;

    WebSocketBackhaulHandler(final String name) {
        this.name = name;
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext requestCtx, final WebSocketBackhaulRequest request) throws Exception {
    }

    @Override
    public void handlerAdded(final ChannelHandlerContext requestCtx) {
        /*
        final ChannelPipeline cp = webSocketContext.pipeline();
        if (null == cp.get(WebSocketTunnelHandshakeHandler.class)) {
            final WebSocketTunnelHandshakeHandler handler = new WebSocketTunnelHandshakeHandler(name);
            cp.addBefore(webSocketContext.name(), WebSocketTunnelHandshakeHandler.class.getName(), handler);
        }
        */
    }

}