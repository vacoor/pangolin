package com.github.pangolin.routing.internal.server.ss.crypto.impl.aead;

import com.github.pangolin.routing.internal.server.ss.crypto.ShadowsocksAeadCrypt;
import org.bouncycastle.crypto.CipherParameters;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.Provider;
import java.security.spec.AlgorithmParameterSpec;

public abstract class AesGcmCrypt implements ShadowsocksAeadCrypt {
    private final int keySize;
    private final int saltSize;
    private final int nonceSize;
    private final int tagSize;

    protected AesGcmCrypt(final int keySize, final int saltSize, final int nonceSize, final int tagSize) {
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

    @Override
    public int encrypt(final byte[] subKey, final byte[] nonce, final byte[] inBytes, final int inOffset, final int inLength, final byte[] outBytes, final int outOffset) throws Exception {
        return bcCrypt(true, subKey, nonce, inBytes, inOffset, inLength, outBytes, outOffset);
    }

    @Override
    public int decrypt(final byte[] subKey, final byte[] nonce, final byte[] inBytes, final int inOffset, final int inLength, final byte[] outBytes, final int outOffset) throws Exception {
        return bcCrypt(false, subKey, nonce, inBytes, inOffset, inLength, outBytes, outOffset);
    }

    private int bcCrypt(final boolean encrypt,
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
        Cipher cipher = jdkCipher(opmode, secretKey, nonce);
        return cipher.doFinal(bytes, offset, length, outBytes, outOffset);
    }


    static final String AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding";
    static final Provider BC = new BouncyCastleProvider();

    Cipher jdkCipher(final int opmode, final byte[] secretKey, final byte[] nonce) throws Exception {
        final AlgorithmParameterSpec algorithmParameterSpec2 = new GCMParameterSpec(tagSize * Byte.SIZE, nonce);
        final Cipher cipher2 = Cipher.getInstance(AES_GCM_TRANSFORMATION, BC);
        cipher2.init(opmode, new SecretKeySpec(secretKey, "AES"), algorithmParameterSpec2);
        return cipher2;
    }

    public static class Aes128Gcm extends AesGcmCrypt {

        public Aes128Gcm() {
            super(16, 16, 12, 16);
        }

    }

    public static class Aes256Gcm extends AesGcmCrypt {

        public Aes256Gcm() {
            super(32, 32, 12, 16);
        }

    }

}