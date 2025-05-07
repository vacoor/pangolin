package com.github.pangolin.routing.support.handler.codec.ss.crypto.spi;

import com.github.pangolin.routing.support.handler.codec.ss.crypto.CipherAlgorithm;

import java.security.NoSuchAlgorithmException;
import java.util.ServiceLoader;

public abstract class CipherAlgorithmSpi {

    abstract public boolean isSupported(final String algorithm);

    abstract public CipherAlgorithm getCrypt(final String algorithm);

    public static CipherAlgorithm getInstance(final String algorithm) throws NoSuchAlgorithmException {
        for (final CipherAlgorithmSpi spi : ServiceLoader.load(CipherAlgorithmSpi.class)) {
            if (spi.isSupported(algorithm)) {
                return spi.getCrypt(algorithm);
            }
        }
        throw new NoSuchAlgorithmException("NoSuchAlgorithm: " + algorithm);
    }

}
