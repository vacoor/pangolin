package com.github.pangolin.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.util.CharsetUtil;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import static com.github.pangolin.server.WebSocketBridgeServerEngine.*;

public abstract class WebSocketBridgeUtil {

    private static final SecureRandom RNG = new SecureRandom();

    private WebSocketBridgeUtil() {
    }

    public static InetSocketAddress readSocketAddress(final ByteBuf in) throws UnknownHostException {
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

    public static ByteBuf skipSocketAddress(final ByteBuf in) throws UnknownHostException {
        final byte addressType = in.readByte();
        if (ATYPE_IPv4 == addressType) {
            in.skipBytes(IPv4_ADDR_SIZE + 2);
        } else if (ATYPE_DOMAIN == addressType) {
            in.skipBytes(in.readUnsignedByte() + 2);
        } else if (ATYPE_IPv6 == addressType) {
            in.skipBytes(IPv6_ADDR_SIZE + 2);
        } else {
            throw new UnknownHostException("address type: " + addressType);
        }
        return in;
    }

    public static ByteBuf writeSignature(final ByteBuf buf, final byte[] secretKey) throws NoSuchAlgorithmException, InvalidKeyException {
        buf.writeLong(System.currentTimeMillis() / 1000L);  // TS: unix seconds
        final byte[] nonce = new byte[8];
        RNG.nextBytes(nonce);
        buf.writeBytes(nonce);                              // NONCE: 8B random
        buf.writeBytes(hmacSha256(buf, buf.readerIndex(), buf.readableBytes(), secretKey));
        return buf;
    }

    public static ByteBuf verifySignature(final ByteBuf buf, final byte[] secretKey) throws NoSuchAlgorithmException, InvalidKeyException {
        final int offset = buf.readerIndex();
        final int len = buf.readableBytes();

        final byte[] expected = ByteBufUtil.getBytes(buf, len - 32, 32);
        final byte[] computed = hmacSha256(buf, offset, len - 32, secretKey);
        return MessageDigest.isEqual(expected, computed) ? buf : null;
    }

    private static byte[] hmacSha256(final ByteBuf buf, final int offset, final int len, final byte[] secretKey) throws NoSuchAlgorithmException, InvalidKeyException {
        final Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secretKey, "HmacSHA256"));
        if (buf.readableBytes() > 0) {
            final ByteBuffer[] views = buf.nioBuffers(offset, len);
            for (ByteBuffer view : views) {
                mac.update(view);
            }
        }
        return mac.doFinal();
    }

}
