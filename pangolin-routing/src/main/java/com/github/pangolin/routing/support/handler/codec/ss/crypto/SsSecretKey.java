package com.github.pangolin.routing.support.handler.codec.ss.crypto;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Shadowsocks encryption/decription key generator.
 *
 * @see <a href="https://github.com/shadowsocks/shadowsocks-org/wiki/Stream-Ciphers#stream-encryptiondecryption">EVP_BytesToKey</a>
 */
public class SsSecretKey /*implements SecretKey*/ {
    private static final int DIGEST_LENGTH = 32;
    private static final String DIGEST_ALGORITHM = "MD5";

    private static final Charset UTF_8 = StandardCharsets.UTF_8;

    /**
     * The key can be input directly from user or generated from a password.
     *
     * @param password the password
     * @param keySize  the key size
     * @return the generated key bytes
     * @see <a href="https://github.com/shadowsocks/shadowsocks-org/wiki/Stream-Ciphers#stream-encryptiondecryption">EVP_BytesToKey</a>
     * @see <a href="https://wiki.openssl.org/index.php/Manual:EVP_BytesToKey(3)">EVP_BytesToKey</a>
     */
    public static byte[] generateKey(final String password, final int keySize) {
        final MessageDigest digest = createDigest(DIGEST_ALGORITHM);

        final byte[] keyBytes = new byte[DIGEST_LENGTH];
        final byte[] passwordBytes = null != password ? password.getBytes(UTF_8) : new byte[0];

        byte[] hash = new byte[0];
        for (int i = 0; i < keyBytes.length; i += hash.length) {
            final byte[] input = new byte[hash.length + passwordBytes.length];
            // copy previous hash & password to input.
            System.arraycopy(hash, 0, input, 0, hash.length);
            System.arraycopy(passwordBytes, 0, input, hash.length, passwordBytes.length);

            hash = digest.digest(input);

            // copy hash to key bytes.
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