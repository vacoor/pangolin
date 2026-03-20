package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpSock;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.tcp_request_sock;
import io.netty.channel.Channel;

/**
 * https://github.com/torvalds/linux/blob/master/include/net/inet_connection_sock.h#L35
 */
public interface inet_connection_sock_af_ops {

    void send_check(final Channel net, TcpSock sk, final TcpPacketBuf pkt);

    tcp_request_sock conn_request(final Channel net, TcpSock listenSock, final TcpPacketBuf pkt);

    TcpSock syn_recv_sock(final Channel net, TcpSock listenSock, final TcpPacketBuf pkt, tcp_request_sock req);

}
