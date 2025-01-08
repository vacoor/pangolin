package com.github.pangolin.routing.beta.tun.fakedns;

import com.github.pangolin.routing.beta.tun.fakedns.DnsEngine;
import com.github.pangolin.routing.beta.tun.fakedns.beta.SimpleInet4FakeDns;
import com.github.pangolin.routing.beta.tun.fakedns.handler.DatagramDnsProxyServerHandler;
import com.github.pangolin.routing.beta.tun.fakedns.handler.DatagramFakeDnsServerHandler;
import com.github.pangolin.routing.beta.tun.fakedns.support.FakeDnsDatagramChannelFactory;
import com.github.pangolin.routing.beta.tun.fakedns.support.FakeDnsSocketChannelFactory;
import com.github.pangolin.tun.net.windows.win32.WindowsNetworkInterfaceEx;
import com.github.pangolin.routing.beta.tun.tun2socks.WindowsTun2Socks;
import com.github.pangolin.routing.context.RouteContext;
import com.github.pangolin.routing.handler.internal.server.support.DatagramChannelFactory;
import com.github.pangolin.routing.handler.internal.server.support.SocketChannelFactory;
import com.github.pangolin.routing.route.Route;
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
    public static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("windows");

    @Override
    public Acceptor apply(final int listenPort, final String... args) {
        final Acceptor acceptor = super.apply(listenPort, args);
        return new Acceptor() {
            @Override
            public ChannelFuture start(final RouteContext context) throws Exception {
                DnsEngine fakeDns = null;
                if (null == context.attr(DnsEngine.class.getName())) {
                    fakeDns = SimpleInet4FakeDns.create("198.18.0.1/24", 60).asDnsEngine();
                    context.attr(DnsEngine.class.getName(), fakeDns);
                }

                final DnsEngine finalFakeDns = fakeDns;
                return acceptor.start(context).addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(final ChannelFuture future) throws Exception {
                        if (future.isSuccess()) {
                            final InetSocketAddress addr = (InetSocketAddress) future.channel().localAddress();
                            if (null != finalFakeDns) {
                                startFakeDnsIfNecessary(finalFakeDns, context, "127.0.0.1:" + addr.getPort());
                            }
                        }
                    }
                });
            }
        };
    }

    private void startFakeDnsIfNecessary(final DnsEngine fakeDns, final RouteContext context, String socks5Server) throws Exception {
        /*-
         * benchmark RFC1544.
         */
        List<InetSocketAddress> dnsServers = Arrays.asList(new InetSocketAddress("192.168.1.1", 53));
//        final DnsEngine fakeDns = SimpleInet6FakeDns.create("2001:2::/48", 60).asDnsEngine();
        if (IS_WINDOWS) {
            dnsServers = WindowsNetworkInterfaceEx.allDns().stream().map(a -> new InetSocketAddress(a, 53)).collect(Collectors.toList());
        }

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
                        ch.pipeline().addLast(new DatagramFakeDnsServerHandler(fakeDns, domain -> {
                            final Route r = context.getRoute(InetSocketAddress.createUnresolved(domain, 0));
                            return null != r && !"DIRECT".equalsIgnoreCase(r.getUpstream());
                        }));
                        ch.pipeline().addLast(new DatagramDnsProxyServerHandler(resolver));
                    }
                }).option(ChannelOption.SO_BROADCAST, true)
                .bind(53)
                .addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(final ChannelFuture future) throws Exception {
                        if (future.isSuccess()) {
                            log.info("Fake DNS startup on {}", future.channel().localAddress());
//                            context.attr(DnsEngine.class.getName(), fakeDns);

                            if (IS_WINDOWS) {
                                // new WindowsTun2Socks("Panglin", socks5Server, "以太网 2").start();
                            }
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
