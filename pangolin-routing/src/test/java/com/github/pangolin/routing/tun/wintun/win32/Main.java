package com.github.pangolin.routing.tun.wintun.win32;

import static org.drasyl.channel.tun.jna.windows.Wintun.WintunCloseAdapter;
import static org.drasyl.channel.tun.jna.windows.Wintun.WintunCreateAdapter;
import static org.drasyl.channel.tun.jna.windows.Wintun.WintunEndSession;
import static org.drasyl.channel.tun.jna.windows.Wintun.WintunGetAdapterLUID;
import static org.drasyl.channel.tun.jna.windows.Wintun.WintunStartSession;

import com.google.common.collect.Lists;
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
import java.net.SocketException;
import java.util.Enumeration;
import java.util.List;

@Slf4j
public class Main {

    private static List<InetAddress> allDns() throws SocketException {
        final List<InetAddress> nameServers = Lists.newLinkedList();
        final Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
        while (nis.hasMoreElements()) {
            final NetworkInterface ni = nis.nextElement();
            if (ni.isLoopback() || !ni.isUp()) {
                continue;
            }

            final int index = ni.getIndex();
            final WindowsNetworkInterfaceEx nix = WindowsNetworkInterfaceEx.getByIndex(index);
            final List<InetAddress> interfaceDns = nix.getInterfaceDns(false);

            /*
            final String name = String.format(
                    "[Java NetworkInterface name = %s, display name = %s, System Adapter name = %s, alias = %s]",
                    ni.getName(), ni.getDisplayName(), nix.name(), nix.alias()
            );
            System.out.println(String.format("%s %s", name, interfaceDns));
            */
            nameServers.addAll(interfaceDns);
        }
        return nameServers;
    }

    public static void main(String[] args) throws IOException {
        final List<InetAddress> dns = allDns();

        final Guid.GUID guid = Guid.GUID.newGuid();

        Wintun.WINTUN_ADAPTER_HANDLE adapter = null;
        Wintun.WINTUN_SESSION_HANDLE session = null;
        try {
            adapter = WintunCreateAdapter(new WString("iTun"), new WString("PAN"), guid);
            final Pointer luidRef = new Memory(Native.POINTER_SIZE);
            WintunGetAdapterLUID(adapter, luidRef);
            final long luid = luidRef.getLong(0);

            /*-
             * Initialize interface address and dns.
             */
            final WindowsNetworkInterfaceEx nix = WindowsNetworkInterfaceEx.getByLuid(luid);
            nix.addInterfaceAddress(InterfaceAddressEx.of(InetAddress.getByName("198.18.0.1"), (short) 24));
            nix.setInterfaceDns(new InetAddress[]{InetAddress.getByName("198.18.0.2")});

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




}
