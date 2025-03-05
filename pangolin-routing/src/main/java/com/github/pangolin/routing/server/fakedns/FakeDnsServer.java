package com.github.pangolin.routing.server.fakedns;

import com.github.pangolin.routing.server.fakedns.beta.SimpleInet4FakeDns;
import com.github.pangolin.routing.server.fakedns.handler.DatagramDnsProxyServerHandler;
import com.github.pangolin.routing.server.fakedns.handler.DatagramFakeDnsServerHandler;
import com.github.pangolin.routing.server.tun.adapter.darwin.jna.SystemConfigurationTest;
import com.github.pangolin.routing.server.tun.adapter.windows.WindowsNetworkInterfaceEx;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.resolver.dns.DnsNameResolver;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.resolver.dns.SequentialDnsServerAddressStreamProvider;
import io.netty.util.internal.PlatformDependent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;

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
//        final List<InetSocketAddress> dnsServers = Collections.emptyList();
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
        if (PlatformDependent.isOsx()) {
            // SystemConfigurationTest.getPrimaryDnsServers();
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
        DnsNameResolver resolver = null;
        if (!CollectionUtils.isEmpty(upstreamDnsServers)) {
            resolver = new DnsNameResolverBuilder()
                    .eventLoop(loop.next())
                    .recursionDesired(true)
                    .channelFactory(NioDatagramChannel::new)
                    .nameServerProvider(new SequentialDnsServerAddressStreamProvider(upstreamDnsServers))
                    .build();
        }
        final DnsNameResolver resolverToUse = resolver;

        final Bootstrap b = new Bootstrap();
        return b.group(loop)
                .channel(NioDatagramChannel.class)
                .handler(new ChannelInitializer<DatagramChannel>() {
                    @Override
                    protected void initChannel(DatagramChannel ch) {
                        ch.pipeline().addLast(new DatagramFakeDnsServerHandler(fakeDns, domainFakePredicate));
                        if (null != resolverToUse) {
                            ch.pipeline().addLast(new DatagramDnsProxyServerHandler(resolverToUse));
                        }
                    }
                }).option(ChannelOption.SO_BROADCAST, true)
                .bind(53).addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(final ChannelFuture future) throws Exception {
                        if (!future.isSuccess()) {
                            final Throwable cause = future.cause();
                            log.warn("FakeDNS error: {}", cause.getMessage(), cause);
                        } else {
                            log.info("Fake DNS startup on {}", future.channel().localAddress());
                        }
                    }
                });
    }

    public static void main(String[] args) throws Exception {
        final DnsEngine fakeDns = SimpleInet4FakeDns.create("198.18.0.0/24", 60).asDnsEngine();

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