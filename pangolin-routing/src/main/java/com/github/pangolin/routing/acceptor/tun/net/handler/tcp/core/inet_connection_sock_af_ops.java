package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpSock;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.tcp_request_sock;
import io.netty.channel.Channel;
import org.pcap4j.packet.IpPacket;
import org.pcap4j.packet.TcpPacket;

/**
 * https://github.com/torvalds/linux/blob/master/include/net/inet_connection_sock.h#L35
 */
public interface inet_connection_sock_af_ops {

    tcp_request_sock conn_request(final Channel net, TcpSock listenSock, final IpPacket ipPacket);

}
