package com.github.pangolin.routing.internal.server.ss.crypto.impl.stream;

import com.github.pangolin.routing.internal.server.ss.crypto.ShadowsocksStreamCrypt;
import org.bouncycastle.crypto.StreamCipher;
import org.bouncycastle.crypto.engines.CamelliaEngine;
import org.bouncycastle.crypto.modes.CFBBlockCipher;

public class CamelliaCfbCrypt implements ShadowsocksStreamCrypt {
    private final int keySize;

    public CamelliaCfbCrypt(final int keySize) {
        this.keySize = keySize;
    }

    @Override
    public int getKeySize() {
        return keySize;
    }

    @Override
    public int getIvSize() {
        return 16;
    }

    @Override
    public StreamCipher getCipher(final boolean encrypt, final byte[] key, final byte[] iv) {
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