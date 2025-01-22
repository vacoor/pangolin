package com.github.pangolin.tun.net;

import java.io.IOException;

public interface TunAdapter {

    byte[] readPacket() throws IOException;

    void writePacket(byte[] bytes) throws IOException;

    void close() throws IOException;

}
