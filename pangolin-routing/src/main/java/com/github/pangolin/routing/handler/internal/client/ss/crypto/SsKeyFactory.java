package com.github.pangolin.routing.handler.internal.client.ss.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class SsKeyFactory {
    private static final int KEY_LENGTH = 32;

    public static byte[] generateKey(final int keySize, final String password) {
        return init(password, keySize);
    }

    private static byte[] init(String password, final int _length) {
        MessageDigest md = null;
        byte[] keys = new byte[KEY_LENGTH];
        byte[] temp = null;
        byte[] hash = null;
        byte[] passwordBytes = null;
        int i = 0;

        try {
            md = MessageDigest.getInstance("MD5");
            passwordBytes = password.getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
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