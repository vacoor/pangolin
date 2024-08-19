package com.github.pangolin.routing.server;

import com.github.pangolin.routing.RouteApplication;
import com.github.pangolin.routing.beta.fakedns.DnsEngine;
import com.github.pangolin.routing.beta.fakedns.FakeDnsEngine4;
import com.github.pangolin.routing.beta.fakedns.handler.DatagramDnsProxyServerHandler;
import com.github.pangolin.routing.beta.fakedns.handler.DatagramFakeDnsServerHandler;
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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Arrays;
import java.util.List;

/**
 *
 */
public class AppTest {
    private static final String TUN2SOCKS_READY_PATTERN = ".*tun://.* <-> .*";

    private void awaitTun2SocksReady() throws IOException, URISyntaxException {
        final Process tun2SocksProcess = createTun2SocksProcessBuilder().start();
        BufferedReader reader = new BufferedReader(new InputStreamReader(tun2SocksProcess.getInputStream()));
        try {
            String outputLine;
            do {
                outputLine = reader.readLine();
                if (outputLine == null) {
                    //Something goes wrong. Stream is ended before server was activated.
                    throw new RuntimeException("Can't start tun2socks. Check logs for details.");
                }
                System.out.println(outputLine);
            } while (!outputLine.matches(TUN2SOCKS_READY_PATTERN));
        } finally {
            IOUtils.close(reader);
        }
    }

    ProcessBuilder createTun2SocksProcessBuilder() throws URISyntaxException {
        final URL resource = getClass().getResource("/support/windows/tun2socks.exe");
        final File executable = new File(resource.toURI().getPath());
        // tun2socks.exe -device wintun -proxy socks5://127.0.0.1:3081 -interface "以太网 2"
        return new ProcessBuilder()
                .command(
                        executable.getAbsolutePath(),
                        "-device", "wintun",
                        "-proxy", "socks5://127.0.0.1:3081",
                        "-interface", "以太网 2"
                )
                .directory(executable.getParentFile());
    }

    public void initTun() throws IOException, InterruptedException {
        int code = new ProcessBuilder().command("netsh", "interface", "ipv4", "set", "address", "name=\"wintun\"", "source=static", "address=198.18.0.1", "mask=255.255.255.0").start().waitFor();
        System.out.println("SetAddress: " + code);
        int code2 = new ProcessBuilder().command("netsh", "interface", "ipv4", "set", "dnsservers", "name=\"wintun\"", "static", "address=127.0.0.1", "register=none", "validate=no").start().waitFor();
        System.out.println("SetDnsServer: " + code2);
        int code3 = new ProcessBuilder().command("ipconfig", "/flushdns").start().waitFor();
        System.out.println("FlushDNS: " + code3);
    }

    @Test
    public void test2() throws Exception {
//        final Acceptor acceptor = new MixinAcceptorFactory().apply(1089);
//        acceptor.start(new InMemoryRouteContext(null)).sync().channel().closeFuture().sync();
//        RouteApplication.main(new String[0]);

        final ApplicationHome home = new ApplicationHome(AppTest.class);
        final URL conf = new File(home.getDir(), "conf/default.conf").toURI().toURL();
        final FakeDnsEngine4 engine4 = FakeDnsEngine4.create("198.18.0.0", "255.255.0.0");

        final RouteApplication app = new RouteApplication() {
            @Override
            protected RouteContext createParentContext(final URL configLocation) throws Exception {
                RouteContext ctx = super.createParentContext(configLocation);
                ctx.attr(DnsEngine.class.getName(), engine4);
                return ctx;
            }
        };
        final RouteContext context = app.run(conf);

        final EventLoopGroup loop = new NioEventLoopGroup();
//        final List<InetSocketAddress> addresses = Arrays.asList(new InetSocketAddress("192.168.1.1", 53));
        final List<InetSocketAddress> addresses = Arrays.asList(new InetSocketAddress("10.188.207.9", 53));
        final DnsNameResolver resolver =
                new DnsNameResolverBuilder()
                        .eventLoop(loop.next())
                        .recursionDesired(true)
                        .channelFactory(NioDatagramChannel::new)
                        .nameServerProvider(new SequentialDnsServerAddressStreamProvider(addresses))
                        .build();


        final Bootstrap b = new Bootstrap();
        b.group(loop).channel(NioDatagramChannel.class)
                .handler(new ChannelInitializer<DatagramChannel>() {
                    @Override
                    protected void initChannel(DatagramChannel ch) {
                        ch.pipeline().addLast(new DatagramFakeDnsServerHandler(engine4, context));
                        ch.pipeline().addLast(new DatagramDnsProxyServerHandler(resolver));
                    }
                }).option(ChannelOption.SO_BROADCAST, true).bind(53).addListener(new GenericFutureListener<Future<? super Void>>() {
            @Override
            public void operationComplete(final Future<? super Void> future) throws Exception {
                System.out.println("DNS: " + future.isSuccess());
            }
        });

        final List<String> routes = Lists.newLinkedList();
        final List<String> addRouteCommands = Lists.newLinkedList();
        final List<String> deleteRouteCommands = Lists.newLinkedList();
        for (Route<InetSocketAddress> route : context.routes()) {
            for (RoutePredicate predicate : route.getPredicates()) {
                if (predicate instanceof SubnetRoutePredicate) {
                    SubnetRoutePredicate p = (SubnetRoutePredicate) predicate;
                    String s = p.getNetworkAddress().getHostAddress() + "/" + p.getCidrPrefix();
//                    System.out.println(String.format("sudo route add -net %s 198.18.0.1", s));
                    routes.add(s);
                    addRouteCommands.add(String.format("sudo route add -net %s 198.18.0.1", s));
                    deleteRouteCommands.add(String.format("sudo route delete -net %s", s));
                }
            }
        }

        System.out.println("-----------------------");
        System.out.println("----------- Add route commands ----------");
        final FileWriter addRouteWriter = new FileWriter(new File(home.getDir(), "tun_add_route.sh"));
        for (String addRouteCommand : addRouteCommands) {
//            System.out.println(addRouteCommand);
            addRouteWriter.write(addRouteCommand);
            addRouteWriter.write("\r\n");
        }
        addRouteWriter.flush();
        IOUtils.close(addRouteWriter);

        final FileWriter deleteRouteWriter = new FileWriter(new File(home.getDir(), "tun_delete_route.sh"));
        System.out.println("----------- Delete route commands ----------");
        for (String deleteRouteCommand : deleteRouteCommands) {
//            System.out.println(deleteRouteCommand);
            deleteRouteWriter.write(deleteRouteCommand);
            deleteRouteWriter.write("\r\n");
        }
        deleteRouteWriter.flush();
        IOUtils.close(deleteRouteWriter);
        System.out.println("-----------------------");

        awaitTun2SocksReady();
        initTun();

        app.await();
    }

}
