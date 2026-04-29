package com.github.pangolin.util;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.base64.Base64;
import io.netty.handler.codec.base64.Base64Dialect;
import io.netty.util.CharsetUtil;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;

import static com.github.pangolin.util.Constants.*;

public class Util {
    private static final String HMAC_SHA256 = "HmacSHA256";

    private Util() {
    }

    public static String last(final Map<String, List<String>> params, final String key) {
        final List<String> values = null != params ? params.get(key) : null;
        return null != values && !values.isEmpty() ? values.get(values.size() - 1) : null;
    }

    public static String urlSafeBase64Encode(final ByteBuf payload) {
        final ByteBuf base64 = Base64.encode(payload, Base64Dialect.URL_SAFE);
        try {
            return base64.toString(CharsetUtil.UTF_8);
        } finally {
            base64.release();
        }
    }

    public static ByteBuf urlSafeBase64Decode(final String payload) {
        final ByteBuf encoded = Unpooled.wrappedBuffer(payload.getBytes(CharsetUtil.UTF_8));
        try {
            return Base64.decode(encoded, Base64Dialect.URL_SAFE);
        } finally {
            encoded.release();
        }
    }

    public static InetSocketAddress readSocketAddress(final ByteBuf in, final boolean resolve) throws UnknownHostException {
        final byte addressType = in.readByte();
        if (ATYPE_IPv4 == addressType) {
            final byte[] addr = new byte[IPv4_ADDR_SIZE];
            in.readBytes(addr);
            return new InetSocketAddress(InetAddress.getByAddress(addr), in.readUnsignedShort());
        } else if (ATYPE_DOMAIN == addressType) {
            final String domain = in.readCharSequence(in.readUnsignedByte(), CharsetUtil.UTF_8).toString();
            final int port = in.readUnsignedShort();
            return resolve ? new InetSocketAddress(domain, port) : InetSocketAddress.createUnresolved(domain, port);
        } else if (ATYPE_IPv6 == addressType) {
            final byte[] addr = new byte[IPv6_ADDR_SIZE];
            in.readBytes(addr);
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

    public static byte[] hmacSha256(final ByteBuf buf, final int offset, final int len, final byte[] secretKey) {
        try {
            final Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secretKey, HMAC_SHA256));
            if (buf.readableBytes() > 0) {
                final ByteBuffer[] views = buf.nioBuffers(offset, len);
                for (ByteBuffer view : views) {
                    mac.update(view);
                }
            }
            return mac.doFinal();
        } catch (final InvalidKeyException e) {
            throw new IllegalStateException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}