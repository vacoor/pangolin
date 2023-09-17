package com.github.pangolin.routing.internal.server.ss.crypto;

import org.bouncycastle.crypto.StreamCipher;

public interface ShadowsocksStreamCrypt {

    int getKeySize();

    int getIvSize();

    StreamCipher getCipher(final boolean encrypt, final byte[] key, final byte[] iv);

}