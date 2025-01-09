package com.github.pangolin.routing.beta.tun.fakedns;

import com.github.pangolin.routing.beta.tun.fakedns.beta.SimpleInet4FakeDns;
import com.github.pangolin.routing.beta.tun.fakedns.handler.DatagramDnsProxyServerHandler;
import com.github.pangolin.routing.beta.tun.fakedns.handler.DatagramFakeDnsServerHandler;
import com.github.pangolin.routing.beta.tun.fakedns.support.FakeDnsDatagramChannelFactory;
import com.github.pangolin.routing.beta.tun.fakedns.support.FakeDnsSocketChannelFactory;
import com.github.pangolin.routing.context.RouteContext;
import com.github.pangolin.routing.handler.internal.server.support.DatagramChannelFactory;
import com.github.pangolin.routing.handler.internal.server.support.SocketChannelFactory;
import com.github.pangolin.routing.route.Route;
import com.github.pangolin.routing.server.Acceptor;
import com.github.pangolin.routing.server.MixinAcceptorFactory;
import com.github.pangolin.tun.net.windows.win32.WindowsNetworkInterfaceEx;
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
import java.net.SocketException;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
public class FakeDnsMixinAcceptorFactory extends MixinAcceptorFactory {
    public static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("windows");

    @Override
    public Acceptor apply(final int listenPort, final String... args) {
        final Acceptor acceptor = super.apply(listenPort, args);
        final String fakeSubnet = "198.18.0.1/24";
        final String fakeIp = "198.18.0.1";
        final String fakeNetmask = "255.255.255.0";

        return new Acceptor() {
            @Override
            public ChannelFuture start(final RouteContext context) throws Exception {
                DnsEngine fakeDns = null;
                if (null == context.attr(DnsEngine.class.getName())) {
                    fakeDns = SimpleInet4FakeDns.create(fakeSubnet, 60).asDnsEngine();
                    context.attr(DnsEngine.class.getName(), fakeDns);
                }

                final DnsEngine fakeDns2 = fakeDns;
                return acceptor.start(context).addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(final ChannelFuture future) throws Exception {
                        if (future.isSuccess() && 3081 == listenPort) {
                            final InetSocketAddress addr = (InetSocketAddress) future.channel().localAddress();
                            final List<InetSocketAddress> upstreamDnsServers = determineDnsServers();
                            startFakeDnsIfNecessary(fakeDns2, context, upstreamDnsServers).addListener(new ChannelFutureListener() {
                                @Override
                                public void operationComplete(final ChannelFuture future) throws Exception {
                                    if (future.isSuccess()) {
                                        log.info("Fake DNS startup on {}", future.channel().localAddress());

                                        // final String socks5Server = "127.0.0.1:" + addr.getPort();
                                        final String socks5Server = "127.0.0.1:" + listenPort;
                                        startTunIfNecessary(fakeIp, fakeNetmask, socks5Server);
                                    }
                                }
                            });
                        }
                    }
                });
            }
        };
    }

    private ChannelFuture startFakeDnsIfNecessary(final DnsEngine fakeDns, final RouteContext context, final List<InetSocketAddress> upstreamDnsServers) throws Exception {
        /*-
         * benchmark RFC1544.
         */
//        final DnsEngine fakeDns = SimpleInet6FakeDns.create("2001:2::/48", 60).asDnsEngine();

        final EventLoopGroup loop = new NioEventLoopGroup();
        final DnsNameResolver resolver = new DnsNameResolverBuilder()
                .eventLoop(loop.next())
                .recursionDesired(true)
                .channelFactory(NioDatagramChannel::new)
                .nameServerProvider(new SequentialDnsServerAddressStreamProvider(upstreamDnsServers))
                .build();

        final Bootstrap b = new Bootstrap();
        return b.group(new NioEventLoopGroup())
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
                }).option(ChannelOption.SO_BROADCAST, true).bind(53);
    }

    private List<InetSocketAddress> determineDnsServers() throws SocketException {
        if (IS_WINDOWS) {
            return WindowsNetworkInterfaceEx.allDns()
                    .stream()
                    .filter(a -> !a.isAnyLocalAddress())
                    .filter(a -> !a.isLoopbackAddress())
                    .map(a -> new InetSocketAddress(a, 53))
                    .collect(Collectors.toList());
        }
        return Collections.singletonList(new InetSocketAddress("192.168.1.1", 53));
    }

    private void startTunIfNecessary(final String tunIp, final String tunNetmask, final String socksServer) {
//        if (IS_WINDOWS) {
        // new WindowsTun2Socks("Panglin", socks5Server, "以太网 2").start();
//        }
        System.err.println("### Please run: \r\n"
                + "tun2socks -device wintun -proxy socks5://" + socksServer + " -interface \"以太网 2\"\r\n"
                + "netsh interface ipv4 set address name=\"wintun\" source=static address=198.18.0.1 mask=255.255.255.0\r\n"
                + "netsh interface ipv4 set dnsservers name=\"wintun\" static address=127.0.0.1 register=none validate=no");
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
