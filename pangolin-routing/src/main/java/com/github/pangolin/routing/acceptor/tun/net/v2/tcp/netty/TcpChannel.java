package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.netty;

import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.FourTuple;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConnectionState;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpMultiplexer;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpOutput;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSendBuffer;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSock;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSockHandler;
import io.netty.buffer.ByteBuf;
import io.netty.channel.AbstractChannel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 把一条 v2 TCP {@link TcpSock} 连接暴露为 Netty {@link io.netty.channel.Channel}。
 *
 * <p>生命周期:
 * <ol>
 *   <li>{@link com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.Tcp4Multiplexer#tcp_v4_syn_recv_sock}
 *       建 child sock → {@code tcp_try_establish → tcp_init_transfer} 调
 *       {@link TcpChannelFactory#create} 生成本 channel;</li>
 *   <li>{@code TcpMultiplexer} 在 {@code sock.eventLoop()} 上调
 *       {@code eventLoop.register(ch)} → {@code pipeline.fireChannelActive()};</li>
 *   <li>读路径:{@code TcpMultiplexer.consume} 分流到
 *       {@link TcpSockHandler#onInboundData},经本类内部 autoRead 门控后
 *       {@code fireChannelRead};</li>
 *   <li>写路径:用户 {@code ctx.writeAndFlush(ByteBuf)} → {@link #doWrite} 按 MSS 分片
 *       落到 {@code TcpMultiplexer.enqueueWrite};</li>
 *   <li>关闭:主动 {@code ch.close()} → {@link #doClose} 发 FIN;被动 FIN / RST
 *       由 bridge 回调,最终由 {@code inet_csk_destroy_sock → sock.close} 触发
 *       bridge.onSocketDestroyed → 本 channel unsafe().closeForcibly。</li>
 * </ol>
 *
 * <p><b>EventLoop 硬约束</b>:本 channel 的 {@code eventLoop} 必须 == {@code sock.eventLoop()}
 * == TUN 入站 channel 的 EL,由 {@link #isCompatible} 守护。
 */
public class TcpChannel extends AbstractChannel {

    private static final ChannelMetadata METADATA = new ChannelMetadata(false);

    private final TcpSock sock;
    private final TcpMultiplexer multiplexer;
    private final TcpChannelConfig config;

    /** autoRead=false 时暂存待 fire 的 inbound buf。 */
    private final Deque<ByteBuf> pendingInbound = new ArrayDeque<>();

    /** 用户调了 {@code doBeginRead} 但尚未消费的待 fire 信号。 */
    private boolean readRequested;

    /** 半关状态。 */
    private boolean inputShutdown;
    private boolean outputShutdown;

    /** {@link #doClose} 幂等标志。 */
    private boolean closing;

    /** RST / onSocketDestroyed 触发的关闭 — 不发 FIN。 */
    private boolean abortive;

    /** writability 最近一次推上 pipeline 的 low/high 状态。 */
    private boolean lastWritable = true;

    public TcpChannel(TcpSock sock, TcpMultiplexer multiplexer) {
        super(null);
        this.sock = sock;
        this.multiplexer = multiplexer;
        this.config = new TcpChannelConfig(this);
        sock.handler(new BridgeImpl());
    }

    public TcpSock sock() {
        return sock;
    }

    public TcpMultiplexer multiplexer() {
        return multiplexer;
    }

    // ---- AbstractChannel template methods ----

    @Override
    protected AbstractUnsafe newUnsafe() {
        return new TcpChannelUnsafe();
    }

    @Override
    protected boolean isCompatible(EventLoop loop) {
        return loop == sock.eventLoop();
    }

    @Override
    protected SocketAddress localAddress0() {
        FourTuple t = sock.fourTuple();
        return new InetSocketAddress(t.dstInetAddress(), t.dstPort());
    }

    @Override
    protected SocketAddress remoteAddress0() {
        FourTuple t = sock.fourTuple();
        return new InetSocketAddress(t.srcInetAddress(), t.srcPort());
    }

    @Override
    protected void doRegister() {
        // no-op: EL 已由外部选定,sock.eventLoop() 即本 channel EL
    }

    @Override
    protected void doBind(SocketAddress localAddress) {
        throw new UnsupportedOperationException("TcpChannel is passive-open only");
    }

    @Override
    protected void doDisconnect() throws Exception {
        doClose();
    }

    @Override
    protected void doClose() throws Exception {
        if (closing) {
            return;
        }
        closing = true;

        // 主动关闭 → 发 FIN(对齐 v1 childCloseListener 的 tcp_close_state + tcp_send_fin);
        // abortive 关闭(RST / sock 已销毁)跳过 FIN,避免在已被对端 RST 的连接上再次写入。
        if (!abortive && sock.hasConnection() && sock.state().canSend()) {
            if (multiplexer.tcp_close_state(sock)) {
                TcpOutput.INSTANCE.tcp_send_fin(sock);
            }
        }

        // drain & release 暂存 inbound,避免 buffer leak
        drainPendingInboundReleasing();

        // 与 sock 解绑,让后续 inet_csk_destroy_sock 不再回调本 channel
        if (sock.handler() != null) {
            sock.handler(null);
        }
    }

    @Override
    protected void doBeginRead() throws Exception {
        // autoRead=true 时 pipeline 会在每次 fireChannelReadComplete 后自动调本方法;
        // 读路径由 bridge.onInboundData 主动推送,这里只在有积压时 drain。
        if (inputShutdown) {
            return;
        }
        if (pendingInbound.isEmpty()) {
            readRequested = true;
            // 接收缓冲仍有数据则放开 rcvPaused,由下一次 tcp_data_queue 驱动 consume
            if (sock.rcvPaused() && sock.receiveBuffer() != null && sock.receiveBuffer().isReadable()) {
                sock.rcvPaused(false);
            }
            return;
        }
        readRequested = false;
        drainPendingInboundFiring();
        pipeline().fireChannelReadComplete();
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        if (outputShutdown || !sock.hasConnection() || !sock.state().canSend()) {
            // 连接已不可写 — 让 in.remove 走 failure 路径以释放缓冲
            for (;;) {
                Object msg = in.current();
                if (msg == null) break;
                in.remove(new ClosedChannelException());
            }
            return;
        }

        for (;;) {
            Object msg = in.current();
            if (msg == null) {
                break;
            }
            if (!(msg instanceof ByteBuf)) {
                in.remove(new UnsupportedOperationException(
                        "TcpChannel only accepts ByteBuf, got " + msg.getClass().getName()));
                continue;
            }
            ByteBuf buf = (ByteBuf) msg;
            int remaining = buf.readableBytes();
            if (remaining == 0) {
                in.remove();
                continue;
            }

            final int mss = Math.max(1, TcpOutput.INSTANCE.tcp_current_mss(sock));
            int consumed = 0;
            while (consumed < remaining) {
                final int len = Math.min(remaining - consumed, mss);
                final boolean lastSlice   = (consumed + len == remaining);
                final boolean lastMsg     = in.size() == 1;
                final boolean flush       = lastSlice && lastMsg;
                final ByteBuf slice = buf.retainedSlice(buf.readerIndex() + consumed, len);
                multiplexer.enqueueWrite(sock, slice, flush);
                consumed += len;
            }
            in.remove(); // release 原 buf(retainedSlice 各自独立引用)
        }

        // 每次 doWrite 后检查水位
        evaluateWritability();
    }

    // ---- Channel contract ----

    @Override
    public ChannelConfig config() {
        return config;
    }

    @Override
    public boolean isOpen() {
        return !closing;
    }

    @Override
    public boolean isActive() {
        if (closing || !isRegistered()) {
            return false;
        }
        TcpConnectionState st = sock.state();
        if (st == null) return false;
        // 对齐 Linux TCPF_ESTABLISHED|FIN_WAIT1|FIN_WAIT2|CLOSE_WAIT|CLOSING|LAST_ACK
        switch (st) {
            case TCP_ESTABLISHED:
            case FIN_WAIT_1:
            case FIN_WAIT_2:
            case CLOSE_WAIT:
            case CLOSING:
            case LAST_ACK:
                return sock.hasConnection();
            default:
                return false;
        }
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    // ---- bridge-facing helpers (package-private) ----

    void fireChannelReadFromTcp(ByteBuf data) {
        assert eventLoop().inEventLoop();
        if (closing || inputShutdown) {
            data.release();
            return;
        }
        if (!config.isAutoRead() && !readRequested) {
            pendingInbound.addLast(data);
            // 背压:停止 drain receiveBuffer,让对端感知窗口收缩
            sock.rcvPaused(true);
            return;
        }
        readRequested = false;
        pipeline().fireChannelRead(data);
        pipeline().fireChannelReadComplete();
    }

    private void drainPendingInboundFiring() {
        while (!pendingInbound.isEmpty()) {
            ByteBuf buf = pendingInbound.pollFirst();
            if (closing || inputShutdown) {
                buf.release();
            } else {
                pipeline().fireChannelRead(buf);
            }
        }
    }

    private void drainPendingInboundReleasing() {
        while (!pendingInbound.isEmpty()) {
            pendingInbound.pollFirst().release();
        }
    }

    private void evaluateWritability() {
        TcpSendBuffer sb = sock.sendBuffer();
        if (sb == null) {
            return;
        }
        long pending = sb.pendingBytes();
        int high = config.getWriteBufferHighWaterMark();
        int low  = config.getWriteBufferLowWaterMark();
        ChannelOutboundBuffer cob = unsafe().outboundBuffer();
        if (cob == null) {
            return;
        }
        if (lastWritable && pending >= high) {
            cob.setUserDefinedWritability(1, false);
            lastWritable = false;
        } else if (!lastWritable && pending <= low) {
            cob.setUserDefinedWritability(1, true);
            lastWritable = true;
        }
    }

    // ---- inner types ----

    private final class TcpChannelUnsafe extends AbstractUnsafe {
        @Override
        public void connect(SocketAddress remote, SocketAddress local, ChannelPromise promise) {
            promise.setFailure(new UnsupportedOperationException(
                    "TcpChannel is passive-open; peer already connected via SYN handshake"));
        }
    }

    /**
     * {@link TcpSockHandler} 内联实现 — 把 core 层的协议栈事件转成 Netty pipeline 事件。
     * 所有方法均假定已在 {@code sock.eventLoop()} 上。
     */
    private final class BridgeImpl implements TcpSockHandler {
        @Override
        public void onInboundData(ByteBuf data) {
            fireChannelReadFromTcp(data);
        }

        @Override
        public void onPeerFin() {
            if (inputShutdown || closing) {
                return;
            }
            inputShutdown = true;
            // 对齐 Netty ChannelInputShutdownEvent(NioSocketChannel 对端 FIN 时触发)
            pipeline().fireUserEventTriggered(
                    io.netty.channel.socket.ChannelInputShutdownEvent.INSTANCE);
        }

        @Override
        public void onReset(Throwable cause) {
            if (closing) {
                return;
            }
            abortive = true;
            pipeline().fireExceptionCaught(
                    cause != null ? cause : new SocketException("Connection reset by peer"));
            unsafe().closeForcibly();
        }

        @Override
        public void onWritabilityChanged() {
            evaluateWritability();
        }

        @Override
        public void onSocketDestroyed() {
            if (!closing) {
                // sock 已被销毁 — 不要再走 doClose 的 FIN 分支,直接 closeForcibly
                abortive = true;
                drainPendingInboundReleasing();
                unsafe().closeForcibly();
            }
        }
    }
}
