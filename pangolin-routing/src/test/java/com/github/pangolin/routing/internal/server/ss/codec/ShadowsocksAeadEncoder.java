package com.github.pangolin.routing.internal.server.ss.codec;

import freework.util.Bytes;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import org.bouncycastle.crypto.CipherParameters;
import org.bouncycastle.crypto.digests.SHA1Digest;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.crypto.params.KeyParameter;

import java.security.SecureRandom;

public class ShadowsocksAeadEncoder extends MessageToByteEncoder<ByteBuf> {
    private static final int LENGTH_SIZE = 2;
    private static final int CHUNK_SIZE_MASK = 0x3FFF;

    static final int KEY_SIZE = 16;
    static final int SALT_SIZE = 16;
    static final int GCM_NONCE_SIZE = 12;
    static final int GCM_TAG_SIZE = 16;


    private final byte[] masterKey;
    private final byte[] salt;
    private final byte[] subKey;
    private final byte[] nonce;
    private boolean saltWrote;

    public ShadowsocksAeadEncoder(final byte[] masterKey) {
        this(masterKey, new SecureRandom());
    }

    public ShadowsocksAeadEncoder(final byte[] masterKey, final SecureRandom random) {
        this(masterKey, nextBytes(random, new byte[SALT_SIZE]));
    }

    private static byte[] nextBytes(final SecureRandom random, byte[] bytes) {
        random.nextBytes(bytes);
        return bytes;
    }

    public ShadowsocksAeadEncoder(final byte[] masterKey, final byte[] salt) {
        this.masterKey = masterKey;
        this.salt = salt;
        this.subKey = generateSubkey(masterKey, KEY_SIZE, salt);
        this.nonce = new byte[GCM_NONCE_SIZE];
    }

    @Override
    protected void encode(final ChannelHandlerContext ctx, ByteBuf in, ByteBuf out) throws Exception {
        /*-
         Reference: https://github.com/shadowsocks/shadowsocks-org/wiki/AEAD-Ciphers
         +------+--------------------------+---------------------------+------------------------+-------------------------+-----+
         | salt |  encrypted header chunk  |  encrypted payload chunk  | encrypted header chunk | encrypted payload chunk | ... |
         +------+--------------------------+---------------------------+------------------------+-------------------------+-----+
         |  16B | 2B payload len + 16B tag | variable length + 16B tag | ...                    | ...                     | ... |
         +------+--------------------------+---------------------------+------------------------+-------------------------+-----+
         */
        while (in.isReadable()) {
            final int chunkSize = in.readableBytes() & CHUNK_SIZE_MASK;
            final byte[] chunkSizeBytes = new byte[]{(byte) ((chunkSize >>> 8) & 0xff), (byte) (chunkSize & 0xff)};
            final byte[] buffer = new byte[LENGTH_SIZE + GCM_TAG_SIZE + chunkSize + GCM_TAG_SIZE];

            int outOffset = encrypt(chunkSizeBytes, 0, chunkSizeBytes.length, buffer, 0);

            in.readBytes(buffer, outOffset, chunkSize);
            outOffset += encrypt(buffer, outOffset, chunkSize, buffer, outOffset);

            if (!saltWrote) {
                saltWrote = true;
                out.writeBytes(salt);
            }
            out.writeBytes(buffer, 0, outOffset);
        }
    }


    private int encrypt(final byte[] inBytes, int inOffset, int inLength, final byte[] outBytes, final int outOffset) throws Exception {
        try {
            return crypt(true, subKey, nonce, inBytes, inOffset, inLength, outBytes, outOffset);
        } finally {
            nextNone(nonce);
        }
    }

    public static int crypt(final boolean encrypt,
                            final byte[] subKey, final byte[] nonce,
                            final byte[] inBytes, final int inOffset, final int inLength,
                            final byte[] outBytes, final int outOffset) throws Exception {
        final GCMBlockCipher cipher = new GCMBlockCipher(new AESEngine());
        final CipherParameters cipherParameters = new AEADParameters(new KeyParameter(subKey), GCM_TAG_SIZE * Byte.SIZE, nonce);
        cipher.init(encrypt, cipherParameters);

        final int len = cipher.processBytes(inBytes, inOffset, inLength, outBytes, outOffset);
        return len + cipher.doFinal(outBytes, outOffset + len);
    }


    static byte[] generateSubkey(final byte[] masterKey, final int keySize, final byte[] salt) {
        final HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA1Digest());
        hkdf.init(new HKDFParameters(masterKey, salt, Bytes.toBytes("ss-subkey")));

        final byte[] okm = new byte[keySize];
        final int written = hkdf.generateBytes(okm, 0, keySize);
        assert written == keySize;
        return okm;
    }


    static byte[] nextNone(byte[] nonce) {
        for (int i = 0; i < nonce.length; i++) {
            ++nonce[i];
            if (nonce[i] != 0) {
                break;
            }
        }
        return nonce;
    }
}
