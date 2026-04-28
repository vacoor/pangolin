package com.github.pangolin.agent.servlet;

import com.github.pangolin.util.Channels;
import com.github.pangolin.util.Util;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

import static com.github.pangolin.util.Constants.*;

@Deprecated
// @ServerEndpoint(value = "/ws/bridge", configurator = WebSocketBridgeEndpoint.AuthenticationConfigurator.class)
public class WebSocketBridgeEndpoint {
    private static final int MIN_TRUST_PAYLOAD_LENGTH = 1 + 1 + 1 + 1 + IPv4_ADDR_SIZE + 2;

    private static final int PAYLOAD_TS_SIZE = 8;
    private static final int PAYLOAD_NONCE_SIZE = 8;
    private static final int PAYLOAD_HMAC_SIZE = 32;
    private static final int MIN_NOT_TRUST_PAYLOAD_LENGTH = MIN_TRUST_PAYLOAD_LENGTH + PAYLOAD_TS_SIZE + PAYLOAD_NONCE_SIZE + PAYLOAD_HMAC_SIZE;

    private static final int MAX_TIMESTAMP_DIFF_SECONDS = 30;

    private Channel channel;
    private NioEventLoopGroup brGroup;

    @OnOpen
    public void onOpen(final Session session) throws InterruptedException, IOException {
        final Map<String, List<String>> params = session.getRequestParameterMap();
        final List<String> encodedPayload = params.get("access_token");
        final String encodedPayloadToUse = null != encodedPayload && !encodedPayload.isEmpty() ? encodedPayload.get(encodedPayload.size() - 1) : null;
        if (null == encodedPayloadToUse) {
            session.close(new CloseReason(CloseReason.CloseCodes.NOT_CONSISTENT, ""));
            return;
        }

        final ByteBuf payload = Util.urlSafeBase64Decode(encodedPayloadToUse);
        try {
            final CloseReason.CloseCodes status = checkPayload(payload);
            if (null != status) {
                session.close(new CloseReason(status, status.name()));
                return;
            }
            final byte version = payload.readByte();
            if (VER_1 != version) {
                session.close(new CloseReason(CloseReason.CloseCodes.NOT_CONSISTENT, String.format("unsupported version: %s, (expected: %s)", version, VER_1)));
                return;
            }
            final byte cmd = payload.readByte();
            if (CMD_CONNECT != cmd) {
                session.close(new CloseReason(CloseReason.CloseCodes.NOT_CONSISTENT, String.format("unsupported command type: %s, (expected: %s)", version, VER_1)));
                return;
            }

            final byte rsv = payload.readByte();
            if (RSV != rsv) {
                session.close(new CloseReason(CloseReason.CloseCodes.NOT_CONSISTENT, String.format("unsupported rsv: %s, (expected: %s)", rsv, RSV)));
                return;
            }

            final InetSocketAddress target = Util.readSocketAddress(payload, true);

            brGroup = new NioEventLoopGroup(1);
            channel = Channels.open(target, true, brGroup, new ChannelInboundHandlerAdapter() {

                @Override
                public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
                    if (msg instanceof ByteBuf) {
                        try {
                            if (session.isOpen()) {
                                session.getBasicRemote().sendBinary(((ByteBuf) msg).nioBuffer());
                            }
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
                            if (session.isOpen()) {
                                session.close(new CloseReason(CloseReason.CloseCodes.CLOSED_ABNORMALLY, future.cause().getMessage()));
                            }
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
        } finally {
            payload.release();
        }
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

    private CloseReason.CloseCodes checkPayload(final ByteBuf payload) {
        final int len = null != payload ? payload.readableBytes() : 0;
        if (len < MIN_NOT_TRUST_PAYLOAD_LENGTH) {
            return CloseReason.CloseCodes.NOT_CONSISTENT;
        }

        final int offset = payload.readerIndex();
        final byte[] expected = ByteBufUtil.getBytes(payload, offset + len - PAYLOAD_HMAC_SIZE, PAYLOAD_HMAC_SIZE);
        final byte[] computed = hmac(payload, offset, len - PAYLOAD_HMAC_SIZE);
        if (!MessageDigest.isEqual(expected, computed)) {
            return CloseReason.CloseCodes.VIOLATED_POLICY;
        }

        final long timestamp = payload.getLong(len - PAYLOAD_HMAC_SIZE - PAYLOAD_NONCE_SIZE - PAYLOAD_TS_SIZE);
        if (Math.abs(System.currentTimeMillis() / 1000 - timestamp) > MAX_TIMESTAMP_DIFF_SECONDS) {
            return CloseReason.CloseCodes.VIOLATED_POLICY;
        }

        // XXX check nonce

        return null;
    }

    private byte[] hmac(final ByteBuf payload, final int offset, final int len) {
        final String secretKey = System.getProperty("websocket.bridge.secretKey", "c254dacd0cde3be75ac2988f691ec105");
        final byte[] secretKeyBytes = secretKey.getBytes(StandardCharsets.UTF_8);
        return Util.hmacSha256(payload, offset, len, secretKeyBytes);
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