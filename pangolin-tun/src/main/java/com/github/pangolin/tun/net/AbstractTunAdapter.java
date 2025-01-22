package com.github.pangolin.tun.net;

import java.util.List;

public abstract class AbstractTunAdapter<T extends NetworkInterfaceEx> {
    protected final T nix;

    protected AbstractTunAdapter(final T nix) {
        this.nix = nix;
    }

    public List<InterfaceAddressEx> getInterfaceAddresses() {
        return nix.getInterfaceAddresses();
    }

    public void setInterfaceAddress(final InterfaceAddressEx address) {
        nix.setInterfaceAddress(address);
    }

    public void addInterfaceAddress(final InterfaceAddressEx address) {
        nix.addInterfaceAddress(address);
    }

    public void deleteInterfaceAddress(final InterfaceAddressEx address) {
        nix.deleteInterfaceAddress(address);
    }

    public void flushInterfaceAddresses() {
        nix.flushInterfaceAddresses();
    }
}
