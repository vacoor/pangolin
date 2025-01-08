package com.github.pangolin.routing.beta.tun.fakedns;

import com.github.pangolin.routing.beta.tun.fakedns.beta.SimpleInet4FakeDns;
import com.github.pangolin.routing.beta.tun.fakedns.handler.DatagramDnsProxyServerHandler;
import com.github.pangolin.routing.beta.tun.fakedns.handler.DatagramFakeDnsServerHandler;
import com.github.pangolin.tun.net.windows.win32.WindowsNetworkInterfaceEx;
import com.github.pangolin.routing.context.InMemoryRouteContext;
import com.github.pangolin.routing.context.RouteContext;
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

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class FakeDnsServer {
    public static void main(String[] args) throws Exception {
        final DnsEngine fakeDns = SimpleInet4FakeDns.create("198.18.0.0/15", 60).asDnsEngine();
        List<InetSocketAddress> dnsServers = Arrays.asList(new InetSocketAddress("192.168.1.1", 53));



        if (System.getProperty("os.name").toLowerCase().contains("windows")) {
            dnsServers = WindowsNetworkInterfaceEx.allDns().stream().map(a -> new InetSocketAddress(a, 53)).collect(Collectors.toList());
        }
        final EventLoopGroup loop = new NioEventLoopGroup();
//        final List<InetSocketAddress> addresses = Arrays.asList(new InetSocketAddress("192.168.1.1", 53));
        final DnsNameResolver resolver =
                new DnsNameResolverBuilder()
                        .eventLoop(loop.next())
                        .recursionDesired(true)
                        .channelFactory(NioDatagramChannel::new)
                        .nameServerProvider(new SequentialDnsServerAddressStreamProvider(dnsServers))
                        .build();
        final RouteContext context = new InMemoryRouteContext();

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
                .addListener(new GenericFutureListener<Future<? super Void>>() {
            @Override
            public void operationComplete(final Future<? super Void> future) throws Exception {
                System.out.println("DNS: " + future.isSuccess());
            }
        });
    }
}