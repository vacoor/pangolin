package com.github.pangolin.routing.ssh;

import com.github.pangolin.routing.handler.internal.client.SshProxyHandler;
import com.github.pangolin.routing.handler.internal.server.Socks5ProxyServerHandler;
import com.github.pangolin.server.NettyServer;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.cert.CertificateException;

/**
 * TODO DOC ME!.
 *
 * @author changhe.yang
 * @since 20240422
 */
@Slf4j
public class SshProxyServer {

  public static void main(String[] args) throws InterruptedException, SSLException, CertificateException {
    String hostname = "";
    int port = 22;
    String username = "root";
    String password = "";

    final String portStr = System.getProperty("server.port", "1081");
    final int proxyServerPort = Integer.parseInt(portStr);
    final NettyServer server = new NettyServer(proxyServerPort);

    AbstractProxySocketChannelFactory factory = new AbstractProxySocketChannelFactory() {

      @Override
      protected ChannelHandler select(final SocketAddress destinationAddress) {
        return new SshProxyHandler(hostname, port, username, password);
      }
    };
    ChannelFuture proxyServerChannel = server.start(true, new ChannelInitializer<SocketChannel>() {
      @Override
      protected void initChannel(final SocketChannel ch) throws Exception {
        ch.pipeline().addLast(new Socks5ProxyServerHandler(null, null, factory));
      }
    });

    proxyServerChannel.addListener(new ChannelFutureListener() {
      @Override
      public void operationComplete(final ChannelFuture future) throws Exception {
        if (future.isSuccess()) {
          final InetSocketAddress localAddress = (InetSocketAddress) future.channel().localAddress();
          log.info("Mixed proxy started on port {}", localAddress.getPort());
        } else {
          future.cause().printStackTrace();
        }
      }
    }).sync().channel().closeFuture().sync();
  }
}
