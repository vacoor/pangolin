package com.github.pangolin.routing.server.tun.adapter.unix;


import com.github.pangolin.routing.server.tun.adapter.InterfaceAddressEx;
import com.github.pangolin.routing.server.tun.adapter.NetworkInterfaceEx;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

/**
 *
 */
public abstract class UnixNetworkInterface implements NetworkInterfaceEx {

    @Override
    public void setInterfaceAddress(final InterfaceAddressEx address) {
        final InetAddress addr = address.getAddress();
        final int networkPrefixLength = address.getNetworkPrefixLength();

        if (addr instanceof Inet4Address) {
            setInterfaceAddress4((Inet4Address) addr, networkPrefixLength);
        } else if (addr instanceof Inet6Address) {
            setInterfaceAddress6((Inet6Address) addr, networkPrefixLength);
        } else {
            throw new UnsupportedOperationException();
        }
    }

    protected abstract void setInterfaceAddress4(final Inet4Address address, final int prefix);

    protected abstract void setInterfaceAddress6(final Inet6Address address, final int prefix);

    @Override
    public void addInterfaceAddress(InterfaceAddressEx addressEx) {
        final InetAddress addr = addressEx.getAddress();
        final int prefix = addressEx.getNetworkPrefixLength();

        if (addr instanceof Inet4Address) {
            addInterfaceAddress4((Inet4Address) addr, prefix);
        } else if (addr instanceof Inet6Address) {
            addInterfaceAddress6((Inet6Address) addr, prefix);
        } else {
            throw new UnsupportedOperationException();
        }
    }

    protected abstract void addInterfaceAddress4(final Inet4Address address, final int prefix);

    protected abstract void addInterfaceAddress6(final Inet6Address address, final int prefix);

    @Override
    public void deleteInterfaceAddress(InterfaceAddressEx address) {
        final InetAddress addr = address.getAddress();
        final int networkPrefixLength = address.getNetworkPrefixLength();
        if (addr instanceof Inet4Address) {
            deleteInterfaceAddress4((Inet4Address) addr, networkPrefixLength);
        } else if (addr instanceof Inet6Address) {
            deleteInterfaceAddress6((Inet6Address) addr, networkPrefixLength);
        } else {
            throw new UnsupportedOperationException();
        }
    }

    protected abstract void deleteInterfaceAddress4(final Inet4Address address, final int prefix);

    protected abstract void deleteInterfaceAddress6(final Inet6Address address, final int prefix);

    @Override
    public void flushInterfaceAddresses() {
        flushInterfaceAddresses4();
        flushInterfaceAddresses6();
    }

    protected abstract void flushInterfaceAddresses4();

    protected abstract void flushInterfaceAddresses6();

}
