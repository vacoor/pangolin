package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpSock;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.tcp_request_sock;
import io.netty.channel.Channel;
import org.pcap4j.packet.IpPacket;
import org.pcap4j.packet.TcpPacket;

/**
 * https://github.com/torvalds/linux/blob/master/include/net/inet_connection_sock.h#L35
 */
public interface inet_connection_sock_af_ops<T extends IpPacket> {

    void send_check(final Channel net, TcpSock sk, final T ipPacket, final TcpPacket tcpPacket);

    tcp_request_sock conn_request(final Channel net, TcpSock listenSock, final T ipPacket);

    TcpSock syn_recv_sock(final Channel net, TcpSock listenSock, final T ipPacket, tcp_request_sock req);

}
