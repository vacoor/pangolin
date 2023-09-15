package com.github.pangolin.routing.internal.server.ss.codec;

import freework.util.Bytes;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;
import org.bouncycastle.crypto.CipherParameters;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.StreamCipher;
import org.bouncycastle.crypto.digests.SHA1Digest;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.modes.AEADBlockCipher;
import org.bouncycastle.crypto.modes.AEADCipher;
import org.bouncycastle.crypto.modes.CFBBlockCipher;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

public class ShadowsocksCodec2 extends ByteToMessageCodec<ByteBuf> {
    private final byte[] key;
    private final int ivSize;
    private final byte[] saltBytes;
    private AEADCipher encrypt2;
    private AEADCipher decrypt2;
    private final byte[] encryptNonce;
    private final byte[] decryptNonce;
    private final SecretKey _k;

    static final int GCM_IV_SIZE = 12;
    static final int GCM_TAG_SIZE = 16;
    static final int LENGTH_SIZE = 2;
    static final int CHUNK_SIZE_MASK = 0x3FFF;

    public ShadowsocksCodec2(SecretKey secretKey, int ivSize, SecureRandom random) {
        this._k = secretKey;
        this.saltBytes = nextBytes(random, new byte[ivSize]);
        this.key = genSubkey(secretKey.getEncoded(), 128/8,saltBytes);
        this.ivSize = ivSize;
        this.encryptNonce = new byte[ivSize];
        this.decryptNonce = new byte[ivSize];
    }

    @Override
    protected void encode(final ChannelHandlerContext ctx, ByteBuf in, ByteBuf out) throws Exception {
        if (!in.isReadable()) {
            return;
        }
        if (null == encrypt2) {
            out.writeBytes(saltBytes);
        }

        final byte[] bytes = new byte[in.readableBytes()];
        in.readBytes(bytes);
        for (int offset = 0, outOffset = 0; offset < bytes.length; ) {
            final int chunkSize = Math.min(bytes.length - offset, CHUNK_SIZE_MASK);
            final byte[] chunkSizeBytes = new byte[]{(byte) ((chunkSize >>> 8) & 0xff), (byte) (chunkSize & 0xff)};
            final byte[] buffer = new byte[LENGTH_SIZE + GCM_TAG_SIZE + chunkSize + GCM_TAG_SIZE];

            encrypt(chunkSizeBytes, 0, chunkSizeBytes.length, buffer, 0);
            encrypt(bytes, offset, chunkSize, buffer, LENGTH_SIZE + GCM_TAG_SIZE);
            offset += chunkSize;
            out.writeBytes(buffer);
        }
        encrypt2 = null;
    }

    private int encrypt(final byte[] inBytes, int inOffset, int inLength, final byte[] outBytes, final int outOffset) throws InvalidCipherTextException {
        try {
            encrypt2 = instantiateCipher(true, c(key, encryptNonce));
            encrypt2.processBytes(inBytes, inOffset, inLength, outBytes, outOffset);
            return encrypt2.doFinal(outBytes, outOffset + inLength + GCM_TAG_SIZE);
        } finally {
            nextNone(encryptNonce);
        }
    }

    private CipherParameters c(byte[] key, final byte[] nonce) {
        return new AEADParameters(new KeyParameter(key), 16 * 8, Arrays.copyOf(nonce, nonce.length));
    }

    protected byte[] nextNone(byte[] nonce) {
        for (int i = 0; i < nonce.length; i++) {
            ++nonce[i];
//                nonce[i]++;
            if (nonce[i] != 0) {
                break;
            }
        }
        return nonce;
    }

    @Override
    protected void decode(ChannelHandlerContext channelHandlerContext, ByteBuf in, List<Object> out) throws Exception {
        if (null == decrypt2) {
            final byte[] saltBytes = new byte[ivSize];
            in.readBytes(saltBytes);
            byte[] bytes = genSubkey(_k.getEncoded(), 128 / 8, saltBytes);
            decrypt2 = instantiateCipher(false, c(bytes, decryptNonce));
        }
        final byte[] bytes = new byte[in.readableBytes()];
        in.readBytes(bytes);
        final byte[] plain = new byte[bytes.length + 16];
        int i = decrypt2.processBytes(bytes, 0, bytes.length, plain, 0);
        out.add(Unpooled.wrappedBuffer(plain, 0, i));
        nextNone(decryptNonce);
    }

    byte[] genSubkey(final byte[] encoded, final int keySize, byte[] salt) {
        HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA1Digest());
        hkdf.init(new HKDFParameters(encoded, salt, Bytes.toBytes("ss-subkey")));
        byte[] okm = new byte[keySize];
        hkdf.generateBytes(okm, 0, keySize);
        return okm;
    }

    private byte[] nextBytes(final SecureRandom random, byte[] bytes) {
        random.nextBytes(bytes);
        return bytes;
    }

    private static String getAlgorithm(final String transformation) {
        final int i = null != transformation ? transformation.indexOf('/') : -1;
        return -1 < i ? transformation.substring(0, i) : transformation;
    }

    /**
     * {@link javax.crypto.Cipher} is block ciphers.
     * If you were encrypting the data between the client and server with a block cipher,
     * you’d have to wait until the client typed enough characters to fill a block
     * @param enc
     * @param parameters
     * @return
     */
    private AEADCipher instantiateCipher(final boolean enc, final CipherParameters parameters) {
        // CFBBlockCipher cipher = new CFBBlockCipher(new AESEngine(), 128);
        GCMBlockCipher cipher = new GCMBlockCipher(new AESEngine());
        cipher.init(enc, parameters);
        return cipher;
    }
}
