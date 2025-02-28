package com.github.pangolin.routing.server.tun.adapter.windows;

import static com.github.pangolin.routing.server.tun.adapter.windows.jna.IpHelpLib.SOCKADDR_INET;
import static com.github.pangolin.routing.server.tun.adapter.windows.jna.IpHelpLib.sockaddr_in;
import static com.github.pangolin.routing.server.tun.adapter.windows.jna.IpHelpLib.sockaddr_in6;
import static com.sun.jna.platform.win32.IPHlpAPI.AF_INET;
import static com.sun.jna.platform.win32.IPHlpAPI.AF_INET6;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 *
 */
public class WindowsUtils {

    static SOCKADDR_INET toSockAddr(final InetAddress address) {
        return writeSockAddr(new SOCKADDR_INET(), address);
    }

    static SOCKADDR_INET writeSockAddr(final SOCKADDR_INET sockAddr, final InetAddress address) {
        if (address instanceof Inet4Address) {
            final sockaddr_in sockaddrIn = new sockaddr_in();
            sockaddrIn.sin_family = AF_INET;
            sockaddrIn.sin_port = 0;
            sockaddrIn.sin_addr = address.getAddress();

            // sockAddr.si_family = sockaddrIn.sin_family;
            sockAddr.setTypedValue(sockaddrIn);
        } else if (address instanceof Inet6Address) {
            final sockaddr_in6 sockaddrIn6 = new sockaddr_in6();
            sockaddrIn6.sin6_family = AF_INET6;
            sockaddrIn6.sin6_port = 0;
            sockaddrIn6.sin6_addr = address.getAddress();
            sockaddrIn6.sin6_scope_id = ((Inet6Address) address).getScopeId();

            // sockAddr.si_family = sockaddrIn6.sin6_family;
            sockAddr.setTypedValue(sockaddrIn6);
        } else {
            throw new UnsupportedOperationException();
        }
        return sockAddr;
    }

    static InetAddress toInetAddress(final SOCKADDR_INET sockAddr) {
        if (AF_INET == sockAddr.si_family) {
            final sockaddr_in v4 = (sockaddr_in) sockAddr.getTypedValue(sockaddr_in.class);
            return toInetAddress(v4.sin_addr);
        } else if (AF_INET6 == sockAddr.si_family) {
            final sockaddr_in6 v6 = (sockaddr_in6) sockAddr.getTypedValue(sockaddr_in6.class);
            return toInet6Address(v6.sin6_addr, v6.sin6_scope_id);
        } else {
            throw new IllegalStateException("Unknown si family: " + sockAddr.si_family);
        }
    }

    static InetAddress toInetAddress(final byte[] sinAddr) {
        try {
            return InetAddress.getByAddress(sinAddr);
        } catch (final UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    static Inet6Address toInet6Address(final byte[] sin6Addr, final int scopeId) {
        try {
            return Inet6Address.getByAddress(null, sin6Addr, scopeId);
        } catch (final UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

}
