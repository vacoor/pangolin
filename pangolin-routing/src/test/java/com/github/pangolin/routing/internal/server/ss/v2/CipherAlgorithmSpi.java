package com.github.pangolin.routing.internal.server.ss.v2;

import java.util.ServiceLoader;

public abstract class CipherAlgorithmSpi {

    abstract public boolean isSupported(final String algorithm);

    abstract public CipherAlgorithm getCrypt(final String algorithm);

    public static CipherAlgorithm getInstance(final String algorithm) {
        for (final CipherAlgorithmSpi spi : ServiceLoader.load(CipherAlgorithmSpi.class)) {
            if (spi.isSupported(algorithm)) {
                return spi.getCrypt(algorithm);
            }
        }
        throw new IllegalStateException("NoSuchAlgorithm: " + algorithm);
    }

}
