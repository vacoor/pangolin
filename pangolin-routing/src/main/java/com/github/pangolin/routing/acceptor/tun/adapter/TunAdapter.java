package com.github.pangolin.routing.acceptor.tun.adapter;

import java.io.IOException;
import java.nio.ByteBuffer;

public abstract class TunAdapter {
    protected volatile boolean closed;

    public abstract String name();

    public ByteBuffer read() throws IOException {
        checkOpen();
        return read0();
    }

    public void write(final ByteBuffer packet) throws IOException {
        checkOpen();
        write0(packet);
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("Device is closed.");
        }
    }

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
