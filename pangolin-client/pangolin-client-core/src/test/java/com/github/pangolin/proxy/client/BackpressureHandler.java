package com.github.pangolin.proxy.client;

import com.github.pangolin.util.Channels;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.PendingWriteQueue;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;

import java.net.URI;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * TODO DOC ME!.
 *
 * @author changhe.yang
 * @since 20230821
 */
public class BackpressureHandler extends ChannelDuplexHandler {
    protected boolean pendingRead;
    protected boolean pendingWrite;
    protected List<Object> pendingReads = new LinkedList<>();
    protected PendingWriteQueue pendingWrites;

    protected ChannelHandlerContext flusher;

    public BackpressureHandler() {
        this(false, false);
    }

    public BackpressureHandler(final boolean pendingRead, final boolean pendingWrite) {
        this.pendingRead = pendingRead;
        this.pendingWrite = pendingWrite;
    }

    @Override
    public void handlerRemoved(final ChannelHandlerContext ctx) throws Exception {
        writePendingWrites();
        if (null != flusher) {
            flusher.flush();
            flusher = null;
        }
        readPendingReads(ctx);
    }


    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        if (pendingRead) {
            pendingReads.add(msg);
        } else {
            readPendingReads(ctx);
            ctx.fireChannelRead(msg);
        }
    }

    @Override
    public void channelReadComplete(final ChannelHandlerContext ctx) throws Exception {
        if (!pendingRead) {
            ctx.fireChannelReadComplete();
        }
    }

    @Override
    public void read(final ChannelHandlerContext ctx) throws Exception {
//        if (null != pendingReads && !pendingReads.isEmpty()) {
//            readPendingReads(ctx);
//        } else {
            super.read(ctx);
//        }
    }

    private void readPendingReads(final ChannelHandlerContext ctx) {
        while (!pendingReads.isEmpty()) {
            ctx.fireChannelRead(pendingReads.remove(0));
        }
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) throws Exception {
        if (pendingWrite) {
            failPendingWrites(cause);
        } else {
            ctx.fireExceptionCaught(cause);
        }
    }

    @Override
    public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) throws Exception {
        if (pendingWrite) {
            addPendingWrite(ctx, msg, promise);
        } else {
            writePendingWrites();
            ctx.write(msg, promise);
        }
    }

    @Override
    public void flush(final ChannelHandlerContext ctx) throws Exception {
        if (pendingWrite) {
            flusher = ctx;
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

    public static void main(String[] args) throws Exception {
        NioEventLoopGroup group = new NioEventLoopGroup();
        URI webSocketEndpoint = URI.create("ws://127.0.0.1:8899/ws/echo");
        String webSocketProtocol = "";

        DefaultHttpHeaders httpHeaders = new DefaultHttpHeaders();
        Channels.open(webSocketEndpoint.getHost(), webSocketEndpoint.getPort(), group, new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
                final ChannelPipeline cp = ch.pipeline();
//                cp.addLast(createClientSslContext().newHandler(ch.alloc()));
//                cp.addLast(new IdleStateHandler(0, 0, 50));
                cp.addLast(new HttpClientCodec(), new HttpObjectAggregator(1024 * 1024 * 8));
//                cp.addLast(WebSocketClientCompressionHandler.INSTANCE);

                cp.addLast(new WebSocketClientProtocolHandler(WebSocketClientHandshakerFactory.newHandshaker(
                        webSocketEndpoint, WebSocketVersion.V13, webSocketProtocol, false, httpHeaders, 65536, false, true
                ), false));
                cp.addLast(new BackpressureHandler() {
                    @Override
                    public void userEventTriggered(final ChannelHandlerContext ctx, final Object evt) throws Exception {
                        if (WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE.equals(evt)) {
                            ctx.channel().config().setAutoRead(false);
                            this.pendingRead = this.pendingWrite = true;


//                            ctx.channel().config().setAutoRead(false);
                            // ctx.pipeline().addBefore(ctx.name(), null, new BackpressureHandler());
                            System.out.println("Handshake OK");
                            ctx.executor().schedule(() -> {
                                ctx.pipeline().replace(this, null, new ChannelInboundHandlerAdapter() {
                                    @Override
                                    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
                                        System.out.println("MSG: " + msg);
                                    }
                                });
                                ctx.channel().config().setAutoRead(true);
                            }, 3, TimeUnit.SECONDS);
                        }
                    }
                });
            }
        }).sync().channel().closeFuture().await();
    }
}
