package com.github.pangolin.routing.server.tun.adapter.linux;


import com.github.pangolin.routing.server.tun.adapter.AbstractTunAdapter;
import com.github.pangolin.routing.server.tun.adapter.unix.jna.LibC;
import com.sun.jna.LastErrorException;
import com.sun.jna.Native;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static com.github.pangolin.routing.server.tun.adapter.linux.jna.If.*;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.IfTun.*;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.Socket.AF_INET;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.Socket.SOCK_DGRAM;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.Sockios.SIOCSIFFLAGS;
import static com.github.pangolin.routing.server.tun.adapter.unix.jna.LibC.*;
import static com.sun.jna.platform.linux.Fcntl.O_RDWR;

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
        final byte[] buf = new byte[mtu];
        final int bytesRead = LibC.read(fd, buf, mtu);

        final int ipVersion = buf[0] >> 4;
        log.trace("IPv{} packet read.", ipVersion);

        return ByteBuffer.wrap(buf, 0, bytesRead);
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

    public static LinuxTunAdapter open(final String tunName, final int mtu) throws Exception {
        final String nameToUse = checkName(tunName);

        // open tun device.
        final int fd = LibC.open("/dev/net/tun", O_RDWR);
        if (-1 == fd) {
            throw new LastErrorException(Native.getLastError());
        }

        // configure/create actual tun device.
        final ifreq tunReq = new ifreq(nameToUse);
        tunReq.ifr_ifru.setType("ifru_flags");
        tunReq.ifr_ifru.ifru_flags = IFF_TUN | IFF_NO_PI;
        if (0 != ioctl(fd, TUNSETIFF, tunReq)) {
            throw new LastErrorException(Native.getLastError());
        }

        final String ifname = Native.toString(tunReq.ifr_name, StandardCharsets.US_ASCII);
        final int skfd = socket(AF_INET, SOCK_DGRAM, 0);
        if (-1 == skfd) {
            throw new LastErrorException(Native.getLastError());
        }

        int mtuToUse = mtu;
        if (0 < mtuToUse) {
            LinuxNetworkInterfaceEx.setMTU(skfd, ifname, mtuToUse);
        } else {
            mtuToUse = LinuxNetworkInterfaceEx.getMTU(skfd, ifname);
        }

        // Set the tun device to active and ready to transfer packets.
        final int flags = IFF_POINTOPOINT | IFF_MULTICAST;
        final ifreq ifr2 = new ifreq(nameToUse);
        ifr2.ifr_ifru.setType("ifru_flags");
        ifr2.ifr_ifru.ifru_flags = IFF_UP | IFF_RUNNING | flags;
        if (0 != ioctl(skfd, SIOCSIFFLAGS, ifr2)) {
            throw new LastErrorException(Native.getLastError());
        }

        close(skfd);

        return new LinuxTunAdapter(skfd, ifname, mtuToUse);
    }

    private static String checkName(final String name) {
        if (name.length() > IFNAMSIZ || !StandardCharsets.US_ASCII.newEncoder().canEncode(name)) {
            throw new IllegalArgumentException(String.format("Device name must be an ASCII string shorter than %s characters or null.", IFNAMSIZ));
        }
        return name;
    }

}