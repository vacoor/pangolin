package com.github.pangolin.routing.server.tun.adapter.darwin;


import com.github.pangolin.routing.server.tun.adapter.InterfaceAddressEx;
import com.github.pangolin.routing.server.tun.adapter.NetworkInterfaceEx;
import com.github.pangolin.routing.server.tun.adapter.darwin.jna.If;
import com.github.pangolin.routing.server.tun.adapter.darwin.jna.If.*;
import com.google.common.collect.Lists;

import java.net.*;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.Socket.*;
import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.Sockio.*;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.LibC.*;

/**
 *
 */
public class DarwinNetworkInterfaceEx implements NetworkInterfaceEx {
    private final String ifname;

    public DarwinNetworkInterfaceEx(final String ifname) {
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
                flushInterfaceAddresses4(fd, ifname);
                addInterfaceAddress4(fd, ifname, (Inet4Address) addr, networkPrefixLength);
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
        final ifaddrs ifa = new ifaddrs();
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
                    final sockaddr_in sockaddr = new sockaddr_in(n.ifa_addr.getPointer());
                    final sockaddr_in netmask = null != n.ifa_netmask ? new sockaddr_in(n.ifa_netmask.getPointer()) : null;
                    final int prefix = null != netmask ? netmaskToPrefixLength(netmask.sin_addr) : 0;

                    interfaceAddresses.add(InterfaceAddressEx.of(toInetAddress(sockaddr.sin_addr), prefix));
                } else if (AF_INET6 == sa_family) {
                    final sockaddr_in6 sockaddr = new sockaddr_in6(n.ifa_addr.getPointer());
                    final sockaddr_in6 netmask = null != n.ifa_netmask ? new sockaddr_in6(n.ifa_netmask.getPointer()) : null;
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
//        ifr.ifr_ifru.ifru_addr.sin_len = (byte) ifr.ifr_ifru.ifru_addr.size();

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
        ifr.ifr_ifru.setType("ifru_addr");

        ioctl(fd, SIOCGIFNETMASK, ifr);

        final sockaddr_in netmask = ifr.ifr_ifru.ifru_addr;
        assert AF_INET == netmask.sin_family;
        return netmask.sin_addr;
    }

    private static void setInterfaceNetmask4(final int fd, final String ifname, final byte[] addr) {
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
        ifr.ifr_ifru.ifru_addr.sin_addr = addr;

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
        ifr.ifra_mask.sin_addr = cidrPrefixToNetmask(ipAddress.clone(), prefixLength);

        ioctl(fd, SIOCAIFADDR, ifr);
    }

    private static void deleteInterfaceIpAddress4(final int fd, final String ifname, final Inet4Address addr) {
        final Ifreq ifr = new Ifreq(ifname);
        ifr.ifr_ifru.setType("ifru_addr");
        ifr.ifr_ifru.ifru_addr.sin_family = AF_INET;
        ifr.ifr_ifru.ifru_addr.sin_port = 0;
        ifr.ifr_ifru.ifru_addr.sin_addr = addr.getAddress();

        ioctl(fd, SIOCDIFADDR, ifr);
    }

    // ------------------------ END IPv4 related ------------------------

    // ------------------------ START IPv6 related ------------------------


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
        ifr6.ifra_prefixmask.sin6_addr = cidrPrefixToNetmask(ipAddress.clone(), prefixLength);

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
        ifr6.ifra_prefixmask.sin6_addr = cidrPrefixToNetmask(ipAddress.clone(), prefixLength);

        /* important!!! */
        ifr6.ifra_lifetime.ia6t_vltime = in6_addrlifetime.ND6_INFINITE_LIFETIME;
        ifr6.ifra_lifetime.ia6t_pltime = in6_addrlifetime.ND6_INFINITE_LIFETIME;

        ioctl(fd, SIOCDIFADDR_IN6, ifr6);
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

    private static int netmaskToPrefixLength(final byte[] ipBytes) {
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


    public static void main(String[] args) throws Exception {
//        InetAddress byAddress = (InetAddress) Inet4Address.getByName("192.168.1.1");
//        Inet6Address byAddress = (Inet6Address) InetAddress.getByName("fd2c:8ee9:8bc:3a49:49ca:e99b:fc86:7fa2");
//        byte[] netmask = cidrPrefixToNetmask(byAddress.getAddress(), 64);
//        System.out.println(NetUtil.bytesToIpAddress(netmask));

        final DarwinNetworkInterfaceEx nix = new DarwinNetworkInterfaceEx("en0");
        System.out.println(nix.getInterfaceAddress4());
        System.out.println(nix.getInterfaceAddresses());

//        List<InterfaceAddressEx> interfaceAddresses = nix.getInterfaceAddresses();
//        for (InterfaceAddressEx interfaceAddress : interfaceAddresses) {
//            System.out.println(interfaceAddress);
//        }
    }
}
