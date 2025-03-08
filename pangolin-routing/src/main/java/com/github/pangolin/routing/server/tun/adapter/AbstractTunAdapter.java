package com.github.pangolin.routing.server.tun.adapter;

import java.io.IOException;
import java.nio.ByteBuffer;

public abstract class AbstractTunAdapter<T extends NetworkInterfaceEx> implements TunAdapter {
    protected final T nix;
    protected volatile boolean closed;

    protected AbstractTunAdapter(final T nix) {
        this.nix = nix;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ByteBuffer read() throws IOException {
        checkOpen();
        return read0();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write(final ByteBuffer packet) throws IOException {
        checkOpen();
        write0(packet);
    }

    public void addInterfaceAddress(final InterfaceAddressEx address) {
        checkOpen();
        nix.addInterfaceAddress(address);
    }


    protected void checkOpen() {
        if (closed) {
            throw new IllegalStateException("Device is closed.");
        }
    }

    @Override
    public void destroy() throws IOException {
        if (!closed) {
            closed = true;
            destroy0();
        }
    }

    protected abstract ByteBuffer read0() throws IOException;

    protected abstract void write0(final ByteBuffer buffer) throws IOException;

    protected abstract void destroy0() throws IOException;

}
