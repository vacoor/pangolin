package com.github.pangolin.routing.tun.wintun.win32;

import com.sun.jna.platform.win32.IPHlpAPI;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.List;

public class WindowsNetworkInterfaceEx {
    private final long interfaceLuid;
    private transient volatile int index;

    public WindowsNetworkInterfaceEx(final long interfaceLuid) {
        this.interfaceLuid = interfaceLuid;
    }

    public int getIndex() {
        if (index <= 0) {
            index = NetworkInterfaceEx.interfaceLuidToIndex(interfaceLuid);
        }
        return index;
    }

    public long getLuid() {
        return interfaceLuid;
    }

    public int getMTU() throws SocketException {
        return networkInterface().getMTU();
    }

    public List<InterfaceAddressEx> getInterfaceAddresses() {
        /* 地址信息可能不对.
        final List<InterfaceAddress> addrs = networkInterface().getInterfaceAddresses();
        final List<InterfaceAddressEx> addr2s = new LinkedList<>();
        for (final InterfaceAddress addr : addrs) {
            addr2s.add(InterfaceAddressEx.of(addr.getAddress(), addr.getNetworkPrefixLength()));
        }
        return addr2s;
        */
        return NetworkInterfaceEx.getInterfaceAddresses(interfaceLuid);
    }

    private NetworkInterface networkInterface() {
        try {
            /*-
             * java.net.NetworkInterface is SNAPSHOT and name/displayName, getInterfaceAddresses().networkPrefixLength is wrong.
             */
            return NetworkInterface.getByIndex(getIndex());
        } catch (final SocketException e) {
            throw new IllegalStateException(e);
        }
    }

    public void addInterfaceAddress(final InterfaceAddressEx address) {
        NetworkInterfaceEx.addInterfaceAddress(interfaceLuid, address.getAddress(), (byte) address.getNetworkPrefixLength());
    }

    public void setInterfaceAddress(final InterfaceAddressEx address) {
        NetworkInterfaceEx.setInterfaceAddress(interfaceLuid, address.getAddress(), (byte) address.getNetworkPrefixLength());
    }

    public void flushInterfaceAddresses() {
        NetworkInterfaceEx.flushInterfaceAddresses(interfaceLuid, IPHlpAPI.AF_UNSPEC);
    }

    public static WindowsNetworkInterfaceEx getByIndex(final int index) throws SocketException {
        return of(NetworkInterface.getByIndex(index));
    }

    public static WindowsNetworkInterfaceEx getByInetAddress(final InetAddress addr) throws SocketException {
        return of(NetworkInterface.getByInetAddress(addr));
    }

    /* TODO NetworkInterface name/displayName 和 实际name/alias不一样.
    public static WindowsNetworkInterfaceEx getByName(final String name) throws SocketException {
        return of(NetworkInterface.getByName(name));
    }
    */

    public static WindowsNetworkInterfaceEx getByAlias(final String interfaceAlias) throws SocketException {
        return getByLuid(NetworkInterfaceEx.interfaceAliasToLuid(interfaceAlias));
    }

    public static WindowsNetworkInterfaceEx getByLuid(final long interfaceLuid) throws SocketException {
        return new WindowsNetworkInterfaceEx(interfaceLuid);
    }

    public static WindowsNetworkInterfaceEx of(final NetworkInterface ni) {
        final long interfaceLuid = NetworkInterfaceEx.interfaceIndexToLuid(ni.getIndex());
        return new WindowsNetworkInterfaceEx(interfaceLuid);
    }

}