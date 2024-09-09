package com.github.pangolin.routing.beta;

import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;
import org.drasyl.channel.tun.jna.darwin.DarwinTunDevice;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.concurrent.TimeUnit;

import static com.github.pangolin.routing.beta.If.IFNAMSIZ;
import static com.github.pangolin.routing.beta.KernControl.CTLIOCGINFO;
import static com.github.pangolin.routing.beta.UnixTunUtils.AF_INET;
import static com.github.pangolin.routing.beta.UnixTunUtils.SIOCSIFADDR;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.drasyl.channel.tun.jna.shared.LibC.*;
import static org.drasyl.channel.tun.jna.shared.Socket.AF_SYSTEM;
import static org.drasyl.channel.tun.jna.shared.Socket.SOCK_DGRAM;

public class MacTunUtils {
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

        setIpv4Address(ifname, (Inet4Address) InetAddress.getByName("10.18.71.2"));
        System.out.println("IPv4 -> OK");

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
        System.out.println("IF:" + ifname);
        TimeUnit.SECONDS.sleep(15);
        System.out.println("MTU=" + getMtu(fd, ifname));
        return ifname;
    }

    public static int getMtu(final int fd, final String ifname) {
//        final int fd = socket(AF_INET, SOCK_DGRAM, 0);
        /*
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
        */
        // get mtu
        final org.drasyl.channel.tun.jna.shared.If.Ifreq ifreq = new org.drasyl.channel.tun.jna.shared.If.Ifreq(ifname);
        ioctl(fd, SIOCGIFMTU, ifreq);
        return ifreq.ifr_ifru.ifru_mtu;
    }

    private static void setIpv4Address(final String ifname, final Inet4Address addr) {
        final int sockfd = socket(AF_INET, SOCK_DGRAM, 0);
        try {
            System.out.println("sockfd=" + sockfd);
            final ifaliasreq ifra = new ifaliasreq(ifname);

            final If.Ifreq.sockaddr_in sockaddr_in = new If.Ifreq.sockaddr_in();
            sockaddr_in.sin_family = AF_INET;
            sockaddr_in.sin_port = 0;
            sockaddr_in.sin_addr = addr.getAddress();

            ifra.ifra_addr = sockaddr_in;
//            ifr.ifr_ifru.ifru_addr = sockaddr_in;

            final int code = ioctl(sockfd, SIOCSIFADDR, ifra);
            if (0 != code) {
                throw new IllegalStateException("Error code: " + code);
            }
        } finally {
            close(sockfd);
        }
    }

    @Structure.FieldOrder({ "ifra_name", "ifra_addr", "ifra_broadaddr", "ifra_mask" })
    public static class ifaliasreq extends Structure {
        public byte[] ifra_name = new byte[IFNAMSIZ];
        public If.Ifreq.sockaddr_in ifra_addr;
        public If.Ifreq.sockaddr_in ifra_broadaddr;
        public If.Ifreq.sockaddr_in ifra_mask;

        public ifaliasreq(final String name) {
            this.ifra_name = new byte[IFNAMSIZ];
            if (name != null) {
                final byte[] bytes = name.getBytes(US_ASCII);
                System.arraycopy(bytes, 0, this.ifra_name, 0, bytes.length);
            }
        }

    }

}