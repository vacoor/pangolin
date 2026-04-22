package com.github.pangolin.routing.acceptor.tun.net.channel;

import com.github.pangolin.routing.acceptor.tun.adapter.InterfaceAddressEx;
import com.github.pangolin.routing.acceptor.tun.adapter.ClosedByWakeupException;
import com.github.pangolin.routing.acceptor.tun.adapter.TunAdapterV2;
import com.github.pangolin.routing.acceptor.tun.adapter.darwin.DarwinTunAdapterV2;
import com.github.pangolin.routing.acceptor.tun.adapter.linux.LinuxTunAdapterV2;
import com.github.pangolin.routing.acceptor.tun.adapter.windows.WindowsTunAdapterV2;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.AbstractChannel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultEventLoop;
import io.netty.channel.EventLoop;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.StringUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AlreadyConnectedException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * TunChannel v2：基于 {@link TunAdapterV2}，支持显式 {@code wakeup()} 的优雅关闭。
 *
 * <h3>关闭流程</h3>
 * <ol>
 *   <li>外部 {@code channel.close()} → netty 在 eventLoop 调 {@link #doClose()};</li>
 *   <li>{@code device.wakeup()} 唤醒 {@code readLoop} 里阻塞在 {@code device.read()} 的线程；</li>
 *   <li>{@code readLoop.shutdownGracefully().sync()} 等 {@link #doRead()} 返回；</li>
 *   <li>{@code device.close()} 释放底层 fd / handle。</li>
 * </ol>
 *
 * <p>{@link #doRead()} 捕获 {@link ClosedByWakeupException} 后直接 return，不
 * {@code fireExceptionCaught}、也不反向触发 {@code unsafe.close} —— 此时 {@code doClose}
 * 已经在调用链上负责资源释放。
 */
@Slf4j
public class TunChannelV2 extends AbstractChannel {
    private static final ChannelMetadata METADATA = new ChannelMetadata(false);
    private static final String EXPECTED_TYPES =
            " (expected: " + StringUtil.simpleClassName(ByteBuf.class) + ')';

    /** readLoop 优雅关闭 quiet period / timeout —— 超过则强停，防止 doClose 无限阻塞 eventLoop。 */
    private static final long READ_LOOP_SHUTDOWN_QUIET_MS = 0L;
    private static final long READ_LOOP_SHUTDOWN_TIMEOUT_MS = 1000L;

    final Runnable readTask = this::doRead;
    private final TunChannelConfig config = new DefaultTunChannelConfig(this);
    private final List<Object> readBuf = new ArrayList<>();
    private boolean readPending;
    private final EventLoop readLoop = new DefaultEventLoop();
    private TunAdapterV2 device;
    private boolean closed;

    public TunChannelV2() {
        super(null);
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    @Override
    public TunChannelConfig config() {
        return config;
    }

    @Override
    public boolean isOpen() {
        return !closed;
    }

    @Override
    public boolean isActive() {
        return !closed && device != null;
    }

    @Override
    protected SocketAddress localAddress0() {
        // FIXME
        return null;
    }

    @Override
    protected SocketAddress remoteAddress0() {
        return null;
    }

    @Override
    protected void doBind(final SocketAddress localAddress) throws Exception {
        final int mtu = config.getMtu();
        final TunAddress ta = (TunAddress) localAddress;
        final String ifname = ta.ifname();
        final InterfaceAddressEx[] bindings = ta.getInterfaceAddresses();

        if (PlatformDependent.isOsx()) {
            device = DarwinTunAdapterV2.open(ifname, mtu, bindings);
        } else if (PlatformDependent.isWindows()) {
            final String wintunType = config.getOption(TunChannelOption.WINTUN_TYPE);
            final String wintunUuid = config.getOption(TunChannelOption.WINTUN_UUID);
            final String wintunTypeToUse = null != wintunType ? wintunType : ifname;
            device = WindowsTunAdapterV2.open(ifname, wintunTypeToUse, wintunUuid, mtu, bindings);
        } else {
            device = LinuxTunAdapterV2.open(ifname, mtu, bindings);
        }
        log.info("TUN adapter v2 initialized: {}", device.name());
    }

    @Override
    protected void doDisconnect() throws Exception {
        // do nothing
    }

    @Override
    protected void doClose() throws Exception {
        if (closed) {
            return;
        }
        closed = true;
        if (device == null) {
            return;
        }

        try {
            device.wakeup();
        } catch (final IOException e) {
            log.warn("wakeup tun failed: {}", e.getMessage());
        }

        readLoop.shutdownGracefully(
                READ_LOOP_SHUTDOWN_QUIET_MS,
                READ_LOOP_SHUTDOWN_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
        ).syncUninterruptibly();

        device.close();
    }

    /**
     * Read messages into the given array and return the amount which was read.
     */
    @SuppressWarnings("java:S112")
    protected int doReadMessages(List<Object> msgs) throws Exception {
        final ByteBuffer packet = device.read();
        msgs.add(Unpooled.wrappedBuffer(packet));
        return 1;
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        while (true) {
            final Object msg = in.current();
            if (msg == null) {
                break;
            }

            try {
                device.write(((ByteBuf) msg).nioBuffers());
            } finally {
                in.remove();
            }
        }
    }

    @Override
    protected Object filterOutboundMessage(Object msg) {
        if (msg instanceof ByteBuf) {
            return msg;
        }

        throw new UnsupportedOperationException(
                "unsupported message type: " + StringUtil.simpleClassName(msg) + EXPECTED_TYPES);
    }

    @SuppressWarnings({"java:S135", "java:S1117", "java:S1181", "java:S1874", "java:S3776"})
    private void doRead() {
        if (!readPending) {
            return;
        }
        readPending = false;

        final ChannelConfig config = config();
        final ChannelPipeline pipeline = pipeline();
        final RecvByteBufAllocator.Handle allocHandle = unsafe().recvBufAllocHandle();
        allocHandle.reset(config);

        // read messages until RecvByteBuf is full
        boolean closedByWakeup = false;
        boolean closed = false;
        Throwable exception = null;
        try {
            do {
                int localRead = doReadMessages(readBuf);
                if (localRead == 0) {
                    break;
                }
                if (localRead < 0) {
                    closed = true;
                    break;
                }

                allocHandle.incMessagesRead(localRead);
            } while (allocHandle.continueReading());
        } catch (final ClosedByWakeupException e) {
            // 被 wakeup 唤醒的正常退出路径：doClose 已在调用链上负责资源释放，
            // 这里不 fireExceptionCaught，也不回调 unsafe.close（避免重入 doClose）。
            closedByWakeup = true;
        } catch (final Throwable t) {
            exception = t;
        }

        // process read messages
        boolean readData = false;
        int size = readBuf.size();
        if (size > 0) {
            readData = true;
            for (int i = 0; i < size; i++) {
                readPending = false;
                pipeline.fireChannelRead(readBuf.get(i));
            }
            readBuf.clear();
            allocHandle.readComplete();
            pipeline.fireChannelReadComplete();
        }

        if (closedByWakeup) {
            return;
        }

        if (exception != null) {
            if (exception instanceof IOException) {
                closed = true;
            }

            if (isOpen()) {
                pipeline.fireExceptionCaught(exception);
            }
        }

        if (closed) {
            if (isOpen()) {
                unsafe().close(unsafe().voidPromise());
            }
        } else if (readPending || config.isAutoRead() || !readData && isActive()) {
            read();
        }
    }

    @Override
    protected AbstractUnsafe newUnsafe() {
        return new TunChannelV2Unsafe();
    }

    @Override
    protected boolean isCompatible(final EventLoop loop) {
        return loop instanceof DefaultEventLoop;
    }

    @Override
    protected void doBeginRead() {
        if (readPending) {
            return;
        }
        if (!isActive()) {
            return;
        }

        readPending = true;
        readLoop.execute(readTask);
    }

    public TunAdapterV2 device() {
        return device;
    }

    private class TunChannelV2Unsafe extends AbstractUnsafe {
        @Override
        public void connect(final SocketAddress remoteAddress,
                            final SocketAddress localAddress,
                            final ChannelPromise promise) {
            throw new AlreadyConnectedException();
        }
    }
}
