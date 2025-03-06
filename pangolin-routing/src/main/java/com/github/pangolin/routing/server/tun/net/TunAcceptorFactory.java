package com.github.pangolin.routing.server.tun.net;

import com.github.pangolin.routing.context.RouteContext;
import com.github.pangolin.routing.handler.internal.server.support.SocketChannelFactory;
import com.github.pangolin.routing.route.Route;
import com.github.pangolin.routing.server.Acceptor;
import com.github.pangolin.routing.server.AcceptorFactory;
import com.github.pangolin.routing.server.fakedns.DnsEngine;
import com.github.pangolin.routing.server.tun.adapter.TunAdapter;
import com.github.pangolin.routing.server.tun.adapter.darwin.DarwinTunAdapter;
import com.github.pangolin.routing.server.tun.adapter.darwin.DarwinDnsUtils;
import com.github.pangolin.routing.server.tun.adapter.windows.WindowsNetworkInterfaceEx;
import com.github.pangolin.routing.server.tun.adapter.windows.WindowsTunAdapter;
import com.github.pangolin.routing.server.tun.net.channel.TunAddress;
import com.github.pangolin.routing.server.tun.net.channel.TunChannel;
import com.github.pangolin.routing.server.tun.net.handler.IpPacketCodec;
import com.github.pangolin.routing.server.tun.net.handler.Tcp4PacketHandler;
import com.sun.jna.Platform;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 *
 */
@Slf4j
public class TunAcceptorFactory implements AcceptorFactory {

    @Override
    public Acceptor apply(final int listenPort, final String... args) {
        String ifname = args.length > 0 ? args[0] : "utun8";
        return new Acceptor() {
            @Override
            public ChannelFuture start(final RouteContext context) throws Exception {
                final DnsEngine dnsEngine = context.attr(DnsEngine.class.getName());
                return startTun(ifname, dnsEngine, context.newSocketChannelFactory(), context);
            }
        };
    }

    public static ChannelFuture startTun(final String ifname, final DnsEngine dnsEngine, final SocketChannelFactory factory, final RouteContext context) {
        final EventLoopGroup group = new DefaultEventLoopGroup(1);
        final Bootstrap b = new Bootstrap()
                .group(group)
                .channel(TunChannel.class)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(final Channel ch) throws Exception {
                        ch.pipeline().addLast(new IpPacketCodec());
//                            ch.pipeline().addLast(new SimpleTcpPacketHandler(dnsEngine, factory));
//                        ch.pipeline().addLast(new IcmpV4PacketHandler());

//                        ch.pipeline().addLast(new DatagramPacketCodec());
//                        ch.pipeline().addLast(new DatagramFakeDnsServerHandler(dnsEngine, domain -> !isDirect(context, domain)));

//                        ch.pipeline().addLast(new Udp4PacketHandler());
                        ch.pipeline().addLast(new Tcp4PacketHandler(dnsEngine, factory));
                    }
                });
        return b.bind(new TunAddress(ifname)).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future) throws Exception {
                if (future.isSuccess()) {
                    log.info("Tun device started: {}", ifname);
                    if (Platform.isMac()) {
                        log.info("networksetup -setdnsservers \"Wi-Fi\" 127.0.0.1(empty)");
                        log.info("sudo killall -HUP mDNSResponder;");
                    }
                    final TunAdapter adapter = ((TunChannel) future.channel()).device();
                    if (adapter instanceof WindowsTunAdapter) {
                        ((WindowsTunAdapter) adapter).setInterfaceDns(new InetAddress[]{
                                InetAddress.getByName("127.0.0.1")
                        });
                        WindowsNetworkInterfaceEx.flushDnsCache();
                    } else if (adapter instanceof DarwinTunAdapter) {
                        DarwinDnsUtils.addDnsServerAndCleanupOnShutdown("127.0.0.1");
                    }
                }
            }
        });
    }

    private static boolean isDirect(final RouteContext context, final String domain) {
        final Route r = context.getRoute(InetSocketAddress.createUnresolved(domain, 0));
        return null == r || "DIRECT".equalsIgnoreCase(r.getUpstream());
    }

    public static void main(String[] args) {

    }
}
