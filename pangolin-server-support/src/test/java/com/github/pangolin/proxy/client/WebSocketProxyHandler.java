package com.github.pangolin.proxy.client;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.proxy.ProxyHandler;

import java.net.SocketAddress;

/**
 * TODO DOC ME!.
 *
 * @author changhe.yang
 * @since 20230905
 */
public class WebSocketProxyHandler extends ProxyHandler {

    protected WebSocketProxyHandler(final SocketAddress proxyAddress) {
        super(proxyAddress);
    }

    @Override
    public String protocol() {
        return "ws";
    }

    @Override
    public String authScheme() {
        return "basic";
    }

    @Override
    protected void addCodec(final ChannelHandlerContext channelHandlerContext) throws Exception {

    }

    @Override
    protected void removeEncoder(final ChannelHandlerContext channelHandlerContext) throws Exception {

    }

    @Override
    protected void removeDecoder(final ChannelHandlerContext channelHandlerContext) throws Exception {

    }

    @Override
    protected Object newInitialMessage(final ChannelHandlerContext channelHandlerContext) throws Exception {
        return null;
    }

    @Override
    protected boolean handleResponse(final ChannelHandlerContext channelHandlerContext, final Object o) throws Exception {
        return false;
    }
}
