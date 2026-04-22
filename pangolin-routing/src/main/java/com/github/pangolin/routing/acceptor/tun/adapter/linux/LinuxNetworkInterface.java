package com.github.pangolin.routing.acceptor.tun.adapter.linux;

import com.github.pangolin.routing.acceptor.tun.adapter.InterfaceAddressEx;
import com.github.pangolin.routing.acceptor.tun.adapter.linux.jna.Netlink;
import com.github.pangolin.routing.acceptor.tun.adapter.linux.jna.RtNetlink;
import com.github.pangolin.routing.acceptor.tun.adapter.NetworkInterfaceEx;
import com.github.pangolin.routing.acceptor.tun.adapter.linux.jna.If;
import com.github.pangolin.routing.acceptor.tun.adapter.linux.jna.If.*;
import com.github.pangolin.routing.acceptor.tun.adapter.unix.UnixNetworkInterface;
import com.github.pangolin.routing.acceptor.tun.adapter.unix.jna.LibC;
import com.google.common.collect.Lists;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Structure;
import lombok.extern.slf4j.Slf4j;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.List;

import static com.github.pangolin.routing.acceptor.tun.adapter.linux.LinuxNetworkRoutingTable.*;
import static com.github.pangolin.routing.acceptor.tun.adapter.linux.LinuxUtils.*;
import static com.github.pangolin.routing.acceptor.tun.adapter.linux.jna.If.*;
import static com.github.pangolin.routing.acceptor.tun.adapter.linux.jna.Socket.*;
import static com.github.pangolin.routing.acceptor.tun.adapter.linux.jna.Sockios.*;
import static com.github.pangolin.routing.acceptor.tun.adapter.util.NetUtils2.binmaskToCidr;
import static com.github.pangolin.routing.acceptor.tun.adapter.util.NetUtils2.cidrToNetmaskAddress;

/**
 * This class represents a Network Interface on Linux OS.
 */
@Slf4j
public class LinuxNetworkInterface extends UnixNetworkInterface implements NetworkInterfaceEx {

    /**
     * Lib C instance.
     */
    private static final LibC LIBC = LibC.INSTANTCE;

    private static final int IFADDRMSG_SIZE = new ifaddrmsg(PLACEHOLDER_MEMORY).size();

    /**
     * the name of this network interface.
     */
    private final String ifname;

    private LinuxNetworkInterface(final String ifname) {
        this.ifname = ifname;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String name() {
        return ifname;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getMTU() {
        return getMTU0(ifname);
    }

    /**
     * Set the Maximum Transmission Unit (MTU) of this interface.
     *
     * @param mtu the value of the MTU for that interface.
     */
    public void setMTU(final int mtu) {
        setMTU0(ifname, mtu);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<InterfaceAddressEx> getInterfaceAddresses() {
        return getInterfaceAddresses0(ifname, AF_UNSPEC);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void setInet4InterfaceAddress(final Inet4Address address, final int prefix) {
        setInet4InterfaceAddress0(ifname, address, prefix);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void setInet6InterfaceAddress(final Inet6Address address, final int prefix) {
        setInet6InterfaceAddress0(ifname, address, prefix);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void addInet4InterfaceAddress(final Inet4Address address, final int prefix) {
        addInet4InterfaceAddress0(ifname, address, prefix);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void addInet6InterfaceAddress(final Inet6Address address, final int prefix) {
        addInet6InterfaceAddress0(ifname, address, prefix);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void deleteInet4InterfaceAddress(final Inet4Address address, final int prefix) {
        deleteInet4InterfaceAddress0(ifname, address, prefix);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void deleteInet6InterfaceAddress(final Inet6Address address, final int prefix) {
        deleteInet6InterfaceAddress0(ifname, address, prefix);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void flushInet4InterfaceAddresses() {
        flushInet4InterfaceAddresses0(ifname);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void flushInet6InterfaceAddresses() {
        flushInet6InterfaceAddresses0(ifname);
    }

    /**
     * Creates the network interface with the specified name.
     *
     * @param ifname The name of the network interface.
     * @return the network interface
     */
    public static LinuxNetworkInterface getByName(final String ifname) {
        return new LinuxNetworkInterface(ifname);
    }


    // ---------

    private static int fd4() {
        return LIBC.socket(AF_INET, SOCK_DGRAM, AF_UNSPEC);
    }

    private static int fd6() {
        return LIBC.socket(AF_INET6, SOCK_DGRAM, AF_UNSPEC);
    }

    /**
     * Set the IPv4 {@code InterfaceAddresses} of this network interface.
     *
     * @param ifname  the name of network interface
     * @param address a IPv4 InterfaceAddresses bound to this network interface
     */
    private static void setInet4InterfaceAddress0(final String ifname, final Inet4Address address, final int prefix) {
        final int fd = fd4();
        try {
            // XXX flushInterfaceAddress & addInet4InterfaceAddress
            final Inet4Address netmask = toNetmask(address, prefix);
            setInet4Address(fd, ifname, address);
            // setInet4DstAddress(fd, ifname, address);
            setInet4Netmask(fd, ifname, netmask);
        } finally {
            LIBC.close(fd);
        }
    }

    /**
     * Set the IPv6 {@code InterfaceAddresses} of this network interface.
     *
     * @param ifname  the name of network interface
     * @param address a IPv6 InterfaceAddresses bound to this network interface
     */
    private static void setInet6InterfaceAddress0(final String ifname, final Inet6Address address, final int prefix) {
        final int fd = fd6();
        try {
            flushInterfaceAddresses0(fd, ifname, AF_INET6);
            addInet6InterfaceAddress(fd, ifname, address, prefix);
        } finally {
            LIBC.close(fd);
        }
    }

    /**
     * Add the IPv4 {@code InterfaceAddresses} of this network interface.
     *
     * @param ifname  the name of network interface
     * @param address a IPv4 InterfaceAddresses bound to this network interface
     */
    private static void addInet4InterfaceAddress0(final String ifname, final Inet4Address address, final int prefix) {
        addInet4InterfaceAddress(ifname, address, prefix);
    }

    /**
     * Add the IPv6 {@code InterfaceAddresses} of this network interface.
     *
     * @param ifname  the name of network interface
     * @param address a IPv6 InterfaceAddresses bound to this network interface
     */
    private static void addInet6InterfaceAddress0(final String ifname, final Inet6Address address, final int prefix) {
        final int fd = fd6();
        try {
            addInet6InterfaceAddress(fd, ifname, address, prefix);
        } finally {
            LIBC.close(fd);
        }
    }

    /**
     * Delete the IPv4 {@code InterfaceAddresses} of this network interface.
     *
     * @param ifname  the name of network interface
     * @param address a IPv4 InterfaceAddresses bound to this network interface
     */
    private static void deleteInet4InterfaceAddress0(final String ifname, final Inet4Address address, final int prefix) {
        final int fd = fd4();
        try {
            deleteInet4Address(fd, ifname, address);
        } finally {
            LIBC.close(fd);
        }
    }

    /**
     * Delete the IPv6 {@code InterfaceAddresses} of this network interface.
     *
     * @param ifname  the name of network interface
     * @param address a IPv6 InterfaceAddresses bound to this network interface
     */
    private static void deleteInet6InterfaceAddress0(final String ifname, final Inet6Address address, final int prefix) {
        final int fd = fd6();
        try {
            deleteInet6InterfaceAddress(fd, ifname, address, prefix);
        } finally {
            LIBC.close(fd);
        }
    }

    /**
     * Flush the IPv4 {@code InterfaceAddresses} of this network interface.
     *
     * @param ifname the name of network interface
     */
    private static void flushInet4InterfaceAddresses0(final String ifname) {
        final int fd = fd4();
        try {
            flushInterfaceAddresses0(fd, ifname, AF_INET);
        } finally {
            LIBC.close(fd);
        }
    }

    /**
     * Flush the IPv6 {@code InterfaceAddresses} of this network interface.
     *
     * @param ifname the name of network interface
     */
    private static void flushInet6InterfaceAddresses0(final String ifname) {
        final int fd = fd6();
        try {
            flushInterfaceAddresses0(fd, ifname, AF_INET6);
        } finally {
            LIBC.close(fd);
        }
    }

    /**
     * Set the Maximum Transmission Unit (MTU) of this interface.
     *
     * @param ifname the interface name
     * @return the value of the MTU for that interface.
     */
    private static int getMTU0(final String ifname) {
        final int fd = fd4();
        try {
            return getMTU(fd, ifname);
        } finally {
            LIBC.close(fd);
        }
    }

    /**
     * Set the Maximum Transmission Unit (MTU) of this interface.
     *
     * @param ifname the interface name
     * @param mtu    the value of the MTU for that interface.
     */
    private static void setMTU0(final String ifname, final int mtu) {
        final int fd = fd4();
        try {
            setMTU(fd, ifname, mtu);
        } finally {
            LIBC.close(fd);
        }
    }

    // ------------------------ START Interface related ------------------------

    /**
     * Get a List of the {@code InterfaceAddresses}
     * of this network interface.
     *
     * @param ifname the name of network interface
     * @param family the address family
     * @return a {@code List} object with all of the
     * InterfaceAddresss of this network interface
     */
    private static List<InterfaceAddressEx> getInterfaceAddresses0(final String ifname, final int family) {
        final ifaddrs ifa = getifaddrs0(new ifaddrs());
        try {
            final List<InterfaceAddressEx> interfaceAddresses = Lists.newArrayList();
            for (If.ifaddrs n = ifa; null != n; n = n.ifa_next) {
                if (!matches(n, ifname, family)) {
                    continue;
                }

                if (AF_INET == n.ifa_addr.sa_family) {
                    final sockaddr_in sockaddr = (sockaddr_in) n.ifa_addr.getTypedValue(sockaddr_in.class);
                    final sockaddr_in netmask = (sockaddr_in) n.ifa_netmask.getTypedValue(sockaddr_in.class);
                    final int prefix = binmaskToCidr(netmask.sin_addr);

                    interfaceAddresses.add(InterfaceAddressEx.of(toInet4Address(sockaddr), prefix));
                } else if (AF_INET6 == n.ifa_addr.sa_family) {
                    final sockaddr_in6 sockaddr = (sockaddr_in6) n.ifa_addr.getTypedValue(sockaddr_in6.class);
                    final sockaddr_in6 netmask = (sockaddr_in6) n.ifa_netmask.getTypedValue(sockaddr_in6.class);
                    final int prefix = binmaskToCidr(netmask.sin6_addr);

                    interfaceAddresses.add(InterfaceAddressEx.of(toInet6Address(sockaddr), prefix));
                }
            }
            return interfaceAddresses;
        } finally {
            // FIXED when Structure.autoRead=true if the pointer is invalid, it will cause JVM crash
            ifa.setAutoRead(false);
            LIBC.freeifaddrs(ifa);
        }
    }

    /**
     * Flush the {@code InterfaceAddresses} of this network interface.
     *
     * @param fd     the file descriptor
     * @param ifname the name of network interface
     * @param family the address family
     */
    private static void flushInterfaceAddresses0(final int fd, final String ifname, final int family) {
        final ifaddrs ifa = getifaddrs0(new ifaddrs());
        try {
            for (ifaddrs n = ifa; null != n; n = n.ifa_next) {
                if (!matches(n, ifname, family)) {
                    continue;
                }
                if (AF_INET == n.ifa_addr.sa_family) {
                    final sockaddr_in sockaddr = (sockaddr_in) n.ifa_addr.getTypedValue(sockaddr_in.class);
                    final ifreq ifr = new ifreq(n.ifa_name);
                    ifr.ifr_ifru.setType("ifru_addr");
                    ifr.ifr_ifru.ifru_addr = sockaddr;
                    ioctl0(fd, SIOCDIFADDR, ifr);
                } else if (AF_INET6 == n.ifa_addr.sa_family) {
                    final sockaddr_in6 sockaddr = (sockaddr_in6) n.ifa_addr.getTypedValue(sockaddr_in6.class);
                    final sockaddr_in6 netmask = (sockaddr_in6) n.ifa_netmask.getTypedValue(sockaddr_in6.class);
                    final in6_ifreq ifr6 = new in6_ifreq();
                    ifr6.ifr6_ifindex = if_nametoindex0(fd, n.ifa_name);
                    ifr6.ifr6_addr = sockaddr.sin6_addr;
                    ifr6.ifr6_prefixlen = binmaskToCidr(netmask.sin6_addr);
                    ioctl0(fd, SIOCDIFADDR, ifr6);
                } else {
                    log.warn("SKIP unsupported address family: {}", n.ifa_addr.sa_family);
                }
            }
        } finally {
            // FIXED when Structure.autoRead=true if the pointer is invalid, it will cause JVM crash
            ifa.setAutoRead(false);
            LIBC.freeifaddrs(ifa);
        }
    }

    private static boolean matches(final ifaddrs ifaddr, final String ifname, final int family) {
        // IFF_UP ?
        if (null == ifaddr.ifa_addr || null == ifaddr.ifa_name || !ifaddr.ifa_name.equals(ifname)) {
            return false;
        }
        return AF_UNSPEC == family || ifaddr.ifa_addr.sa_family == family;
    }

    // ------------------------ END Interface related ------------------------


    // ------------------------ START IPv4 related ------------------------

    /**
     * Get ifnet address.
     *
     * @param fd     the file descriptor
     * @param ifname the interface name
     * @return the ifnet address
     */
    private static Inet4Address getInet4Address(final int fd, final String ifname) {
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
    private static void setInet4Address(final int fd, final String ifname, final Inet4Address address) {
        ioctl0(fd, SIOCSIFADDR, _ifreq(ifname, address));
    }

    /**
     * Get ifnet destination address.
     *
     * @param fd     the file descriptor
     * @param ifname the interface name
     * @return the ifnet dst address
     */
    private static Inet4Address getInet4DstAddress(final int fd, final String ifname) {
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
    private static void setInet4DstAddress(final int fd, final String ifname, final Inet4Address address) {
        ioctl0(fd, SIOCSIFDSTADDR, _ifreq(ifname, address));
    }

    /**
     * Get net addr mask.
     *
     * @param fd     the file descriptor
     * @param ifname the interface name
     * @return the net addr mask
     */
    private static Inet4Address getInet4Netmask(final int fd, final String ifname) {
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
    private static void setInet4Netmask(final int fd, final String ifname, final Inet4Address netmask) {
        ioctl0(fd, SIOCSIFNETMASK, _ifreq(ifname, netmask));
    }

    /**
     * Delete ifnet address.
     *
     * @param fd      the file descriptor
     * @param ifname  the interface name
     * @param address the ifnet address
     */
    private static void deleteInet4Address(final int fd, final String ifname, final Inet4Address address) {
        // FIXME [25] Inappropriate ioctl for device
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

    private static void addInet4InterfaceAddress(final String ifname, final Inet4Address addr, final int prefix) {
        _netlinkAddresses4(ifname, addr, prefix, false);
    }

    private static void _netlinkAddresses4(final String ifname, final Inet4Address addr, final int prefix, final boolean delete) {
        final byte[] address = addr.getAddress();
        final int sockfd = bind();
        try {
            final int wLen = NLMSGHDR_SIZE + IFADDRMSG_SIZE + rtaAlign(RTATTR_SIZE + address.length);
            final Memory wBuf = new Memory(nlmsgAlign(wLen));

            int bytesWritten = 0;
            final Netlink.nlmsghdr nlm = new Netlink.nlmsghdr(wBuf.share(bytesWritten));
            nlm.nlmsg_len = wLen;
            nlm.nlmsg_flags = Netlink.NLM_F_REQUEST | Netlink.NLM_F_CREATE;
            nlm.nlmsg_type = (short) (!delete ? RtNetlink.RTM_NEWADDR : RtNetlink.RTM_DELADDR);
            nlm.nlmsg_seq = 1;
            nlm.nlmsg_pid = LIBC.getpid();
            nlm.write();
            bytesWritten += nlm.size();

            final ifaddrmsg ifa = new ifaddrmsg(wBuf.share(bytesWritten));
            ifa.ifa_index = if_nametoindex0(ifname);
            ifa.ifa_family = AF_INET;
            ifa.ifa_prefixlen = (byte) prefix;
            if (!delete) {
                ifa.ifa_flags = (byte) IFA_F_PERMANENT;
                ifa.ifa_scope = 0;
            }
            ifa.write();
            bytesWritten += ifa.size();

            bytesWritten += writeBytesRtAttr(wBuf.share(bytesWritten), IFA_LOCAL, address);

            if (LIBC.send(sockfd, nlm.getPointer(), bytesWritten, 0) < 0) {
                throwLastErrorException(Native.getLastError());
            }
        } finally {
            LIBC.close(sockfd);
        }
    }

    // ------------------------ END IPv4 related ------------------------

    // ------------------------ START IPv6 related ------------------------

    /**
     * Add ifnet6 address.
     *
     * @param fd           the file descriptor
     * @param ifname       the interface name
     * @param address      the ifnet6 address
     * @param prefixLength the net addr mask prefix length
     */
    private static void addInet6InterfaceAddress(final int fd,
                                                 final String ifname,
                                                 final Inet6Address address,
                                                 final int prefixLength) {
        final in6_ifreq ifr6 = new in6_ifreq();
        ifr6.ifr6_ifindex = if_nametoindex0(fd, ifname);
        ifr6.ifr6_prefixlen = prefixLength;
        ifr6.ifr6_addr = address.getAddress();

        // XXX: SIOCSIFADDR is append for IPv6
        ioctl0(fd, SIOCSIFADDR, ifr6);
    }

    /**
     * Delete ifnet6 address.
     *
     * @param fd           the file descriptor
     * @param ifname       the interface name
     * @param address      the ifnet6 address
     * @param prefixLength the net addr mask prefix length
     */
    private static void deleteInet6InterfaceAddress(final int fd,
                                                    final String ifname,
                                                    final Inet6Address address,
                                                    final int prefixLength) {
        final in6_ifreq ifr6 = new in6_ifreq();
        ifr6.ifr6_ifindex = if_nametoindex0(fd, ifname);
        ifr6.ifr6_addr = address.getAddress();
        ifr6.ifr6_prefixlen = prefixLength;

        ioctl0(fd, SIOCDIFADDR, ifr6);
    }

    // ------------------------ END IPv6 related ------------------------

    /**
     * Get the Maximum Transmission Unit (MTU) of this interface.
     *
     * @param fd     the file descriptor
     * @param ifname the interface name
     * @return the value of the MTU for that interface.
     */
    public static int getMTU(final int fd, final String ifname) {
        final ifreq ifr = new ifreq(ifname);
        return ioctl0(fd, SIOCGIFMTU, ifr).ifr_ifru.ifru_mtu;
    }

    /**
     * Set the Maximum Transmission Unit (MTU) of this interface.
     *
     * @param fd     the file descriptor
     * @param ifname the interface name
     * @param mtu    the value of the MTU for that interface.
     */
    public static void setMTU(final int fd, final String ifname, final int mtu) {
        final ifreq ifr = new ifreq(ifname);
        ifr.ifr_ifru.setType("ifru_mtu");
        ifr.ifr_ifru.ifru_mtu = mtu;
        ioctl0(fd, SIOCSIFMTU, ifr);
    }

    public static ifaddrs getifaddrs0(final ifaddrs ifa) {
        if (LIBC.getifaddrs(ifa) < 0) {
            throwLastErrorException(Native.getLastError());
        }
        return ifa;
    }

    private static int if_nametoindex0(final int fd, final String ifname) {
        final ifreq ifr = new ifreq(ifname);
        ifr.ifr_ifru.setType("ifru_ifindex");
        ioctl0(fd, SIOGIFINDEX, ifr);
        return ifr.ifr_ifru.ifru_ifindex;
        // return if_nametoindex(ifname);
    }

    static int if_nametoindex0(final String ifname) {
        final int index = LIBC.if_nametoindex(ifname);
        if (0 == index) {
            throwLastErrorException(Native.getLastError());
        }
        return index;
    }

    private static <S extends Structure> S ioctl0(final int fd, final NativeLong request, final S argp) {
        if (LIBC.ioctl(fd, request, argp) < 0) {
            throwLastErrorException(Native.getLastError());
        }
        return argp;
    }

    private static <A extends InetAddress> A toNetmask(final A address, final int prefix) {
        return cidrToNetmaskAddress(address, prefix);
    }

}
