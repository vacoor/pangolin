package com.github.pangolin.routing.server.tun.net.channel;

import java.net.InetAddress;
import java.net.SocketAddress;

public class TunSocketAddress extends SocketAddress {
    private static final long serialVersionUID = -584786182484350484L; // NOSONAR
    private final String ifName;
    private final InetAddress address;
    private final int prefix;

    public TunSocketAddress(final String ifName, final InetAddress address, final int prefix) {
        this.ifName = ifName;
        this.address = address;
        this.prefix = prefix;
    }

    /**
     * Returns the name of the tun device.
     *
     * @return the name of the tun device
     */
    public String ifName() {
        return ifName;
    }

    public InetAddress getAddress() {
        return address;
    }

    public int networkPrefixLength() {
        return prefix;
    }

    @Override
    public String toString() {
        // return Objects.requireNonNullElse(ifName, "");
        return ifName;
    }
}
