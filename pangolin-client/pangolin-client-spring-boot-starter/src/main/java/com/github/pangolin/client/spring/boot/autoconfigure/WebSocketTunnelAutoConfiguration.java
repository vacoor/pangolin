package com.github.pangolin.client.spring.boot.autoconfigure;

import com.github.pangolin.client.WebSocketTunnelClient;
import com.github.pangolin.client.servlet.WebSocketTunnelServlet;
import org.springframework.boot.web.servlet.ServletComponentScan;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 *
 */
@Configuration
@ServletComponentScan(basePackageClasses = {WebSocketTunnelServlet.class})
public class WebSocketTunnelAutoConfiguration implements EnvironmentAware {
    private static final String WS_SERVER_URL_PROPERTY = "spring.management.tunnel";

    private Environment env;
    private final AtomicReference<WebSocketTunnelClient> clientRef = new AtomicReference<>(null);

    @Bean(destroyMethod = "stop")
    public DebugTunnelInitializer tunnel() {
        return new DebugTunnelInitializer().start();
    }

    public class DebugTunnelInitializer {
        private final ScheduledExecutorService scheduler;

        public DebugTunnelInitializer() {
            this.scheduler = Executors.newSingleThreadScheduledExecutor();
        }

        public DebugTunnelInitializer start() {
            scheduler.scheduleWithFixedDelay(new Runnable() {
                @Override
                public void run() {
                    try {
                        launchTunnelClientIfNecessary();
                    } catch (final Exception e) {
                        // ignore
                    }
                }
            }, 60, 60, TimeUnit.SECONDS);
            return this;
        }

        public void stop() {
            scheduler.shutdownNow();
        }
    }

    private void launchTunnelClientIfNecessary() throws IOException, InterruptedException {
        final String wsServerUrlToUse = env.getProperty(WS_SERVER_URL_PROPERTY);
        final String name = ManagementFactory.getRuntimeMXBean().getName();
        final String address = env.getProperty("spring.cloud.client.ip-address", UUID.randomUUID().toString());
        final String profiles = Arrays.toString(env.getActiveProfiles()).replaceAll("[\\[\\]]+", "");
        final String wsTunnelInstanceName = (name + '@' + profiles + '@' + address).replace(" ", "");
        if (StringUtils.hasText(wsServerUrlToUse)) {
            final URI wsServerUri = URI.create(wsServerUrlToUse);
            final WebSocketTunnelClient webSocketTunnelClient = clientRef.get();
            if (null == webSocketTunnelClient || !webSocketTunnelClient.isRunning() || !wsServerUri.equals(webSocketTunnelClient.getServerEndpoint())) {
                if (null != webSocketTunnelClient) {
                    webSocketTunnelClient.shutdownGracefully();
                }
                final WebSocketTunnelClient webSocketTunnelClientNew = new WebSocketTunnelClient(wsTunnelInstanceName, wsServerUri);
                if (clientRef.compareAndSet(webSocketTunnelClient, webSocketTunnelClientNew)) {
                    webSocketTunnelClientNew.start();
                }
            }
        }
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.env = environment;
    }

}
