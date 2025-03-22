package com.github.pangolin.routing.server.tun.net.handler.tcp;

public interface TcpConstants {
    byte FIN = 0x0001;
    byte SYN = 0x0002;
    byte RST = 0x0004;
    byte PSH = 0x0008;
    byte ACK = 0x0010;
    byte URG = 0x0020;


    int TCPF_ESTABLISHED = 1 << TcpConnection.State.TCP_ESTABLISHED.ordinal();
    int TCPF_CLOSE_WAIT = 1 << TcpConnection.State.TCP_CLOSE_WAIT.ordinal();
    int TCPF_CLOSE = 1 << TcpConnection.State.TCP_CLOSE.ordinal();
    int TCPF_LISTEN = 1 << TcpConnection.State.TCP_LISTEN.ordinal();
    int TCPF_SYN_SENT = 1 << TcpConnection.State.TCP_SYN_SENT.ordinal();
    int TCPF_SYN_RECV = 1 << TcpConnection.State.TCP_SYN_RECV.ordinal();
    int TCPF_LAST_ACK = 1 << TcpConnection.State.TCP_LAST_ACK.ordinal();
    int TCPF_CLOSING = 1 << TcpConnection.State.TCP_CLOSING.ordinal();

    int HZ = 1000;
}
