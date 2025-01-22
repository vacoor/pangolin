package com.github.pangolin.tun.net.darwin;

import static com.github.pangolin.tun.net.darwin.jna.KernControl.CTLIOCGINFO;
import static com.github.pangolin.tun.net.darwin.jna.Socket.AF_SYSTEM;
import static com.github.pangolin.tun.net.darwin.jna.Socket.SOCK_DGRAM;
import static java.nio.charset.StandardCharsets.US_ASCII;

import com.github.pangolin.tun.net.AbstractTunAdapter;
import com.github.pangolin.tun.net.InterfaceAddressEx;
import com.github.pangolin.tun.net.darwin.jna.KernControl.CtlInfo;
import com.github.pangolin.tun.net.darwin.jna.KernControl.SockaddrCtl;
import com.github.pangolin.tun.net.linux.jna.LibC2;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.concurrent.TimeUnit;

public class DarwinTunAdapter extends AbstractTunAdapter<DarwinNetworkInterfaceEx> {
    static final int SYSPROTO_CONTROL = 2;
    static final String UTUN_CONTROL_NAME = "com.apple.net.utun_control";
    static final int UTUN_OPT_IFNAME = 2;
    public static final NativeLong SIOCGIFMTU = new NativeLong(0xc0206933L);
    public static final NativeLong SIOCSIFADDR = new NativeLong(0x8020690cL);

    private static final String DEVICE_PREFIX = "utun";
    private static final String ILLEGAL_NAME_EXCEPTION = "Device name must be 'utun<index>' or null.";

    private final int fd;
    private final String ifname;

    protected DarwinTunAdapter(final int fd, final String ifname) {
        super(new DarwinNetworkInterfaceEx(ifname));
        this.fd = fd;
        this.ifname = ifname;
    }


    public int getMTU() throws SocketException {
        return nix.getMTU();
    }

    public void close() {
        LibC2.INSTANCE.close(fd);
    }

    private static DarwinTunAdapter open(String name) throws IOException, InterruptedException {
        final int index = checkName(name);

        // create socket
        final int fd = LibC2.INSTANCE.socket(AF_SYSTEM, SOCK_DGRAM, SYSPROTO_CONTROL);
        if (fd == -1) {
            throw new IOException("Create an endpoint for communication failed.");
        }

        // mark socket as utun device
        final CtlInfo ctlInfo = new CtlInfo(UTUN_CONTROL_NAME);
        LibC2.INSTANCE.ioctl(fd, CTLIOCGINFO, ctlInfo);

        // define address of socket
        final SockaddrCtl address = new SockaddrCtl(AF_SYSTEM, (short) SYSPROTO_CONTROL, ctlInfo.ctl_id, index);
        LibC2.INSTANCE.connect(fd, address, address.sc_len);

        // get socket name
        final SockName sockName = new SockName();
        final IntByReference sockNameLen = new IntByReference(SockName.LENGTH);
        LibC2.INSTANCE.getsockopt(fd, SYSPROTO_CONTROL, UTUN_OPT_IFNAME, sockName, sockNameLen);

        final String ifname = Native.toString(sockName.name, US_ASCII);
        return new DarwinTunAdapter(fd, ifname);
    }

    @SuppressWarnings({"java:S116", "java:S1104", "java:S2160"})
    @Structure.FieldOrder({"name"})
    public static class SockName extends Structure {
        public static final int LENGTH = 16;
        public byte[] name = new byte[LENGTH];
    }

    private static int checkName(final String name) {
        final int index;
        if (name != null) {
            if (name.startsWith(DEVICE_PREFIX)) {
                try {
                    index = Integer.parseInt(name.substring(DEVICE_PREFIX.length()));
                } catch (final NumberFormatException e) {
                    throw new IllegalArgumentException(ILLEGAL_NAME_EXCEPTION);
                }
            } else {
                throw new IllegalArgumentException(ILLEGAL_NAME_EXCEPTION);
            }
        } else {
            index = 0;
        }
        return index;
    }

    public static void main(String[] args) throws Exception {
        final DarwinTunAdapter adapter = open("utun9");
        final String ifname = adapter.ifname;
        System.out.println("ifname: " + ifname);

        System.out.println("MTU -> " + adapter.getMTU());

        Inet4Address ipv4 = (Inet4Address) InetAddress.getByName("192.168.3.1");
        Inet4Address ipv4_2 = (Inet4Address) InetAddress.getByName("192.168.3.2");

        adapter.setInterfaceAddress(InterfaceAddressEx.of(ipv4, 16));
        adapter.addInterfaceAddress(InterfaceAddressEx.of(ipv4_2, 24));

        System.out.println("IPv4 -> " + adapter.getInterfaceAddresses());
        System.out.println("OK");

        Inet6Address ipv6 = (Inet6Address) InetAddress.getByName("fd2c:8ee9:8bc:3a49:49ca:e99b:fc86:7fa2");
        adapter.addInterfaceAddress(InterfaceAddressEx.of(ipv6, 64));

        System.out.println("IPv6 -> OK");

        TimeUnit.SECONDS.sleep(10);

        adapter.flushInterfaceAddresses();

        System.out.println("Cleanup -> OK");

        TimeUnit.SECONDS.sleep(30);
    }
}