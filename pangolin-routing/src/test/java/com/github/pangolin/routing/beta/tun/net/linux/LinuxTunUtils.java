package com.github.pangolin.routing.beta.tun.net.linux;


import static com.sun.jna.platform.linux.Fcntl.O_RDWR;
import static org.drasyl.channel.tun.jna.shared.LibC.ioctl;

import com.github.pangolin.routing.beta.tun.net.linux.If.Ifreq;
import com.github.pangolin.routing.beta.tun.net.InterfaceAddressEx;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;

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

        Inet4Address ipv4 = (Inet4Address) InetAddress.getByName("192.168.3.1");
        Inet4Address ipv4_2 = (Inet4Address) InetAddress.getByName("192.168.3.2");

        nix.setInterfaceAddress4(ipv4, 16);
        nix.addInterfaceAddress(InterfaceAddressEx.of(ipv4_2, 24));

        System.out.println("IPv4 -> " + nix.getInterfaceAddresses());
        System.out.println("OK");

        Inet6Address ipv6 = (Inet6Address) InetAddress.getByName("fd2c:8ee9:8bc:3a49:49ca:e99b:fc86:7fa2");
        Inet6Address ipv62 = (Inet6Address) InetAddress.getByName("fd2c:8ee9:8bc:3a49:49ca:e99b:fc86:7fa3");
        nix.addInterfaceAddress(InterfaceAddressEx.of(ipv6, 64));
        nix.addInterfaceAddress(InterfaceAddressEx.of(ipv62, 64));

        System.out.println("IPv6 -> OK");

        TimeUnit.SECONDS.sleep(10);

        nix.flushInterfaceAddresses();

        System.out.println("Cleanup -> OK");

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