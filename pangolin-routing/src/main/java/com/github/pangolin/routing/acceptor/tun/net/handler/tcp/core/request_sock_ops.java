package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpSock;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.request_sock;
import io.netty.channel.Channel;
import org.pcap4j.packet.IpPacket;
import org.pcap4j.packet.TcpPacket;

// https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L1705
public interface request_sock_ops {

    void send_ack(final Channel net, final TcpSock sk, final IpPacket ipPacket, final request_sock req);

    void send_reset(final Channel net, final TcpSock sk, final IpPacket ipPacket, final TcpPacket tcpPacket, final int reason);

}
