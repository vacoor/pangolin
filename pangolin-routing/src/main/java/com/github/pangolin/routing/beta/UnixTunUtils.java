package com.github.pangolin.routing.beta;


import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import io.netty.util.NetUtil;
import org.drasyl.channel.tun.jna.shared.LibC;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static com.sun.jna.platform.linux.Fcntl.O_RDWR;
import static org.drasyl.channel.tun.jna.shared.LibC.*;
import static org.drasyl.channel.tun.jna.shared.Socket.SOCK_DGRAM;

public class UnixTunUtils {
    static final int AF_INET = 2;
    static final int AF_INET6 = 10;


    /*-
     * https://github.com/torvalds/linux/blob/b831f83e40a24f07c8dcba5be408d93beedc820f/include/uapi/linux/sockios.h#L80
     */

    /**
     * get PA address.
     */
    static final NativeLong SIOCGIFADDR = new NativeLong(0x8915);

    /**
     * set PA address.
     */
    static final NativeLong SIOCSIFADDR = new NativeLong(0x8916);

    /**
     * get network PA mask.
     */
    static final NativeLong SIOCGIFNETMASK = new NativeLong(0x891b);

    /**
     * set network PA mask.
     */
    static final NativeLong SIOCSIFNETMASK = new NativeLong(0x891c);

    /* get metric			*/
    static final NativeLong SIOCGIFMETRIC = new NativeLong(0x891d);

    /* set metric			*/
    static final NativeLong SIOCSIFMETRIC = new NativeLong(0x891e);

    /* get MTU size			*/
    static final NativeLong SIOCGIFMTU = new NativeLong(0x8921);

    /* set MTU size			*/
    static final NativeLong SIOCSIFMTU = new NativeLong(0x8922);
//            #define SIOCSIFNAME	0x8923		/* set interface name */
//            #define	SIOCSIFHWADDR	0x8924		/* set hardware address 	*/
//            #define SIOCGIFENCAP	0x8925		/* get/set encapsulations       */
//            #define SIOCSIFENCAP	0x8926
//            #define SIOCGIFHWADDR	0x8927		/* Get hardware address		*/


    static final NativeLong SIOCGIFINDEX = new NativeLong(0x8933);
    static final NativeLong SIOGIFINDEX = SIOCGIFINDEX;


    static final NativeLong TUNSETIFF = new NativeLong(0x400454caL);
    // TUN device (no Ethernet headers)
    static final short IFF_TUN = 0x0001;
    // do not provide packet information
    static final short IFF_NO_PI = 0x1000;

    public static void main(String[] args) throws Exception {
        final String ifname = createUnixTun("tun9");
        System.out.println(ifname);

        setIpAddress(ifname, InetAddress.getByName("10.18.71.2"));
        setIpAddress(ifname, InetAddress.getByName("2001:da8:ecd1::1"));

        System.out.println("IPv4 -> OK");

        byte[] ip = getIpAddress(ifname);
        System.out.println(NetUtil.bytesToIpAddress(ip));

        setIpv4Netmask(ifname, InetAddress.getByName("255.255.0.0"));
        byte[] netmask = getIpv4Netmask(ifname);
        System.out.println(NetUtil.bytesToIpAddress(netmask));

        System.out.println("MTU: " + getMtu(ifname));

        TimeUnit.SECONDS.sleep(30);
    }

    private static String createUnixTun(final String name) throws Exception {
        final int fd = LibC.open("/dev/net/tun", O_RDWR);
        if (fd == -1) {
            throw new IOException("Create an endpoint for communication failed.");
        }

        final If.Ifreq ifr = new If.Ifreq(name);
        ifr.ifr_ifru.setType("ifru_flags");
        ifr.ifr_ifru.ifru_flags = IFF_TUN | IFF_NO_PI;

        int code = ioctl(fd, TUNSETIFF, ifr);
        if (0 != code) {
            throw new IOException("Create tun device: " + code);
        }

        return Native.toString(ifr.ifr_name, StandardCharsets.US_ASCII);
    }


    public static byte[] getIpAddress(final String ifname) {
        final int fd = socket(AF_INET, SOCK_DGRAM, 0);
        try {
            final If.Ifreq ifr = new If.Ifreq(ifname);
            ifr.ifr_ifru.setType("ifru_addr");

            final int code = ioctl(fd, SIOCGIFADDR, ifr);
            if (0 != code) {
                throw new IllegalStateException("Error code: " + code);
            }

            final If.Ifreq.sockaddr_in sockaddr = (If.Ifreq.sockaddr_in) ifr.ifr_ifru.ifru_addr.getTypedValue(If.Ifreq.sockaddr_in.class);
//                final If.Ifreq.sockaddr_in sockaddr = ifr.ifr_ifru.ifru_addr;
            return sockaddr.sin_addr;
        } finally {
            close(fd);
        }
    }

    public static void setIpAddress(final String ifname, final InetAddress addr) {
        if (addr instanceof Inet4Address) {
            final int sockfd = socket(AF_INET, SOCK_DGRAM, 0);
            try {
                System.out.println("sockfd=" + sockfd);
                final If.Ifreq ifr = new If.Ifreq(ifname);
                ifr.ifr_ifru.setType("ifru_addr");

                final If.Ifreq.sockaddr_in sockaddr_in = new If.Ifreq.sockaddr_in();
                sockaddr_in.sin_family = AF_INET;
                sockaddr_in.sin_port = 0;
                sockaddr_in.sin_addr = addr.getAddress();

                ifr.ifr_ifru.ifru_addr.setType(If.Ifreq.sockaddr_in.class);
                ifr.ifr_ifru.ifru_addr.setTypedValue(sockaddr_in);
//            ifr.ifr_ifru.ifru_addr = sockaddr_in;

                final int code = ioctl(sockfd, SIOCSIFADDR, ifr);
                if (0 != code) {
                    throw new IllegalStateException("Error code: " + code);
                }
            } finally {
                close(sockfd);
            }
        } else if (addr instanceof Inet6Address) {
            final int sockfd = socket(AF_INET6, SOCK_DGRAM, 0);
            System.out.println("sockfd6=" + sockfd);

            /*
            final If.Ifreq ifr = new If.Ifreq(ifname);
            ifr.ifr_ifru.setType("ifru_addr");

            final If.Ifreq.sockaddr_in6 sockaddr_in = new If.Ifreq.sockaddr_in6();
            sockaddr_in.sin6_family = AF_INET6;
            sockaddr_in.sin6_port = 0;
            sockaddr_in.sin6_addr = addr.getAddress();
            // sockaddr_in.sin6_scope_id = ((Inet6Address) addr).getScopeId();

            ifr.ifr_ifru.ifru_addr.setType(If.Ifreq.sockaddr_in6.class);
            ifr.ifr_ifru.ifru_addr.setTypedValue(sockaddr_in);
//            ifr.ifr_ifru.ifru_addr = sockaddr_in;

            final int code = ioctl(sockfd, SIOCSIFADDR, ifr);
            */

            /*
            final If.Ifreq ifr = new If.Ifreq(ifname);
            ifr.ifr_ifru.setType("ifru_ifindex");
            final int ioctl = ioctl(sockfd, SIOGIFINDEX, ifr);

            ifr.ifr_ifru.setType("ifru_ifindex");
            int ifru_ivalue = ifr.ifr_ifru.ifru_ifindex;
            System.out.println("XX: " + ioctl);
            System.out.println(ifr.ifr_ifru);
            System.out.println("index6=" + ifru_ivalue);
            */

            final If.in6_ifreq ifr6 = new If.in6_ifreq();
            // FOR TEST
            ifr6.ifr6_ifindex = 8;
            // ifr6.ifr6_ifindex = ifr.ifru_ifindex;
            ifr6.ifr6_addr.sin6_family = AF_INET6;
            ifr6.ifr6_addr.sin6_port = 0;
            ifr6.ifr6_addr.sin6_addr = addr.getAddress();
            ifr6.ifr6_addr.sin6_scope_id = ((Inet6Address) addr).getScopeId();
            ifr6.ifr6_prefixlen = 64;

            final int code = ioctl(sockfd, SIOCSIFADDR, ifr6);
            if (0 != code) {
                throw new IllegalStateException("Error code: " + code);
            }
        } else {
            throw new UnsupportedOperationException();
        }
    }

    public static byte[] getIpv4Netmask(final String ifname) {
        final int fd = socket(AF_INET, SOCK_DGRAM, 0);
        try {
            final If.Ifreq ifr = new If.Ifreq(ifname);
            ifr.ifr_ifru.setType("ifru_netmask");

            final int code = ioctl(fd, SIOCGIFNETMASK, ifr);
            if (0 != code) {
                throw new IllegalStateException("Error code: " + code);
            }

            final If.Ifreq.sockaddr_in sockaddr = (If.Ifreq.sockaddr_in) ifr.ifr_ifru.ifru_netmask.getTypedValue(If.Ifreq.sockaddr_in.class);
//        final If.Ifreq.sockaddr_in sockaddr = ifr.ifr_ifru.ifru_netmask;
            return sockaddr.sin_addr;
        } finally {
            close(fd);
        }
    }

    public static void setIpv4Netmask(final String ifname, final InetAddress addr) {
        final int fd = socket(AF_INET, SOCK_DGRAM, 0);
        try {
            final If.Ifreq ifr = new If.Ifreq(ifname);
            ifr.ifr_ifru.setType("ifru_netmask");

            if (addr instanceof Inet4Address) {
                final If.Ifreq.sockaddr_in sockaddr_in = new If.Ifreq.sockaddr_in();
                sockaddr_in.sin_family = AF_INET;
                sockaddr_in.sin_port = 0;
                sockaddr_in.sin_addr = addr.getAddress();

                ifr.ifr_ifru.ifru_netmask.setType(If.Ifreq.sockaddr_in.class);
                ifr.ifr_ifru.ifru_netmask.setTypedValue(sockaddr_in);
                final int code = ioctl(fd, SIOCSIFNETMASK, ifr);
                if (0 != code) {
                    throw new IllegalStateException("Error code: " + code);
                }
            }
        } finally {
            close(fd);
        }
    }

    public static int getMtu(final String ifname) {
        final int fd = socket(AF_INET, SOCK_DGRAM, 0);
        try {
            final If.Ifreq ifr = new If.Ifreq(ifname);

            final int code = ioctl(fd, SIOCGIFMTU, ifr);
            if (0 != code) {
                throw new IllegalStateException("Error code: " + code);
            }
            return ifr.ifr_ifru.ifru_mtu;
        } finally {
            close(fd);
        }
    }

}