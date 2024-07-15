package com.github.pangolin.routing.config;

import com.github.pangolin.routing.proxy.AbstractServer;
import com.github.pangolin.routing.proxy.ProxyServer;
import com.github.pangolin.routing.proxy.ServerProvider;
import com.github.pangolin.routing.proxy.spi.ServerResolver;
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

    public ProxyServer create(final String name, final String url) {
        return wrapIfNecessary(doResolve(name, url));
    }

    private static ProxyServer doResolve(final String name, final String url) {
        final ServiceLoader<ServerResolver> resolvers = ServiceLoader.load(ServerResolver.class);
        for (final ServerResolver resolver : resolvers) {
            if (!resolver.acceptsUrl(url)) {
                continue;
            }
            final Properties props = new Properties();
            if (null != name) {
                props.setProperty("name", name);
            }
            final ProxyServer resolved = resolver.resolve(url, props);
            if (null != resolved) {
                return resolved;
            }
        }
        throw new IllegalStateException("NOT found provider, url: " + url);
    }

    public ProxyServer createServerGroup(final String name, final String type, final ServerProvider servers) {
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
        return wrapIfNecessary(new LoadBalancingProxyServer(name, lb));
    }

    private ProxyServer wrapIfNecessary(final ProxyServer server) {
        return server instanceof StatsProxyServer ? server : new StatsProxyServer(server);
    }

    static class LazyServerProvider implements ServerProvider {
        private final List<String> names;
        private final ServerProvider registry;

        LazyServerProvider(final List<String> names, final ServerProvider registry) {
            this.names = names;
            this.registry = registry;
        }

        @Override
        public Collection<String> names() {
            return names;
        }

        @Override
        public ProxyServer getServer(final String name) {
            return names.contains(name) ? registry.getServer(name) : null;
        }

        @Override
        public List<ProxyServer> getServers() {
            return names.stream()
                    .map(registry::getServer)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }
    }

    private class StatsProxyServer extends Server implements ProxyServer {
        private final ProxyServer delegate;

        private StatsProxyServer(final ProxyServer delegate) {
            super(delegate.getName());
            this.delegate = delegate;
        }

        @Override
        public String getName() {
            return delegate.getName();
        }

        @Override
        public ChannelHandler newDatagramProxyHandler(final InetSocketAddress sa) {
            // FIXME
            return delegate.newDatagramProxyHandler(sa);
        }

        @Override
        public ChannelHandler newSocketProxyHandler(final InetSocketAddress sa) {
            ServerStats serverStats = stats.getSingleServerStat(this);
            ChannelHandler h = delegate.newSocketProxyHandler(sa);
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

    private class LazyServerChain extends AbstractServer {
        private final ServerProvider chain;

        private LazyServerChain(final String name, final ServerProvider chain) {
            super(name);
            this.chain = chain;
        }

        @Override
        public ChannelHandler newSocketProxyHandler(final InetSocketAddress sa) {
            final List<ChannelHandler> handlers = chain.getServers()
                    .stream()
                    .map(s -> s.newSocketProxyHandler(sa))
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
    public static class LoadBalancingProxyServer extends AbstractServer {
        private final ILoadBalancer lb;

        public LoadBalancingProxyServer(final String name, final ILoadBalancer lb) {
            super(name);
            this.lb = lb;
        }

        @Override
        public ChannelHandler newDatagramProxyHandler(final InetSocketAddress sa) {
            final ProxyServer server = choose(sa);
            if (null != server) {
                log.info("Choose {} -> {}", server.getName(), sa);
                return server.newDatagramProxyHandler(sa);
            }
            return null;
        }

        @Override
        public ChannelHandler newSocketProxyHandler(final InetSocketAddress sa) {
            final ProxyServer server = choose(sa);
            if (null != server) {
                log.info("Choose {} -> {}", server.getName(), sa);
                return newProxyHandler(server, sa);
            }
            return null;
        }

        protected ChannelHandler newProxyHandler(final ProxyServer server, final InetSocketAddress sa) {
            return server.newSocketProxyHandler(sa);
        }

        protected ProxyServer choose(final InetSocketAddress hint) {
            return (StatsProxyServer) getLoadBalancer().chooseServer(hint);
        }

        private ILoadBalancer getLoadBalancer() {
            return lb;
        }
    }

    private class LazyServerList<T extends Server & ProxyServer> implements ServerList<T> {
        private final ServerProvider servers;

        private LazyServerList(final ServerProvider servers) {
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
