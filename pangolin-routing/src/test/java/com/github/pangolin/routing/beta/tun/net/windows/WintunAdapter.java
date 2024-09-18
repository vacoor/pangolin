package com.github.pangolin.routing.beta.tun.net.windows;

import com.github.pangolin.routing.beta.tun.net.InterfaceAddressEx;
import com.github.pangolin.routing.beta.tun.net.windows.win32.WindowsNetworkInterfaceEx;
import com.github.pangolin.routing.beta.tun.net.windows.win32.jna.IpHelpLib;
import com.sun.jna.LastErrorException;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.*;
import com.sun.jna.ptr.LongByReference;

import java.io.IOException;
import java.net.InetAddress;
import java.util.concurrent.locks.LockSupport;

import static com.github.pangolin.routing.beta.tun.net.windows.WintunLib.*;
import static com.sun.jna.platform.win32.Guid.GUID;

public class WintunAdapter {
    private final WINTUN_ADAPTER_HANDLE adapter;

    public WintunAdapter(final WINTUN_ADAPTER_HANDLE adapter) {
        this.adapter = adapter;
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

    public WintunSession newSession() throws IOException {
        return newSession(0x400000);
    }

    public WintunSession newSession(final int capacity) throws IOException {
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


    public static WintunAdapter open(String name, final String type, final String guid) throws IOException {
        return open(name, type, GUID.fromString(guid));
    }

    /**
     *
     * @param name the name of the tun adapter
     * @param type the type of the tun adapter, null for open existing one, required when creating new adapter.
     * @param guid
     * @return
     * @throws IOException
     */
    public static WintunAdapter open(String name, final String type, final GUID guid) throws IOException {
        if (name == null) {
            name = "tun";
        }

        WINTUN_ADAPTER_HANDLE adapter = null;
        try {
            if (null == guid) {
                adapter = WintunOpenAdapter(new WString(name));
                if (null == adapter) {
                    throw new IOException("Failed to open tun device " + name);
                }
            } else {
                adapter = WintunCreateAdapter(new WString(name), new WString(type), guid);
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
        // final GUID guid = GUID.newGuid();
        final GUID guid = GUID.fromString("{E3E33B4A-2E25-4168-A732-52BBDFD9EE57}");
        final String guidStr = guid.toGuidString();

        final WintunAdapter adapter = WintunAdapter.open("wintun", "wintun", guid);

        final WindowsNetworkInterfaceEx nix = WindowsNetworkInterfaceEx.getByLuid(adapter.getLuid());
        nix.addInterfaceAddress(InterfaceAddressEx.of("198.18.0.1", 24));
        nix.setInterfaceDns(new InetAddress[]{InetAddress.getByName("127.0.0.1")});

        System.out.println(guidStr);
        System.out.println(adapter.adapter);

        LockSupport.park();
//        Pointer pointer = Pointer.createConstant(adapter.getLuid());
//        LongByReference r = new LongByReference(adapter.getLuid());
//        adapter.setIpAddress(InetAddress.getByName("192.168.1.1"), (byte) 24);
//        WintunSession session = adapter.newSession();
    }
}