package com.github.pangolin.routing.acceptor.tun.net.channel;

import com.github.pangolin.routing.acceptor.tun.adapter.InterfaceAddressEx;

import java.net.SocketAddress;

public class TunAddress extends SocketAddress {
    private static final long serialVersionUID = -584786182484350484L; // NOSONAR
    private final String ifname;
    private final InterfaceAddressEx[] bindings;

    public TunAddress(final String ifname, final InterfaceAddressEx... bindings) {
        this.ifname = ifname;
        this.bindings = bindings;
    }

    public TunAddress() {
        this(null);
    }

    /**
     * Returns the name of the tun device.
     *
     * @return the name of the tun device
     */
    public String ifname() {
        return ifname;
    }

    public InterfaceAddressEx[] getInterfaceAddresses() {
        return bindings;
    }

    @Override
    public String toString() {
        // return Objects.requireNonNullElse(name, "");
        return ifname;
    }
}
