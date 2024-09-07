package com.github.pangolin.routing.beta;

import com.sun.jna.Native;
import com.sun.jna.ptr.IntByReference;
import org.drasyl.channel.tun.jna.darwin.DarwinTunDevice;

import java.util.concurrent.TimeUnit;

import static com.github.pangolin.routing.beta.KernControl.CTLIOCGINFO;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.drasyl.channel.tun.jna.shared.LibC.*;
import static org.drasyl.channel.tun.jna.shared.Socket.AF_SYSTEM;
import static org.drasyl.channel.tun.jna.shared.Socket.SOCK_DGRAM;

public class MacTunUtils {
    static final int SYSPROTO_CONTROL = 2;
    static final String UTUN_CONTROL_NAME = "com.apple.net.utun_control";
    static final int UTUN_OPT_IFNAME = 2;

    public static void main(String[] args) throws Exception {
        final String ifname = createDarwinTun(9);
        System.out.println(ifname);

        /*
        setIpAddress(ifname, InetAddress.getByName("10.18.71.2"));
        System.out.println("IPv4 -> OK");

        byte[] ip = getIpAddress(ifname);
        System.out.println(NetUtil.bytesToIpAddress(ip));

        setIpv4Netmask(ifname, InetAddress.getByName("255.255.0.0"));
        byte[] netmask = getIpv4Netmask(ifname);
        System.out.println(NetUtil.bytesToIpAddress(netmask));

        System.out.println("MTU: " + getMtu(ifname));
        */

        TimeUnit.SECONDS.sleep(30);
    }

    private static String createDarwinTun(int index) {
        // create socket
        final int fd = socket(AF_SYSTEM, SOCK_DGRAM, SYSPROTO_CONTROL);

        // mark socket as utun device
        final KernControl.CtlInfo ctlInfo = new KernControl.CtlInfo(UTUN_CONTROL_NAME);
        ioctl(fd, CTLIOCGINFO, ctlInfo);

        // define address of socket
        final KernControl.SockaddrCtl address = new KernControl.SockaddrCtl(
                AF_SYSTEM, (short) SYSPROTO_CONTROL, ctlInfo.ctl_id, index
        );
        connect(fd, address, address.sc_len);

        // get socket name
        final DarwinTunDevice.SockName sockName = new DarwinTunDevice.SockName();
        final IntByReference sockNameLen = new IntByReference(DarwinTunDevice.SockName.LENGTH);
        getsockopt(fd, SYSPROTO_CONTROL, UTUN_OPT_IFNAME, sockName, sockNameLen);

        return Native.toString(sockName.name, US_ASCII);
    }
}