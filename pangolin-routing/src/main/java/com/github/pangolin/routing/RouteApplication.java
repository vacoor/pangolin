package com.github.pangolin.routing;

import com.github.pangolin.routing.context.InMemoryRouteContext;
import com.github.pangolin.routing.context.RouteContext;
import com.github.pangolin.routing.context.RouteContextFactory;
import com.github.pangolin.routing.handler.internal.server.support.SocketChannelFactory;
import com.github.pangolin.routing.route.RoutePredicateFactoriesAware;
import com.github.pangolin.routing.route.RouteRegistry;
import com.github.pangolin.routing.route.predicate.RoutePredicateFactory;
import com.github.pangolin.routing.route.predicate.RoutePredicateSetFactory;
import com.github.pangolin.routing.server.Acceptor;
import com.github.pangolin.routing.server.AcceptorProvider;
import com.github.pangolin.routing.server.Forwarder;
import com.github.pangolin.routing.server.extra.ProxyAutoConfigurationServerHandler;
import com.github.pangolin.routing.server.extra.SwitchyRuleConfigurationServerHandler;
import com.github.pangolin.routing.server.fakedns.DnsEngine;
import com.github.pangolin.routing.server.tun.beta.channel.TunAddress;
import com.github.pangolin.routing.server.tun.beta.channel.TunChannel;
import com.github.pangolin.routing.server.tun.beta.handler.IpPacketCodec;
import com.github.pangolin.routing.server.tun.beta.handler.TcpPacketHandler;
import com.github.pangolin.routing.server.tun.beta.handler.TcpPacketHandler2;
import com.github.pangolin.routing.stats.StatsAware;
import com.github.pangolin.routing.stats.StatsUpstreamCombiner;
import com.github.pangolin.routing.stats.StatsUpstreamFactory;
import com.github.pangolin.routing.upstream.*;
import com.github.pangolin.server.NettyServer;
import com.google.common.base.Function;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.netflix.loadbalancer.LoadBalancerStats;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
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

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

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

        final SocketChannelFactory factory = context.newSocketChannelFactory();
        final Forwarder forwarder = new Forwarder(factory, new NioEventLoopGroup(), new NioEventLoopGroup());
//        forwarder.addForwarding(2222, InetSocketAddress.createUnresolved("127.0.0.1", 22));

        if (args.length > 0 && "tun".equalsIgnoreCase(args[0])) {
            final DnsEngine dnsEngine = context.attr(DnsEngine.class.getName());
            final String ifname = args.length > 1 ? args[1] : "utun8";
            EventLoopGroup group = new DefaultEventLoopGroup(1);
            final Bootstrap b = new Bootstrap()
                    .group(group)
                    .channel(TunChannel.class)
                    .handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(final Channel ch) throws Exception {
                            ch.pipeline().addLast(new IpPacketCodec());
                            ch.pipeline().addLast(new TcpPacketHandler2(dnsEngine, factory));
                        }
                    });
            final Channel ch = b.bind(new TunAddress(ifname)).addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(final ChannelFuture future) throws Exception {
                    if (future.isSuccess()) {
                        log.info("Tun device started: {}", ifname);
                        log.info("sudo route add -net 198.18.0.0/15 198.18.0.1");
                        log.info("networksetup -setdnsservers \"Wi-Fi\" 127.0.0.1");
                        log.info("sudo killall -HUP mDNSResponder;");
                    }
                }
            }).sync().channel();
        }

        /*
        for (Route<InetSocketAddress> route : context.routes()) {
            for (RoutePredicate predicate : route.getPredicates()) {
                if (predicate instanceof SubnetRoutePredicate) {
                    SubnetRoutePredicate p = (SubnetRoutePredicate) predicate;
                    String s = p.getNetworkAddress().getHostAddress() + "/" + p.getCidrPrefix();
                    System.out.println(String.format("sudo route add -net %s 198.18.0.1", s));
                }
            }
        }
        */
        app.await();
//        LinuxTunAdapter.main(args);
//        DarwinTunAdapter.main(args);
    }
}
