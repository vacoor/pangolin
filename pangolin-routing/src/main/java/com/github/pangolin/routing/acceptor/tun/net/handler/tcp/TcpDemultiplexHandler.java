package com.github.pangolin.routing.acceptor.tun.net.handler.tcp;

import com.github.pangolin.routing.acceptor.tun.fakedns.DnsEngine;
import com.github.pangolin.routing.acceptor.tun.net.handler.support.IpPacketHandler;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.core.TcpDemultiplexer;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpSock;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.tcp_request_sock;
import com.github.pangolin.routing.support.SocketChannelFactory;
import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import freework.reflect.Types;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import lombok.extern.slf4j.Slf4j;
import org.pcap4j.packet.IpPacket;
import org.pcap4j.packet.TcpPacket;
import org.pcap4j.packet.namednumber.IpNumber;

import java.lang.reflect.Type;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;

@Slf4j
public abstract class TcpDemultiplexHandler<T extends IpPacket> extends IpPacketHandler<T> {
    private final DnsEngine dnsEngine;
    private final EventLoopGroup childGroup = new NioEventLoopGroup();

    private final Map<String, tcp_request_sock> requestRegistry = Maps.newConcurrentMap();
    private final Map<String, TcpSock> establishedRegistry = Maps.newConcurrentMap();
    private final TcpDemultiplexer<T> demultiplexer;

    public TcpDemultiplexHandler(final DnsEngine dnsEngine, final SocketChannelFactory factory) {
        super(IpNumber.TCP);
        final Type type = Types.resolveType(TcpDemultiplexHandler.class.getTypeParameters()[0], getClass());
        Preconditions.checkState(type instanceof Class<?>, "Can't resolve %s IpPacket Class", TcpDemultiplexHandler.class.getName());

        this.dnsEngine = dnsEngine;
        this.demultiplexer = create(requestRegistry, establishedRegistry, childGroup, dnsEngine, factory);
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final T rawIpPacket) throws Exception {
        try {
            final T ipPacket = prepare(rawIpPacket);
            childGroup.execute(() -> {
                demultiplexer.tcp_rcv(ctx.channel(), ipPacket);
            });
        } catch (final Exception ex) {
            demultiplexer.send_reset(ctx.channel(), rawIpPacket.getHeader(), rawIpPacket.get(TcpPacket.class), -77);
        }
    }


    protected T prepare(final T ipPacket) throws UnknownHostException {
        return ipPacket;
    }

    protected InetAddress resolveDstAddress(final InetAddress address) throws UnknownHostException {
        final byte[] addr = address.getAddress();
        if (dnsEngine.isFakeAddress(addr)) {
            final String hostname = dnsEngine.getHostByAddress(addr);
            if (null != hostname && !hostname.isEmpty()) {
                return InetAddress.getByAddress(hostname, addr);
            }
            throw new UnknownHostException(String.format("Can't resolve hostname for fake IP: %s", address.getHostAddress()));
        }
        return address;
    }

    protected abstract TcpDemultiplexer<T> create(
            Map<String, tcp_request_sock> handshakeRegistry,
            Map<String, TcpSock> establishedRegistry,
            EventLoopGroup childGroup,
            DnsEngine dnsEngine, SocketChannelFactory socketChannelFactory);

}
