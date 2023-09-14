package com.github.pangolin.routing.internal.server.ss.codec;

import com.github.pangolin.routing.internal.server.ss.Crypt;
import com.github.pangolin.routing.internal.server.ss.JcaCrypt;
import com.github.pangolin.routing.internal.server.ss.ShadowsocksKeyFactory;
import freework.codec.Base64;
import freework.util.Bytes;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ShadowsocksCodec extends ByteToMessageCodec<ByteBuf> {
    public enum Algorithm {
        /*-
         * v1.3-1: aes-128-cfb, aes-192-cfb, aes-256-cfb, bf-cfb, cast5-cfb, des-cfb.
         */
        AES_128_CFB("AES/CFB/NoPadding", 128 / 8, 16),
        AES_192_CFB("AES/CFB/NoPadding", 192 / 8, 16),
        AES_256_CFB("AES/CFB/NoPadding", 256 / 8, 16),
        BLOWFISH_CFB("Blowfish/CFB/NoPadding", 128 / 8, 8),

        /*-
         * v1.3.1-1: camellia, idea, rc2 and seed.
         */
        CAMELLIA_CFB("Camellia/CFB/NoPadding", 128 / 8, 16),
        SEED_CFB("Seed/CFB/NoPadding", 128 / 8, 16),
        /*-
         * v2.5.0-1: aes-128/192/256-ctr.
         */

        /*-
         * others.
         */
        AES_128_OFB("AES/OFB/NoPadding", 128 / 8, 16),
        AES_192_OFB("AES/OFB/NoPadding", 192 / 8, 16),
        AES_256_OFB("AES/OFB/NoPadding", 256 / 8, 16),

        CHA_CHA_20("ChaCha20", 256 / 8, 12),
        ;

        public final String transformation;
        public final int keySize;
        public final int ivSize;

        Algorithm(final String transformation, final int keySize, final int ivSize) {
            this.transformation = transformation;
            this.keySize = keySize;
            this.ivSize = ivSize;
        }
    }

    private final String transformation;
    private final SecretKey secretKey;
    private final int ivSize;
    private final SecureRandom random;
    private final Provider provider;
    private final byte[] ivBytes;
    private volatile boolean handshaked;

    public ShadowsocksCodec(Algorithm algorithm, final SecretKey secretKey, final Provider provider) {
        this(algorithm.transformation, secretKey, algorithm.ivSize, new SecureRandom(), provider);
    }

    public ShadowsocksCodec(String transformation, SecretKey secretKey, int ivSize, SecureRandom random, Provider provider) {
        this.transformation = transformation;
        this.secretKey = secretKey;
        this.ivSize = ivSize;
        this.random = random;
        this.provider = provider;
        this.ivBytes = nextBytes(random, new byte[ivSize]);
    }

    @Override
    protected void encode(final ChannelHandlerContext ctx, ByteBuf in, ByteBuf out) throws Exception {
        // FIXME 32K 缓冲区必须
        final byte[] bytes = new byte[in.readableBytes()];
        in.readBytes(bytes);
        if (!handshaked) {
            out.writeBytes(encrypt(bytes, 0, bytes.length, !handshaked));
            handshaked = true;
        } else {
            out.writeBytes(encrypt(bytes, 0, bytes.length, !handshaked));
        }
    }

    @Override
    protected void decode(ChannelHandlerContext channelHandlerContext, ByteBuf in, List<Object> out) throws Exception {
        final byte[] bytes = new byte[in.readableBytes()];
        in.readBytes(bytes);
        out.add(Unpooled.wrappedBuffer(decrypt(bytes, 0, bytes.length)));
    }

    private byte[] decrypt(final byte[] bytes) {
        return decrypt(bytes, 0, bytes.length);
    }

    private byte[] encrypt(final byte[] bytes, final int offset, final int length, boolean iv) {
        /*-
         +--------+-------------------------------------+
         |  xB iv |  variable length encrypted payload  |
         +--------+-------------------------------------+
         */
        final byte[] payloadBytes = getCrypt(new IvParameterSpec(ivBytes)).encrypt(bytes, offset, length);
        if (iv) {
            final byte[] encryptedBytes = Arrays.copyOf(ivBytes, ivBytes.length + payloadBytes.length);
            System.arraycopy(payloadBytes, 0, encryptedBytes, ivBytes.length, payloadBytes.length);
            return encryptedBytes;
        }
        return payloadBytes;
    }



    private byte[] decrypt(final byte[] bytes, final int offset, final int length) {
        /*-
         +--------+-------------------------------------+
         |  xB iv |  variable length encrypted payload  |
         +--------+-------------------------------------+
         */
        final IvParameterSpec ivParameterSpec = new IvParameterSpec(bytes, offset, ivSize);
        return getCrypt(ivParameterSpec).decrypt(bytes, offset + ivSize, length - ivSize);
    }

    private Crypt getCrypt(final AlgorithmParameterSpec algorithmParameterSpec) {
        return Crypt.getSymmetric(transformation, secretKey, algorithmParameterSpec, random, provider);
    }

    private byte[] nextBytes(final SecureRandom random, byte[] bytes) {
        random.nextBytes(bytes);
        return bytes;
    }

    private static String getAlgorithm(final String transformation) {
        final int i = null != transformation ? transformation.indexOf('/') : -1;
        return -1 < i ? transformation.substring(0, i) : transformation;
    }

    public static void main(String[] args) throws Exception {
        final BouncyCastleProvider provider = new BouncyCastleProvider();
        final Map<Algorithm, String> algorithmMap = new LinkedHashMap<Algorithm, String>();
        algorithmMap.put(Algorithm.AES_128_OFB, "HfA9Rx4S9SFgjskIW6d0cmYZA6z4");
        algorithmMap.put(Algorithm.BLOWFISH_CFB, "IWSBhbyfUFatjxDC8g==");
        algorithmMap.put(Algorithm.CAMELLIA_CFB, "AikjfHd/SLSmXIsKuWZDaHIsr8A0");
        algorithmMap.put(Algorithm.SEED_CFB, "Spng7oUONYhCoYxSD5a71Xv6/NgH");
        algorithmMap.put(Algorithm.CHA_CHA_20, "oxFAVenGgds6ng4L7znVu0o=");

        for (Map.Entry<Algorithm, String> entry : algorithmMap.entrySet()) {
            final Algorithm algorithm = entry.getKey();
            final String encrypted = entry.getValue();

            final String algorithmName = getAlgorithm(algorithm.transformation);
            final SecretKey key = ShadowsocksKeyFactory.generateKey(algorithmName, algorithm.keySize, "123456");
            final ShadowsocksCodec AES_128_OFB = new ShadowsocksCodec(algorithm, key, provider);
            System.out.println(Bytes.toString(AES_128_OFB.decrypt(Base64.decode(encrypted))) + " " + algorithm.transformation);
        }

        /*
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
        */
    }
}
