package com.github.pangolin.routing.tun.wintun.win32;

import com.sun.jna.platform.win32.IPHlpAPI;

import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.LinkedList;
import java.util.List;

public class WindowsNetworkInterfaceEx {
    private final long interfaceLuid;
    private final NetworkInterface ni;

    public WindowsNetworkInterfaceEx(final long interfaceLuid, final NetworkInterface ni) {
        this.interfaceLuid = interfaceLuid;
        this.ni = ni;
    }

    public int getIndex() {
        return ni.getIndex();
    }

    public long getLuid() {
        return interfaceLuid;
    }

    public int getMTU() throws SocketException {
        return ni.getMTU();
    }

    public List<InterfaceAddressEx> getInterfaceAddresses() {
        final List<InterfaceAddress> addrs = ni.getInterfaceAddresses();
        final List<InterfaceAddressEx> addr2s = new LinkedList<>();
        for (final InterfaceAddress addr : addrs) {
            addr2s.add(InterfaceAddressEx.of(addr.getAddress(), addr.getNetworkPrefixLength()));
        }
        return addr2s;
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

    public static WindowsNetworkInterfaceEx getByName(final String name) throws SocketException {
        return of(NetworkInterface.getByName(name));
    }

    public static WindowsNetworkInterfaceEx getByInetAddress(final InetAddress addr) throws SocketException {
        return of(NetworkInterface.getByInetAddress(addr));
    }

    public static WindowsNetworkInterfaceEx getByAlias(final String interfaceAlias) throws SocketException {
        final long interfaceLuid = NetworkInterfaceEx.interfaceAliasToLuid(interfaceAlias);
        return getByLuid(interfaceLuid);
    }

    public static WindowsNetworkInterfaceEx getByLuid(final long interfaceLuid) throws SocketException {
        final int index = NetworkInterfaceEx.interfaceLuidToIndex(interfaceLuid);
        return new WindowsNetworkInterfaceEx(interfaceLuid, NetworkInterface.getByIndex(index));
    }

    public static WindowsNetworkInterfaceEx of(final NetworkInterface ni) {
        final long interfaceLuid = NetworkInterfaceEx.interfaceIndexToLuid(ni.getIndex());
        return new WindowsNetworkInterfaceEx(interfaceLuid, ni);
    }

}