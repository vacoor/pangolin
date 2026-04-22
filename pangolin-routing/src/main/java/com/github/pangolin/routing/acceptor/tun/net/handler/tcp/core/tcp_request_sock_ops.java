package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.codec.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.sock.TcpSock;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.sock.tcp_request_sock;
import io.netty.channel.Channel;

import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpConstants.TCP_MSS_DEFAULT;

// https://github.com/torvalds/linux/blob/master/include/net/tcp.h#L2379
// https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L1714
public interface tcp_request_sock_ops {
    public int mss_clamp = TCP_MSS_DEFAULT;

    int init_seq(TcpPacketBuf pkt);

    long init_ts_off(TcpPacketBuf pkt);

    void send_synack(Channel net, TcpSock p, tcp_request_sock req, TcpPacketBuf syn);

    void addToHalfQueue(TcpSock p, tcp_request_sock req);
}
