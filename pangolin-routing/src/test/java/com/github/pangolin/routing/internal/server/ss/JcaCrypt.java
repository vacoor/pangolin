package com.github.pangolin.routing.internal.server.ss;

import freework.codec.Base64;
import freework.util.Bytes;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import javax.crypto.*;
import javax.crypto.spec.IvParameterSpec;
import java.security.*;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Arrays;

public class JcaCrypt {
    /*-
     * iv-size: 16
     */
    public static final String AES_CFB_NO_PADDING = "AES/CFB/NoPadding";
    public static final String AES_OFB_NO_PADDING = "AES/OFB/NoPadding";
    /*-
     * iv-size: 8
     */
    public static final String BLOWFISH_CFB_NO_PADDING = "Blowfish/CFB/NoPadding";
    /*-
     * iv-size: 16
     */
    public static final String CAMELLIA_CFB_NO_PADDING = "Camellia/CFB/NoPadding";

    /*-
     * iv-size: 16
     */
    public static final String SEED_CFB_NO_PADDING = "Seed/CFB/NoPadding";

    private final SecureRandom random = new SecureRandom();
    private final String transformation;
    private final SecretKey secretKey;
    private final int ivSize;
    private final Provider jcaProvider;

    public JcaCrypt(final String transformation, final SecretKey secretKey, final int ivSize) {
        this(transformation, secretKey, ivSize, null);
    }

    public JcaCrypt(final String transformation, final SecretKey secretKey, final int ivSize, final Provider jcaProvider) {
        this.transformation = transformation;
        this.secretKey = secretKey;
        this.ivSize = ivSize;
        this.jcaProvider = jcaProvider;
    }

    public byte[] encrypt(final byte[] bytes, final int offset, final int length) throws BadPaddingException, IllegalBlockSizeException {
        /*-
         +--------+-------------------------------------+
         |  xB iv |  variable length encrypted payload  |
         +--------+-------------------------------------+
         */
        final byte[] ivBytes = nextBytes(random, new byte[ivSize]);
        final byte[] payloadBytes = getCrypt(new IvParameterSpec(ivBytes)).encrypt(bytes, offset, length);
        final byte[] encryptedBytes = Arrays.copyOf(ivBytes, ivBytes.length + payloadBytes.length);
        System.arraycopy(payloadBytes, 0, encryptedBytes, ivBytes.length, payloadBytes.length);
        return encryptedBytes;
    }

    public byte[] decrypt(final byte[] bytes) throws BadPaddingException, IllegalBlockSizeException {
        return decrypt(bytes, 0, bytes.length);
    }

    public byte[] decrypt(final byte[] bytes, final int offset, final int length) throws BadPaddingException, IllegalBlockSizeException {
        /*-
         +--------+-------------------------------------+
         |  xB iv |  variable length encrypted payload  |
         +--------+-------------------------------------+
         */
        final IvParameterSpec ivParameterSpec = new IvParameterSpec(bytes, offset, ivSize);
        return getCrypt(ivParameterSpec).decrypt(bytes, offset + ivSize, length - ivSize);
    }

    protected Crypt getCrypt(final AlgorithmParameterSpec algorithmParameterSpec) {
        return Crypt.getSymmetric(transformation, secretKey, algorithmParameterSpec, random, jcaProvider);
    }

    private byte[] nextBytes(final SecureRandom random, byte[] bytes) {
        random.nextBytes(bytes);
        return bytes;
    }

    protected Cipher instantiateCipher(final AlgorithmParameterSpec params, final int opmode) {
        try {
            return instantiateCipher(transformation, opmode, secretKey, params, null, jcaProvider);
        } catch (final NoSuchPaddingException e) {
            throw new IllegalStateException(e);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } catch (final InvalidAlgorithmParameterException e) {
            throw new IllegalStateException(e);
        } catch (final InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    private Cipher instantiateCipher(final String transformation, final int opmode, final Key key,
                                     final AlgorithmParameterSpec params, final SecureRandom random,
                                     final Provider provider) throws NoSuchPaddingException,
            NoSuchAlgorithmException, InvalidAlgorithmParameterException, InvalidKeyException {
        final Cipher cipher = null != provider ? Cipher.getInstance(transformation, provider) : Cipher.getInstance(transformation);
        if (null == params) {
            if (null == random) {
                cipher.init(opmode, key);
            } else {
                cipher.init(opmode, key, random);
            }
        } else {
            if (null == random) {
                cipher.init(opmode, key, params);
            } else {
                cipher.init(opmode, key, params, random);
            }
        }
        return cipher;
    }

    enum Algorithm {
        AES_128_CFB("AES/CFB/NoPadding", 128 / 8, 16),
        AES_192_CFB("AES/CFB/NoPadding", 192 / 8, 16),
        AES_256_CFB("AES/CFB/NoPadding", 256 / 8, 16),
        AES_128_OFB("AES/OFB/NoPadding", 128 / 8, 16),
        AES_192_OFB("AES/OFB/NoPadding", 192 / 8, 16),
        AES_256_OFB("AES/OFB/NoPadding", 256 / 8, 16),
        BLOWFISH_CFB("Blowfish/CFB/NoPadding", 128 / 8, 8),
        SEED_CFB("Seed/CFB/NoPadding", 128 / 8, 16),
        CAMELLIA_CFB("Camellia/CFB/NoPadding", 128 / 8, 16),
        CHA_CHA_20("ChaCha20", 256 / 8, 12),
        ;

        private final String transformation;
        private final int keySize;
        private final int ivSize;

        Algorithm(final String transformation, final int keySize, final int ivSize) {
            this.transformation = transformation;
            this.keySize = keySize;
            this.ivSize = ivSize;
        }
    }

    public static void main(String[] args) throws Exception {
        final SecretKey aesKey = ShadowsocksKeyFactory.generateKey("AES", 128 / 8, "123456");
        final JcaCrypt AES_128_OFB = new JcaCrypt(AES_OFB_NO_PADDING, aesKey, 16);
        System.out.println(Bytes.toString(AES_128_OFB.decrypt(Base64.decode("HfA9Rx4S9SFgjskIW6d0cmYZA6z4"))));

        final SecretKey bfKey = ShadowsocksKeyFactory.generateKey("Blowfish", 128 / 8, "123456");
        final JcaCrypt blowfish = new JcaCrypt(BLOWFISH_CFB_NO_PADDING, bfKey, 8);
        final byte[] encrypted = Base64.decode("IWSBhbyfUFatjxDC8g==", false);
        System.out.println(Bytes.toString(blowfish.decrypt(encrypted)));

        final SecretKey camelliaKey = ShadowsocksKeyFactory.generateKey("Blowfish", 128 / 8, "123456");
        final JcaCrypt camellia = new JcaCrypt(CAMELLIA_CFB_NO_PADDING, camelliaKey, 16, new BouncyCastleProvider());
        System.out.println(Bytes.toString(camellia.decrypt(Base64.decode("AikjfHd/SLSmXIsKuWZDaHIsr8A0", false))));

        final SecretKey seedKey = ShadowsocksKeyFactory.generateKey("Seed", 128 / 8, "123456");
        final JcaCrypt seed = new JcaCrypt(SEED_CFB_NO_PADDING, seedKey, 16, new BouncyCastleProvider());
        System.out.println(Bytes.toString(seed.decrypt(Base64.decode("Spng7oUONYhCoYxSD5a71Xv6/NgH", false))));

        final SecretKey chacha20Key = ShadowsocksKeyFactory.generateKey("ChaCha20", 256 / 8, "123456");
        final JcaCrypt chacha20 = new JcaCrypt("ChaCha20", chacha20Key, 12, new BouncyCastleProvider());
        System.out.println(Bytes.toString(chacha20.decrypt(Base64.decode("oxFAVenGgds6ng4L7znVu0o=", false))));
    }
}