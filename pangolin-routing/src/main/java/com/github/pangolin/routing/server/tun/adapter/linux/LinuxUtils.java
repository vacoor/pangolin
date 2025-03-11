package com.github.pangolin.routing.server.tun.adapter.linux;

import com.github.pangolin.routing.server.tun.adapter.unix.jna.LibC;
import com.sun.jna.LastErrorException;
import com.sun.jna.Native;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

import static com.github.pangolin.routing.server.tun.adapter.linux.jna.If.sockaddr_in;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.If.sockaddr_in6;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.Socket.AF_INET;
import static com.github.pangolin.routing.server.tun.adapter.linux.jna.Socket.AF_INET6;

class LinuxUtils {

    private static final LibC LIBC = LibC.INSTANTCE;

    static sockaddr_in writeSockAddr4(final sockaddr_in sockAddr, final Inet4Address addr) {
        return writeSockAddr4(sockAddr, addr.getAddress());
    }

    static sockaddr_in writeSockAddr4(final sockaddr_in sockAddr, final byte[] addr) {
        assert addr.length == 4;
        sockAddr.sin_family = AF_INET;
        sockAddr.sin_port = 0;
        sockAddr.sin_addr = addr;
        return sockAddr;
    }

    static Inet4Address toInet4Address(final sockaddr_in sockAddr) {
        assert sockAddr.sin_family == AF_INET;
        try {
            return (Inet4Address) InetAddress.getByAddress(sockAddr.sin_addr);
        } catch (final UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
    }

    static sockaddr_in6 writeSockAddr6(final sockaddr_in6 sockAddr6, final Inet6Address addr) {
        return writeSockAddr6(sockAddr6, addr.getAddress(), addr.getScopeId());
    }

    static sockaddr_in6 writeSockAddr6(final sockaddr_in6 sockAddr6, final byte[] addr, final int scopeId) {
        assert addr.length == 16;
        sockAddr6.sin6_family = AF_INET6;
        sockAddr6.sin6_port = 0;
        sockAddr6.sin6_addr = addr;
        sockAddr6.sin6_scope_id = scopeId;
        return sockAddr6;
    }

    static Inet6Address toInet6Address(final sockaddr_in6 sockAddr) {
        assert sockAddr.sin6_family == AF_INET6;
        try {
            return Inet6Address.getByAddress(null, sockAddr.sin6_addr, sockAddr.sin6_scope_id);
        } catch (final UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Deprecated
    public static void throwLastErrorException(final int errno) {
        final String errmsg = String.format("[%s] %s", errno, LIBC.strerror(errno));
        throw new LinuxLastErrorException(errno, errmsg);
    }

    public static void throwLastErrorException(final String message) {
        final int errno = Native.getLastError();
        final String errmsg = LIBC.strerror(errno);

        final String errmsgToUse = null == message || message.isEmpty()
                ? String.format("[%s] %s", errno, errmsg)
                : String.format("%s: [%s] %s", message, errno, errmsg);
        throw new LinuxLastErrorException(errno, errmsgToUse);
    }


    private static class LinuxLastErrorException extends LastErrorException {

        LinuxLastErrorException(final int errno, final String errmsg) {
            super(errno, errmsg);
        }

    }
}