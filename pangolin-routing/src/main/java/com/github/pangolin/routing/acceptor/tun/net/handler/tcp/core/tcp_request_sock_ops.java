package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpSock;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.request_sock;
import org.pcap4j.packet.IpPacket;

// https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L1705
public interface tcp_request_sock_ops {

    void send_ack(final TcpSock sk, final IpPacket ipPacket, final request_sock req);

    void send_reset(final TcpSock sk, final IpPacket ipPacket, final int reason);

}
