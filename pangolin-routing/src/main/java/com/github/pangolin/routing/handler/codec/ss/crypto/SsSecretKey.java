package com.github.pangolin.routing.handler.codec.ss.crypto;

import javax.crypto.SecretKey;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * @see <a href="https://github.com/shadowsocks/shadowsocks-org/wiki/Stream-Ciphers#stream-encryptiondecryption">EVP_BytesToKey</a>
 */
public class SsSecretKey /*implements SecretKey*/ {
    private static final int DIGEST_LENGTH = 32;
    private static final String DIGEST_ALGORITHM = "MD5";

    private static final Charset UTF_8 = StandardCharsets.UTF_8;

    public static byte[] generateKey(final String password, final int keySize) {
        final MessageDigest digest = createDigest(DIGEST_ALGORITHM);

        final byte[] keyBytes = new byte[DIGEST_LENGTH];
        final byte[] passwordBytes = null != password ? password.getBytes(UTF_8) : new byte[0];

        byte[] hash = new byte[0];
        for (int i = 0; i < keyBytes.length; i += hash.length) {
            final byte[] input = new byte[hash.length + passwordBytes.length];
            System.arraycopy(hash, 0, input, 0, hash.length);
            System.arraycopy(passwordBytes, 0, input, hash.length, passwordBytes.length);

            hash = digest.digest(input);

            System.arraycopy(hash, 0, keyBytes, i, hash.length);
        }

        return keySize != keyBytes.length ? Arrays.copyOf(keyBytes, keySize) : keyBytes;
    }

    private static MessageDigest createDigest(final String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}