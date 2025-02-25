package com.github.pangolin.routing.server.tun.adapter.linux;

import com.github.pangolin.routing.server.tun.adapter.InterfaceAddressEx;
import com.github.pangolin.routing.server.tun.adapter.NetworkInterfaceEx;
import com.github.pangolin.routing.server.tun.adapter.linux.jna.If;
import com.github.pangolin.routing.server.tun.adapter.linux.jna.If.Ifreq;
import com.github.pangolin.routing.server.tun.adapter.linux.jna.If.in6_ifreq;
import com.github.pangolin.routing.server.tun.adapter.linux.jna.If.sockaddr_in;
import com.github.pangolin.routing.server.tun.adapter.linux.jna.If.sockaddr_in6;
import com.github.pangolin.routing.server.tun.adapter.linux.jna.Sockios;
import com.google.common.collect.Lists;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static com.github.pangolin.routing.server.tun.adapter.linux.jna.LibC.*;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.Socket.*;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.Sockios.*;

/**
 *
 */
public class LinuxNetworkInterfaceEx implements NetworkInterfaceEx {
    private final String ifname;

    public LinuxNetworkInterfaceEx(final String ifname) {
        this.ifname = ifname;
    }

    public InterfaceAddressEx getInterfaceAddress4() {
        final int fd = fd4();
        try {
            final InetAddress ipAddress = toInetAddress(getInterfaceIpAddress4(fd, ifname));
            final byte[] interfaceNetmask = getInterfaceNetmask4(fd, ifname);
            final int prefix = netmaskToPrefixLength(interfaceNetmask);
            return InterfaceAddressEx.of(ipAddress, prefix);
        } finally {
            close(fd);
        }
    }

    public void setInterfaceAddress4(final Inet4Address addr, final int networkPrefixLength) {
        final int fd = fd4();
        try {
            setInterfaceIpAddress4(fd, ifname, addr);

            final byte[] netmask = cidrPrefixToNetmask(addr.getAddress().clone(), networkPrefixLength);
            setInterfaceNetmask4(fd, ifname, netmask);
        } finally {
            close(fd);
        }
    }

    @Override
    public List<InterfaceAddressEx> getInterfaceAddresses() {
        return getInterfaceAddresses(ifname, AF_UNSPEC);
    }

    @Override
    public void setInterfaceAddress(final InterfaceAddressEx address) {
        final InetAddress addr = address.getAddress();
        final int networkPrefixLength = address.getNetworkPrefixLength();
        if (addr instanceof Inet4Address) {
            final int fd = fd4();
            try {
                setInterfaceAddress4((Inet4Address) addr, networkPrefixLength);
                // flushInterfaceAddresses4(fd, ifname);
                // addInterfaceAddress4(fd, ifname, (Inet4Address) addr, networkPrefixLength);
            } finally {
                close(fd);
            }
        } else if (addr instanceof Inet6Address) {
            final int fd = fd6();
            try {
                flushInterfaceAddresses6(fd, ifname);
                addInterfaceAddress6(fd, ifname, (Inet6Address) addr, networkPrefixLength);
            } finally {
                close(fd);
            }
        } else {
            throw new UnsupportedOperationException();

        }
    }

    @Override
    public void addInterfaceAddress(final InterfaceAddressEx address) {
        final InetAddress addr = address.getAddress();
        final int networkPrefixLength = address.getNetworkPrefixLength();
        if (addr instanceof Inet4Address) {
            final int fd = fd4();
            try {
                addInterfaceAddress4(fd, ifname, (Inet4Address) addr, networkPrefixLength);
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
        final List<InterfaceAddressEx> addresses = getInterfaceAddresses(ifname, AF_UNSPEC);
        final List<InterfaceAddressEx> ipv4 = addresses.stream()
                .filter(a -> a.getAddress() instanceof Inet4Address)
                .collect(Collectors.toList());
        final List<InterfaceAddressEx> ipv6 = addresses.stream()
                .filter(a -> a.getAddress() instanceof Inet6Address)
                .collect(Collectors.toList());
        if (!ipv4.isEmpty()) {
            final int fd = fd4();
            try {
                ipv4.forEach(addr -> deleteInterfaceIpAddress4(fd, ifname, (Inet4Address) addr.getAddress()));
            } finally {
                close(fd);
            }
        }
        if (!ipv6.isEmpty()) {
            final int fd = fd6();
            try {
                ipv6.forEach(addr -> deleteInterfaceAddress6(fd, ifname, (Inet6Address) addr.getAddress(), addr.getNetworkPrefixLength()));
            } finally {
                close(fd);
            }
        }
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

    public void setMTU(final int mtu) {
        final int fd = fd4();
        try {
            setMtu(fd, ifname, mtu);
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

    static int getMtu(final int fd, final String ifname) {
        final Ifreq ifr = new Ifreq(ifname);
        final int code = ioctl(fd, SIOCGIFMTU, ifr);
        return ifr.ifr_ifru.ifru_mtu;
    }

    static void setMtu(final int fd, final String ifname, final int mtu) {
        final Ifreq ifr = new Ifreq(ifname);
        ifr.ifr_ifru.setType("ifru_mtu");
        ifr.ifr_ifru.ifru_mtu = mtu;
        ioctl(fd, SIOCSIFMTU, ifr);
    }

    // ------------------------ END Interface related ------------------------

    static List<InterfaceAddressEx> getInterfaceAddresses(final String ifname, final int family) {
        final If.ifaddrs ifa = new If.ifaddrs();
        getifaddrs(ifa);

        try {
            final List<InterfaceAddressEx> interfaceAddresses = Lists.newArrayList();
            for (If.ifaddrs n = ifa; null != n; n = n.ifa_next) {
                // IFF_UP ?
                if (null == n.ifa_addr) {
                    continue;
                }

                final String ifaName = n.ifa_name;
                final short sa_family = n.ifa_addr.sa_family;
                if ((AF_UNSPEC != family && family != sa_family) || (null != ifname && !ifname.equals(ifaName))) {
                    continue;
                }

                if (AF_INET == sa_family) {
                    final sockaddr_in sockaddr = (sockaddr_in) n.ifa_addr.getTypedValue(sockaddr_in.class);
                    final sockaddr_in netmask = null != n.ifa_netmask ? (sockaddr_in) n.ifa_netmask.getTypedValue(sockaddr_in.class) : null;
                    final int prefix = null != netmask ? netmaskToPrefixLength(netmask.sin_addr) : 0;

                    interfaceAddresses.add(InterfaceAddressEx.of(toInetAddress(sockaddr.sin_addr), prefix));
                } else if (AF_INET6 == sa_family) {
                    final sockaddr_in6 sockaddr = (sockaddr_in6) n.ifa_addr.getTypedValue(sockaddr_in6.class);

                    final sockaddr_in6 netmask = null != n.ifa_netmask ? (sockaddr_in6) n.ifa_netmask.getTypedValue(sockaddr_in6.class) : null;
                    final int prefix = null != netmask ? netmaskToPrefixLength(netmask.sin6_addr) : 0;

                    interfaceAddresses.add(InterfaceAddressEx.of(toInetAddress(sockaddr.sin6_addr), prefix));
                }
            }
            return interfaceAddresses;
        } finally {
            // FIXED when Structure.autoRead=true if the pointer is invalid, it will cause JVM crash
            ifa.setAutoRead(false);
            freeifaddrs(ifa);
        }
    }

    private static InetAddress toInetAddress(final byte[] sinAddr) {
        try {
            return InetAddress.getByAddress(sinAddr);
        } catch (final UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void flushInterfaceAddresses4(final int fd, final String ifname) {
        for (final InterfaceAddressEx remove : getInterfaceAddresses(ifname, AF_INET)) {
            deleteInterfaceIpAddress4(fd, ifname, (Inet4Address) remove.getAddress());
        }
    }

    private static void flushInterfaceAddresses6(final int fd, final String ifname) {
        for (final InterfaceAddressEx remove : getInterfaceAddresses(ifname, AF_INET6)) {
            deleteInterfaceIpAddress4(fd, ifname, (Inet4Address) remove.getAddress());
        }
    }


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

    private static byte[] getInterfaceNetmask4(final int fd, final String ifname) {
        final Ifreq ifr = new Ifreq(ifname);
        ifr.ifr_ifru.setType("ifru_netmask");

        ioctl(fd, SIOCGIFNETMASK, ifr);

        final sockaddr_in netmask = ifr.ifr_ifru.ifru_netmask;
        assert AF_INET == netmask.sin_family;
        return netmask.sin_addr;
    }

    private static void setInterfaceNetmask4(final int fd, final String ifname, final byte[] addr) {
        final Ifreq ifr = new Ifreq(ifname);
        ifr.ifr_ifru.setType("ifru_netmask");
        ifr.ifr_ifru.ifru_netmask.sin_family = AF_INET;
        ifr.ifr_ifru.ifru_netmask.sin_port = 0;
        ifr.ifr_ifru.ifru_netmask.sin_addr = addr;

        ioctl(fd, SIOCSIFNETMASK, ifr);
    }

    private static void addInterfaceAddress4(final int fd, final String ifname,
                                             final Inet4Address address, final int prefixLength) {
        // FIXME
        /*
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
        ifr.ifra_mask.sin_addr = cidrPrefixToNetmask(ipAddress.clone(), prefixLength);

        ioctl(fd, SIOCAIFADDR, ifr);
        */
    }

    private static void deleteInterfaceIpAddress4(final int fd, final String ifname, final Inet4Address addr) {
        final Ifreq ifr = new Ifreq(ifname);
        ifr.ifr_ifru.setType("ifru_addr");
        ifr.ifr_ifru.ifru_addr.sin_family = AF_INET;
        ifr.ifr_ifru.ifru_addr.sin_port = 0;
        ifr.ifr_ifru.ifru_addr.sin_addr = addr.getAddress();

        // FIXME [25] Inappropriate ioctl for device
//        ioctl(fd, SIOCDIFADDR, ifr);
    }


    // ------------------------ END IPv4 related ------------------------

    // ------------------------ START IPv6 related ------------------------


    private static void addInterfaceAddress6(final int fd, final String ifname, final Inet6Address addr, final int prefixLength) {
        // Wrong: sysctl net.ipv6.conf.all.disable_ipv6 --> 1: [13] Permission denied
        // sysctl net.ipv6.conf.all.disable_ipv6=0
        final Ifreq ifr = new Ifreq(ifname);
        ifr.ifr_ifru.setType("ifru_ifindex");
        ioctl(fd, SIOGIFINDEX, ifr);

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
        final Ifreq ifr = new Ifreq(ifname);
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

    private static byte[] cidrPrefixToNetmask(final byte[] bytes, int prefix) {
        Arrays.fill(bytes, (byte) 0xFF);
        bytes[prefix / Byte.SIZE] <<= prefix % Byte.SIZE;
        prefix += prefix % Byte.SIZE;
        for (int i = prefix / Byte.SIZE; i < bytes.length; i++) {
            bytes[i] = 0;
        }
        return bytes;
    }

    static int netmaskToPrefixLength(final byte[] ipBytes) {
        int prefix_length = 0;
        for (byte b : ipBytes) {
            if ((b & 0xFF) == 0xFF) {
                prefix_length += Byte.SIZE;
                continue;
            }
            for (int j = 0; j < Byte.SIZE; j++) {
                if ((b >> j & 1) != 1) {
                    return prefix_length;
                }
                prefix_length += 1;
            }
        }
        return prefix_length;
    }
}
