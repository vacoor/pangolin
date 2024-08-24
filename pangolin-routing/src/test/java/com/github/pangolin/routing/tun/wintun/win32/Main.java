package com.github.pangolin.routing.tun.wintun.win32;

import static com.github.pangolin.routing.tun.wintun.win32.iphlp.IpHelpLib.*;
import static org.drasyl.channel.tun.jna.windows.Wintun.WintunCloseAdapter;
import static org.drasyl.channel.tun.jna.windows.Wintun.WintunCreateAdapter;
import static org.drasyl.channel.tun.jna.windows.Wintun.WintunEndSession;
import static org.drasyl.channel.tun.jna.windows.Wintun.WintunGetAdapterLUID;
import static org.drasyl.channel.tun.jna.windows.Wintun.WintunStartSession;

import com.github.pangolin.routing.tun.wintun.win32.iphlp.IpHelpLib;
import com.sun.jna.LastErrorException;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.ptr.PointerByReference;
import lombok.extern.slf4j.Slf4j;
import org.drasyl.channel.tun.jna.windows.Guid;
import org.drasyl.channel.tun.jna.windows.WinDef;
import org.drasyl.channel.tun.jna.windows.Wintun;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

@Slf4j
public class Main {



    public static void main(String[] args) throws IOException {
//        System.out.println(NetioAPI.INSTANCE);
//        listAssociatedAddresses(AF_UNSPEC);
        /*
        getAdaptersAddresses();

        System.out.println("------------");


        System.exit(0);
        */
//        final String id = "{22430978-1194-4d70-b652-f1546d123aff}";
//        final String s = id.replaceAll("^\\{|\\}$|-", "");
//        final Guid.GUID guid = Guid.GUID.fromBinary(Hex.decode(s));
        final Guid.GUID guid = Guid.GUID.newGuid();

//        listAssociatedAddresses(IPHlpAPI.AF_UNSPEC);
        Wintun.WINTUN_ADAPTER_HANDLE adapter = null;
        Wintun.WINTUN_SESSION_HANDLE session = null;
        try {
            adapter = WintunCreateAdapter(new WString("iTun"), new WString("PAN"), guid);
            final Pointer luidRef = new Memory(Native.POINTER_SIZE);
            WintunGetAdapterLUID(adapter, luidRef);
            final long luid = luidRef.getLong(0);

            WinNetworkInterface.addInetAddress(luid, InetAddress.getByName("198.18.0.1"), (byte) 24);
            WinNetworkInterface.setInterfaceDns(WinNetworkInterface.interfaceLuidToGuid(luid), AF_INET, new InetAddress[]{InetAddress.getByName("198.18.0.2")}, new String[0]);

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
     * List all ip address related to this adapter.
     *
     * @param family Must be [IPHlpAPI.AF_INET], [IPHlpAPI.AF_INET6] or [IPHlpAPI.AF_UNSPEC]
     * @return List of [AdapterIPAddress], representing an IP.
     * */
    public static void listAssociatedAddresses(int family) throws UnknownHostException {
//        final PointerByReference pointerByReference = new PointerByReference();
        final MIB_UNICASTIPADDRESS_TABLE mibUnicastIpAddressTable = new MIB_UNICASTIPADDRESS_TABLE();
        final int err = IpHelpLib.INSTANCE.GetUnicastIpAddressTable(family, mibUnicastIpAddressTable);
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
        final MIB_UNICASTIPADDRESS_TABLE table = mibUnicastIpAddressTable;//new MIB_UNICASTIPADDRESS_TABLE(pointerByReference.getValue());
        // "MIB_UNICASTIPADDRESS_TABLE size not match. Expect ${table.NumEntries}, actual: ${table.Table.size}"
        assert table.NumEntries == table.Table.length;
        for (final IpHelpLib.MIB_UNICASTIPADDRESS_ROW row : table.Table) {
            if (AF_INET == row.Address.si_family) {
                final IpHelpLib.sockaddr_in v4 = (IpHelpLib.sockaddr_in) row.Address.getTypedValue(IpHelpLib.sockaddr_in.class);
                InetAddress inet4 = InetAddress.getByAddress(v4.sin_addr);
                System.out.println(inet4);
            } else if (AF_INET6 == row.Address.si_family) {
                final IpHelpLib.sockaddr_in6 v6 = (IpHelpLib.sockaddr_in6) row.Address.getTypedValue(IpHelpLib.sockaddr_in6.class);
                InetAddress inet6 = InetAddress.getByAddress(v6.sin6_addr);
                System.out.println(inet6);
            } else {
                //  Unknown si family: ${it.Address.si_family}
            }
            System.out.println(row.Address.si_family);
        }
        IpHelpLib.INSTANCE.FreeMibTable(table.getPointer());
    }


}
