package com.github.pangolin.routing.handler.codec.ss.crypto.spi;

import com.github.pangolin.routing.handler.codec.ss.crypto.AeadCipherAlgorithm;
import com.github.pangolin.routing.handler.codec.ss.crypto.CipherAlgorithm;
import com.github.pangolin.routing.handler.codec.ss.crypto.CipherHandle;
import com.github.pangolin.routing.handler.codec.ss.crypto.StreamCipherAlgorithm;
import org.bouncycastle.crypto.CipherParameters;
import org.bouncycastle.crypto.StreamCipher;
import org.bouncycastle.crypto.engines.*;
import org.bouncycastle.crypto.modes.AEADCipher;
import org.bouncycastle.crypto.modes.CFBBlockCipher;
import org.bouncycastle.crypto.modes.ChaCha20Poly1305;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.modes.SICBlockCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 *
 */
public class BouncyCastleCipherAlgorithmSpi extends CipherAlgorithmSpi {
    private static Map<String, CipherAlgorithm> algorithmMap = new HashMap<>();

    static {
        final Function<Algorithm, StreamCipher> aesCtrFactory = factory -> new SICBlockCipher(new AESEngine());
        final Function<Algorithm, StreamCipher> aesCfbFactory = factory -> new CFBBlockCipher(new AESEngine(), factory.getIvSize() * Byte.SIZE);
        final Function<Algorithm, StreamCipher> camelliaCfbFactory = factory -> new CFBBlockCipher(new CamelliaEngine(), factory.getIvSize() * Byte.SIZE);
        final Function<Algorithm, StreamCipher> salsa20Factory = factory -> new Salsa20Engine();
        final Function<Algorithm, StreamCipher> chacha20Factory = factory -> new ChaChaEngine();
        final Function<Algorithm, StreamCipher> chacha20IetfFactory = factory -> new ChaCha7539Engine();
        final Function<Algorithm, StreamCipher> blowfishCfbFactory = factory -> new CFBBlockCipher(new BlowfishEngine(), factory.getIvSize() * Byte.SIZE);
        final Function<Algorithm, StreamCipher> rc4Factory = factory -> new RC4Engine();

        final Function<AEADAlgorithm, AEADCipher> aesGcmFactory = factory -> new GCMBlockCipher(new AESEngine());
        final Function<AEADAlgorithm, AEADCipher> chacha20IetfPoly1305Factory = factory -> new ChaCha20Poly1305();

        /*-
         */
        final CipherAlgorithm[] algorithms = {
                /*-
                 * @see <a href="https://github.com/shadowsocks/shadowsocks-org/wiki/Stream-Ciphers">Stream Ciphers</a>
                 */
                new Algorithm("aes-128-ctr", 128 / Byte.SIZE, 16, aesCtrFactory),
                new Algorithm("aes-192-ctr", 192 / Byte.SIZE, 16, aesCtrFactory),
                new Algorithm("aes-256-ctr", 256 / Byte.SIZE, 16, aesCtrFactory),
                new Algorithm("aes-128-cfb", 128 / Byte.SIZE, 16, aesCfbFactory),
                new Algorithm("aes-192-cfb", 192 / Byte.SIZE, 16, aesCfbFactory),
                new Algorithm("aes-256-cfb", 256 / Byte.SIZE, 16, aesCfbFactory),
                new Algorithm("camellia-128-cfb", 128 / Byte.SIZE, 16, camelliaCfbFactory),
                new Algorithm("camellia-192-cfb", 192 / Byte.SIZE, 16, camelliaCfbFactory),
                new Algorithm("camellia-256-cfb", 256 / Byte.SIZE, 16, camelliaCfbFactory),
                new Algorithm("chacha20-ietf", 256 / Byte.SIZE, 12, chacha20IetfFactory),
                new Algorithm("bf-cfb", 128 / Byte.SIZE, 8, blowfishCfbFactory),
                new Algorithm("chacha20", 256 / Byte.SIZE, 8, chacha20Factory),
                new Algorithm("salsa20", 256 / Byte.SIZE, 8, salsa20Factory),
                new Rc4Md5("rc4-md5", 128 / Byte.SIZE, 16, rc4Factory),
                /*-
                 * @see <a href="https://github.com/shadowsocks/shadowsocks-org/wiki/AEAD-Ciphers">AEAD Ciphers</a>
                 */
                new AEADAlgorithm("aes-128-gcm", 128 / Byte.SIZE, 16, 12, 16, aesGcmFactory),
                new AEADAlgorithm("aes-256-gcm", 256 / Byte.SIZE, 32, 12, 16, aesGcmFactory),
                new AEADAlgorithm("chacha20-ietf-poly1305", 256 / Byte.SIZE, 32, 12, 16, chacha20IetfPoly1305Factory)
        };
        for (CipherAlgorithm algorithm : algorithms) {
            algorithmMap.put(algorithm.getName(), algorithm);
        }
    }

    @Override
    public boolean isSupported(final String algorithm) {
        return null != algorithm && algorithmMap.containsKey(algorithm.toLowerCase());
    }


    @Override
    public CipherAlgorithm getCrypt(final String algorithm) {
        final CipherAlgorithm alm = null != algorithm ? algorithmMap.get(algorithm.toLowerCase()) : null;
        if (null == alm) {
            throw new IllegalStateException("Can not found algorithm: " + alm);
        }
        return alm;
    }


    private static class Algorithm implements StreamCipherAlgorithm {
        private final String algorithm;
        private final int keySize;
        private final int ivSize;
        private final Function<Algorithm, StreamCipher> factory;

        public Algorithm(final String algorithm, final int keySize, final int ivSize, final Function<Algorithm, StreamCipher> factory) {
            this.algorithm = algorithm;
            this.keySize = keySize;
            this.ivSize = ivSize;
            this.factory = factory;
        }

        @Override
        public String getName() {
            return algorithm;
        }

        @Override
        public int getKeySize() {
            return keySize;
        }

        @Override
        public int getIvSize() {
            return ivSize;
        }

        public CipherHandle getCipher(final boolean encrypt, final byte[] key, final byte[] iv) {
            final StreamCipher cipher = factory.apply(this);
            cipher.init(encrypt, new ParametersWithIV(new KeyParameter(key), iv));
            return new StreamCipherHandleProxy(cipher);
        }

        class StreamCipherHandleProxy implements CipherHandle {
            private final StreamCipher cipher;

            private StreamCipherHandleProxy(final StreamCipher cipher) {
                this.cipher = cipher;
            }

            @Override
            public int update(final byte[] inBytes, final int inOffset, final int inLength, final byte[] outBytes, final int outOffset) throws Exception {
                return cipher.processBytes(inBytes, inOffset, inLength, outBytes, outOffset);
            }

            public int doFinal(final byte[] inBytes, final int inOffset, final int inLength, final byte[] outBytes, final int outOffset) throws Exception {
                return cipher.processBytes(inBytes, inOffset, inLength, outBytes, outOffset);
            }
        }
    }

    private static class Rc4Md5 extends Algorithm {

        public Rc4Md5(final String algorithm, final int keySize, final int ivSize, final Function<Algorithm, StreamCipher> factory) {
            super(algorithm, keySize, ivSize, factory);
        }

        @Override
        public StreamCipherHandleProxy getCipher(final boolean encrypt, final byte[] key, final byte[] iv) {
            try {
                final MessageDigest messageDigest = MessageDigest.getInstance("md5");
                messageDigest.update(key);
                final byte[] digest = messageDigest.digest(iv);
                final RC4Engine rc4Engine = new RC4Engine();
                rc4Engine.init(encrypt, new KeyParameter(digest));
                // rc4Engine.init(encrypt, new ParametersWithIV(new KeyParameter(key), ivDigest));
                return new StreamCipherHandleProxy(rc4Engine);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static class AEADAlgorithm implements AeadCipherAlgorithm {
        private final String algorithm;
        private final int keySize;
        private final int saltSize;
        private final int nonceSize;
        private final int tagSize;
        private final Function<AEADAlgorithm, AEADCipher> factory;

        public AEADAlgorithm(final String algorithm, final int keySize, final int saltSize, final int nonceSize, final int tagSize, final Function<AEADAlgorithm, AEADCipher> factory) {
            this.algorithm = algorithm;
            this.keySize = keySize;
            this.saltSize = saltSize;
            this.nonceSize = nonceSize;
            this.tagSize = tagSize;
            this.factory = factory;
        }

        @Override
        public String getName() {
            return algorithm;
        }

        @Override
        public int getKeySize() {
            return keySize;
        }

        @Override
        public int getSaltSize() {
            return saltSize;
        }

        @Override
        public int getNonceSize() {
            return nonceSize;
        }

        @Override
        public int getTagSize() {
            return tagSize;
        }

        public CipherHandle getCipher(final boolean encrypt, final byte[] key, final byte[] nonce) {
            final AEADCipher cipher = factory.apply(this);
            final CipherParameters cipherParameters = new AEADParameters(new KeyParameter(key), tagSize * Byte.SIZE, nonce);
            cipher.init(encrypt, cipherParameters);
            return new AEADCipherHandleProxy(cipher);
        }

        private class AEADCipherHandleProxy implements CipherHandle {
            private final AEADCipher cipher;

            private AEADCipherHandleProxy(final AEADCipher cipher) {
                this.cipher = cipher;
            }

            @Override
            public int update(final byte[] inBytes, final int inOffset, final int inLength, final byte[] outBytes, final int outOffset) throws Exception {
                return cipher.processBytes(inBytes, inOffset, inLength, outBytes, outOffset);
            }

            public int doFinal(final byte[] inBytes, final int inOffset, final int inLength, final byte[] outBytes, final int outOffset) throws Exception {
                final int len = cipher.processBytes(inBytes, inOffset, inLength, outBytes, outOffset);
                return len + cipher.doFinal(outBytes, outOffset + len);
            }
        }
    }


    public static void main(String[] args) {
        final CipherAlgorithm instance = CipherAlgorithmSpi.getInstance("chacha20-ietf-poly1305");
        System.out.println(instance);
    }
}
