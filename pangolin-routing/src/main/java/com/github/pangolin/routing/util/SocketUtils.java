package com.github.pangolin.routing.util;

import io.netty.util.NetUtil;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

public abstract class SocketUtils {
    private SocketUtils() {
    }

    public static InetAddress addressByName(final String host) {
        return addressByName(host, true);
    }

    public static InetAddress addressByName(final String host, final boolean resolve) {
        try {
            if (resolve) {
                return InetAddress.getByName(host);
            }
            if (NetUtil.isValidIpV4Address(host) || NetUtil.isValidIpV6Address(host)) {
                final byte[] addr = NetUtil.createByteArrayFromIpAddressString(host);
                return null != addr ? InetAddress.getByAddress(addr) : null;
            }
            return null;
        } catch (final UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    public static InetSocketAddress toSocketAddress(final String host, final int port) {
        return toSocketAddress(host, port, true);
    }

    public static InetSocketAddress toSocketAddress(final String host, final int port, final boolean resolve) {
        final InetAddress inetAddress = addressByName(host, resolve);
        return null != inetAddress ? new InetSocketAddress(inetAddress, port) : InetSocketAddress.createUnresolved(host, port);
    }

    public static InetAddress getAddress(final InetSocketAddress socketAddress, final boolean resolve) {
        if (!socketAddress.isUnresolved()) {
            return socketAddress.getAddress();
        }
        return addressByName(socketAddress.getHostString(), resolve);
    }

}