package com.github.pangolin.routing.server.tun.adapter.darwin.jna;


import static java.nio.charset.StandardCharsets.US_ASCII;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 *
 */
public class Utils {

    public static byte[] toBytes(final String name, final int len) {
        final byte[] bytes = new byte[len];
        writeTo(name, bytes, 0);
        return bytes;
    }

    public static int writeTo(final String value, final byte[] target, final int offset) {
        if (null == value) {
            return 0;
        }
        final byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        final int len = Math.min(target.length - offset, bytes.length);
        System.arraycopy(bytes, 0, target, offset, len);
        return len;
    }

    public static void main(String[] args) {
        String name = "utun8";
        byte[] ifra_name = new byte[16];
        if (name != null) {
            final byte[] bytes = name.getBytes(US_ASCII);
            System.arraycopy(bytes, 0, ifra_name, 0, bytes.length);
        }

        byte[] bytes = toBytes(name, ifra_name.length);
        System.out.println(Arrays.equals(ifra_name, bytes));
    }
}
