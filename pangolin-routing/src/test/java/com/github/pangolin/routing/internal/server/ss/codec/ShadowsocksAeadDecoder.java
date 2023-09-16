package com.github.pangolin.routing.internal.server.ss.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ReplayingDecoder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.Provider;
import java.security.spec.AlgorithmParameterSpec;
import java.util.List;

public class ShadowsocksAeadDecoder extends ReplayingDecoder<ShadowsocksAeadDecoder.State> {
    private static final int LENGTH_SIZE = 2;

    private static final int KEY_SIZE = 16;
    private static final int SALT_SIZE = 16;
    private static final int GCM_NONCE_SIZE = 12;
    private static final int GCM_TAG_SIZE = 16;

    private final byte[] masterKey;
    private final byte[] nonce = new byte[GCM_NONCE_SIZE];

    private byte[] subkey;
    private int chunkSize;

    public ShadowsocksAeadDecoder(final byte[] masterKey) {
        super(State.READ_SALT);
        this.masterKey = masterKey;
    }


    @Override
    protected void decode(final ChannelHandlerContext ctx, final ByteBuf in, List<Object> out) throws Exception {
        /*-
         Reference: https://github.com/shadowsocks/shadowsocks-org/wiki/AEAD-Ciphers
         +------+--------------------------+---------------------------+------------------------+-------------------------+-----+
         | salt |  encrypted header chunk  |  encrypted payload chunk  | encrypted header chunk | encrypted payload chunk | ... |
         +------+--------------------------+---------------------------+------------------------+-------------------------+-----+
         |  16B | 2B payload len + 16B tag | variable length + 16B tag | ...                    | ...                     | ... |
         +------+--------------------------+---------------------------+------------------------+-------------------------+-----+
         */
        while (in.isReadable()) {
            switch (state()) {
                case READ_SALT:
                    final byte[] salt = readAsBytes(in, SALT_SIZE);
                    subkey = ShadowsocksAeadEncoder.generateSubkey(masterKey, KEY_SIZE, salt);
                    checkpoint(State.READ_HEADER_CHUNK);
                case READ_HEADER_CHUNK:
                    final byte[] chunkSizeBytes = readAsBytes(in, LENGTH_SIZE + GCM_TAG_SIZE);
                    final int l = decrypt(subkey, chunkSizeBytes, 0, chunkSizeBytes.length, chunkSizeBytes, 0);
                    assert l == LENGTH_SIZE;

                    chunkSize = (chunkSizeBytes[0] & 0xff) << 8 | chunkSizeBytes[1] & 0xff;
                    checkpoint(State.READ_PAYLOAD_CHUNK);
                case READ_PAYLOAD_CHUNK:
                    final byte[] payload = readAsBytes(in, chunkSize + GCM_TAG_SIZE);
                    final int len = decrypt(subkey, payload, 0, payload.length, payload, 0);
                    assert len == payload.length;

                    out.add(Unpooled.wrappedBuffer(payload, 0, len));
                    checkpoint(State.READ_HEADER_CHUNK);
                default:
            }
        }
    }

    private byte[] readAsBytes(final ByteBuf buf, final int len) {
        final byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return bytes;
    }

    private int decrypt(final byte[] subKey, final byte[] inBytes, int inOffset, int inLength, final byte[] outBytes, final int outOffset) throws Exception {
        try {
//            return ShadowsocksAeadEncoder.crypt(false, subKey, nonce, inBytes, inOffset, inLength, outBytes, outOffset);
            return jdkCrypt(Cipher.DECRYPT_MODE, subKey, nonce, inBytes, inOffset, inLength, outBytes, outOffset);
        } finally {
            ShadowsocksAeadEncoder.nextNone(nonce);
        }
    }


    protected int jdkCrypt(final int opmode, final byte[] secretKey, final byte[] nonce,
                        final byte[] bytes, int offset, int length,
                        final byte[] outBytes, final int outOffset) throws Exception {
        Cipher cipher = cipher(opmode, secretKey, nonce);
        return cipher.doFinal(bytes, offset, length, outBytes, outOffset);
    }


    static final String AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding";
    static final Provider BC = new BouncyCastleProvider();
    Cipher cipher(final int opmode, final byte[] secretKey, final byte[] nonce) throws Exception {
        final AlgorithmParameterSpec algorithmParameterSpec2 = new GCMParameterSpec(GCM_TAG_SIZE * Byte.SIZE, nonce);
        final Cipher cipher2 = Cipher.getInstance(AES_GCM_TRANSFORMATION, BC);
//        final Cipher cipher2 = Cipher.getInstance(AES_GCM_TRANSFORMATION);
        cipher2.init(opmode, new SecretKeySpec(secretKey, "AES"), algorithmParameterSpec2);
        return cipher2;
    }

    enum State {
        READ_SALT, READ_HEADER_CHUNK, READ_PAYLOAD_CHUNK
    }
}
