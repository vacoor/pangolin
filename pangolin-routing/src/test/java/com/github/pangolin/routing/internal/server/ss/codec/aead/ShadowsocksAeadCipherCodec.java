package com.github.pangolin.routing.internal.server.ss.codec.aead;

import com.github.pangolin.routing.internal.server.ss.codec.ShadowsocksCrypt;
import freework.util.Bytes;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.CombinedChannelDuplexHandler;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.codec.ReplayingDecoder;
import org.bouncycastle.crypto.digests.SHA1Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;

import java.security.SecureRandom;
import java.util.List;

/**
 * @see <a href="https://github.com/shadowsocks/shadowsocks-org/wiki/AEAD-Ciphers">AEAD Ciphers</a>
 */
public class ShadowsocksAeadCipherCodec extends CombinedChannelDuplexHandler<ByteToMessageDecoder, MessageToByteEncoder<ByteBuf>> {
    private static final int LENGTH_SIZE = 2;
    private static final int CHUNK_SIZE_MASK = 0x3FFF;

    private final ShadowsocksAeadCryptFactory factory;

    public ShadowsocksAeadCipherCodec(final byte[] masterKey, final ShadowsocksAeadCryptFactory factory, final SecureRandom random) {
        if (masterKey.length != factory.getKeySize()) {
            throw new IllegalArgumentException("master key size != factory.getKeySize()");
        }
        this.factory = factory;
        this.init(
                new ShadowsocksAeadDecoder(masterKey, factory.getSaltSize(), factory.getNonceSize(), factory.getTagSize()),
                new ShadowsocksAeadEncoder(masterKey, factory.getSaltSize(), factory.getNonceSize(), factory.getTagSize(), random)
        );
    }

    /**
     * @param masterKey the master key
     * @param salt      the non-secret salt
     * @return the subkey
     * @see <a href="https://github.com/shadowsocks/shadowsocks-org/wiki/AEAD-Ciphers#key-derivation">Key Derivation</a>
     */
    private byte[] generateSubkey(final byte[] masterKey, final byte[] salt) {
        final HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA1Digest());
        hkdf.init(new HKDFParameters(masterKey, salt, Bytes.toBytes("ss-subkey")));

        final byte[] okm = new byte[masterKey.length];
        final int written = hkdf.generateBytes(okm, 0, masterKey.length);
        assert written == masterKey.length;
        return okm;
    }

    /**
     * Encrypt/Decrypt.
     */
    private int crypt(final boolean encrypt,
                      final byte[] subKey, final byte[] nonce,
                      final byte[] inBytes, int inOffset, int inLength,
                      final byte[] outBytes, final int outOffset) throws Exception {
        final int len = doCrypt(factory, encrypt, subKey, nonce, inBytes, inOffset, inLength, outBytes, outOffset);
        afterCrypt(nonce);
        return len;
    }

    private int doCrypt(final ShadowsocksAeadCryptFactory factory, final boolean encrypt,
                        final byte[] subKey, final byte[] nonce,
                        final byte[] inBytes, int inOffset, int inLength,
                        final byte[] outBytes, final int outOffset) throws Exception {
        final ShadowsocksCrypt crypt = factory.getInstance(subKey, nonce);
        return encrypt ? crypt.encrypt(inBytes, inOffset, inLength, outBytes, outOffset) : crypt.decrypt(inBytes, inOffset, inLength, outBytes, outOffset);
    }

    /*
    private int doBcCrypt(final boolean encrypt,
                          final byte[] subKey, final byte[] nonce,
                          final byte[] inBytes, final int inOffset, final int inLength,
                          final byte[] outBytes, final int outOffset) throws Exception {
        final GCMBlockCipher cipher = new GCMBlockCipher(new AESEngine());
        final CipherParameters cipherParameters = new AEADParameters(new KeyParameter(subKey), tagSize * Byte.SIZE, nonce);
        cipher.init(encrypt, cipherParameters);

        final int len = cipher.processBytes(inBytes, inOffset, inLength, outBytes, outOffset);
        return len + cipher.doFinal(outBytes, outOffset + len);
    }
    protected int jdkCrypt(final int opmode, final byte[] secretKey, final byte[] nonce,
                           final byte[] bytes, int offset, int length,
                           final byte[] outBytes, final int outOffset) throws Exception {
        Cipher cipher = cipher(opmode, secretKey, nonce);
        return cipher.doFinal(bytes, offset, length, outBytes, outOffset);
    }


    static final String AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding";
    static final Provider BC = new BouncyCastleProvider();

    Cipher cipher(final int opmode, final byte[] secretKey, final byte[] nonce) throws Exception {
        final AlgorithmParameterSpec algorithmParameterSpec2 = new GCMParameterSpec(tagSize * Byte.SIZE, nonce);
        final Cipher cipher2 = Cipher.getInstance(AES_GCM_TRANSFORMATION, BC);
        //        final Cipher cipher2 = Cipher.getInstance(AES_GCM_TRANSFORMATION);
        cipher2.init(opmode, new SecretKeySpec(secretKey, "AES"), algorithmParameterSpec2);
        return cipher2;
    }
    */


    /**
     * After each encrypt/decrypt operation, the nonce is
     * incremented by one as if it wre an unsigned little-endian integer.
     * <p>
     * Note: that each TCP chunk involves two AEAD encrypt/decrypt
     * operation, therefore each chunk increases the nonce twice.
     * </p>
     *
     * @see <a href="https://github.com/shadowsocks/shadowsocks-org/wiki/AEAD-Ciphers#tcp">TCP</a>
     */
    private void afterCrypt(final byte[] nonce) {
        for (int i = 0; i < nonce.length; i++) {
            nonce[i]++;
            if (nonce[i] != 0) {
                break;
            }
        }
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
     * Shadowsocks AEAD stream encoder.
     */
    private class ShadowsocksAeadEncoder extends MessageToByteEncoder<ByteBuf> {
        private final byte[] masterKey;
        private final int saltSize;
        private final int nonceSize;
        private final int tagSize;

        private final byte[] salt;
        private final byte[] nonce;
        private final byte[] subkey;

        private boolean saltWrote;


        public ShadowsocksAeadEncoder(final byte[] masterKey, final int saltSize, final int nonceSize, final int tagSize, final SecureRandom random) {
            this.masterKey = masterKey;
            this.saltSize = saltSize;
            this.nonceSize = nonceSize;
            this.tagSize = tagSize;
            this.salt = nextBytes(random, new byte[saltSize]);
            this.nonce = new byte[nonceSize];
            this.subkey = generateSubkey(masterKey, salt);
        }

        /**
         * {@inheritDoc}
         *
         * @see <a href="https://github.com/shadowsocks/shadowsocks-org/wiki/AEAD-Ciphers#tcp">TCP</a>
         */
        @Override
        protected void encode(final ChannelHandlerContext ctx, ByteBuf in, ByteBuf out) throws Exception {
            /*-
             +------+------------------------+-------------------------+------------------------+-------------------------+-----+
             | salt | encrypted header chunk | encrypted payload chunk | encrypted header chunk | encrypted payload chunk | ... |
             +------+------------------------+-------------------------+------------------------+-------------------------+-----+
             | salt |  2B payload len + tag  |  variable length + tag  | ...                    | ...                     | ... |
             +------+------------------------+-------------------------+------------------------+-------------------------+-----+
             */
            while (in.isReadable()) {
                /*-
                 * Payload length is a 2-byte big-endian unsigned integer capped at 0x3FFF.
                 */
                final int chunkSize = in.readableBytes() & CHUNK_SIZE_MASK;
                final byte[] chunkSizeBytes = new byte[]{(byte) ((chunkSize >>> 8) & 0xff), (byte) (chunkSize & 0xff)};
                final byte[] buffer = new byte[LENGTH_SIZE + tagSize + chunkSize + tagSize];

                int len = encrypt(chunkSizeBytes, 0, chunkSizeBytes.length, buffer, 0);

                in.readBytes(buffer, len, chunkSize);
                len += encrypt(buffer, len, chunkSize, buffer, len);

                if (!saltWrote) {
                    saltWrote = true;
                    out.writeBytes(salt);
                }
                out.writeBytes(buffer, 0, len);
            }
        }

        private int encrypt(final byte[] inBytes, int inOffset, int inLength, final byte[] outBytes, final int outOffset) throws Exception {
            return crypt(true, subkey, nonce, inBytes, inOffset, inLength, outBytes, outOffset);
        }
    }

    /**
     * Shadowsocks AEAD stream decoder.
     */
    private class ShadowsocksAeadDecoder extends ReplayingDecoder<DecoderState> {
        private final byte[] masterKey;
        private final int saltSize;
        private final int nonceSize;
        private final int tagSize;
        private final byte[] nonce;

        private byte[] salt;
        private byte[] subkey;
        private int chunkSize;

        public ShadowsocksAeadDecoder(final byte[] masterKey, final int saltSize, final int nonceSize, final int tagSize) {
            super(DecoderState.READ_SALT);
            this.masterKey = masterKey;
            this.saltSize = saltSize;
            this.nonceSize = nonceSize;
            this.tagSize = tagSize;
            this.nonce = new byte[nonceSize];
        }

        @Override
        protected void decode(final ChannelHandlerContext ctx, final ByteBuf in, List<Object> out) throws Exception {
            /*-
             +------+------------------------+-------------------------+------------------------+-------------------------+-----+
             | salt | encrypted header chunk | encrypted payload chunk | encrypted header chunk | encrypted payload chunk | ... |
             +------+------------------------+-------------------------+------------------------+-------------------------+-----+
             | salt |  2B payload len + tag  |  variable length + tag  | ...                    | ...                     | ... |
             +------+------------------------+-------------------------+------------------------+-------------------------+-----+
             */
            while (in.isReadable()) {
                switch (state()) {
                    case READ_SALT:
                        salt = readAsBytes(in, saltSize);
                        subkey = generateSubkey(masterKey, salt);
                        checkpoint(DecoderState.READ_HEADER_CHUNK);
                    case READ_HEADER_CHUNK:
                        final byte[] chunkSizeBytes = readAsBytes(in, LENGTH_SIZE + tagSize);
                        final int written = decrypt(chunkSizeBytes, 0, chunkSizeBytes.length, chunkSizeBytes, 0);
                        assert written == LENGTH_SIZE;

                        chunkSize = (chunkSizeBytes[0] & 0xff) << 8 | chunkSizeBytes[1] & 0xff;
                        checkpoint(DecoderState.READ_PAYLOAD_CHUNK);
                    case READ_PAYLOAD_CHUNK:
                        final byte[] payload = readAsBytes(in, chunkSize + tagSize);
                        final int len = decrypt(payload, 0, payload.length, payload, 0);
                        assert len == payload.length;

                        out.add(Unpooled.wrappedBuffer(payload, 0, len));
                        checkpoint(DecoderState.READ_HEADER_CHUNK);
                    default:
                        // NOOP
                }
            }
        }

        private int decrypt(final byte[] inBytes, int inOffset, int inLength, final byte[] outBytes, final int outOffset) throws Exception {
            return crypt(false, subkey, nonce, inBytes, inOffset, inLength, outBytes, outOffset);
        }
    }

    enum DecoderState {
        READ_SALT, READ_HEADER_CHUNK, READ_PAYLOAD_CHUNK
    }
}