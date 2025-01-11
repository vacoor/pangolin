package com.github.pangolin.routing.extra.fakedns;

import com.github.pangolin.routing.extra.fakedns.beta.SimpleInet4FakeDns;
import com.github.pangolin.routing.extra.fakedns.handler.DatagramDnsProxyServerHandler;
import com.github.pangolin.routing.extra.fakedns.handler.DatagramFakeDnsServerHandler;
import com.github.pangolin.tun.net.windows.win32.WindowsNetworkInterfaceEx;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
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
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Slf4j
public class FakeDnsServer {

    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("windows");

    public static ChannelFuture startFakeDns(final DnsEngine fakeDns,
                                             final Predicate<String> domainFakePredicate) throws SocketException {
        log.info("FakeDNS Starting...");
        final List<InetSocketAddress> dnsServers = determineDnsServers();
        log.info("FakeDNS detect DNS: {}", dnsServers);
        return startFakeDns(fakeDns, domainFakePredicate, dnsServers);
    }

    private static List<InetSocketAddress> determineDnsServers() throws SocketException {
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


    public static ChannelFuture startFakeDns(final DnsEngine fakeDns,
                                             final Predicate<String> domainFakePredicate,
                                             final List<InetSocketAddress> upstreamDnsServers) {
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
                        ch.pipeline().addLast(new DatagramFakeDnsServerHandler(fakeDns, domainFakePredicate));
                        ch.pipeline().addLast(new DatagramDnsProxyServerHandler(resolver));
                    }
                }).option(ChannelOption.SO_BROADCAST, true)
                .bind(53).addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(final ChannelFuture future) throws Exception {
                        if (!future.isSuccess()) {
                            final Throwable cause = future.cause();
                            log.warn("FakeDNS error: {}", cause.getMessage(), cause);
                        }
                    }
                });
    }

    public static void main(String[] args) throws Exception {
        final DnsEngine fakeDns = SimpleInet4FakeDns.create("198.18.0.0/15", 60).asDnsEngine();

        startFakeDns(fakeDns, domain -> true).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future) throws Exception {
                if (future.isSuccess()) {
                    log.info("Fake DNS startup on {}", future.channel().localAddress());
                }
            }
        });
    }
}