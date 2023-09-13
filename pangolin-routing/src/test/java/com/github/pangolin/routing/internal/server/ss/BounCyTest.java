package com.github.pangolin.routing.internal.server.ss;

import freework.codec.Base64;
import freework.util.Bytes;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.Test;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
//import javax.crypto.spec.ChaCha20ParameterSpec;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.Security;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Arrays;

public class BounCyTest {
    @Test
    public void aesGcm() throws Exception{
        final String AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding";
        final int GCM_IV_SIZE = 12;
        final int GCM_TAG_SIZE = 16;

        final SecureRandom random = new SecureRandom();
        final byte[] plain = Bytes.toBytes("Hello");
        final byte[] ivBytes = new byte[GCM_IV_SIZE];
        random.nextBytes(ivBytes);

        final SecretKey secretKey = ShadowsocksKeyFactory.generateKey("AES", 128 / 8, "123456");
        final AlgorithmParameterSpec algorithmParameterSpec = new GCMParameterSpec(GCM_TAG_SIZE * Byte.SIZE, ivBytes);
        final Cipher cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, algorithmParameterSpec);
        final byte[] encrypted = cipher.doFinal(plain);
        final byte[] bytes = Arrays.copyOf(ivBytes, ivBytes.length + encrypted.length);
        System.arraycopy(encrypted, 0, bytes, ivBytes.length, encrypted.length);
        System.out.println(Base64.encodeToString(bytes));

        // decrypt
        byte[] cipherBytes = Base64.decode("XSdDRve9YTe7BfYLlJZ/6ojNwhuicsnJTR9NAmW1aQFCbQCkehkCDtYtOUHFPYISh6JdFHg6dA==");
        cipherBytes = Arrays.copyOfRange(cipherBytes, 16, cipherBytes.length);
        final byte[] nonceBytes = new byte[GCM_IV_SIZE];
        int len = 2 + GCM_TAG_SIZE;

        final AlgorithmParameterSpec algorithmParameterSpec2 = new GCMParameterSpec(GCM_TAG_SIZE * Byte.SIZE, nonceBytes);
        final Cipher cipher2 = Cipher.getInstance(AES_GCM_TRANSFORMATION, new BouncyCastleProvider());
        cipher2.init(Cipher.DECRYPT_MODE, secretKey, algorithmParameterSpec2);
        cipherBytes = Arrays.copyOf(cipherBytes, len);
        final byte[] decrypted = cipher2.doFinal(cipherBytes);
        System.out.println(ByteBuffer.wrap(decrypted, 0, 2));
    }

    @Test
    public void camellia() throws Exception {
        Provider p = new BouncyCastleProvider();
        // Get a Cipher instance and set up the parameters
        // Assume SecretKey "key", 12-byte nonce "nonceBytes" and plaintext "pText"
        // are coming from outside this code snippet
        final SecureRandom random = new SecureRandom();
        final byte[] keyBytes = Arrays.copyOf(Bytes.toBytes("password"), 128 / 8);
        final SecretKeySpec key = new SecretKeySpec(keyBytes, "Camellia");

//        final byte[] nonceBytes = new byte[8];
//        random.nextBytes(nonceBytes);
        final byte[] nonceBytes = new byte[16];

        final String transformation = "Camellia/CFB/NoPadding";

         Cipher mambo = Cipher.getInstance(transformation, p);
//        Cipher mambo = Cipher.getInstance(transformation);
        // AlgorithmParameterSpec mamboSpec = new ChaCha20ParameterSpec(nonceBytes, 0);   // Use a starting counter value of "7"
        AlgorithmParameterSpec mamboSpec = new IvParameterSpec(nonceBytes);   // Use a starting counter value of "7"
        // Encrypt our input
        mambo.init(Cipher.ENCRYPT_MODE, key, mamboSpec);
        byte[] iv = mambo.getIV();
        byte[] encryptedResult = mambo.doFinal(Bytes.toBytes("Hello"));
        System.out.println(Base64.encodeToString(encryptedResult));


        Cipher mambo2 = Cipher.getInstance(transformation, p);
        // Encrypt our input
        mambo2.init(Cipher.DECRYPT_MODE, key, mamboSpec);
        byte[] decryptedResult = mambo2.doFinal(encryptedResult);
        System.out.println(Bytes.toString(decryptedResult));
    }

    @Test
    public void camelliaSs() throws Exception {
        final Provider p = new BouncyCastleProvider();
        final String transformation = "Camellia/CFB/NoPadding";
        final byte[] keyBytes = init("123456", 128 / 8);
        final SecretKeySpec key = new SecretKeySpec(keyBytes, "Camellia");

        final byte[] encrypted = Base64.decode("AikjfHd/SLSmXIsKuWZDaHIsr8A0", false);
//        Cipher mambo2 = Cipher.getInstance(transformation);
         Cipher mambo2 = Cipher.getInstance(transformation, p);
        AlgorithmParameterSpec mamboSpec = new IvParameterSpec(Arrays.copyOf(encrypted, 16));   // Use a starting counter value of "7"
        mambo2.init(Cipher.DECRYPT_MODE, key, mamboSpec);
        byte[] decryptedResult = mambo2.doFinal(encrypted, 16, encrypted.length - 16);
        System.out.println(Bytes.toString(decryptedResult));
    }

    @Test
    public void seedSs() throws Exception {
        final Provider p = new BouncyCastleProvider();
        final String transformation = "Seed/CFB/NoPadding";
        final byte[] keyBytes = init("123456", 128 / 8);
        final SecretKeySpec key = new SecretKeySpec(keyBytes, "Seed");

        final byte[] encrypted = Base64.decode("Spng7oUONYhCoYxSD5a71Xv6/NgH", false);
//        Cipher mambo2 = Cipher.getInstance(transformation);
        Cipher mambo2 = Cipher.getInstance(transformation, p);
        AlgorithmParameterSpec mamboSpec = new IvParameterSpec(Arrays.copyOf(encrypted, 16));   // Use a starting counter value of "7"
        mambo2.init(Cipher.DECRYPT_MODE, key, mamboSpec);
        byte[] decryptedResult = mambo2.doFinal(encrypted, 16, encrypted.length - 16);
        System.out.println(Bytes.toString(decryptedResult));
    }


    @Test
    public void blowfish() throws Exception {
//        Provider p = new BouncyCastleProvider();
        // Get a Cipher instance and set up the parameters
        // Assume SecretKey "key", 12-byte nonce "nonceBytes" and plaintext "pText"
        // are coming from outside this code snippet
        final SecureRandom random = new SecureRandom();
        final byte[] keyBytes = Arrays.copyOf(Bytes.toBytes("password"), 128 / 8);
        final SecretKeySpec key = new SecretKeySpec(keyBytes, "Blowfish");

//        final byte[] nonceBytes = new byte[8];
//        random.nextBytes(nonceBytes);
        final byte[] nonceBytes = new byte[8];

        final String transformation = "Blowfish/CFB/NoPadding";

        Cipher mambo = Cipher.getInstance(transformation);
        // Cipher mambo = Cipher.getInstance(transformation, p);
        // AlgorithmParameterSpec mamboSpec = new ChaCha20ParameterSpec(nonceBytes, 0);   // Use a starting counter value of "7"
        AlgorithmParameterSpec mamboSpec = new IvParameterSpec(nonceBytes);   // Use a starting counter value of "7"
        // Encrypt our input
        mambo.init(Cipher.ENCRYPT_MODE, key, mamboSpec);
        byte[] iv = mambo.getIV();
        byte[] encryptedResult = mambo.doFinal(Bytes.toBytes("Hello"));
        System.out.println(Base64.encodeToString(encryptedResult));


        Cipher mambo2 = Cipher.getInstance(transformation);
        // Cipher mambo2 = Cipher.getInstance(transformation, p);
        // Encrypt our input
        mambo2.init(Cipher.DECRYPT_MODE, key, mamboSpec);
        byte[] decryptedResult = mambo2.doFinal(encryptedResult);
        System.out.println(Bytes.toString(decryptedResult));
    }

    @Test
    public void blowfishSs() throws Exception {
        final Provider p = new BouncyCastleProvider();
        final String transformation = "Blowfish/CFB/NoPadding";
        final byte[] keyBytes = init("123456", 128 / 8);
        final SecretKeySpec key = new SecretKeySpec(keyBytes, "Blowfish");

        final byte[] encrypted = Base64.decode("IWSBhbyfUFatjxDC8g==", false);
        Cipher mambo2 = Cipher.getInstance(transformation);
        // Cipher mambo2 = Cipher.getInstance(transformation, p);
        AlgorithmParameterSpec mamboSpec = new IvParameterSpec(Arrays.copyOf(encrypted, 8));   // Use a starting counter value of "7"
        mambo2.init(Cipher.DECRYPT_MODE, key, mamboSpec);
        byte[] decryptedResult = mambo2.doFinal(encrypted, 8, encrypted.length - 8);
        System.out.println(Bytes.toString(decryptedResult));
    }

    @Test
    public void chacha20() throws Exception {
        Provider p = new BouncyCastleProvider();
        // Get a Cipher instance and set up the parameters
        // Assume SecretKey "key", 12-byte nonce "nonceBytes" and plaintext "pText"
        // are coming from outside this code snippet
        final SecureRandom random = new SecureRandom();
        final byte[] keyBytes = Arrays.copyOf(Bytes.toBytes("password"), 256 / 8);
        final SecretKeySpec key = new SecretKeySpec(keyBytes, "ChaCha20");

        final byte[] nonceBytes = new byte[12];
        random.nextBytes(nonceBytes);

//        final String transformation = "ChaCha20/None/NoPadding";
        final String transformation = "ChaCha20";

        Cipher mambo = Cipher.getInstance(transformation, p);
        // AlgorithmParameterSpec mamboSpec = new ChaCha20ParameterSpec(nonceBytes, 0);   // Use a starting counter value of "7"
        AlgorithmParameterSpec mamboSpec = new IvParameterSpec(nonceBytes);   // Use a starting counter value of "7"
        // Encrypt our input
        mambo.init(Cipher.ENCRYPT_MODE, key, mamboSpec);
        byte[] iv = mambo.getIV();
        byte[] encryptedResult = mambo.doFinal(Bytes.toBytes("Hello"));
        System.out.println(Base64.encodeToString(encryptedResult));


        Cipher mambo2 = Cipher.getInstance(transformation, p);
        // Encrypt our input
        mambo2.init(Cipher.DECRYPT_MODE, key, mamboSpec);
        byte[] decryptedResult = mambo2.doFinal(encryptedResult);
        System.out.println(Bytes.toString(decryptedResult));
    }


    @Test
    public void chacha2Ss() throws Exception {
        final Provider p = new BouncyCastleProvider();
        final String transformation = "ChaCha20";
        final byte[] keyBytes = init("123456", 256 / 8);
        final SecretKeySpec key = new SecretKeySpec(keyBytes, "ChaCha20");

        final byte[] encrypted = Base64.decode("oxFAVenGgds6ng4L7znVu0o=", false);
        Cipher mambo2 = Cipher.getInstance(transformation, p);
        AlgorithmParameterSpec mamboSpec = new IvParameterSpec(Arrays.copyOf(encrypted, 12));   // Use a starting counter value of "7"
        mambo2.init(Cipher.DECRYPT_MODE, key, mamboSpec);
        byte[] decryptedResult = mambo2.doFinal(encrypted, 12, encrypted.length - 12);
        System.out.println(Bytes.toString(decryptedResult));
    }

    @Test
    public void chacha20Poly1305() throws Exception {
        // Get a Cipher instance and set up the parameters
        // Assume SecretKey "key", 12-byte nonce "nonceBytes" and plaintext "pText"
        // are coming from outside this code snippet
        final SecureRandom random = new SecureRandom();
        final byte[] keyBytes = Arrays.copyOf(Bytes.toBytes("password"), 256 / 8);
        final SecretKeySpec key = new SecretKeySpec(keyBytes, "ChaCha20-Poly1305");

        final byte[] nonceBytes = new byte[12];
        random.nextBytes(nonceBytes);

        Cipher mambo = Cipher.getInstance("ChaCha20-Poly1305/None/NoPadding");
        // ChaCha20ParameterSpec mamboSpec = new ChaCha20ParameterSpec(nonceBytes, 7);   // Use a starting counter value of "7"
        AlgorithmParameterSpec mamboSpec = new IvParameterSpec(nonceBytes);
        // Encrypt our input
        mambo.init(Cipher.ENCRYPT_MODE, key, mamboSpec);
        byte[] encryptedResult = mambo.doFinal(Bytes.toBytes("Hello"));
        System.out.println(Base64.encodeToString(encryptedResult));


        Cipher mambo2 = Cipher.getInstance("ChaCha20-Poly1305/None/NoPadding");
        // ChaCha20ParameterSpec mamboSpec2 = new ChaCha20ParameterSpec(nonceBytes, 7);   // Use a starting counter value of "7"
        AlgorithmParameterSpec mamboSpec2 = new IvParameterSpec(nonceBytes);
        // Encrypt our input
        mambo2.init(Cipher.DECRYPT_MODE, key, mamboSpec2);
        byte[] decryptedResult = mambo2.doFinal(encryptedResult);
        System.out.println(Bytes.toString(decryptedResult));
    }

    private byte[] init(String password, int _length) {
        MessageDigest md;
        byte[] keys = new byte[32];
        byte[] temp = null;
        byte[] hash = null;
        byte[] passwordBytes;

        try {
            md = MessageDigest.getInstance("MD5");
            passwordBytes = password.getBytes();
        } catch (Exception e) {
            return null;
        }

        for(int i = 0;i < keys.length;i += hash.length){
            if (i == 0) {
                hash = md.digest(passwordBytes);
                temp = new byte[passwordBytes.length + hash.length];
            } else {
                System.arraycopy(hash, 0, temp, 0, hash.length);
                System.arraycopy(passwordBytes, 0, temp, hash.length, passwordBytes.length);
                hash = md.digest(temp);
            }
            System.arraycopy(hash, 0, keys, i, hash.length);
        }

        if (_length != 32) {
            byte[] keysl = new byte[_length];
            System.arraycopy(keys, 0, keysl, 0, _length);
            return keysl;
        }
        return keys;
    }
}
