package com.github.pangolin.routing.upstream.spi;

import com.github.pangolin.routing.stats.StatsAware;
import com.github.pangolin.routing.stats.StatsUpstream;
import com.github.pangolin.routing.upstream.Upstream;
import com.github.pangolin.routing.upstream.UpstreamRegistry;
import com.github.pangolin.routing.upstream.AbstractUpstream;
import com.github.pangolin.routing.upstream.UpstreamCombiner;
import com.netflix.client.config.IClientConfig;
import com.netflix.client.config.PropertyResolver;
import com.netflix.client.config.ReloadableClientConfig;
import com.netflix.loadbalancer.*;
import io.netty.channel.ChannelHandler;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Slf4j
public class UpstreamSelectFactory implements UpstreamCombiner, StatsAware {
    private LoadBalancerStats stats;

    @Override
    public String name() {
        return "select";
    }

    @Override
    public Upstream combine(final String name, final Iterable<String> names, final UpstreamRegistry registry) {
        final IClientConfig config = new DefaultClientConfig();
        final IRule rule = new WeightedResponseTimeRule();
        final IPing ping = new DummyPing();
        final ServerList<? extends Server> serverList = new Servers(registry, names);
        final ServerListFilter<? extends Server> serverListFilter = new ZoneAffinityServerListFilter<>(config);
        final ServerListUpdater serverListUpdater = new PollingServerListUpdater(config);

        final ZoneAwareLoadBalancer<? extends Server> lb = new ZoneAwareLoadBalancer(config, rule, ping, serverList, serverListFilter, serverListUpdater);
        lb.setLoadBalancerStats(stats);

        return new AbstractUpstream(name) {

            @Override
            public SocketAddress address() {
                return null;
            }

            @Override
            public boolean isVirtual() {
                return true;
            }

            @Override
            public ChannelHandler newSocketProxyHandler(final InetSocketAddress destination) {
                final Upstream upstream = (Upstream) lb.chooseServer();
                if (null != upstream) {
                    log.info("[{}/TCP] -> [{}] -> {}", name, upstream.name(), destination);
                }
                return null != upstream ? upstream.newSocketProxyHandler(destination) : null;
            }

            @Override
            public ChannelHandler newDatagramProxyHandler(final InetSocketAddress destination) {
                final Upstream upstream = (Upstream) lb.chooseServer();
                if (null != upstream) {
                    log.info("[{}/UDP] -> [{}] -> {}", name, upstream.name(), destination);
                }
                return null != upstream ? upstream.newDatagramProxyHandler(destination) : null;
            }

            @Override
            public String toString() {
                return name + "/select," + names;
            }
        };
    }

    @Override
    public void setStats(final LoadBalancerStats stats) {
        this.stats = stats;
    }

    private class Servers implements ServerList<StatsUpstream> {
        private final UpstreamRegistry registry;
        private final Iterable<String> names;

        private Servers(final UpstreamRegistry registry, final Iterable<String> names) {
            this.registry = registry;
            this.names = names;
        }

        @Override
        public List<StatsUpstream> getInitialListOfServers() {
            return getUpdatedListOfServers();
        }

        @Override
        public List<StatsUpstream> getUpdatedListOfServers() {
            // FIXME 为什么为null
            return StreamSupport.stream(names.spliterator(), false).map(name -> (StatsUpstream) registry.getUpstream(name)).filter(Objects::nonNull).collect(Collectors.toList());
        }
    }

    private class DefaultClientConfig extends ReloadableClientConfig {

        public DefaultClientConfig() {
            super(new PropertyResolver() {
                @Override
                public <T> Optional<T> get(final String s, final Class<T> aClass) {
                    return Optional.empty();
                }

                @Override
                public void forEach(final String s, final BiConsumer<String, String> biConsumer) {

                }

                @Override
                public void onChange(final Runnable runnable) {

                }
            });
        }

        @Override
        public void loadDefaultValues() {

        }

        @Override
        public String resolveDeploymentContextbasedVipAddresses() {
            return null;
        }
    }
}
