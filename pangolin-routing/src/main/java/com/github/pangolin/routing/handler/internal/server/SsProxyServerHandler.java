package com.github.pangolin.routing.handler.internal.server;

import com.github.pangolin.handler.TcpInboundRedirectHandler;
import com.github.pangolin.routing.handler.codec.ss.SsAeadCipherCodec;
import com.github.pangolin.routing.handler.codec.ss.SsStreamCipherCodec;
import com.github.pangolin.routing.handler.codec.ss.crypto.AeadCipherAlgorithm;
import com.github.pangolin.routing.handler.codec.ss.crypto.CipherAlgorithm;
import com.github.pangolin.routing.handler.codec.ss.crypto.StreamCipherAlgorithm;
import com.github.pangolin.routing.handler.codec.ss.crypto.spi.CipherAlgorithmSpi;
import com.github.pangolin.routing.handler.internal.server.support.SocketChannelFactory;
import com.github.pangolin.routing.handler.internal.server.support.StandardSocketChannelFactory;
import com.github.pangolin.server.NettyServer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.socksx.v5.Socks5AddressDecoder;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.security.SecureRandom;

@Slf4j
public class SsProxyServerHandler extends ChannelDuplexHandler {
    private final String password;
    private final CipherAlgorithm algorithm;
    private final SocketChannelFactory factory;

    public SsProxyServerHandler(final String password, final CipherAlgorithm algorithm, final SocketChannelFactory factory) {
        this.password = password;
        this.algorithm = algorithm;
        this.factory = factory;
    }

    private ChannelPromise handshakePromise;

    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
        if (algorithm instanceof StreamCipherAlgorithm) {
            StreamCipherAlgorithm sca = (StreamCipherAlgorithm) algorithm;
            ctx.pipeline().addBefore(ctx.name(), null, new SsStreamCipherCodec(sca, password, new SecureRandom()));
        } else if (algorithm instanceof AeadCipherAlgorithm) {
            AeadCipherAlgorithm aca = (AeadCipherAlgorithm) algorithm;
            ctx.pipeline().addBefore(ctx.name(), null, new SsAeadCipherCodec(aca, password, new SecureRandom()));
        } else {
            throw new UnsupportedOperationException("algorithm not supported: " + algorithm.getName());
        }
    }


    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        final ByteBuf buf = (ByteBuf) msg;
        final Socks5AddressType addressType = Socks5AddressType.valueOf(buf.readByte());
        final String address = Socks5AddressDecoder.DEFAULT.decodeAddress(addressType, buf);
        final int port = buf.readUnsignedShort();

        ctx.channel().config().setAutoRead(false);

        final ChannelConfig c = ctx.channel().config();
        // FIXME
        final InetSocketAddress addr = InetSocketAddress.createUnresolved(address, port);
        factory.open(addr, c.getConnectTimeoutMillis(), false, ctx.channel().eventLoop(), new ChannelDuplexHandler() {

            @Override
            public void channelRegistered(final ChannelHandlerContext delegateCtx) throws Exception {
                ctx.pipeline().replace(ctx.handler(), null, new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
                        delegateCtx.writeAndFlush(msg);
                    }
                });

                delegateCtx.pipeline().addBefore(delegateCtx.name(), null, new ChannelDuplexHandler() {
                    private PendingWriteQueue pendingWrites;

                    @Override
                    public void handlerAdded(final ChannelHandlerContext delegateCtx) throws Exception {
                        pendingWrites = new PendingWriteQueue(delegateCtx);
                        pendingWrites.add(buf, delegateCtx.newPromise());
                    }

                    @Override
                    public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) throws Exception {
                        if (!ctx.channel().isActive()) {
                            pendingWrites.add(msg, promise);
                            return;
                        }
                        if (!pendingWrites.isEmpty()) {
                            pendingWrites.removeAndWriteAll();
                            ctx.flush();
                        }
                        super.write(ctx, msg, promise);
                    }

                    @Override
                    public void channelActive(final ChannelHandlerContext ctx) throws Exception {
                        if (!pendingWrites.isEmpty()) {
                            pendingWrites.removeAndWriteAll();
                            ctx.flush();
                        }
                        super.channelActive(ctx);
                    }
                });
                delegateCtx.pipeline().replace(this, null, new TcpInboundRedirectHandler(ctx));
                delegateCtx.channel().config().setAutoRead(true);
                ctx.channel().config().setAutoRead(true);
            }

        }).channel().closeFuture().addListener(closeOnComplete(ctx));
    }

    private ChannelFutureListener closeOnComplete(final ChannelHandlerContext ctx) {
        return new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future) throws Exception {
                if (ctx.channel().isActive()) {
                    log.info("[SS] Connection to {} closed", future.channel().remoteAddress());
                    ctx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                }
            }
        };
    }

    public static void main(String[] args) throws Exception {
//        final String method = "rc4-md5";
        final String method = "chacha20-ietf-poly1305";
        final CipherAlgorithm instance = (CipherAlgorithm) CipherAlgorithmSpi.getInstance(method);
        final NettyServer server = new NettyServer(56001);
        server.start(true, new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
                ch.pipeline().addLast(new SsProxyServerHandler("jASkBs", instance, new StandardSocketChannelFactory()));
            }
        }).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future) throws Exception {
                if (future.isSuccess()) {
                    final InetSocketAddress localAddress = (InetSocketAddress) future.channel().localAddress();
                    System.out.println(String.format("ProxyServer started on %s:%s", localAddress.getHostString(), localAddress.getPort()));
                } else {
                    future.cause().printStackTrace();
                }
            }
        }).sync().channel().closeFuture().sync();
    }
}