package com.github.pangolin.routing;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import com.github.pangolin.routing.context.InMemoryRouteContext;
import com.github.pangolin.routing.context.RouteContext;
import com.github.pangolin.routing.context.RouteContextFactory;
import com.github.pangolin.routing.route.RoutePredicateFactoriesAware;
import com.github.pangolin.routing.route.RouteRegistry;
import com.github.pangolin.routing.route.predicate.RoutePredicateFactory;
import com.github.pangolin.routing.route.predicate.RoutePredicateSetFactory;
import com.github.pangolin.routing.server.acceptor.Acceptor;
import com.github.pangolin.routing.server.acceptor.AcceptorProvider;
import com.github.pangolin.routing.server.extra.ProxyAutoConfigurationServerHandler;
import com.github.pangolin.routing.server.extra.SwitchyRuleConfigurationServerHandler;
import com.github.pangolin.routing.server.fakedns.FakeDnsAcceptorFactory;
import com.github.pangolin.routing.server.tun.TunAcceptorFactory;
import com.github.pangolin.routing.upstream.stats.StatsAware;
import com.github.pangolin.routing.upstream.stats.StatsUpstreamCombiner;
import com.github.pangolin.routing.upstream.stats.StatsUpstreamFactory;
import com.github.pangolin.routing.upstream.DirectUpstream;
import com.github.pangolin.routing.upstream.DropUpstream;
import com.github.pangolin.routing.upstream.RejectUpstream;
import com.github.pangolin.routing.upstream.Upstream;
import com.github.pangolin.routing.upstream.UpstreamCombiner;
import com.github.pangolin.routing.upstream.UpstreamCombinersAware;
import com.github.pangolin.routing.upstream.UpstreamFactoriesAware;
import com.github.pangolin.routing.upstream.UpstreamFactory;
import com.github.pangolin.server.NettyServer;
import com.google.common.base.Function;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.netflix.loadbalancer.LoadBalancerStats;
import com.sun.jna.Platform;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.concurrent.GlobalEventExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.system.ApplicationHome;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

@Slf4j
public class RouteApplication {
    protected final LoadBalancerStats stats = new LoadBalancerStats();
    protected final Iterable<UpstreamFactory> upstreamFactories;
    protected final Map<String, UpstreamCombiner> upstreamCombiners = Maps.newLinkedHashMap();
    protected final Map<String, RoutePredicateFactory> predicateFactories = Maps.newLinkedHashMap();

    //    private final List<Acceptor> acceptors = Lists.newLinkedList();
    protected final ChannelGroup channelGroup = new DefaultChannelGroup("acceptor-channels", GlobalEventExecutor.INSTANCE);


    public RouteApplication() {
        this(
                ServiceLoader.load(UpstreamFactory.class),
                ServiceLoader.load(UpstreamCombiner.class),
                ServiceLoader.load(RoutePredicateFactory.class)
        );
    }

    public RouteApplication(final Iterable<UpstreamFactory> upstreamFactories,
                            final Iterable<UpstreamCombiner> upstreamCombiners,
                            final Iterable<RoutePredicateFactory> predicateFactories) {
        this.upstreamFactories = this.initUpstreamFactories(upstreamFactories);
        this.initUpstreamCombiners(upstreamCombiners);
        this.initPredicateFactories(predicateFactories);
    }

    private Iterable<UpstreamFactory> initUpstreamFactories(final Iterable<UpstreamFactory> factories) {
        return Iterables.transform(factories, new Function<UpstreamFactory, UpstreamFactory>() {
            @Override
            public UpstreamFactory apply(final UpstreamFactory upstreamFactory) {
                return new StatsUpstreamFactory(upstreamFactory, stats);
            }
        });
    }

    private void initUpstreamCombiners(final Iterable<UpstreamCombiner> factories) {
        for (final UpstreamCombiner factory : factories) {
            final String key = factory.name();
            if (upstreamCombiners.containsKey(key)) {
                log.warn("A UpstreamCombiner named " + key + " already exists, class: " + upstreamCombiners.get(key) + ". It will be overwritten.");
            }
            if (factory instanceof StatsAware) {
                ((StatsAware) factory).setStats(stats);
            }
            upstreamCombiners.put(key, new StatsUpstreamCombiner(factory, stats));
            log.info("Loaded UpstreamCombiner [" + key + "]");
        }
    }

    private void initPredicateFactories(final Iterable<RoutePredicateFactory> factories) {
        for (final RoutePredicateFactory factory : factories) {
            final String key = factory.name();
            if (predicateFactories.containsKey(key)) {
                log.warn("A RoutePredicateFactory named " + key + " already exists, class: " + predicateFactories.get(key) + ". It will be overwritten.");
            }
            predicateFactories.put(key, factory);
            log.info("Loaded RoutePredicateFactory [" + key + "]");
        }
        predicateFactories.put("RULE-SET", new RoutePredicateSetFactory("RULE-SET", predicateFactories));
    }

    public RouteContext run(final URL configLocation) throws Exception {
        final RouteContext context = createParentContext(configLocation);
        final InMemoryRouteContext contextToUse = new InMemoryRouteContext(context);
        final Upstream[] embeddedUpstreams = new Upstream[]{
                new DirectUpstream(),
                new DropUpstream(),
                new RejectUpstream()
        };
        for (final Upstream embeddedUpstream : embeddedUpstreams) {
            contextToUse.addUpstream(embeddedUpstream.name(), embeddedUpstream);
        }

        int port = 0;
        for (RouteContext ctx = contextToUse; null != ctx; ctx = ctx.parent()) {
            if (!(ctx instanceof AcceptorProvider)) {
                continue;
            }
            final List<Acceptor> acceptors = ((AcceptorProvider) ctx).getAcceptors();
            for (final Acceptor acceptor : acceptors) {
                final ChannelFuture start = acceptor.start(contextToUse);
                final Channel channel = start.channel();
                channelGroup.add(channel);
                start.sync();
                final SocketAddress bound = channel.localAddress();

                port = ((InetSocketAddress) bound).getPort();

                log.info("bound to {}", bound);
            }
        }

        channelGroup.add(createRouteExporterAcceptor(port).start(contextToUse).channel());

        return contextToUse;
    }


    public void await() throws InterruptedException {
        channelGroup.newCloseFuture().sync();
    }


    protected RouteContext createParentContext(final URL configLocation) throws Exception {
        final ServiceLoader<RouteContextFactory> factories = ServiceLoader.load(RouteContextFactory.class);
        RouteContext context = null;
        for (final RouteContextFactory factory : factories) {
            if (factory instanceof UpstreamFactoriesAware) {
                ((UpstreamFactoriesAware) factory).setUpstreamFactories(upstreamFactories);
            }
            if (factory instanceof UpstreamCombinersAware) {
                ((UpstreamCombinersAware) factory).setUpstreamCombiners(upstreamCombiners);
            }
            if (factory instanceof RoutePredicateFactoriesAware) {
                ((RoutePredicateFactoriesAware) factory).setRoutePredicateFactories(predicateFactories);
            }

            context = factory.createContext(configLocation, context);
        }

        return context;
    }

    private Acceptor createRouteExporterAcceptor(final int proxyPort) {
        return (new Acceptor() {
            @Override
            public ChannelFuture start(final RouteContext context) throws Exception {
                return new NettyServer(9080).start(true, new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(final SocketChannel ch) throws Exception {
                        ch.pipeline().addLast(
                                new SwitchyRuleConfigurationServerHandler((RouteRegistry) context),
                                new ProxyAutoConfigurationServerHandler((RouteRegistry) context, proxyPort)
                        );
                        ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
                                ctx.writeAndFlush(new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.NOT_FOUND)).addListener(ChannelFutureListener.CLOSE);
                            }
                        });
                    }
                }).addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(final ChannelFuture future) throws Exception {
                        if (future.isSuccess()) {
                            final InetSocketAddress localAddress = (InetSocketAddress) future.channel().localAddress();
                            log.info("Web interface started on port: {} ({})", localAddress.getPort(), localAddress);
                        } else {
                            future.cause().printStackTrace();
                        }
                    }
                });
            }
        });
    }

    public static void main(String[] args) throws Exception {
        final ApplicationHome home = new ApplicationHome(RouteApplication.class);
        final URL conf = new File(home.getDir(), "conf/default.conf").toURI().toURL();
        final RouteApplication app = new RouteApplication();
        final RouteContext context = app.run(conf);

        app.channelGroup.add(new FakeDnsAcceptorFactory().apply(0, "FakeDNS").start(context).channel());

        final Set<String> bypass = Sets.newTreeSet();
        for (Upstream upstream : context.upstreams()) {
            final SocketAddress address = upstream.address();
            if (!upstream.isVirtual() && address instanceof InetSocketAddress) {
                final InetSocketAddress addr = (InetSocketAddress) address;
                if (!addr.isUnresolved()) {
                    final String hostAddress = addr.getAddress().getHostAddress();
//                    System.out.println(upstream.name() + " -> " + addr);
                    bypass.add(hostAddress);
                }
            }
        }

        System.out.println("Bypass = " + bypass);

        if (args.length > 0 && "tun".equalsIgnoreCase(args[0])) {
            final String defName = Platform.isMac() ? "utun8" : (Platform.isLinux() ? "tun8" : "以太网 P");
            final String ifname = args.length > 1 ? args[1] : defName;
            app.channelGroup.add(new TunAcceptorFactory().apply(0, ifname).start(context).addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(final ChannelFuture future) throws Exception {
                    if (future.isSuccess()) {
                        log.info("TUN adapter started on: {}", ifname);
                    } else {
                        log.error("Tun adapter bound error: {}", future.cause().getMessage(), future.cause());
                    }
                }
            }).channel());
        }

        app.await();
//        LinuxTunAdapter.main(args);
//        DarwinTunAdapter.main(args);
    }
}
