package com.github.pangolin.tun.net.windows;

import static com.github.pangolin.tun.net.windows.jna.WintunLib.WINTUN_ADAPTER_HANDLE;
import static com.github.pangolin.tun.net.windows.jna.WintunLib.WINTUN_SESSION_HANDLE;
import static com.github.pangolin.tun.net.windows.jna.WintunLib.WintunAllocateSendPacket;
import static com.github.pangolin.tun.net.windows.jna.WintunLib.WintunCloseAdapter;
import static com.github.pangolin.tun.net.windows.jna.WintunLib.WintunCreateAdapter;
import static com.github.pangolin.tun.net.windows.jna.WintunLib.WintunEndSession;
import static com.github.pangolin.tun.net.windows.jna.WintunLib.WintunGetReadWaitEvent;
import static com.github.pangolin.tun.net.windows.jna.WintunLib.WintunOpenAdapter;
import static com.github.pangolin.tun.net.windows.jna.WintunLib.WintunReceivePacket;
import static com.github.pangolin.tun.net.windows.jna.WintunLib.WintunReleaseReceivePacket;
import static com.github.pangolin.tun.net.windows.jna.WintunLib.WintunSendPacket;
import static com.github.pangolin.tun.net.windows.jna.WintunLib.WintunStartSession;
import static com.sun.jna.platform.win32.Guid.GUID;

import com.github.pangolin.tun.net.AbstractTunAdapter;
import com.github.pangolin.tun.net.InterfaceAddressEx;
import com.github.pangolin.tun.net.windows.jna.WintunLib;
import com.sun.jna.LastErrorException;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;

import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

/**
 * Windows tun adapter based on <a href="https://www.wintun.net/">wintun</a>.
 */
public class WindowsTunAdapter extends AbstractTunAdapter<WindowsNetworkInterfaceEx> {
    private static final int INFINITE = 0xFFFFFFFF;
    private static final int ERROR_NO_MORE_ITEMS = 259;

    private final WINTUN_ADAPTER_HANDLE adapter;
    private final WINTUN_SESSION_HANDLE session;

    public WindowsTunAdapter(final WINTUN_ADAPTER_HANDLE adapter, final WINTUN_SESSION_HANDLE session) {
        super(new WindowsNetworkInterfaceEx(getLuid(adapter)));
        this.adapter = adapter;
        this.session = session;
    }

    private static long getLuid(final WINTUN_ADAPTER_HANDLE adapter) {
        final LongByReference luidRef = new LongByReference();
        WintunLib.WintunGetAdapterLUID(adapter, luidRef);
        return luidRef.getValue();
    }

    public int getMTU() throws SocketException {
        return nix.getMTU();
    }

    public void setMTU(final int mtu) {
        throw new UnsupportedOperationException();
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

    public byte[] readPacket() throws IOException {
//        if (closed) {
//            throw new IOException("Device is closed.");
//        }

        while (true) {
            try {
                // read bytes
//                 final Pointer packetSizePointer = new Memory(Native.POINTER_SIZE);
                final IntByReference packetSizePointer = new IntByReference();
                final Pointer packetPointer = WintunReceivePacket(session, packetSizePointer);

                // extract ip version
                final int ipVersion = packetPointer.getByte(0) >> 4;

                // shrink bytebuf to actual required size
//                final int PacketSize = packetSizePointer.getInt(0);
                final int PacketSize = packetSizePointer.getValue();
                byte[] packet = packetPointer.getByteArray(0, PacketSize);
//                final ByteBuf byteBuf = alloc.buffer(PacketSize);
//                byteBuf.writeBytes(packetPointer.getByteArray(0, PacketSize));
                WintunReleaseReceivePacket(session, packetPointer);

                /*
                if (ipVersion == 4) {
                    return new Tun4Packet(byteBuf);
                } else {
                    return new Tun6Packet(byteBuf);
                }
                */
                return packet;
            } catch (final LastErrorException e) {
                if (e.getErrorCode() == ERROR_NO_MORE_ITEMS) {
                    Kernel32.INSTANCE.WaitForSingleObject(WintunGetReadWaitEvent(session), INFINITE);
                } else {
                    throw e;
                }
            }
        }
    }

    //    @Override
    public void writePacket(final byte[] packet, int offset, int len) throws IOException {
//        if (closed) {
//            throw new IOException("Device is closed.");
//        }

        final WinDef.DWORD packetSize = new WinDef.DWORD(len);
        final Pointer packetPointer = WintunAllocateSendPacket(session, packetSize);
        packetPointer.write(0, packet, offset, len);

        WintunSendPacket(session, packetPointer);
    }

    public void close() throws IOException {
        if (null != session) {
            WintunEndSession(session);
        }
        if (null != adapter) {
            WintunCloseAdapter(adapter);
        }
    }

    public static WindowsTunAdapter open(String name, final String type) throws IOException {
        return open(name, type, GUID.newGuid());
    }

    public static WindowsTunAdapter open(String name, final String type, final String guid) throws IOException {
        return open(name, type, GUID.fromString(guid));
    }

    /**
     * @param tunName the name of the tun adapter
     * @param tunType the type of the tun adapter, null for open existing one, required when creating new adapter.
     * @param guid
     * @return
     * @throws IOException
     */
    public static WindowsTunAdapter open(String tunName, final String tunType, final GUID guid) throws IOException {
        if (tunName == null) {
            tunName = "tun";
        }

        WINTUN_ADAPTER_HANDLE adapter = null;
        WINTUN_SESSION_HANDLE session = null;
        try {
            if (null == guid) {
                adapter = WintunOpenAdapter(new WString(tunName));
                if (null == adapter) {
                    throw new IOException("Failed to open tun device " + tunName);
                }
            } else {
                adapter = WintunCreateAdapter(new WString(tunName), new WString(tunType), guid);
            }
            session = WintunStartSession(adapter, new WinDef.DWORD(0x400000));
            return new WindowsTunAdapter(adapter, session);
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


    public static void main(String[] args) throws IOException {
        final GUID guid = GUID.newGuid();
        // final GUID guid = GUID.fromString("{E3E33B4A-2E25-4168-A732-52BBDFD9EE57}");
        final String guidStr = guid.toGuidString();

        final WindowsTunAdapter adapter = WindowsTunAdapter.open("wintun", "wintun", guid);
        adapter.addInterfaceAddress(InterfaceAddressEx.of("172.16.0.1", 24));
        adapter.setInterfaceDns(new InetAddress[]{InetAddress.getByName("127.0.0.1")});

        System.out.println(guidStr);
        System.out.println(adapter.adapter);

        LockSupport.park();
//        Pointer pointer = Pointer.createConstant(adapter.getLuid());
//        LongByReference r = new LongByReference(adapter.getLuid());
//        adapter.setIpAddress(InetAddress.getByName("192.168.1.1"), (byte) 24);
//        WintunSession session = adapter.newSession();
    }
}