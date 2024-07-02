package com.github.pangolin.agent;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.net.URI;

@SpringBootApplication
public class WebSocketBackhaulTunnelAgentApplication {

    public static void main(String[] args) throws Exception {
        SpringApplication.run(WebSocketBackhaulTunnelAgentApplication.class, args);
        /*
        final String agentName = System.getProperty("agent.name");
        final String agentServer = System.getProperty("agent.server");

        final URI uri = URI.create(agentServer);
        final WebSocketBackhaulTunnelAgent client = new WebSocketBackhaulTunnelAgent(agentName, uri);
        client.start().closeFuture().addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture channelFuture) throws Exception {
                client.shutdownGracefully();
            }
        }).sync();
        System.out.println("Over");
        */
    }

}