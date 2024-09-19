package com.github.pangolin.routing.beta.tun;

import com.github.pangolin.routing.beta.tun.fakedns.DnsEngine;
import com.github.pangolin.routing.beta.tun.fakedns.beta.SimpleInet4FakeDns;
import com.github.pangolin.routing.beta.tun.fakedns.handler.DatagramDnsProxyServerHandler;
import com.github.pangolin.routing.beta.tun.fakedns.handler.DatagramFakeDnsServerHandler;
import com.github.pangolin.routing.beta.tun.fakedns.support.FakeDnsDatagramChannelFactory;
import com.github.pangolin.routing.beta.tun.fakedns.support.FakeDnsSocketChannelFactory;
import com.github.pangolin.routing.beta.tun.net.windows.win32.WindowsNetworkInterfaceEx;
import com.github.pangolin.routing.beta.tun.tun2socks.WindowsTun2Socks;
import com.github.pangolin.routing.context.RouteContext;
import com.github.pangolin.routing.handler.internal.server.support.DatagramChannelFactory;
import com.github.pangolin.routing.handler.internal.server.support.SocketChannelFactory;
import com.github.pangolin.routing.server.Acceptor;
import com.github.pangolin.routing.server.MixinAcceptorFactory;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.resolver.dns.DnsNameResolver;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.resolver.dns.SequentialDnsServerAddressStreamProvider;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
public class FakeDnsMixinAcceptorFactory extends MixinAcceptorFactory {

    @Override
    public Acceptor apply(final int listenPort, final String... args) {
        final Acceptor acceptor = super.apply(listenPort, args);
        return new Acceptor() {
            @Override
            public ChannelFuture start(final RouteContext context) throws Exception {
                return acceptor.start(context).addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(final ChannelFuture future) throws Exception {
                        if (future.isSuccess()) {
                            startFakeDnsIfNecessary(context);
                        }
                    }
                });
            }
        };
    }

    private void startFakeDnsIfNecessary(final RouteContext context) throws Exception {
        if (null != context.attr(DnsEngine.class.getName())) {
            return;
        }

        /*-
         * benchmark RFC1544.
         */
        List<InetSocketAddress> dnsServers = Arrays.asList(new InetSocketAddress("192.168.1.1", 53));
//        final DnsEngine fakeDns = SimpleInet6FakeDns.create("2001:2::/48", 60).asDnsEngine();
        final boolean isWindows = System.getProperty("os.name").toLowerCase().contains("windows");
        if (isWindows) {
            dnsServers = WindowsNetworkInterfaceEx.allDns().stream().map(a -> new InetSocketAddress(a, 53)).collect(Collectors.toList());
        }

        final DnsEngine fakeDns = SimpleInet4FakeDns.create("198.18.0.1/24", 60).asDnsEngine();
        final EventLoopGroup loop = new NioEventLoopGroup();
        final DnsNameResolver resolver = new DnsNameResolverBuilder()
                        .eventLoop(loop.next())
                        .recursionDesired(true)
                        .channelFactory(NioDatagramChannel::new)
                        .nameServerProvider(new SequentialDnsServerAddressStreamProvider(dnsServers))
                        .build();

        final Bootstrap b = new Bootstrap();
        b.group(new NioEventLoopGroup())
                .channel(NioDatagramChannel.class)
                .handler(new ChannelInitializer<DatagramChannel>() {
                    @Override
                    protected void initChannel(DatagramChannel ch) {
                        ch.pipeline().addLast(new DatagramFakeDnsServerHandler(fakeDns, domain -> true));
                        ch.pipeline().addLast(new DatagramDnsProxyServerHandler(resolver));
                    }
                }).option(ChannelOption.SO_BROADCAST, true)
                .bind(53)
                .addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(final ChannelFuture future) throws Exception {
                        if (future.isSuccess()) {
                            log.info("Fake DNS startup on {}", future.channel().localAddress());
                            context.attr(DnsEngine.class.getName(), fakeDns);

//                            if (isWindows) {
//                                new WindowsTun2Socks().start();
//                            }
                        }
                    }
                });
    }

    @Override
    protected SocketChannelFactory createSocketChannelFactory(final RouteContext context, final String upstream) {
        final SocketChannelFactory socketChannelFactory = super.createSocketChannelFactory(context, upstream);
        final DnsEngine fakeDns = context.attr(DnsEngine.class.getName());
        return null != fakeDns ? new FakeDnsSocketChannelFactory(fakeDns, socketChannelFactory) : socketChannelFactory;
    }

    @Override
    protected DatagramChannelFactory createDatagramChannelFactory(final RouteContext context, final String upstream) {
        final DatagramChannelFactory datagramChannelFactory = super.createDatagramChannelFactory(context, upstream);
        final DnsEngine fakeDns = context.attr(DnsEngine.class.getName());
        return null != fakeDns ? new FakeDnsDatagramChannelFactory(fakeDns, datagramChannelFactory) : datagramChannelFactory;
    }

}
