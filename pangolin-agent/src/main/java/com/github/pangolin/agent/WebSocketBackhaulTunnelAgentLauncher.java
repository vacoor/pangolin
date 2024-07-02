package com.github.pangolin.agent;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;

public class WebSocketBackhaulTunnelAgentLauncher {
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile WebSocketBackhaulTunnelAgent2 agent;

    public void launchIfNecessary(final String name, final String uri) throws IOException, InterruptedException {
        if (null == uri || uri.isEmpty()) {
            if (null != agent) {
                agent.shutdownGracefully();
            }
            return;
        }

        if (null != agent && !URI.create(uri).equals(agent.getWebSocketServerEndpoint())) {
            agent.shutdownGracefully();
        }

        if (running.compareAndSet(false, true)) {
            agent = new WebSocketBackhaulTunnelAgent2(name, URI.create(uri));
            try {

                agent.start().channel().closeFuture().addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(final ChannelFuture channelFuture) throws Exception {
                        running.compareAndSet(true, false);
                        agent.shutdownGracefully();
                    }
                });
            } catch (final IOException e) {
                running.compareAndSet(true, false);
                throw e;
            } catch (final InterruptedException e) {
                running.compareAndSet(true, false);
                throw e;
            }
        }

    }

}