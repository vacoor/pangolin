package com.github.pangolin.routing;

import com.github.pangolin.routing.acceptor.Acceptor;
import com.github.pangolin.routing.acceptor.AcceptorProvider;
import com.github.pangolin.routing.acceptor.extra.RuleExporterAcceptor;
import com.github.pangolin.routing.acceptor.tun.adapter.NetworkRoutingTable;
import com.github.pangolin.routing.context.InheritableRouteContext;
import com.github.pangolin.routing.context.RouteContext;
import com.github.pangolin.routing.context.RouteContextFactory;
import com.github.pangolin.routing.route.RoutePredicateFactoriesAware;
import com.github.pangolin.routing.route.RouteRegistry;
import com.github.pangolin.routing.route.RouteUpstream;
import com.github.pangolin.routing.route.predicate.RoutePredicateFactory;
import com.github.pangolin.routing.route.predicate.RoutePredicateSetFactory;
import com.github.pangolin.routing.upstream.*;
import com.google.common.collect.Maps;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
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

@Slf4j
public class Application {
    protected final Iterable<UpstreamFactory> upstreamFactories;
    protected final Map<String, UpstreamCombiner> upstreamCombiners = Maps.newLinkedHashMap();
    protected final Map<String, RoutePredicateFactory> predicateFactories = Maps.newLinkedHashMap();

    //    private final List<Acceptor> acceptors = Lists.newLinkedList();
    protected final ChannelGroup channelGroup = new DefaultChannelGroup("acceptor-channels", GlobalEventExecutor.INSTANCE);


    public Application() {
        this(
                ServiceLoader.load(UpstreamFactory.class),
                ServiceLoader.load(UpstreamCombiner.class),
                ServiceLoader.load(RoutePredicateFactory.class)
        );
    }

    public Application(final Iterable<UpstreamFactory> upstreamFactories,
                       final Iterable<UpstreamCombiner> upstreamCombiners,
                       final Iterable<RoutePredicateFactory> predicateFactories) {
        this.upstreamFactories = upstreamFactories;
        this.initUpstreamCombiners(upstreamCombiners);
        this.initPredicateFactories(predicateFactories);
    }

    private void initUpstreamCombiners(final Iterable<UpstreamCombiner> factories) {
        for (final UpstreamCombiner factory : factories) {
            final String key = factory.name();
            if (upstreamCombiners.containsKey(key)) {
                log.warn("A UpstreamCombiner named " + key + " already exists, class: " + upstreamCombiners.get(key) + ". It will be overwritten.");
            }
            upstreamCombiners.put(key, factory);
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
        log.info("Initializing context...");
        final InheritableRouteContext root = new InheritableRouteContext(null, null);
        final Upstream[] embeddedUpstreams = new Upstream[]{
                DirectUpstream.INSTANCE,
                DropUpstream.INSTANCE,
                RejectUpstream.INSTANCE
        };
        for (final Upstream embeddedUpstream : embeddedUpstreams) {
            root.addUpstream(embeddedUpstream.name(), embeddedUpstream);
        }

        final RouteContext context = createParentContext(configLocation, root);
        final RouteContext contextToUse = context;//new InheritableRouteContext(configLocation, context);
        root.addUpstream(RouteUpstream.NAME, new RouteUpstream((RouteRegistry<InetSocketAddress>) contextToUse, (UpstreamRegistry) contextToUse));
        log.info("Context initialized.");

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
                if (bound instanceof InetSocketAddress) {
                    port = ((InetSocketAddress) bound).getPort();

                    log.info("bound to {}", bound);
                }
            }
        }

        channelGroup.add(createRouteExporterAcceptor(port).start(contextToUse).channel());

        return contextToUse;
    }


    public void await() throws InterruptedException {
        channelGroup.newCloseFuture().sync();
    }


    protected RouteContext createParentContext(final URL configLocation, RouteContext root) throws Exception {
        final ServiceLoader<RouteContextFactory> factories = ServiceLoader.load(RouteContextFactory.class);
        RouteContext context = root;
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

            context = factory.create(configLocation, context);
        }

        return context;
    }

    private Acceptor createRouteExporterAcceptor(final int proxyPort) {
        return new RuleExporterAcceptor(proxyPort);
    }

    public static void main(String[] args) throws Exception {
        final ApplicationHome home = new ApplicationHome(Application.class);
        final URL conf = new File(home.getDir(), "conf/default.conf").toURI().toURL();
        final Application app = new Application();
        final RouteContext context = app.run(conf);

        app.await();
    }

}
