package com.github.pangolin.routing.server.tun.net.handler.tcp;

import java.util.List;

import static com.github.pangolin.routing.server.tun.net.handler.tcp.TcpConnection.State;
import static org.pcap4j.packet.TcpPacket.TcpOption;
import org.pcap4j.packet.Packet;
import static org.pcap4j.packet.Packet.Builder;

class TcpOutput {
    private final TcpConnection<?> conn;

    TcpOutput(final TcpConnection<?> conn) {
        this.conn = conn;
    }

}