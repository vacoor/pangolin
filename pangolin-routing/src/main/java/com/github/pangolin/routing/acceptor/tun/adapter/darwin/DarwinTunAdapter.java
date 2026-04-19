package com.github.pangolin.routing.acceptor.tun.adapter.darwin;

import com.github.pangolin.routing.acceptor.tun.adapter.InterfaceAddressEx;
import com.github.pangolin.routing.acceptor.tun.adapter.TunAdapter;
import com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.IfUtun;
import com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.KernControl;
import com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.Socket;
import com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.SysDomain;
import com.github.pangolin.routing.acceptor.tun.adapter.unix.jna.LibC;
import com.github.pangolin.routing.acceptor.tun.adapter.util.NetUtils2;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.sun.jna.*;
import com.sun.jna.ptr.IntByReference;
import lombok.extern.slf4j.Slf4j;

import java.io.EOFException;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;

import static java.nio.charset.StandardCharsets.US_ASCII;

@Slf4j
public class DarwinTunAdapter extends TunAdapter {

    private static final LibC LIBC = LibC.INSTANTCE;

    /**
     * Address family bytes.
     */
    private static final int AF_BYTES = 4;

    /**
     * utun interface name prefix.
     */
    private static final String UTUN_NAME_PREFIX = "utun";
    private static final String ILLEGAL_NAME_EXCEPTION = "Device name must be 'utun<index>' or null.";

    /**
     * The utun file descriptor.
     */
    private final int fd;

    /**
     * The utun interface name.
     */
    private final String ifname;

    /**
     * The utun maximum transmission unit.
     */
    private final int mtu;

    private DarwinTunAdapter(final int fd, final String ifname, final int mtu) {
        this.fd = fd;
        this.ifname = ifname;
        this.mtu = mtu;
    }

    public int fd() {
        return fd;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String name() {
        return ifname;
    }

    public int getMTU() {
        return mtu;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected ByteBuffer read0() throws IOException {
        // read from socket
        final int mtu = getMTU();
        final int packetSize = mtu + AF_BYTES;
        final ByteBuffer buf = ByteBuffer.allocateDirect(packetSize);
        final int bytesRead = LIBC.read(fd, buf, packetSize);
        if (bytesRead < 0) {
            throw ioException("read() failed", Native.getLastError());
        }
        if (bytesRead <= AF_BYTES) {
            throw new EOFException("TUN device returned incomplete packet.");
        }
        buf.limit(bytesRead).position(0);

        // 4-bytes address family
        final int addressFamily = buf.getInt(0);
        if (Socket.AF_INET != addressFamily && Socket.AF_INET6 != addressFamily) {
            throw new IOException("Unknown address family: " + addressFamily);
        }

        final int ipVersion = buf.get(AF_BYTES) >> 4;
        log.trace("IPv{} packet read.", ipVersion);

        // skip 4-bytes address family
        return buf.position(AF_BYTES).slice();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void write0(final ByteBuffer[] packet) throws IOException {
        final int ipVersion = packet[0].get(packet[0].position()) >> 4;
        final int addressFamily = addressFamily(ipVersion);
        final ByteBuffer addressFamilyBuf = ByteBuffer.allocateDirect(AF_BYTES);
        addressFamilyBuf.put(new byte[]{
                (byte) (addressFamily >> 24),
                (byte) (addressFamily >> 16),
                (byte) (addressFamily >> 8),
                (byte) addressFamily
        });
        addressFamilyBuf.flip();

        final List<Memory> manualMemories = Lists.newArrayList();
        final LibC.Iovec[] iov = (LibC.Iovec[]) new LibC.Iovec().toArray(1 + packet.length);
        int expected = 0;
        try {
            expected += write(iov, 0, addressFamilyBuf, manualMemories);
            for (int i = 0; i < packet.length; i++) {
                expected += write(iov, i + 1, packet[i], manualMemories);
            }

            final NativeLong written = LIBC.writev(fd, iov, iov.length);
            checkWriteResult("writev()", written.longValue(), expected);

            // AF 头不计入调用方 buffer，从 payload 部分开始推进 position
            long remaining = written.longValue() - AF_BYTES;
            for (final ByteBuffer buf : packet) {
                if (remaining <= 0) {
                    break;
                }
                final int take = (int) Math.min(remaining, buf.remaining());
                buf.position(buf.position() + take);
                remaining -= take;
            }
        } finally {
            closeMemories(manualMemories);
        }
    }

    private void closeMemories(final List<Memory> memories) {
        for (final Memory memory : memories) {
            if (memory != null) {
                memory.close();
            }
        }
    }

    private int write(final LibC.Iovec[] iov, final int offset, final ByteBuffer buf, final List<Memory> memories) {
        // 只准备 iovec，不动 position；真正的消费（推进 position）由外层 writev 成功后统一完成
        final int len = buf.remaining();
        final Pointer ptr;
        if (buf.isDirect()) {
            ptr = Native.getDirectBufferPointer(buf).share(buf.position());
        } else {
            log.warn("Non-direct -> Direct memory.");
            final Memory memory = new Memory(len);
            memories.add(memory);
            if (buf.hasArray()) {
                memory.write(0, buf.array(), buf.arrayOffset() + buf.position(), len);
            } else {
                final byte[] tmp = new byte[len];
                buf.duplicate().get(tmp);
                memory.write(0, tmp, 0, len);
            }
            ptr = memory;
        }
        iov[offset].iov_base = ptr;
        iov[offset].iov_len = new NativeLong(len);
        return len;
    }

    private int addressFamily(final int ipVersion) throws IOException {
        int addressFamily = Socket.AF_UNSPEC;
        if (4 == ipVersion) {
            addressFamily = Socket.AF_INET;
        } else if (6 == ipVersion) {
            addressFamily = Socket.AF_INET6;
        } else {
            throw new IOException("Unknown address family: " + addressFamily);
        }
        return addressFamily;
    }

    private void write0(final ByteBuffer packet) throws IOException {
        final int ipVersion = packet.get(packet.position()) >> 4;
        final int addressFamily = addressFamily(ipVersion);

        final int payloadLen = packet.remaining();
        final ByteBuffer buf = ByteBuffer.allocateDirect(AF_BYTES + payloadLen);
        buf.put(new byte[]{
                (byte) (addressFamily >> 24),
                (byte) (addressFamily >> 16),
                (byte) (addressFamily >> 8),
                (byte) addressFamily
        });
        // 用局部副本把 payload 拷入 direct buf，避免在 write 成功前污染 packet 的 position
        buf.put(packet.duplicate());
        buf.flip();

        final int expected = buf.remaining();
        final int written = LIBC.write(fd, buf, expected);
        checkWriteResult("write()", written, expected);
        packet.position(packet.position() + payloadLen);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void destroy0() {
        LIBC.close(fd);
    }

    /* ********************** */

    /**
     * Creates a utun interface and bind to ifname and interface addresses.
     *
     * @param ifname   the utun interface name, it will be automatically assigned if null.
     * @param mtu      the utun maximum transmission unit.
     * @param bindings the interface addresses to bind
     * @return the utun interface
     */
    public static DarwinTunAdapter open(final String ifname, final int mtu,
                                        final InterfaceAddressEx... bindings) {
        final int scUnit = nameToUnit(ifname);
        int fd = -1;
        boolean success = false;
        String ifnameToUse = null;
        int mtuToUse = mtu;

        try {
            fd = LIBC.socket(Socket.AF_SYSTEM, Socket.SOCK_DGRAM, SysDomain.SYSPROTO_CONTROL);
            if (fd < 0) {
                DarwinUtils.throwLastErrorException(Native.getLastError());
            }

            final KernControl.ctl_info ctlInfo = new KernControl.ctl_info(IfUtun.UTUN_CONTROL_NAME);
            if (LIBC.ioctl(fd, KernControl.CTLIOCGINFO, ctlInfo) < 0) {
                DarwinUtils.throwLastErrorException(Native.getLastError());
            }

            final KernControl.sockaddr_ctl address = new KernControl.sockaddr_ctl((byte) Socket.AF_SYSTEM, (short) SysDomain.SYSPROTO_CONTROL, ctlInfo.ctl_id, scUnit);
            if (LIBC.connect(fd, address, address.sc_len) < 0) {
                DarwinUtils.throwLastErrorException(Native.getLastError());
            }

            final SockName sockName = new SockName();
            final IntByReference sockNameLen = new IntByReference(SockName.LENGTH);
            if (LIBC.getsockopt(fd, SysDomain.SYSPROTO_CONTROL, IfUtun.UTUN_OPT_IFNAME, sockName, sockNameLen) < 0) {
                DarwinUtils.throwLastErrorException(Native.getLastError());
            }

            ifnameToUse = Native.toString(sockName.name, US_ASCII);

            if (0 < mtuToUse) {
                DarwinNetworkInterface.setMTU(fd, ifnameToUse, mtuToUse);
            } else {
                mtuToUse = DarwinNetworkInterface.getMTU(fd, ifnameToUse);
            }

            final DarwinNetworkInterface nix = DarwinNetworkInterface.getByName(ifnameToUse);
            final Set<InetAddress> processes = Sets.newHashSet();
            for (final InterfaceAddressEx binding : bindings) {
                nix.addInterfaceAddress(binding);

                final InetAddress gw = binding.getAddress();
                final int prefix = binding.getNetworkPrefixLength();
                final InetAddress dst = NetUtils2.getNetworkAddress(gw, prefix);
                if (processes.add(dst) && dst instanceof Inet4Address) {
                    DarwinNetworkRoutingTable.add0(dst, prefix, gw, ifnameToUse);
                }
            }
            success = true;
            return new DarwinTunAdapter(fd, ifnameToUse, mtuToUse);
        } finally {
            if (!success && fd >= 0) {
                LIBC.close(fd);
            }
        }
    }

    private IOException ioException(final String operation, final int errno) {
        return new IOException(String.format("%s: [%s] %s", operation, errno, LIBC.strerror(errno)));
    }

    private void checkWriteResult(final String operation, final long written, final long expected) throws IOException {
        if (written < 0) {
            throw ioException(operation + " failed", Native.getLastError());
        }
        Preconditions.checkState(expected >= 0, "Expected packet length must be non-negative");
        if (written != expected) {
            throw new IOException(String.format("%s short write: expected=%s actual=%s", operation, expected, written));
        }
    }

    private static int nameToUnit(final String ifname) {
        final int index;
        if (ifname != null) {
            if (ifname.startsWith(UTUN_NAME_PREFIX)) {
                try {
                    index = Integer.parseInt(ifname.substring(UTUN_NAME_PREFIX.length()));
                } catch (final NumberFormatException e) {
                    throw new IllegalArgumentException(ILLEGAL_NAME_EXCEPTION);
                }
            } else {
                throw new IllegalArgumentException(ILLEGAL_NAME_EXCEPTION);
            }
            // utun unit = index + 1
            return index + 1;
        } else {
            // automatically assigned
            return 0;
        }
    }

    @SuppressWarnings({"java:S116", "java:S1104", "java:S2160"})
    @Structure.FieldOrder({"name"})
    public static class SockName extends Structure {
        static final int LENGTH = 16;
        public byte[] name = new byte[LENGTH];
    }

}
