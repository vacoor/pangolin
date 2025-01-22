package com.github.pangolin.tun.channel;

import com.github.pangolin.tun.net.TunAdapter;
import com.github.pangolin.tun.net.darwin.DarwinTunAdapter;
import com.github.pangolin.tun.net.linux.LinuxTunAdapter;
import com.github.pangolin.tun.net.windows.WindowsTunAdapter;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
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

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.AlreadyConnectedException;
import java.util.ArrayList;
import java.util.List;

public class TunChannel extends AbstractChannel {
    private static final ChannelMetadata METADATA = new ChannelMetadata(false);
    private static final String EXPECTED_TYPES =
            " (expected: " + StringUtil.simpleClassName(ByteBuf.class) + ')';
    final Runnable readTask = this::doRead;
    private final TunChannelConfig config = new DefaultTunChannelConfig(this);
    private final List<Object> readBuf = new ArrayList<>();
    private boolean readPending;
    private final EventLoop readLoop = new DefaultEventLoop();
    private TunAdapter device;
    private boolean closed;

    public TunChannel() {
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
        if (device != null) {
            // FIXME
            // return device.localAddress();
            return null;
        }
        else {
            return null;
        }
    }

    @Override
    protected SocketAddress remoteAddress0() {
        return null;
    }

    @Override
    protected void doBind(final SocketAddress localAddress) throws Exception {
        if (PlatformDependent.isOsx()) {
            device = DarwinTunAdapter.open(((TunAddress) localAddress).ifName());
        }
        else if (PlatformDependent.isWindows()) {
            device = WindowsTunAdapter.open(((TunAddress) localAddress).ifName(), "X");
        }
        else {
            device = LinuxTunAdapter.open(((TunAddress) localAddress).ifName());
        }
    }

    @Override
    protected void doDisconnect() throws Exception {
        // do nothing
    }

    @Override
    protected void doClose() throws Exception {
        if (!closed) {
            closed = true;
            if (device != null) {
                device.close();
            }
        }
    }

    /**
     * Read messages into the given array and return the amount which was read.
     */
    @SuppressWarnings("java:S112")
    protected int doReadMessages(List<Object> msgs) throws Exception {
        byte[] bytes = device.readPacket();
        ByteBuf byteBuf = Unpooled.wrappedBuffer(bytes);
        msgs.add(byteBuf);
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
                byte[] bytes = ByteBufUtil.getBytes((ByteBuf) msg);
                device.writePacket(bytes);
            }
            finally {
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

    @SuppressWarnings({ "java:S135", "java:S1117", "java:S1181", "java:S1874", "java:S3776" })
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
        }
        catch (final Throwable t) {
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
        }
        else if (readPending || config.isAutoRead() || !readData && isActive()) {
            read();
        }
    }

    @Override
    protected AbstractUnsafe newUnsafe() {
        return new TunChannelUnsafe();
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

    public TunAdapter device() {
        return device;
    }

    private class TunChannelUnsafe extends AbstractUnsafe {
        @Override
        public void connect(final SocketAddress remoteAddress,
                            final SocketAddress localAddress,
                            final ChannelPromise promise) {
            throw new AlreadyConnectedException();
        }
    }
}