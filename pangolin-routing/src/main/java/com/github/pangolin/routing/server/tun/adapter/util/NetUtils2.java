package com.github.pangolin.routing.server.tun.adapter.util;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

public class NetUtils2 {
    public static <A extends InetAddress> A cidrToNetmaskAddress(final A address, final int prefix) {
        byte[] bytes = cidrPrefixToNetmask(new byte[address.getAddress().length], prefix);
        try {
            return (A) InetAddress.getByAddress(bytes);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] cidrPrefixToNetmask(final byte[] bytes, int prefix) {
        final byte[] netmask = Arrays.copyOf(bytes, bytes.length);
        Arrays.fill(netmask, (byte) 0xFF);
        netmask[prefix / Byte.SIZE] <<= prefix % Byte.SIZE;
        prefix += prefix % Byte.SIZE;
        for (int i = prefix / Byte.SIZE; i < netmask.length; i++) {
            netmask[i] = 0;
        }
        return netmask;
    }

    public static int netmaskToPrefixLength(final byte[] ipBytes) {
        int prefix_length = 0;
        for (byte b : ipBytes) {
            if ((b & 0xFF) == 0xFF) {
                prefix_length += Byte.SIZE;
                continue;
            }
            for (int j = 0; j < Byte.SIZE; j++) {
                if ((b >> j & 1) != 1) {
                    return prefix_length;
                }
                prefix_length += 1;
            }
        }
        return prefix_length;
    }
}