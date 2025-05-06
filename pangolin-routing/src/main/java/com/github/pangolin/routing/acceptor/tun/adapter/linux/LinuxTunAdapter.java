package com.github.pangolin.routing.acceptor.tun.adapter.linux;


import com.github.pangolin.routing.acceptor.tun.adapter.InterfaceAddressEx;
import com.github.pangolin.routing.acceptor.tun.adapter.linux.jna.If;
import com.github.pangolin.routing.acceptor.tun.adapter.linux.jna.IfTun;
import com.github.pangolin.routing.acceptor.tun.adapter.linux.jna.Socket;
import com.github.pangolin.routing.acceptor.tun.adapter.linux.jna.Sockios;
import com.github.pangolin.routing.acceptor.tun.adapter.unix.jna.LibC;
import com.github.pangolin.routing.acceptor.tun.adapter.TunAdapter;
import com.sun.jna.LastErrorException;
import com.sun.jna.Native;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static com.github.pangolin.routing.acceptor.tun.adapter.linux.LinuxUtils.throwLastErrorException;
import static com.sun.jna.platform.linux.Fcntl.O_RDWR;
import static java.nio.charset.StandardCharsets.US_ASCII;

@Slf4j
public class LinuxTunAdapter extends TunAdapter {

    private static final LibC LIBC = LibC.INSTANTCE;

    private final int fd;
    private final String ifname;
    private final int mtu;

    private LinuxTunAdapter(final int fd, final String ifname, final int mtu) {
        this.fd = fd;
        this.ifname = ifname;
        this.mtu = mtu;
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
    protected ByteBuffer read0() {
        // read from socket
        final int mtu = getMTU();

        final ByteBuffer buf = ByteBuffer.allocateDirect(mtu);
        final int bytesRead = LIBC.read(fd, buf, mtu);
        if (-1 == bytesRead) {
            throw new IllegalStateException("fd closed");
        }

        final int ipVersion = buf.get(0) >> 4;
        log.trace("IPv{} packet read.", ipVersion);

        return buf;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void write0(final ByteBuffer packet) {
        final byte[] bytes = new byte[packet.remaining()];
        packet.get(bytes).clear();
        LIBC.write(fd, bytes, bytes.length);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void destroy0() {
        LIBC.close(fd);
    }

    /* ********************** */

    public static LinuxTunAdapter open(final String tunName, final int mtu,
                                       final InterfaceAddressEx... bindings) throws Exception {
        final String ifnameToCreate = checkName(tunName);

        // open tun device.
        final int fd = LIBC.open("/dev/net/tun", O_RDWR);
        if (fd < 0) {
            LinuxUtils.throwLastErrorException(Native.getLastError());
        }

        // configure/create actual tun device.
        final If.ifreq ifr = new If.ifreq(ifnameToCreate);
        ifr.ifr_ifru.setType("ifru_flags");
        ifr.ifr_ifru.ifru_flags = IfTun.IFF_TUN | IfTun.IFF_NO_PI;
        if (LIBC.ioctl(fd, IfTun.TUNSETIFF, ifr) < 0) {
            LinuxUtils.throwLastErrorException(Native.getLastError());
        }

        final String ifnameToUse = Native.toString(ifr.ifr_name, StandardCharsets.US_ASCII);

        final int skfd = LIBC.socket(Socket.AF_INET, Socket.SOCK_DGRAM, 0);
        if (skfd < 0) {
            LinuxUtils.throwLastErrorException(Native.getLastError());
        }

        // Set the tun device to active and ready to transfer packets.
        final If.ifreq ifr2 = new If.ifreq(ifnameToUse);
        ifr2.ifr_ifru.setType("ifru_flags");
        ifr2.ifr_ifru.ifru_flags = If.IFF_UP | If.IFF_RUNNING | If.IFF_POINTOPOINT | If.IFF_MULTICAST;
        if (LIBC.ioctl(skfd, Sockios.SIOCSIFFLAGS, ifr2) < 0) {
            throw new LastErrorException(Native.getLastError());
        }

        int mtuToUse = mtu;
        if (0 < mtuToUse) {
            LinuxNetworkInterface.setMTU(skfd, ifnameToUse, mtuToUse);
        } else {
            mtuToUse = LinuxNetworkInterface.getMTU(skfd, ifnameToUse);
        }
        LIBC.close(skfd);

        final LinuxNetworkInterface nix = LinuxNetworkInterface.getByName(ifnameToUse);
        for (final InterfaceAddressEx binding : bindings) {
            nix.addInterfaceAddress(binding);
        }
        return new LinuxTunAdapter(fd, ifnameToUse, mtuToUse);
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