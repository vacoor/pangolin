package com.github.pangolin.routing.acceptor.tun.adapter;

import java.net.SocketException;
import java.util.List;

/**
 * This class represents a Network Interface made up of a name,
 * and a list of IP addresses assigned to this interface.
 */
public interface NetworkInterfaceEx {

    /**
     * Get the name of this network interface.
     *
     * @return the name of this network interface
     */
    String name();

    /**
     * Get a List of the {@code InterfaceAddresses}
     * of this network interface.
     *
     * @return a {@code List} object with all of the
     *         InterfaceAddresss of this network interface
     */
    List<InterfaceAddressEx> getInterfaceAddresses();

    /**
     * Set the {@code InterfaceAddresses} of this network interface.
     *
     * @param address a InterfaceAddresses bound to this network interface
     */
    void setInterfaceAddress(final InterfaceAddressEx address);

    /**
     * Add the {@code InterfaceAddresses} of this network interface.
     *
     * @param address a InterfaceAddresses bound to this network interface
     */
    void addInterfaceAddress(final InterfaceAddressEx address);

    /**
     * Delete the {@code InterfaceAddresses} of this network interface.
     *
     * @param address a InterfaceAddresses bound to this network interface
     */
    void deleteInterfaceAddress(final InterfaceAddressEx address);

    /**
     * Flush the {@code InterfaceAddresses} of this network interface.
     */
    void flushInterfaceAddresses();

    /**
     * Returns the Maximum Transmission Unit (MTU) of this interface.
     *
     * @return the value of the MTU for that interface.
     */
    int getMTU() throws SocketException;

}
