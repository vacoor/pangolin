package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness;

import com.github.pangolin.routing.acceptor.tun.net.codec.IpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.codec.IpPacketCodec;
import com.github.pangolin.routing.acceptor.tun.net.codec.Tcp4PacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.TcpMultiplexHandler;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.hook.TcpSockInitializer;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;

/**
 * v2 TCP 栈的 E2E 测试台。用 {@link EmbeddedChannel} 包 pipeline
 * ({@code IpPacketCodec → TcpMultiplexHandler}),喂入 raw IP 字节 → 读出 raw IP 字节。
 *
 * <p>使用方式:
 * <pre>
 * TcpStackHarness h = new TcpStackHarness(TcpSockInitializer.DENY);
 * h.sendInbound(PacketFactory.syn(CLIENT, 1111, SERVER, 80, 1000));
 * Tcp4PacketBuf rsp = h.readOutboundTcp();
 * assertThat(rsp.isRst()).isTrue();
 * </pre>
 *
 * <p>地址常量 {@link #CLIENT_IP} / {@link #SERVER_IP} 仅为"习惯选"的测试地址,
 * 栈本身不关心地址值(没有路由表);任意 IPv4 地址都行。
 */
public final class TcpStackHarness implements AutoCloseable {

    public static final byte[] CLIENT_IP = {10, 0, 0, 1};
    public static final byte[] SERVER_IP = {10, 0, 0, 2};
    public static final int CLIENT_PORT_DEFAULT = 12345;
    public static final int SERVER_PORT_DEFAULT = 80;

    private final EmbeddedChannel channel;
    private final TcpMultiplexHandler handler;

    public TcpStackHarness(TcpSockInitializer initializer) {
        this.handler = new TcpMultiplexHandler(FakeDnsEngine.INSTANCE, initializer);
        this.channel = new EmbeddedChannel(new IpPacketCodec(), this.handler);
    }

    /**
     * 喂入一个入站 raw IP 包。释放权转给 pipeline(IpPacketCodec 内部 retainedSlice)。
     */
    public void sendInbound(ByteBuf rawIp) {
        channel.writeInbound(rawIp);
    }

    /**
     * 读下一个出站 IP 包并 wrap 成 {@link Tcp4PacketBuf};没有则返回 {@code null}。
     * 调用方负责 release 返回值(通过 {@link Tcp4PacketBuf#release()})。
     *
     * @throws AssertionError 若出站的不是 IPv4+TCP 包(说明栈行为异常)
     */
    public Tcp4PacketBuf readOutboundTcp() {
        Object msg = channel.readOutbound();
        if (msg == null) return null;
        if (!(msg instanceof ByteBuf)) {
            throw new AssertionError("expected ByteBuf outbound, got " + msg.getClass());
        }
        IpPacketBuf ip = IpPacketBuf.wrap((ByteBuf) msg);
        if (!(ip instanceof Tcp4PacketBuf)) {
            ip.release();
            throw new AssertionError("expected Tcp4PacketBuf, got " + ip.getClass());
        }
        return (Tcp4PacketBuf) ip;
    }

    /** 统计当前出站队列中未被 {@link #readOutboundTcp} 取走的包数。 */
    public int outboundSize() {
        return channel.outboundMessages().size();
    }

    public EmbeddedChannel channel() {
        return channel;
    }

    public TcpMultiplexHandler handler() {
        return handler;
    }

    @Override
    public void close() {
        channel.finishAndReleaseAll();
    }
}
