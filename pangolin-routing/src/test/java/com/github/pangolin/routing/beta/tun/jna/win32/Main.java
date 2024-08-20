package com.github.pangolin.routing.beta.tun.jna.win32;

import com.github.pangolin.routing.beta.tun.jna.win32.iphlp.MibUnicastIPAddressRow;
import com.github.pangolin.routing.beta.tun.jna.win32.iphlp.MibUnicastIPAddressTable;
import com.github.pangolin.routing.beta.tun.jna.win32.iphlp.SocketAddrIn;
import com.github.pangolin.routing.beta.tun.jna.win32.iphlp.SocketAddrIn6;
import com.sun.jna.platform.win32.IPHlpAPI;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.ptr.PointerByReference;

import java.net.InetAddress;
import java.net.UnknownHostException;

public class Main {

    public static void main(String[] args) throws UnknownHostException {
//        final MibUnicastIPAddressRow row = new MibUnicastIPAddressRow();
//        ExtendedIPHlpAPI.INSTANCE.InitializeUnicastIpAddressEntry(row);

        listAssociatedAddresses(IPHlpAPI.AF_INET6);
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
