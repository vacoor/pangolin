package com.github.pangolin.routing.beta.macos;


import com.github.pangolin.routing.beta.If.*;
import com.github.pangolin.routing.beta.InterfaceAddressEx;
import com.github.pangolin.routing.beta.NetworkInterfaceEx;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import io.netty.util.NetUtil;
import org.pcap4j.core.Inets;

import java.net.*;
import java.util.Collections;
import java.util.List;

import static com.github.pangolin.routing.beta.If.IFNAMSIZ;
import static com.github.pangolin.routing.beta.linux.Socket.AF_INET;
import static com.github.pangolin.routing.beta.linux.Socket.SOCK_DGRAM;
import static com.github.pangolin.routing.beta.macos.Sockio.*;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.drasyl.channel.tun.jna.shared.LibC.*;
import static org.drasyl.channel.tun.jna.shared.LibC.ioctl;
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
        final int fd = fd();
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
        final int fd = fd();
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

    public static void setInterfaceAddress(final String ifname, final Inet4Address address) {
        int fd = fd();
        try {
            setInterfaceAddress(fd, ifname, address);
        } finally {
            close(fd);
        }
    }

    public static void setInterfaceAddress(final int fd, final String ifname, final Inet4Address address) {
        final ifaliasreq ifra = new ifaliasreq(ifname);
        ifra.ifra_addr.sin_family = Inets.AF_INET;
        ifra.ifra_addr.sin_port = 0;
        ifra.ifra_addr.sin_addr = address.getAddress();

        ifra.ifra_mask.sin_family = Inets.AF_INET;
        ifra.ifra_mask.sin_port = 0;
//        ifra.ifra_mask.sin_addr = getNetworkAddress(address.getAddress(), 32);
        ifra.ifra_mask.sin_addr = new byte[] {
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF
        };
        System.out.println(NetUtil.bytesToIpAddress(ifra.ifra_mask.sin_addr));

        ioctl(fd, SIOCSIFADDR, ifra);
//        ioctl(fd, SIOCAIFADDR, ifra);
    }

    static void setInterfaceAddress6(final int fd, final String ifname, final Inet6Address address) {
        final in6_aliasreq ifr6 = new in6_aliasreq(ifname);
        ifr6.ifra_addr.sin6_len = (byte) Native.getNativeSize(sockaddr_in6.class, ifr6.ifra_addr);
//        ifr6.ifra_addr.sin6_len = 16;
        ifr6.ifra_addr.sin6_family = AF_INET6;
        ifr6.ifra_addr.sin6_port = 0;
        ifr6.ifra_addr.sin6_addr = address.getAddress();

        System.out.println("Size: " + ifr6.ifra_addr.sin6_len);


//        ifr6.ifra_prefixmask.sin6_len = 16;
        ifr6.ifra_prefixmask.sin6_len = (byte) Native.getNativeSize(sockaddr_in6.class, ifr6.ifra_prefixmask);
        ifr6.ifra_prefixmask.sin6_family = AF_INET6;
        ifr6.ifra_prefixmask.sin6_port = 0;
        ifr6.ifra_prefixmask.sin6_addr = new byte[] {
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
                0, 0, 0, 0,
                0, 0, 0, 0
        };

        System.out.println("Size: " + ifr6.ifra_prefixmask.sin6_len);

        /* important!!! */
        ifr6.ifra_lifetime.ia6t_vltime = 0xFFFFFFFF;
        ifr6.ifra_lifetime.ia6t_pltime = 0xFFFFFFFF;

        System.out.println(NetUtil.bytesToIpAddress(ifr6.ifra_addr.sin6_addr));

//        ioctl(fd, SIOCSIFADDR, ifr6);
        ioctl(fd, SIOCAIFADDR_IN6, ifr6);
    }

    @Structure.FieldOrder({"ifra_name", "ifra_addr", "ifra_broadaddr", "ifra_mask"})
    public static class ifaliasreq extends Structure {
        public byte[] ifra_name = new byte[IFNAMSIZ];
        public sockaddr_in ifra_addr;
        public sockaddr_in ifra_broadaddr;
        public sockaddr_in ifra_mask;

        public ifaliasreq(final String name) {
            this.ifra_name = new byte[IFNAMSIZ];
            if (name != null) {
                final byte[] bytes = name.getBytes(US_ASCII);
                System.arraycopy(bytes, 0, this.ifra_name, 0, bytes.length);
            }
        }
    }

    /**
     * https://github.com/apple-oss-distributions/xnu/blob/main/bsd/netinet6/in6.h
     */
    @Structure.FieldOrder({"sin6_len", "sin6_family", "sin6_port", "sin6_flowinfo", "sin6_addr", "sin6_scope_id"})
    public static class sockaddr_in6 extends Structure {

        public sockaddr_in6() {
        }

        public sockaddr_in6(Pointer p) {
            super(p);
            read();
        }

        public byte sin6_len;
        public short sin6_family;
        public short sin6_port;
        public int sin6_flowinfo;
        public byte[] sin6_addr = new byte[16];
        public int sin6_scope_id;
    }

    /**
     * https://github.com/apple/darwin-xnu/blob/main/bsd/netinet6/in6_var.h
     */
    @Structure.FieldOrder({"ifra_name", "ifra_addr", "ifra_dstaddr", "ifra_prefixmask", "ifra_flags", "ifra_lifetime"})
    public static class in6_aliasreq extends Structure {
        public byte[] ifra_name = new byte[IFNAMSIZ];
        public sockaddr_in6 ifra_addr;
        public sockaddr_in6 ifra_dstaddr;
        public sockaddr_in6 ifra_prefixmask;
        public int ifra_flags;
        public in6_addrlifetime ifra_lifetime;

        public in6_aliasreq(final String name) {
            this.ifra_name = new byte[IFNAMSIZ];
            if (name != null) {
                final byte[] bytes = name.getBytes(US_ASCII);
                System.arraycopy(bytes, 0, this.ifra_name, 0, bytes.length);
            }
        }
    }

    @Structure.FieldOrder({"ia6t_expire", "ia6t_preferred", "ia6t_vltime", "ia6t_pltime"})
    public static class in6_addrlifetime extends Structure {
        public long ia6t_expire;
        public long ia6t_preferred;
        public int ia6t_vltime = 0xFFFFFFFF;
        public int ia6t_pltime = 0xFFFFFFFF;
    }
}
