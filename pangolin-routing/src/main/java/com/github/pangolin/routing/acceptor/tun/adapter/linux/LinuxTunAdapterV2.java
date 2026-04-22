package com.github.pangolin.routing.acceptor.tun.adapter.linux;

import com.github.pangolin.routing.acceptor.tun.adapter.InterfaceAddressEx;
import com.github.pangolin.routing.acceptor.tun.adapter.linux.jna.If;
import com.github.pangolin.routing.acceptor.tun.adapter.linux.jna.IfTun;
import com.github.pangolin.routing.acceptor.tun.adapter.linux.jna.Socket;
import com.github.pangolin.routing.acceptor.tun.adapter.linux.jna.Sockios;
import com.github.pangolin.routing.acceptor.tun.adapter.unix.jna.LibC;
import com.github.pangolin.routing.acceptor.tun.adapter.ClosedByWakeupException;
import com.github.pangolin.routing.acceptor.tun.adapter.TunAdapterV2;
import com.github.pangolin.routing.acceptor.tun.adapter.linux.jna.LibCEpoll;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.sun.jna.*;
import lombok.extern.slf4j.Slf4j;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.sun.jna.platform.linux.Fcntl.O_RDWR;
import static java.nio.charset.StandardCharsets.US_ASCII;

/**
 * Linux TUN adapter v2：epoll + eventfd 支持 destroy 显式唤醒。
 *
 * <p>结构：
 * <ul>
 *   <li>{@code tunFd}：tun 设备 fd，open 后立即 {@code fcntl(F_SETFL, O_NONBLOCK)}；</li>
 *   <li>{@code wakeupFd}：{@code eventfd(0, EFD_NONBLOCK)}，用于 wakeup 信号；</li>
 *   <li>{@code epfd}：{@code epoll_create1(0)}，同时监听 tunFd / wakeupFd 的 EPOLLIN。</li>
 * </ul>
 *
 * <p>Open 流程复用 v1 {@link com.github.pangolin.routing.acceptor.tun.adapter.linux.LinuxTunAdapter}
 * 的底层建网（ioctl TUNSETIFF、SIOCSIFFLAGS、MTU、地址绑定）。
 */
@Slf4j
public class LinuxTunAdapterV2 extends TunAdapterV2 {

    private static final LibC LIBC = LibC.INSTANTCE;
    private static final LibCEpoll EPOLL = LibCEpoll.INSTANCE;

    /** 单次 epoll_wait 最多收 2 个事件（tun fd + wakeup fd）。 */
    private static final int MAX_EVENTS = 2;

    private final int fd;
    private final int wakeupFd;
    private final int epfd;
    private final String ifname;
    private final int mtu;

    /**
     * 复用的 events buffer：MAX_EVENTS * 12 字节。read0 为单线程调用，无并发冲突。
     */
    private final Memory eventsBuf;

    private LinuxTunAdapterV2(final int fd, final int wakeupFd, final int epfd,
                              final String ifname, final int mtu) {
        this.fd = fd;
        this.wakeupFd = wakeupFd;
        this.epfd = epfd;
        this.ifname = ifname;
        this.mtu = mtu;
        this.eventsBuf = new Memory((long) MAX_EVENTS * LibCEpoll.EPOLL_EVENT_SIZE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String name() {
        return ifname;
    }

    public int fd() {
        return fd;
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
        while (true) {
            final int n = EPOLL.epoll_wait(epfd, eventsBuf, MAX_EVENTS, -1);
            if (n < 0) {
                final int errno = Native.getLastError();
                if (errno == LibCEpoll.EINTR) {
                    continue;
                }
                throw ioException("epoll_wait() failed", errno);
            }

            for (int i = 0; i < n; i++) {
                final long data = eventsBuf.getLong(
                        (long) i * LibCEpoll.EPOLL_EVENT_SIZE + LibCEpoll.EPOLL_EVENT_DATA_OFFSET);
                if (data == wakeupFd) {
                    throw new ClosedByWakeupException();
                }
            }

            final ByteBuffer buf = ByteBuffer.allocateDirect(mtu);
            final int bytesRead = LIBC.read(fd, buf, mtu);
            if (bytesRead < 0) {
                final int errno = Native.getLastError();
                if (errno == LibCEpoll.EAGAIN || errno == LibCEpoll.EINTR) {
                    continue;
                }
                throw ioException("read() failed", errno);
            }
            if (bytesRead == 0) {
                throw new EOFException("TUN device reached EOF.");
            }
            buf.limit(bytesRead).position(0);

            final int ipVersion = buf.get(0) >> 4;
            log.trace("IPv{} packet read.", ipVersion);
            return buf;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void write0(final ByteBuffer[] packet) throws IOException {
        if (1 == packet.length) {
            final ByteBuffer buf = packet[0];
            final int expected = buf.remaining();
            final int written = LIBC.write(fd, buf, expected);
            checkWriteResult("write()", written, expected);
            buf.position(buf.position() + written);
        } else {
            final List<Memory> manualMemories = Lists.newArrayList();
            final LibC.Iovec[] iov = (LibC.Iovec[]) new LibC.Iovec().toArray(packet.length);
            int expected = 0;
            try {
                for (int i = 0; i < packet.length; i++) {
                    expected += write(iov, i, packet[i], manualMemories);
                }

                final NativeLong written = LIBC.writev(fd, iov, iov.length);
                checkWriteResult("writev()", written.longValue(), expected);

                long remaining = written.longValue();
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
    }

    @Override
    protected void wakeup0() throws IOException {
        // eventfd 写入：8-byte 小端 u64，值累加到内核计数器，触发 EPOLLIN
        final Memory one = new Memory(8);
        one.setLong(0, 1L);
        final int w = EPOLL.write(wakeupFd, one, 8L);
        if (w != 8) {
            final int errno = Native.getLastError();
            // EAGAIN 在 eventfd 计数器即将溢出（极难触发）；其余属于严重错误
            if (errno != LibCEpoll.EAGAIN) {
                throw ioException("wakeup write(eventfd) failed", errno);
            }
        }
    }

    /* ********************** */

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

    /**
     * {@inheritDoc}
     */
    @Override
    protected void destroy0() {
        if (0 <= epfd) {
            EPOLL.close(epfd);
        }
        if (0 <= wakeupFd) {
            EPOLL.close(wakeupFd);
        }
        if (0 <= fd) {
            LIBC.close(fd);
        }
    }

    /* ********************** */

    public static LinuxTunAdapterV2 open(final String tunName, final int mtu,
                                         final InterfaceAddressEx... bindings) throws Exception {
        final String ifnameToCreate = checkName(tunName);

        int fd = -1;
        int skfd = -1;
        int wakeupFd = -1;
        int epfd = -1;
        boolean success = false;

        try {
            // open tun device.
            fd = LIBC.open("/dev/net/tun", O_RDWR);
            if (fd < 0) {
                throw lastErr("open(/dev/net/tun)");
            }

            // configure/create actual tun device.
            final If.ifreq ifr = new If.ifreq(ifnameToCreate);
            ifr.ifr_ifru.setType("ifru_flags");
            ifr.ifr_ifru.ifru_flags = IfTun.IFF_TUN | IfTun.IFF_NO_PI;
            if (LIBC.ioctl(fd, IfTun.TUNSETIFF, ifr) < 0) {
                throw lastErr("ioctl(TUNSETIFF)");
            }

            final String ifnameToUse = Native.toString(ifr.ifr_name, StandardCharsets.US_ASCII);


            // O_NONBLOCK：read0 的 EAGAIN-retry 语义前置条件
            final int flags = EPOLL.fcntl(fd, LibCEpoll.F_GETFL, 0);
            if (flags < 0) {
                throw lastErr("fcntl(F_GETFL)");
            }
            if (EPOLL.fcntl(fd, LibCEpoll.F_SETFL, flags | LibCEpoll.O_NONBLOCK) < 0) {
                throw lastErr("fcntl(F_SETFL, O_NONBLOCK)");
            }

            skfd = LIBC.socket(Socket.AF_INET, Socket.SOCK_DGRAM, 0);
            if (skfd < 0) {
                throw lastErr("socket()");
            }

            // Set the tun device to active and ready to transfer packets.
            final If.ifreq ifr2 = new If.ifreq(ifnameToUse);
            ifr2.ifr_ifru.setType("ifru_flags");
            ifr2.ifr_ifru.ifru_flags = If.IFF_UP | If.IFF_RUNNING | If.IFF_POINTOPOINT | If.IFF_MULTICAST;
            if (LIBC.ioctl(skfd, Sockios.SIOCSIFFLAGS, ifr2) < 0) {
                throw lastErr("ioctl(SIOCSIFFLAGS)");
            }

            int mtuToUse = mtu;
            if (0 < mtuToUse) {
                LinuxNetworkInterface.setMTU(skfd, ifnameToUse, mtuToUse);
            } else {
                mtuToUse = LinuxNetworkInterface.getMTU(skfd, ifnameToUse);
            }

            final LinuxNetworkInterface nix = LinuxNetworkInterface.getByName(ifnameToUse);
            for (final InterfaceAddressEx binding : bindings) {
                nix.addInterfaceAddress(binding);
            }

            // 建 eventfd 作为 wakeup 源
            wakeupFd = EPOLL.eventfd(0, LibCEpoll.EFD_NONBLOCK);
            if (wakeupFd < 0) {
                throw lastErr("eventfd()");
            }

            // 建 epoll 并注册 tunFd、wakeupFd
            epfd = EPOLL.epoll_create1(0);
            if (epfd < 0) {
                throw lastErr("epoll_create1()");
            }

            try (Memory ev = new Memory(LibCEpoll.EPOLL_EVENT_SIZE)) {
                ev.setInt(LibCEpoll.EPOLL_EVENT_EVENTS_OFFSET, LibCEpoll.EPOLLIN);
                ev.setLong(LibCEpoll.EPOLL_EVENT_DATA_OFFSET, (long) fd);
                if (EPOLL.epoll_ctl(epfd, LibCEpoll.EPOLL_CTL_ADD, fd, ev) < 0) {
                    throw lastErr("epoll_ctl(ADD tunFd)");
                }

                ev.setInt(LibCEpoll.EPOLL_EVENT_EVENTS_OFFSET, LibCEpoll.EPOLLIN);
                ev.setLong(LibCEpoll.EPOLL_EVENT_DATA_OFFSET, (long) wakeupFd);
                if (EPOLL.epoll_ctl(epfd, LibCEpoll.EPOLL_CTL_ADD, wakeupFd, ev) < 0) {
                    throw lastErr("epoll_ctl(ADD wakeupFd)");
                }
            }

            success = true;
            return new LinuxTunAdapterV2(fd, wakeupFd, epfd, ifnameToUse, mtuToUse);
        } finally {
            if (skfd >= 0) {
                LIBC.close(skfd);
            }
            if (!success) {
                if (epfd >= 0) {
                    EPOLL.close(epfd);
                }
                if (wakeupFd >= 0) {
                    EPOLL.close(wakeupFd);
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

    private static String checkName(final String name) {
        if (null == name) {
            final Set<String> ifnames = getIfnames();
            for (int i = 0; i < 255; i++) {
                final String nameToUse = String.format("tun%s", i);
                if (!ifnames.contains(nameToUse)) {
                    return nameToUse;
                }
            }
            throw new IllegalStateException("Can't generate device name");
        }

        if (name.length() > If.IFNAMSIZ || !US_ASCII.newEncoder().canEncode(name)) {
            throw new IllegalArgumentException(String.format("Device name must be an ASCII string shorter than %s characters or null.", If.IFNAMSIZ));
        }
        return name;
    }

    private static Set<String> getIfnames() {
        final If.ifaddrs ifa = LinuxNetworkInterface.getifaddrs0(new If.ifaddrs());
        try {
            final Set<String> ifnames = new HashSet<>();
            for (If.ifaddrs n = ifa; null != n; n = n.ifa_next) {
                if (null != n.ifa_name) {
                    ifnames.add(n.ifa_name);
                }
            }
            return ifnames;
        } finally {
            // FIXED when Structure.autoRead=true if the pointer is invalid, it will cause JVM crash
            ifa.setAutoRead(false);
            LIBC.freeifaddrs(ifa);
        }
    }

}
