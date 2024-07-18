package com.github.pangolin.routing.handler.codec.ss;

import com.github.pangolin.routing.handler.codec.ss.crypto.SsSecretKey;
import com.github.pangolin.routing.handler.codec.ss.crypto.StreamCipherAlgorithm;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageCodec;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

/**
 * @see <a href="https://github.com/shadowsocks/shadowsocks-org/wiki/Stream-Ciphers#udp">UDP</a>
 */
public class SsDatagramStreamCryptCodec extends MessageToMessageCodec<DatagramPacket, DatagramPacket> {
    private final byte[] masterKey;
    private final StreamCipherAlgorithm algorithm;
    private final SecureRandom random;

    private final int ivSize;

    public SsDatagramStreamCryptCodec(final StreamCipherAlgorithm algorithm, final String password, final SecureRandom random) {
        this(generateMasterKey(algorithm, password), algorithm, random);
    }

    private static byte[] generateMasterKey(final StreamCipherAlgorithm algorithm, final String password) {
        return SsSecretKey.generateKey(password, algorithm.getKeySize());
    }

    public SsDatagramStreamCryptCodec(final byte[] masterKey, final StreamCipherAlgorithm algorithm, final SecureRandom random) {
        if (masterKey.length != algorithm.getKeySize()) {
            throw new IllegalArgumentException("master key size != crypt.getKeySize()");
        }
        this.masterKey = masterKey;
        this.algorithm = algorithm;
        this.random = random;

        this.ivSize = algorithm.getIvSize();
    }

    @Override
    protected void encode(final ChannelHandlerContext ctx, final DatagramPacket in, final List<Object> out) throws Exception {
        /*-
         +------+-------------------+
         |  iv  | encrypted payload |
         +------+-------------------+
         */
        final ByteBuf payload = in.content();
        final int len = payload.readableBytes();
        final byte[] iv = nextBytes(random, new byte[ivSize]);

        final byte[] chunk = Arrays.copyOf(iv, iv.length + len);
        payload.readBytes(chunk, iv.length, len);

        final int written = encrypt(masterKey, iv, chunk, iv.length, len, chunk, iv.length);
        assert written == len;
        out.add(in.replace(Unpooled.wrappedBuffer(chunk)));
    }

    @Override
    protected void decode(final ChannelHandlerContext ctx, final DatagramPacket in, List<Object> out) throws Exception {
        /*-
         +------+-------------------+
         |  iv  | encrypted payload |
         +------+-------------------+
         */
        final ByteBuf chunk = in.content();
        if (chunk.hasArray()) {
            final byte[] buf = chunk.array();
            final int offset = chunk.arrayOffset();
            final int len = chunk.readableBytes();
            final byte[] iv = Arrays.copyOfRange(buf, offset, offset + ivSize);
            final int written = decrypt(masterKey, iv, buf, offset + ivSize, len - ivSize, buf, offset + ivSize);
            out.add(in.replace(Unpooled.wrappedBuffer(buf, offset + ivSize, written)));
        } else {
            final byte[] iv = readAsBytes(chunk, ivSize);
            final byte[] payload = readAsBytes(chunk, chunk.readableBytes());
            final int written = decrypt(masterKey, iv, payload, 0, payload.length, payload, 0);
            out.add(in.replace(Unpooled.wrappedBuffer(payload, 0, written)));
        }
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