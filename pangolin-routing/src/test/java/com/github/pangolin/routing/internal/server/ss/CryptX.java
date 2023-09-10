package com.github.pangolin.routing.internal.server.ss;

import freework.codec.Base64;
import freework.crypto.cipher.Crypt;
import freework.util.Bytes;

import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;

public class CryptX {
    /**
     * Gets algorithm of the transformation.
     * <p>
     * Transformation format: "algorithm/mode/padding" or "algorithm".
     * eg:
     * - AES
     * - AES/CBC/NoPadding
     * - AES/CBC/PKCS5Padding
     * - RSA/ECB/PKCS1Padding
     *
     * @param transformation the name of transformation
     * @return the algorithm of the transformation
     */
    private static String getAlgorithm(final String transformation) {
        final int i = null != transformation ? transformation.indexOf('/') : -1;
        return -1 < i ? transformation.substring(0, i) : transformation;
    }

    private static class ShadowsocksAesCrypt {
        private final String transformation;
        private final byte[] encodedKey;


        private ShadowsocksAesCrypt(final String transformation, final String password, final int keySize) {
            this.transformation = transformation;
            this.encodedKey = init(password, keySize);
        }

        private int ivLength() {
            return 16;
        }

        public byte[] encrypt(byte[] bytes) {
            final byte[] ivBytes = new byte[ivLength()];
            new SecureRandom().nextBytes(ivBytes);

            final SecretKeySpec secretKey = Crypt.newSymmetricKey(transformation, encodedKey);
            final Crypt crypt = Crypt.getSymmetric(transformation, secretKey, new IvParameterSpec(ivBytes));
            final byte[] encoded = crypt.encrypt(bytes);
            final byte[] packet = Arrays.copyOf(ivBytes, ivBytes.length + encoded.length);
            System.arraycopy(encoded, 0, packet, ivBytes.length, encoded.length);

            return packet;
        }

        public byte[] decrypt(byte[] bytes) {
            final IvParameterSpec ivParameterSpec = new IvParameterSpec(bytes, 0, ivLength());

            final SecretKeySpec secretKey = Crypt.newSymmetricKey(transformation, encodedKey);
            final Crypt crypt = Crypt.getSymmetric(transformation, secretKey, ivParameterSpec);
            final byte[] plain = crypt.decrypt(Arrays.copyOfRange(bytes, ivLength(), bytes.length));
            return plain;
        }

    }

    public static void main(String[] args) throws NoSuchAlgorithmException, UnsupportedEncodingException {
        final int length = 256 / 8;
        final String password = "123456";
        final byte[] encoded = Base64.decode("lNDyySzefa7g9Ry607wpxjvDkS34");

        ShadowsocksAesCrypt cr = new ShadowsocksAesCrypt("AES/GCM/NoPadding", password, length);
        System.out.println(Bytes.toString(cr.decrypt(encoded)));

    }

    private final static int KEY_LENGTH = 32;

    private static byte[] init(String password, final int _length) {
        MessageDigest md = null;
        byte[] keys = new byte[KEY_LENGTH];
        byte[] temp = null;
        byte[] hash = null;
        byte[] passwordBytes = null;
        int i = 0;

        try {
            md = MessageDigest.getInstance("MD5");
            passwordBytes = password.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
//            logger.error("ShadowSocksKey: Unsupported string encoding", e);
            throw new RuntimeException(e);
        } catch (Exception e) {
//            logger.error("init error", e);
//            return null;
            throw new RuntimeException(e);
        }

        while (i < keys.length) {
            if (i == 0) {
                hash = md.digest(passwordBytes);
                temp = new byte[passwordBytes.length + hash.length];
            } else {
                System.arraycopy(hash, 0, temp, 0, hash.length);
                System.arraycopy(passwordBytes, 0, temp, hash.length, passwordBytes.length);
                hash = md.digest(temp);
            }
            System.arraycopy(hash, 0, keys, i, hash.length);
            i += hash.length;
        }

        if (_length != KEY_LENGTH) {
            byte[] keysl = new byte[_length];
            System.arraycopy(keys, 0, keysl, 0, _length);
            return keysl;
        }
        return keys;
    }
}