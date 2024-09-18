package com.github.pangolin.routing.beta.tun.net;

import java.net.InetAddress;
import java.net.UnknownHostException;

public class InterfaceAddressEx {
    private final InetAddress address;
    private final int networkPrefixLength;

    private InterfaceAddressEx(final InetAddress address, final int networkPrefixLength) {
        this.address = address;
        this.networkPrefixLength = networkPrefixLength;
    }

    public InetAddress getAddress() {
        return address;
    }

    public int getNetworkPrefixLength() {
        return networkPrefixLength;
    }

    /**
     * Compares this object against the specified object.
     * The result is {@code true} if and only if the argument is
     * not {@code null} and it represents the same interface address as
     * this object.
     * <p>
     * Two instances of {@code InterfaceAddress} represent the same
     * address if the InetAddress, the prefix length and the broadcast are
     * the same for both.
     *
     * @param obj the object to compare against.
     * @return {@code true} if the objects are the same;
     * {@code false} otherwise.
     * @see InterfaceAddressEx#hashCode()
     */
    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof InterfaceAddressEx)) {
            return false;
        }
        InterfaceAddressEx that = (InterfaceAddressEx) obj;
        if (!(address == null ? that.address == null : address.equals(that.address))) {
            return false;
        }

        if (networkPrefixLength != that.networkPrefixLength) {
            return false;
        }
        return true;
    }

    /**
     * Returns a hashcode for this Interface address.
     *
     * @return a hash code value for this Interface address.
     */
    @Override
    public int hashCode() {
        return address.hashCode() + networkPrefixLength;
    }

    /**
     * Converts this Interface address to a {@code String}. The
     * string returned is of the form: InetAddress / prefix length [ broadcast address ].
     *
     * @return a string representation of this Interface address.
     */
    @Override
    public String toString() {
        return address + "/" + networkPrefixLength;
    }

    public static InterfaceAddressEx of(final InetAddress address, final int networkPrefixLength) {
        return new InterfaceAddressEx(address, networkPrefixLength);
    }

    public static InterfaceAddressEx of(final String address, final int networkPrefixLength) throws UnknownHostException {
        return new InterfaceAddressEx(InetAddress.getByName(address), networkPrefixLength);
    }

}