package com.github.pangolin.routing.acceptor.tun.adapter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class TunAdapter {
    private final AtomicBoolean closed = new AtomicBoolean();

    public abstract String name();

    public ByteBuffer read() throws IOException {
        checkOpen();
        return read0();
    }

    public void write(final ByteBuffer[] packet) throws IOException {
        checkOpen();
        if (0 < packet.length) {
            write0(packet);
        }
    }

    private void checkOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Device is closed.");
        }
    }

    public void destroy() throws IOException {
        if (closed.compareAndSet(false, true)) {
            destroy0();
        }
    }

    protected abstract ByteBuffer read0() throws IOException;

    protected abstract void write0(final ByteBuffer[] packet) throws IOException;

    protected abstract void destroy0() throws IOException;

}
