package com.github.pangolin.routing.internal.server.ss.crypto;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;

public class ShadowsocksKeyFactory {
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