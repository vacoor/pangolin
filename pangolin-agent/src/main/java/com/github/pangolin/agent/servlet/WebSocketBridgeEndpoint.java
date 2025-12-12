package com.github.pangolin.agent.servlet;

import com.github.pangolin.util.Channels;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.base64.Base64;
import io.netty.handler.codec.base64.Base64Dialect;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;

import javax.websocket.*;
import javax.websocket.server.HandshakeRequest;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;

@ServerEndpoint(value = "/ws/bridge", configurator = WebSocketBridgeEndpoint.AuthenticationConfigurator.class)
public class WebSocketBridgeEndpoint {
    private Channel channel;

    @OnOpen
    public void onOpen(final Session session) throws InterruptedException, IOException {
        final Map<String, List<String>> params = session.getRequestParameterMap();
        final List<String> accessToken = params.get("access_token");
        final String accessTokenToUse = null != accessToken && accessToken.size() > 0 ? accessToken.get(accessToken.size() - 1) : null;
        if (null == accessTokenToUse) {
            session.close(new CloseReason(CloseReason.CloseCodes.NOT_CONSISTENT, ""));
            return;
        }

        final ByteBuf in = Base64.decode(Unpooled.wrappedBuffer(accessTokenToUse.getBytes()), Base64Dialect.URL_SAFE);
        final byte version = in.readByte();
        if (VER_1_1 != version) {
            session.close(new CloseReason(
                    CloseReason.CloseCodes.NOT_CONSISTENT,
                    String.format("unsupported version: %s, (expected: %s)", version, VER_1_1)
            ));
        }
        final String accessKey = in.readCharSequence(in.readUnsignedByte(), CharsetUtil.UTF_8).toString();
        final byte cmd = in.readByte();
        if (CMD_CONNECT != cmd) {
            session.close(new CloseReason(
                    CloseReason.CloseCodes.NOT_CONSISTENT,
                    String.format("unsupported command type: %s, (expected: %s)", version, VER_1_1)
            ));
        }

        final byte rsv = in.readByte();
        if (0 != rsv) {
            session.close(new CloseReason(
                    CloseReason.CloseCodes.NOT_CONSISTENT,
                    String.format("unsupported rsv: %s, (expected: %s)", rsv, 0)
            ));
        }
        final InetSocketAddress target = parseSocketAddress(in);

        if (null == target || !authenticate(accessKey)) {
            session.close(new CloseReason(CloseReason.CloseCodes.NOT_CONSISTENT, ""));
            return;
        }

        final NioEventLoopGroup brGroup = new NioEventLoopGroup(1);
        channel = Channels.open(target, true, brGroup, new ChannelInboundHandlerAdapter() {

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
        }).sync().channel().closeFuture().addListener(new ChannelFutureListener() {
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

    private boolean authenticate(final String accessKey) {
        return System.getProperty("websocket.bridge.access_key", "c254dacd0cde3be75ac2988f691ec105").equals(accessKey);
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

    private static final byte IPv4_ADDR_SIZE = 4;
    private static final byte IPv6_ADDR_SIZE = 16;

    private static final byte VER_1_1 = 0x01;

    private static final byte CMD_CONNECT = 0x01;

    private static final byte ATYPE_IPv4 = 0x01;
    private static final byte ATYPE_DOMAIN = 0x03;
    private static final byte ATYPE_IPv6 = 0x04;

    private static InetSocketAddress parseSocketAddress(final ByteBuf in) throws UnknownHostException {
        final byte addressType = in.readByte();
        if (ATYPE_IPv4 == addressType) {
            final byte[] addr = ByteBufUtil.getBytes(in.readBytes(IPv4_ADDR_SIZE));
            return new InetSocketAddress(InetAddress.getByAddress(addr), in.readUnsignedShort());
        } else if (ATYPE_DOMAIN == addressType) {
            final String domain = in.readCharSequence(in.readUnsignedByte(), CharsetUtil.UTF_8).toString();
            return InetSocketAddress.createUnresolved(domain, in.readUnsignedShort());
        } else if (ATYPE_IPv6 == addressType) {
            final byte[] addr = ByteBufUtil.getBytes(in.readBytes(IPv6_ADDR_SIZE));
            return new InetSocketAddress(InetAddress.getByAddress(addr), in.readUnsignedShort());
        }
        throw new UnknownHostException("address type: " + addressType);
    }
}