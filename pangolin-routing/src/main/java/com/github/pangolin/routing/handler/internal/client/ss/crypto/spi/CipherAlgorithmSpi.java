package com.github.pangolin.routing.handler.internal.client.ss.crypto.spi;

import com.github.pangolin.routing.handler.internal.client.ss.crypto.CipherAlgorithm;

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
