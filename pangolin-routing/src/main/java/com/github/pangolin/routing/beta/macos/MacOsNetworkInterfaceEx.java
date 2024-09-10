package com.github.pangolin.routing.beta.macos;


import com.github.pangolin.routing.beta.InterfaceAddressEx;
import com.github.pangolin.routing.beta.NetworkInterfaceEx;
import com.github.pangolin.routing.beta.macos.If.*;
import io.netty.util.NetUtil;

import java.net.*;
import java.util.Collections;
import java.util.List;

import static com.github.pangolin.routing.beta.linux.Socket.AF_INET;
import static com.github.pangolin.routing.beta.linux.Socket.SOCK_DGRAM;
import static com.github.pangolin.routing.beta.macos.Sockio.*;
import static org.drasyl.channel.tun.jna.shared.LibC.*;
import static org.drasyl.channel.tun.jna.shared.Socket.AF_INET6;

/**
 *
 */
public class MacOsNetworkInterfaceEx implements NetworkInterfaceEx {
    private final String ifname;

    public MacOsNetworkInterfaceEx(final String ifname) {
        this.ifname = ifname;
    }

    @Override
    public List<InterfaceAddressEx> getInterfaceAddresses() {
        final int fd = fd4();
        try {
            final InetAddress ipAddress = toInetAddress(getInterfaceIpAddress(fd, ifname));
            final byte[] interfaceNetmask = getInterfaceNetmask(fd, ifname);
            final int prefix = subnetMaskToPrefix(ipAddressToInt(interfaceNetmask));
            return Collections.singletonList(InterfaceAddressEx.of(ipAddress, prefix));
        } finally {
            close(fd);
        }
    }

    private static InetAddress toInetAddress(final byte[] sinAddr) {
        try {
            return InetAddress.getByAddress(sinAddr);
        } catch (final UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void setInterfaceAddress(final InterfaceAddressEx address) {
        final InetAddress addr = address.getAddress();
        final int networkPrefixLength = address.getNetworkPrefixLength();
        if (!(addr instanceof Inet4Address)) {
            throw new UnsupportedOperationException();
        }
        final int fd = fd4();
        try {
            setInterfaceIpAddress(fd, ifname, (Inet4Address) addr);
            byte[] bytes = subnetMaskToAddress(prefixToSubnetMask(networkPrefixLength));

            System.out.println(NetUtil.bytesToIpAddress(bytes));

            setInterfaceNetmask(fd, ifname, (Inet4Address) toInetAddress(bytes));
        } finally {
            close(fd);
        }
    }

    private static int prefixToSubnetMask(final int cidrPrefix) {
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

    private static int subnetMaskToPrefix(final int subnetMask) {
        int i = 0;
        int mask = subnetMask;
        for (i = 0; i < 32; i++) {
            if ((mask & 1) != 1) {
                mask >>= 1;
            } else {
                break;
            }
        }
        return 32 - i;
    }

    private static byte[] subnetMaskToAddress(final int subnetMask) {
        return new byte[]{
                (byte) ((subnetMask >> 24) & 0xff),
                (byte) ((subnetMask >> 16) & 0xff),
                (byte) ((subnetMask >> 8) & 0xff),
                (byte) ((subnetMask >> 0) & 0xff)
        };
    }

    private static int ipAddressToInt(final byte[] ipBytes) {
        assert ipBytes.length == 4;
        return (ipBytes[0] & 0xff) << 24 | (ipBytes[1] & 0xff) << 16 | (ipBytes[2] & 0xff) << 8 | ipBytes[3] & 0xff;
    }

    @Override
    public void flushInterfaceAddresses() {

    }

    @Override
    public int getMTU() throws SocketException {
        final int fd = fd4();
        try {
            return getMtu(fd, ifname);
        } finally {
            close(fd);
        }
    }

    private static int fd4() {
        return socket(AF_INET, SOCK_DGRAM, 0);
    }

    private static int fd6() {
        return socket(AF_INET6, SOCK_DGRAM, 0);
    }


    private static byte[] getInterfaceNetmask(final int fd, final String ifname) {
        final Ifreq ifr = new Ifreq(ifname);
        ifr.ifr_ifru.setType("ifru_addr");

        ioctl(fd, SIOCGIFNETMASK, ifr);

        final sockaddr netmask = ifr.ifr_ifru.ifru_addr;
        assert AF_INET == netmask.sin_family;
        return netmask.sin_addr;
    }

    private static void setInterfaceNetmask(final int fd, final String ifname, final Inet4Address addr) {
        /*-
         Wrong:
         -> Wrong 1: address: 1.2.3.4 netmask: 255.255.255.0
         -> Wrong 2: address: 10.18.71.2 netmask: 255.255.255.0
         -> result: netmask 0xff0000
         --------------------------
         Correct: address: 192.168.1.1 netmask: 255.255.255.0
         -> result: netmask 0xffffff00
         */
        final Ifreq ifr = new Ifreq(ifname);
        ifr.ifr_ifru.setType("ifru_addr");

        ifr.ifr_ifru.ifru_addr.sin_family = AF_INET;
        ifr.ifr_ifru.ifru_addr.sin_port = 0;
        ifr.ifr_ifru.ifru_addr.sin_addr = addr.getAddress();

        int code = ioctl(fd, SIOCSIFNETMASK, ifr);
        System.out.println("CODE=" + code);
    }

    private static int getMtu(final int fd, final String ifname) {
        final Ifreq ifr = new Ifreq(ifname);
        final int code = ioctl(fd, SIOCGIFMTU, ifr);
        return ifr.ifr_ifru.ifru_mtu;
    }

    private static void setMtu(final int fd, final String ifname, final int mtu) {
        final Ifreq ifr = new Ifreq(ifname);
        ifr.ifr_ifru.setType("ifru_mtu");
        ifr.ifr_ifru.ifru_mtu = mtu;
        ioctl(fd, SIOCSIFMTU, ifr);
    }

    // -------------------

    public static void addInterfaceAddress(final String ifname, final InetAddress address, final int prefixLength) {
        int fd = 0;
        try {
            if (address instanceof Inet4Address) {
                fd = fd4();
                addInterfaceAddress(fd, ifname, (Inet4Address) address, prefixLength);
            } else if (address instanceof Inet6Address) {
                fd = fd6();
                addInterfaceAddress(fd, ifname, (Inet6Address) address, prefixLength);
            } else {
                throw new UnsupportedOperationException();
            }
        } finally {
            close(fd);
        }
    }

    @Deprecated
    public static byte[] getInterfaceIpAddress6(final String ifname) {
        int fd = fd6();
        final In6Ifreq ifr6 = new In6Ifreq(ifname);
        ifr6.ifr_ifru.setType("ifru_addr");

        ioctl(fd, SIOCGIFADDR_IN6, ifr6);

        final sockaddr_in6 addr = ifr6.ifr_ifru.ifru_addr;
        assert AF_INET6 == addr.sin6_family;
        return addr.sin6_addr;
    }

    private static byte[] getInterfaceIpAddress(final int fd, final String ifname) {
        final Ifreq ifr = new Ifreq(ifname);
        ifr.ifr_ifru.setType("ifru_addr");

        ioctl(fd, SIOCGIFADDR, ifr);

        final sockaddr addr = ifr.ifr_ifru.ifru_addr;
        assert AF_INET == addr.sin_family;
        return addr.sin_addr;
    }

    private static void setInterfaceIpAddress(final int fd, final String ifname, final Inet4Address addr) {
        final Ifreq ifr = new Ifreq(ifname);
        ifr.ifr_ifru.setType("ifru_addr");
        ifr.ifr_ifru.ifru_addr.sin_family = AF_INET;
        ifr.ifr_ifru.ifru_addr.sin_port = 0;
        ifr.ifr_ifru.ifru_addr.sin_addr = addr.getAddress();

        ioctl(fd, SIOCSIFADDR, ifr);
    }

    private static void addInterfaceAddress(final int fd, final String ifname,
                                            final Inet4Address address, final int prefixLength) {
        final byte[] ipAddress = address.getAddress();

        final in_aliasreq ifr = new in_aliasreq(ifname);
        ifr.ifra_addr.sin_family = AF_INET;
        ifr.ifra_addr.sin_port = 0;
        ifr.ifra_addr.sin_addr = ipAddress;

        // required.
        ifr.ifra_broadaddr.sin_family = AF_INET;
        ifr.ifra_broadaddr.sin_port = 0;
        ifr.ifra_broadaddr.sin_addr = ipAddress;

        ifr.ifra_mask.sin_family = AF_INET;
        ifr.ifra_mask.sin_port = 0;
        ifr.ifra_mask.sin_addr = prefixLengthToNetmask4(prefixLength);

        ioctl(fd, SIOCAIFADDR, ifr);
    }

    private static byte[] prefixLengthToNetmask4(final int length) {
        return subnetMaskToAddress(prefixToSubnetMask(length));
    }

    private static void addInterfaceAddress(final int fd, final String ifname,
                                            final Inet6Address address, final int prefixLength) {
        final byte[] ipAddress = address.getAddress();

        final in6_aliasreq ifr6 = new in6_aliasreq(ifname);
        ifr6.ifra_addr.sin6_len = (byte) ifr6.ifra_addr.size();
        ifr6.ifra_addr.sin6_family = AF_INET6;
        ifr6.ifra_addr.sin6_port = 0;
        ifr6.ifra_addr.sin6_addr = ipAddress;

        ifr6.ifra_prefixmask.sin6_len = (byte) ifr6.ifra_prefixmask.size();
        ifr6.ifra_prefixmask.sin6_family = AF_INET6;
        ifr6.ifra_prefixmask.sin6_port = 0;
        ifr6.ifra_prefixmask.sin6_addr = prefixLengthToNetmask6(prefixLength);

        /* important!!! */
        ifr6.ifra_lifetime.ia6t_vltime = in6_addrlifetime.ND6_INFINITE_LIFETIME;
        ifr6.ifra_lifetime.ia6t_pltime = in6_addrlifetime.ND6_INFINITE_LIFETIME;

        ioctl(fd, SIOCAIFADDR_IN6, ifr6);
    }

    private static byte[] prefixLengthToNetmask6(final int length) {
        return new byte[]{
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
                0, 0, 0, 0,
                0, 0, 0, 0
        };
    }
}
