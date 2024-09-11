package com.github.pangolin.routing.beta.linux;


import static com.github.pangolin.routing.beta.linux.Socket.AF_INET;
import static com.github.pangolin.routing.beta.linux.Socket.AF_INET6;
import static com.sun.jna.platform.linux.Fcntl.O_RDWR;
import static org.drasyl.channel.tun.jna.shared.LibC.ioctl;

import com.github.pangolin.routing.beta.linux.If.Ifreq;
import com.github.pangolin.routing.beta.InterfaceAddressEx;
import com.github.pangolin.routing.beta.linux.If.ifaddrs;
import com.github.pangolin.routing.beta.linux.If.sockaddr_in;
import com.github.pangolin.routing.beta.linux.If.sockaddr_in6;
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

        final LinuxNetworkInterfaceEx nix = new LinuxNetworkInterfaceEx(ifname);
        System.out.println("MTU -> " + nix.getMTU());

        nix.setInterfaceAddress(InterfaceAddressEx.of("192.168.1.2", 16));
        nix.setInterfaceAddress(InterfaceAddressEx.of("192.168.1.3", 16));

        System.out.println("Set IPv4 -> " + nix.getInterfaceAddresses());

        nix.addInterfaceAddress(InterfaceAddressEx.of("192.168.1.4", 16));
        System.out.println("Add IPv4 -> " + nix.getInterfaceAddresses());

//        TimeUnit.SECONDS.sleep(10);

//        nix.deleteInterfaceAddress(InterfaceAddressEx.of("192.168.1.4", 16));

        Inet6Address ipv6 = (Inet6Address) InetAddress.getByName("fd2c:8ee9:8bc:3a49:49ca:e99b:fc86:7fa2");
        nix.addInterfaceAddress(InterfaceAddressEx.of(ipv6, 64));

        System.out.println("IPv6 -> OK");

        ifaddrs ifa = new ifaddrs();
        ifa.ifa_name = ifname;
        int code = LibC2.INSTANCE.getifaddrs(ifa);

        for(ifaddrs node = ifa; null != node; node = node.ifa_next) {
//            final String name = Native.toString(node.ifa_name, StandardCharsets.US_ASCII);
            System.out.println("------------");
            String name = node.ifa_name;
            System.out.println(name);
            if (null == node.ifa_addr) {
                continue;
            }

            System.out.println(ifa.ifa_flags);
            short sa_family = node.ifa_addr.sa_family;
            if (AF_INET == sa_family) {
                final sockaddr_in sockaddr_in = (If.sockaddr_in) node.ifa_addr.getTypedValue(sockaddr_in.class);
                System.out.println(NetUtil.bytesToIpAddress(sockaddr_in.sin_addr));
                System.out.println(LinuxNetworkInterfaceEx.netmaskToPrefixLength(sockaddr_in.sin_addr));
            } else if (AF_INET6 == sa_family) {
                final sockaddr_in6 sockaddr_in = (If.sockaddr_in6) node.ifa_addr.getTypedValue(sockaddr_in6.class);
                System.out.println(NetUtil.bytesToIpAddress(sockaddr_in.sin6_addr));
                System.out.println(LinuxNetworkInterfaceEx.netmaskToPrefixLength(sockaddr_in.sin6_addr));
            }

            sa_family = null != node.ifa_netmask ? node.ifa_netmask.sa_family : -1;
            if (AF_INET == sa_family) {
                final sockaddr_in sockaddr_in = (If.sockaddr_in) node.ifa_netmask.getTypedValue(sockaddr_in.class);
                System.out.println(NetUtil.bytesToIpAddress(sockaddr_in.sin_addr));
                System.out.println(LinuxNetworkInterfaceEx.netmaskToPrefixLength(sockaddr_in.sin_addr));
            } else if (AF_INET6 == sa_family) {
                final sockaddr_in6 sockaddr_in = (If.sockaddr_in6) node.ifa_netmask.getTypedValue(sockaddr_in6.class);
                System.out.println(NetUtil.bytesToIpAddress(sockaddr_in.sin6_addr));
                System.out.println(LinuxNetworkInterfaceEx.netmaskToPrefixLength(sockaddr_in.sin6_addr));
            }
            System.out.println("------------");
        }

        LibC2.INSTANCE.freeifaddrs(ifa);

        TimeUnit.SECONDS.sleep(10);

        nix.deleteInterfaceAddress(InterfaceAddressEx.of(ipv6, 64));
        System.out.println("IPv6 -> Clean");

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


}