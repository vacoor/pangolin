package com.github.pangolin.routing.proxy;

import com.netflix.client.config.IClientConfig;
import com.netflix.loadbalancer.DummyPing;
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

import java.net.InetSocketAddress;
import java.util.List;
import java.util.stream.Collectors;

/**
 *
 */
public class LazyServerFactory {
    private final LoadBalancerStats stats;

    public LazyServerFactory(final LoadBalancerStats stats) {
        this.stats = stats;
    }

    public ProxyServer createServerGroup(final String name, final String type, final ServerProvider servers) {
        if ("chain".equals(type)) {
            return new StatsProxyServer(new LazyServerChain(name, servers));
        }

        final IClientConfig config = new ServerFactory.DefaultClientConfig();
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
        return new StatsProxyServer(new ServerFactory.LoadBalancingProxyServer(name, lb));
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
            return (List<T>) servers.getServers();
        }
    }
}
