package com.github.pangolin.routing.handler.internal.server.socks5.server;

import com.github.pangolin.routing.handler.internal.server.Socks5ProxyServerHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.socksx.v5.*;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import lombok.extern.slf4j.Slf4j;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;

@Slf4j
public class Socks5ProxyServerHandler2 extends Socks5ProxyServerHandler {
    private final Socks5DatagramServerHandler udpServerHandler;

    public Socks5ProxyServerHandler2(final Socks5DatagramServerHandler udpServerHandler) {
        this.udpServerHandler = udpServerHandler;
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        final InetSocketAddress clientAddress = (InetSocketAddress) ctx.channel().remoteAddress();
        if (msg instanceof Socks5CommandRequest) {
            final Socks5CommandRequest request = (Socks5CommandRequest) msg;
            final Socks5CommandType type = request.type();

            final int port = request.dstPort();
            final String address = request.dstAddr();
            final Socks5AddressType addressType = request.dstAddrType();

            log.info("[SOCKS5] Received {} {} request => {}:{}", clientAddress, type.toString(), address, port);

            if (Socks5CommandType.UDP_ASSOCIATE.equals(type)) {
                final InetSocketAddress serverAddress = (InetSocketAddress) ctx.channel().localAddress();
                final String udpServerHost = serverAddress.getHostString();
                final int udpServerPort = serverAddress.getPort();

                ctx.channel().closeFuture().addListener(new GenericFutureListener<Future<? super Void>>() {
                    @Override
                    public void operationComplete(final Future<? super Void> future) throws Exception {
                        udpServerHandler.removeFromWhitelist(clientAddress);
                    }
                });

                if (serverAddress.isUnresolved()) {
                    udpServerHandler.addToWhitelist(clientAddress);
                    ctx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, Socks5AddressType.DOMAIN, udpServerHost, udpServerPort));
                    return;
                }
                InetAddress inetAddress = serverAddress.getAddress();
                if (inetAddress instanceof Inet4Address) {
                    udpServerHandler.addToWhitelist(clientAddress);
                    ctx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, Socks5AddressType.IPv4, udpServerHost, udpServerPort));
                    return;
                }
                if (inetAddress instanceof Inet6Address) {
                    udpServerHandler.addToWhitelist(clientAddress);
                    ctx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, Socks5AddressType.IPv6, udpServerHost, udpServerPort));
                    return;
                }
            }
        }
        super.channelRead(ctx, msg);
    }
}