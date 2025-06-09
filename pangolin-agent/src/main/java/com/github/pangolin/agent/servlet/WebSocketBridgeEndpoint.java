package com.github.pangolin.agent.servlet;

import com.github.pangolin.util.Channels;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.ReferenceCountUtil;

import javax.websocket.CloseReason;
import javax.websocket.HandshakeResponse;
import javax.websocket.OnClose;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.HandshakeRequest;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;

@ServerEndpoint(value = "/ws/bridge", configurator = WebSocketBridgeEndpoint.AuthenticationConfigurator.class)
public class WebSocketBridgeEndpoint {
    private Channel channel;

    @OnOpen
    public void onOpen(final Session session) throws InterruptedException, IOException {
        final Map<String, List<String>> params = session.getRequestParameterMap();
        final List<String> target = params.get("target");
        final String targetToUse = null != target && target.size() > 0 ? target.get(target.size() - 1) : null;

        if (!authenticate(session)) {
            session.close(new CloseReason(CloseReason.CloseCodes.CANNOT_ACCEPT, ""));
            return;
        }

        final InetSocketAddress destination = parseTarget(targetToUse);
        if (null == destination) {
            session.close(new CloseReason(CloseReason.CloseCodes.CANNOT_ACCEPT, ""));
            return;
        }

        final NioEventLoopGroup brGroup = new NioEventLoopGroup(1);
        channel = Channels.open(destination, true, brGroup, new ChannelInboundHandlerAdapter() {

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

        }).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future) throws Exception {
                if (!future.isSuccess()) {
                    try {
                        session.close(new CloseReason(CloseReason.CloseCodes.CLOSED_ABNORMALLY, future.cause().getMessage()));
                    } finally {
                        brGroup.shutdownGracefully();
                    }
                }
            }
        }).channel().closeFuture().addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future) throws Exception {
                try {
                    if (session.isOpen()) {
                        session.close();
                    }
                } finally {
                    brGroup.shutdownGracefully();
                }
            }
        }).channel();
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

    private boolean authenticate(final Session session) {
        final Map<String, List<String>> params = session.getRequestParameterMap();
        final List<String> accessToken = params.get("access_token");
        final String accessTokenToUse = null != accessToken && accessToken.size() > 0 ? accessToken.get(accessToken.size() - 1) : null;
        return System.getProperty("websocket.bridge.access_token", "c254dacd0cde3be75ac2988f691ec105").equals(accessTokenToUse);
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