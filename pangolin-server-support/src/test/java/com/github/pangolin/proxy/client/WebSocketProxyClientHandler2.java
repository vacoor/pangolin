package com.github.pangolin.proxy.client;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelId;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelProgressivePromise;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.MessageToMessageCodec;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.EmptyHttpHeaders;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseDecoder;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.Utf8FrameValidator;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;

/**
 * @see io.netty.resolver.NoopAddressResolverGroup#INSTANCE
 */
@Slf4j
public class WebSocketProxyClientHandler2 extends ProxyClientHandler {
    private static final AttributeKey<WebSocketClientHandshaker> HANDSHAKER_ATTR_KEY = AttributeKey.valueOf(WebSocketClientHandshaker.class, "HANDSHAKER");

    private final URI webSocketProxyServerEndpoint;
    private final WebSocketVersion webSocketVersion;
    private final String webSocketProxyServerProtocol;
    private final HttpHeaders customHandshakeHttpHeaders;
    private final boolean allowExtensions;
    private final int maxFramePayloadLength;
    private final boolean performMasking;
    private final boolean allowMaskMismatch;

    public WebSocketProxyClientHandler2(final URI webSocketProxyServerEndpoint, final String webSocketProxyServerProtocol) {
        this(webSocketProxyServerEndpoint, WebSocketVersion.V13, webSocketProxyServerProtocol, true, 65536, true, true);
    }

    public WebSocketProxyClientHandler2(final URI webSocketProxyServerEndpoint,
                                        final WebSocketVersion webSocketVersion,
                                        final String webSocketProxyServerProtocol,
                                        final boolean allowExtensions, final int maxFramePayloadLength,
                                        final boolean performMasking, final boolean allowMaskMismatch) {
        this(webSocketProxyServerEndpoint, webSocketVersion, webSocketProxyServerProtocol,
                EmptyHttpHeaders.INSTANCE, allowExtensions, maxFramePayloadLength, performMasking, allowMaskMismatch);
    }

    public WebSocketProxyClientHandler2(final URI webSocketProxyServerEndpoint,
                                        final WebSocketVersion webSocketVersion,
                                        final String webSocketProxyServerProtocol,
                                        final HttpHeaders customHandshakeHttpHeaders,
                                        final boolean allowExtensions, final int maxFramePayloadLength,
                                        final boolean performMasking, final boolean allowMaskMismatch) {
        super(new InetSocketAddress(webSocketProxyServerEndpoint.getHost(), webSocketProxyServerEndpoint.getPort()));
        this.webSocketProxyServerEndpoint = webSocketProxyServerEndpoint;
        this.webSocketVersion = webSocketVersion;
        this.webSocketProxyServerProtocol = webSocketProxyServerProtocol;
        this.customHandshakeHttpHeaders = customHandshakeHttpHeaders;
        this.allowExtensions = allowExtensions;
        this.maxFramePayloadLength = maxFramePayloadLength;
        this.performMasking = performMasking;
        this.allowMaskMismatch = allowMaskMismatch;
    }

    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
        final ChannelPipeline cp = ctx.pipeline();
        if (null == cp.get(HttpResponseDecoder.class)) {
            if (null == cp.get(HttpClientCodec.class)) {
                cp.addBefore(ctx.name(), null, new HttpClientCodec());
                // throw new IllegalStateException("ChannelPipeline does not contain " + "a HttpResponseDecoder or HttpClientCodec");
            }
        }
        if (null == cp.get(HttpObjectAggregator.class)) {
//            throw new IllegalStateException("ChannelPipeline does not contain " + "a HttpObjectAggregator");
            cp.addBefore(ctx.name(), null, new HttpObjectAggregator(8 * 1024 * 1024));
        }

        if (null == cp.get(WebSocketProxyCodec.class)) {
            cp.addAfter(ctx.name(), WebSocketProxyCodec.class.getName(), new WebSocketProxyCodec());
        }
        if (null == cp.get(Utf8FrameValidator.class)) {
            cp.addAfter(ctx.name(), Utf8FrameValidator.class.getName(), new Utf8FrameValidator());
        }
    }

    @Override
    public void channelActive(final ChannelHandlerContext ctx) throws Exception {
        final InetSocketAddress address = getDelegateAddress();
        final DefaultHttpHeaders customHandshakeHttpHeadersToUse = new DefaultHttpHeaders();
        customHandshakeHttpHeadersToUse.add(customHandshakeHttpHeaders);
        customHandshakeHttpHeadersToUse.set("X-TARGET-ADDRESS", address.getHostString());
        customHandshakeHttpHeadersToUse.setInt("X-TARGET-PORT", address.getPort());

        final String target = "target=tcp://" + address.getHostString() + ":" + address.getPort();
        String s = webSocketProxyServerEndpoint.toString();

        final URI uri = URI.create(s + (s.contains("?") ? "&" + target : "?" + target));
        final WebSocketClientHandshaker handshaker = WebSocketClientHandshakerFactory.newHandshaker(
                uri, webSocketVersion, webSocketProxyServerProtocol,
                allowExtensions, customHandshakeHttpHeadersToUse, maxFramePayloadLength, performMasking, allowMaskMismatch
        );

        handshaker.handshake(new CtxWriteDelegatingChannel(ctx)).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                if (!future.isSuccess()) {
                    ctx.fireExceptionCaught(future.cause());
                }
            }
        });
        ctx.channel().attr(HANDSHAKER_ATTR_KEY).set(handshaker);
        ctx.fireChannelActive();
    }

    protected boolean channelRead0(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        if (!(msg instanceof FullHttpResponse)) {
            throw new UnsupportedOperationException();
        }
        final FullHttpResponse httpResponse = (FullHttpResponse) msg;
        final WebSocketClientHandshaker handshaker = ctx.channel().attr(HANDSHAKER_ATTR_KEY).get();
        if (handshaker.isHandshakeComplete()) {
            throw new IllegalStateException();
        }
        handshaker.finishHandshake(ctx.channel(), httpResponse);
        ctx.fireUserEventTriggered("XX");
        return true;
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) throws Exception {
        super.exceptionCaught(ctx, cause);
    }

    private class WebSocketProxyCodec extends MessageToMessageCodec<WebSocketFrame, ByteBuf> {

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
}