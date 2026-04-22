package com.github.pangolin.routing.support.handler.client;

import com.github.pangolin.routing.util.SocketUtils;
import com.google.common.collect.Maps;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.MessageToMessageCodec;
import io.netty.handler.codec.base64.Base64;
import io.netty.handler.codec.base64.Base64Dialect;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.net.*;
import java.util.List;
import java.util.Map;

/**
 * @see io.netty.resolver.NoopAddressResolverGroup#INSTANCE
 */
@Slf4j
public class WebSocketProxyHandler extends AbstractProxyHandler {
    private static final AttributeKey<WebSocketClientHandshaker> HANDSHAKER_ATTR_KEY = AttributeKey.valueOf(WebSocketClientHandshaker.class, "HANDSHAKER");

    private final URI webSocketProxyServerEndpoint;
    private final WebSocketVersion webSocketVersion;
    private final String webSocketProxyServerProtocol;
    private final HttpHeaders customHandshakeHttpHeaders;
    private final boolean allowExtensions;
    private final int maxFramePayloadLength;
    private final boolean performMasking;
    private final boolean allowMaskMismatch;
//    private final String accessKey;

    public WebSocketProxyHandler(final URI webSocketProxyServerEndpoint,
                                 final String webSocketProxyServerProtocol) {
        this(webSocketProxyServerEndpoint, WebSocketVersion.V13, webSocketProxyServerProtocol, true, 65536, true, true);
    }

    public WebSocketProxyHandler(final URI webSocketProxyServerEndpoint,
                                 final WebSocketVersion webSocketVersion,
                                 final String webSocketProxyServerProtocol,
                                 final boolean allowExtensions, final int maxFramePayloadLength,
                                 final boolean performMasking, final boolean allowMaskMismatch) {
        this(webSocketProxyServerEndpoint, webSocketVersion, webSocketProxyServerProtocol,
                EmptyHttpHeaders.INSTANCE, allowExtensions, maxFramePayloadLength, performMasking, allowMaskMismatch);
    }

    public WebSocketProxyHandler(final URI webSocketProxyServerEndpoint,
                                 final WebSocketVersion webSocketVersion,
                                 final String webSocketProxyServerProtocol,
                                 final HttpHeaders customHandshakeHttpHeaders,
                                 final boolean allowExtensions, final int maxFramePayloadLength,
                                 final boolean performMasking, final boolean allowMaskMismatch) {
        super(SocketUtils.toSocketAddress(webSocketProxyServerEndpoint.getHost(), webSocketProxyServerEndpoint.getPort()));
        this.webSocketProxyServerEndpoint = webSocketProxyServerEndpoint;
        this.webSocketVersion = webSocketVersion;
        this.webSocketProxyServerProtocol = webSocketProxyServerProtocol;
        this.customHandshakeHttpHeaders = customHandshakeHttpHeaders;
        this.allowExtensions = allowExtensions;
        this.maxFramePayloadLength = maxFramePayloadLength;
        this.performMasking = performMasking;
        this.allowMaskMismatch = allowMaskMismatch;
//        this.accessKey = accessKey;
    }

    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
        final ChannelPipeline cp = ctx.pipeline();
        if (null == cp.get(HttpResponseDecoder.class)) {
            if (null == cp.get(HttpClientCodec.class)) {
                cp.addBefore(ctx.name(), HttpClientCodec.class.getName(), new HttpClientCodec());
            }
        }
        if (null == cp.get(HttpObjectAggregator.class)) {
            cp.addBefore(ctx.name(), HttpObjectAggregator.class.getName(), new HttpObjectAggregator(8 * 1024 * 1024));
        }

        if (null == cp.get(Utf8FrameValidator.class)) {
            cp.addBefore(ctx.name(), Utf8FrameValidator.class.getName(), new Utf8FrameValidator());
        }

        if (!HttpMethod.CONNECT.name().equals(webSocketProxyServerProtocol)) {
            if (null == cp.get(TcpOverWebSocketProxyCodec.class)) {
                cp.addAfter(ctx.name(), TcpOverWebSocketProxyCodec.class.getName(), new TcpOverWebSocketProxyCodec());
            }
        }
        super.handlerAdded(ctx);
    }

    @Override
    public void channelActive(final ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
    }

    @Override
    protected ChannelPromise handshake(final ChannelHandlerContext ctx, final ChannelPromise handshakePromise) throws Exception {
        final InetSocketAddress address = destinationAddress();
        final String accessToken = createAccessTokenNew(ctx.alloc(), address);

        final Map<String, String> handshakeUrlParams = Maps.newHashMap();
        final DefaultHttpHeaders handshakeHeaders = new DefaultHttpHeaders();

        // keeping legacy.
        // handshakeUrlParams.put("target", address.getHostString() + ":" + address.getPort());

        handshakeHeaders.add(customHandshakeHttpHeaders);
//        handshakeHeaders.set("X-TARGET-ADDRESS", address.getHostString());
//        handshakeHeaders.setInt("X-TARGET-PORT", address.getPort());

        handshakeUrlParams.put("access_token", accessToken);
        handshakeHeaders.add("Authorization", "Bearer " + accessToken);

        final URI handshakeUri = createUri(webSocketProxyServerEndpoint, handshakeUrlParams);

        final WebSocketClientHandshaker handshaker = WebSocketClientHandshakerFactory.newHandshaker(
                handshakeUri, webSocketVersion, webSocketProxyServerProtocol,
                allowExtensions, handshakeHeaders, maxFramePayloadLength, performMasking, allowMaskMismatch
        );
         handshaker.handshake(new CtxWriteDelegatingChannel(ctx)).addListener(new ChannelFutureListener() {
//        handshaker.handshake(ctx.channel()).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                if (!future.isSuccess()) {
                    handshakePromise.tryFailure(future.cause());
                }
            }
        });
        ctx.channel().attr(HANDSHAKER_ATTR_KEY).set(handshaker);
        return handshakePromise;
    }

    private void removeIfNeeded(final ChannelPipeline cp, final Class<? extends ChannelHandler>... types) {
        for (final Class<? extends ChannelHandler> type : types) {
            final ChannelHandler handler = cp.get(type);
            if (null != handler) {
                cp.remove(handler);
            }
        }
    }

    private String createAccessTokenNew(final ByteBufAllocator alloc, final InetSocketAddress target) {
        final ByteBuf buffer = alloc.buffer();
        try {
            buffer.writeByte(0x01);
            buffer.writeByte(0x01);
            buffer.writeByte(0);

            if (target.isUnresolved()) {
                final String hostname = target.getHostString();
                final byte[] bytes = hostname.getBytes(CharsetUtil.UTF_8);
                buffer.writeByte(0x03);
                buffer.writeByte(bytes.length);
                buffer.writeBytes(bytes);
            } else {
                final InetAddress address = target.getAddress();
                if (address instanceof Inet4Address) {
                    buffer.writeByte(0x01);
                } else if (address instanceof Inet6Address) {
                    buffer.writeByte(0x04);
                } else {
                    throw new UnsupportedOperationException(address.toString());
                }
                buffer.writeBytes(address.getAddress());
            }
            buffer.writeShort(target.getPort());
            // Base64.encode 返回新分配的 ByteBuf,toString 后必须 release,否则泄漏。
            final ByteBuf encoded = Base64.encode(buffer, Base64Dialect.URL_SAFE);
            try {
                return encoded.toString(CharsetUtil.UTF_8);
            } finally {
                encoded.release();
            }
        } finally {
            buffer.release();
        }
    }

    @Override
    protected boolean handshakeRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        if (!(msg instanceof FullHttpResponse)) {
            throw new UnsupportedOperationException();
        }
        final FullHttpResponse httpResponse = (FullHttpResponse) msg;
        final WebSocketClientHandshaker handshaker = ctx.channel().attr(HANDSHAKER_ATTR_KEY).get();
        if (handshaker.isHandshakeComplete()) {
            throw new IllegalStateException();
        }
        handshaker.finishHandshake(ctx.channel(), httpResponse);
        if (HttpMethod.CONNECT.name().equals(webSocketProxyServerProtocol)) {
            ctx.pipeline().remove("ws-encoder");
            ctx.pipeline().remove("ws-decoder");
            ctx.pipeline().remove(Utf8FrameValidator.class);
            // auto remove when handshake completed.
            // ctx.pipeline().remove(HttpClientCodec.class);
        } else {
//            if (null == ctx.pipeline().get(TcpOverWebSocketProxyCodec.class)) {
//                ctx.pipeline().addAfter(ctx.name(), TcpOverWebSocketProxyCodec.class.getName(), new TcpOverWebSocketProxyCodec());
//            }
        }
        return true;
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) throws Exception {
        super.exceptionCaught(ctx, cause);
    }

    private class TcpOverWebSocketProxyCodec extends MessageToMessageCodec<WebSocketFrame, ByteBuf> {

        @Override
        protected void encode(final ChannelHandlerContext ctx, final ByteBuf msg, final List<Object> out) throws Exception {
            if (msg.isReadable()) {
                out.add(new BinaryWebSocketFrame(ReferenceCountUtil.retain(msg)));
            }
        }

        @Override
        protected void decode(final ChannelHandlerContext ctx, final WebSocketFrame frame, final List<Object> out) throws Exception {
            if (frame instanceof PingWebSocketFrame) {
                ctx.writeAndFlush(new PongWebSocketFrame(ReferenceCountUtil.retain(frame.content())));
            } else if (frame instanceof PongWebSocketFrame) {
                log.debug("WebSocket Pong.");
            } else if (frame instanceof CloseWebSocketFrame) {
                ctx.close();
            } else {
                out.add(Unpooled.wrappedBuffer(ReferenceCountUtil.retain(frame.content())));
            }
        }
    }

    private static class CtxWriteDelegatingChannel implements Channel {
        private final Channel delegate;
        private final ChannelHandlerContext ctx;

        CtxWriteDelegatingChannel(final ChannelHandlerContext ctx) {
            this.ctx = ctx;
            this.delegate = ctx.channel();
        }

        @Override
        public ChannelFuture write(final Object msg) {
            return ctx.write(msg);
        }

        @Override
        public ChannelFuture write(final Object msg, final ChannelPromise promise) {
            return ctx.write(msg, promise);
        }

        @Override
        public Channel flush() {
            return ctx.flush().channel();
        }

        @Override
        public ChannelFuture writeAndFlush(final Object msg, final ChannelPromise promise) {
            return ctx.writeAndFlush(msg, promise);
        }

        @Override
        public ChannelFuture writeAndFlush(final Object msg) {
            return ctx.writeAndFlush(msg);
        }

        @Override
        public ChannelId id() {
            return delegate.id();
        }

        @Override
        public EventLoop eventLoop() {
            return delegate.eventLoop();
        }

        @Override
        public Channel parent() {
            return delegate.parent();
        }

        @Override
        public ChannelConfig config() {
            return delegate.config();
        }

        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        @Override
        public boolean isRegistered() {
            return delegate.isRegistered();
        }

        @Override
        public boolean isActive() {
            return delegate.isActive();
        }

        @Override
        public ChannelMetadata metadata() {
            return delegate.metadata();
        }

        @Override
        public SocketAddress localAddress() {
            return delegate.localAddress();
        }

        @Override
        public SocketAddress remoteAddress() {
            return delegate.remoteAddress();
        }

        @Override
        public ChannelFuture closeFuture() {
            return delegate.closeFuture();
        }

        @Override
        public boolean isWritable() {
            return delegate.isWritable();
        }

        @Override
        public long bytesBeforeUnwritable() {
            return delegate.bytesBeforeUnwritable();
        }

        @Override
        public long bytesBeforeWritable() {
            return delegate.bytesBeforeUnwritable();
        }

        @Override
        public Unsafe unsafe() {
            return delegate.unsafe();
        }

        @Override
        public ChannelPipeline pipeline() {
            return delegate.pipeline();
        }

        @Override
        public ByteBufAllocator alloc() {
            return delegate.alloc();
        }

        @Override
        public ChannelFuture bind(final SocketAddress localAddress) {
            return delegate.bind(localAddress);
        }

        @Override
        public ChannelFuture connect(final SocketAddress remoteAddress) {
            return delegate.connect(remoteAddress);
        }

        @Override
        public ChannelFuture connect(final SocketAddress remoteAddress, final SocketAddress localAddress) {
            return delegate.connect(remoteAddress, localAddress);
        }

        @Override
        public ChannelFuture disconnect() {
            return delegate.disconnect();
        }

        @Override
        public ChannelFuture close() {
            return delegate.close();
        }

        @Override
        public ChannelFuture deregister() {
            return delegate.deregister();
        }

        @Override
        public ChannelFuture bind(final SocketAddress localAddress, final ChannelPromise promise) {
            return delegate.bind(localAddress, promise);
        }

        @Override
        public ChannelFuture connect(final SocketAddress remoteAddress, final ChannelPromise promise) {
            return delegate.connect(remoteAddress, promise);
        }

        @Override
        public ChannelFuture connect(final SocketAddress remoteAddress, final SocketAddress localAddress, final ChannelPromise promise) {
            return delegate.connect(remoteAddress, localAddress, promise);
        }

        @Override
        public ChannelFuture disconnect(final ChannelPromise promise) {
            return delegate.disconnect(promise);
        }

        @Override
        public ChannelFuture close(final ChannelPromise promise) {
            return delegate.close(promise);
        }

        @Override
        public ChannelFuture deregister(final ChannelPromise promise) {
            return delegate.deregister(promise);
        }

        @Override
        public Channel read() {
            return delegate.read();
        }


        @Override
        public ChannelPromise newPromise() {
            return delegate.newPromise();
        }

        @Override
        public ChannelProgressivePromise newProgressivePromise() {
            return delegate.newProgressivePromise();
        }

        @Override
        public ChannelFuture newSucceededFuture() {
            return delegate.newSucceededFuture();
        }

        @Override
        public ChannelFuture newFailedFuture(final Throwable cause) {
            return delegate.newFailedFuture(cause);
        }

        @Override
        public ChannelPromise voidPromise() {
            return delegate.voidPromise();
        }

        @Override
        public <T> Attribute<T> attr(final AttributeKey<T> attributeKey) {
            return delegate.attr(attributeKey);
        }

        @Override
        public <T> boolean hasAttr(final AttributeKey<T> attributeKey) {
            return delegate.hasAttr(attributeKey);
        }

        @Override
        public int compareTo(final Channel o) {
            return delegate.compareTo(o);
        }

    }

    private static URI createUri(final URI endpoint, final Map<String, String> urlParams) {
        final QueryStringDecoder decoder = new QueryStringDecoder(endpoint.toASCIIString());
        final QueryStringEncoder encoder = new QueryStringEncoder(decoder.path());
        for (final Map.Entry<String, List<String>> parameter : decoder.parameters().entrySet()) {
            for (final String value : parameter.getValue()) {
                encoder.addParam(parameter.getKey(), value);
            }
        }
        for (final Map.Entry<String, String> urlParam : urlParams.entrySet()) {
            encoder.addParam(urlParam.getKey(), urlParam.getValue());
        }
        try {
            return endpoint.resolve(encoder.toUri());
        } catch (final URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
    }

}