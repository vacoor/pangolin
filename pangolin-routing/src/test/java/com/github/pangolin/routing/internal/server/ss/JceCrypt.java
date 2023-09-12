package com.github.pangolin.routing.internal.server.ss;

import freework.codec.Base64;
import freework.util.Bytes;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import javax.crypto.*;
import javax.crypto.spec.IvParameterSpec;
import java.security.*;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Arrays;

public class JceCrypt {
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
    private final Provider jceProvider;

    public JceCrypt(final String transformation, final SecretKey secretKey, final int ivSize) {
        this(transformation, secretKey, ivSize, null);
    }

    public JceCrypt(final String transformation, final SecretKey secretKey, final int ivSize, final Provider jceProvider) {
        this.transformation = transformation;
        this.secretKey = secretKey;
        this.ivSize = ivSize;
        this.jceProvider = jceProvider;
    }

    public byte[] encrypt(final byte[] bytes, final int offset, final int length) throws BadPaddingException, IllegalBlockSizeException {
        final byte[] ivBytes = new byte[ivSize];
        random.nextBytes(ivBytes);
        final IvParameterSpec ivParameterSpec = new IvParameterSpec(ivBytes);
        final Cipher cipher = instantiateCipher(ivParameterSpec, Cipher.ENCRYPT_MODE);
        final byte[] encrypted = cipher.doFinal(bytes, offset, length);
        final byte[] r = Arrays.copyOf(ivBytes, ivBytes.length + encrypted.length);
        System.arraycopy(encrypted, 0, r, ivBytes.length, encrypted.length);
        return r;
    }

    public byte[] decrypt(final byte[] bytes) throws BadPaddingException, IllegalBlockSizeException {
        return decrypt(bytes, 0, bytes.length);
    }

    public byte[] decrypt(final byte[] bytes, final int offset, final int length) throws BadPaddingException, IllegalBlockSizeException {
        final IvParameterSpec ivParameterSpec = new IvParameterSpec(bytes, offset, ivSize);
        final Cipher cipher = instantiateCipher(ivParameterSpec, Cipher.DECRYPT_MODE);
        return cipher.doFinal(bytes, offset + ivSize, length - ivSize);
    }

    protected Cipher instantiateCipher(final AlgorithmParameterSpec params, final int opmode) {
        try {
            return instantiateCipher(transformation, opmode, secretKey, params, null, jceProvider);
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

    public static void main(String[] args) throws Exception {
        final SecretKey aesKey = ShadowsocksKeyFactory.generateKey("AES", 128 / 8, "123456");
        final JceCrypt AES_128_OFB = new JceCrypt(AES_OFB_NO_PADDING, aesKey, 16);
        System.out.println(Bytes.toString(AES_128_OFB.decrypt(Base64.decode("HfA9Rx4S9SFgjskIW6d0cmYZA6z4"))));

        final SecretKey bfKey = ShadowsocksKeyFactory.generateKey("Blowfish", 128 / 8, "123456");
        final JceCrypt blowfish = new JceCrypt(BLOWFISH_CFB_NO_PADDING, bfKey, 8);
        final byte[] encrypted = Base64.decode("IWSBhbyfUFatjxDC8g==", false);
        System.out.println(Bytes.toString(blowfish.decrypt(encrypted)));

        final SecretKey camelliaKey = ShadowsocksKeyFactory.generateKey("Blowfish", 128 / 8, "123456");
        final JceCrypt camellia = new JceCrypt(CAMELLIA_CFB_NO_PADDING, camelliaKey, 16, new BouncyCastleProvider());
        System.out.println(Bytes.toString(camellia.decrypt(Base64.decode("AikjfHd/SLSmXIsKuWZDaHIsr8A0", false))));

        final SecretKey seedKey = ShadowsocksKeyFactory.generateKey("Seed", 128 / 8, "123456");
        final JceCrypt seed = new JceCrypt(SEED_CFB_NO_PADDING, seedKey, 16, new BouncyCastleProvider());
        System.out.println(Bytes.toString(seed.decrypt(Base64.decode("Spng7oUONYhCoYxSD5a71Xv6/NgH", false))));

        final SecretKey chacha20Key = ShadowsocksKeyFactory.generateKey("ChaCha20", 256 / 8, "123456");
        final JceCrypt chacha20 = new JceCrypt("ChaCha20", chacha20Key, 12, new BouncyCastleProvider());
        System.out.println(Bytes.toString(chacha20.decrypt(Base64.decode("oxFAVenGgds6ng4L7znVu0o=", false))));
    }
}