package com.github.pangolin.routing.server.tun.adapter;

import java.io.IOException;
import java.nio.ByteBuffer;

public interface TunAdapter {

    String name();

    ByteBuffer read() throws IOException;

    void write(final ByteBuffer packet) throws IOException;

    void destroy() throws IOException;

}
