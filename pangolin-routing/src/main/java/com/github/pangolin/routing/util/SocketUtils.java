package com.github.pangolin.routing.util;

import io.netty.util.NetUtil;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

public class SocketUtils {

    public static InetSocketAddress toSocketAddress(final String hostname, final int port) {
        return toSocketAddress(hostname, port, true);
    }

    public static InetSocketAddress toSocketAddress(final String hostname, final int port, final boolean resolve) {
        try {
            if (NetUtil.isValidIpV4Address(hostname) || NetUtil.isValidIpV6Address(hostname)) {
                final byte[] addr = NetUtil.createByteArrayFromIpAddressString(hostname);
                if (null != addr) {
                    final InetAddress address = InetAddress.getByAddress(addr);
                    return new InetSocketAddress(address, port);
                }
            }
            return resolve ? new InetSocketAddress(hostname, port) : InetSocketAddress.createUnresolved(hostname, port);
        } catch (final UnknownHostException e) {
            throw new RuntimeException(e);
        }
    }

}