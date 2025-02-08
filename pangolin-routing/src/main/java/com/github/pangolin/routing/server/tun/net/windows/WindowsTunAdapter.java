package com.github.pangolin.routing.server.tun.net.windows;

import static com.github.pangolin.routing.server.tun.net.windows.jna.WintunLib.WINTUN_ADAPTER_HANDLE;
import static com.github.pangolin.routing.server.tun.net.windows.jna.WintunLib.WINTUN_SESSION_HANDLE;
import static com.github.pangolin.routing.server.tun.net.windows.jna.WintunLib.WintunAllocateSendPacket;
import static com.github.pangolin.routing.server.tun.net.windows.jna.WintunLib.WintunCloseAdapter;
import static com.github.pangolin.routing.server.tun.net.windows.jna.WintunLib.WintunCreateAdapter;
import static com.github.pangolin.routing.server.tun.net.windows.jna.WintunLib.WintunEndSession;
import static com.github.pangolin.routing.server.tun.net.windows.jna.WintunLib.WintunGetAdapterLUID;
import static com.github.pangolin.routing.server.tun.net.windows.jna.WintunLib.WintunGetReadWaitEvent;
import static com.github.pangolin.routing.server.tun.net.windows.jna.WintunLib.WintunGetRunningDriverVersion;
import static com.github.pangolin.routing.server.tun.net.windows.jna.WintunLib.WintunOpenAdapter;
import static com.github.pangolin.routing.server.tun.net.windows.jna.WintunLib.WintunReceivePacket;
import static com.github.pangolin.routing.server.tun.net.windows.jna.WintunLib.WintunReleaseReceivePacket;
import static com.github.pangolin.routing.server.tun.net.windows.jna.WintunLib.WintunSendPacket;
import static com.github.pangolin.routing.server.tun.net.windows.jna.WintunLib.WintunStartSession;
import static com.sun.jna.platform.win32.Guid.GUID;
import static com.sun.jna.platform.win32.IPHlpAPI.AF_INET;
import static com.sun.jna.platform.win32.IPHlpAPI.AF_INET6;

import com.github.pangolin.routing.server.tun.net.AbstractTunAdapter;
import com.sun.jna.LastErrorException;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Windows tun adapter based on <a href="https://www.wintun.net/">wintun</a>.
 */
@Slf4j
public class WindowsTunAdapter extends AbstractTunAdapter<WindowsNetworkInterfaceEx> {
    private static final int INFINITE = 0xFFFFFFFF;
    private static final int ERROR_NO_MORE_ITEMS = 259;

    private final long luid;
    private final String ifname;
    private final int mtu;
    private final WINTUN_ADAPTER_HANDLE adapter;
    private final WINTUN_SESSION_HANDLE session;

    private WindowsTunAdapter(final long luid,
                              final String ifname, final int mtu,
                              final WINTUN_ADAPTER_HANDLE adapter,
                              final WINTUN_SESSION_HANDLE session) {
        super(new WindowsNetworkInterfaceEx(luid));
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
                final Pointer packetPointer = WintunReceivePacket(session, packetSizeRef);
                try {
                    final int ipVersion = packetPointer.getByte(0) >> 4;
                    log.trace("IPv{} packet read.", ipVersion);

                    final int size = packetSizeRef.getValue();
                    return packetPointer.getByteBuffer(0, size);
                } finally {
                    WintunReleaseReceivePacket(session, packetPointer);
                }
            } catch (final LastErrorException e) {
                if (e.getErrorCode() == ERROR_NO_MORE_ITEMS) {
                    Kernel32.INSTANCE.WaitForSingleObject(WintunGetReadWaitEvent(session), INFINITE);
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

    public List<InetAddress> getInterfaceDns(final boolean manualSetOnly) {
        return nix.getInterfaceDns(manualSetOnly);
    }

    public void setInterfaceDns(final InetAddress[] nameServers) {
        nix.setInterfaceDns(nameServers);
    }

    public void flushInterfaceDns() {
        nix.flushInterfaceDns();
    }

    @Override
    public String toString() {
        return ifname + " WindowsTunAdapter (By Wintun " + WintunGetRunningDriverVersion() + ")";
    }

    /* ********************** */

    public static WindowsTunAdapter open(final String name, final String type, final int mtu) throws IOException {
        return open(name, type, (GUID) null, mtu);
    }

    public static WindowsTunAdapter open(final String name, final String type,
                                         final String guid, final int mtu) throws IOException {
        return open(name, type, GUID.fromString(guid), mtu);
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
                                         final GUID guid, final int mtu) throws IOException {
        WINTUN_ADAPTER_HANDLE adapter = null;
        WINTUN_SESSION_HANDLE session = null;
        try {
            if (null == guid) {
                try {
                    adapter = WintunOpenAdapter(new WString(name));
                } catch (final LastErrorException err) {
                    if (err.getErrorCode() != 1168) {
                        throw err;
                    }
                    adapter = WintunCreateAdapter(new WString(name), new WString(type), GUID.newGuid());
                }
            } else {
                adapter = WintunCreateAdapter(new WString(name), new WString(type), guid);
            }
            session = WintunStartSession(adapter, new WinDef.DWORD(0x400000));

            final long luid = getLuid(adapter);

            int mtuToUse = mtu;
            if (0 < mtuToUse) {
                WindowsNetworkInterfaceEx.setMTU(luid, AF_INET, mtuToUse);
                WindowsNetworkInterfaceEx.setMTU(luid, AF_INET6, mtuToUse);
            } else {
                mtuToUse = WindowsNetworkInterfaceEx.getMTU(luid, AF_INET);
            }

            return new WindowsTunAdapter(luid, name, mtuToUse, adapter, session);
        } catch (final LastErrorException e) {
            if (null != session) {
                WintunEndSession(session);
            }
            if (null != adapter) {
                WintunCloseAdapter(adapter);
            }
            throw new IOException(e);
        }
    }

    private static long getLuid(final WINTUN_ADAPTER_HANDLE adapter) {
        final LongByReference luidRef = new LongByReference();
        WintunGetAdapterLUID(adapter, luidRef);
        return luidRef.getValue();
    }

}