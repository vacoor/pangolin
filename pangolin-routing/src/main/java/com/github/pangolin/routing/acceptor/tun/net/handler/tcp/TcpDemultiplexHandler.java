package com.github.pangolin.routing.acceptor.tun.net.handler.tcp;

import com.github.pangolin.routing.acceptor.tun.fakedns.DnsEngine;
import com.github.pangolin.routing.acceptor.tun.net.handler.IpPacketHandler;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpDemultiplexer;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpState;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.tcp_request_sock;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.v2.SockCommon;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.v2.TcpSock;
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
import java.util.Collection;
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
    private final Map<String, tcp_request_sock> requestMap = Maps.newConcurrentMap();
    private final Map<String, TcpSock> establishedMap = Maps.newConcurrentMap();

    public TcpDemultiplexHandler(final DnsEngine dnsEngine, final SocketChannelFactory factory) {
        super(IpNumber.TCP);
        this.dnsEngine = dnsEngine;
        this.socketChannelFactory = factory;
        final Type type = Types.resolveType(TcpDemultiplexHandler.class.getTypeParameters()[0], getClass());
        Preconditions.checkState(type instanceof Class<?>, "Can't resolve %s IpPacket Class", TcpDemultiplexHandler.class.getName());
    }

    public Collection<TcpDemultiplexer> getConnections() {
        return sessionMap.values();
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


        final String sockKey = srcAddr.getHostAddress() + ":" + tcpSrcPort.valueAsInt() + " => " + dstAddr.getHostAddress() + ":" + tcpDstPort.valueAsInt();


        final String listenKey = "";
        if (!tcpHeader.getRst() && !tcpHeader.getAck() && tcpHeader.getSyn()) {
            sessionMap.putIfAbsent(listenKey, create(ctx.channel(), childGroup, dnsEngine, socketChannelFactory, () -> {
                    log.info("[{}] Destroy: {}", ctx.channel().id(), listenKey);
//                    sessionMap.remove(listenKey);
                establishedMap.remove(sockKey);
            }));
        }
        TcpDemultiplexer<T> tcpDemultiplexer = sessionMap.get(listenKey);

        SockCommon sk = establishedMap.get(sockKey);
        if (null != sk) {
            int a = 0;
        }
        if (null != sk) {
//            log.info("[X!ESTABLISHED] -> {}", sockKey);
        } else {
            sk = requestMap.get(sockKey);
            if (null != sk) {
//                log.info("[X!ESTABLISHING] -> {}", sockKey);

                tcp_request_sock request_sock = (tcp_request_sock) sk;
                TcpSock tcpSock = tcpDemultiplexer.tcp_check_req(request_sock, tcpPacket);
                // TODO add to established.
                tcpSock.state.set(TcpState.TCP_SYN_RECV);
                moveEstablished(request_sock, tcpSock);
                sk = tcpSock;
                // moveToEstablished(request_sock, tcpSock);
            } else {
                sk = tcpDemultiplexer;
                if (null != sk) {
//                    log.info("[X!HANDSHAKE] -> {}", sockKey);
                }
            }
        }

        if (null != tcpDemultiplexer) {
            SockCommon finalTcpSock = sk;
            childGroup.execute(() -> {
                TcpDemultiplexHandler t = TcpDemultiplexHandler.this;
                log.trace("{}", t);
                tcpDemultiplexer.handler(finalTcpSock, ipPacket, tcpPacket);
            });
        }

    }



    protected T prepare(final T ipPacket) throws UnknownHostException {
        return ipPacket;
    }

    protected abstract T newReset(final T ipPacket);

    protected InetAddress resolveDstAddress(final InetAddress address) throws UnknownHostException {
        final byte[] addr = address.getAddress();
        if (false) {
            return InetAddress.getByAddress("www.baidu.com", addr);
        }
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

    protected void addHalfQueue(final TcpSock parent, final tcp_request_sock request) {
        InetAddress srcAddr = request.srcAddr;
        TcpPort tcpSrcPort = request.srcPort;
        InetAddress dstAddr = request.dstAddr;
        TcpPort tcpDstPort = request.dstPort;
//        final String sockKey = srcAddr.toString() + ":" + tcpSrcPort.valueAsInt() + " => " + dstAddr + ":" + tcpDstPort.valueAsInt();
        final String sockKey = srcAddr.getHostAddress() + ":" + tcpSrcPort.valueAsInt() + " => " + dstAddr.getHostAddress() + ":" + tcpDstPort.valueAsInt();
        requestMap.putIfAbsent(sockKey, request);
    }

    protected void moveEstablished(final tcp_request_sock request, final TcpSock child) {
        InetAddress srcAddr = request.srcAddr;
        TcpPort tcpSrcPort = request.srcPort;
        InetAddress dstAddr = request.dstAddr;
        TcpPort tcpDstPort = request.dstPort;
        final String sockKey = srcAddr.getHostAddress() + ":" + tcpSrcPort.valueAsInt() + " => " + dstAddr.getHostAddress() + ":" + tcpDstPort.valueAsInt();
        requestMap.remove(sockKey, request);

        establishedMap.put(sockKey, child);
//        sessionMap.remove(sockKey);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        super.exceptionCaught(ctx, cause);
    }

}
