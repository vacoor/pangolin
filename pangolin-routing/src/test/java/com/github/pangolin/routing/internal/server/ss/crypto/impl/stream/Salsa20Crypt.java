package com.github.pangolin.routing.internal.server.ss.crypto.impl.stream;

import org.bouncycastle.crypto.StreamCipher;
import org.bouncycastle.crypto.engines.Salsa20Engine;

/**
 * salsa20.
 */
public class Salsa20Crypt extends AbstractStreamCrypt {

    public Salsa20Crypt() {
        super(32, 8);
    }

    @Override
    public StreamCipher createCipher() {
        return new Salsa20Engine();
    }

}