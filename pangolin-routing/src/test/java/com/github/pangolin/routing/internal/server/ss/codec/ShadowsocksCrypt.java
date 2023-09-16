package com.github.pangolin.routing.internal.server.ss.codec;

public interface ShadowsocksCrypt {

    int encrypt(final byte[] inBytes, int inOffset, int inLength, final byte[] outBytes, final int outOffset) throws Exception;

    int decrypt(final byte[] inBytes, int inOffset, int inLength, final byte[] outBytes, final int outOffset) throws Exception;


}
