package com.github.pangolin.routing.acceptor.tun.net.handler.support;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;

/**
 * Base handler that filters incoming {@link IpPacketBuf} messages by protocol number.
 */
public abstract class IpPacketHandler extends ChannelDuplexHandler {

    private final byte ipProtocol;

    public IpPacketHandler(final byte ipProtocol) {
        this.ipProtocol = ipProtocol;
    }

    protected boolean accept(final IpPacketBuf pkt) {
        return pkt.nextProto() == ipProtocol;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        if (msg instanceof IpPacketBuf) {
            final IpPacketBuf pkt = (IpPacketBuf) msg;
            if (accept(pkt)) {
                try {
                    channelRead0(ctx, pkt);
                } finally {
                    pkt.release();
                }
                return;
            }
        }
        ctx.fireChannelRead(msg);
    }

    protected abstract void channelRead0(final ChannelHandlerContext ctx, final IpPacketBuf pkt) throws Exception;
}
