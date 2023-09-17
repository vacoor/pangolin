package com.github.pangolin.routing.internal.server.ss.crypto.impl.stream;

import org.bouncycastle.crypto.StreamCipher;
import org.bouncycastle.crypto.engines.BlowfishEngine;
import org.bouncycastle.crypto.modes.CFBBlockCipher;

/**
 * bf-cfb.
 */
public class BlowfishCfbCrypt extends AbstractStreamCrypt {

    public BlowfishCfbCrypt() {
        super(16, 8);
    }

    @Override
    protected StreamCipher createCipher() {
        return new CFBBlockCipher(new BlowfishEngine(), getIvSize() * Byte.SIZE);
    }

}