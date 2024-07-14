package com.github.pangolin.routing.handler.codec.ss.crypto;

import freework.util.Bytes;
import org.bouncycastle.crypto.digests.SHA1Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;

public class SsSubKey {

    /**
     * @param masterKey the master key
     * @param salt      the non-secret encodeSalt
     * @return the subkey
     * @see <a href="https://github.com/shadowsocks/shadowsocks-org/wiki/AEAD-Ciphers#key-derivation">Key Derivation</a>
     */
    public static byte[] generateSubkey(final byte[] masterKey, final byte[] salt) {
        final HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA1Digest());
        hkdf.init(new HKDFParameters(masterKey, salt, Bytes.toBytes("ss-subkey")));

        final byte[] okm = new byte[masterKey.length];
        final int written = hkdf.generateBytes(okm, 0, masterKey.length);
        assert written == masterKey.length;
        return okm;
    }

}