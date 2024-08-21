package com.github.pangolin.routing.beta.tun.jna.win32;

import static org.drasyl.channel.tun.jna.windows.Wintun.WintunCloseAdapter;
import static org.drasyl.channel.tun.jna.windows.Wintun.WintunCreateAdapter;
import static org.drasyl.channel.tun.jna.windows.Wintun.WintunEndSession;
import static org.drasyl.channel.tun.jna.windows.Wintun.WintunStartSession;

import com.github.pangolin.routing.beta.tun.jna.win32.iphlp.MibUnicastIPAddressRow;
import com.github.pangolin.routing.beta.tun.jna.win32.iphlp.MibUnicastIPAddressTable;
import com.github.pangolin.routing.beta.tun.jna.win32.iphlp.SocketAddrIn;
import com.github.pangolin.routing.beta.tun.jna.win32.iphlp.SocketAddrIn6;
import com.sun.jna.LastErrorException;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.IPHlpAPI;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;
import freework.codec.Hex;
import org.drasyl.channel.tun.jna.windows.Guid;
import org.drasyl.channel.tun.jna.windows.WinDef;
import org.drasyl.channel.tun.jna.windows.Wintun;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

public class Main {

    public static void main(String[] args) throws IOException {
//        System.out.println(NetioAPI.INSTANCE);
        final String id = "{22430978-1194-4d70-b652-f1546d123aff}";
        final String s = id.replaceAll("^\\{|\\}$|-", "");
        final Guid.GUID guid = Guid.GUID.fromBinary(Hex.decode(s));
//        Guid.GUID.newGuid();

//        listAssociatedAddresses(IPHlpAPI.AF_UNSPEC);
        Wintun.WINTUN_ADAPTER_HANDLE adapter = null;
        Wintun.WINTUN_SESSION_HANDLE session = null;
        try {
            adapter = WintunCreateAdapter(new WString("iTun"), new WString("PAN"), guid);

            final MibUnicastIPAddressRow row = createMibUnicastIpAddressRow(Inet4Address.getByName("172.16.1.1"), adapter);
            row.OnLinkPrefixLength = 24;
            row.ValidLifetime = 1000;
            row.PreferredLifetime = 1000;

            int err = ExtendedIPHlpAPI.INSTANCE.CreateUnicastIpAddressEntry(row);
            if (WinError.NO_ERROR != err) {
                throw new RuntimeException("Failed to create new MIB_UNICASTIPADDRESS_ROW: " + err);
            }
            /*
            if (WinError.NO_ERROR != err && WinError.ERROR_OBJECT_ALREADY_EXISTS != err) {
                throw new RuntimeException("Failed to create new MIB_UNICASTIPADDRESS_ROW: " + err);
            }
            */
            session = WintunStartSession(adapter, new WinDef.DWORD(0x400000));

            System.out.println();
        } catch (final LastErrorException e) {
            if (session != null) {
                WintunEndSession(session);
            }

            if (adapter != null) {
                WintunCloseAdapter(adapter);
            }

            throw new IOException(e);
        }
    }

    /**
     * Create and initialize a [MibUnicastIPAddressRow], fill the luid and ip.
     * */
    private static MibUnicastIPAddressRow createMibUnicastIpAddressRow(InetAddress address, Wintun.WINTUN_ADAPTER_HANDLE adapter){
        final MibUnicastIPAddressRow row = new MibUnicastIPAddressRow();
        ExtendedIPHlpAPI.INSTANCE.InitializeUnicastIpAddressEntry(row);

        final Pointer memory = new Memory(Native.POINTER_SIZE);
        Wintun.WintunGetAdapterLUID(adapter, memory);
        row.InterfaceLuid = memory.getLong(0);

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

    /**
     * List all ip address related to this adapter.
     *
     * @param ipFamily Must be [IPHlpAPI.AF_INET], [IPHlpAPI.AF_INET6] or [IPHlpAPI.AF_UNSPEC]
     * @return List of [AdapterIPAddress], representing an IP.
     * */
    public static void listAssociatedAddresses(int ipFamily) throws UnknownHostException {
        final PointerByReference pointerByReference = new PointerByReference();
        final int err = ExtendedIPHlpAPI.INSTANCE.GetUnicastIpAddressTable(ipFamily, pointerByReference);
        // something wrong
        if (err != WinError.NO_ERROR && err != WinError.ERROR_NOT_FOUND)
            throw new RuntimeException("Failed to list unicast ip addresses:" + err);
        // no ip, return empty list
        if (err != WinError.NO_ERROR) {
            //return emptyList();
            System.out.println("EMPTY");
            return;
        }

        // parsing pointer
        final MibUnicastIPAddressTable table = new MibUnicastIPAddressTable(pointerByReference.getValue());
        // "MIB_UNICASTIPADDRESS_TABLE size not match. Expect ${table.NumEntries}, actual: ${table.Table.size}"
        assert table.NumEntries == table.Table.length;
        for (final MibUnicastIPAddressRow row : table.Table) {
            if (IPHlpAPI.AF_INET == row.Address.si_family) {
                final SocketAddrIn v4 = (SocketAddrIn) row.Address.getTypedValue(SocketAddrIn.class);
                InetAddress inet4 = InetAddress.getByAddress(v4.sin_addr);
                System.out.println(inet4);
            } else if (IPHlpAPI.AF_INET6 == row.Address.si_family) {
                final SocketAddrIn6 v6 = (SocketAddrIn6) row.Address.getTypedValue(SocketAddrIn6.class);
                InetAddress inet6 = InetAddress.getByAddress(v6.sin6_addr);
                System.out.println(inet6);
            } else {
                //  Unknown si family: ${it.Address.si_family}
            }
            System.out.println(row.Address.si_family);
        }
        ExtendedIPHlpAPI.INSTANCE.FreeMibTable(table.getPointer());
    }

}
