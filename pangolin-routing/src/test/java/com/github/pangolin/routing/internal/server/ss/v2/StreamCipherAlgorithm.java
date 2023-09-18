package com.github.pangolin.routing.internal.server.ss.v2;

/**
 */
public interface StreamCipherAlgorithm extends CipherAlgorithm {

    int getKeySize();

    int getIvSize();

    CipherHandle getCipher(final boolean encrypt, final byte[] secretKey, final byte[] iv);

}
