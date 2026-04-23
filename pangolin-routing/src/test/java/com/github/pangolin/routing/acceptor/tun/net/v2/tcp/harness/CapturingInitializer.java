package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpMultiplexer;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSock;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.hook.TcpSockHandler;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.hook.TcpSockInitializer;
import io.netty.buffer.ByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试用 {@link TcpSockInitializer} — 接纳所有 SYN(走默认 {@code sendSynAck}),
 * 在 onEstablished 时挂一个 {@link CapturingHandler},把所有回调事件录入列表供断言。
 *
 * <p>支持多连接并发;调用方通过 {@link #handler()} 取最近一条连接的 handler。
 * 多连接时使用 {@link #handlers()}。
 */
public final class CapturingInitializer implements TcpSockInitializer {

    private final List<CapturingHandler> handlers = new ArrayList<>();

    @Override
    public void onEstablished(TcpSock sock, TcpMultiplexer multiplexer) {
        CapturingHandler h = new CapturingHandler(sock, multiplexer);
        sock.handler(h);
        handlers.add(h);
    }

    /** 最近一条(最后被 onEstablished 的)连接的 handler。 */
    public CapturingHandler handler() {
        if (handlers.isEmpty()) return null;
        return handlers.get(handlers.size() - 1);
    }

    public List<CapturingHandler> handlers() {
        return handlers;
    }

    /**
     * 录制 5 个回调事件的 handler。payload 在 {@link #onInboundData} 内部复制一份,
     * 避免测试代码要关心 refcount。另外暴露 {@link #send} 方便测试从栈侧主动发数据。
     */
    public static final class CapturingHandler implements TcpSockHandler {
        private final TcpSock sock;
        private final TcpMultiplexer multiplexer;
        private final List<byte[]> inboundPayloads = new ArrayList<>();
        private int peerFinCount;
        private Throwable lastResetCause;
        private int writabilityChangedCount;
        private boolean destroyed;

        CapturingHandler(TcpSock sock, TcpMultiplexer multiplexer) {
            this.sock = sock;
            this.multiplexer = multiplexer;
        }

        public TcpSock sock() { return sock; }
        public TcpMultiplexer multiplexer() { return multiplexer; }
        public List<byte[]> inboundPayloads() { return inboundPayloads; }
        public int peerFinCount() { return peerFinCount; }
        public Throwable lastResetCause() { return lastResetCause; }
        public int writabilityChangedCount() { return writabilityChangedCount; }
        public boolean destroyed() { return destroyed; }

        /**
         * 从栈侧主动发一段 payload — 等价于应用层调 {@code ctx.writeAndFlush(buf)},
         * 但直接走 {@link TcpMultiplexer#sendmsg} 入口,跳过 {@code TcpChannel}。
         * 入参 bytes 被复制到一个新的 ByteBuf 里(所有权归 sendmsg 管理)。
         */
        public void send(byte[] payload) {
            io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.buffer(payload.length);
            buf.writeBytes(payload);
            multiplexer.sendmsg(sock, buf, true);
        }

        @Override
        public void onInboundData(ByteBuf data) {
            try {
                byte[] copy = new byte[data.readableBytes()];
                data.getBytes(data.readerIndex(), copy);
                inboundPayloads.add(copy);
            } finally {
                data.release();
            }
        }

        @Override
        public void onPeerFin() {
            peerFinCount++;
        }

        @Override
        public void onReset(Throwable cause) {
            lastResetCause = cause;
        }

        @Override
        public void onWritabilityChanged() {
            writabilityChangedCount++;
        }

        @Override
        public void onSocketDestroyed() {
            destroyed = true;
        }
    }
}
