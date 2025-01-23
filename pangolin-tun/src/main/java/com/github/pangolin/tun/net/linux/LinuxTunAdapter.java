package com.github.pangolin.tun.net.linux;


import com.github.pangolin.tun.net.AbstractTunAdapter;
import com.github.pangolin.tun.net.linux.jna.LibC;
import com.sun.jna.Native;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static com.github.pangolin.tun.net.linux.jna.If.*;
import static com.github.pangolin.tun.net.linux.jna.IfTun.*;
import static com.github.pangolin.tun.net.linux.jna.LibC.*;
import static com.github.pangolin.tun.net.linux.jna.Socket.AF_INET;
import static com.github.pangolin.tun.net.linux.jna.Socket.SOCK_DGRAM;
import static com.github.pangolin.tun.net.linux.jna.Sockios.SIOCSIFFLAGS;
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
        if (fd == -1) {
            throw new IOException("Create an endpoint for communication failed.");
        }

        // configure/create actual tun device.
        final Ifreq ifr = new Ifreq(nameToUse);
        ifr.ifr_ifru.setType("ifru_flags");
        ifr.ifr_ifru.ifru_flags = IFF_TUN | IFF_NO_PI;
        ioctl(fd, TUNSETIFF, ifr);

        final String ifname = Native.toString(ifr.ifr_name, StandardCharsets.US_ASCII);
        final int skfd = socket(AF_INET, SOCK_DGRAM, 0);

        int mtuToUse = mtu;
        if (0 < mtuToUse) {
            LinuxNetworkInterfaceEx.setMtu(skfd, ifname, mtuToUse);
        } else {
            mtuToUse = LinuxNetworkInterfaceEx.getMtu(skfd, ifname);
        }

        // Set the tun device to active and ready to transfer packets.
        final int flags = IFF_POINTOPOINT | IFF_MULTICAST;
        final Ifreq ifr2 = new Ifreq(nameToUse);
        ifr2.ifr_ifru.setType("ifru_flags");
        ifr2.ifr_ifru.ifru_flags = IFF_UP | IFF_RUNNING | flags;
        ioctl(skfd, SIOCSIFFLAGS, ifr2);

        close(skfd);

        return new LinuxTunAdapter(fd, ifname, mtuToUse);
    }

    private static String checkName(final String name) {
        if (name.length() > IFNAMSIZ || !StandardCharsets.US_ASCII.newEncoder().canEncode(name)) {
            throw new IllegalArgumentException(String.format("Device name must be an ASCII string shorter than %s characters or null.", IFNAMSIZ));
        }
        return name;
    }

}