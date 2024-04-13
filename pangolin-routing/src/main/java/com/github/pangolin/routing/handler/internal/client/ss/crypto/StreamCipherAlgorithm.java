package com.github.pangolin.routing.handler.internal.client.ss.crypto;

/**
 */
@Deprecated
public interface StreamCipherAlgorithm extends CipherAlgorithm {

    int getKeySize();

    int getIvSize();

    CipherHandle getCipher(final boolean encrypt, final byte[] secretKey, final byte[] iv);

}
