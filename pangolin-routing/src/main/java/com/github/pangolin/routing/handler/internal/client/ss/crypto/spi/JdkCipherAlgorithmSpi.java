package com.github.pangolin.routing.handler.internal.client.ss.crypto.spi;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.Key;
import java.security.Provider;
import java.security.spec.AlgorithmParameterSpec;

public class JdkCipherAlgorithmSpi {
    protected int jdkCrypt(final int opmode, final byte[] secretKey, final byte[] nonce,
                           final byte[] bytes, int offset, int length,
                           final byte[] outBytes, final int outOffset) throws Exception {
        Cipher cipher = jdkCipher(opmode, secretKey, nonce);
        return cipher.doFinal(bytes, offset, length, outBytes, outOffset);
    }


    static final String AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding";
    static final Provider BC = new BouncyCastleProvider();

    Cipher jdkCipher(final int opmode, final byte[] secretKey, final byte[] nonce) throws Exception {
        int tagSize = 16;
        final AlgorithmParameterSpec algorithmParameterSpec2 = new GCMParameterSpec(tagSize * Byte.SIZE, nonce);
        final Cipher cipher2 = Cipher.getInstance(AES_GCM_TRANSFORMATION, BC);
        cipher2.init(opmode, new SecretKeySpec(secretKey, "AES"), algorithmParameterSpec2);
        return cipher2;
    }

    private int jdkCrypt(final boolean encrypt,
                         final byte[] subKey, final byte[] nonce,
                         final byte[] inBytes, final int inOffset, final int inLength,
                         final byte[] outBytes, final int outOffset) throws Exception {
        final Key theKey = new SecretKeySpec(subKey, "ChaCha20-Poly1305");
        final AlgorithmParameterSpec spec = new IvParameterSpec(nonce);
        final Cipher cipher = Cipher.getInstance("ChaCha20-Poly1305");
        cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, theKey, spec);
        return cipher.doFinal(inBytes, inOffset, inLength, outBytes, outOffset);
    }
}