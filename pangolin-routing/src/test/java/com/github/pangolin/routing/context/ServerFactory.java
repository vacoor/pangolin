package com.github.pangolin.routing.context;

import com.github.pangolin.routing.proxy.ProxyServer;
import com.github.pangolin.routing.proxy.ServerProvider;
import com.github.pangolin.routing.proxy.spi.ServerResolver;
import com.netflix.client.config.IClientConfig;
import com.netflix.loadbalancer.*;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.stream.Collectors;

/**
 *
 */
public class ServerFactory {
    private final LoadBalancerStats stats;

    public ServerFactory(final LoadBalancerStats stats) {
        this.stats = stats;
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


    public ProxyServer createServerGroup(final String name, final String type, final ServerProvider servers) {
        if ("chain".equals(type)) {
            return new StatsProxyServer(new LazyServerChain(name, servers));
        }

        final IClientConfig config = new com.github.pangolin.routing.proxy.ServerFactory.DefaultClientConfig();
        final IRule rule = new WeightedResponseTimeRule();
        final IPing ping = new DummyPing();
        final ServerList<? extends Server> serverList = new LazyServerList<>(servers);
        final ServerListFilter<? extends Server> serverListFilter = new ZoneAffinityServerListFilter<>(config);
        final ServerListUpdater serverListUpdater = new PollingServerListUpdater(config);

        final ZoneAwareLoadBalancer<? extends Server> lb = new ZoneAwareLoadBalancer(config, rule, ping, serverList, serverListFilter, serverListUpdater);

//        lb.initWithNiwsConfig(new DefaultClientConfig());
        lb.setLoadBalancerStats(stats);
//        lb.setRule(new WeightedResponseTimeRule());
//        lb.setServersList();
        return new StatsProxyServer(new LoadBalancingProxyServer(name, lb));
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
        public ChannelHandler newProxyHandler(final InetSocketAddress sa) {
            ServerStats serverStats = stats.getSingleServerStat(this);
            ChannelHandler h = delegate.newProxyHandler(sa);
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

    private class LazyServerChain implements ProxyServer {
        private final String name;
        private final ServerProvider chain;

        private LazyServerChain(final String name, final ServerProvider chain) {
            this.name = name;
            this.chain = chain;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public ChannelHandler newProxyHandler(final InetSocketAddress sa) {
            final List<ChannelHandler> handlers = chain.getServers()
                    .stream()
                    .map(s -> s.newProxyHandler(sa))
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
            return (List) servers.getServers();
        }
    }

    public ProxyServer resolve(final String name, final String url) {
        return new StatsProxyServer(doResolve(name, url));
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

    @Slf4j
    public static class LoadBalancingProxyServer implements ProxyServer {
        private final String name;
        private final ILoadBalancer lb;

        public LoadBalancingProxyServer(final String name, final ILoadBalancer lb) {
            this.name = name;
            this.lb = lb;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public ChannelHandler newProxyHandler(final InetSocketAddress sa) {
            final ProxyServer server = choose(sa);
            if (null != server) {
                log.info("Choose {} -> {}", server.getName(), sa);
                return newProxyHandler(server, sa);
            }
            return null;
        }

        protected ChannelHandler newProxyHandler(final ProxyServer server, final InetSocketAddress sa) {
            return server.newProxyHandler(sa);
        }

        protected ProxyServer choose(final InetSocketAddress hint) {
            final Server server = getLoadBalancer().chooseServer(hint);
            return null != server ? unwrapServer(server) : null;
        }

        protected ProxyServer unwrapServer(final Server server) {
            return ((StatsProxyServer) server).delegate;
        }

        private ILoadBalancer getLoadBalancer() {
            return lb;
        }
    }
}
