package com.github.pangolin.routing.beta.linux;

import com.github.pangolin.routing.beta.linux.If.Ifreq;
import com.github.pangolin.routing.beta.linux.If.in6_ifreq;
import com.github.pangolin.routing.beta.linux.If.sockaddr_in;
import com.github.pangolin.routing.beta.InterfaceAddressEx;
import com.github.pangolin.routing.beta.NetworkInterfaceEx;

import java.net.*;
import java.util.Collections;
import java.util.List;

import static com.github.pangolin.routing.beta.linux.Socket.*;
import static com.github.pangolin.routing.beta.linux.Sockios.*;
import static org.drasyl.channel.tun.jna.shared.LibC.*;

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
        final int fd = fd();
        try {
            final InetAddress ipAddress = toInetAddress(getInterfaceIpAddress(fd, ifname));
            final byte[] interfaceNetmask = getInterfaceNetmask(fd, ifname);
            final int prefix = subnetMaskToPrefix(ipAddressToInt(interfaceNetmask));
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
        if (!(addr instanceof Inet4Address)) {
            throw new UnsupportedOperationException();
        }
        final int fd = fd();
        try {
            setInterfaceIpAddress(fd, ifname, (Inet4Address) addr);
            byte[] bytes = subnetMaskToAddress(prefixToSubnetMask(networkPrefixLength));
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
        return new byte[] {
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
        final int fd = fd();
        try {
            return getMtu(fd, ifname);
        } finally {
            close(fd);
        }
    }

    private static int fd() {
        return socket(AF_INET, SOCK_DGRAM, 0);
    }

    private static byte[] getInterfaceIpAddress(final int fd, final String ifname) {
        final Ifreq ifr = new Ifreq(ifname);
        ifr.ifr_ifru.setType("ifru_addr");

        ioctl(fd, SIOCGIFADDR, ifr);

        final sockaddr_in addr = ifr.ifr_ifru.ifru_addr;
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

    private static byte[] getInterfaceNetmask(final int fd, final String ifname) {
        final Ifreq ifr = new Ifreq(ifname);
        ifr.ifr_ifru.setType("ifru_netmask");

        ioctl(fd, SIOCGIFNETMASK, ifr);

        final sockaddr_in netmask = ifr.ifr_ifru.ifru_netmask;
        assert AF_INET == netmask.sin_family;
        return netmask.sin_addr;
    }

    private static void setInterfaceNetmask(final int fd, final String ifname, final Inet4Address addr) {
        final Ifreq ifr = new Ifreq(ifname);
        ifr.ifr_ifru.setType("ifru_netmask");
        ifr.ifr_ifru.ifru_netmask.sin_family = AF_INET;
        ifr.ifr_ifru.ifru_netmask.sin_port = 0;
        ifr.ifr_ifru.ifru_netmask.sin_addr = addr.getAddress();

        ioctl(fd, SIOCSIFNETMASK, ifr);
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

    static void setInterfaceAddress6(final int fd, final String ifname, final Inet6Address addr, final int prefixLength) {
        final If.Ifreq ifr = new If.Ifreq(ifname);
        ifr.ifr_ifru.setType("ifru_ifindex");
        ioctl(fd, SIOGIFINDEX, ifr);
        System.out.println("IFR index=" + ifr.ifr_ifru.ifru_ifindex);

        final in6_ifreq ifr6 = new in6_ifreq();
        ifr6.ifr6_ifindex = ifr.ifr_ifru.ifru_ifindex;

        ifr6.ifr6_addr.sin6_family = AF_INET6;
        ifr6.ifr6_addr.sin6_port = 0;
        ifr6.ifr6_addr.sin6_addr = addr.getAddress();
        ifr6.ifr6_addr.sin6_scope_id = addr.getScopeId();
        ifr6.ifr6_prefixlen = prefixLength;
        ioctl(fd, Sockios.SIOCSIFADDR, ifr6);
    }

}
