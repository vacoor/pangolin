package com.github.pangolin.tun.net.windows;

import static com.github.pangolin.tun.net.windows.WintunLib.WINTUN_ADAPTER_HANDLE;
import static com.github.pangolin.tun.net.windows.WintunLib.WINTUN_SESSION_HANDLE;
import static com.github.pangolin.tun.net.windows.WintunLib.WintunCloseAdapter;
import static com.github.pangolin.tun.net.windows.WintunLib.WintunCreateAdapter;
import static com.github.pangolin.tun.net.windows.WintunLib.WintunEndSession;
import static com.github.pangolin.tun.net.windows.WintunLib.WintunOpenAdapter;
import static com.github.pangolin.tun.net.windows.WintunLib.WintunStartSession;
import static com.sun.jna.platform.win32.Guid.GUID;

import com.github.pangolin.tun.net.InterfaceAddressEx;
import com.github.pangolin.tun.net.windows.win32.WindowsNetworkInterfaceEx;
import com.github.pangolin.tun.net.windows.win32.jna.IpHelpLib;
import com.sun.jna.LastErrorException;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Win32Exception;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.ptr.LongByReference;

import java.io.IOException;
import java.net.InetAddress;
import java.util.concurrent.locks.LockSupport;

public class WintunAdapter {
    private final WINTUN_ADAPTER_HANDLE adapter;

    public WintunAdapter(final WINTUN_ADAPTER_HANDLE adapter) {
        this.adapter = adapter;
    }

    public WintunSession startSession() throws IOException {
        return startSession(0x400000);
    }

    public WintunSession startSession(final int capacity) throws IOException {
        WINTUN_SESSION_HANDLE session = null;
        try {
            session = WintunStartSession(adapter, new WinDef.DWORD(capacity));
            return new WintunSession(this, session);
        } catch (final LastErrorException e) {
            if (session != null) {
                WintunEndSession(session);
            }
            throw new IOException(e);
        }
    }

    public long getLuid() {
        final LongByReference luidRef = new LongByReference();
        WintunLib.WintunGetAdapterLUID(adapter, luidRef);
        return luidRef.getValue();
    }


    public int getMTU(int ipFamily) {
        IpHelpLib.MIB_IPINTERFACE_ROW row = new IpHelpLib.MIB_IPINTERFACE_ROW();
        IpHelpLib.INSTANCE.InitializeIpInterfaceEntry(row);
        row.InterfaceLuid = getLuid();
        row.Family = ipFamily;
        int i = IpHelpLib.INSTANCE.GetIpInterfaceEntry(row);
        if (WinError.NO_ERROR != i) {
            throw new Win32Exception(i);
        }
        return row.NlMtu;
    }

    public void setMTU(int ipFamily, int mtu) {
        IpHelpLib.MIB_IPINTERFACE_ROW row = new IpHelpLib.MIB_IPINTERFACE_ROW();
        IpHelpLib.INSTANCE.InitializeIpInterfaceEntry(row);
        row.InterfaceLuid = getLuid();
        row.Family = ipFamily;
        int i = IpHelpLib.INSTANCE.GetIpInterfaceEntry(row);
        if (WinError.NO_ERROR != i) {
            throw new Win32Exception(i);
        }

        row.NlMtu = mtu;
        IpHelpLib.INSTANCE.SetIpInterfaceEntry(row);
    }

    public void close() {
        WintunCloseAdapter(adapter);
    }

    public static WintunAdapter open(String name, final String type, final String guid) throws IOException {
        return open(name, type, GUID.fromString(guid));
    }

    /**
     * @param tunName the name of the tun adapter
     * @param tunType the type of the tun adapter, null for open existing one, required when creating new adapter.
     * @param guid
     * @return
     * @throws IOException
     */
    public static WintunAdapter open(String tunName, final String tunType, final GUID guid) throws IOException {
        if (tunName == null) {
            tunName = "tun";
        }

        WINTUN_ADAPTER_HANDLE adapter = null;
        try {
            if (null == guid) {
                adapter = WintunOpenAdapter(new WString(tunName));
                if (null == adapter) {
                    throw new IOException("Failed to open tun device " + tunName);
                }
            } else {
                adapter = WintunCreateAdapter(new WString(tunName), new WString(tunType), guid);
            }
            return new WintunAdapter(adapter);
        } catch (final LastErrorException e) {
            if (adapter != null) {
                WintunCloseAdapter(adapter);
            }
            throw new IOException(e);
        }
    }


    public static void main(String[] args) throws IOException {
         final GUID guid = GUID.newGuid();
//        final GUID guid = GUID.fromString("{E3E33B4A-2E25-4168-A732-52BBDFD9EE57}");
        final String guidStr = guid.toGuidString();

        final WintunAdapter adapter = WintunAdapter.open("wintun", "wintun", guid);

        final WindowsNetworkInterfaceEx nix = WindowsNetworkInterfaceEx.getByLuid(adapter.getLuid());
        nix.addInterfaceAddress(InterfaceAddressEx.of("172.16.0.1", 24));
        nix.setInterfaceDns(new InetAddress[]{InetAddress.getByName("127.0.0.1")});

        System.out.println(guidStr);
        System.out.println(adapter.adapter);

        final WintunSession session = adapter.startSession();

        LockSupport.park();
//        Pointer pointer = Pointer.createConstant(adapter.getLuid());
//        LongByReference r = new LongByReference(adapter.getLuid());
//        adapter.setIpAddress(InetAddress.getByName("192.168.1.1"), (byte) 24);
//        WintunSession session = adapter.startSession();
    }
}