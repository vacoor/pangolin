package com.github.pangolin.client.spring.boot.autoconfigure;

import com.github.pangolin.agent.WebSocketBridgeAgent;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Configuration
// @ServletComponentScan(basePackageClasses = {WebSocketEndpointLoaderListener.class})
public class WebSocketBridgeAgentAutoConfiguration {
    private static final String WS_SERVER_URL_PROPERTY = "spring.management.tunnel";
    private static final String SECRET_KEY_PROPERTY = "spring.management.tunnelSecretKey";

    @Bean(initMethod = "start", destroyMethod = "stop")
    public WebSocketBridgeAgentWatchdog webSocketBridgeAgentWatchdog() {
        return new WebSocketBridgeAgentWatchdog();
    }

    private static class WebSocketBridgeAgentWatchdog implements EnvironmentAware {
        private final AtomicBoolean started = new AtomicBoolean(false);
        private final ScheduledExecutorService scheduler;

        private Environment env;
        private volatile WebSocketBridgeAgent agent;

        public WebSocketBridgeAgentWatchdog() {
            this.scheduler = Executors.newSingleThreadScheduledExecutor();
        }

        private synchronized void launchIfNecessary() throws IOException, InterruptedException {
            final String name = env.getProperty("spring.application.name", UUID.randomUUID().toString());
            final String profiles = Arrays.toString(env.getActiveProfiles()).replaceAll("[\\[\\]]+", "");
            final String tunnelKey = !profiles.isEmpty() ? (name + '@' + profiles).replace(" ", "") : name;

            String secretKey = env.getProperty(SECRET_KEY_PROPERTY);
            if (null == secretKey) {
                secretKey = new StringBuilder(tunnelKey).reverse().append("^_^").append(tunnelKey).toString();
            }

            final String endpoint = env.getProperty(WS_SERVER_URL_PROPERTY);
            final String wsServerUrlToUse = null != endpoint ? endpoint + "/" + tunnelKey : null;
            this.launchIfNecessary(tunnelKey, secretKey, wsServerUrlToUse);
        }

        private void launchIfNecessary(final String name, final String secretKey, final String uri) throws IOException, InterruptedException {
            if (null == uri || uri.isEmpty()) {
                if (null != agent) {
                    agent.shutdownGracefully();
                }
                return;
            }

            final URI endpoint = URI.create(uri);
            if (null != agent && !endpoint.equals(agent.getWebSocketServerEndpoint())) {
                agent.shutdownGracefully();
                agent = new WebSocketBridgeAgent(name, secretKey, endpoint).start();
            } else if (null == agent) {
                agent = new WebSocketBridgeAgent(name, secretKey, endpoint).start();
            }
        }

        public void start() {
            if (started.compareAndSet(false, true)) {
                scheduler.scheduleWithFixedDelay(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            launchIfNecessary();
                        } catch (final Exception e) {
                            e.printStackTrace();
                            // ignore
                        }
                    }
                }, 15, 15, TimeUnit.SECONDS);
            }
        }

        public void stop() {
            if (started.compareAndSet(true, false)) {
                scheduler.shutdown();
                if (null != agent) {
                    agent.shutdownGracefully();
                }
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void setEnvironment(Environment environment) {
            this.env = environment;
        }

    }

}
