package com.github.pangolin.routing.internal.server.ss.crypto;

import freework.util.Bytes;
import org.bouncycastle.crypto.digests.SHA1Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.Key;
import java.security.SecureRandom;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Arrays;

public class Stateful {
    static final String AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding";
    static final int GCM_IV_SIZE = 12;
    static final int GCM_TAG_SIZE = 16;
    static final int LENGTH_SIZE = 2;
    static final int CHUNK_SIZE_MASK = 0x3FFF;
//    static final int CHUNK_SIZE_MASK = 0x3;

    private static abstract class Base {
        private final byte[] nonce;
        protected final byte[] encodedKey;

        public Base(final byte[] keys, final int ivSize) {
            this.encodedKey = keys;
            this.nonce = new byte[ivSize];
        }

        protected byte[] nextNone() {
            for (int i = 0; i < nonce.length; i++) {
                ++nonce[i];
//                nonce[i]++;
                if (nonce[i] != 0) {
                    break;
                }
            }
            return nonce;
        }

        byte[] crypt(final int opmode, final Key secretKey, final byte[] bytes) throws Exception {
            return crypt(opmode, secretKey, bytes, 0, bytes.length);
        }

        byte[] crypt(final int opmode, final Key secretKey, final byte[] bytes, int offset, int length) throws Exception {
            final Cipher cipher = cipher(opmode, secretKey, nonce);
            nextNone();
            return cipher.doFinal(bytes, offset, length);
        }

        Cipher cipher(final int opmode, final Key secretKey, final byte[] nonce) throws Exception {
            final AlgorithmParameterSpec algorithmParameterSpec2 = new GCMParameterSpec(GCM_TAG_SIZE * Byte.SIZE, nonce);
            final Cipher cipher2 = Cipher.getInstance(AES_GCM_TRANSFORMATION, new BouncyCastleProvider());
            cipher2.init(opmode, secretKey, algorithmParameterSpec2);
            return cipher2;
        }

        byte[] genSubkey(final byte[] encoded, final int keySize, byte[] salt) {
            HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA1Digest());
            hkdf.init(new HKDFParameters(encoded, salt, Bytes.toBytes("ss-subkey")));
            byte[] okm = new byte[keySize];
            hkdf.generateBytes(okm, 0, keySize);
            return okm;
        }
    }

    public static class Encoder extends Base {
        private final byte[] salt = new byte[16];
        private final SecretKey secretKey;

        public Encoder(byte[] keys) {
            super(keys, GCM_IV_SIZE);
            new SecureRandom().nextBytes(salt);
            secretKey = new SecretKeySpec(genSubkey(encodedKey, 128 / 8, salt), "AES");
        }

        public byte[] encrypt(final byte[] bytes) throws Exception {
            final int l = (int) Math.ceil(1d * bytes.length / CHUNK_SIZE_MASK) * (16 + 2 + GCM_TAG_SIZE + GCM_TAG_SIZE) + bytes.length;
            final byte[] buffer = new byte[l];
            for (int offset = 0, outOffset = 0; offset < bytes.length; ) {
                final int len = Math.min(bytes.length - offset, CHUNK_SIZE_MASK);

                final byte[] encryptedLen = crypt(Cipher.ENCRYPT_MODE, secretKey, new byte[]{(byte) ((len >>> 8) & 0xff), (byte) (len & 0xff)});
                final byte[] encryptedPayload = crypt(Cipher.ENCRYPT_MODE, secretKey, bytes, offset, len);

            /*
            final byte[] encrypted = Arrays.copyOf(salt, salt.length + encryptedLen.length + encryptedPayload.length);
            System.arraycopy(encryptedLen, 0, encrypted, salt.length, encryptedLen.length);
            System.arraycopy(encryptedPayload, 0, encrypted, salt.length + encryptedLen.length, encryptedPayload.length);
            return encrypted;
            */
                System.arraycopy(salt, 0, buffer, outOffset, salt.length);
                System.arraycopy(encryptedLen, 0, buffer, outOffset + salt.length, encryptedLen.length);
                System.arraycopy(encryptedPayload, 0, buffer, outOffset + salt.length + encryptedLen.length, encryptedPayload.length);

                offset += len;
                outOffset += salt.length + encryptedLen.length + encryptedPayload.length;
                System.out.println(outOffset <= buffer.length);
            }
            return buffer;
        }

    }

    public static class Decoder extends Base {

        public Decoder(byte[] keys) {
            super(keys, GCM_IV_SIZE);
        }

        public byte[] decrypt(final byte[] cipherBytes) throws Exception {
            int offset = 0;
            final SecretKeySpec secretKey = new SecretKeySpec(genSubkey(encodedKey, 128 / 8, Arrays.copyOfRange(cipherBytes, offset, 16)), "AES");
            offset += 16;


            final byte[] decrypted = crypt(Cipher.DECRYPT_MODE, secretKey, cipherBytes, offset, LENGTH_SIZE + GCM_TAG_SIZE);
            offset += LENGTH_SIZE + GCM_TAG_SIZE;
            int payloadLength = (decrypted[0] & 0xff) << 8 | (decrypted[1] & 0xff);

            return crypt(Cipher.DECRYPT_MODE, secretKey, cipherBytes, offset, payloadLength + GCM_TAG_SIZE);
            // index += payloadLength + GCM_TAG_SIZE;
        }

    }
}