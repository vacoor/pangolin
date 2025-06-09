package com.github.pangolin.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class WebSocketBridgeAgentApplication {

    public static void main(String[] args) throws Exception {
        SpringApplication.run(WebSocketBridgeAgentApplication.class, args);
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