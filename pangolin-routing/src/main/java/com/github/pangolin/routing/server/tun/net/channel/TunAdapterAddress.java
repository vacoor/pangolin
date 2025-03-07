package com.github.pangolin.routing.server.tun.net.channel;

import com.github.pangolin.routing.server.tun.adapter.InterfaceAddressEx;

import java.net.InetAddress;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.List;

public class TunAdapterAddress extends SocketAddress {
    private final String name;
    private final InterfaceAddressEx[] bindings;

    public TunAdapterAddress(final String name, final InterfaceAddressEx... bindings) {
        this.name = name;
        this.bindings = bindings;
    }

    /**
     * Returns the name of the tun device.
     *
     * @return the name of the tun device
     */
    public String name() {
        return name;
    }

    public List<InterfaceAddressEx> getInterfaceAddresses() {
        return Arrays.asList(bindings);
    }

    @Override
    public String toString() {
        // return Objects.requireNonNullElse(name, "");
        return name;
    }
}
