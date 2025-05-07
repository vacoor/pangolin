package com.github.pangolin.routing.acceptor.tun;

import com.github.pangolin.routing.acceptor.Acceptor;
import com.github.pangolin.routing.acceptor.tun.adapter.InterfaceAddressEx;
import com.github.pangolin.routing.acceptor.tun.adapter.NetworkRoutingTable;
import com.github.pangolin.routing.acceptor.tun.adapter.TunAdapter;
import com.github.pangolin.routing.acceptor.tun.adapter.darwin.DarwinDns;
import com.github.pangolin.routing.acceptor.tun.adapter.darwin.DarwinTunAdapter;
import com.github.pangolin.routing.acceptor.tun.adapter.linux.LinuxTunAdapter;
import com.github.pangolin.routing.acceptor.tun.adapter.windows.WindowsNetworkInterface;
import com.github.pangolin.routing.acceptor.tun.adapter.windows.WindowsTunAdapter;
import com.github.pangolin.routing.acceptor.tun.fakedns.DnsEngine;
import com.github.pangolin.routing.acceptor.tun.net.channel.TunAddress;
import com.github.pangolin.routing.acceptor.tun.net.channel.TunChannel;
import com.github.pangolin.routing.acceptor.tun.net.channel.TunChannelOption;
import com.github.pangolin.routing.acceptor.tun.net.handler.IpPacketCodec;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.Tcp4PacketHandler;
import com.github.pangolin.routing.context.RouteContext;
import com.github.pangolin.routing.support.SocketChannelFactory;
import com.github.pangolin.routing.util.SocketUtils;
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
public class TunAcceptor implements Acceptor {
    private static final String DEFAULT_WINTUN_TYPE = "Proxies Host-Only";
    private static final String DEFAULT_WINTUN_UUID = "{2B54EB73-2CF2-4C1A-B900-E193C9E16966}";

    private final String ifname;
    private final String wintunType;
    private final String wintunUuid;

    public TunAcceptor(final String ifname) {
        this(ifname, DEFAULT_WINTUN_TYPE, DEFAULT_WINTUN_UUID);
    }

    public TunAcceptor(final String ifname, final String wintunType, final String wintunUuid) {
        this.ifname = ifname;
        this.wintunType = wintunType;
        this.wintunUuid = wintunUuid;
    }

    @Override
    public ChannelFuture start(final RouteContext context) throws Exception {
        final DnsEngine dnsEngine = context.attr(DnsEngine.class.getName());
        final SocketChannelFactory factory = context.newSocketChannelFactory();
        return startTun(ifname, dnsEngine, factory);
    }

    public ChannelFuture startTun(final String ifname, final DnsEngine dnsEngine, final SocketChannelFactory factory) throws Exception {
        final EventLoopGroup group = new DefaultEventLoopGroup();

        final Bootstrap b = new Bootstrap()
                .group(group)
                .channel(TunChannel.class)
                .option(TunChannelOption.WINTUN_TYPE, wintunType)
                .option(TunChannelOption.WINTUN_UUID, wintunUuid)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(final Channel ch) throws Exception {
                        ch.pipeline().addLast(new IpPacketCodec());
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
                        DarwinDns.flushDnsCache();
                    } else if (adapter instanceof LinuxTunAdapter) {
                        // ip route add 192.168.2.0/24 via 192.168.1.1 dev eth0
//                        Inet4Address addr = (Inet4Address) InetAddress.getByName("198.18.2.0");
//                        Inet4Address gw = (Inet4Address) InetAddress.getByName("198.18.0.1");
//                        LinuxNetworkRoutingTable.add("tun8", addr, 24, gw, false);

                    }

                    final InetAddress dst = SocketUtils.addressByName("10.188.71.3", true);
                    final InetAddress gw = SocketUtils.addressByName("198.18.0.1", true);
                    NetworkRoutingTable.get().add(dst, (byte) 24, gw, adapter.name(), 0);
                } else {
                    log.error("Tun adapter bound error: {}", future.cause().getMessage(), future.cause());
                }
            }
        });
    }
}
