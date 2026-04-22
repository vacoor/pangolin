package com.github.pangolin.routing.acceptor.tun.adapter.darwin;

import com.github.pangolin.routing.acceptor.tun.adapter.InterfaceAddressEx;
import com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.IfUtun;
import com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.KernControl;
import com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.Socket;
import com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.SysDomain;
import com.github.pangolin.routing.acceptor.tun.adapter.unix.jna.LibC;
import com.github.pangolin.routing.acceptor.tun.adapter.util.NetUtils2;
import com.github.pangolin.routing.acceptor.tun.adapter.ClosedByWakeupException;
import com.github.pangolin.routing.acceptor.tun.adapter.TunAdapterV2;
import com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.LibCKqueue;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.sun.jna.LastErrorException;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
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

/**
 * Darwin TUN adapter v2：kqueue + EVFILT_USER 支持 destroy 显式唤醒。
 *
 * <p>结构：
 * <ul>
 *   <li>{@code fd}：utun 设备 fd，open 后 {@code fcntl(F_SETFL, O_NONBLOCK)}；</li>
 *   <li>{@code kq}：{@code kqueue()}，同时注册 {@code EVFILT_READ(tunFd)} 与
 *       {@code EVFILT_USER(WAKEUP_IDENT)}；</li>
 *   <li>{@code wakeup0()}：一次 {@code kevent} 发 {@code NOTE_TRIGGER}，EVFILT_USER 即就绪。</li>
 * </ul>
 *
 * <p>Open 流程复用 v1 底层建网路径（utun fd 创建 / ctl_info / sockaddr_ctl / ifname /
 * MTU / route），仅在 v2 额外做非阻塞和多路复用注册。
 */
@Slf4j
public class DarwinTunAdapterV2 extends TunAdapterV2 {

    private static final LibC LIBC = LibC.INSTANTCE;
    private static final LibCKqueue KQ = LibCKqueue.INSTANCE;

    /** EVFILT_USER 的 ident；任意常数即可，0 号有时有歧义，这里用 1。 */
    private static final long WAKEUP_IDENT = 1L;

    /** 单次 kevent 最多收 2 个事件（tun + user-wakeup）。 */
    private static final int MAX_EVENTS = 2;

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
    private final int kq;

    /**
     * The utun interface name.
     */
    private final String ifname;

    /**
     * The utun maximum transmission unit.
     */
    private final int mtu;
    private final Memory eventsBuf;

    private DarwinTunAdapterV2(final int fd, final int kq,
                               final String ifname, final int mtu) {
        this.fd = fd;
        this.kq = kq;
        this.ifname = ifname;
        this.mtu = mtu;
        this.eventsBuf = new Memory((long) MAX_EVENTS * LibCKqueue.KEVENT_SIZE);
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

        while (true) {
            final int n = KQ.kevent(kq, null, 0, eventsBuf, MAX_EVENTS, null);
            if (n < 0) {
                final int errno = Native.getLastError();
                if (errno == LibCKqueue.EINTR) {
                    continue;
                }
                throw ioException("kevent() failed", errno);
            }

            for (int i = 0; i < n; i++) {
                final long base = (long) i * LibCKqueue.KEVENT_SIZE;
                final short filter = eventsBuf.getShort(base + LibCKqueue.KEVENT_FILTER_OFFSET);
                final long ident = eventsBuf.getLong(base + LibCKqueue.KEVENT_IDENT_OFFSET);
                if (filter == LibCKqueue.EVFILT_USER && ident == WAKEUP_IDENT) {
                    throw new ClosedByWakeupException();
                }
            }

            final ByteBuffer buf = ByteBuffer.allocateDirect(packetSize);
            final int bytesRead = LIBC.read(fd, buf, packetSize);
            if (bytesRead < 0) {
                final int errno = Native.getLastError();
                if (errno == LibCKqueue.EAGAIN || errno == LibCKqueue.EINTR) {
                    continue;
                }
                throw ioException("read() failed", errno);
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

    @Override
    protected void wakeup0() throws IOException {
        try (Memory change = new Memory(LibCKqueue.KEVENT_SIZE)) {
            change.clear();
            change.setLong(LibCKqueue.KEVENT_IDENT_OFFSET, WAKEUP_IDENT);
            change.setShort(LibCKqueue.KEVENT_FILTER_OFFSET, LibCKqueue.EVFILT_USER);
            change.setShort(LibCKqueue.KEVENT_FLAGS_OFFSET, (short) 0);
            change.setInt(LibCKqueue.KEVENT_FFLAGS_OFFSET, LibCKqueue.NOTE_TRIGGER);
            change.setLong(LibCKqueue.KEVENT_DATA_OFFSET, 0L);
            change.setLong(LibCKqueue.KEVENT_UDATA_OFFSET, 0L);

            if (KQ.kevent(kq, change, 1, null, 0, null) < 0) {
                throw ioException("kevent(trigger wakeup) failed", Native.getLastError());
            }
        }
    }

    @Override
    protected void destroy0() {
        if (0 <= kq) {
            KQ.close(kq);
        }
        if (0 <= fd) {
            LIBC.close(fd);
        }
    }

    /* ********************** */


    private int addressFamily(final int ipVersion) throws IOException {
        int addressFamily = Socket.AF_UNSPEC;
        if (4 == ipVersion) {
            addressFamily = Socket.AF_INET;
        } else if (6 == ipVersion) {
            addressFamily = Socket.AF_INET6;
        } else {
            throw new IOException("Unknown ip version: " + ipVersion);
        }
        return addressFamily;
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
    public static DarwinTunAdapterV2 open(final String ifname, final int mtu,
                                          final InterfaceAddressEx... bindings) throws IOException {
        final int scUnit = nameToUnit(ifname);
        int fd = -1;
        int kq = -1;
        boolean success = false;
        String ifnameToUse = null;
        int mtuToUse = mtu;

        try {
            fd = LIBC.socket(Socket.AF_SYSTEM, Socket.SOCK_DGRAM, SysDomain.SYSPROTO_CONTROL);
            if (fd < 0) {
                throw lastErr("socket(AF_SYSTEM)");
            }

            final KernControl.ctl_info ctlInfo = new KernControl.ctl_info(IfUtun.UTUN_CONTROL_NAME);
            if (LIBC.ioctl(fd, KernControl.CTLIOCGINFO, ctlInfo) < 0) {
                throw lastErr("ioctl(CTLIOCGINFO)");
            }

            final KernControl.sockaddr_ctl address = new KernControl.sockaddr_ctl((byte) Socket.AF_SYSTEM, (short) SysDomain.SYSPROTO_CONTROL, ctlInfo.ctl_id, scUnit);
            if (LIBC.connect(fd, address, address.sc_len) < 0) {
                throw lastErr("connect(utun)");
            }

            final SockName sockName = new SockName();
            final IntByReference sockNameLen = new IntByReference(SockName.LENGTH);
            if (LIBC.getsockopt(fd, SysDomain.SYSPROTO_CONTROL, IfUtun.UTUN_OPT_IFNAME, sockName, sockNameLen) < 0) {
                throw lastErr("getsockopt(UTUN_OPT_IFNAME)");
            }

            ifnameToUse = Native.toString(sockName.name, US_ASCII);

            // O_NONBLOCK
            final int flags = KQ.fcntl(fd, LibCKqueue.F_GETFL, 0);
            if (flags < 0) {
                throw lastErr("fcntl(F_GETFL)");
            }
            if (KQ.fcntl(fd, LibCKqueue.F_SETFL, flags | LibCKqueue.O_NONBLOCK) < 0) {
                throw lastErr("fcntl(F_SETFL, O_NONBLOCK)");
            }

            /*-
             * OPT-8: ifconfig 式 ioctl（SIOCSIFMTU/SIOCGIFMTU）语义上应走独立的
             * AF_INET DGRAM socket，而非复用 utun 的 SYSPROTO_CONTROL fd；
             * 后者在当前 macOS 能工作，但属于非标准用法，后续版本可能被收紧。
             */
            if (0 < mtuToUse) {
                DarwinNetworkInterface.setMTU0(ifnameToUse, mtuToUse);
            } else {
                mtuToUse = DarwinNetworkInterface.getMTU0(ifnameToUse);
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

            // 建 kqueue 并注册 EVFILT_READ(tunFd) + EVFILT_USER(WAKEUP_IDENT)
            kq = KQ.kqueue();
            if (kq < 0) {
                throw lastErr("kqueue()");
            }

            try (Memory changes = new Memory(2L * LibCKqueue.KEVENT_SIZE)) {
                changes.clear();
                // slot 0: EVFILT_READ on tunFd
                changes.setLong(LibCKqueue.KEVENT_IDENT_OFFSET, fd);
                changes.setShort(LibCKqueue.KEVENT_FILTER_OFFSET, LibCKqueue.EVFILT_READ);
                changes.setShort(LibCKqueue.KEVENT_FLAGS_OFFSET,
                        (short) (LibCKqueue.EV_ADD | LibCKqueue.EV_CLEAR));
                // slot 1: EVFILT_USER on WAKEUP_IDENT
                final long base1 = LibCKqueue.KEVENT_SIZE;
                changes.setLong(base1 + LibCKqueue.KEVENT_IDENT_OFFSET, WAKEUP_IDENT);
                changes.setShort(base1 + LibCKqueue.KEVENT_FILTER_OFFSET, LibCKqueue.EVFILT_USER);
                changes.setShort(base1 + LibCKqueue.KEVENT_FLAGS_OFFSET,
                        (short) (LibCKqueue.EV_ADD | LibCKqueue.EV_CLEAR));

                if (KQ.kevent(kq, changes, 2, null, 0, null) < 0) {
                    throw lastErr("kevent(register tunFd + user-wakeup)");
                }
            }

            success = true;
            return new DarwinTunAdapterV2(fd, kq, ifnameToUse, mtuToUse);
        } finally {
            if (!success) {
                if (kq >= 0) {
                    KQ.close(kq);
                }
                if (fd >= 0) {
                    LIBC.close(fd);
                }
            }
        }
    }

    private static LastErrorException lastErr(final String op) {
        final int errno = Native.getLastError();
        return new LastErrorException(String.format("%s failed: [%s] %s", op, errno, LIBC.strerror(errno)));
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
                if (index < 0 || index >= Short.MAX_VALUE) {
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
