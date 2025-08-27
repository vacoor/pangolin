package com.github.pangolin.routing.support.handler.client;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.socksx.v4.DefaultSocks4CommandRequest;
import io.netty.handler.codec.socksx.v4.Socks4ClientDecoder;
import io.netty.handler.codec.socksx.v4.Socks4ClientEncoder;
import io.netty.handler.codec.socksx.v4.Socks4CommandResponse;
import io.netty.handler.codec.socksx.v4.Socks4CommandStatus;
import io.netty.handler.codec.socksx.v4.Socks4CommandType;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

public class Socks4ProxyHandler extends AbstractProxyHandler {
    private static final String NONE = "";

    private String uid;

    public Socks4ProxyHandler(final SocketAddress proxyAddress) {
        this(proxyAddress, NONE);
    }

    public Socks4ProxyHandler(final SocketAddress proxyAddress, final String uid) {
        super(proxyAddress);
        this.uid = uid;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
        final ChannelPipeline cp = ctx.pipeline();
        if (null == cp.get(Socks4ClientDecoder.class)) {
            cp.addBefore(ctx.name(), null, new Socks4ClientDecoder());
        }
        if (null == cp.get(Socks4ClientEncoder.class)) {
            cp.addBefore(ctx.name(), null, Socks4ClientEncoder.INSTANCE);
        }
        super.handlerAdded(ctx);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected ChannelPromise handshake(final ChannelHandlerContext ctx, final ChannelPromise handshakePromise) throws Exception {
        final InetSocketAddress destination = destinationAddress();
        final String addressToUse = destination.isUnresolved() ? destination.getHostString() : destination.getAddress().getHostAddress();
        final String usernameToUse = null != uid ? uid : NONE;

        ctx.writeAndFlush(new DefaultSocks4CommandRequest(Socks4CommandType.CONNECT, addressToUse, destination.getPort(), usernameToUse));
        return handshakePromise;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean handshakeRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        if (msg instanceof Socks4CommandResponse) {
            final Socks4CommandResponse res = (Socks4CommandResponse) msg;
            if (Socks4CommandStatus.SUCCESS.equals(res.status())) {
                ctx.pipeline().remove(Socks4ClientEncoder.class);
                ctx.pipeline().remove(Socks4ClientDecoder.class);
                return true;
            }
            throw new ConnectException("status = " + res.status());
        }
        throw new ConnectException("handshake error");
    }

}