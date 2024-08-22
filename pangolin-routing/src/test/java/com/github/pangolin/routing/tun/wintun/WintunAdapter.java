package com.github.pangolin.routing.tun.wintun;

import com.github.pangolin.routing.tun.wintun.win32.iphlp.IpHelpLib;
import com.sun.jna.LastErrorException;
import com.sun.jna.Pointer;
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

    public long getLuid() {
        final LongByReference luidRef = new LongByReference();
        WintunLib.WintunGetAdapterLUID(adapter, luidRef);
        return luidRef.getValue();
    }

    /**
     * Create and initialize a [MIB_UNICASTIPADDRESS_ROW], fill the luid and ip.
     * */
    private IpHelpLib.MIB_UNICASTIPADDRESS_ROW createMibUnicastIpAddressRow(InetAddress address){
        final IpHelpLib.MIB_UNICASTIPADDRESS_ROW row = new IpHelpLib.MIB_UNICASTIPADDRESS_ROW();
        IpHelpLib.INSTANCE.InitializeUnicastIpAddressEntry(row);

        row.InterfaceLuid = getLuid();

        if (address instanceof Inet4Address) {
            row.Address.setType(IpHelpLib.sockaddr_in.class);
            row.Address.Ipv4.sin_family = IPHlpAPI.AF_INET;
            row.Address.Ipv4.sin_port = 0;
            row.Address.Ipv4.sin_addr = address.getAddress();
        } else if (address instanceof Inet6Address) {
            row.Address.setType(IpHelpLib.sockaddr_in6.class);
            row.Address.Ipv6.sin6_family = IPHlpAPI.AF_INET6;
            row.Address.Ipv6.sin6_port = 0;
            row.Address.Ipv6.sin6_addr = address.getAddress();
            row.Address.Ipv6.sin6_scope_id = ((Inet6Address)address).getScopeId();
        }
        return row;
    }

    public void setIp(final InetAddress address, final byte prefixLength) {
        final IpHelpLib.MIB_UNICASTIPADDRESS_ROW row = createMibUnicastIpAddressRow(address);
        row.OnLinkPrefixLength = prefixLength;
        row.ValidLifetime = 1000;
        row.PreferredLifetime = 1000;

        int err = IpHelpLib.INSTANCE.CreateUnicastIpAddressEntry(row);
        if (WinError.NO_ERROR != err && err != WinError.ERROR_OBJECT_ALREADY_EXISTS) {
            throw new RuntimeException("Failed to create new MIB_UNICASTIPADDRESS_ROW: " + err);
        }
    }

    public void unsetIp(final InetAddress address) {
        IpHelpLib.MIB_UNICASTIPADDRESS_ROW row = createMibUnicastIpAddressRow(address);
        int err = IpHelpLib.INSTANCE.DeleteUnicastIpAddressEntry(row);
        if (WinError.NO_ERROR != err) {
            throw new Win32Exception(err);
        }
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
        WintunAdapter adapter = WintunAdapter.open("wintun", "wintun", GUID.newGuid());
        Pointer pointer = Pointer.createConstant(adapter.getLuid());
        LongByReference r = new LongByReference(adapter.getLuid());
        AddressAndNetmaskHelper.setIPv4AndNetmask(r, "192.168.0.1", 24);
//        adapter.setIp(InetAddress.getByName("192.168.1.1"), (byte) 24);
        WintunSession session = adapter.newSession();
    }
}