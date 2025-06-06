package com.github.pangolin.agent.servlet;

import com.github.pangolin.util.Channels;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.ReferenceCountUtil;

import javax.websocket.*;
import javax.websocket.server.HandshakeRequest;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;

@ServerEndpoint(value = "/ws/bridge", configurator = WebSocketBridgeEndpoint.AuthenticationConfigurator.class)
public class WebSocketBridgeEndpoint {
    private Channel channel;

    @OnOpen
    public void onOpen(final Session session) throws InterruptedException, IOException {
        final List<String> target = session.getRequestParameterMap().get("target");
        final String targetToUse = target.size() > 0 ? target.get(target.size() - 1) : null;
        final InetSocketAddress destination = parseTarget(targetToUse);
        if (null == destination) {
            session.close();
            return;
        }

        final NioEventLoopGroup brGroup = new NioEventLoopGroup(1);

        ChannelFuture future = Channels.open(destination, true, brGroup, new ChannelInboundHandlerAdapter() {

            @Override
            public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
                if (msg instanceof ByteBuf) {
                    try {
                        session.getBasicRemote().sendBinary(((ByteBuf) msg).nioBuffer());
                    } finally {
                        ReferenceCountUtil.release(msg);
                    }
                } else {
                    ctx.fireChannelRead(msg);
                }
            }

        });
        channel = future.channel();
        channel.closeFuture().addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future) throws Exception {
                if (session.isOpen()) {
                    session.close();;
                }
                brGroup.shutdownGracefully();
            }
        });
    }

    private InetSocketAddress parseTarget(final String target) {
        if (null == target || (target.contains("://") && !target.startsWith("tcp://"))) {
            return null;
        }
        final String hostAndPort = target.startsWith("tcp://") ? target.substring(6) : target;
        // resolve ?
        final String[] split = hostAndPort.split(":", 2);
        return InetSocketAddress.createUnresolved(split[0], Integer.parseInt(split[1]));
    }

    @OnClose
    public void onClose() {
        if (null != channel && channel.isOpen()) {
            channel.close();
        }
    }

    @OnMessage
    public void onMessage(final byte[] bytes) {
        if (null != channel && channel.isOpen()) {
            channel.writeAndFlush(Unpooled.wrappedBuffer(bytes));
        }
    }


    public static class AuthenticationConfigurator extends ServerEndpointConfig.Configurator {

        @Override
        public void modifyHandshake(final ServerEndpointConfig sec,
                                    final HandshakeRequest request,
                                    final HandshakeResponse response) {
//            response.getHeaders().clear();
//            throw new IllegalArgumentException("xx");
        }

    }
}