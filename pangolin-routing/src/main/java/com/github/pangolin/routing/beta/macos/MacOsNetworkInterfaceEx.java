package com.github.pangolin.routing.beta.macos;


import static com.github.pangolin.routing.beta.linux.Socket.AF_INET;
import static com.github.pangolin.routing.beta.linux.Socket.SOCK_DGRAM;
import static com.github.pangolin.routing.beta.macos.Sockio.SIOCAIFADDR;
import static com.github.pangolin.routing.beta.macos.Sockio.SIOCAIFADDR_IN6;
import static com.github.pangolin.routing.beta.macos.Sockio.SIOCDIFADDR;
import static com.github.pangolin.routing.beta.macos.Sockio.SIOCDIFADDR_IN6;
import static com.github.pangolin.routing.beta.macos.Sockio.SIOCGIFADDR;
import static com.github.pangolin.routing.beta.macos.Sockio.SIOCGIFADDR_IN6;
import static com.github.pangolin.routing.beta.macos.Sockio.SIOCGIFMTU;
import static com.github.pangolin.routing.beta.macos.Sockio.SIOCGIFNETMASK;
import static com.github.pangolin.routing.beta.macos.Sockio.SIOCSIFADDR;
import static com.github.pangolin.routing.beta.macos.Sockio.SIOCSIFMTU;
import static com.github.pangolin.routing.beta.macos.Sockio.SIOCSIFNETMASK;
import static org.drasyl.channel.tun.jna.shared.LibC.close;
import static org.drasyl.channel.tun.jna.shared.LibC.ioctl;
import static org.drasyl.channel.tun.jna.shared.LibC.socket;
import static org.drasyl.channel.tun.jna.shared.Socket.AF_INET6;

import com.github.pangolin.routing.beta.InterfaceAddressEx;
import com.github.pangolin.routing.beta.NetworkInterfaceEx;
import com.github.pangolin.routing.beta.macos.If.Ifreq;
import com.github.pangolin.routing.beta.macos.If.In6Ifreq;
import com.github.pangolin.routing.beta.macos.If.in6_addrlifetime;
import com.github.pangolin.routing.beta.macos.If.in6_aliasreq;
import com.github.pangolin.routing.beta.macos.If.in_aliasreq;
import com.github.pangolin.routing.beta.macos.If.sockaddr;
import com.github.pangolin.routing.beta.macos.If.sockaddr_in6;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;

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
            final InetAddress ipAddress = toInetAddress(getInterfaceIpAddress4(fd, ifname));
            final byte[] interfaceNetmask = getInterfaceNetmask4(fd, ifname);
            final int prefix = netmaskToPrefixLength4(interfaceNetmask);
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
        if (addr instanceof Inet4Address) {
            final int fd = fd4();
            try {
                setInterfaceAddress4(fd, ifname, (Inet4Address) addr, networkPrefixLength);
            } finally {
                close(fd);
            }
        } else if (addr instanceof Inet6Address) {
            final int fd = fd6();
            try {
                // FIXME flushInterfaceAddress6
                addInterfaceAddress6(fd, ifname, (Inet6Address) addr, networkPrefixLength);
            } finally {
                close(fd);
            }
        } else {
            throw new UnsupportedOperationException();
        }
    }

    private void setInterfaceAddress4(final int fd, final String ifname, final Inet4Address addr, final int networkPrefixLength) {
        setInterfaceIpAddress4(fd, ifname, (Inet4Address) addr);

        final byte[] netmask = prefixLengthToNetmask4(networkPrefixLength);
        setInterfaceNetmask4(fd, ifname, (Inet4Address) toInetAddress(netmask));
    }

    @Override
    public void addInterfaceAddress(InterfaceAddressEx addressEx) {
        final InetAddress address = addressEx.getAddress();
        final int prefixLength = addressEx.getNetworkPrefixLength();
        int fd = 0;
        try {
            if (address instanceof Inet4Address) {
                fd = fd4();
                addInterfaceAddress4(fd, ifname, (Inet4Address) address, prefixLength);
            } else if (address instanceof Inet6Address) {
                fd = fd6();
                addInterfaceAddress6(fd, ifname, (Inet6Address) address, prefixLength);
            } else {
                throw new UnsupportedOperationException();
            }
        } finally {
            close(fd);
        }
    }

    @Override
    public void deleteInterfaceAddress(InterfaceAddressEx address) {
        final InetAddress addr = address.getAddress();
        final int networkPrefixLength = address.getNetworkPrefixLength();
        if (addr instanceof Inet4Address) {
            final int fd = fd4();
            try {
                deleteInterfaceIpAddress4(fd, ifname, (Inet4Address) addr);
            } finally {
                close(fd);
            }
        } else if (addr instanceof Inet6Address) {
            final int fd = fd6();
            try {
                deleteInterfaceAddress6(fd, ifname, (Inet6Address) addr, networkPrefixLength);
            } finally {
                close(fd);
            }
        } else {
            throw new UnsupportedOperationException();
        }
    }


    @Override
    public void flushInterfaceAddresses() {
        // FIXME
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

    // ------------------------ START Interface related ------------------------

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

    // ------------------------ END Interface related ------------------------

    // ------------------------ START IPv4 related ------------------------

    private static byte[] getInterfaceIpAddress4(final int fd, final String ifname) {
        final Ifreq ifr = new Ifreq(ifname);
        ifr.ifr_ifru.setType("ifru_addr");

        ioctl(fd, SIOCGIFADDR, ifr);

        final sockaddr addr = ifr.ifr_ifru.ifru_addr;
        assert AF_INET == addr.sin_family;
        return addr.sin_addr;
    }

    private static void setInterfaceIpAddress4(final int fd, final String ifname, final Inet4Address addr) {
        final Ifreq ifr = new Ifreq(ifname);
        ifr.ifr_ifru.setType("ifru_addr");
        ifr.ifr_ifru.ifru_addr.sin_family = AF_INET;
        ifr.ifr_ifru.ifru_addr.sin_port = 0;
        ifr.ifr_ifru.ifru_addr.sin_addr = addr.getAddress();

        ioctl(fd, SIOCSIFADDR, ifr);
    }

    private static void deleteInterfaceIpAddress4(final int fd, final String ifname, final Inet4Address addr) {
        final Ifreq ifr = new Ifreq(ifname);
        ifr.ifr_ifru.setType("ifru_addr");
        ifr.ifr_ifru.ifru_addr.sin_family = AF_INET;
        ifr.ifr_ifru.ifru_addr.sin_port = 0;
        ifr.ifr_ifru.ifru_addr.sin_addr = addr.getAddress();

        ioctl(fd, SIOCDIFADDR, ifr);
    }

    private static byte[] getInterfaceNetmask4(final int fd, final String ifname) {
        final Ifreq ifr = new Ifreq(ifname);
        ifr.ifr_ifru.setType("ifru_addr");

        ioctl(fd, SIOCGIFNETMASK, ifr);

        final sockaddr netmask = ifr.ifr_ifru.ifru_addr;
        assert AF_INET == netmask.sin_family;
        return netmask.sin_addr;
    }

    private static void setInterfaceNetmask4(final int fd, final String ifname, final Inet4Address addr) {
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

        ioctl(fd, SIOCSIFNETMASK, ifr);
    }

    private static void addInterfaceAddress4(final int fd, final String ifname,
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

    // ------------------------ END IPv4 related ------------------------

    // ------------------------ START IPv6 related ------------------------

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


    private static void addInterfaceAddress6(final int fd, final String ifname,
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

    private static void deleteInterfaceAddress6(final int fd, final String ifname, final Inet6Address addr, final int prefixLength) {
        final byte[] ipAddress = addr.getAddress();

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

        ioctl(fd, SIOCDIFADDR_IN6, ifr6);
    }

    // ------------------------ END IPv6 related ------------------------

    private static byte[] prefixLengthToNetmask4(final int cidrPrefix) {
        final int subnetMask = (int) ((-1L << 32 - cidrPrefix) & 0xffffffff);
        return new byte[] {
            (byte) ((subnetMask >> 24) & 0xff),
            (byte) ((subnetMask >> 16) & 0xff),
            (byte) ((subnetMask >> 8) & 0xff),
            (byte) ((subnetMask >> 0) & 0xff)
        };
    }

    private static int netmaskToPrefixLength4(final byte[] ipBytes) {
        assert ipBytes.length == 4;
        final int subnetMask = (ipBytes[0] & 0xff) << 24 | (ipBytes[1] & 0xff) << 16 | (ipBytes[2] & 0xff) << 8 | ipBytes[3] & 0xff;
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

    private static byte[] prefixLengthToNetmask6(final int length) {
        return new byte[]{
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
                0, 0, 0, 0,
                0, 0, 0, 0
        };
    }

}
