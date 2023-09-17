package com.github.pangolin.routing.internal.server.ss.crypto.impl.stream;

import org.bouncycastle.crypto.StreamCipher;
import org.bouncycastle.crypto.engines.CamelliaEngine;
import org.bouncycastle.crypto.modes.CFBBlockCipher;

public abstract class CamelliaCfbCrypt extends AbstractStreamCrypt {

    public CamelliaCfbCrypt(final int keySize) {
        super(keySize, 16);
    }

    @Override
    protected StreamCipher createCipher() {
        return new CFBBlockCipher(new CamelliaEngine(), getIvSize() * Byte.SIZE);
    }

    public static class Camellia128Cfb extends CamelliaCfbCrypt {
        public Camellia128Cfb() {
            super(128 / 8);
        }
    }

    public static class Camellia192Cfb extends CamelliaCfbCrypt {
        public Camellia192Cfb() {
            super(192 / 8);
        }
    }

    public static class Camellia256Cfb extends CamelliaCfbCrypt {
        public Camellia256Cfb() {
            super(256 / 8);
        }
    }
}