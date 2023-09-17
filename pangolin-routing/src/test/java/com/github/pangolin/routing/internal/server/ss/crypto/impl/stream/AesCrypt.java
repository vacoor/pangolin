package com.github.pangolin.routing.internal.server.ss.crypto.impl.stream;

import com.github.pangolin.routing.internal.server.ss.crypto.ShadowsocksStreamCrypt;
import org.bouncycastle.crypto.StreamCipher;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.CFBBlockCipher;
import org.bouncycastle.crypto.modes.OFBBlockCipher;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;

public abstract class AesCrypt implements ShadowsocksStreamCrypt {
    private final int keySize;

    public AesCrypt(final int keySize) {
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
        final StreamCipher cipher = createCipher();
        cipher.init(encrypt, new ParametersWithIV(new KeyParameter(key), iv));
        return cipher;
    }

    protected abstract StreamCipher createCipher();

    public static class AesCtr extends AesCrypt {

        public AesCtr(final int keySize) {
            super(keySize);
        }

        @Override
        protected StreamCipher createCipher() {
            return new OFBBlockCipher(new AESEngine(), getIvSize() * Byte.SIZE);
        }
    }


    public static class AesCfb extends AesCrypt {

        public AesCfb(final int keySize) {
            super(keySize);
        }

        @Override
        protected StreamCipher createCipher() {
            return new CFBBlockCipher(new AESEngine(), getIvSize() * Byte.SIZE);
        }
    }

    public static class Aes128Cfb extends AesCfb {

        public Aes128Cfb() {
            super(128 / 8);
        }

    }

    public static class Aes192Cfb extends AesCfb {

        public Aes192Cfb() {
            super(192 / 8);
        }

    }

    public static class Aes256Cfb extends AesCfb {

        public Aes256Cfb() {
            super(256 / 8);
        }

    }
}