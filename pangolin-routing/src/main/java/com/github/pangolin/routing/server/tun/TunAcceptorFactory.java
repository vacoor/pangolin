package com.github.pangolin.routing.server.tun;

import com.github.pangolin.routing.context.RouteContext;
import com.github.pangolin.routing.server.acceptor.Acceptor;
import com.github.pangolin.routing.server.acceptor.AcceptorFactory;
import com.github.pangolin.routing.server.fakedns.DnsEngine;
import com.github.pangolin.routing.server.tun.adapter.AbstractTunAdapter;
import com.github.pangolin.routing.server.tun.adapter.InterfaceAddressEx;
import com.github.pangolin.routing.server.tun.adapter.TunAdapter;
import com.github.pangolin.routing.server.tun.adapter.darwin.DarwinDnsUtils;
import com.github.pangolin.routing.server.tun.adapter.darwin.DarwinNetworkRoute;
import com.github.pangolin.routing.server.tun.adapter.darwin.DarwinTunAdapter;
import com.github.pangolin.routing.server.tun.adapter.util.NetUtils2;
import com.github.pangolin.routing.server.tun.adapter.windows.WindowsNetworkInterfaceEx;
import com.github.pangolin.routing.server.tun.adapter.windows.WindowsTunAdapter;
import com.github.pangolin.routing.server.tun.net.channel.TunAddress;
import com.github.pangolin.routing.server.tun.net.channel.TunChannel;
import com.github.pangolin.routing.server.tun.net.handler.IpPacketCodec;
import com.github.pangolin.routing.server.tun.net.handler.Tcp4PacketHandler;
import com.github.pangolin.routing.support.SocketChannelFactory;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;

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
                return startTun(ifname, dnsEngine, context.newSocketChannelFactory());
            }
        };
    }

    public static ChannelFuture startTun(final String ifname, final DnsEngine dnsEngine, final SocketChannelFactory factory) {
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

//                        ch.pipeline().addLast(new Udp4PacketHandler());
                        ch.pipeline().addLast(new Tcp4PacketHandler(dnsEngine, factory));
                    }
                });
        return b.bind(new TunAddress(ifname)).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future) throws Exception {
                if (future.isSuccess()) {
                    final TunAdapter device = ((TunChannel) future.channel()).device();

                    InterfaceAddressEx of = InterfaceAddressEx.of("198.18.0.1", 24);
                    ((AbstractTunAdapter) device).setInterfaceAddress(of);
                    ((AbstractTunAdapter) device).addInterfaceAddress(InterfaceAddressEx.of("2001:2::", 48));

                    final TunAdapter adapter = ((TunChannel) future.channel()).device();
                    if (adapter instanceof WindowsTunAdapter) {
                        ((WindowsTunAdapter) adapter).setInterfaceDns(new InetAddress[]{InetAddress.getByName("127.0.0.1")});
                        WindowsNetworkInterfaceEx.flushDnsCache();
                    } else if (adapter instanceof DarwinTunAdapter) {
                        /*-
                         * MacOS 不会添加默认网关路由.
                         * sudo route add -net 198.18.0.0/24 198.18.0.1
                         */
                        final InetAddress gw = of.getAddress();
                        final int prefix = of.getNetworkPrefixLength();
                        final InetAddress dst = NetUtils2.getNetworkAddress(gw, prefix);
                        DarwinNetworkRoute.add(dst, prefix, gw, ifname);

                        // log.info("networksetup -setdnsservers \"Wi-Fi\" 127.0.0.1(empty)");
                        // log.info("sudo killall -HUP mDNSResponder;");
                        DarwinDnsUtils.addDnsServerAndCleanupOnShutdown(new String[]{"::1", "127.0.0.1"});
                    }
                }

                if (future.isSuccess()) {
                    log.info("TUN adapter started on: {}", ifname);
                } else {
                    log.error("Tun adapter bound error: {}", future.cause().getMessage(), future.cause());
                }
            }
        });
    }

}
