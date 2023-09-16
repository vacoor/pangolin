package com.github.pangolin.routing.internal.server.ss.codec.aead;

import com.github.pangolin.routing.internal.server.ss.codec.ShadowsocksCrypt;
import freework.util.Bytes;
import org.bouncycastle.crypto.CipherParameters;
import org.bouncycastle.crypto.digests.SHA1Digest;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.crypto.params.KeyParameter;

public abstract class AesGcmCryptFactory implements ShadowsocksAeadCryptFactory {
    private final int keySize;
    private final int saltSize;
    private final int nonceSize;
    private final int tagSize;

    protected AesGcmCryptFactory(final int keySize, final int saltSize, final int nonceSize, final int tagSize) {
        this.keySize = keySize;
        this.saltSize = saltSize;
        this.nonceSize = nonceSize;
        this.tagSize = tagSize;
    }

    @Override
    public int getKeySize() {
        return keySize;
    }

    @Override
    public int getSaltSize() {
        return saltSize;
    }

    @Override
    public int getNonceSize() {
        return nonceSize;
    }

    @Override
    public int getTagSize() {
        return tagSize;
    }

    public byte[] generateSubkey(final byte[] masterKey, final byte[] salt) {
        final HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA1Digest());
        hkdf.init(new HKDFParameters(masterKey, salt, Bytes.toBytes("ss-subkey")));

        final int keySize = getKeySize();
        final byte[] okm = new byte[keySize];
        final int written = hkdf.generateBytes(okm, 0, keySize);
        assert written == keySize;
        return okm;
    }

    @Override
    public ShadowsocksCrypt getInstance(final byte[] subkey, final byte[] nonce) {
        if (getKeySize() != subkey.length) {

        }
        if (getNonceSize() != nonce.length) {

        }
        return new AesGcmCrypt(subkey, nonce, getTagSize());
    }

    public static class Aes128Gcm extends AesGcmCryptFactory {

        protected Aes128Gcm() {
            super(16, 16, 12, 16);
        }

    }

    public static class Aes256Gcm extends AesGcmCryptFactory {

        protected Aes256Gcm() {
            super(32, 32, 12, 16);
        }

    }

    private static class AesGcmCrypt implements ShadowsocksCrypt {
        private final byte[] subKey;
        private final byte[] nonce;
        private final int tagSize;

        public AesGcmCrypt(final byte[] subKey, final byte[] nonce, final int tagSize) {
            this.subKey = subKey;
            this.nonce = nonce;
            this.tagSize = tagSize;
        }

        @Override
        public int encrypt(final byte[] inBytes, final int inOffset, final int inLength, final byte[] outBytes, final int outOffset) throws Exception {
            return crypt(true, subKey, nonce, inBytes, inOffset, inLength, outBytes, outOffset);
        }

        @Override
        public int decrypt(final byte[] inBytes, final int inOffset, final int inLength, final byte[] outBytes, final int outOffset) throws Exception {
            return crypt(false, subKey, nonce, inBytes, inOffset, inLength, outBytes, outOffset);
        }

        private int crypt(final boolean encrypt,
                          final byte[] subKey, final byte[] nonce,
                          final byte[] inBytes, final int inOffset, final int inLength,
                          final byte[] outBytes, final int outOffset) throws Exception {
            final GCMBlockCipher cipher = new GCMBlockCipher(new AESEngine());
            final CipherParameters cipherParameters = new AEADParameters(new KeyParameter(subKey), tagSize * Byte.SIZE, nonce);
            cipher.init(encrypt, cipherParameters);

            final int len = cipher.processBytes(inBytes, inOffset, inLength, outBytes, outOffset);
            return len + cipher.doFinal(outBytes, outOffset + len);
        }

    }
}