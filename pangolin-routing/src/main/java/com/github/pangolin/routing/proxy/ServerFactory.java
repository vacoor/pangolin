package com.github.pangolin.routing.proxy;

import com.github.pangolin.routing.proxy.spi.ServerResolver;
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
import java.util.Arrays;
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


    public ProxyServer createServerGroup(final String name, final String type,
                                                 final String url, final List<ProxyServer> servers) {
        if ("chain".equals(type)) {
            return new StatsProxyServer(new ProxyServerChain(name, servers.toArray(new ProxyServer[0])));
        }
        final IClientConfig config = new DefaultClientConfig();
        final IRule rule = new WeightedResponseTimeRule();
        final IPing ping = new DummyPing();
        final ServerList<? extends Server> serverList = new StaticServerList<>(
                servers.stream().map(StatsProxyServer::new).collect(Collectors.toList())
        );
        final ServerListFilter<? extends Server> serverListFilter = new ZoneAffinityServerListFilter<>(config);
        final ServerListUpdater serverListUpdater = new PollingServerListUpdater(config);

        final ZoneAwareLoadBalancer<? extends Server> lb = new ZoneAwareLoadBalancer(config, rule, ping, serverList, serverListFilter, serverListUpdater);

//        lb.initWithNiwsConfig(new DefaultClientConfig());
        lb.setLoadBalancerStats(stats);
//        lb.setRule(new WeightedResponseTimeRule());
//        lb.setServersList();

        return new StatsProxyServer(new LoadBalancingProxyServer(name, lb));
    }

    private class StaticServerList<T extends Server> implements ServerList<T> {
        private final List<T> servers;

        private StaticServerList(final List<T> servers) {
            this.servers = servers;
        }

        @Override
        public List<T> getInitialListOfServers() {
            return servers;
        }

        @Override
        public List<T> getUpdatedListOfServers() {
            return servers;
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

    /**
     *
     */
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

    public static class ProxyServerChain implements ProxyServer {
        private final String name;
        private final ProxyServer[] proxyServers;

        public ProxyServerChain(final String name, final ProxyServer... proxyServers) {
            this.name = name;
            this.proxyServers = proxyServers;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public ChannelHandler newProxyHandler(final InetSocketAddress sa) {
            final List<ChannelHandler> handlers = Arrays.stream(proxyServers)
                    .map(s -> this.newProxyHandler(s, sa))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            if (!handlers.isEmpty()) {
                return new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(final SocketChannel ch) throws Exception {
                        ch.pipeline().addLast(handlers.toArray(new ChannelHandler[0]));
                    }
                };
            }
            return null;
        }

        protected ChannelHandler newProxyHandler(final ProxyServer server, final InetSocketAddress sa) {
            return server.newProxyHandler(sa);
        }
    }

    /**
     * TODO DOC ME!.
     *
     * @author changhe.yang
     * @since 20240709
     */
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
