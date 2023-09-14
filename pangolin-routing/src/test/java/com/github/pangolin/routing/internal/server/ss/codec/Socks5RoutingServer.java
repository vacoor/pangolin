package com.github.pangolin.routing.internal.server.ss.codec;

import com.github.pangolin.routing.RoutingRule;
import com.github.pangolin.routing.internal.client.Socks5ProxyHandler;
import com.github.pangolin.routing.pattern.DomainPattern;
import com.github.pangolin.routing.resolver.RoutingFileParser;
import com.github.pangolin.server.NettyServer;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;

import javax.net.ssl.SSLException;
import java.net.InetSocketAddress;
import java.security.cert.CertificateException;
import java.util.Arrays;
import java.util.List;

/**
 *
 */
public class Socks5RoutingServer extends NettyServer {

    public Socks5RoutingServer(final int listenPort) {
        super(listenPort);
    }

    public Socks5RoutingServer(final String listenHost, final int listenPort) {
        super(listenHost, listenPort);
    }

    public Socks5RoutingServer(final String listenHost, final int listenPort, final EventLoopGroup bossGroup, final EventLoopGroup workerGroup) {
        super(listenHost, listenPort, bossGroup, workerGroup);
    }

    public ChannelFuture start(final List<RoutingRule> routings) throws InterruptedException, CertificateException, SSLException {
        return super.start(true, new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
                ch.pipeline().addLast(new Socks5RoutingServerHandler(routings));
            }
        });
    }


    public static void main(String[] args) throws Exception {
        // final List<RoutingRule> routingRules = RoutingFileParser.parse();

        final List<RoutingRule> routingRules = Arrays.asList(new RoutingRule(new DomainPattern("**"), () ->
                new Socks5ProxyHandler(new InetSocketAddress("127.0.0.1", 8388))
        ));

        new Socks5RoutingServer(1080).start(routingRules).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future) throws Exception {
                if (future.isSuccess()) {
                    final InetSocketAddress localAddress = (InetSocketAddress) future.channel().localAddress();
                    System.out.println(String.format("Server started on %s:%s", localAddress.getHostString(), localAddress.getPort()));
                } else {
                    future.cause().printStackTrace();
                }
            }
        }).sync().channel().closeFuture().sync();
        /*-
        Connect through local to http://bing.com/ failed.
        Error: Connection refused
         */
    }

}
