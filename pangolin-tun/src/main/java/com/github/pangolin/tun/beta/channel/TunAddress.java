package com.github.pangolin.tun.beta.channel;

import java.net.SocketAddress;

public class TunAddress extends SocketAddress {
    private static final long serialVersionUID = -584786182484350484L; // NOSONAR
    private final String ifName;

    public TunAddress(final String ifName) {
        this.ifName = ifName;
    }

    public TunAddress() {
        this(null);
    }

    /**
     * Returns the name of the tun device.
     *
     * @return the name of the tun device
     */
    public String ifName() {
        return ifName;
    }

    @Override
    public String toString() {
        // return Objects.requireNonNullElse(ifName, "");
        return ifName;
    }
}
