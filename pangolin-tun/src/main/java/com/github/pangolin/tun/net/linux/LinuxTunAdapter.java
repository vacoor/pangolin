package com.github.pangolin.tun.net.linux;


import static com.github.pangolin.tun.net.darwin.jna.Sockio.SIOCSIFFLAGS;
import static com.github.pangolin.tun.net.linux.jna.If.IFNAMSIZ;
import static com.sun.jna.platform.linux.Fcntl.O_RDWR;
import static org.drasyl.channel.tun.jna.shared.LibC.socket;
import static org.drasyl.channel.tun.jna.shared.Socket.AF_INET;
import static org.drasyl.channel.tun.jna.shared.Socket.SOCK_DGRAM;

import com.github.pangolin.tun.net.AbstractTunAdapter;
import com.github.pangolin.tun.net.InterfaceAddressEx;
import com.github.pangolin.tun.net.linux.jna.If.Ifreq;
import com.github.pangolin.tun.net.linux.jna.LibC2;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import io.netty.util.NetUtil;
import org.pcap4j.packet.IpSelector;
import org.pcap4j.packet.Packet;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.concurrent.TimeUnit;

public class LinuxTunAdapter extends AbstractTunAdapter<LinuxNetworkInterfaceEx> {
    static final NativeLong TUNSETIFF = new NativeLong(0x400454caL);
    // TUN device (no Ethernet headers)
    static final short IFF_TUN = 0x0001;
    // do not provide packet information
    static final short IFF_NO_PI = 0x1000;

    private final int fd;
    private final String name;

    public LinuxTunAdapter(final int fd, final String ifname) {
        super(new LinuxNetworkInterfaceEx(ifname));
        this.fd = fd;
        this.name = ifname;
    }

    public int getMTU() {
        return nix.getMTU();
    }

    public void setMTU(final int mtu) {
        nix.setMTU(mtu);
    }

    @Override
    public byte[] readPacket() throws IOException {
        System.out.println("READ...");
        final int mtu = nix.getMTU();
        final int capacity = mtu;

        final ByteBuffer buffer = ByteBuffer.allocateDirect(capacity);

        // read from socket
        final int bytesRead = LibC2.INSTANCE.read(fd, buffer, mtu);

        final byte[] bytes = new byte[bytesRead];
        final int ipVersion = bytes[0] >> 4;
//        buffer.flip();
        buffer.get(bytes);
        System.out.println("OVER...");
        return bytes;
    }

    @Override
    public void writePacket(byte[] packet) {
        writePacket(packet, packet.length);
    }

    public void writePacket(byte[] packet, int len) {
         LibC2.INSTANCE.write(fd, packet, len);
    }


    @Override
    public void close() {
        // close tun device.
        LibC2.INSTANCE.close(fd);
    }

    public static LinuxTunAdapter open(final String tunName) throws Exception {
        final String nameToUse = checkDeviceName(tunName);

        // open tun device.
        final int fd = LibC2.INSTANCE.open("/dev/net/tun", O_RDWR);
        if (fd == -1) {
            throw new IOException("Create an endpoint for communication failed.");
        }

        // configure/create actual tun device.
        final Ifreq ifr = new Ifreq(nameToUse);
        ifr.ifr_ifru.setType("ifru_flags");
        ifr.ifr_ifru.ifru_flags = IFF_TUN | IFF_NO_PI;

        final int code = LibC2.INSTANCE.ioctl(fd, TUNSETIFF, ifr);
        if (0 != code) {
            throw new IOException("Create tun device: " + code);
        }

        final String ifname = Native.toString(ifr.ifr_name, StandardCharsets.US_ASCII);

        //
        /*
        short IFF_UP = 0x1;
        final int s = socket(AF_INET, SOCK_DGRAM, 0);
        final Ifreq ifr2 = new Ifreq(nameToUse);
        ifr2.ifr_ifru.setType("ifru_flags");
        ifr2.ifr_ifru.ifru_flags = IFF_UP;
        final int code2 = LibC2.INSTANCE.ioctl(s, SIOCSIFFLAGS, ifr2);
        if (0 != code2) {
            throw new IOException("Create tun device: " + code);
        }
        */


        return new LinuxTunAdapter(fd, ifname);
    }

    private static String checkDeviceName(final String name) {
        if (name.length() > IFNAMSIZ || !StandardCharsets.US_ASCII.newEncoder().canEncode(name)) {
            throw new IllegalArgumentException(String.format("Device name must be an ASCII string shorter than %s characters or null.", IFNAMSIZ));
        }
        return name;
    }

    public static void main(String[] args) throws Exception {
        final LinuxTunAdapter adapter = open("tun9");
        adapter.setMTU(1500);
        System.out.println("MTU -> " + adapter.getMTU());

        Inet4Address ipv4 = (Inet4Address) InetAddress.getByName("192.168.3.1");
        Inet4Address ipv4_2 = (Inet4Address) InetAddress.getByName("192.168.3.2");

        adapter.setInterfaceAddress(InterfaceAddressEx.of(ipv4, 16));
        adapter.addInterfaceAddress(InterfaceAddressEx.of(ipv4_2, 24));

        System.out.println("IPv4 -> " + adapter.getInterfaceAddresses());
        System.out.println("OK");

        Inet6Address ipv6 = (Inet6Address) InetAddress.getByName("fd2c:8ee9:8bc:3a49:49ca:e99b:fc86:7fa2");
        Inet6Address ipv62 = (Inet6Address) InetAddress.getByName("fd2c:8ee9:8bc:3a49:49ca:e99b:fc86:7fa3");
        adapter.addInterfaceAddress(InterfaceAddressEx.of(ipv6, 64));
        adapter.addInterfaceAddress(InterfaceAddressEx.of(ipv62, 64));

        System.out.println("IPv6 -> OK");

//        TimeUnit.SECONDS.sleep(10);

//        adapter.flushInterfaceAddresses();

//        System.out.println("Cleanup -> OK");

//        TimeUnit.SECONDS.sleep(30);


        while (true) {
            final byte[] bytes = adapter.readPacket();
            Packet packet = IpSelector.newPacket(bytes, 0, bytes.length);
            System.out.println(packet);
        }
    }

}