package com.github.pangolin.proxy.client;

import com.github.pangolin.util.Channels;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.PendingWriteQueue;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketClientCompressionHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.ReferenceCountUtil;

import javax.net.ssl.SSLException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.channels.ConnectionPendingException;

/**
 * TODO DOC ME!.
 *
 * @author changhe.yang
 * @since 20230821
 */
public class WebSocketProxyClientHandler extends ChannelDuplexHandler {
    private static final int MAX_HTTP_CONTENT_LENGTH = 1024 * 1024 * 8;

    private boolean pending = true;
    private boolean flushed = false;
    private boolean initialized = false;
    private PendingWriteQueue pendingWrites;
    private boolean suppressChannelReadComplete;

    private final URI webSocketUrl;
    private final String webSocketProtocol;
    private final SocketAddress webSocketProxyServerAddress;
    private volatile transient SocketAddress delegateAddress;

    private ChannelHandlerContext toProxyServerCtx;


    public WebSocketProxyClientHandler(final URI webSocketUrl, final String webSocketProtocol) {
        this.webSocketUrl = webSocketUrl;
        this.webSocketProtocol = webSocketProtocol;
        this.webSocketProxyServerAddress = new InetSocketAddress(webSocketUrl.getHost(), webSocketUrl.getPort());
    }


    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
        final ChannelPipeline cp = ctx.pipeline();
        final boolean isSecure = webSocketUrl.getScheme().equalsIgnoreCase("wss");
        if (isSecure) {
            cp.addLast(createSslContext().newHandler(ctx.channel().alloc()));
        }
        cp.addBefore(ctx.name(), null, new HttpClientCodec());
        cp.addBefore(ctx.name(), null, new HttpObjectAggregator(MAX_HTTP_CONTENT_LENGTH));
        cp.addBefore(ctx.name(), null, WebSocketClientCompressionHandler.INSTANCE);
        cp.addAfter(ctx.name(), null, new WebSocketClientProtocolHandler(WebSocketClientHandshakerFactory.newHandshaker(
                webSocketUrl, WebSocketVersion.V13, webSocketProtocol, true, new DefaultHttpHeaders(), 65536, true, true
        ), false));
    }

    private static SslContext createSslContext() throws SSLException {
        return SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
    }

    @Override
    public void connect(final ChannelHandlerContext ctx, final SocketAddress remoteAddress, final SocketAddress localAddress, final ChannelPromise promise) throws Exception {
        if (null != delegateAddress) {
            promise.setFailure(new ConnectionPendingException());
        } else {
            delegateAddress = remoteAddress;
            ctx.connect(webSocketProxyServerAddress, localAddress, promise);
        }
    }


    @Override
    public void userEventTriggered(final ChannelHandlerContext ctx, final Object evt) throws Exception {
        if (WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_ISSUED.equals(evt)) {
            pending = true;
        } else if (WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE.equals(evt)) {
            channelProxied(ctx);
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        if (pending) {
            suppressChannelReadComplete = true;
            try {
                final boolean done = channelRead0(ctx, msg);
                if (done) {
                    // XXX
                    channelProxyConnected(ctx);

                    pending = false;
                    writePendingWrites();
                    if (flushed) {
                        ctx.flush();
                    }
                }
            } finally {
                ReferenceCountUtil.release(msg);
            }
        } else {
            suppressChannelReadComplete = false;
            ctx.fireChannelRead(msg);
        }
    }

    protected void channelProxyConnected(final ChannelHandlerContext ctx) {
        ctx.fireUserEventTriggered("Proxied");
    }

    @Override
    public void channelReadComplete(final ChannelHandlerContext ctx) throws Exception {
        if (suppressChannelReadComplete) {
            suppressChannelReadComplete = false;
            if (!ctx.channel().config().isAutoRead()) {
                ctx.read();
            }
        } else {
            ctx.fireChannelReadComplete();
        }
    }

    private boolean channelRead0(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        if (msg instanceof FullHttpResponse) {
            final FullHttpResponse httpResponse = (FullHttpResponse) msg;
            ctx.fireChannelRead(msg);
            return true;
        } else {
        }
        return false;
    }

    @Override
    public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
        if (pending) {
            //
        } else {
            ctx.fireChannelInactive();
        }
    }

    protected void channelProxied(final ChannelHandlerContext ctx) {
        initialized = true;
        pending = false;
        // FIXME cancel timeout
        writePendingWrites();
        if (flushed) {
            ctx.flush();
        }
    }

    protected void channelProxyFailure(final ChannelHandlerContext ctx, final Throwable cause) {
        pending = false;
        // FIXME cancel timeout
        failPendingWrites(cause);
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) throws Exception {
        if (pending) {
            // FIXME setConnectFail
            channelProxyFailure(ctx, cause);
        } else {
            ctx.fireExceptionCaught(cause);
        }
    }

    @Override
    public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) throws Exception {
        if (!initialized) {
            ctx.write(msg, promise);
        } else if (pending) {
            addPendingWrite(ctx, msg, promise);
        } else {
            writePendingWrites();
            ctx.write(msg, promise);
        }
    }

    @Override
    public void flush(final ChannelHandlerContext ctx) throws Exception {
        if (!initialized) {
            ctx.flush();
            initialized = true;
        } else if (pending) {
            flushed = true;
        } else {
            writePendingWrites();
            ctx.flush();
        }
    }

    private void addPendingWrite(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) {
        if (null == pendingWrites) {
            pendingWrites = new PendingWriteQueue(ctx);
        }
        pendingWrites.add(msg, promise);
    }

    private void writePendingWrites() {
        if (null != pendingWrites) {
            pendingWrites.removeAndWriteAll();
            pendingWrites = null;
        }
    }

    private void failPendingWrites(final Throwable cause) {
        if (null != pendingWrites) {
            pendingWrites.removeAndFailAll(cause);
            pendingWrites = null;
        }
    }

    @SuppressWarnings("unchecked")
    public <T extends SocketAddress> T getDelegateAddress() {
        return (T) delegateAddress;
    }

    public static void main(String[] args) throws InterruptedException {
        Channels.open("127.0.0.1", 999, new NioEventLoopGroup(),
                new WebSocketProxyClientHandler(URI.create("ws://127.0.0.1:8888/ws"), null)
        ).sync().await();
    }
}
