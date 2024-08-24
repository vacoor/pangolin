package com.github.pangolin.routing.tun.wintun.win32;

import static com.github.pangolin.routing.tun.wintun.win32.IpHelpLib.*;
import static org.drasyl.channel.tun.jna.windows.Wintun.WintunCloseAdapter;
import static org.drasyl.channel.tun.jna.windows.Wintun.WintunCreateAdapter;
import static org.drasyl.channel.tun.jna.windows.Wintun.WintunEndSession;
import static org.drasyl.channel.tun.jna.windows.Wintun.WintunGetAdapterLUID;
import static org.drasyl.channel.tun.jna.windows.Wintun.WintunStartSession;

import com.sun.jna.LastErrorException;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import lombok.extern.slf4j.Slf4j;
import org.drasyl.channel.tun.jna.windows.Guid;
import org.drasyl.channel.tun.jna.windows.WinDef;
import org.drasyl.channel.tun.jna.windows.Wintun;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

@Slf4j
public class Main {

    public static void main(String[] args) throws IOException {
        final int family = AF_INET;
        // final long interfaceLuid = NetworkInterfaceEx.interfaceAliasToLuid("以太网 2");
        final Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
        while (networkInterfaces.hasMoreElements()) {
            final NetworkInterface ni = networkInterfaces.nextElement();
            final String name = ni.getName();
            final String alias = ni.getDisplayName();
            final int index = ni.getIndex();
            final int mtu = ni.getMTU();
            System.out.println("------");
            System.out.println(String.format("[%s] %s / %s -> %s", index, name, alias, mtu));

            final long luid = NetworkInterfaceEx.interfaceIndexToLuid(index);
            int mtu1 = NetworkInterfaceEx.getMTU(luid, AF_INET);
            System.out.println(mtu1);
//            ni.getInetAddresses()
//            ni.getInterfaceAddresses().get(0).getNetworkPrefixLength()
        }


        System.out.println();
        System.exit(0);
        final Guid.GUID guid = Guid.GUID.newGuid();

//        listAssociatedAddresses(IPHlpAPI.AF_UNSPEC);
        Wintun.WINTUN_ADAPTER_HANDLE adapter = null;
        Wintun.WINTUN_SESSION_HANDLE session = null;
        try {
            adapter = WintunCreateAdapter(new WString("iTun"), new WString("PAN"), guid);
            final Pointer luidRef = new Memory(Native.POINTER_SIZE);
            WintunGetAdapterLUID(adapter, luidRef);
            final long luid = luidRef.getLong(0);

            NetworkInterfaceEx.addInterfaceAddress(luid, InetAddress.getByName("198.18.0.1"), (byte) 24);
            NetworkInterfaceEx.setInterfaceDns(NetworkInterfaceEx.interfaceLuidToGuid(luid), AF_INET, new InetAddress[]{InetAddress.getByName("198.18.0.2")}, new String[0]);

            session = WintunStartSession(adapter, new WinDef.DWORD(0x400000));

            final MIB_IPINTERFACE_ROW row = new MIB_IPINTERFACE_ROW();

            INSTANCE.InitializeIpInterfaceEntry(row);
            row.Family = AF_INET;
            row.InterfaceLuid = luid;

            int i = INSTANCE.GetIpInterfaceEntry(row);
            System.out.println(row.Metric);
            System.out.println(row.NlMtu);
            System.out.println();
//            flushInterfaceAddresses(luid, AF_UNSPEC);

            row.Metric = 6;
            row.NlMtu = 65500;

            int i1 = INSTANCE.SetIpInterfaceEntry(row);

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
