package com.github.pangolin.routing.server.tun.adapter.linux;


import com.github.pangolin.routing.server.tun.adapter.AbstractTunAdapter;
import com.github.pangolin.routing.server.tun.adapter.InterfaceAddressEx;
import com.github.pangolin.routing.server.tun.adapter.unix.jna.LibC;
import com.sun.jna.LastErrorException;
import com.sun.jna.Native;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static com.github.pangolin.routing.server.tun.adapter.linux.LinuxUtils.throwLastErrorException;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.If.*;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.IfTun.*;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.Socket.AF_INET;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.Socket.SOCK_DGRAM;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.Sockios.SIOCSIFFLAGS;
import static com.github.pangolin.routing.server.tun.adapter.unix.jna.LibC.*;
import static com.sun.jna.platform.linux.Fcntl.O_RDWR;
import static java.nio.charset.StandardCharsets.US_ASCII;

@Slf4j
public class LinuxTunAdapter extends AbstractTunAdapter<LinuxNetworkInterfaceEx> {
    private final int fd;
    private final String ifname;
    private final int mtu;

    private LinuxTunAdapter(final int fd, final String ifname, final int mtu) {
        super(new LinuxNetworkInterfaceEx(ifname));
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
        final int bytesRead = LibC.read(fd, buf, mtu);
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
        LibC.write(fd, bytes, bytes.length);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void destroy0() {
        close(fd);
    }

    /* ********************** */

    public static LinuxTunAdapter open(final String tunName, final int mtu,
                                       final InterfaceAddressEx... bindings) throws Exception {
        final String ifnameToCreate = checkName(tunName);

        // open tun device.
        final int fd = LibC.open("/dev/net/tun", O_RDWR);
        if (fd < 0) {
            throwLastErrorException(Native.getLastError());
        }

        // configure/create actual tun device.
        final ifreq ifr = new ifreq(ifnameToCreate);
        ifr.ifr_ifru.setType("ifru_flags");
        ifr.ifr_ifru.ifru_flags = IFF_TUN | IFF_NO_PI;
        if (ioctl(fd, TUNSETIFF, ifr) < 0) {
            throwLastErrorException(Native.getLastError());
        }

        final String ifnameToUse = Native.toString(ifr.ifr_name, StandardCharsets.US_ASCII);

        final int skfd = socket(AF_INET, SOCK_DGRAM, 0);
        if (skfd < 0) {
            throwLastErrorException(Native.getLastError());
        }

        // Set the tun device to active and ready to transfer packets.
        final ifreq ifr2 = new ifreq(ifnameToUse);
        ifr2.ifr_ifru.setType("ifru_flags");
        ifr2.ifr_ifru.ifru_flags = IFF_UP | IFF_RUNNING | IFF_POINTOPOINT | IFF_MULTICAST;
        if (ioctl(skfd, SIOCSIFFLAGS, ifr2) < 0) {
            throw new LastErrorException(Native.getLastError());
        }

        int mtuToUse = mtu;
        if (0 < mtuToUse) {
            LinuxNetworkInterfaceEx.setMTU(skfd, ifnameToUse, mtuToUse);
        } else {
            mtuToUse = LinuxNetworkInterfaceEx.getMTU(skfd, ifnameToUse);
        }

        close(skfd);

        LinuxTunAdapter ap = new LinuxTunAdapter(fd, ifnameToUse, mtuToUse);
        for (InterfaceAddressEx binding : bindings) {
            ap.addInterfaceAddress(binding);
        }

        return ap;
    }

    private static String checkName(final String name) {
        if (name.length() > IFNAMSIZ || !US_ASCII.newEncoder().canEncode(name)) {
            throw new IllegalArgumentException(String.format("Device name must be an ASCII string shorter than %s characters or null.", IFNAMSIZ));
        }
        return name;
    }

    private static Set<String> getIfnames() {
        final ifaddrs ifa = LinuxNetworkInterfaceEx.getifaddrs0(new ifaddrs());
        try {
            final Set<String> ifnames = new HashSet<>();
            for (ifaddrs n = ifa; null != n; n = n.ifa_next) {
                if (null != n.ifa_name) {
                    ifnames.add(n.ifa_name);
                }
            }
            return ifnames;
        } finally {
            // FIXED when Structure.autoRead=true if the pointer is invalid, it will cause JVM crash
            ifa.setAutoRead(false);
            freeifaddrs(ifa);
        }
    }

}