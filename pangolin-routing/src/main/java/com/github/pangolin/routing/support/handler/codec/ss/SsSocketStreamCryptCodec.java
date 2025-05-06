package com.github.pangolin.routing.support.handler.codec.ss;

import com.github.pangolin.routing.support.handler.codec.ss.crypto.CipherHandle;
import com.github.pangolin.routing.support.handler.codec.ss.crypto.SsSecretKey;
import com.github.pangolin.routing.support.handler.codec.ss.crypto.StreamCipherAlgorithm;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.CombinedChannelDuplexHandler;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.MessageToByteEncoder;

import java.security.SecureRandom;
import java.util.List;


/**
 * @see <a href="https://shadowsocks.org/doc/stream.html">Stream ciphers</a>
 * @see <a href="https://github.com/shadowsocks/shadowsocks-org/wiki/Stream-Ciphers">Stream Ciphers</a>
 */
public class SsSocketStreamCryptCodec extends CombinedChannelDuplexHandler<ByteToMessageDecoder, MessageToByteEncoder<ByteBuf>> {
    private static final int MAX_BUF_SIZE = 1024;

    private final StreamCipherAlgorithm algorithm;


    public SsSocketStreamCryptCodec(final StreamCipherAlgorithm algorithm, final String password, final SecureRandom random) {
        this(generateMasterKey(algorithm, password), algorithm, random);
    }

    private static byte[] generateMasterKey(final StreamCipherAlgorithm algorithm, final String password) {
        return SsSecretKey.generateKey(password, algorithm.getKeySize());
    }

    public SsSocketStreamCryptCodec(final byte[] masterKey, final StreamCipherAlgorithm algorithm, final SecureRandom random) {
        this.algorithm = algorithm;
        this.init(
                new ShadowsocksStreamDecoder(masterKey, algorithm.getIvSize()),
                new ShadowsocksStreamEncoder(masterKey, algorithm.getIvSize(), random)
        );
    }

    /**
     * {@link javax.crypto.Cipher} is block ciphers.
     * If you were encrypting the data between the client and server with a block cipher,
     * you’d have to wait until the client typed enough characters to fill a block
     */
    private CipherHandle createStreamCipher(final byte[] masterKey, final byte[] iv, final boolean encrypt) {
        return algorithm.getCipher(encrypt, masterKey, iv);
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

    /**
     * Shadowsocks stream encoder.
     */
    @SuppressWarnings("unused")
    private class ShadowsocksStreamEncoder extends MessageToByteEncoder<ByteBuf> {
        private final byte[] masterKey;
        private final int ivSize;
        private final byte[] ivBytes;
        private final CipherHandle cipher;

        private boolean ivWrote;


        public ShadowsocksStreamEncoder(final byte[] masterKey, final int ivSize, final SecureRandom random) {
            this.masterKey = masterKey;
            this.ivSize = ivSize;
            this.ivBytes = nextBytes(random, new byte[ivSize]);
            this.cipher = createStreamCipher(masterKey, ivBytes, true);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected void encode(final ChannelHandlerContext ctx, ByteBuf in, ByteBuf out) throws Exception {
            /*-
             * [IV][encrypted payload][encrypted payload]....
             */
            while (in.isReadable()) {
                if (!ivWrote) {
                    ivWrote = true;
                    out.writeBytes(ivBytes);
                }
                if (in.hasArray()) {
                    final byte[] buf = in.array();
                    final int offset = in.arrayOffset();
                    final int len = in.readableBytes();
                    final int written = encrypt(buf, offset, len, buf, offset);
                    assert written == len;
                    out.writeBytes(buf, offset, written);
                    in.skipBytes(written);
                } else {
                    final byte[] payload = readAsBytes(in, Math.min(in.readableBytes(), MAX_BUF_SIZE));
                    final int written = encrypt(payload, 0, payload.length, payload, 0);
                    assert written == payload.length;
                    out.writeBytes(payload, 0, written);
                }
            }
        }

        private int encrypt(final byte[] inBytes, int inOffset, int inLength, final byte[] outBytes, final int outOffset) throws Exception {
            return cipher.update(inBytes, inOffset, inLength, outBytes, outOffset);
        }
    }

    /**
     * Shadowsocks stream decoder.
     */
    @SuppressWarnings("unused")
    private class ShadowsocksStreamDecoder extends ByteToMessageDecoder {
        private final byte[] masterKey;
        private final int ivSize;

        private byte[] ivBytes;
        private CipherHandle cipher;

        public ShadowsocksStreamDecoder(final byte[] masterKey, final int ivSize) {
            this.masterKey = masterKey;
            this.ivSize = ivSize;
        }

        @Override
        protected void decode(final ChannelHandlerContext ctx, final ByteBuf in, List<Object> out) throws Exception {
            /*-
             * [IV][encrypted payload][encrypted payload]....
             */
            if (null == ivBytes) {
                if (!in.isReadable(ivSize)) {
                    return;
                }
                ivBytes = readAsBytes(in, ivSize);
                cipher = createStreamCipher(masterKey, ivBytes, false);
            }
            while (in.isReadable()) {
                if (in.hasArray()) {
                    final byte[] buf = in.array();
                    final int offset = in.arrayOffset();
                    final int len = in.readableBytes();
                    final int written = decrypt(buf, offset, len, buf, offset);
                    assert written == len;
                    out.add(Unpooled.wrappedBuffer(buf, offset, written));
                    in.skipBytes(len);
                } else {
                    final byte[] payload = readAsBytes(in, Math.min(in.readableBytes(), MAX_BUF_SIZE));
                    final int written = decrypt(payload, 0, payload.length, payload, 0);
                    assert written == payload.length;
                    out.add(Unpooled.wrappedBuffer(payload, 0, written));
                }
            }
        }

        private int decrypt(final byte[] inBytes, int inOffset, int inLength, final byte[] outBytes, final int outOffset) throws Exception {
            return cipher.update(inBytes, inOffset, inLength, outBytes, outOffset);
        }
    }

}