package com.github.pangolin.routing.server.tun.net.channel;

import com.github.pangolin.routing.server.tun.adapter.AbstractTunAdapter;
import com.github.pangolin.routing.server.tun.adapter.InterfaceAddressEx;
import com.github.pangolin.routing.server.tun.adapter.TunAdapter;
import com.github.pangolin.routing.server.tun.adapter.darwin.DarwinNetworkRoute;
import com.github.pangolin.routing.server.tun.adapter.darwin.DarwinTunAdapter;
import com.github.pangolin.routing.server.tun.adapter.linux.LinuxTunAdapter;
import com.github.pangolin.routing.server.tun.adapter.windows.WindowsTunAdapter;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.util.NetUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.StringUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AlreadyConnectedException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
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
        } else {
            return null;
        }
    }

    @Override
    protected SocketAddress remoteAddress0() {
        return null;
    }

    @Override
    protected void doBind(final SocketAddress localAddress) throws Exception {
        final int mtu = config.getMtu();
        final String ifname = ((TunAddress) localAddress).ifName();
        if (PlatformDependent.isOsx()) {
            device = DarwinTunAdapter.open(ifname, mtu);
        } else if (PlatformDependent.isWindows()) {
            device = WindowsTunAdapter.open(ifname, "Proxies Host-Only", "{2B54EB73-2CF2-4C1A-B900-E193C9E16966}", mtu);
        } else {
            device = LinuxTunAdapter.open(ifname, mtu);
        }
        log.info("Open tun adapter: {}", device);
        InterfaceAddressEx of = InterfaceAddressEx.of("198.18.0.1", 24);
        ((AbstractTunAdapter) device).setInterfaceAddress(of);
        if (PlatformDependent.isOsx()) {
            int subnetMask = prefixToSubnetMask(of.getNetworkPrefixLength());
            int networkAddress = ipAddressToInt((Inet4Address) of.getAddress()) & subnetMask;

            Inet4Address dst = (Inet4Address) InetAddress.getByName(NetUtil.intToIpAddress(networkAddress));
            Inet4Address netmask = (Inet4Address) InetAddress.getByName(NetUtil.intToIpAddress(subnetMask));
            Inet4Address gw = (Inet4Address) of.getAddress();
            /*-
             * MacOS 不会添加默认网关路由.
             * sudo route add -net 198.18.0.0/24 198.18.0.1
             */
            DarwinNetworkRoute.add(dst, netmask, gw, ifname);
        }
    }

    private int prefixToSubnetMask(final int cidrPrefix) {
        /*-
         * Perform the shift on a long and downcast it to int afterwards.
         * This is necessary to handle a cidrPrefix of zero correctly.
         * The left shift operator on an int only uses the five least
         * significant bits of the right-hand operand. Thus -1 << 32 evaluates
         * to -1 instead of 0. The left shift operator applied on a long
         * uses the six least significant bits.
         *
         * Also see https://github.com/netty/netty/issues/2767
         */
        return (int) ((-1L << 32 - cidrPrefix) & 0xffffffff);
    }

    private static int ipAddressToInt(final Inet4Address ipAddress) {
        final byte[] ipBytes = ipAddress.getAddress();
        assert ipBytes.length == 4;
        return (ipBytes[0] & 0xff) << 24 | (ipBytes[1] & 0xff) << 16 | (ipBytes[2] & 0xff) << 8 | ipBytes[3] & 0xff;
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
                device.destroy();
            }
        }
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
                device.write(((ByteBuf) msg).nioBuffer());
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