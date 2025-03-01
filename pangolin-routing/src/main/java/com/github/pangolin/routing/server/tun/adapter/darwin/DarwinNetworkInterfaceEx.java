package com.github.pangolin.routing.server.tun.adapter.darwin;


import com.github.pangolin.routing.server.tun.adapter.InterfaceAddressEx;
import com.github.pangolin.routing.server.tun.adapter.NetworkInterfaceEx;
import com.github.pangolin.routing.server.tun.adapter.darwin.jna.If;
import com.github.pangolin.routing.server.tun.adapter.darwin.jna.If.*;
import com.github.pangolin.routing.server.tun.adapter.unix.UnixNetworkInterfaceEx;
import com.google.common.collect.Lists;
import com.sun.jna.LastErrorException;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Structure;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.util.List;

import static com.github.pangolin.routing.server.tun.adapter.darwin.DarwinUtils.*;
import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.If.*;
import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.Socket.*;
import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.Sockio.*;
import static com.github.pangolin.routing.server.tun.adapter.unix.jna.LibC.*;
import static com.github.pangolin.routing.server.tun.adapter.util.NetUtils2.cidrToNetmaskAddress;
import static com.github.pangolin.routing.server.tun.adapter.util.NetUtils2.netmaskToPrefixLength;

/**
 *
 */
public class DarwinNetworkInterfaceEx extends UnixNetworkInterfaceEx implements NetworkInterfaceEx {
    private final String ifname;

    public DarwinNetworkInterfaceEx(final String ifname) {
        this.ifname = ifname;
    }

    @Override
    public int getMTU() {
        final int fd = fd4();
        try {
            return getMTU(fd, ifname);
        } finally {
            close(fd);
        }
    }

    public void setMTU(final int mtu) {
        final int fd = fd4();
        try {
            setMTU(fd, ifname, mtu);
        } finally {
            close(fd);
        }
    }

    @Override
    public List<InterfaceAddressEx> getInterfaceAddresses() {
        return getInterfaceAddresses(ifname, AF_UNSPEC);
    }

    @Override
    protected void setInterfaceAddress4(final Inet4Address address, final int prefix) {
        final int fd = fd4();
        try {
            // XXX flushInterfaceAddress & addInterfaceAddress4
            final Inet4Address netmask = cidrToNetmaskAddress(address, prefix);
            setAddress4(fd, ifname, address);
            // setDstAddress4(fd, ifname, address);
            setNetmask4(fd, ifname, netmask);
        } finally {
            close(fd);
        }
    }

    @Override
    protected void setInterfaceAddress6(final Inet6Address address, final int prefix) {
        final int fd = fd6();
        try {
            final Inet6Address netmask = cidrToNetmaskAddress(address, prefix);
            flushInterfaceAddresses(fd, ifname, AF_INET6);
            addInterfaceAddress6(fd, ifname, address, netmask);
        } finally {
            close(fd);
        }
    }

    @Override
    protected void addInterfaceAddress4(final Inet4Address address, final int prefix) {
        final int fd = fd4();
        try {
            final Inet4Address netmask = cidrToNetmaskAddress(address, prefix);
            addInterfaceAddress4(fd, ifname, address, netmask);
        } finally {
            close(fd);
        }
    }

    @Override
    protected void addInterfaceAddress6(final Inet6Address address, final int prefix) {
        final int fd = fd6();
        try {
            final Inet6Address netmask = cidrToNetmaskAddress(address, prefix);
            addInterfaceAddress6(fd, ifname, address, netmask);
        } finally {
            close(fd);
        }
    }

    @Override
    protected void deleteInterfaceAddress4(final Inet4Address address, final int prefix) {
        final int fd = fd4();
        try {
            deleteAddress4(fd, ifname, address);
        } finally {
            close(fd);
        }
    }

    @Override
    protected void deleteInterfaceAddress6(final Inet6Address address, final int prefix) {
        final int fd = fd4();
        try {
            final Inet6Address netmask = cidrToNetmaskAddress(address, prefix);
            deleteInterfaceAddress6(fd, ifname, address, netmask);
        } finally {
            close(fd);
        }
    }

    @Override
    protected void flushInterfaceAddresses4() {
        final int fd = fd4();
        try {
            flushInterfaceAddresses(fd, ifname, AF_INET);
        } finally {
            close(fd);
        }
    }

    @Override
    protected void flushInterfaceAddresses6() {
        final int fd = fd6();
        try {
            flushInterfaceAddresses(fd, ifname, AF_INET6);
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

    static int getMTU(final int fd, final String ifname) {
        final ifreq ifr = new ifreq(ifname);
        return ioctl0(fd, SIOCGIFMTU, ifr).ifr_ifru.ifru_mtu;
    }

    static void setMTU(final int fd, final String ifname, final int mtu) {
        final ifreq ifr = new ifreq(ifname);
        ifr.ifr_ifru.setType("ifru_mtu");
        ifr.ifr_ifru.ifru_mtu = mtu;
        ioctl0(fd, SIOCSIFMTU, ifr);
    }

    // ------------------------ END Interface related ------------------------


    private static List<InterfaceAddressEx> getInterfaceAddresses(final String ifname, final int family) {
        final ifaddrs ifa = new ifaddrs();
        if (0 != getifaddrs(ifa)) {
            throw new LastErrorException(Native.getLastError());
        }

        try {
            final List<InterfaceAddressEx> interfaceAddresses = Lists.newArrayList();
            for (If.ifaddrs n = ifa; null != n; n = n.ifa_next) {
                if (!matches(n, ifname, family)) {
                    continue;
                }

                if (AF_INET == n.ifa_addr.sa_family) {
                    final sockaddr_in sockaddr = new sockaddr_in(n.ifa_addr.getPointer());
                    final sockaddr_in netmask = new sockaddr_in(n.ifa_netmask.getPointer());
                    final int prefix = netmaskToPrefixLength(netmask.sin_addr);

                    interfaceAddresses.add(InterfaceAddressEx.of(toInet4Address(sockaddr), prefix));
                } else if (AF_INET6 == n.ifa_addr.sa_family) {
                    final sockaddr_in6 sockaddr = new sockaddr_in6(n.ifa_addr.getPointer());
                    final sockaddr_in6 netmask = new sockaddr_in6(n.ifa_netmask.getPointer());
                    final int prefix = netmaskToPrefixLength(netmask.sin6_addr);

                    interfaceAddresses.add(InterfaceAddressEx.of(toInet6Address(sockaddr), prefix));
                }
            }
            return interfaceAddresses;
        } finally {
            // FIXED when Structure.autoRead=true if the pointer is invalid, it will cause JVM crash
            ifa.setAutoRead(false);
            freeifaddrs(ifa);
        }
    }

    private static void flushInterfaceAddresses(final int fd, final String ifname, final int family) {
        final ifaddrs ifa = getifaddrs0(new ifaddrs());
        try {
            for (ifaddrs n = ifa; null != n; n = n.ifa_next) {
                if (!matches(n, ifname, family)) {
                    continue;
                }
                if (AF_INET == n.ifa_addr.sa_family) {
                    final sockaddr_in sockaddr = new sockaddr_in(n.ifa_addr.getPointer());
                    deleteAddress4(fd, ifname, toInet4Address(sockaddr));
                } else if (AF_INET6 == n.ifa_addr.sa_family) {
                    final sockaddr_in6 sockaddr = new sockaddr_in6(n.ifa_addr.getPointer());
                    final sockaddr_in6 netmask = new sockaddr_in6(n.ifa_netmask.getPointer());
                    deleteInterfaceAddress6(fd, ifname, toInet6Address(sockaddr), toInet6Address(netmask));
                } else {
                    throw new UnsupportedOperationException("family: " + n.ifa_addr.sa_family);
                }
            }
        } finally {
            // FIXED when Structure.autoRead=true if the pointer is invalid, it will cause JVM crash
            ifa.setAutoRead(false);
            freeifaddrs(ifa);
        }
    }

    private static boolean matches(final ifaddrs ifaddr, final String ifname, final int family) {
        // IFF_UP ?
        if (null == ifaddr.ifa_addr || null == ifaddr.ifa_name || !ifaddr.ifa_name.equals(ifname)) {
            return false;
        }
        return AF_UNSPEC == family || ifaddr.ifa_addr.sa_family == family;
    }


    // ------------------------ START IPv4 related ------------------------

    /**
     * Get ifnet address.
     *
     * @param fd     the file descriptor
     * @param ifname the interface name
     * @return the ifnet address
     */
    private static Inet4Address getAddress4(final int fd, final String ifname) {
        final ifreq ifr = ioctl0(fd, SIOCGIFADDR, _ifreq(ifname, null));
        return toInet4Address(ifr.ifr_ifru.ifru_addr);
    }

    /**
     * Set ifnet address.
     *
     * @param fd      the file descriptor
     * @param ifname  the interface name
     * @param address the ifnet address
     */
    private static void setAddress4(final int fd, final String ifname, final Inet4Address address) {
        ioctl0(fd, SIOCSIFADDR, _ifreq(ifname, address));
    }

    /**
     * Get ifnet destination address.
     *
     * @param fd     the file descriptor
     * @param ifname the interface name
     * @return the ifnet dst address
     */
    private static Inet4Address getDstAddress4(final int fd, final String ifname) {
        final ifreq ifr = ioctl0(fd, SIOCGIFDSTADDR, _ifreq(ifname, null));
        return toInet4Address(ifr.ifr_ifru.ifru_addr);
    }

    /**
     * Set ifnet destination address.
     *
     * @param fd      the file descriptor
     * @param ifname  the interface name
     * @param address the ifnet dst address
     */
    private static void setDstAddress4(final int fd, final String ifname, final Inet4Address address) {
        ioctl0(fd, SIOCSIFDSTADDR, _ifreq(ifname, address));
    }

    /**
     * Get net addr mask.
     *
     * @param fd     the file descriptor
     * @param ifname the interface name
     * @return the net addr mask
     */
    private static Inet4Address getNetmask4(final int fd, final String ifname) {
        final ifreq ifr = ioctl0(fd, SIOCGIFNETMASK, _ifreq(ifname, null));
        return toInet4Address(ifr.ifr_ifru.ifru_addr);
    }

    /**
     * Set net addr mask.
     *
     * @param fd      the file descriptor
     * @param ifname  the interface name
     * @param netmask the net addr mask
     */
    private static void setNetmask4(final int fd, final String ifname, final Inet4Address netmask) {
        ioctl0(fd, SIOCSIFNETMASK, _ifreq(ifname, netmask));
    }

    /**
     * Delete ifnet address.
     *
     * @param fd      the file descriptor
     * @param ifname  the interface name
     * @param address the ifnet address
     */
    private static void deleteAddress4(final int fd, final String ifname, final Inet4Address address) {
        ioctl0(fd, SIOCDIFADDR, _ifreq(ifname, address));
    }

    private static ifreq _ifreq(final String ifname, final Inet4Address addr) {
        final ifreq ifr = new ifreq(ifname);
        ifr.ifr_ifru.setType("ifru_addr");
        if (null != addr) {
            writeSockAddr4(ifr.ifr_ifru.ifru_addr, addr);
            assert AF_INET == ifr.ifr_ifru.ifru_addr.sin_family;
        }
        return ifr;
    }


    private static void addInterfaceAddress4(final int fd, final String ifname,
                                             final Inet4Address address, final Inet4Address netmask) {
        final ifaliasreq ifr = new ifaliasreq(ifname);
        writeSockAddr4(ifr.ifra_addr, address);

        // required.
        writeSockAddr4(ifr.ifra_broadaddr, address);

        // netmask
        writeSockAddr4(ifr.ifra_mask, netmask);

        ioctl0(fd, SIOCAIFADDR, ifr);
    }


    // ------------------------ END IPv4 related ------------------------

    // ------------------------ START IPv6 related ------------------------


    private static void addInterfaceAddress6(final int fd, final String ifname,
                                             final Inet6Address address, final Inet6Address netmask) {
        final in6_aliasreq ifr6 = new in6_aliasreq(ifname);
        writeSockAddr6(ifr6.ifra_addr, address);
        // writeSockAddr6(ifr6.ifra_dstaddr, address);
        writeSockAddr6(ifr6.ifra_prefixmask, netmask);

        /* important!!! */
        ifr6.ifra_lifetime.ia6t_vltime = ND6_INFINITE_LIFETIME;
        ifr6.ifra_lifetime.ia6t_pltime = ND6_INFINITE_LIFETIME;

        ioctl0(fd, SIOCAIFADDR_IN6, ifr6);
    }

    private static void deleteInterfaceAddress6(final int fd, final String ifname,
                                                final Inet6Address addr, final Inet6Address netmask) {
        final in6_aliasreq ifr6 = new in6_aliasreq(ifname);

        writeSockAddr6(ifr6.ifra_addr, addr);
        // writeSockAddr6(ifr6.ifra_dstaddr, addr);
        writeSockAddr6(ifr6.ifra_prefixmask, netmask);

        /* important!!! */
        ifr6.ifra_lifetime.ia6t_vltime = ND6_INFINITE_LIFETIME;
        ifr6.ifra_lifetime.ia6t_pltime = ND6_INFINITE_LIFETIME;

        ioctl0(fd, SIOCDIFADDR_IN6, ifr6);
    }

    // ------------------------ END IPv6 related ------------------------

    private static ifaddrs getifaddrs0(final ifaddrs ifa) {
        if (0 != getifaddrs(ifa)) {
            throwUnchecked(Native.getLastError());
        }
        return ifa;
    }

    private static <S extends Structure> S ioctl0(final int fd, final NativeLong request, final S argp) {
        if (0 != ioctl(fd, request, argp)) {
            throwUnchecked(Native.getLastError());
        }
        return argp;
    }

    private static void throwUnchecked(final int errno) {
        final String errmsg = String.format("[%s] %s", errno, strerror(errno));
        throw new DarwinException(errno, errmsg);
    }

    private static class DarwinException extends LastErrorException {

        DarwinException(final int errno, final String errmsg) {
            super(errno, errmsg);
        }

    }
}
