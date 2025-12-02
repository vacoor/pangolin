package com.github.pangolin.routing.acceptor.tun.adapter.darwin;

import com.github.pangolin.routing.acceptor.tun.adapter.InterfaceAddressEx;
import com.github.pangolin.routing.acceptor.tun.adapter.TunAdapter;
import com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.IfUtun;
import com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.KernControl;
import com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.Socket;
import com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna.SysDomain;
import com.github.pangolin.routing.acceptor.tun.adapter.unix.jna.LibC;
import com.github.pangolin.routing.acceptor.tun.adapter.util.NetUtils2;
import com.google.common.collect.Sets;
import com.sun.jna.Native;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.Set;

import static java.nio.charset.StandardCharsets.US_ASCII;

@Slf4j
public class DarwinTunAdapter extends TunAdapter {

    private static final LibC LIBC = LibC.INSTANTCE;

    /**
     * Address family bytes.
     */
    private static final int AF_BYTES = 4;

    /**
     * utun interface name prefix.
     */
    private static final String UTUN_NAME_PREFIX = "utun";
    private static final String ILLEGAL_NAME_EXCEPTION = "Device name must be 'utun<index>' or null.";

    /**
     * The utun file descriptor.
     */
    private final int fd;

    /**
     * The utun interface name.
     */
    private final String ifname;

    /**
     * The utun maximum transmission unit.
     */
    private final int mtu;

    private DarwinTunAdapter(final int fd, final String ifname, final int mtu) {
        this.fd = fd;
        this.ifname = ifname;
        this.mtu = mtu;
    }

    public int fd() {
        return fd;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String name() {
        return ifname;
    }

    public int getMTU() {
        return mtu;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected ByteBuffer read0() throws IOException {
        // read from socket
        final int mtu = getMTU();
        final int packetSize = mtu + AF_BYTES;
        final ByteBuffer buf = ByteBuffer.allocateDirect(packetSize);
        final int bytesRead = LIBC.read(fd, buf, packetSize);

        // 4-bytes address family
        final int addressFamily = buf.getInt(0);
        if (Socket.AF_INET != addressFamily && Socket.AF_INET6 != addressFamily) {
            throw new IOException("Unknown address family: " + addressFamily);
        }

        final int ipVersion = buf.get(AF_BYTES) >> 4;
        log.trace("IPv{} packet read.", ipVersion);

        // skip 4-bytes address family
        buf.position(AF_BYTES).limit(bytesRead);
        return buf;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void write0(final ByteBuffer packet) throws IOException {
        final int ipVersion = packet.get(packet.position()) >> 4;
        int addressFamily = Socket.AF_UNSPEC;
        if (4 == ipVersion) {
            addressFamily = Socket.AF_INET;
        } else if (6 == ipVersion) {
            addressFamily = Socket.AF_INET6;
        } else {
            throw new IOException("Unknown address family: " + addressFamily);
        }

        final ByteBuffer buf = ByteBuffer.allocateDirect(
                AF_BYTES + packet.remaining()
        );
        buf.put(new byte[]{
                (byte) (addressFamily >> 24),
                (byte) (addressFamily >> 16),
                (byte) (addressFamily >> 8),
                (byte) addressFamily
        });
        buf.put(packet);
        buf.flip();

        LIBC.write(fd, buf, buf.remaining());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void destroy0() {
        LIBC.close(fd);
    }

    /* ********************** */

    /**
     * Creates a utun interface and bind to ifname and interface addresses.
     *
     * @param ifname   the utun interface name, it will be automatically assigned if null.
     * @param mtu      the utun maximum transmission unit.
     * @param bindings the interface addresses to bind
     * @return the utun interface
     */
    public static DarwinTunAdapter open(final String ifname, final int mtu,
                                        final InterfaceAddressEx... bindings) {
        final int scUnit = nameToUnit(ifname);

        // create socket
        final int fd = LIBC.socket(Socket.AF_SYSTEM, Socket.SOCK_DGRAM, SysDomain.SYSPROTO_CONTROL);
        if (fd < 0) {
            DarwinUtils.throwLastErrorException(Native.getLastError());
        }

        // mark socket as utun device
        final KernControl.ctl_info ctlInfo = new KernControl.ctl_info(IfUtun.UTUN_CONTROL_NAME);
        if (LIBC.ioctl(fd, KernControl.CTLIOCGINFO, ctlInfo) < 0) {
            DarwinUtils.throwLastErrorException(Native.getLastError());
        }

        // define address of socket
        final KernControl.sockaddr_ctl address = new KernControl.sockaddr_ctl((byte) Socket.AF_SYSTEM, (short) SysDomain.SYSPROTO_CONTROL, ctlInfo.ctl_id, scUnit);
        if (LIBC.connect(fd, address, address.sc_len) < 0) {
            DarwinUtils.throwLastErrorException(Native.getLastError());
        }

        // get socket name
        final SockName sockName = new SockName();
        final IntByReference sockNameLen = new IntByReference(SockName.LENGTH);
        if (LIBC.getsockopt(fd, SysDomain.SYSPROTO_CONTROL, IfUtun.UTUN_OPT_IFNAME, sockName, sockNameLen) < 0) {
            DarwinUtils.throwLastErrorException(Native.getLastError());
        }

        final String ifnameToUse = Native.toString(sockName.name, US_ASCII);

        // get or set interface MTU
        int mtuToUse = mtu;
        if (0 < mtuToUse) {
            DarwinNetworkInterface.setMTU(fd, ifnameToUse, mtuToUse);
        } else {
            mtuToUse = DarwinNetworkInterface.getMTU(fd, ifnameToUse);
        }

        final DarwinNetworkInterface nix = DarwinNetworkInterface.getByName(ifnameToUse);
        final Set<InetAddress> processes = Sets.newHashSet();
        for (final InterfaceAddressEx binding : bindings) {
            nix.addInterfaceAddress(binding);

            /*-
             * MacOS 不会添加默认路由.
             * sudo route add -net 198.18.0.0/24 198.18.0.1
             */
            final InetAddress gw = binding.getAddress();
            final int prefix = binding.getNetworkPrefixLength();
            final InetAddress dst = NetUtils2.getNetworkAddress(gw, prefix);
            if (processes.add(dst) && dst instanceof Inet4Address) {
                DarwinNetworkRoutingTable.add0(dst, prefix, gw, ifnameToUse);
            }
        }
        return new DarwinTunAdapter(fd, ifnameToUse, mtuToUse);
    }

    private static int nameToUnit(final String ifname) {
        final int index;
        if (ifname != null) {
            if (ifname.startsWith(UTUN_NAME_PREFIX)) {
                try {
                    index = Integer.parseInt(ifname.substring(UTUN_NAME_PREFIX.length()));
                } catch (final NumberFormatException e) {
                    throw new IllegalArgumentException(ILLEGAL_NAME_EXCEPTION);
                }
            } else {
                throw new IllegalArgumentException(ILLEGAL_NAME_EXCEPTION);
            }
            // utun unit = index + 1
            return index + 1;
        } else {
            // automatically assigned
            return 0;
        }
    }

    @SuppressWarnings({"java:S116", "java:S1104", "java:S2160"})
    @Structure.FieldOrder({"name"})
    public static class SockName extends Structure {
        static final int LENGTH = 16;
        public byte[] name = new byte[LENGTH];
    }

}