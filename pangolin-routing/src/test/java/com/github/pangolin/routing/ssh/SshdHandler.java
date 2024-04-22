package com.github.pangolin.routing.ssh;

import com.github.pangolin.util.Channels;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.ClientBuilder;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelDirectTcpip;
import org.apache.sshd.client.future.AuthFuture;
import org.apache.sshd.client.future.OpenFuture;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.channel.Channel;
import org.apache.sshd.common.channel.ChannelListener;
import org.apache.sshd.common.channel.RequestHandler;
import org.apache.sshd.common.channel.StreamingChannel;
import org.apache.sshd.common.forward.ChannelToPortHandler;
import org.apache.sshd.common.forward.TcpipClientChannel;
import org.apache.sshd.common.future.SshFutureListener;
import org.apache.sshd.common.io.IoSession;
import org.apache.sshd.common.session.ConnectionService;
import org.apache.sshd.common.session.helpers.AbstractSession;
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

/**
 * TODO DOC ME!.
 *
 * @author changhe.yang
 * @since 20240422
 */
@Slf4j
public class SshdHandler extends ChannelDuplexHandler {
  private ClientSession clientSession;
  private NettyIoSession nettyIoSession;

  @Override
  public void channelActive(final ChannelHandlerContext ctx) throws Exception {
    super.channelActive(ctx);
  }

  @Override
  public void connect(final ChannelHandlerContext ctx, final SocketAddress remoteAddress, final SocketAddress localAddress, final ChannelPromise promise) throws Exception {
    final SshClient client = ClientBuilder.builder().build();
    client.setIoServiceFactoryFactory(new NettyIoServiceFactoryFactory());
    client.start();

    final InetSocketAddress sa = new InetSocketAddress("139.224.238.197", 22);
    NettyIoConnector connector = new NettyIoConnector((NettyIoServiceFactory) client.getIoServiceFactory(), client.getSessionFactory());

    final NettyIoSession nettyIoSession = new NettyIoSession(connector, client.getSessionFactory(), null);
    Field adapter = NettyIoSession.class.getDeclaredField("adapter");
    adapter.setAccessible(true);
    final ChannelInboundHandler adapterHandler = (ChannelInboundHandler) adapter.get(nettyIoSession);
    ctx.pipeline().addAfter(ctx.name(), "SSHD-ADAPTER", adapterHandler);

    promise.addListener(new GenericFutureListener<Future<? super Void>>() {
      @Override
      public void operationComplete(final Future<? super Void> future) throws Exception {
        if (future.isSuccess()) {
          ctx.channel().eventLoop().execute(new Runnable() {
            @Override
            public void run() {
              try {
                ClientSession clientSession = (ClientSession) nettyIoSession.getAttribute(AbstractSession.SESSION);
                clientSession.setUsername("root");
                clientSession.addPasswordIdentity("Aimon2015Aliyun!123");
                clientSession.auth().addListener(new SshFutureListener<AuthFuture>() {
                  @Override
                  public void operationComplete(final AuthFuture authFuture) {
                    System.out.println("AUTH: " + authFuture.isSuccess());
                    if (authFuture.isSuccess()) {
                      SshdHandler.this.clientSession = clientSession;
                      ctx.channel().eventLoop().execute(() -> openChannel(clientSession, nettyIoSession, ctx));
                    }
                  }
                });
              } catch (Exception ex) {
                ex.printStackTrace();
              }
            }
          });
        }
      }
    });

    super.connect(ctx, sa, localAddress, promise);
  }

  @Override
  public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
    super.channelRead(ctx, msg);
  }

  @Override
  public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) throws Exception {
    super.write(ctx, msg, promise);
  }

  private void openChannel(final ClientSession clientSession, final NettyIoSession ioSession, final ChannelHandlerContext ctx) {
    final TcpipClientChannel tcpipClientChannel = new TcpipClientChannel(TcpipClientChannel.Type.Direct, ioSession, new SshdSocketAddress("www.baidu.com", 80)) {
      @Override
      protected ChannelToPortHandler createChannelToPortHandler(final IoSession session) {
        return super.createChannelToPortHandler(session);
      }
    };
    tcpipClientChannel.setStreaming(StreamingChannel.Streaming.Async);
//    session.suspendRead();

//    final ClientSessionImpl clientSession = (ClientSessionImpl) AbstractSession.getSession(session);
    try {
      ConnectionService service = clientSession.getService(ConnectionService.class);
      service.registerChannel(tcpipClientChannel);

      tcpipClientChannel.open().addListener(new SshFutureListener<OpenFuture>() {
        @Override
        public void operationComplete(final OpenFuture future) {
          System.out.println(future.isOpened());
          if (future.isOpened()) {
//            ctx.pipeline().addAfter("SSHD-ADAPTER", null, new DirectHandler(session, channel));
            ioSession.suspendRead();

            byte[] bytes = "GET / HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.UTF_8);
            ByteArrayBuffer buff = new ByteArrayBuffer(bytes);
            try {
              ThreadUtils.runAsInternal(tcpipClientChannel.getAsyncIn(), out -> out.writeBuffer(buff).addListener(f -> ioSession.resumeRead()));
            } catch (IOException e) {
              e.printStackTrace();
            }
          }
          /*-
           * org.apache.sshd.common.forward.SocksProxy.Socks4.onChannelOpened
           */
          /*
          session.resumeRead();;
          Buffer buffer = new ByteArrayBuffer(Long.SIZE, false);
          buffer.putByte((byte) 0x00);
          Throwable t = future.getException();
          if (t != null) {
            service.unregisterChannel(channel);
            channel.close(true);
            buffer.putByte((byte) 0x5b);
          } else {
            buffer.putByte((byte) 0x5a);
          }
            buffer.putByte((byte) 0x00);
            buffer.putByte((byte) 0x00);
            buffer.putByte((byte) 0x00);
            buffer.putByte((byte) 0x00);
            buffer.putByte((byte) 0x00);
            buffer.putByte((byte) 0x00);
            try {
              session.writeBuffer(buffer);
            } catch (Exception e) {
              // TODO Auto-generated catch block
              log.error("Failed ({}) to send channel open packet for {}: {}", e.getClass().getSimpleName(), channel,
                  e.getMessage());
              throw new IllegalStateException("Failed to send packet", e);
          }
          */
        }
      });
    } catch (IOException e) {
      e.printStackTrace();
    }
  }


  public static void main(String[] args) throws InterruptedException {
    Channels.open("127.0.0.1", 22, true, new NioEventLoopGroup(), new ChannelInitializer<SocketChannel>() {
      @Override
      protected void initChannel(final SocketChannel channel) throws Exception {
        channel.pipeline().addLast(new SshdHandler());
        channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
          @Override
          public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
            super.channelRead(ctx, msg);
          }
        });
      }
    }).channel().closeFuture().sync();
  }
}
