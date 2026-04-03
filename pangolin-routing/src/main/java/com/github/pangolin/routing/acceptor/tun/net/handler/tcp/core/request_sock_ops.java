package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.sock.TcpSock;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.sock.request_sock;
import io.netty.channel.Channel;

// https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L1705
public interface request_sock_ops {

    void send_ack(final Channel net, final TcpSock sk, final TcpPacketBuf pkt, final request_sock req);

    void send_reset(final Channel net, final TcpSock sk, final TcpPacketBuf pkt, final int reason);

}
