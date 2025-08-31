package com.github.pangolin.routing.acceptor.tun.net.handler.tcp;

import com.github.pangolin.routing.acceptor.tun.net.handler.IpPacketHandler;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpConnection;
import com.github.pangolin.routing.support.SocketChannelFactory;
import com.github.pangolin.routing.acceptor.tun.fakedns.DnsEngine;
import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import freework.reflect.Types;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import java.net.UnknownHostException;
import lombok.extern.slf4j.Slf4j;
import org.pcap4j.packet.IpPacket;
import org.pcap4j.packet.IpPacket.IpHeader;
import org.pcap4j.packet.TcpPacket;
import org.pcap4j.packet.TcpPacket.TcpHeader;
import org.pcap4j.packet.namednumber.IpNumber;
import org.pcap4j.packet.namednumber.TcpPort;

import java.lang.reflect.Type;
import java.net.InetAddress;
import java.util.Map;

@Slf4j
public abstract class TcpPacketHandler<T extends IpPacket> extends IpPacketHandler<T> {
    private final DnsEngine dnsEngine;
    private final SocketChannelFactory socketChannelFactory;
    private final EventLoopGroup childGroup = new NioEventLoopGroup();

    private final Map<String, TcpConnection> sessionMap = Maps.newConcurrentMap();

    public TcpPacketHandler(final DnsEngine dnsEngine, final SocketChannelFactory factory) {
        super(IpNumber.TCP);
        this.dnsEngine = dnsEngine;
        this.socketChannelFactory = factory;
        final Type type = Types.resolveType(TcpPacketHandler.class.getTypeParameters()[0], getClass());
        Preconditions.checkState(type instanceof Class<?>, "Can't resolve %s IpPacket Class", TcpPacketHandler.class.getName());
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final T rawIpPacket) throws Exception {
        final T ipPacket;
        try {
            ipPacket = prepare(rawIpPacket);
        } catch (Exception e) {
            ctx.channel().writeAndFlush(newReset(rawIpPacket));
            return;
        }
        final IpHeader ipHeader = ipPacket.getHeader();
        final InetAddress srcAddr = ipHeader.getSrcAddr();
        final InetAddress dstAddr = ipHeader.getDstAddr();

        final TcpPacket tcpPacket = (TcpPacket) ipPacket.getPayload();
        final TcpHeader tcpHeader = tcpPacket.getHeader();
        final TcpPort tcpSrcPort = tcpHeader.getSrcPort();
        final TcpPort tcpDstPort = tcpHeader.getDstPort();


        final String sockKey = srcAddr.toString() + ":" + tcpSrcPort.valueAsInt() + " => " + dstAddr + ":" + tcpDstPort.valueAsInt();
        if (!tcpHeader.getRst() && !tcpHeader.getAck() && tcpHeader.getSyn()) {
            sessionMap.putIfAbsent(sockKey, create(ctx.channel(), childGroup, dnsEngine, socketChannelFactory, () -> {
                    log.info("[{}] Destroy: {}", ctx.channel().id(), sockKey);
                    sessionMap.remove(sockKey);
            }));
        }
        TcpConnection<T> tcpConnection = sessionMap.get(sockKey);
        if (null != tcpConnection) {
//            childGroup.execute(() -> {
                tcpConnection.handler(ipPacket, tcpPacket);
//            });
        }

    }

    protected T prepare(final T ipPacket) throws UnknownHostException {
        return ipPacket;
    }

    protected abstract T newReset(final T ipPacket);

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

    protected abstract TcpConnection<T> create(Channel parent, EventLoopGroup childGroup, DnsEngine dnsEngine, SocketChannelFactory socketChannelFactory, Runnable destroyCallback);

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        super.exceptionCaught(ctx, cause);
    }
}
