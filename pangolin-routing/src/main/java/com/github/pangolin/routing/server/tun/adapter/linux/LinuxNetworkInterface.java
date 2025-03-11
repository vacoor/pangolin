package com.github.pangolin.routing.server.tun.adapter.linux;

import com.github.pangolin.routing.server.tun.adapter.InterfaceAddressEx;
import com.github.pangolin.routing.server.tun.adapter.NetworkInterfaceEx;
import com.github.pangolin.routing.server.tun.adapter.linux.jna.If;
import com.github.pangolin.routing.server.tun.adapter.linux.jna.If.ifreq;
import com.github.pangolin.routing.server.tun.adapter.linux.jna.If.in6_ifreq;
import com.github.pangolin.routing.server.tun.adapter.linux.jna.If.sockaddr_in;
import com.github.pangolin.routing.server.tun.adapter.linux.jna.If.sockaddr_in6;
import com.github.pangolin.routing.server.tun.adapter.unix.UnixNetworkInterface;
import com.google.common.collect.Lists;
import com.sun.jna.*;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.util.List;

import static com.github.pangolin.routing.server.tun.adapter.linux.LinuxUtils.*;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.If.ifaddrs;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.Netlink.*;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.RtNetlink.*;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.Socket.*;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.Sockios.*;
import static com.github.pangolin.routing.server.tun.adapter.unix.jna.LibC.*;
import static com.github.pangolin.routing.server.tun.adapter.util.NetUtils2.cidrToNetmaskAddress;
import static com.github.pangolin.routing.server.tun.adapter.util.NetUtils2.netmaskToPrefixLength;

/**
 *
 */
public class LinuxNetworkInterface extends UnixNetworkInterface implements NetworkInterfaceEx {
    private final String ifname;

    public LinuxNetworkInterface(final String ifname) {
        this.ifname = ifname;
    }

    @Override
    public String name() {
        return ifname;
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
    protected void setInet4InterfaceAddress(final Inet4Address address, final int prefix) {
        final int fd = fd4();
        try {
            // XXX flushInterfaceAddress & addInet4InterfaceAddress
            final Inet4Address netmask = cidrToNetmaskAddress(address, prefix);
            setAddress4(fd, ifname, address);
            // setDstAddress4(fd, ifname, address);
            setNetmask4(fd, ifname, netmask);
        } finally {
            close(fd);
        }
    }

    @Override
    protected void setInet6InterfaceAddress(final Inet6Address address, final int prefix) {
        final int fd = fd6();
        try {
            flushInterfaceAddresses(fd, ifname, AF_INET6);
            addInterfaceAddress6(fd, ifname, address, prefix);
        } finally {
            close(fd);
        }
    }

    @Override
    protected void addInet4InterfaceAddress(final Inet4Address address, final int prefix) {
        addInterfaceAddress4(ifname, address, prefix);
    }


    @Override
    protected void addInet6InterfaceAddress(final Inet6Address address, final int prefix) {
        final int fd = fd6();
        try {
            addInterfaceAddress6(fd, ifname, address, prefix);
        } finally {
            close(fd);
        }
    }

    @Override
    protected void deleteInet4InterfaceAddress(final Inet4Address address, final int prefix) {
        final int fd = fd4();
        try {
            deleteAddress4(fd, ifname, address);
        } finally {
            close(fd);
        }
    }

    @Override
    protected void deleteInet6InterfaceAddress(final Inet6Address address, final int prefix) {
        final int fd = fd6();
        try {
            deleteInterfaceAddress6(fd, ifname, address, prefix);
        } finally {
            close(fd);
        }
    }

    @Override
    protected void flushInet4InterfaceAddresses() {
        final int fd = fd4();
        try {
            flushInterfaceAddresses(fd, ifname, AF_INET);
        } finally {
            close(fd);
        }
    }

    @Override
    protected void flushInet6InterfaceAddresses() {
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
                    final int prefix = netmaskToPrefixLength(netmask.sin_addr);

                    interfaceAddresses.add(InterfaceAddressEx.of(toInet4Address(sockaddr), prefix));
                } else if (AF_INET6 == n.ifa_addr.sa_family) {
                    final sockaddr_in6 sockaddr = (sockaddr_in6) n.ifa_addr.getTypedValue(sockaddr_in6.class);
                    final sockaddr_in6 netmask = (sockaddr_in6) n.ifa_netmask.getTypedValue(sockaddr_in6.class);
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
                    final sockaddr_in sockaddr = (sockaddr_in) n.ifa_addr.getTypedValue(sockaddr_in.class);
                    deleteAddress4(fd, ifname, toInet4Address(sockaddr));
                } else if (AF_INET6 == n.ifa_addr.sa_family) {
                    final sockaddr_in6 sockaddr = (sockaddr_in6) n.ifa_addr.getTypedValue(sockaddr_in6.class);
                    final sockaddr_in6 netmask = (sockaddr_in6) n.ifa_netmask.getTypedValue(sockaddr_in6.class);
                    final int prefix = netmaskToPrefixLength(netmask.sin6_addr);
                    deleteInterfaceAddress6(fd, ifname, toInet6Address(sockaddr), prefix);
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
        // FIXME [25] Inappropriate ioctl for device
        ioctl0(fd, SIOCDIFADDR, _ifreq(ifname, address));
    }

    private static void addInterfaceAddress4(final String ifname, final Inet4Address addr, final int prefix) {
        _netlinkAddresses4(ifname, addr, prefix, false);
    }

    private static void _netlinkAddresses4(final String ifname, final Inet4Address addr, final int prefix, final boolean delete) {
        int IFA_F_PERMANENT = 0x80;
        int IFA_LOCAL = 0x02;

        final byte[] address = addr.getAddress();
        final int sockfd = socket(AF_NETLINK, SOCK_RAW, NETLINK_ROUTE);
        try {
            final sockaddr_nl localAddr = new sockaddr_nl();
            localAddr.nl_family = AF_NETLINK;
            localAddr.nl_pid = API_INSTANTCE.getpid();

            if (API_INSTANTCE.bind(sockfd, localAddr, localAddr.size()) < 0) {
                throwLastErrorException(Native.getLastError());
            }

            int msgSize = 32; // nlmsghdr(16) + ifaddrmsg(8) + rtattr(4) + address(4)

            // 初始化消息内存
            final Memory buffer = new Memory(msgSize);
            int offset = 0;

            final nlmsghdr hl = new nlmsghdr(buffer.share(offset));
            hl.nlmsg_len = msgSize;
            hl.nlmsg_flags = NLM_F_REQUEST | NLM_F_CREATE;
            hl.nlmsg_type = (short) (!delete ? RTM_NEWADDR : RTM_DELADDR);
            hl.write();
            offset += hl.size();

            final ifaddrmsg ifa = new ifaddrmsg(buffer.share(offset));
            ifa.ifa_family = AF_INET;
            ifa.ifa_prefixlen = (byte) prefix;
            ifa.ifa_index = if_nametoindex0(ifname);
            if (!delete) {
                ifa.ifa_flags = (byte) IFA_F_PERMANENT;
                ifa.ifa_scope = 0;
            }
            ifa.write();
            offset += ifa.size();

            // 填充rtattr（IP地址）
            rtattr rta = new rtattr(buffer.share(offset));
            rta.rta_len = (byte) (rta.size() + address.length);
            rta.rta_type = (short) IFA_LOCAL;
            rta.write();
            offset += rta.size();

            buffer.write(offset, address, 0, address.length);
            offset += address.length;

            final sockaddr_nl.ByRef dest_addr = new sockaddr_nl.ByRef();
            dest_addr.nl_family = AF_NETLINK;
            dest_addr.nl_pid = 0;
            dest_addr.nl_groups = 0;

            final IOVec.ByRef ioVec = new IOVec.ByRef();
            ioVec.iov_base = buffer;
            ioVec.iov_len = offset;

            final MsgHdr msg = new MsgHdr();
            msg.msg_name = dest_addr;
            msg.msg_namelen = new NativeLong(dest_addr.size());
            msg.msg_iov = ioVec;
            msg.msg_iovlen = new NativeLong(1);
            msg.msg_control = null;
            msg.msg_controllen = new NativeLong(0);
            msg.msg_flags = 0;

            // final int written = API_INSTANTCE.sendmsg(sockfd, msg, 0);
            final int written = API_INSTANTCE.send(sockfd, hl.getPointer(), offset, 0);
            if (written < 0) {
                throwLastErrorException(Native.getLastError());
            }
        } finally {
            close(sockfd);
        }
    }

    private static int nlmsgAlign(final int len) {
        final int ALIGN = 4;  // NLMSG_ALIGN 通常为 4 字节对齐 ‌:ml-citation{ref="3,4" data="citationList"}
        return (len + ALIGN - 1) & ~(ALIGN - 1);
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

    // ------------------------ END IPv4 related ------------------------

    // ------------------------ START IPv6 related ------------------------


    private static void addInterfaceAddress6(final int fd, final String ifname,
                                             final Inet6Address addr, final int prefixLength) {
        // Wrong: sysctl net.ipv6.conf.all.disable_ipv6 --> 1: [13] Permission denied
        // sysctl net.ipv6.conf.all.disable_ipv6=0

        final in6_ifreq ifr6 = new in6_ifreq();
        ifr6.ifr6_addr = addr.getAddress();
        ifr6.ifr6_prefixlen = prefixLength;
        ifr6.ifr6_ifindex = if_nametoindex0(fd, ifname);

        // SIOCSIFADDR is append for IPv6
        ioctl0(fd, SIOCSIFADDR, ifr6);
    }

    private static void deleteInterfaceAddress6(final int fd, final String ifname, final Inet6Address addr, final int prefixLength) {
        // Wrong: sysctl net.ipv6.conf.all.disable_ipv6 --> 1: [13] Permission denied
        // sysctl net.ipv6.conf.all.disable_ipv6=0

        final in6_ifreq ifr6 = new in6_ifreq();
        ifr6.ifr6_addr = addr.getAddress();
        ifr6.ifr6_prefixlen = prefixLength;
        ifr6.ifr6_ifindex = if_nametoindex0(fd, ifname);

        ioctl0(fd, SIOCDIFADDR, ifr6);
    }

    // ------------------------ END IPv6 related ------------------------

    static ifaddrs getifaddrs0(final ifaddrs ifa) {
        if (getifaddrs(ifa) < 0) {
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

    private static int if_nametoindex0(final String ifname) {
        final int index = if_nametoindex(ifname);
        if (0 == index) {
            throwLastErrorException(Native.getLastError());
        }
        return index;
    }

    private static <S extends Structure> S ioctl0(final int fd, final NativeLong request, final S argp) {
        if (ioctl(fd, request, argp) < 0) {
            throwLastErrorException(Native.getLastError());
        }
        return argp;
    }

}
