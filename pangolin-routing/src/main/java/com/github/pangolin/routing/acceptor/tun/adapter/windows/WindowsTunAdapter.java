package com.github.pangolin.routing.acceptor.tun.adapter.windows;

import com.github.pangolin.routing.acceptor.tun.adapter.InterfaceAddressEx;
import com.github.pangolin.routing.acceptor.tun.adapter.TunAdapter;
import com.github.pangolin.routing.acceptor.tun.adapter.windows.jna.IpHlpLib;
import com.sun.jna.LastErrorException;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.ByteBuffer;

import static com.github.pangolin.routing.acceptor.tun.adapter.windows.jna.WintunLib.*;
import static com.sun.jna.platform.win32.Guid.GUID;
import static com.sun.jna.platform.win32.IPHlpAPI.AF_INET;

/**
 * Windows tun adapter based on <a href="https://www.wintun.net/">wintun</a>.
 */
@Slf4j
public class WindowsTunAdapter extends TunAdapter {
    private static final int INFINITE = 0xFFFFFFFF;

    private final long luid;
    private final String ifname;
    private final int mtu;
    private final WINTUN_ADAPTER_HANDLE adapter;
    private final WINTUN_SESSION_HANDLE session;

    private WindowsTunAdapter(final long luid,
                              final String ifname, final int mtu,
                              final WINTUN_ADAPTER_HANDLE adapter,
                              final WINTUN_SESSION_HANDLE session) {
        this.luid = luid;
        this.ifname = ifname;
        this.mtu = mtu;
        this.adapter = adapter;
        this.session = session;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String name() {
        return ifname;
    }

    public long luid() {
        return luid;
    }

    public int getMTU() {
        return mtu;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected ByteBuffer read0() {
        do {
            try {
                // read from wintun
                final IntByReference packetSizeRef = new IntByReference();
                Pointer packetPointer = null;
                try {
                    packetPointer = WintunReceivePacket(session, packetSizeRef);
                    final int ipVersion = packetPointer.getByte(0) >> 4;
                    log.trace("IPv{} packet read.", ipVersion);

                    final int size = packetSizeRef.getValue();
                    return packetPointer.getByteBuffer(0, size);
                } finally {
                    if (null != packetPointer) {
                        WintunReleaseReceivePacket(session, packetPointer);
                    }
                }
            } catch (final LastErrorException e) {
                if (e.getErrorCode() == WinError.ERROR_NO_MORE_ITEMS) {
                    Kernel32.INSTANCE.WaitForSingleObject(WintunGetReadWaitEvent(session), INFINITE);
                } else if (e.getErrorCode() == WinError.ERROR_HANDLE_EOF) {
                    // disabled or remove.
                    throw e;
                } else {
                    throw e;
                }
            }
        } while (true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void write0(final ByteBuffer packet) {
        final WinDef.DWORD size = new WinDef.DWORD(packet.remaining());
        final Pointer packetPointer = WintunAllocateSendPacket(session, size);

        // packetPointer.write(0, packet, offset, len);
        for (int offset = 0; packet.hasRemaining(); offset++) {
            packetPointer.setByte(offset, packet.get());
        }

        WintunSendPacket(session, packetPointer);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void destroy0() {
        if (null != session) {
            WintunEndSession(session);
        }
        if (null != adapter) {
            WintunCloseAdapter(adapter);
        }
    }

    @Override
    public String toString() {
        return ifname + " WindowsTunAdapter (By Wintun " + WintunGetRunningDriverVersion() + ")";
    }

    /* ********************** */

    public static WindowsTunAdapter open(final String name, final String type, final int mtu, final InterfaceAddressEx... bindings) throws IOException {
        return open(name, type, (String) null, mtu, bindings);
    }

    public static WindowsTunAdapter open(final String name, final String type,
                                         final String guid, final int mtu, final InterfaceAddressEx... bindings) throws IOException {
        final GUID guidToUse = null != guid ? GUID.fromString(guid) : GUID.newGuid();
        return open(name, type, guidToUse, mtu, bindings);
    }

    /**
     * @param name the name of the tun adapter
     * @param type the type of the tun adapter, null for open existing one, required when creating new adapter.
     * @param guid the GUID of the tun adapter
     * @param mtu  the maximum transmission unit
     * @return the tun adapter
     * @throws IOException
     */
    public static WindowsTunAdapter open(final String name,
                                         final String type,
                                         final GUID guid, final int mtu,
                                         final InterfaceAddressEx... bindings) throws IOException {
        WINTUN_ADAPTER_HANDLE adapter = null;
        WINTUN_SESSION_HANDLE session = null;
        int i = 0;
        do {
            try {
                if (null == (adapter = tryWintunOpenAdapter(name, guid))) {
                    log.info("Try create WintunAdapter: {}", name);
                    adapter = WintunCreateAdapter(new WString(name), new WString(type), guid);
                }
                session = WintunStartSession(adapter, new WinDef.DWORD(0x400000));

                final long luid = getLuid(adapter);

                int mtuToUse = mtu;
                if (0 < mtuToUse) {
                    WindowsNetworkInterface.setMTU0(luid, AF_INET, mtuToUse);
                } else {
                    mtuToUse = WindowsNetworkInterface.getMTU0(luid, AF_INET);
                }

                /*-
                 * Windows is a weak-end system model
                 * where the IP implementation accepts datagrams destined for
                 * any IP address of the host, regardless of the interface the
                 * datagram arrives on.
                 *
                 * Windows automatically adds routes:
                 * route add 198.18.0.0 mask 255.255.255.0 0.0.0.0 metric 261 IF {ifindex}
                 * route add 198.18.0.1 mask 255.255.255.255 0.0.0.0 metric 261 IF {ifindex}
                 *
                 * if you want the TUN to capture all packets, add the following routes:
                 * route delete 198.18.0.0 mask 255.255.255.0 0.0.0.0
                 * route delete 198.18.0.1 mask 255.255.255.255 0.0.0.0
                 * route add 198.18.0.0 mask 255.255.255.0 198.18.0.1 metric 261 IF 12
                 * route add 198.18.0.1 mask 255.255.255.255 198.18.0.1 metric 261 IF 12
                 */
                for (final InterfaceAddressEx binding : bindings) {
                    WindowsNetworkInterface.addInterfaceAddress0(
                            luid,
                            binding.getAddress(),
                            (byte) binding.getNetworkPrefixLength()
                    );
                }

                return new WindowsTunAdapter(luid, name, mtuToUse, adapter, session);
            } catch (final LastErrorException e) {
                if (null != session) {
                    WintunEndSession(session);
                }
                if (null != adapter) {
                    WintunCloseAdapter(adapter);
                }

                if (WinError.ERROR_ALREADY_EXISTS == e.getErrorCode() && ++i < 3) {
                    continue;
                }
                throw new IOException(e);
            }
        } while (true);
    }

    private static WINTUN_ADAPTER_HANDLE tryWintunOpenAdapter(final String name, final GUID guid) {
        String nameToOpen = name;
        if (null != guid) {
            final LongByReference luidRef = new LongByReference();
            final int err = IpHlpLib.INSTANCE.ConvertInterfaceGuidToLuid(guid, luidRef);
            // ignore WinError.ERROR_INVALID_PARAMETER(87)
            if (WinError.NO_ERROR == err) {
                final char[] buff = new char[IpHlpLib.NDIS_IF_MAX_STRING_SIZE + 1];
                final int err2 = IpHlpLib.INSTANCE.ConvertInterfaceLuidToAlias(luidRef, buff, buff.length);
                if (WinError.NO_ERROR == err2) {
                    nameToOpen = Native.toString(buff);
                }
            }
        }

        try {
            log.info("Try open WintunAdapter: {}", nameToOpen);
            return WintunOpenAdapter(new WString(nameToOpen));
        } catch (final LastErrorException err) {
            if (err.getErrorCode() != WinError.ERROR_NOT_FOUND) {
                throw err;
            }
            return null;
        }
    }

    private static long getLuid(final WINTUN_ADAPTER_HANDLE adapter) {
        final LongByReference luidRef = new LongByReference();
        WintunGetAdapterLUID(adapter, luidRef);
        return luidRef.getValue();
    }

}