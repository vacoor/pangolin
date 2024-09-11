package com.github.pangolin.routing.beta.linux;

import static com.github.pangolin.routing.beta.linux.Socket.AF_INET;
import static com.github.pangolin.routing.beta.linux.Socket.AF_INET6;
import static com.github.pangolin.routing.beta.linux.Socket.SOCK_DGRAM;
import static com.github.pangolin.routing.beta.linux.Sockios.SIOCDIFADDR;
import static com.github.pangolin.routing.beta.linux.Sockios.SIOCGIFADDR;
import static com.github.pangolin.routing.beta.linux.Sockios.SIOCGIFMTU;
import static com.github.pangolin.routing.beta.linux.Sockios.SIOCGIFNETMASK;
import static com.github.pangolin.routing.beta.linux.Sockios.SIOCSIFADDR;
import static com.github.pangolin.routing.beta.linux.Sockios.SIOCSIFMTU;
import static com.github.pangolin.routing.beta.linux.Sockios.SIOCSIFNETMASK;
import static com.github.pangolin.routing.beta.linux.Sockios.SIOGIFINDEX;
import static org.drasyl.channel.tun.jna.shared.LibC.close;
import static org.drasyl.channel.tun.jna.shared.LibC.ioctl;
import static org.drasyl.channel.tun.jna.shared.LibC.socket;

import com.github.pangolin.routing.beta.InterfaceAddressEx;
import com.github.pangolin.routing.beta.NetworkInterfaceEx;
import com.github.pangolin.routing.beta.linux.If.Ifreq;
import com.github.pangolin.routing.beta.linux.If.in6_ifreq;
import com.github.pangolin.routing.beta.linux.If.sockaddr_in;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;

/**
 *
 */
public class LinuxNetworkInterfaceEx implements NetworkInterfaceEx {
    private final String ifname;

    public LinuxNetworkInterfaceEx(final String ifname) {
        this.ifname = ifname;
    }

    @Override
    public List<InterfaceAddressEx> getInterfaceAddresses() {
        final int fd = fd4();
        try {
            final InetAddress ipAddress = toInetAddress(getInterfaceIpAddress4(fd, ifname));
            final byte[] interfaceNetmask = getInterfaceNetmask4(fd, ifname);
            final int prefix = netmaskToPrefixLength4(interfaceNetmask);
            return Collections.singletonList(InterfaceAddressEx.of(ipAddress, (short) prefix));
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
    public void addInterfaceAddress(final InterfaceAddressEx address) {
        final InetAddress addr = address.getAddress();
        final int networkPrefixLength = address.getNetworkPrefixLength();
        if (addr instanceof Inet4Address) {
            final int fd = fd4();
            try {
                // FIXME
                setInterfaceAddress4(fd, ifname, (Inet4Address) addr, networkPrefixLength);
            } finally {
                close(fd);
            }
        } else if (addr instanceof Inet6Address) {
            final int fd = fd6();
            try {
                addInterfaceAddress6(fd, ifname, (Inet6Address) addr, networkPrefixLength);
            } finally {
                close(fd);
            }
        } else {
            throw new UnsupportedOperationException();
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
    public int getMTU() {
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

        final sockaddr_in addr = ifr.ifr_ifru.ifru_addr;
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

        // FIXME [25] Inappropriate ioctl for device
        ioctl(fd, SIOCDIFADDR, ifr);
    }

    private static byte[] getInterfaceNetmask4(final int fd, final String ifname) {
        final Ifreq ifr = new Ifreq(ifname);
        ifr.ifr_ifru.setType("ifru_netmask");

        ioctl(fd, SIOCGIFNETMASK, ifr);

        final sockaddr_in netmask = ifr.ifr_ifru.ifru_netmask;
        assert AF_INET == netmask.sin_family;
        return netmask.sin_addr;
    }

    private static void setInterfaceNetmask4(final int fd, final String ifname, final Inet4Address addr) {
        final Ifreq ifr = new Ifreq(ifname);
        ifr.ifr_ifru.setType("ifru_netmask");
        ifr.ifr_ifru.ifru_netmask.sin_family = AF_INET;
        ifr.ifr_ifru.ifru_netmask.sin_port = 0;
        ifr.ifr_ifru.ifru_netmask.sin_addr = addr.getAddress();

        ioctl(fd, SIOCSIFNETMASK, ifr);
    }

    // ------------------------ END IPv4 related ------------------------

    // ------------------------ START IPv6 related ------------------------

    private static void addInterfaceAddress6(final int fd, final String ifname, final Inet6Address addr, final int prefixLength) {
        // Wrong: sysctl net.ipv6.conf.all.disable_ipv6 --> 1: [13] Permission denied
        // sysctl net.ipv6.conf.all.disable_ipv6=0
        final If.Ifreq ifr = new If.Ifreq(ifname);
        ifr.ifr_ifru.setType("ifru_ifindex");
        ioctl(fd, SIOGIFINDEX, ifr);
        System.out.println("IFR index=" + ifr.ifr_ifru.ifru_ifindex);

        final in6_ifreq ifr6 = new in6_ifreq();
        ifr6.ifr6_ifindex = ifr.ifr_ifru.ifru_ifindex;
        ifr6.ifr6_prefixlen = prefixLength;

        ifr6.ifr6_addr = addr.getAddress();
        // SIOCSIFADDR is append for IPv6
        ioctl(fd, Sockios.SIOCSIFADDR, ifr6);
    }

    private static void deleteInterfaceAddress6(final int fd, final String ifname, final Inet6Address addr, final int prefixLength) {
        // Wrong: sysctl net.ipv6.conf.all.disable_ipv6 --> 1: [13] Permission denied
        // sysctl net.ipv6.conf.all.disable_ipv6=0
        final If.Ifreq ifr = new If.Ifreq(ifname);
        ifr.ifr_ifru.setType("ifru_ifindex");
        ioctl(fd, SIOGIFINDEX, ifr);
        System.out.println("IFR index=" + ifr.ifr_ifru.ifru_ifindex);

        final in6_ifreq ifr6 = new in6_ifreq();
        ifr6.ifr6_ifindex = ifr.ifr_ifru.ifru_ifindex;
        ifr6.ifr6_prefixlen = prefixLength;

//        ifr6.ifr6_addr.sin6_family = AF_INET6;
//        ifr6.ifr6_addr.sin6_port = 0;
        ifr6.ifr6_addr = addr.getAddress();
//        ifr6.ifr6_addr.sin6_scope_id = addr.getScopeId();
        ioctl(fd, Sockios.SIOCDIFADDR, ifr6);
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
}
