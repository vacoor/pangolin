package com.github.pangolin.routing.internal.server.ss.codec.aead;

import com.github.pangolin.routing.internal.server.ss.codec.ShadowsocksCrypt;

public interface ShadowsocksAeadCryptFactory {

    int getKeySize();

    int getSaltSize();

    int getNonceSize();

    int getTagSize();

    byte[] generateSubkey(final byte[] masterKey, final byte[] salt);

    ShadowsocksCrypt getInstance(final byte[] subkey, final byte[] nonce);

}