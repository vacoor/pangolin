package com.github.pangolin.routing.server.tun.adapter.darwin;

import com.github.pangolin.routing.server.tun.adapter.AbstractTunAdapter;
import com.github.pangolin.routing.server.tun.adapter.InterfaceAddressEx;
import com.github.pangolin.routing.server.tun.adapter.darwin.jna.KernControl.ctl_info;
import com.github.pangolin.routing.server.tun.adapter.darwin.jna.KernControl.sockaddr_ctl;
import com.github.pangolin.routing.server.tun.adapter.unix.jna.LibC;
import com.github.pangolin.routing.server.tun.adapter.util.NetUtils2;
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

import static com.github.pangolin.routing.server.tun.adapter.darwin.DarwinNetworkInterface.setMTU;
import static com.github.pangolin.routing.server.tun.adapter.darwin.DarwinUtils.throwLastErrorException;
import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.IfUtun.UTUN_CONTROL_NAME;
import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.IfUtun.UTUN_OPT_IFNAME;
import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.KernControl.CTLIOCGINFO;
import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.Socket.*;
import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.SysDomain.SYSPROTO_CONTROL;
import static com.github.pangolin.routing.server.tun.adapter.unix.jna.LibC.*;
import static java.nio.charset.StandardCharsets.US_ASCII;

@Slf4j
public class DarwinTunAdapter extends AbstractTunAdapter {
    /**
     * Address family bytes.
     */
    private static final int AF_BYTES = 4;

    /**
     * utun device prefix.
     */
    private static final String UTUN_NAME_PREFIX = "utun";
    private static final String ILLEGAL_NAME_EXCEPTION = "Device name must be 'utun<index>' or null.";

    private final int fd;
    private final String ifname;
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
        final int bytesRead = LibC.read(fd, buf, packetSize);

        // 4-bytes address family
        final int addressFamily = buf.getInt(0);
        if (AF_INET != addressFamily && AF_INET6 != addressFamily) {
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
        int addressFamily = AF_UNSPEC;
        if (4 == ipVersion) {
            addressFamily = AF_INET;
        } else if (6 == ipVersion) {
            addressFamily = AF_INET6;
        } else {
            throw new IOException("Unknown address family: " + addressFamily);
        }

        final ByteBuffer buf = ByteBuffer.allocate(
                AF_BYTES + packet.remaining()
        ).put(new byte[]{
                (byte) (addressFamily >> 24),
                (byte) (addressFamily >> 16),
                (byte) (addressFamily >> 8),
                (byte) addressFamily
        }).put(packet);

        buf.flip();

        LibC.write(fd, buf, buf.remaining());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void destroy0() {
        close(fd);
    }

    /* ********************** */

    public static DarwinTunAdapter open(final String ifname, final int mtu,
                                        final InterfaceAddressEx... bindings) {
        final int scUnit = nameToUnit(ifname);

        // create socket
        final int fd = socket(AF_SYSTEM, SOCK_DGRAM, SYSPROTO_CONTROL);
        if (fd < 0) {
            throwLastErrorException(Native.getLastError());
        }

        // mark socket as utun device
        final ctl_info ctlInfo = new ctl_info(UTUN_CONTROL_NAME);
        if (ioctl(fd, CTLIOCGINFO, ctlInfo) < 0) {
            throwLastErrorException(Native.getLastError());
        }

        // define address of socket
        final sockaddr_ctl address = new sockaddr_ctl((byte) AF_SYSTEM, (short) SYSPROTO_CONTROL, ctlInfo.ctl_id, scUnit);
        if (connect(fd, address, address.sc_len) < 0) {
            throwLastErrorException(Native.getLastError());
        }

        // get socket name
        final SockName sockName = new SockName();
        final IntByReference sockNameLen = new IntByReference(SockName.LENGTH);
        if (getsockopt(fd, SYSPROTO_CONTROL, UTUN_OPT_IFNAME, sockName, sockNameLen) < 0) {
            throwLastErrorException(Native.getLastError());
        }

        final String ifnameToUse = Native.toString(sockName.name, US_ASCII);

        // get or set interface MTU
        int mtuToUse = mtu;
        if (0 < mtuToUse) {
            setMTU(fd, ifnameToUse, mtuToUse);
        } else {
            mtuToUse = DarwinNetworkInterface.getMTU(fd, ifnameToUse);
        }

        final DarwinTunAdapter adapter = new DarwinTunAdapter(fd, ifnameToUse, mtuToUse);
        final Set<InetAddress> netDst = Sets.newHashSet();
        final DarwinNetworkInterface nix = new DarwinNetworkInterface(ifnameToUse);
        for (final InterfaceAddressEx binding : bindings) {
            nix.addInterfaceAddress(binding);

            /*-
             * MacOS 不会添加默认网关路由.
             * sudo route add -net 198.18.0.0/24 198.18.0.1
             */
            final InetAddress gw = binding.getAddress();
            final int prefix = binding.getNetworkPrefixLength();
            final InetAddress dst = NetUtils2.getNetworkAddress(gw, prefix);
            if (netDst.add(dst) && dst instanceof Inet4Address) {
                DarwinNetworkRoute.add(dst, prefix, gw, ifnameToUse);
            }
        }
        return adapter;
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
            // AUTO alloc
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