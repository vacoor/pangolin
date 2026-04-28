package com.github.pangolin.agent;

import com.github.pangolin.agent.util.Channels2;
import com.github.pangolin.handler.TcpOverWebSocketDecodeHandler;
import com.github.pangolin.handler.TcpOverWebSocketEncodeHandler;
import com.github.pangolin.util.Channels;
import com.github.pangolin.util.Util;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.flow.FlowControlHandler;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.CharsetUtil;
import io.netty.util.concurrent.Promise;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import static com.github.pangolin.util.Constants.*;

/**
 * WebSocket 回传通道代理.
 */
@Slf4j
public class WebSocketBridgeAgentHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
    private static final String PROTO_AGENT_BACKHAUL = "BACKHAUL";
    private static final SecureRandom RNG = new SecureRandom();

    /**
     * Agent name.
     */
    private final String name;
    private final byte[] secretKey;

    /**
     * WebSocket agent handshaker.
     */
    private final WebSocketClientHandshaker handshaker;

    /**
     * WebSocket agent handshake http headers.
     */
    private final HttpHeaders customHttpHeaders;

    public WebSocketBridgeAgentHandler(final String name,
                                       final byte[] secretKey,
                                       final WebSocketClientHandshaker handshaker,
                                       final HttpHeaders customHttpHeaders) {
        this.name = name;
        this.secretKey = secretKey;
        this.handshaker = handshaker;
        this.customHttpHeaders = customHttpHeaders;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void userEventTriggered(final ChannelHandlerContext ctx, final Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            ctx.writeAndFlush(new PingWebSocketFrame());
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void channelActive(final ChannelHandlerContext ctx) throws Exception {
        final InetSocketAddress addr = ((InetSocketAddress) ctx.channel().localAddress());
        final int port = addr.getPort();
        final ByteBuffer addrBytes = CharsetUtil.UTF_8.encode(addr.getHostString());
        final ByteBuffer nameBytes = CharsetUtil.UTF_8.encode(name);

        final ByteBuf buf = ctx.alloc().buffer(4 + addrBytes.remaining() + 2 + 1 + nameBytes.remaining() + 8 + 8 + 32);
        try {
            buf.writeByte(VER_1);

            buf.writeByte(CMD_SERVICE);
            buf.writeByte(RSV);

            buf.writeByte(ATYPE_DOMAIN);
            buf.writeByte(addrBytes.remaining());
            buf.writeBytes(addrBytes);

            buf.writeShort(port);
            buf.writeByte(nameBytes.remaining());
            buf.writeBytes(nameBytes);

            writeSignature(buf, secretKey);

            final String encoded = Util.urlSafeBase64Encode(buf);
            customHttpHeaders.set("Authorization", "Bearer " + encoded);
        } finally {
            buf.release();
        }
        super.channelActive(ctx);
    }


    /**
     * {@inheritDoc}
     */
    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final WebSocketFrame frame) throws Exception {
        if (!(frame instanceof BinaryWebSocketFrame)) {
            ctx.fireChannelRead(frame.retain());
            return;
        }

        final ByteBuf in = frame.content();
        final byte version = in.readByte();
        final byte command = in.readByte();
        final byte rsv = in.readByte();

        if (VER_1 == version && CMD_CONNECT == command) {
            final InetSocketAddress destination = Util.readSocketAddress(in, false);
            final String id = in.readString(in.readUnsignedByte(), CharsetUtil.UTF_8);

            final WebSocketClientHandshaker backhaulHandshaker = newBackhaulHandshaker(id, ctx);
            final ChannelPromise backhaulPromise = ctx.newPromise().addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(final ChannelFuture future) throws Exception {
                    if (!future.isSuccess()) {
                        ctx.writeAndFlush(new BinaryWebSocketFrame(newReply(ctx, id, rsv, REPLY_HOST_UNREACHABLE)));
                    }
                }
            });

            pipe(destination, backhaulHandshaker, ctx.channel().eventLoop(), backhaulPromise);
        }
    }

    /**
     * {@inheritDoc}
     */

    private WebSocketClientHandshaker newBackhaulHandshaker(final String id, final ChannelHandlerContext ctx) throws NoSuchAlgorithmException, InvalidKeyException {
        // BACKHAUL handshake token: append auth tail (TS / NONCE / HMAC)
        final ByteBuf buf = writeSignature(newReply(ctx, id, (byte) 0, REPLY_SUCCESS), secretKey);
        try {
            final DefaultHttpHeaders backhaulHeaders = new DefaultHttpHeaders();
            final String encoded = Util.urlSafeBase64Encode(buf);
            backhaulHeaders.set("Authorization", "Bearer " + encoded);

            return WebSocketClientHandshakerFactory.newHandshaker(
                    handshaker.uri(), handshaker.version(), PROTO_AGENT_BACKHAUL,
                    false, backhaulHeaders, handshaker.maxFramePayloadLength()
            );
        } finally {
            buf.release();
        }
    }

    /*
     * server <--ws--> agent <--socket--> target
     * or downgrade to:
     * server <--socket--> agent <--socket--> target
     */
    private static ChannelFuture pipe(final SocketAddress destination,
                                      final WebSocketClientHandshaker backhaulHandhaker,
                                      final EventLoopGroup brGroup, final ChannelPromise promise) throws InterruptedException {
        final ChannelFutureListener propagationOnFailure = propagationOnFailure(promise);
        return Channels.open(destination, false, brGroup, new ChannelInboundHandlerAdapter() {
            @Override
            public void channelActive(final ChannelHandlerContext destinationCtx) throws Exception {
                Channels2.openWs(backhaulHandhaker, brGroup, new ChannelInboundHandlerAdapter() {
                    private boolean handshakeComplete;

                    @Override
                    public void handlerAdded(final ChannelHandlerContext backhaulCtx) throws Exception {
                        final ChannelPipeline cp = backhaulCtx.pipeline();
                        if (null == cp.get(FlowControlHandler.class)) {
                            final ChannelHandlerContext wsCtx = cp.context(WebSocketClientProtocolHandler.class);
                            cp.addBefore(wsCtx.name(), FlowControlHandler.class.getName(), new FlowControlHandler());
                        }
                    }

                    @Override
                    public void userEventTriggered(final ChannelHandlerContext backhaulCtx, final Object evt) throws Exception {
                        if (WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE.equals(evt)) {
                            handshakeComplete = true;
                            backhaulCtx.channel().config().setAutoRead(false);

                            /*-
                             * server <--ws--> agent <--socket--> target
                             */
                            destinationCtx.pipeline().replace(destinationCtx.name(), "destination-br", new TcpOverWebSocketEncodeHandler(backhaulCtx));
                            backhaulCtx.pipeline().replace(backhaulCtx.name(), "backhaul-br", new TcpOverWebSocketDecodeHandler(destinationCtx));

                            backhaulCtx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(new ChannelFutureListener() {
                                @Override
                                public void operationComplete(final ChannelFuture future) throws Exception {
                                    if (future.isSuccess()) {
                                        destinationCtx.channel().config().setAutoRead(true);
                                        backhaulCtx.channel().config().setAutoRead(true);
                                    }
                                }
                            });
                            destinationCtx.channel().closeFuture().addListener(new ChannelFutureListener() {
                                @Override
                                public void operationComplete(final ChannelFuture future) throws Exception {
                                    backhaulCtx.channel().close();
                                }
                            });
                        }
                    }

                    @Override
                    public void channelInactive(final ChannelHandlerContext backhaulCtx) throws Exception {
                        if (!handshakeComplete) {
                            promise.tryFailure(new IOException("Backhaul connection closed before handshake completed"));
                        }
                        closeDestinationIfHandshakeIncomplete();
                        super.channelInactive(backhaulCtx);
                    }

                    @Override
                    public void exceptionCaught(final ChannelHandlerContext backhaulCtx, final Throwable cause) throws Exception {
                        promise.tryFailure(cause);
                        closeDestinationIfHandshakeIncomplete();
                        backhaulCtx.close();
                    }

                    private void closeDestinationIfHandshakeIncomplete() {
                        if (!handshakeComplete && destinationCtx.channel().isActive()) {
                            destinationCtx.channel().close();
                        }
                    }
                }).addListener(propagationOnFailure).addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(final ChannelFuture future) throws Exception {
                        if (!future.isSuccess()) {
                            // can't open backhaul connection.
                            future.channel().close();
                            if (destinationCtx.channel().isActive()) {
                                destinationCtx.channel().close();
                            }
                        }
                    }
                });
            }
        }).addListener(propagationOnFailure).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
    }

    private static ChannelFutureListener propagationOnFailure(final Promise<?> promise) {
        return new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future) throws Exception {
                if (!future.isSuccess()) {
                    promise.tryFailure(future.cause());
                }
            }
        };
    }

    private ByteBuf newReply(final ChannelHandlerContext ctx,
                             final String id, final byte rsv, final byte status) {
        final ByteBuffer idBytes = CharsetUtil.UTF_8.encode(id);
        final ByteBuf reply = ctx.alloc().buffer(4 + IPv4_ADDR_SIZE + 2 + 1 + idBytes.remaining() + 8 + 8 + 32);
        reply.writeByte(VER_1);
        reply.writeByte(status);
        reply.writeByte(rsv);
        reply.writeByte(ATYPE_IPv4);
        reply.writeInt(0);
        reply.writeShort(0);
        reply.writeByte(idBytes.remaining());
        reply.writeBytes(idBytes);
        return reply;
    }

    private ByteBuf writeSignature(final ByteBuf buf, final byte[] secretKey) throws NoSuchAlgorithmException, InvalidKeyException {
        buf.writeLong(System.currentTimeMillis() / 1000L);  // TS: unix seconds
        final byte[] nonce = new byte[8];
        RNG.nextBytes(nonce);
        buf.writeBytes(nonce);                              // NONCE: 8B random
        buf.writeBytes(Util.hmacSha256(buf, buf.readerIndex(), buf.readableBytes(), secretKey));
        return buf;
    }
}
