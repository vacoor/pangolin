package com.github.pangolin.routing.internal.server.ss.crypto.impl.stream;

import org.bouncycastle.crypto.StreamCipher;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.CFBBlockCipher;
import org.bouncycastle.crypto.modes.SICBlockCipher;

public abstract class AesCrypt extends AbstractStreamCrypt {

    public AesCrypt(final int keySize) {
        super(keySize, 16);
    }

    public static class AesCtr extends AesCrypt {

        public AesCtr(final int keySize) {
            super(keySize);
        }

        @Override
        protected StreamCipher createCipher() {
            return new SICBlockCipher(new AESEngine());
        }
    }

    public static class Aes128Ctr extends AesCtr {

        public Aes128Ctr() {
            super(128 / 8);
        }

    }

    public static class Aes192Ctr extends AesCtr {

        public Aes192Ctr() {
            super(192 / 8);
        }

    }

    public static class Aes256Ctr extends AesCtr {

        public Aes256Ctr() {
            super(256 / 8);
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