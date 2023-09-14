package com.github.pangolin.routing.internal.server.ss;

import freework.codec.Base64;
import freework.util.Bytes;

import javax.crypto.Cipher;
//import javax.crypto.spec.ChaCha20ParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.Key;
import java.security.SecureRandom;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Arrays;

public class ChaCha20Poly1305 {

    /*
    static byte[] chacha20(byte[] message, byte[] key, byte[] nonce, int counter) throws Exception{
        Key theKey = new SecretKeySpec(key, "ChaCha20");

        ChaCha20ParameterSpec spec = new ChaCha20ParameterSpec(nonce, counter);

        Cipher cipher = Cipher.getInstance("ChaCha20");

        cipher.init(Cipher.ENCRYPT_MODE, theKey, spec);

        return cipher.doFinal(message);

    }
    */

    static byte[] encrypt(byte[] message, byte[] key, byte[] nonce) throws Exception {
        Key theKey = new SecretKeySpec(key, "ChaCha20-Poly1305");

        AlgorithmParameterSpec spec = new IvParameterSpec(nonce);

        Cipher cipher = Cipher.getInstance("ChaCha20-Poly1305");

        cipher.init(Cipher.ENCRYPT_MODE, theKey, spec);

        return cipher.doFinal(message);
    }

    public static void main(String[] args) throws Exception {
        final byte[] key = Arrays.copyOf(Bytes.toBytes("password"), 256 / 8);
        final byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        System.out.println(Base64.encodeToString(encrypt(Bytes.toBytes("Hello0"), key, nonce)));
//        System.out.println(Bytes.toString(decrypt(key, encrypt(key, Bytes.toBytes("Hello0")))));
//        System.out.println(Base64.encodeToString(chacha20(Bytes.toBytes("Hello0"), key, nonce, 1)));
    }
}