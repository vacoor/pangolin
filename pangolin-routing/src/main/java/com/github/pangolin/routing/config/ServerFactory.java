package com.github.pangolin.routing.config;

import com.github.pangolin.routing.upstream.AbstractUpstreamServer;
import com.github.pangolin.routing.upstream.UpstreamServer;
import com.github.pangolin.routing.upstream.UpstreamServerProvider;
import com.github.pangolin.routing.upstream.UpstreamServerFactory;
import com.google.common.collect.Lists;
import com.netflix.client.config.IClientConfig;
import com.netflix.client.config.PropertyResolver;
import com.netflix.client.config.ReloadableClientConfig;
import com.netflix.loadbalancer.DummyPing;
import com.netflix.loadbalancer.ILoadBalancer;
import com.netflix.loadbalancer.IPing;
import com.netflix.loadbalancer.IRule;
import com.netflix.loadbalancer.LoadBalancerStats;
import com.netflix.loadbalancer.PollingServerListUpdater;
import com.netflix.loadbalancer.Server;
import com.netflix.loadbalancer.ServerList;
import com.netflix.loadbalancer.ServerListFilter;
import com.netflix.loadbalancer.ServerListUpdater;
import com.netflix.loadbalancer.ServerStats;
import com.netflix.loadbalancer.WeightedResponseTimeRule;
import com.netflix.loadbalancer.ZoneAffinityServerListFilter;
import com.netflix.loadbalancer.ZoneAwareLoadBalancer;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 *
 */
public class ServerFactory {
    private final LoadBalancerStats stats;

    public ServerFactory(final LoadBalancerStats stats) {
        this.stats = stats;
    }

    public UpstreamServer create(final String name, final String url) {
        return wrapIfNecessary(doResolve(name, url));
    }

    private static UpstreamServer doResolve(final String name, final String url) {
        final ServiceLoader<UpstreamServerFactory> factories = ServiceLoader.load(UpstreamServerFactory.class);
        for (final UpstreamServerFactory factory : factories) {
            if (!factory.accept(url)) {
                continue;
            }
            final Properties props = new Properties();
            if (null != name) {
                props.setProperty("name", name);
            }
            final UpstreamServer resolved = factory.create(url, props);
            if (null != resolved) {
                return resolved;
            }
        }
        throw new IllegalStateException("NOT found provider, url: " + url);
    }

    public UpstreamServer createServerGroup(final String name, final String type, final UpstreamServerProvider servers) {
        if ("chain".equals(type)) {
            return wrapIfNecessary(new LazyServerChain(name, servers));
        }

        final IClientConfig config = new DefaultClientConfig();
        final IRule rule = new WeightedResponseTimeRule();
        final IPing ping = new DummyPing();
        final ServerList<? extends Server> serverList = new LazyServerList<>(servers);
        final ServerListFilter<? extends Server> serverListFilter = new ZoneAffinityServerListFilter<>(config);
        final ServerListUpdater serverListUpdater = new PollingServerListUpdater(config);

        final ZoneAwareLoadBalancer<? extends Server> lb = new ZoneAwareLoadBalancer(config, rule, ping, serverList, serverListFilter, serverListUpdater);
        lb.setLoadBalancerStats(stats);
        return wrapIfNecessary(new LoadBalancingUpstreamServer(name, lb));
    }

    private UpstreamServer wrapIfNecessary(final UpstreamServer server) {
        return server instanceof StatsUpstreamServer ? server : new StatsUpstreamServer(server);
    }

    static class LazyUpstreamServerProvider implements UpstreamServerProvider {
        private final List<String> names;
        private final UpstreamServerProvider registry;

        LazyUpstreamServerProvider(final List<String> names, final UpstreamServerProvider registry) {
            this.names = names;
            this.registry = registry;
        }

        @Override
        public Collection<String> names() {
            return names;
        }

        @Override
        public UpstreamServer getServer(final String name) {
            return names.contains(name) ? registry.getServer(name) : null;
        }

        @Override
        public List<UpstreamServer> getServers() {
            return names.stream()
                    .map(registry::getServer)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }
    }

    private class StatsUpstreamServer extends Server implements UpstreamServer {
        private final UpstreamServer delegate;

        private StatsUpstreamServer(final UpstreamServer delegate) {
            super(delegate.getName());
            this.delegate = delegate;
        }

        @Override
        public String getName() {
            return delegate.getName();
        }

        @Override
        public ChannelHandler newDatagramProxyHandler(final InetSocketAddress destination) {
            // FIXME
            return delegate.newDatagramProxyHandler(destination);
        }

        @Override
        public ChannelHandler newSocketProxyHandler(final InetSocketAddress destination) {
            ServerStats serverStats = stats.getSingleServerStat(this);
            ChannelHandler h = delegate.newSocketProxyHandler(destination);
            if (null == h) {
                return null;
            }
            return new ChannelDuplexHandler() {
                private volatile long requestTime;

                @Override
                public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
                    ctx.pipeline().addBefore(ctx.name(), null, h);
                }

                @Override
                public void channelActive(final ChannelHandlerContext ctx) throws Exception {
                    super.channelActive(ctx);
                    serverStats.incrementOpenConnectionsCount();
                    serverStats.clearSuccessiveConnectionFailureCount();
                }

                @Override
                public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
                    super.channelInactive(ctx);
                    serverStats.decrementOpenConnectionsCount();
                }

                @Override
                public void flush(final ChannelHandlerContext ctx) throws Exception {
                    super.flush(ctx);
                    serverStats.incrementActiveRequestsCount();
                    requestTime = System.nanoTime();
                }


                @Override
                public void channelReadComplete(final ChannelHandlerContext ctx) throws Exception {
                    super.channelReadComplete(ctx);
                    serverStats.decrementActiveRequestsCount();
                    serverStats.noteResponseTime(System.nanoTime() - requestTime / 1000D);
                }
            };
        }
    }

    private class LazyServerChain extends AbstractUpstreamServer {
        private final UpstreamServerProvider chain;

        private LazyServerChain(final String name, final UpstreamServerProvider chain) {
            super(name);
            this.chain = chain;
        }

        @Override
        public ChannelHandler newSocketProxyHandler(final InetSocketAddress destination) {
            final List<ChannelHandler> handlers = chain.getServers()
                    .stream()
                    .map(s -> s.newSocketProxyHandler(destination))
                    .collect(Collectors.toList());
            if (handlers.isEmpty()) {
                return null;
            }
            return new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(final SocketChannel ch) {
                    ch.pipeline().addLast(handlers.toArray(new ChannelHandler[0]));
                }
            };
        }
    }

    @Slf4j
    public static class LoadBalancingUpstreamServer extends AbstractUpstreamServer {
        private final ILoadBalancer lb;

        public LoadBalancingUpstreamServer(final String name, final ILoadBalancer lb) {
            super(name);
            this.lb = lb;
        }

        @Override
        public ChannelHandler newDatagramProxyHandler(final InetSocketAddress destination) {
            final UpstreamServer server = choose(destination);
            if (null != server) {
                log.info("Choose {} -> {}", server.getName(), destination);
                return server.newDatagramProxyHandler(destination);
            }
            return null;
        }

        @Override
        public ChannelHandler newSocketProxyHandler(final InetSocketAddress destination) {
            final UpstreamServer server = choose(destination);
            if (null != server) {
                log.info("Choose {} -> {}", server.getName(), destination);
                return newProxyHandler(server, destination);
            }
            return null;
        }

        protected ChannelHandler newProxyHandler(final UpstreamServer server, final InetSocketAddress sa) {
            return server.newSocketProxyHandler(sa);
        }

        protected UpstreamServer choose(final InetSocketAddress hint) {
            return (StatsUpstreamServer) getLoadBalancer().chooseServer(hint);
        }

        private ILoadBalancer getLoadBalancer() {
            return lb;
        }
    }

    private class LazyServerList<T extends Server & UpstreamServer> implements ServerList<T> {
        private final UpstreamServerProvider servers;

        private LazyServerList(final UpstreamServerProvider servers) {
            this.servers = servers;
        }

        @Override
        public List<T> getInitialListOfServers() {
            return getUpdatedListOfServers();
        }

        @Override
        public List<T> getUpdatedListOfServers() {
            return (List) Lists.newArrayList(this.servers.getServers());
        }
    }


    public static class DefaultClientConfig extends ReloadableClientConfig {

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
