package com.github.pangolin.routing.server.tun.net;

import com.github.pangolin.routing.context.RouteContext;
import com.github.pangolin.routing.handler.internal.server.support.SocketChannelFactory;
import com.github.pangolin.routing.server.Acceptor;
import com.github.pangolin.routing.server.AcceptorFactory;
import com.github.pangolin.routing.server.fakedns.DnsEngine;
import com.github.pangolin.routing.server.tun.net.channel.TunAddress;
import com.github.pangolin.routing.server.tun.net.channel.TunChannel;
import com.github.pangolin.routing.server.tun.net.handler.IcmpV4PacketHandler;
import com.github.pangolin.routing.server.tun.net.handler.IpPacketCodec;
import com.github.pangolin.routing.server.tun.net.handler.Tcp4PacketHandler;
import com.github.pangolin.routing.server.tun.net.handler.Udp4PacketHandler;
import com.github.pangolin.routing.server.tun.adapter.TunAdapter;
import com.github.pangolin.routing.server.tun.adapter.windows.WindowsNetworkInterfaceEx;
import com.github.pangolin.routing.server.tun.adapter.windows.WindowsTunAdapter;
import com.sun.jna.Platform;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
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
                        ch.pipeline().addLast(new IcmpV4PacketHandler());
                        ch.pipeline().addLast(new Udp4PacketHandler());
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
                    }
                }
            }
        });
    }

    public static void main(String[] args) {

    }
}
