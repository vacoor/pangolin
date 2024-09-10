package com.github.pangolin.routing.beta.linux;


import static com.sun.jna.platform.linux.Fcntl.O_RDWR;
import static org.drasyl.channel.tun.jna.shared.LibC.ioctl;

import com.github.pangolin.routing.beta.linux.If.Ifreq;
import com.github.pangolin.routing.beta.InterfaceAddressEx;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;

import java.io.IOException;
import java.net.Inet4Address;
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

        Inet4Address ipv4 = (Inet4Address) InetAddress.getByName("10.18.71.2");
        final LinuxNetworkInterfaceEx lix = new LinuxNetworkInterfaceEx(ifname);
        lix.setInterfaceAddress(InterfaceAddressEx.of(ipv4, 16));

        System.out.println("IPv4 -> " + lix.getInterfaceAddresses());
        System.out.println("MTU -> " + lix.getMTU());

        /*
        setIpAddress(ifname, ipv4);
        setIpv4Netmask(ifname, InetAddress.getByName("255.255.0.0"));

        byte[] ip = getIpAddress(ifname);
        System.out.println(NetUtil.bytesToIpAddress(ip));


        byte[] netmask = getIpv4Netmask(ifname);
        System.out.println(NetUtil.bytesToIpAddress(netmask));

        System.out.println("IPv4 -> OK");

        System.out.println("MTU: " + getMtu(ifname));


        setIpAddress(ifname, InetAddress.getByName("fec2::22"));
        System.out.println("IPv6 -> OK");
        */

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