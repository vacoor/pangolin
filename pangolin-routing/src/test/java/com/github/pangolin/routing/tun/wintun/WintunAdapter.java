package com.github.pangolin.routing.tun.wintun;

import com.github.pangolin.routing.beta.tun.jna.win32.IpHelpLib;
import com.github.pangolin.routing.beta.tun.jna.win32.iphlp.MibIPInterfaceRow;
import com.github.pangolin.routing.beta.tun.jna.win32.iphlp.MibUnicastIPAddressRow;
import com.github.pangolin.routing.beta.tun.jna.win32.iphlp.SocketAddrIn;
import com.github.pangolin.routing.beta.tun.jna.win32.iphlp.SocketAddrIn6;
import com.sun.jna.LastErrorException;
import com.sun.jna.Native;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.*;
import com.sun.jna.ptr.LongByReference;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

import static com.github.pangolin.routing.tun.wintun.WintunLib.*;
import static com.sun.jna.platform.win32.Guid.GUID;

public class WintunAdapter {
    private final WINTUN_ADAPTER_HANDLE adapter;

    public WintunAdapter(final WINTUN_ADAPTER_HANDLE adapter) {
        this.adapter = adapter;
    }

    private long getLuid() {
        final LongByReference luidRef = new LongByReference();
        WintunLib.WintunGetAdapterLUID(adapter, luidRef);
        return luidRef.getValue();
    }

    /**
     * Create and initialize a [MibUnicastIPAddressRow], fill the luid and ip.
     * */
    private MibUnicastIPAddressRow createMibUnicastIpAddressRow(InetAddress address){
        final MibUnicastIPAddressRow row = new MibUnicastIPAddressRow();
        IpHelpLib.INSTANCE.InitializeUnicastIpAddressEntry(row);

        row.InterfaceLuid = getLuid();

        if (address instanceof Inet4Address) {
            row.Address.setType(SocketAddrIn.class);
            row.Address.Ipv4.sin_family = IPHlpAPI.AF_INET;
            row.Address.Ipv4.sin_port = 0;
            row.Address.Ipv4.sin_addr = address.getAddress();
        } else if (address instanceof Inet6Address) {
            row.Address.setType(SocketAddrIn6.class);
            row.Address.Ipv4.sin_family = IPHlpAPI.AF_INET6;
            row.Address.Ipv4.sin_port = 0;
            row.Address.Ipv4.sin_addr = address.getAddress();
        }
        return row;
    }

    public void setIp(final InetAddress address, final byte prefixLength) {
        final MibUnicastIPAddressRow row = createMibUnicastIpAddressRow(address);
        row.OnLinkPrefixLength = prefixLength;
        row.ValidLifetime = 1000;
        row.PreferredLifetime = 1000;

        int err = IpHelpLib.INSTANCE.CreateUnicastIpAddressEntry(row);
        if (WinError.NO_ERROR != err && err != WinError.ERROR_OBJECT_ALREADY_EXISTS) {
            throw new RuntimeException("Failed to create new MIB_UNICASTIPADDRESS_ROW: " + err);
        }
    }

    public void unsetIp(final InetAddress address) {
        MibUnicastIPAddressRow row = createMibUnicastIpAddressRow(address);
        int err = IpHelpLib.INSTANCE.DeleteUnicastIpAddressEntry(row);
        if (WinError.NO_ERROR != err) {
            throw new Win32Exception(err);
        }
    }

    public int getMTU(int ipFamily) {
        MibIPInterfaceRow row = new MibIPInterfaceRow();
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
        MibIPInterfaceRow row = new MibIPInterfaceRow();
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
        WintunAdapter adapter = WintunAdapter.open("wintun", "wintun", GUID.newGuid());
        WintunSession session = adapter.newSession();
    }
}