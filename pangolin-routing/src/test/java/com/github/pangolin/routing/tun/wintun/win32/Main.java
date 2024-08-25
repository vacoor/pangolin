package com.github.pangolin.routing.tun.wintun.win32;

import com.sun.jna.*;
import lombok.extern.slf4j.Slf4j;
import org.drasyl.channel.tun.jna.windows.Guid;
import org.drasyl.channel.tun.jna.windows.WinDef;
import org.drasyl.channel.tun.jna.windows.Wintun;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.List;

import static org.drasyl.channel.tun.jna.windows.Wintun.*;

@Slf4j
public class Main {

    public static void main(String[] args) throws IOException {
        final Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
        while (nis.hasMoreElements()) {
            final NetworkInterface ni = nis.nextElement();
            if (!ni.isLoopback() && ni.isUp()) {
            }

            final List<InetAddress> interfaceDns = WindowsNetworkInterfaceEx.getByAlias("以太网 2").getInterfaceDns(false);
            System.out.println(interfaceDns);
        }

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
