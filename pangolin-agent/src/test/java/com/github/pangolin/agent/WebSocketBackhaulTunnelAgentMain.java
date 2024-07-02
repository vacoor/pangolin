package com.github.pangolin.agent;

import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public class WebSocketBackhaulTunnelAgentMain {

    public static void main(String[] args) throws Exception {
        final String name = System.getProperty("agent.name", "Local");
        final String uriStr = System.getProperty("agent.server", "ws://127.0.0.1:2345/tunnel");
//        final URI uri = URI.create(uriStr);

        final WebSocketBackhaulTunnelAgentLauncher launcher = new WebSocketBackhaulTunnelAgentLauncher();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    launcher.launchIfNecessary(name, uriStr);
                } catch (final Exception e) {
                    e.printStackTrace();
                    // ignore
                }
            }
        }, 10, 10, TimeUnit.SECONDS);
    }

}