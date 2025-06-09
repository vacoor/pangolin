package com.github.pangolin.client.spring.boot.autoconfigure;

import com.github.pangolin.agent.WebSocketBackhaulTunnelAgentLauncher;
import com.github.pangolin.agent.servlet.WebSocketEndpointLoaderListener;
import org.springframework.boot.web.servlet.ServletComponentScan;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 *
 */
@Configuration
@ServletComponentScan(basePackageClasses = {WebSocketEndpointLoaderListener.class})
public class WebSocketBackhaulTunnelAutoConfiguration implements EnvironmentAware {
    private static final String WS_SERVER_URL_PROPERTY = "spring.management.tunnel";

    private Environment env;
    private final WebSocketBackhaulTunnelAgentLauncher launcher = new WebSocketBackhaulTunnelAgentLauncher();

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
                        e.printStackTrace();
                        // ignore
                    }
                }
            }, 15, 15, TimeUnit.SECONDS);
            return this;
        }

        public void stop() {
            scheduler.shutdownNow();
        }
    }

    private void launchTunnelClientIfNecessary() throws IOException, InterruptedException {
        final String wsServerUrlToUse = env.getProperty(WS_SERVER_URL_PROPERTY);
        final String name = env.getProperty("spring.application.name", UUID.randomUUID().toString());
        final String profiles = Arrays.toString(env.getActiveProfiles()).replaceAll("[\\[\\]]+", "");
        final String wsTunnelInstanceName = !profiles.isEmpty() ? (name + '@' + profiles).replace(" ", "") : name;

        launcher.launchIfNecessary(wsTunnelInstanceName, wsServerUrlToUse);
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.env = environment;
    }

}
