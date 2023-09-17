package com.github.pangolin.routing.internal.server.ss.crypto.impl.stream;

import com.github.pangolin.routing.internal.server.ss.crypto.ShadowsocksStreamCrypt;
import org.bouncycastle.crypto.StreamCipher;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;

public abstract class AbstractStreamCrypt implements ShadowsocksStreamCrypt {
    private final int keySize;
    private final int ivSize;

    public AbstractStreamCrypt(final int keySize, final int ivSize) {
        this.keySize = keySize;
        this.ivSize = ivSize;
    }

    @Override
    public int getKeySize() {
        return keySize;
    }

    @Override
    public int getIvSize() {
        return ivSize;
    }

    @Override
    public StreamCipher getCipher(final boolean encrypt, final byte[] key, final byte[] iv) {
        final StreamCipher cipher = createCipher();
        cipher.init(encrypt, new ParametersWithIV(new KeyParameter(key), iv));
        return cipher;
    }

    protected abstract StreamCipher createCipher();

}