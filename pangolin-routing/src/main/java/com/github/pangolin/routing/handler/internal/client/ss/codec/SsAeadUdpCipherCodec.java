package com.github.pangolin.routing.handler.internal.client.ss.codec;

import com.github.pangolin.routing.handler.internal.client.ss.crypto.AeadCipherAlgorithm;
import freework.util.Bytes;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageCodec;
import org.bouncycastle.crypto.digests.SHA1Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

/**
 * @see <a href="https://github.com/shadowsocks/shadowsocks-org/wiki/AEAD-Ciphers#udp">UDP</a>
 */
public class SsAeadUdpCipherCodec extends MessageToMessageCodec<DatagramPacket, DatagramPacket> {
    private final byte[] masterKey;
    private final AeadCipherAlgorithm algorithm;
    private final SecureRandom random;

    private final int saltSize;
    private final int nonceSize;
    private final int tagSize;

    private final byte[] nonce;

    public SsAeadUdpCipherCodec(final byte[] masterKey, final AeadCipherAlgorithm algorithm, final SecureRandom random) {
        if (masterKey.length != algorithm.getKeySize()) {
            throw new IllegalArgumentException("master key size != crypt.getKeySize()");
        }
        this.masterKey = masterKey;
        this.algorithm = algorithm;
        this.random = random;

        this.saltSize = algorithm.getSaltSize();
        this.nonceSize = algorithm.getNonceSize();
        this.tagSize = algorithm.getTagSize();
        this.nonce = new byte[nonceSize];
    }

    @Override
    protected void encode(final ChannelHandlerContext ctx, final DatagramPacket in, final List<Object> out) throws Exception {
        /*-
         +------+-------------------+-----+
         | salt | encrypted payload | tag |
         +------+-------------------+-----+
         */
        final ByteBuf payload = in.content();
        final int len = payload.readableBytes();
        final byte[] salt = nextBytes(random, new byte[saltSize]);
        final byte[] subkey = generateSubkey(masterKey, salt);

        final byte[] chunk = Arrays.copyOf(salt, salt.length + len + tagSize);
        payload.readBytes(chunk, salt.length, len);

        final int written = encrypt(subkey, nonce, chunk, salt.length, len, chunk, salt.length);
        assert written == len + tagSize;
        out.add(in.replace(Unpooled.wrappedBuffer(chunk)));
    }

    @Override
    protected void decode(final ChannelHandlerContext ctx, final DatagramPacket in, List<Object> out) throws Exception {
        /*-
         +------+-------------------+-----+
         | salt | encrypted payload | tag |
         +------+-------------------+-----+
         */
        final ByteBuf chunk = in.content();
        if (chunk.hasArray()) {
            final byte[] buf = chunk.array();
            final int offset = chunk.arrayOffset();
            final int len = chunk.readableBytes();
            final byte[] subkey = generateSubkey(masterKey, Arrays.copyOfRange(buf, offset, offset + saltSize));
            final int written = decrypt(subkey, nonce, buf, offset + saltSize, len - saltSize, buf, offset + saltSize);
            out.add(in.replace(Unpooled.wrappedBuffer(buf, offset + saltSize, written)));
        } else {
            final byte[] salt = readAsBytes(chunk, saltSize);
            final byte[] subkey = generateSubkey(masterKey, salt);
            final byte[] payload = readAsBytes(chunk, chunk.readableBytes());
            final int written = decrypt(subkey, nonce, payload, 0, payload.length, payload, 0);
            out.add(in.replace(Unpooled.wrappedBuffer(payload, 0, written)));
        }
    }

    /**
     * @param masterKey the master key
     * @param salt      the non-secret encodeSalt
     * @return the encodeSubkey
     * @see <a href="https://github.com/shadowsocks/shadowsocks-org/wiki/AEAD-Ciphers#key-derivation">Key Derivation</a>
     */
    private byte[] generateSubkey(final byte[] masterKey, final byte[] salt) {
        final HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA1Digest());
        hkdf.init(new HKDFParameters(masterKey, salt, Bytes.toBytes("ss-encodeSubkey")));

        final byte[] okm = new byte[masterKey.length];
        final int written = hkdf.generateBytes(okm, 0, masterKey.length);
        assert written == masterKey.length;
        return okm;
    }

    private int encrypt(final byte[] subkey, final byte[] nonce,
                        final byte[] inBytes, int inOffset, int inLength,
                        final byte[] outBytes, final int outOffset) throws Exception {
        return algorithm.getCipher(true, subkey, nonce).doFinal(inBytes, inOffset, inLength, outBytes, outOffset);
    }

    private int decrypt(final byte[] subkey, final byte[] nonce,
                        final byte[] inBytes, int inOffset, int inLength,
                        final byte[] outBytes, final int outOffset) throws Exception {
        return algorithm.getCipher(false, subkey, nonce).doFinal(inBytes, inOffset, inLength, outBytes, outOffset);
    }

    private byte[] nextBytes(final SecureRandom random, final byte[] bytes) {
        random.nextBytes(bytes);
        return bytes;
    }

    private byte[] readAsBytes(final ByteBuf buf, final int len) {
        final byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return bytes;
    }

}