package com.github.pangolin.routing.internal.server.ss.codec.stream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.CombinedChannelDuplexHandler;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.MessageToByteEncoder;
import org.bouncycastle.crypto.StreamCipher;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.CFBBlockCipher;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;

import java.security.SecureRandom;
import java.util.List;


/**
 * @see <a href="https://github.com/shadowsocks/shadowsocks-org/wiki/Stream-Ciphers">Stream Ciphers</a>
 */
public class ShadowsocksStreamCipherCodec extends CombinedChannelDuplexHandler<ByteToMessageDecoder, MessageToByteEncoder<ByteBuf>> {
    private static final int MAX_BUF_SIZE = 1024;

    public ShadowsocksStreamCipherCodec(final byte[] masterKey, final int ivSize, final SecureRandom random) {
        this.init(
                new ShadowsocksStreamDecoder(masterKey, ivSize),
                new ShadowsocksAeadEncoder(masterKey, ivSize, random)
        );
    }

    /**
     * {@link javax.crypto.Cipher} is block ciphers.
     * If you were encrypting the data between the client and server with a block cipher,
     * you’d have to wait until the client typed enough characters to fill a block
     */
    private StreamCipher createStreamCipher(final byte[] masterKey, final byte[] iv, final boolean encrypt) {
        CFBBlockCipher cipher = new CFBBlockCipher(new AESEngine(), 16 * 8);
        cipher.init(encrypt, new ParametersWithIV(new KeyParameter(masterKey), iv));
        return cipher;
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
    private class ShadowsocksAeadEncoder extends MessageToByteEncoder<ByteBuf> {
        private final byte[] masterKey;
        private final int ivSize;
        private final byte[] ivBytes;
        private final StreamCipher cipher;

        private boolean ivWrote;


        public ShadowsocksAeadEncoder(final byte[] masterKey, final int ivSize, final SecureRandom random) {
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
            return cipher.processBytes(inBytes, inOffset, inLength, outBytes, outOffset);
        }
    }

    /**
     * Shadowsocks stream decoder.
     */
    private class ShadowsocksStreamDecoder extends ByteToMessageDecoder {
        private final byte[] masterKey;
        private final int ivSize;

        private byte[] ivBytes;
        private StreamCipher cipher;

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
            return cipher.processBytes(inBytes, inOffset, inLength, outBytes, outOffset);
        }
    }

}