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
import java.net.Inet4Address;
import java.net.Inet6Address;
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

            WinNetworkInterface.setInetAddress(luid, InetAddress.getByName("198.18.0.3"), (byte) 24);

//            flushAddresses(luid, AF_UNSPEC);

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




}
