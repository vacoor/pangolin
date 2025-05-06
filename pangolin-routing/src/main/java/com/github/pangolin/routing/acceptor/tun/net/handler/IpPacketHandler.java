package com.github.pangolin.routing.acceptor.tun.net.handler;

import static org.pcap4j.packet.IpPacket.IpHeader;

import com.google.common.base.Preconditions;
import freework.reflect.Types;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.ReferenceCountUtil;
import org.pcap4j.packet.IpPacket;
import org.pcap4j.packet.namednumber.IpNumber;

import java.lang.reflect.Type;

/**
 *
 */
public abstract class IpPacketHandler<T extends IpPacket> extends ChannelDuplexHandler {
    private final Class<T> ipPacketType;
    private final IpNumber ipProtocol;

    @SuppressWarnings("unchecked")
    public IpPacketHandler(final IpNumber ipProtocol) {
        final Type type = Types.resolveType(IpPacketHandler.class.getTypeParameters()[0], getClass());
        Preconditions.checkState(type instanceof Class<?>, "Can't resolve %s IpPacket Class", IpPacketHandler.class.getName());
        this.ipPacketType = (Class<T>) type;
        this.ipProtocol = ipProtocol;
    }

    public IpPacketHandler(final Class<T> ipPacketType, final IpNumber ipProtocol) {
        this.ipPacketType = ipPacketType;
        this.ipProtocol = ipProtocol;
    }

    protected boolean accept(final Object msg) {
        if (!ipPacketType.isInstance(msg)) {
            return false;
        }
        final T ipPacket = ipPacketType.cast(msg);
        final IpHeader iph = ipPacket.getHeader();
        return null != iph.getProtocol() && iph.getProtocol().equals(ipProtocol);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @SuppressWarnings("unchecked")
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        boolean release = true;
        try {
            if (msg instanceof IpPacket && accept(msg)) {
                channelRead0(ctx, (T) msg);
            } else {
                release = false;
                ctx.fireChannelRead(msg);
            }
        } finally {
            if (release) {
                ReferenceCountUtil.release(msg);
            }
        }
    }

    protected abstract void channelRead0(final ChannelHandlerContext ctx, final T ipPacket) throws Exception;

}
