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
import com.github.pangolin.routing.acceptor.tun.net.handler.support.IpPacketCodec;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.Tcp4DemultiplexHandler;
import com.github.pangolin.routing.context.RouteContext;
import com.github.pangolin.routing.support.DatagramChannelFactory;
import com.github.pangolin.routing.support.SocketChannelFactory;
import com.github.pangolin.routing.upstream.DynamicUpstream;
import com.github.pangolin.routing.upstream.Upstream;
import com.github.pangolin.routing.util.SocketUtils;
import freework.crypto.digest.Hash;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.handler.codec.dns.DatagramDnsQuery;
import io.netty.handler.codec.dns.DatagramDnsResponse;
import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 *
 */
@Slf4j
public class TunAcceptor implements Acceptor {
    private static final String DEFAULT_WINTUN_TYPE = "Pangolin Virtual Ethernet Adapter";

    private final String ifname;
    private final InterfaceAddressEx[] bindings;

    private final String wintunType;
    private final String wintunUuid;

    private String upstream;

    public TunAcceptor(final String ifname, final InterfaceAddressEx[] bindings, final String upstream) {
        this.ifname = ifname;
        this.bindings = bindings;
        this.wintunType = DEFAULT_WINTUN_TYPE;
        this.wintunUuid = determineUuid(bindings);
        this.upstream = upstream;
    }

    public String getUpstream() {
        return upstream;
    }

    public void setUpstream(final String upstream) {
        this.upstream = upstream;
    }

    @Override
    public ChannelFuture start(final RouteContext context) throws Exception {
        final SocketChannelFactory socketFactory = getSocketChannelFactory(context);
        final DatagramChannelFactory datagramFactory = getDatagramChannelFactory(context);

        DnsEngine lazyDns = new DnsEngine() {

            @Override
            public boolean isFakeAddress(final byte[] address) {
                final DnsEngine dns = engine();
                return null != dns && dns.isFakeAddress(address);
            }

            @Override
            public String getHostByAddress(final byte[] address) {
                final DnsEngine dns = engine();
                return null != dns ? dns.getHostByAddress(address) : null;
            }

            @Override
            public DatagramDnsResponse lookup(final DatagramDnsQuery query) {
                final DnsEngine dns = engine();
                return null != dns ? dns.lookup(query) : null;
            }

            private DnsEngine engine() {
                return context.attr(DnsEngine.class.getName());
            }
        };

        return start0(ifname, bindings, wintunType, wintunUuid, lazyDns, socketFactory, datagramFactory);
    }

    private ChannelFuture start0(final String ifname, final InterfaceAddressEx[] bindings,
                                 final String wintunType, final String wintunUuid,
                                 final DnsEngine dnsEngine,
                                 final SocketChannelFactory socketFactory, final DatagramChannelFactory datagramFactory) throws Exception {
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
                        ch.pipeline().addLast(new Tcp4DemultiplexHandler(dnsEngine, socketFactory));
                    }
                });
        return b.bind(new TunAddress(ifname, bindings)).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future) throws Exception {
                if (future.isSuccess()) {
                    final TunAdapter adapter = ((TunChannel) future.channel()).device();
                    log.info("TUN adapter({}) started on: {}", wintunUuid, adapter.name());

//                    final String[] dnsServers = {"127.0.0.1", "::1"};
                    final String[] dnsServers = {"127.0.0.1"};
                    if (adapter instanceof WindowsTunAdapter) {
                        final long luid = ((WindowsTunAdapter) adapter).luid();
                        WindowsNetworkInterface.getByLuid(luid).setInterfaceDns(
                                Arrays.stream(dnsServers).map(SocketUtils::addressByName).toArray(InetAddress[]::new)
                        );
                        log.info("Set DNS server to: {} for {}", Arrays.toString(dnsServers), adapter.name());

                        WindowsNetworkInterface.flushDnsCache();
                        log.info("Flush DNS cache");

                        future.channel().eventLoop().schedule(() -> {
                            for (final NetworkRoutingTable.Route route : NetworkRoutingTable.get().routes()) {
                                System.out.println(route);
                            }
                        }, 3, TimeUnit.SECONDS);

                    } else if (adapter instanceof DarwinTunAdapter) {
                        // networksetup -setdnsservers "Wi-Fi" 127.0.0.1 or "empty"
                        // sudo killall -HUP mDNSResponder;

                            /*
                            192.168.1.202
                            255.255.255.0
                            192.168.1.1
                         */
                         DarwinDns.addDns(dnsServers);
//                        DarwinDns.setDns(dnsServers);
                        log.info("Set DNS server to: {}", Arrays.toString(dnsServers));

                        DarwinDns.flushDnsCache();
                        log.info("Flush DNS cache");
                    } else if (adapter instanceof LinuxTunAdapter) {
                        log.warn("Can't set DNS server to: {}", Arrays.toString(dnsServers));
                        log.warn("Can't flush DNS cache");
                    }

                    final InetAddress gw = SocketUtils.addressByName("198.18.0.1");
//                    NetworkRoutingTable.get().add(gw, (byte) 32, gw, adapter.name(), 25);

                    // TODO add route
                    final InetAddress dst = SocketUtils.addressByName("10.188.70.45");
                    NetworkRoutingTable.get().add(dst, (byte) 24, gw, adapter.name(), 0);
                } else {
                    log.error("Tun adapter bound error: {}", future.cause().getMessage(), future.cause());
                }
            }
        });
    }

    private SocketChannelFactory getSocketChannelFactory(final RouteContext context) {
        final Upstream upstreamToUse = new DynamicUpstream("tun-socket-upstream") {

            @Override
            public boolean isAvailable() {
                return context.getUpstream(upstream).isAvailable();
            }

            @Override
            protected Upstream choose(final InetSocketAddress destination) {
                return context.getUpstream(upstream);
            }

        };
        return context.newSocketChannelFactory(upstreamToUse);
    }

    private DatagramChannelFactory getDatagramChannelFactory(final RouteContext context) {
        final Upstream upstreamToUse = new DynamicUpstream("tun-datagram-upstream") {

            @Override
            public boolean isAvailable() {
                return context.getUpstream(upstream).isAvailable();
            }

            @Override
            protected Upstream choose(final InetSocketAddress destination) {
                return context.getUpstream(upstream);
            }

        };
        return context.newDatagramChannelFactory(upstreamToUse);
    }

    private static String determineUuid(final InterfaceAddressEx... bindings) {
        final StringBuilder buff = new StringBuilder();
        for (final InterfaceAddressEx binding : bindings) {
            buff.append(binding.getAddress().getHostAddress()).append(binding.getNetworkPrefixLength()).append(";");
        }
        final String hash = new Hash.MD5(buff.toString()).toHex().toUpperCase();
        buff.setLength(0);
        buff.append(hash).insert(8, "-").insert(13, "-").insert(18, "-").insert(23, "-").insert(0, "{").append("}");
        return buff.toString();
    }

}
