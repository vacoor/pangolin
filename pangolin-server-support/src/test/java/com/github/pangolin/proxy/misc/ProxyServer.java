package com.github.pangolin.proxy.misc;

import com.github.pangolin.proxy.server.http.HttpProxyServerHandler;
import com.github.pangolin.proxy.server.socks.v4.Socks4ProxyServerHandler;
import com.github.pangolin.proxy.server.socks.v5.Socks5ProxyServerHandler;
import com.github.pangolin.server.NettyServer;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.socksx.v4.Socks4Message;
import io.netty.handler.codec.socksx.v5.Socks5Message;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLException;
import java.net.InetSocketAddress;
import java.security.cert.CertificateException;
import java.util.concurrent.ExecutionException;

@Slf4j
public class ProxyServer extends NettyServer {

    public ProxyServer(final int listenPort) {
        this(null, listenPort);
    }

    public ProxyServer(final String listenHost, final int listenPort) {
        super(listenHost, listenPort);
    }

    public ChannelFuture start() throws InterruptedException, CertificateException, SSLException {
        return super.start(true, new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
                ch.pipeline().addLast(new ProxyPortUnificationServerHandler());
                ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
                        final ChannelPipeline cp = ctx.pipeline();
                        if (msg instanceof HttpMessage) {
                            cp.replace(this, null, new HttpProxyServerHandler());
                            ctx.fireChannelRead(msg);
                        } else if (msg instanceof Socks4Message) {
                            cp.replace(this, null, new Socks4ProxyServerHandler());
                            ctx.fireChannelRead(msg);
                        } else if (msg instanceof Socks5Message) {
                            cp.replace(this, null, new Socks5ProxyServerHandler());
                            ctx.fireChannelRead(msg);
                        } else {
                            ReferenceCountUtil.release(msg);
                            log.warn("{} Unable to receive HTTP/SOCKS4/5 message: {}", ctx.channel(), msg.getClass().getName());
                            ctx.close();
                        }
                    }
                });
            }
        });
    }

    public static void main(String[] args) throws InterruptedException, SSLException, CertificateException, ExecutionException {
        new ProxyServer(1080).start().addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future) throws Exception {
                if (future.isSuccess()) {
                    final InetSocketAddress localAddress = (InetSocketAddress) future.channel().localAddress();
                    System.out.println(String.format("Server started on %s:%s", localAddress.getHostString(), localAddress.getPort()));
                } else {
                    future.cause().printStackTrace();
                }
            }
        }).sync().channel().closeFuture().sync().await();
    }

}
