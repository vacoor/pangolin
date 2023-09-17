package com.github.pangolin.routing.internal.server.ss.crypto;

public interface ShadowsocksAeadCrypt {

    int getKeySize();

    int getSaltSize();

    int getNonceSize();

    int getTagSize();

    int encrypt(final byte[] subkey, final byte[] nonce,
                final byte[] inBytes, int inOffset, int inLength,
                final byte[] outBytes, final int outOffset) throws Exception;

    int decrypt(final byte[] subkey, final byte[] nonce,
                final byte[] inBytes, int inOffset, int inLength,
                final byte[] outBytes, final int outOffset) throws Exception;

}
