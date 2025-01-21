package com.github.pangolin.routing.extra.fakedns;

import com.github.pangolin.routing.extra.fakedns.beta.SimpleInet4FakeDns;
import com.github.pangolin.routing.extra.fakedns.support.FakeDnsDatagramChannelFactory;
import com.github.pangolin.routing.extra.fakedns.support.FakeDnsSocketChannelFactory;
import com.github.pangolin.routing.context.RouteContext;
import com.github.pangolin.routing.handler.internal.server.support.DatagramChannelFactory;
import com.github.pangolin.routing.handler.internal.server.support.SocketChannelFactory;
import com.github.pangolin.routing.route.Route;
import com.github.pangolin.routing.server.Acceptor;
import com.github.pangolin.routing.server.MixinAcceptorFactory;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.function.Predicate;

@Slf4j
public class FakeDnsMixinAcceptorFactory extends MixinAcceptorFactory {

    @Override
    public Acceptor apply(final int listenPort, final String... args) {
        final Acceptor acceptor = super.apply(listenPort, args);
        final String fakeSubnet = "198.18.0.1/15";
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
                        if (future.isSuccess()) {
                            final Channel channel = future.channel();
                            final InetSocketAddress addr = (InetSocketAddress) channel.localAddress();
                            final int listenPort = addr.getPort();
                            log.info("ACCEPTOR: {}", addr);
                            if (1081 == listenPort) {
                                final Predicate<String> domainFakePredicate = domain -> !isDirect(context, domain);
                                startFakeDnsIfNecessary(fakeDns2, domainFakePredicate).addListener(new ChannelFutureListener() {
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
                    }
                });
            }
        };
    }

    private boolean isDirect(final RouteContext context, final String domain) {
        final Route r = context.getRoute(InetSocketAddress.createUnresolved(domain, 0));
        return null == r || "DIRECT".equalsIgnoreCase(r.getUpstream());
    }

    private ChannelFuture startFakeDnsIfNecessary(final DnsEngine fakeDns,
                                                  final Predicate<String> domainFakePredicate) throws Exception {
        return FakeDnsServer.startFakeDns(fakeDns, domainFakePredicate);
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
