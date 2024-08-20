package com.github.pangolin.routing.beta.tun;

import com.github.pangolin.routing.RouteApplication;
import com.github.pangolin.routing.beta.tun.fakedns.DnsEngine;
import com.github.pangolin.routing.beta.tun.fakedns.FakeDnsEngine4;
import com.github.pangolin.routing.beta.tun.fakedns.handler.DatagramDnsProxyServerHandler;
import com.github.pangolin.routing.beta.tun.fakedns.handler.DatagramFakeDnsServerHandler;
import com.github.pangolin.routing.beta.tun.tun2socks.WindowsTun2Socks;
import com.github.pangolin.routing.context.RouteContext;
import com.github.pangolin.routing.route.Route;
import com.github.pangolin.routing.route.predicate.RoutePredicate;
import com.github.pangolin.routing.route.predicate.SubnetRoutePredicate;
import com.google.common.collect.Lists;
import freework.io.IOUtils;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.resolver.dns.DnsNameResolver;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.resolver.dns.SequentialDnsServerAddressStreamProvider;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import org.junit.Test;
import org.springframework.boot.system.ApplicationHome;

import java.io.File;
import java.io.FileWriter;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.Arrays;
import java.util.List;

/**
 *
 */
public class TunBasedApplication {

    @Test
    public void test2() throws Exception {
//        final Acceptor acceptor = new MixinAcceptorFactory().apply(1089);
//        acceptor.start(new InMemoryRouteContext(null)).sync().channel().closeFuture().sync();
//        RouteApplication.main(new String[0]);

        final ApplicationHome home = new ApplicationHome(TunBasedApplication.class);
        final URL conf = new File(home.getDir(), "conf/default.conf").toURI().toURL();
        final FakeDnsEngine4 fakeDns = FakeDnsEngine4.create("198.18.0.0", "255.255.0.0");
        final List<InetSocketAddress> dnsServers = Arrays.asList(new InetSocketAddress("10.188.207.9", 53));

        final RouteApplication app = new RouteApplication() {
            @Override
            protected RouteContext createParentContext(final URL configLocation) throws Exception {
                RouteContext ctx = super.createParentContext(configLocation);
                ctx.attr(DnsEngine.class.getName(), fakeDns);
                return ctx;
            }
        };
        final RouteContext context = app.run(conf);

        final EventLoopGroup loop = new NioEventLoopGroup();
//        final List<InetSocketAddress> addresses = Arrays.asList(new InetSocketAddress("192.168.1.1", 53));
        final DnsNameResolver resolver =
                new DnsNameResolverBuilder()
                        .eventLoop(loop.next())
                        .recursionDesired(true)
                        .channelFactory(NioDatagramChannel::new)
                        .nameServerProvider(new SequentialDnsServerAddressStreamProvider(dnsServers))
                        .build();


        final Bootstrap b = new Bootstrap();
        b.group(loop).channel(NioDatagramChannel.class)
                .handler(new ChannelInitializer<DatagramChannel>() {
                    @Override
                    protected void initChannel(DatagramChannel ch) {
                        ch.pipeline().addLast(new DatagramFakeDnsServerHandler(fakeDns, context));
                        ch.pipeline().addLast(new DatagramDnsProxyServerHandler(resolver));
                    }
                }).option(ChannelOption.SO_BROADCAST, true).bind(53).addListener(new GenericFutureListener<Future<? super Void>>() {
            @Override
            public void operationComplete(final Future<? super Void> future) throws Exception {
                System.out.println("DNS: " + future.isSuccess());
            }
        });

        new WindowsTun2Socks().start();

        app.await();
    }

}
