package com.github.pangolin.routing.acceptor.extra;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import com.github.pangolin.routing.acceptor.Acceptor;
import com.github.pangolin.routing.context.RouteContext;
import com.github.pangolin.routing.route.RouteRegistry;
import com.github.pangolin.server.NettyServer;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;

/**
 *
 */
@Slf4j
public class RuleExporterAcceptor implements Acceptor {
    private final int listenPort;
    private final int pacProxyPort;

    public RuleExporterAcceptor(final int pacProxyPort) {
        this(9090, pacProxyPort);
    }

    public RuleExporterAcceptor(final int listenPort, final int pacProxyPort) {
        this.listenPort = listenPort;
        this.pacProxyPort = pacProxyPort;
    }

    @Override
    public ChannelFuture start(final RouteContext context) throws Exception {
        return new NettyServer(listenPort).start(true, new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
                ch.pipeline().addLast(
                        new SwitchyRuleConfigurationServerHandler((RouteRegistry) context),
                        new ProxyAutoConfigurationServerHandler((RouteRegistry) context, pacProxyPort)
                );

                ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
                        ctx.writeAndFlush(new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.NOT_FOUND)).addListener(ChannelFutureListener.CLOSE);
                    }
                });
            }
        }).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future) throws Exception {
                if (future.isSuccess()) {
                    final InetSocketAddress localAddress = (InetSocketAddress) future.channel().localAddress();
                    log.info("Web interface started on port: {} ({})", localAddress.getPort(), localAddress);
                } else {
                    future.cause().printStackTrace();
                }
            }
        });
    }

}
