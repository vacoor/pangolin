package com.github.pangolin.routing.beta.macos;

import com.github.pangolin.routing.beta.If.sockaddr_in;
import com.github.pangolin.routing.beta.InterfaceAddressEx;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;
import org.drasyl.channel.tun.jna.darwin.DarwinTunDevice;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.TimeUnit;

import static com.github.pangolin.routing.beta.If.IFNAMSIZ;
import static com.github.pangolin.routing.beta.macos.KernControl.CTLIOCGINFO;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.drasyl.channel.tun.jna.shared.LibC.*;
import static org.drasyl.channel.tun.jna.shared.Socket.AF_SYSTEM;
import static org.drasyl.channel.tun.jna.shared.Socket.SOCK_DGRAM;

public class MacTunUtils {
    static final int AF_INET = 2;
    static final int AF_INET6 = 30;

    static final int SYSPROTO_CONTROL = 2;
    static final String UTUN_CONTROL_NAME = "com.apple.net.utun_control";
    static final int UTUN_OPT_IFNAME = 2;
    public static final NativeLong SIOCGIFMTU = new NativeLong(0xc0206933L);
    public static final NativeLong SIOCSIFADDR = new NativeLong(0x8020690cL);


    public static void main(String[] args) throws Exception {
//        TunDevice dev = DarwinTunDevice.open("utun9", 0);
//        System.out.println(dev.localAddress());

//        System.exit(0);

        final String ifname = createDarwinTun("utun9");
        System.out.println("IFNAME:" + ifname);

//        System.out.println("MTU=" + getMtu(ifname));
        Inet4Address ipv4 = (Inet4Address) InetAddress.getByName("10.18.71.2");
        MacOsNetworkInterfaceEx mix = new MacOsNetworkInterfaceEx(ifname);
        mix.setInterfaceAddress(InterfaceAddressEx.of(ipv4, 32));

        System.out.println("IPv4 -> " + mix.getInterfaceAddresses());
        System.out.println("MTU -> " + mix.getMTU());


        /*
        final int sockfd = socket(AF_INET6, SOCK_DGRAM, 0);
        try {
            Inet6Address ipv6 = (Inet6Address) InetAddress.getByName("2001:da8:ecd1::1");
            MacOsNetworkInterfaceEx2.setInterfaceAddress6(sockfd, ifname, ipv6);
            System.out.println("IPv6 -> OK");
        }finally {
            close(sockfd);
        }
        */

        /*
        byte[] ip = getIpAddress(ifname);
        System.out.println(NetUtil.bytesToIpAddress(ip));

        setIpv4Netmask(ifname, InetAddress.getByName("255.255.0.0"));
        byte[] netmask = getIpv4Netmask(ifname);
        System.out.println(NetUtil.bytesToIpAddress(netmask));

        System.out.println("MTU: " + getMtu(ifname));
        */

        TimeUnit.SECONDS.sleep(30);
    }

    private static final String DEVICE_PREFIX = "utun";
    private static final IllegalArgumentException ILLEGAL_NAME_EXCEPTION = new IllegalArgumentException("Device name must be 'utun<index>' or null.");

    private static String createDarwinTun(String name) throws IOException, InterruptedException {
        final int index;
        if (name != null) {
            if (name.startsWith(DEVICE_PREFIX)) {
                try {
                    index = Integer.parseInt(name.substring(DEVICE_PREFIX.length()));
                }
                catch (final NumberFormatException e) {
                    throw ILLEGAL_NAME_EXCEPTION;
                }
            }
            else {
                throw ILLEGAL_NAME_EXCEPTION;
            }
        }
        else {
            index = 0;
        }

        // create socket
        final int fd = socket(AF_SYSTEM, SOCK_DGRAM, SYSPROTO_CONTROL);

        if (fd == -1) {
            throw new IOException("Create an endpoint for communication failed.");
        }

        // mark socket as utun device
        final KernControl.CtlInfo ctlInfo = new KernControl.CtlInfo(UTUN_CONTROL_NAME);
        ioctl(fd, CTLIOCGINFO, ctlInfo);

        // define address of socket
        final KernControl.SockaddrCtl address = new KernControl.SockaddrCtl(AF_SYSTEM, (short) SYSPROTO_CONTROL, ctlInfo.ctl_id, index);
        connect(fd, address, address.sc_len);

        // get socket name
        final DarwinTunDevice.SockName sockName = new DarwinTunDevice.SockName();
        final IntByReference sockNameLen = new IntByReference(DarwinTunDevice.SockName.LENGTH);
        getsockopt(fd, SYSPROTO_CONTROL, UTUN_OPT_IFNAME, sockName, sockNameLen);

            final String ifname = Native.toString(sockName.name, US_ASCII);
//        System.out.println("IF:" + ifname);
//        TimeUnit.SECONDS.sleep(15);
//        System.out.println("MTU=" + getMtu(fd, ifname));
        return ifname;
    }

}