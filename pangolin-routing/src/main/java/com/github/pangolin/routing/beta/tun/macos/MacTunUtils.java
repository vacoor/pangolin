package com.github.pangolin.routing.beta.tun.macos;

import com.github.pangolin.routing.beta.InterfaceAddressEx;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.ptr.IntByReference;
import org.drasyl.channel.tun.jna.darwin.DarwinTunDevice;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.concurrent.TimeUnit;

import static com.github.pangolin.routing.beta.tun.macos.KernControl.CTLIOCGINFO;
import static com.github.pangolin.routing.beta.tun.macos.Socket.*;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.drasyl.channel.tun.jna.shared.LibC.*;

public class MacTunUtils {
    static final int SYSPROTO_CONTROL = 2;
    static final String UTUN_CONTROL_NAME = "com.apple.net.utun_control";
    static final int UTUN_OPT_IFNAME = 2;
    public static final NativeLong SIOCGIFMTU = new NativeLong(0xc0206933L);
    public static final NativeLong SIOCSIFADDR = new NativeLong(0x8020690cL);


    public static void main(String[] args) throws Exception {
        final String ifname = createDarwinTun("utun9");
        System.out.println("ifname: " + ifname);

        MacOsNetworkInterfaceEx nix = new MacOsNetworkInterfaceEx(ifname);
        System.out.println("MTU -> " + nix.getMTU());

        Inet4Address ipv4 = (Inet4Address) InetAddress.getByName("192.168.3.1");
        Inet4Address ipv4_2 = (Inet4Address) InetAddress.getByName("192.168.3.2");

        nix.setInterfaceAddress4(ipv4, 16);
        nix.addInterfaceAddress(InterfaceAddressEx.of(ipv4_2, 24));

        System.out.println("IPv4 -> " + nix.getInterfaceAddresses());
        System.out.println("OK");

        Inet6Address ipv6 = (Inet6Address) InetAddress.getByName("fd2c:8ee9:8bc:3a49:49ca:e99b:fc86:7fa2");
        nix.addInterfaceAddress(InterfaceAddressEx.of(ipv6, 64));

        System.out.println("IPv6 -> OK");

        TimeUnit.SECONDS.sleep(10);

        nix.flushInterfaceAddresses();

        System.out.println("Cleanup -> OK");

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
                } catch (final NumberFormatException e) {
                    throw ILLEGAL_NAME_EXCEPTION;
                }
            } else {
                throw ILLEGAL_NAME_EXCEPTION;
            }
        } else {
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