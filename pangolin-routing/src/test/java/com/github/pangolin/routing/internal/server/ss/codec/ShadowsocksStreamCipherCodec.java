package com.github.pangolin.routing.internal.server.ss.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;
import org.bouncycastle.crypto.CipherParameters;
import org.bouncycastle.crypto.StreamCipher;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.CFBBlockCipher;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;

import javax.crypto.SecretKey;
import java.security.SecureRandom;
import java.util.List;

public class ShadowsocksStreamCipherCodec extends ByteToMessageCodec<ByteBuf> {
    private final SecretKey secretKey;
    private final int ivSize;
    private final byte[] ivBytes;
    private StreamCipher encrypt2;
    private StreamCipher decrypt2;


    public ShadowsocksStreamCipherCodec(SecretKey secretKey, int ivSize, SecureRandom random) {
        this.secretKey = secretKey;
        this.ivSize = ivSize;
        this.ivBytes = nextBytes(random, new byte[ivSize]);
    }

    @Override
    protected void encode(final ChannelHandlerContext ctx, ByteBuf in, ByteBuf out) throws Exception {
        if (!in.isReadable()) {
            return;
        }

        if (null == encrypt2) {
            encrypt2 = instantiateCipher(true, new ParametersWithIV(new KeyParameter(secretKey.getEncoded()), ivBytes));
            out.writeBytes(ivBytes);
        }

        final byte[] bytes = new byte[in.readableBytes()];
        in.readBytes(bytes);
        byte[] update = new byte[bytes.length];
        final int len = encrypt2.processBytes(bytes, 0, bytes.length, update, 0);
        out.writeBytes(update, 0, len);
    }

    @Override
    protected void decode(ChannelHandlerContext channelHandlerContext, ByteBuf in, List<Object> out) throws Exception {
        if (null == decrypt2) {
            final byte[] deIvBytes = new byte[ivSize];
            in.readBytes(deIvBytes);
            decrypt2 = instantiateCipher(false, new ParametersWithIV(new KeyParameter(secretKey.getEncoded()), deIvBytes));
        }
        final byte[] bytes = new byte[in.readableBytes()];
        in.readBytes(bytes);
        final byte[] plain = new byte[bytes.length];
        int i = decrypt2.processBytes(bytes, 0, bytes.length, plain, 0);
        out.add(Unpooled.wrappedBuffer(plain, 0, i));
    }

    private byte[] nextBytes(final SecureRandom random, byte[] bytes) {
        random.nextBytes(bytes);
        return bytes;
    }

    /**
     * {@link javax.crypto.Cipher} is block ciphers.
     * If you were encrypting the data between the client and server with a block cipher,
     * you’d have to wait until the client typed enough characters to fill a block
     * @param enc
     * @param parameters
     * @return
     */
    private StreamCipher instantiateCipher(final boolean enc, final CipherParameters parameters) {
        CFBBlockCipher cipher = new CFBBlockCipher(new AESEngine(), 128);
        cipher.init(enc, parameters);
        return cipher;
    }
}
