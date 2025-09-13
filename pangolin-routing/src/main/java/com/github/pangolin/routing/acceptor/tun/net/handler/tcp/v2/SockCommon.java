package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.v2;

import org.pcap4j.packet.namednumber.TcpPort;

import java.net.InetAddress;

// https://github.com/torvalds/linux/blob/master/include/net/sock.h#L150
public class SockCommon {
    public InetAddress srcAddr;
    public InetAddress dstAddr;

    public TcpPort srcPort;
    public TcpPort dstPort;

}
