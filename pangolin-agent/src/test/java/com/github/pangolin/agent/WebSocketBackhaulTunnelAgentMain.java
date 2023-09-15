package com.github.pangolin.agent;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;

import java.net.URI;

public class WebSocketBackhaulTunnelAgentMain {

    public static void main(String[] args) throws Exception {
        final String name = "BZ";
         final URI uri = URI.create("ws://116.225.101.248:2345/tunnel");
//        final URI uri = URI.create("ws://127.0.0.1:2345/tunnel");
        final WebSocketBackhaulTunnelAgent client = new WebSocketBackhaulTunnelAgent(name, uri);
        client.start().closeFuture().addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture channelFuture) throws Exception {
                client.shutdownGracefully();
            }
        }).sync();
        System.out.println("Over");
    }

}