package com.github.pangolin.routing.handler.internal.client;

import com.github.pangolin.routing.util.SocketUtils;
import com.github.pangolin.util.Channels;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.ClientBuilder;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.AbstractClientChannel;
import org.apache.sshd.client.future.AuthFuture;
import org.apache.sshd.client.future.DefaultOpenFuture;
import org.apache.sshd.client.future.OpenFuture;
import org.apache.sshd.client.session.AbstractClientSession;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.Closeable;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.SshException;
import org.apache.sshd.common.channel.ChannelAsyncInputStream;
import org.apache.sshd.common.channel.ChannelAsyncOutputStream;
import org.apache.sshd.common.channel.ChannelOutputStream;
import org.apache.sshd.common.channel.LocalWindow;
import org.apache.sshd.common.future.CloseFuture;
import org.apache.sshd.common.future.DefaultCloseFuture;
import org.apache.sshd.common.future.SshFutureListener;
import org.apache.sshd.common.io.IoSession;
import org.apache.sshd.common.io.IoWriteFuture;
import org.apache.sshd.common.session.ConnectionService;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.common.util.threads.ThreadUtils;
import org.apache.sshd.netty.NettyIoConnector;
import org.apache.sshd.netty.NettyIoServiceFactory;
import org.apache.sshd.netty.NettyIoServiceFactoryFactory;
import org.apache.sshd.netty.NettyIoSession;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Consumer;

/**
 *
 */
@Slf4j
public class SshProxyHandler extends ChannelDuplexHandler {
    private static final String SSH_NETTY_ADAPTER_NAME = "SSH-CLIENT-NETTY-ADAPTER";
    private static final String SSH_NETTY_DIRECT_BRIDGE_ADAPTER_NAME = "SSH-CLIENT-DIRECT-BRIDGE-ADAPTER";

    private static final SshClient DEFAULT_SSH_CLIENT = ClientBuilder.builder().build();

    static {
        DEFAULT_SSH_CLIENT.setIoServiceFactoryFactory(new NettyIoServiceFactoryFactory());
        DEFAULT_SSH_CLIENT.start();
    }

    private final String hostname;
    private final int port;
    private final String username;
    private final String password;

    private NettyIoSession ioSession;
    private NettyIoConnector ioConnector;

    public SshProxyHandler(final String hostname, final int port, final String username, final String password) {
        this.hostname = hostname;
        this.port = port;
        this.username = username;
        this.password = password;
    }

    @Override
    public void channelActive(final ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
    }

    @Override
    public void connect(final ChannelHandlerContext ctx, final SocketAddress remoteAddress, final SocketAddress localAddress, final ChannelPromise promise) throws Exception {
        final InetSocketAddress proxyAddress = SocketUtils.toSocketAddress(hostname, port);

        final SshClient client = DEFAULT_SSH_CLIENT;
        final NettyIoConnector ioConnector = new NettyIoConnector((NettyIoServiceFactory) client.getIoServiceFactory(), client.getSessionFactory());
        final NettyIoSession ioSession = new NettyIoSession(ioConnector, client.getSessionFactory(), null);

        ctx.pipeline().addAfter(ctx.name(), SSH_NETTY_ADAPTER_NAME, this.getNettyAdapter(ioSession));

        final ChannelPromise connectPromise = ctx.newPromise();
        connectPromise.addListener(connectListener(ctx, (InetSocketAddress) remoteAddress, promise));

        ctx.connect(proxyAddress, localAddress, connectPromise);

        this.ioSession = ioSession;
        this.ioConnector = ioConnector;
    }

    @Override
    public void close(final ChannelHandlerContext ctx, final ChannelPromise promise) throws Exception {
        this.destroy(ioSession);
        super.close(ctx, promise);
    }

    @Override
    public void disconnect(final ChannelHandlerContext ctx, final ChannelPromise promise) throws Exception {
        this.destroy(ioSession);
        super.disconnect(ctx, promise);
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) throws Exception {
        super.exceptionCaught(ctx, cause);
    }

    private void destroy(final IoSession ioSession) throws Exception {
        this.getClientSession(ioSession).disconnect(0, "No message");
    }

    private void closeQuiet(final Closeable closeable) {
        try {
            Closeable.close(closeable);
        } catch (final IOException e) {
            // IGNORE
        }
    }

    private GenericFutureListener<Future<? super Void>> connectListener(final ChannelHandlerContext ctx, final InetSocketAddress remoteAddress, final ChannelPromise promise) {
        return new GenericFutureListener<Future<? super Void>>() {
            @Override
            public void operationComplete(final Future<? super Void> future) throws Exception {
                if (future.isSuccess()) {
                    onConnected(ctx, remoteAddress, promise);
                } else {
                    promise.tryFailure(future.cause());
                }
            }
        };
    }

    private void onConnected(final ChannelHandlerContext ctx, final InetSocketAddress remoteAddress, final ChannelPromise promise) {
        // run after SSHD-adapter
        ctx.channel().eventLoop().execute(() -> onConnected0(ctx, remoteAddress, promise));
    }

    private void onConnected0(final ChannelHandlerContext ctx, final InetSocketAddress remoteAddress, final ChannelPromise promise) {
        try {
            final ClientSession session = this.getClientSession(ioSession);
            session.addCloseFutureListener(new SshFutureListener<CloseFuture>() {
                @Override
                public void operationComplete(final CloseFuture future) {
                    closeQuiet(ioSession);
                    closeQuiet(ioConnector);
                    if (ctx.channel().isOpen()) {
                        ctx.close();
                    }
                }
            });

            session.setUsername(username);
            session.addPasswordIdentity(password);
            session.auth().addListener(authToNext(ctx, ctx0 -> this.onAuthenticated(ctx0, remoteAddress, promise), promise));
        } catch (final IOException ioe) {
            ctx.fireExceptionCaught(ioe);
        }
    }

    private SshFutureListener<AuthFuture> authToNext(final ChannelHandlerContext ctx, final Consumer<ChannelHandlerContext> next, final ChannelPromise promise) {
        return new SshFutureListener<AuthFuture>() {
            @Override
            public void operationComplete(final AuthFuture future) {
                if (future.isSuccess()) {
                    next.accept(ctx);
                } else {
                    promise.tryFailure(future.getException());
                }
            }
        };
    }

    private void onAuthenticated(final ChannelHandlerContext ctx, final InetSocketAddress remoteAddress, final ChannelPromise promise) {
        try {
            // TODO
            final SshdSocketAddress source = new SshdSocketAddress(0);
            final SshdSocketAddress dest = new SshdSocketAddress(remoteAddress);
            final RedirectTcpipClientChannel channel = new RedirectTcpipClientChannel(ioSession, source, dest);

            this.getClientSession(ioSession).getService(ConnectionService.class).registerChannel(channel);
            channel.open().addListener(openToNext(ctx, ctx0 -> {
                ctx.channel().pipeline().addAfter(SSH_NETTY_ADAPTER_NAME, SSH_NETTY_DIRECT_BRIDGE_ADAPTER_NAME, channel.adapter);
            }, promise));
        } catch (IOException e) {
            promise.tryFailure(e);
        }
    }

    private SshFutureListener<OpenFuture> openToNext(final ChannelHandlerContext ctx, final Consumer<ChannelHandlerContext> next, final ChannelPromise promise) {
        return new SshFutureListener<OpenFuture>() {
            @Override
            public void operationComplete(final OpenFuture future) {
                if (future.isOpened()) {
                    next.accept(ctx);
                    promise.trySuccess();
                } else {
                    promise.tryFailure(future.getException());
                }
            }
        };
    }


    private ChannelHandler getNettyAdapter(final NettyIoSession nettyIoSession) throws NoSuchFieldException, IllegalAccessException {
        final Field adapter = NettyIoSession.class.getDeclaredField("adapter");
        adapter.setAccessible(true);
        return (ChannelHandler) adapter.get(nettyIoSession);
    }

    private ClientSession getClientSession(final IoSession ioSession) {
        return (ClientSession) AbstractClientSession.getSession(ioSession, false);
    }


    private static class RedirectTcpipClientChannel extends AbstractClientChannel {
        private static final String DIRECT_TCPIP = "direct-tcpip";

        protected final SshdSocketAddress source;
        protected final SshdSocketAddress remote;
        protected final Adapter adapter;


        public RedirectTcpipClientChannel(final IoSession clientSession, SshdSocketAddress source, SshdSocketAddress remote) {
            super(DIRECT_TCPIP);
            this.source = source;
            this.remote = remote;
            this.adapter = new Adapter(Objects.requireNonNull(clientSession, "No server session provided"), this);
            this.streaming = Streaming.Async;
        }


        @Override
        public synchronized OpenFuture open() throws IOException {
            SshdSocketAddress src = this.source;
            SshdSocketAddress dst = this.remote;

            if (closeFuture.isClosed()) {
                throw new SshException("Session has been closed");
            }

            openFuture = new DefaultOpenFuture(src, futureLock);
            if (log.isDebugEnabled()) {
                log.debug("open({}) send SSH_MSG_CHANNEL_OPEN", this);
            }

            Session session = getSession();
            String srcHost = src.getHostName();
            String dstHost = dst.getHostName();
            LocalWindow wLocal = getLocalWindow();
            String type = getChannelType();
            Buffer buffer = session.createBuffer(
                    SshConstants.SSH_MSG_CHANNEL_OPEN,
                    type.length() + srcHost.length() + dstHost.length() + Long.SIZE
            );
            buffer.putString(type);
            buffer.putUInt(getChannelId());
            buffer.putUInt(wLocal.getSize());
            buffer.putUInt(wLocal.getPacketSize());
            buffer.putString(dstHost);
            buffer.putUInt(dst.getPort());
            buffer.putString(srcHost);
            buffer.putUInt(src.getPort());
            writePacket(buffer);
            return openFuture;
        }

        @Override
        protected synchronized void doOpen() throws IOException {
            if (streaming == Streaming.Async) {
                asyncIn = new ChannelAsyncOutputStream(this, SshConstants.SSH_MSG_CHANNEL_DATA) {
                    @SuppressWarnings("synthetic-access")
                    @Override
                    protected CloseFuture doCloseGracefully() {
                        DefaultCloseFuture result = new DefaultCloseFuture(getChannelId(), futureLock);
                        CloseFuture packetsWritten = super.doCloseGracefully();
                        packetsWritten.addListener(p -> {
                            try {
                                // The channel writes EOF directly through the SSH session
                                IoWriteFuture eofSent = sendEof();
                                if (eofSent != null) {
                                    eofSent.addListener(f -> result.setClosed());
                                    return;
                                }
                            } catch (Exception e) {
                                getSession().exceptionCaught(e);
                            }
                            result.setClosed();
                        });
                        return result;
                    }
                };
                asyncOut = new ChannelAsyncInputStream(this);
            } else {
                out = new ChannelOutputStream(this, getRemoteWindow(), log, SshConstants.SSH_MSG_CHANNEL_DATA, true);
                invertedIn = out;
            }
        }

        @Override
        protected Closeable getInnerCloseable() {
            /*
            return builder().sequential(port.getPortSession(), super.getInnerCloseable()).build();
            */
            return super.getInnerCloseable();
        }

        @Override
        protected void doWriteData(byte[] data, int off, long len) throws IOException {
            // port.sendToPort(SshConstants.SSH_MSG_CHANNEL_DATA, data, off, len);
            adapter.sendToPort(SshConstants.SSH_MSG_CHANNEL_DATA, data, off, len);
        }

        @Override
        protected void doWriteExtendedData(byte[] data, int off, long len) throws IOException {
            throw new UnsupportedOperationException(getChannelType() + " Tcpip channel does not support extended data");
        }

        @Override
        public void handleEof() throws IOException {
            super.handleEof();
//            port.handleEof();
        }

        private static class Adapter extends ChannelDuplexHandler {
            private final IoSession ioSession;
            private final RedirectTcpipClientChannel channel;
            private ChannelHandlerContext ctx;

            private Adapter(final IoSession ioSession, final RedirectTcpipClientChannel channel) {
                this.ioSession = ioSession;
                this.channel = channel;
            }

            @Override
            public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
                this.ctx = ctx;
            }

            @Override
            public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) throws Exception {
                ioSession.suspendRead();

                ByteBuf buf = (ByteBuf) msg;
                final byte[] bytes = new byte[buf.readableBytes()];
                buf.readBytes(bytes);
                ThreadUtils.runAsInternal(channel.getAsyncIn(), out -> out.writeBuffer(new ByteArrayBuffer(bytes)).addListener(f -> {
                    ioSession.resumeRead();
                    Throwable exception = f.getException();
                    if (null != exception) {
                        promise.tryFailure(exception);
                    } else {
                        promise.trySuccess();
                    }
                }));
            }

            void sendToPort(final byte cmd, final byte[] data, final int off, final long len) throws IOException {
                ctx.fireChannelRead(Unpooled.wrappedBuffer(data, off, (int) len));
            }
        }
    }


    public static void main(String[] args) throws InterruptedException {
        String hostname = "";
        int port = 22;
        String username = "root";
        String password = "";

        Channel channel = Channels.open("www.baidu.com", 80, true, new NioEventLoopGroup(), new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel channel) throws Exception {
                channel.pipeline().addLast(new SshProxyHandler(hostname, port, username, password));
                channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
                        ByteBuf buf = (ByteBuf) msg;
                        final byte[] bytes = new byte[buf.readableBytes()];
                        buf.readBytes(bytes);
                        System.out.println(new String(bytes, StandardCharsets.UTF_8));
                    }
                });
            }
        }).sync().channel();


//    Thread.sleep(5 * 1000);

        byte[] bytes = "GET / HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        ByteBuf msg = Unpooled.wrappedBuffer(bytes);
        channel.writeAndFlush(msg);
        channel.closeFuture().sync();
    }

}
