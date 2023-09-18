package com.github.pangolin.routing.internal.server.ss.crypto.impl.stream;

import com.github.pangolin.routing.internal.server.ss.crypto.ShadowsocksStreamCrypt;
import org.bouncycastle.crypto.StreamCipher;
import org.bouncycastle.crypto.engines.RC4Engine;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 */
public class Rc4Md5Crypt implements ShadowsocksStreamCrypt {

    @Override
    public int getKeySize() {
        return 16;
    }

    @Override
    public int getIvSize() {
        return 16;
    }

    @Override
    public StreamCipher getCipher(final boolean encrypt, final byte[] key, final byte[] iv) {
        try {
            final MessageDigest messageDigest = MessageDigest.getInstance("md5");
            messageDigest.update(key);
            final byte[] digest = messageDigest.digest(iv);
            final RC4Engine rc4Engine = new RC4Engine();
            rc4Engine.init(encrypt, new KeyParameter(digest));
            // rc4Engine.init(encrypt, new ParametersWithIV(new KeyParameter(key), ivDigest));
            return rc4Engine;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
