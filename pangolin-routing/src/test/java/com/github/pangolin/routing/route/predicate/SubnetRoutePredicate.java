package com.github.pangolin.routing.route.predicate;

import java.net.InetAddress;
import java.net.UnknownHostException;

public abstract class SubnetRoutePredicate<T extends InetAddress> implements RoutePredicate<T> {
    private final InetAddress ipAddress;
    private final int cidrPrefix;

    private volatile InetAddress networkAddress;

    public SubnetRoutePredicate(final InetAddress ipAddress, final int cidrPrefix) {
        this.ipAddress = ipAddress;
        this.cidrPrefix = cidrPrefix;
    }

    public InetAddress getNetworkAddress() {
        if (null != networkAddress) {
            return networkAddress;
        }

        final byte[] ipBytes = ipAddress.getAddress();
        final byte[] networkBytes = new byte[ipBytes.length];
        for (int i = 0, prefix = cidrPrefix; i < ipBytes.length && prefix > 0; i++, prefix -= 8) {
            byte b = ipBytes[i];
            if (prefix < 8) {
                final int shift = 8 - prefix;
                b = (byte) ((b >> shift) << shift);
            }
            networkBytes[i] = b;
        }
        try {
            return networkAddress = InetAddress.getByAddress(networkBytes);
        } catch (final UnknownHostException e) {
            throw new IllegalStateException("Illegal network address byte length of " + networkBytes.length);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return ipAddress.getHostAddress() + "/" + cidrPrefix;
    }
}