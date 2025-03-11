package com.github.pangolin.routing.server.tun.adapter.unix;


import com.github.pangolin.routing.server.tun.adapter.InterfaceAddressEx;
import com.github.pangolin.routing.server.tun.adapter.NetworkInterfaceEx;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.channels.UnsupportedAddressTypeException;

/**
 * This class represents a Network Interface on Unix-like OS.
 */
public abstract class UnixNetworkInterface implements NetworkInterfaceEx {

    /**
     * {@inheritDoc}
     */
    @Override
    public void setInterfaceAddress(final InterfaceAddressEx address) {
        final InetAddress addr = address.getAddress();
        final int networkPrefixLength = address.getNetworkPrefixLength();
        if (addr instanceof Inet4Address) {
            setInet4InterfaceAddress((Inet4Address) addr, networkPrefixLength);
        } else if (addr instanceof Inet6Address) {
            setInet6InterfaceAddress((Inet6Address) addr, networkPrefixLength);
        } else {
            throwUnsupportedAddressFamilyException(addr);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addInterfaceAddress(final InterfaceAddressEx address) {
        final InetAddress addr = address.getAddress();
        final int prefix = address.getNetworkPrefixLength();
        if (addr instanceof Inet4Address) {
            addInet4InterfaceAddress((Inet4Address) addr, prefix);
        } else if (addr instanceof Inet6Address) {
            addInet6InterfaceAddress((Inet6Address) addr, prefix);
        } else {
            throwUnsupportedAddressFamilyException(addr);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deleteInterfaceAddress(final InterfaceAddressEx address) {
        final InetAddress addr = address.getAddress();
        final int networkPrefixLength = address.getNetworkPrefixLength();
        if (addr instanceof Inet4Address) {
            deleteInet4InterfaceAddress((Inet4Address) addr, networkPrefixLength);
        } else if (addr instanceof Inet6Address) {
            deleteInet6InterfaceAddress((Inet6Address) addr, networkPrefixLength);
        } else {
            throwUnsupportedAddressFamilyException(addr);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void flushInterfaceAddresses() {
        flushInet4InterfaceAddresses();
        flushInet6InterfaceAddresses();
    }

    /**
     * Set the IPv4 {@code InterfaceAddresses} of this network interface.
     *
     * @param address a IPv4 InterfaceAddresses bound to this network interface
     */
    protected abstract void setInet4InterfaceAddress(final Inet4Address address, final int prefix);

    /**
     * Set the IPv6 {@code InterfaceAddresses} of this network interface.
     *
     * @param address a IPv6 InterfaceAddresses bound to this network interface
     */
    protected abstract void setInet6InterfaceAddress(final Inet6Address address, final int prefix);

    /**
     * Add the IPv4 {@code InterfaceAddresses} of this network interface.
     *
     * @param address a IPv4 InterfaceAddresses bound to this network interface
     */
    protected abstract void addInet4InterfaceAddress(final Inet4Address address, final int prefix);

    /**
     * Add the IPv6 {@code InterfaceAddresses} of this network interface.
     *
     * @param address a IPv6 InterfaceAddresses bound to this network interface
     */
    protected abstract void addInet6InterfaceAddress(final Inet6Address address, final int prefix);

    /**
     * Delete the IPv4 {@code InterfaceAddresses} of this network interface.
     *
     * @param address a IPv4 InterfaceAddresses bound to this network interface
     */
    protected abstract void deleteInet4InterfaceAddress(final Inet4Address address, final int prefix);

    /**
     * Delete the IPv6 {@code InterfaceAddresses} of this network interface.
     *
     * @param address a IPv6 InterfaceAddresses bound to this network interface
     */
    protected abstract void deleteInet6InterfaceAddress(final Inet6Address address, final int prefix);

    /**
     * Flush the IPv4 {@code InterfaceAddresses} of this network interface.
     */
    protected abstract void flushInet4InterfaceAddresses();

    /**
     * Flush the IPv6 {@code InterfaceAddresses} of this network interface.
     */
    protected abstract void flushInet6InterfaceAddresses();

    protected void throwUnsupportedAddressFamilyException(final InetAddress address) {
        throw new UnsupportedAddressTypeException();
    }
}
