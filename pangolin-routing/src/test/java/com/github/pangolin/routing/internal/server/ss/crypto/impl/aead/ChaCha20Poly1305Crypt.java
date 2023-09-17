package com.github.pangolin.routing.internal.server.ss.crypto.impl.aead;

import com.github.pangolin.routing.internal.server.ss.crypto.ShadowsocksAeadCrypt;
import org.bouncycastle.crypto.CipherParameters;
import org.bouncycastle.crypto.modes.ChaCha20Poly1305;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.Key;
import java.security.spec.AlgorithmParameterSpec;

public class ChaCha20Poly1305Crypt implements ShadowsocksAeadCrypt {

    @Override
    public int getKeySize() {
        return 32;
    }

    @Override
    public int getSaltSize() {
        return 32;
    }

    @Override
    public int getNonceSize() {
        return 12;
    }

    @Override
    public int getTagSize() {
        return 16;
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
        final ChaCha20Poly1305 cipher = new ChaCha20Poly1305();
        final CipherParameters cipherParameters = new AEADParameters(new KeyParameter(subKey), getTagSize() * Byte.SIZE, nonce);
        cipher.init(encrypt, cipherParameters);

        final int len = cipher.processBytes(inBytes, inOffset, inLength, outBytes, outOffset);
        return len + cipher.doFinal(outBytes, outOffset + len);
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