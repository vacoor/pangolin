package com.github.pangolin.routing.handler.codec.ss;

import com.github.pangolin.routing.handler.codec.ss.crypto.AeadCipherAlgorithm;
import com.github.pangolin.routing.handler.codec.ss.crypto.SsSecretKey;
import com.github.pangolin.routing.handler.codec.ss.crypto.SsSubKey;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageCodec;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

/**
 * UDP AEAD codec.
 *
 * @see <a href="https://github.com/shadowsocks/shadowsocks-org/wiki/AEAD-Ciphers#udp">UDP</a>
 */
public class SsDatagramPacketAeadCryptCodec extends MessageToMessageCodec<DatagramPacket, DatagramPacket> {
    private final byte[] masterKey;
    private final AeadCipherAlgorithm algorithm;
    private final SecureRandom random;

    private final byte[] nonce;

    public SsDatagramPacketAeadCryptCodec(final AeadCipherAlgorithm algorithm, final String password, final SecureRandom random) {
        this(generateMasterKey(algorithm, password), algorithm, random);
    }

    private static byte[] generateMasterKey(final AeadCipherAlgorithm algorithm, final String password) {
        return SsSecretKey.generateKey(password, algorithm.getKeySize());
    }

    public SsDatagramPacketAeadCryptCodec(final byte[] masterKey, final AeadCipherAlgorithm algorithm, final SecureRandom random) {
        if (masterKey.length != algorithm.getKeySize()) {
            throw new IllegalArgumentException("master key size != crypt.getKeySize()");
        }
        this.masterKey = masterKey;
        this.algorithm = algorithm;

        this.random = random;
        this.nonce = new byte[algorithm.getNonceSize()];
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
        final byte[] salt = nextBytes(random, new byte[algorithm.getSaltSize()]);
        final byte[] subkey = generateSubkey(masterKey, salt);

        final int tagSize = algorithm.getTagSize();
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
        final int saltSize = algorithm.getSaltSize();

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
     * @return the subkey
     * @see <a href="https://github.com/shadowsocks/shadowsocks-org/wiki/AEAD-Ciphers#key-derivation">Key Derivation</a>
     */
    private byte[] generateSubkey(final byte[] masterKey, final byte[] salt) {
        return SsSubKey.generateSubkey(masterKey, salt);
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