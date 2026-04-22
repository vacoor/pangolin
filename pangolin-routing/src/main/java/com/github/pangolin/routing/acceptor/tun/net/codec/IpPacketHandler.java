package com.github.pangolin.routing.acceptor.tun.net.codec;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.ReferenceCountUtil;

/**
 * Base handler that filters incoming {@link IpPacketBuf} messages by protocol number
 * and delivers them typed as {@code P} to the subclass.
 *
 * @param <P> the specific {@link IpPacketBuf} subtype this handler expects
 */
public abstract class IpPacketHandler<P extends IpPacketBuf> extends ChannelDuplexHandler {

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
    @SuppressWarnings("unchecked")
    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        if (msg instanceof IpPacketBuf) {
            final IpPacketBuf pkt = (IpPacketBuf) msg;
            if (accept(pkt)) {
                try {
                    channelRead0(ctx, (P) pkt);
                } finally {
                    ReferenceCountUtil.release(pkt);
                }
                return;
            }
        }
        ctx.fireChannelRead(msg);
    }

    protected abstract void channelRead0(final ChannelHandlerContext ctx, final P pkt) throws Exception;
}
