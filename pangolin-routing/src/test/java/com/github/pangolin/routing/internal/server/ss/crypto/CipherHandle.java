package com.github.pangolin.routing.internal.server.ss.crypto;

/**
 *
 */
public interface CipherHandle {

    int update(final byte[] inBytes, int inOffset, int inLength,
               final byte[] outBytes, final int outOffset) throws Exception;


    int doFinal(final byte[] inBytes, int inOffset, int inLength,
                final byte[] outBytes, final int outOffset) throws Exception;

}
