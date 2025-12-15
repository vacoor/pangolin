package com.github.pangolin.agent;

import com.github.pangolin.agent.util.Channels2;
import com.github.pangolin.handler.TcpInboundRedirectHandler;
import com.github.pangolin.handler.TcpOverWebSocketDecodeHandler;
import com.github.pangolin.handler.TcpOverWebSocketEncodeHandler;
import com.github.pangolin.util.Channels;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.base64.Base64;
import io.netty.handler.codec.base64.Base64Dialect;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.flow.FlowControlHandler;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.CharsetUtil;
import io.netty.util.concurrent.Promise;
import lombok.extern.slf4j.Slf4j;

import java.net.*;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

/**
 * WebSocket 回传通道代理.
 */
@Slf4j
public class WebSocketBridgeAgentHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
    private static final String AGENT_VERSION = "1.2";
    private static final String PROTO_AGENT_BACKHAUL = "BACKHAUL";

    private static final byte IPv4_ADDR_SIZE = 4;
    private static final byte IPv6_ADDR_SIZE = 16;

    private static final byte VER_1 = 0x01;

    private static final byte CMD_CONNECT = 0x01;

    private static final byte ATYPE_IPv4 = 0x01;
    private static final byte ATYPE_DOMAIN = 0x03;
    private static final byte ATYPE_IPv6 = 0x04;

    private static final byte REPLY_SUCCESS = 0x00;
    private static final byte REPLY_FAILURE = 0x01;
    private static final byte REPLY_FORBIDDEN = 0x02;
    private static final byte REPLY_NETWORK_UNREACHABLE = 0x03;
    private static final byte REPLY_HOST_UNREACHABLE = 0x04;
    private static final byte REPLY_CONNECTION_REFUSED = 0x05;
    private static final byte REPLY_TTL_EXPIRED = 0x06;
    private static final byte REPLY_COMMAND_UNSUPPORTED = 0x07;
    private static final byte REPLY_ADDRESS_UNSUPPORTED = 0x08;


    private enum State {SUSPENDED, INITIALIZING, INITIALIZED}

    /**
     * Agent name.
     */
    private final String name;

    /**
     * WebSocket agent handshaker.
     */
    private final WebSocketClientHandshaker handshaker;

    /**
     * WebSocket agent handshake http headers.
     */
    private final HttpHeaders customHttpHeaders;

    /**
     * WebSocket agent state.
     */
    private final AtomicReference<State> state = new AtomicReference<>(State.SUSPENDED);

    public WebSocketBridgeAgentHandler(final String name, final WebSocketClientHandshaker handshaker, final HttpHeaders customHttpHeaders) {
        this.name = name;
        this.handshaker = handshaker;
        this.customHttpHeaders = customHttpHeaders;
    }

    @Override
    public void channelActive(final ChannelHandlerContext ctx) throws Exception {
        if (null != customHttpHeaders) {
            final InetSocketAddress localAddress = (InetSocketAddress) ctx.channel().localAddress();
            customHttpHeaders.set("X-Node-Name", name);
            customHttpHeaders.set("X-Node-Version", AGENT_VERSION);
            customHttpHeaders.set("X-Node-Intranet", localAddress.getHostString());

            final Channel ch = ctx.channel();
            final InetSocketAddress addr = ((InetSocketAddress) ch.localAddress());

            final int port = addr.getPort();
            final ByteBuffer addrBytes = CharsetUtil.UTF_8.encode(addr.getHostString());
            final ByteBuffer nameBytes = CharsetUtil.UTF_8.encode(name);
            final ByteBuffer versionBytes = CharsetUtil.UTF_8.encode(AGENT_VERSION);

            final ByteBuf buf = ctx.alloc().buffer(4 + addrBytes.remaining() + 2 + 1 + nameBytes.remaining());
            buf.writeByte(VER_1);

            buf.writeByte(0xFF);
            buf.writeByte(0);

            buf.writeByte(ATYPE_DOMAIN);
            buf.writeByte(addrBytes.remaining());
            buf.writeBytes(addrBytes);

            buf.writeShort(port);
            buf.writeByte(nameBytes.remaining());
            buf.writeBytes(nameBytes);
            buf.writeByte(AGENT_VERSION.length());
            buf.writeBytes(versionBytes);

            final String token = Base64.encode(buf, Base64Dialect.URL_SAFE).toString(CharsetUtil.UTF_8);
            customHttpHeaders.set("Authorization", "Bearer " + token);
        }
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
        state.set(State.SUSPENDED);
        super.channelInactive(ctx);
    }

    @Override
    public void userEventTriggered(final ChannelHandlerContext ctx, final Object evt) throws Exception {
        if (WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_ISSUED.equals(evt)) {
            state.compareAndSet(State.SUSPENDED, State.INITIALIZING);
        } else if (WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE.equals(evt)) {
            state.compareAndSet(State.INITIALIZING, State.INITIALIZED);
        } else if (evt instanceof IdleStateEvent) {
            if (ctx.channel().isActive() && State.INITIALIZED.equals(state.get())) {
                ctx.writeAndFlush(new PingWebSocketFrame());
            }
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final WebSocketFrame frame) throws Exception {
        if (!(frame instanceof BinaryWebSocketFrame)) {
            ctx.fireChannelRead(frame.retain());
            return;
        }

        final ByteBuf in = frame.content();
        final byte version = in.readByte();
        final String id = in.readCharSequence(in.readUnsignedByte(), CharsetUtil.UTF_8).toString();
        final byte command = in.readByte();
        final byte rsv = in.readByte();

        if (CMD_CONNECT == command) {
            final InetAddress address = parseAddress(in);
            final int port = in.readUnsignedShort();
            final InetSocketAddress destination = new InetSocketAddress(address, port);

            final WebSocketClientHandshaker backhaulHandshaker = newBackhaulHandshaker(id, ctx);
            final ChannelPromise backhaulPromise = ctx.newPromise().addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(final ChannelFuture future) throws Exception {
                    if (!future.isSuccess()) {
                        ctx.writeAndFlush(new BinaryWebSocketFrame(
                                newReply(ctx, id, rsv, REPLY_HOST_UNREACHABLE)
                        ));
                    }
                }
            });

            pipe(destination, backhaulHandshaker, ctx.channel().eventLoop(), backhaulPromise, 0 != rsv);
        }
    }

    private InetAddress parseAddress(final ByteBuf in) throws UnknownHostException {
        final byte addressType = in.readByte();
        if (ATYPE_IPv4 == addressType) {
            final byte[] addr = ByteBufUtil.getBytes(in.readBytes(IPv4_ADDR_SIZE));
            return InetAddress.getByAddress(addr);
        } else if (ATYPE_DOMAIN == addressType) {
            final String domain = in.readCharSequence(in.readUnsignedByte(), CharsetUtil.UTF_8).toString();
            return InetAddress.getByName(domain);
        } else if (ATYPE_IPv6 == addressType) {
            final byte[] addr = ByteBufUtil.getBytes(in.readBytes(IPv6_ADDR_SIZE));
            return InetAddress.getByAddress(addr);
        }
        throw new UnknownHostException("address type: " + addressType);
    }

    private ByteBuf newReply(final ChannelHandlerContext ctx,
                             final String id, final byte rsv, final byte status) {
        final ByteBuffer idBytes = CharsetUtil.UTF_8.encode(id);
        final ByteBuf reply = ctx.alloc().buffer(1 + idBytes.remaining() + 4 + IPv4_ADDR_SIZE + 2);
        reply.writeByte(VER_1);
        reply.writeByte(idBytes.remaining());
        reply.writeBytes(idBytes);
        reply.writeByte(status);
        reply.writeByte(rsv);
        reply.writeByte(ATYPE_IPv4);
        reply.writeInt(0);
        reply.writeShort(0);
        return reply;
    }

    private WebSocketClientHandshaker newBackhaulHandshaker(final String id, final ChannelHandlerContext ctx) {
        final URI uri = handshaker.uri();
        final String basePath = uri.getPath();
        final String backhaulPath = basePath.endsWith("/") ? basePath + id : basePath + "/" + id;
        final String endpoint = uri.getScheme() + "://" + uri.getHost() + ":" + uri.getPort() + backhaulPath;
        final URI backhaulWebSocketUri = URI.create(endpoint + "?id=" + id);

        ByteBuf buf = newReply(ctx, id, (byte) 0, REPLY_SUCCESS);

        final String token = Base64.encode(buf, Base64Dialect.URL_SAFE).toString(CharsetUtil.UTF_8);
        final DefaultHttpHeaders backhaulHeaders = new DefaultHttpHeaders();
        backhaulHeaders.set("Authorization", "Bearer " + token);

        // backhaulHeaders.set("")
        return WebSocketClientHandshakerFactory.newHandshaker(
                backhaulWebSocketUri, handshaker.version(), PROTO_AGENT_BACKHAUL,
                false, backhaulHeaders, handshaker.maxFramePayloadLength()
        );
    }


    @Override
    public void exceptionCaught(final ChannelHandlerContext webSocketContext, final Throwable cause) throws Exception {
        log.warn("Software caused connection abort: {}", cause.getMessage(), cause);
        webSocketContext.writeAndFlush(new CloseWebSocketFrame(WebSocketCloseStatus.INTERNAL_SERVER_ERROR, cause.getMessage())).addListener(ChannelFutureListener.CLOSE);
    }


    /*
     * server <--ws--> agent <--socket--> target
     * or downgrade to:
     * server <--socket--> agent <--socket--> target
     */
    public static ChannelFuture pipe(final SocketAddress destination,
                                     final WebSocketClientHandshaker backhaulHandhaker,
                                     final EventLoopGroup brGroup, final ChannelPromise promise,
                                     final boolean downgrade) throws InterruptedException {
        final ChannelFutureListener propagationOnFailure = propagationOnFailure(promise);
        return Channels.open(destination, false, brGroup, new ChannelInboundHandlerAdapter() {
            @Override
            public void channelActive(final ChannelHandlerContext destinationCtx) throws Exception {
                Channels2.openWs(backhaulHandhaker, brGroup, new ChannelInboundHandlerAdapter() {
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
                            backhaulCtx.channel().config().setAutoRead(false);

                            if (!downgrade) {
                                /*-
                                 * server <--ws--> agent <--socket--> target
                                 */
                                destinationCtx.pipeline().replace(destinationCtx.name(), "destination-br", new TcpOverWebSocketEncodeHandler(backhaulCtx));
                                backhaulCtx.pipeline().replace(backhaulCtx.name(), "backhaul-br", new TcpOverWebSocketDecodeHandler(destinationCtx));
                            } else {
                                /*-
                                 * server <--ws--> agent <--socket--> target
                                 * downgrade to:
                                 * server <--socket--> agent <--socket--> target
                                 */
                                destinationCtx.pipeline().replace(destinationCtx.name(), null, new TcpInboundRedirectHandler(backhaulCtx));
                                backhaulCtx.pipeline().replace(backhaulCtx.name(), null, new TcpInboundRedirectHandler(destinationCtx));
                            }

                            backhaulCtx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(new ChannelFutureListener() {
                                @Override
                                public void operationComplete(final ChannelFuture future) throws Exception {
                                    if (future.isSuccess()) {
                                        if (downgrade) {
                                            /*-
                                             * remove websocket codec.
                                             */
                                            // backhaulCtx.pipeline().remove(WebSocketServerHandshakeNegotiationHandler.WebSocketCtrlFrameHandler.class);
                                            backhaulCtx.pipeline().remove(Utf8FrameValidator.class);
                                            backhaulCtx.pipeline().remove("wsencoder");
                                            backhaulCtx.pipeline().remove("wsdecoder");
                                        }

                                        destinationCtx.channel().config().setAutoRead(true);
                                        backhaulCtx.channel().config().setAutoRead(true);
                                    }
                                }
                            });
                        }
                    }
                }).addListener(propagationOnFailure).addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(final ChannelFuture future) throws Exception {
                        if (!future.isSuccess()) {
                            // can't open backhaul connection.
                            future.channel().close();
                            destinationCtx.channel().close();
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

}