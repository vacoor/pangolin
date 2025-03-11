package com.github.pangolin.routing.server.tun;

import com.github.pangolin.routing.context.RouteContext;
import com.github.pangolin.routing.server.acceptor.Acceptor;
import com.github.pangolin.routing.server.acceptor.AcceptorFactory;
import com.github.pangolin.routing.server.fakedns.DnsEngine;
import com.github.pangolin.routing.server.tun.adapter.InterfaceAddressEx;
import com.github.pangolin.routing.server.tun.adapter.TunAdapter;
import com.github.pangolin.routing.server.tun.adapter.darwin.DarwinDns;
import com.github.pangolin.routing.server.tun.adapter.darwin.DarwinTunAdapter;
import com.github.pangolin.routing.server.tun.adapter.linux.LinuxTunAdapter;
import com.github.pangolin.routing.server.tun.adapter.windows.WindowsNetworkInterface;
import com.github.pangolin.routing.server.tun.adapter.windows.WindowsTunAdapter;
import com.github.pangolin.routing.server.tun.net.channel.TunAddress;
import com.github.pangolin.routing.server.tun.net.channel.TunChannel;
import com.github.pangolin.routing.server.tun.net.handler.IpPacketCodec;
import com.github.pangolin.routing.server.tun.net.handler.Tcp4PacketHandler;
import com.github.pangolin.routing.support.SocketChannelFactory;
import com.sun.jna.Platform;
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
        final String defName = Platform.isWindows() ? "以太网 P" : null;
        final String ifname = args.length > 0 ? args[0] : defName;
        return new Acceptor() {
            @Override
            public ChannelFuture start(final RouteContext context) throws Exception {
                final DnsEngine dnsEngine = context.attr(DnsEngine.class.getName());
                return startTun(ifname, dnsEngine, context.newSocketChannelFactory());
            }
        };
    }

    public static ChannelFuture startTun(final String ifname, final DnsEngine dnsEngine, final SocketChannelFactory factory) throws Exception {
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
        final InterfaceAddressEx[] bindings = {
                InterfaceAddressEx.of("198.18.0.1", 24),
//                InterfaceAddressEx.of("198.18.0.254", 24),
//                InterfaceAddressEx.of("2001:2::", 48)
        };
        return b.bind(new TunAddress(ifname, bindings)).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future) throws Exception {
                if (future.isSuccess()) {
                    final TunAdapter adapter = ((TunChannel) future.channel()).device();
                    log.info("TUN adapter started on: {}", adapter.name());

                    if (adapter instanceof WindowsTunAdapter) {
                        // log.info("ipconfig /flushdns");
                        final WindowsNetworkInterface nix = WindowsNetworkInterface.getByLuid(((WindowsTunAdapter) adapter).luid());
                        nix.setInterfaceDns(new InetAddress[]{InetAddress.getByName("127.0.0.1")});
                        WindowsNetworkInterface.flushDnsCache();
                    } else if (adapter instanceof DarwinTunAdapter) {
                        // log.info("networksetup -setdnsservers \"Wi-Fi\" 127.0.0.1(empty)");
                        // log.info("sudo killall -HUP mDNSResponder;");
                        DarwinDns.addDns(new String[]{"::1", "127.0.0.1"});
                    } else if (adapter instanceof LinuxTunAdapter) {
                        // ip route add 192.168.2.0/24 via 192.168.1.1 dev eth0
//                        Inet4Address addr = (Inet4Address) InetAddress.getByName("198.18.2.0");
//                        Inet4Address gw = (Inet4Address) InetAddress.getByName("198.18.0.1");
//                        LinuxNetworkRoutingTable.add("tun8", addr, 24, gw, false);

                    }
                } else {
                    log.error("Tun adapter bound error: {}", future.cause().getMessage(), future.cause());
                }
            }
        });
    }

}
