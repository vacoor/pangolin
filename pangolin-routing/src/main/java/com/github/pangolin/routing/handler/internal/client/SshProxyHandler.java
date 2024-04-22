package com.github.pangolin.routing.handler.internal.client;

import com.github.pangolin.util.Channels;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.ClientBuilder;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.future.AuthFuture;
import org.apache.sshd.client.future.OpenFuture;
import org.apache.sshd.client.session.AbstractClientSession;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.channel.StreamingChannel;
import org.apache.sshd.common.forward.ChannelToPortHandler;
import org.apache.sshd.common.forward.TcpipClientChannel;
import org.apache.sshd.common.future.SshFutureListener;
import org.apache.sshd.common.io.IoSession;
import org.apache.sshd.common.session.ConnectionService;
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
import java.util.function.Consumer;

/**
 * TODO DOC ME!.
 *
 * @author changhe.yang
 * @since 20240422
 */
@Slf4j
public class SshProxyHandler extends ChannelDuplexHandler {
  private static final String SSHD_NETTY_ADAPTER_NAME = "SSHD-NETTY-ADAPTER";
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
    final InetSocketAddress proxyAddress = new InetSocketAddress(hostname, port);

    final SshClient client = DEFAULT_SSH_CLIENT;
    final NettyIoConnector ioConnector = new NettyIoConnector((NettyIoServiceFactory) client.getIoServiceFactory(), client.getSessionFactory());
    this.ioSession = new NettyIoSession(ioConnector, client.getSessionFactory(), null);

    ctx.pipeline().addAfter(ctx.name(), SSHD_NETTY_ADAPTER_NAME, this.getNettyAdapter(ioSession));

    final ChannelPromise connectPromise = ctx.newPromise();
    connectPromise.addListener(connectListener(ctx, (InetSocketAddress) remoteAddress, promise));

    ctx.connect(proxyAddress, localAddress, connectPromise);
  }

  @Override
  public void disconnect(final ChannelHandlerContext ctx, final ChannelPromise promise) throws Exception {
    this.ioSession.close();
    super.disconnect(ctx, promise);
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
    ctx.channel().eventLoop().execute(() -> {
      try {
        openChannel(ioSession, remoteAddress, ctx, promise);
      } catch (IOException e) {
        promise.tryFailure(e);
      }
    });
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

  @Override
  public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
    super.channelRead(ctx, msg);
  }

  @Override
  public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) throws Exception {
    super.write(ctx, msg, promise);
  }

  private class TcpOutboundBridgeHandler extends ChannelOutboundHandlerAdapter {
    private final IoSession ioSession;
    private final ClientChannel channel;

    private TcpOutboundBridgeHandler(final IoSession ioSession, final ClientChannel channel) {
      this.ioSession = ioSession;
      this.channel = channel;
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

  }

  private class TcpInboundBridgeHandler extends ChannelInboundHandlerAdapter {
    ChannelHandlerContext ctx;

    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
      this.ctx = ctx;
    }

    public void sendToPort(final byte cmd, final byte[] data, final int off, final long len) throws IOException {
      ctx.fireChannelRead(Unpooled.wrappedBuffer(data, off, (int) len));
    }
  }


  private class TcpBridgeHandler extends ChannelDuplexHandler {
    private final IoSession ioSession;
    private final ClientChannel channel;

    private TcpBridgeHandler(final IoSession ioSession, final ClientChannel channel) {
      this.ioSession = ioSession;
      this.channel = channel;
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

    ChannelHandlerContext ctx;

    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
      this.ctx = ctx;
    }

    public void sendToPort(final byte cmd, final byte[] data, final int off, final long len) throws IOException {
      ctx.fireChannelRead(Unpooled.wrappedBuffer(data, off, (int) len));
    }
  }

  private void openChannel(final NettyIoSession ioSession, final InetSocketAddress remoteAddress, ChannelHandlerContext ctx, final ChannelPromise promise) throws IOException {
    final TcpInboundBridgeHandler tcpipHandler = new TcpInboundBridgeHandler();

    final TcpipClientChannel tcpipClientChannel = new TcpipClientChannel(TcpipClientChannel.Type.Direct, ioSession, new SshdSocketAddress(remoteAddress)) {
      @Override
      protected ChannelToPortHandler createChannelToPortHandler(final IoSession session) {
        return new ChannelToPortHandler(session, this) {
          @Override
          public void sendToPort(final byte cmd, final byte[] data, final int off, final long len) throws IOException {
            tcpipHandler.sendToPort(cmd, data, off, len);
          }
        };
      }
    };
    tcpipClientChannel.setStreaming(StreamingChannel.Streaming.Async);

    this.getClientSession(ioSession).getService(ConnectionService.class).registerChannel(tcpipClientChannel);

    tcpipClientChannel.open().addListener(openToNext(ctx, ctx0 -> {
      ctx.channel().pipeline().addAfter(SSHD_NETTY_ADAPTER_NAME, "SSHD-DATA-INBOUND-ADAPTER", tcpipHandler);
      ctx.channel().pipeline().addAfter(SSHD_NETTY_ADAPTER_NAME, "SSHD-DATA-OUTBOUND-ADAPTER", new TcpOutboundBridgeHandler(ioSession, tcpipClientChannel));
    }, promise));
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
