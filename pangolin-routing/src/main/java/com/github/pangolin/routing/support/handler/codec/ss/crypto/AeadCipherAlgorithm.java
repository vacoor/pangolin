package com.github.pangolin.routing.support.handler.codec.ss.crypto;

public interface AeadCipherAlgorithm extends CipherAlgorithm {

    int getKeySize();

    int getSaltSize();

    int getNonceSize();

    int getTagSize();

    CipherHandle getCipher(final boolean encrypt, final byte[] secretKey, final byte[] nonce);

}
