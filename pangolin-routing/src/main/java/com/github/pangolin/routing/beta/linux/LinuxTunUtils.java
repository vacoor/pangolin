package com.github.pangolin.routing.beta.linux;


import static com.github.pangolin.routing.beta.linux.Socket.AF_INET;
import static com.github.pangolin.routing.beta.linux.Socket.AF_INET6;
import static com.github.pangolin.routing.beta.linux.Sockios.SIOGIFINDEX;
import static com.sun.jna.platform.linux.Fcntl.O_RDWR;
import static org.drasyl.channel.tun.jna.shared.LibC.close;
import static org.drasyl.channel.tun.jna.shared.LibC.ioctl;
import static org.drasyl.channel.tun.jna.shared.LibC.socket;
import static org.drasyl.channel.tun.jna.shared.Socket.SOCK_DGRAM;

import com.github.pangolin.routing.beta.If;
import com.github.pangolin.routing.beta.If.Ifreq;
import com.github.pangolin.routing.beta.If.in6_ifreq;
import com.github.pangolin.routing.beta.If.sockaddr_in;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import io.netty.util.NetUtil;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.drasyl.channel.tun.jna.shared.LibC;

public class LinuxTunUtils {
    static final NativeLong TUNSETIFF = new NativeLong(0x400454caL);
    // TUN device (no Ethernet headers)
    static final short IFF_TUN = 0x0001;
    // do not provide packet information
    static final short IFF_NO_PI = 0x1000;

    public static void main(String[] args) throws Exception {
        final String ifname = createUnixTun("tun9");
        System.out.println(ifname);

        setIpAddress(ifname, InetAddress.getByName("10.18.71.2"));

        System.out.println("IPv4 -> OK");

        byte[] ip = getIpAddress(ifname);
        System.out.println(NetUtil.bytesToIpAddress(ip));

        setIpv4Netmask(ifname, InetAddress.getByName("255.255.0.0"));
        byte[] netmask = getIpv4Netmask(ifname);
        System.out.println(NetUtil.bytesToIpAddress(netmask));

        System.out.println("MTU: " + getMtu(ifname));

        setIpAddress(ifname, InetAddress.getByName("fec2::22"));
        System.out.println("IPv6 -> OK");

        TimeUnit.SECONDS.sleep(30);
    }

    private static String createUnixTun(final String name) throws Exception {
        final int fd = LibC.open("/dev/net/tun", O_RDWR);
        if (fd == -1) {
            throw new IOException("Create an endpoint for communication failed.");
        }

        final Ifreq ifr = new Ifreq(name);
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
          return LinuxNetworkInterfaceEx.getIpAddress(fd, ifname);
        } finally {
            close(fd);
        }
    }

    public static void setIpAddress(final String ifname, final InetAddress addr) {
        if (addr instanceof Inet4Address) {
            final int sockfd = socket(AF_INET, SOCK_DGRAM, 0);
            try {
                LinuxNetworkInterfaceEx.setIpAddress(sockfd, ifname, (Inet4Address) addr);
            } finally {
                close(sockfd);
            }
        } else if (addr instanceof Inet6Address) {
            final int sockfd = socket(AF_INET6, SOCK_DGRAM, 0);
            try {
                LinuxNetworkInterfaceEx.setInterfaceAddress6(sockfd, ifname, (Inet6Address) addr, 64);
            } finally {
                close(sockfd);
            }
        } else {
            throw new UnsupportedOperationException();
        }
    }

    public static byte[] getIpv4Netmask(final String ifname) {
        final int fd = socket(AF_INET, SOCK_DGRAM, 0);
        try {
          return LinuxNetworkInterfaceEx.getNetmask(fd, ifname);
        } finally {
            close(fd);
        }
    }

    public static void setIpv4Netmask(final String ifname, final InetAddress addr) {
        final int fd = socket(AF_INET, SOCK_DGRAM, 0);
        try {
          LinuxNetworkInterfaceEx.setNetmask(fd, ifname, (Inet4Address) addr);
        } finally {
            close(fd);
        }
    }

    public static int getMtu(final String ifname) {
        final int fd = socket(AF_INET, SOCK_DGRAM, 0);
        try {
          return LinuxNetworkInterfaceEx.getMtu(fd, ifname);
        } finally {
            close(fd);
        }
    }

}