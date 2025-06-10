package com.github.pangolin.agent;

import com.github.pangolin.handler.TcpInboundRedirectHandler;
import com.github.pangolin.handler.TcpOverWebSocketDecodeHandler;
import com.github.pangolin.handler.TcpOverWebSocketEncodeHandler;
import com.github.pangolin.util.Channels;
import com.github.pangolin.agent.util.Channels2;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.Utf8FrameValidator;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import io.netty.handler.flow.FlowControlHandler;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.CharsetUtil;
import io.netty.util.concurrent.Promise;
import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

/**
 * WebSocket 回传通道代理.
 */
@Slf4j
public class WebSocketBridgeAgentHandler2 extends SimpleChannelInboundHandler<WebSocketFrame> {
    private static final String AGENT_VERSION = "1.1";
    private static final String BACKHAUL_PROTOCOL = "PASSIVE";

    private static final byte IPv4_ADDR_SIZE = 4;
    private static final byte IPv6_ADDR_SIZE = 16;

    private static final byte VER_1_1 = 0x01;

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

    public WebSocketBridgeAgentHandler2(final String name, final WebSocketClientHandshaker handshaker, final HttpHeaders customHttpHeaders) {
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

        final InetAddress address = parseAddress(in);
        final int port = in.readUnsignedShort();
        final InetSocketAddress destination = new InetSocketAddress(address, port);

        final WebSocketClientHandshaker backhaulHandshaker = newBackhaulHandshaker(id);
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

        if (CMD_CONNECT == command) {
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
        reply.writeByte(VER_1_1);
        reply.writeByte(idBytes.remaining());
        reply.writeBytes(idBytes);
        reply.writeByte(status);
        reply.writeByte(rsv);
        reply.writeByte(ATYPE_IPv4);
        reply.writeInt(0);
        reply.writeShort(0);
        return reply;
    }

    private WebSocketClientHandshaker newBackhaulHandshaker(final String id) {
        final URI uri = handshaker.uri();
        final String endpoint = uri.getScheme() + "://" + uri.getHost() + ":" + uri.getPort() + uri.getPath();
        final URI backhaulWebSocketUri = URI.create(endpoint + "?id=" + id);
        return newHandshaker(backhaulWebSocketUri, BACKHAUL_PROTOCOL);
    }

    private WebSocketClientHandshaker newHandshaker(final URI webSocketEndpoint, final String subprotocol) {
        return WebSocketClientHandshakerFactory.newHandshaker(
                webSocketEndpoint, handshaker.version(), subprotocol,
                false, customHttpHeaders, handshaker.maxFramePayloadLength()
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

    private static final int MAX_HTTP_CONTENT_LENGTH = 1024 * 1024 * 8;

    public static void main(String[] args) throws InterruptedException {
        final WebSocketClientHandshaker handshaker = WebSocketClientHandshakerFactory.newHandshaker(
                URI.create("ws://localhost:8888"), WebSocketVersion.V13, "x", true, new DefaultHttpHeaders()
        );

        Channels.listen(
                null, 9999, true, new NioEventLoopGroup(), new NioEventLoopGroup(),
                new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(final SocketChannel ch) throws Exception {
                        final ChannelPipeline pipeline = ch.pipeline();
//                if (null != sslContext) {
//                    pipeline.addLast(sslContext.newHandler(ch.alloc()));
//                }
                        pipeline.addLast(
                                new HttpServerCodec(),
                                new HttpObjectAggregator(MAX_HTTP_CONTENT_LENGTH),
                        /*- 浏览器似乎处理压缩有问题(permessage-deflate).
                        new WebSocketServerCompressionHandler(),
                        new WebSocketServerProtocolHandler(endpointPath, ALL_PROTOCOLS, true, 65536, true, true),
                        */
                                new WebSocketServerProtocolHandler("/ws", "*", false, 65536, true, true),
                                new WebSocketBridgeAgentHandler2("xx", handshaker, new DefaultHttpHeaders())
//                        new WebSocketBackhaulTunnelServerHandler(webSocketBackhaulTunnelServerEngine, webSocketBackhaulTunnelServerForwarder)
//                        new WebSocketBackhaulTunnelServerHandler2(endpointPath, "*", webSocketBackhaulTunnelServerEngine, webSocketBackhaulTunnelServerForwarder)
                        );
                    }
                }).sync().channel().closeFuture().sync();
    }
}