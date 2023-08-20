package com.github.pangolin.proxy.server;

import com.github.pangolin.proxy.AbstractNettyServer;
import com.github.pangolin.util.WebSocketUtils;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLException;
import java.security.cert.CertificateException;

@Slf4j
public class WebSocketServerTunnelHandlerMain extends ChannelInboundHandlerAdapter {
    private final NioEventLoopGroup eventLoopGroup;

    public WebSocketServerTunnelHandlerMain(final NioEventLoopGroup eventLoopGroup) {
        this.eventLoopGroup = eventLoopGroup;
    }

    @Override
    public void channelRead(final ChannelHandlerContext webSocketContext, final Object msg) throws Exception {
        System.out.println("xx");
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext webSocketContext, final Throwable cause) throws Exception {
        log.warn("{} Software caused connection abort: {}", webSocketContext.channel(), cause.getMessage());
        WebSocketUtils.internalErrorClose(webSocketContext, cause.getMessage());
    }


    public static void main(String[] args) throws InterruptedException, SSLException, CertificateException {
        final int MAX_HTTP_CONTENT_LENGTH = 1024 * 1024 * 8;
        final AbstractNettyServer server = new AbstractNettyServer(8888) {
            public NioEventLoopGroup getBossGroup() {
                return this.bossGroup;
            }
        };
        server.start(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
                final NioEventLoopGroup g = new NioEventLoopGroup(0, new DefaultThreadFactory("WebSocketTunnelServer-boss", true));
                ch.pipeline().addLast(
                        new HttpServerCodec(),
                        new HttpObjectAggregator(MAX_HTTP_CONTENT_LENGTH),
                        new WebSocketServerTunnelHandler("/ws", "*", false, 65536, true, true)
//                        new WebSocketServerProtocolHandler("/ws", "", false, 65536, true, true)

                );
                ch.pipeline().addLast(new WebSocketServerTunnelHandlerMain(g));

//                ch.pipeline().addLast(new Socks5CommandHandler(g));
            }
        }).closeFuture().sync();
    }
}