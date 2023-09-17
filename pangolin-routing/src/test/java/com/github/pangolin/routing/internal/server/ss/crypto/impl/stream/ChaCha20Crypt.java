package com.github.pangolin.routing.internal.server.ss.crypto.impl.stream;

import org.bouncycastle.crypto.StreamCipher;
import org.bouncycastle.crypto.engines.ChaCha7539Engine;

/**
 * chacha20-ietf.
 */
public class ChaCha20Crypt extends AbstractStreamCrypt {

    public ChaCha20Crypt() {
        super(32, 12);
    }

    @Override
    protected StreamCipher createCipher() {
        return new ChaCha7539Engine();
    }

}