package com.github.pangolin.routing.acceptor.tun.net.handler.tcp;

import com.github.pangolin.routing.acceptor.tun.fakedns.DnsEngine;
import com.github.pangolin.routing.acceptor.tun.net.handler.IpPacketHandler;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpDemultiplexer;
import com.github.pangolin.routing.support.SocketChannelFactory;
import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import freework.reflect.Types;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import java.lang.reflect.Type;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.pcap4j.packet.IpPacket;
import org.pcap4j.packet.IpPacket.IpHeader;
import org.pcap4j.packet.TcpPacket;
import org.pcap4j.packet.TcpPacket.TcpHeader;
import org.pcap4j.packet.namednumber.IpNumber;
import org.pcap4j.packet.namednumber.TcpPort;

@Slf4j
public abstract class TcpDemultiplexHandler<T extends IpPacket> extends IpPacketHandler<T> {
    private final DnsEngine dnsEngine;
    private final SocketChannelFactory socketChannelFactory;
    private final EventLoopGroup childGroup = new NioEventLoopGroup();

    private final Map<String, TcpDemultiplexer> sessionMap = Maps.newConcurrentMap();

    public TcpDemultiplexHandler(final DnsEngine dnsEngine, final SocketChannelFactory factory) {
        super(IpNumber.TCP);
        this.dnsEngine = dnsEngine;
        this.socketChannelFactory = factory;
        final Type type = Types.resolveType(TcpDemultiplexHandler.class.getTypeParameters()[0], getClass());
        Preconditions.checkState(type instanceof Class<?>, "Can't resolve %s IpPacket Class", TcpDemultiplexHandler.class.getName());
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
        TcpDemultiplexer<T> tcpDemultiplexer = sessionMap.get(sockKey);
        if (null != tcpDemultiplexer) {
            childGroup.execute(() -> {
                tcpDemultiplexer.handler(ipPacket, tcpPacket);
            });
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

    protected abstract TcpDemultiplexer<T> create(Channel parent, EventLoopGroup childGroup, DnsEngine dnsEngine, SocketChannelFactory socketChannelFactory, Runnable destroyCallback);

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        super.exceptionCaught(ctx, cause);
    }

}
