package com.github.pangolin.routing.server.tun.adapter.darwin;

import com.github.pangolin.routing.server.tun.adapter.AbstractTunAdapter;
import com.github.pangolin.routing.server.tun.adapter.darwin.jna.KernControl.CtlInfo;
import com.github.pangolin.routing.server.tun.adapter.darwin.jna.KernControl.SockaddrCtl;
import com.github.pangolin.routing.server.tun.adapter.linux.jna.LibC;
import com.sun.jna.Native;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.ByteBuffer;

import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.KernControl.CTLIOCGINFO;
import static com.github.pangolin.routing.server.tun.adapter.darwin.jna.Socket.*;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.LibC.*;
import static java.nio.charset.StandardCharsets.US_ASCII;

@Slf4j
public class DarwinTunAdapter extends AbstractTunAdapter<DarwinNetworkInterfaceEx> {
    private static final int SYSPROTO_CONTROL = 2;
    private static final int UTUN_OPT_IFNAME = 2;
    private static final String UTUN_CONTROL_NAME = "com.apple.net.utun_control";

    private static final int ADDRESS_FAMILY_SIZE = 4;

    private static final String DEVICE_PREFIX = "utun";
    private static final String ILLEGAL_NAME_EXCEPTION = "Device name must be 'utun<index>' or null.";

    private final int fd;
    private final String ifname;
    private final int mtu;

    private DarwinTunAdapter(final int fd, final String ifname, final int mtu) {
        super(new DarwinNetworkInterfaceEx(ifname));
        this.fd = fd;
        this.ifname = ifname;
        this.mtu = mtu;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String name() {
        return ifname;
    }

    public int fd() {
        return fd;
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
        final int packetSize = mtu + ADDRESS_FAMILY_SIZE;
        final ByteBuffer buf = ByteBuffer.allocate(packetSize);
        final int bytesRead = LibC.read(fd, buf, packetSize);

        // 4-bytes address family
        final int addressFamily = buf.getInt(0);
        if (AF_INET != addressFamily && AF_INET6 != addressFamily) {
            throw new IOException("Unknown address family: " + addressFamily);
        }

        final int ipVersion = buf.get(ADDRESS_FAMILY_SIZE) >> 4;
        log.trace("IPv{} packet read.", ipVersion);

        // skip 4-bytes address family
        buf.position(ADDRESS_FAMILY_SIZE).limit(bytesRead);
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
                ADDRESS_FAMILY_SIZE + packet.remaining()
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
        LibC.close(fd);
    }

    /* ********************** */

    public static DarwinTunAdapter open(String name, int mtu) throws IOException {
        final int utunNum = nameToNum(name);

        // create socket
        final int fd = socket(AF_SYSTEM, SOCK_DGRAM, SYSPROTO_CONTROL);
        if (fd == -1) {
            throw new IOException("Create an endpoint for communication failed.");
        }

        // mark socket as utun device
        final CtlInfo ctlInfo = new CtlInfo(UTUN_CONTROL_NAME);
        ioctl(fd, CTLIOCGINFO, ctlInfo);

        // define address of socket
        final SockaddrCtl address = new SockaddrCtl(AF_SYSTEM, (short) SYSPROTO_CONTROL, ctlInfo.ctl_id, utunNum);
        connect(fd, address, address.sc_len);

        // get socket name
        final SockName sockName = new SockName();
        final IntByReference sockNameLen = new IntByReference(SockName.LENGTH);
        getsockopt(fd, SYSPROTO_CONTROL, UTUN_OPT_IFNAME, sockName, sockNameLen);

        final String ifname = Native.toString(sockName.name, US_ASCII);

        int mtuToUse = mtu;
        if (0 < mtuToUse) {
            DarwinNetworkInterfaceEx.setMtu(fd, ifname, mtuToUse);
        } else {
            mtuToUse = DarwinNetworkInterfaceEx.getMtu(fd, ifname);
        }

        return new DarwinTunAdapter(fd, ifname, mtuToUse);
    }


    private static int nameToNum(final String name) {
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
        // utunnum = index + 1
        return index + 1;
    }

    @SuppressWarnings({"java:S116", "java:S1104", "java:S2160"})
    @Structure.FieldOrder({"name"})
    public static class SockName extends Structure {
        static final int LENGTH = 16;
        public byte[] name = new byte[LENGTH];
    }

}