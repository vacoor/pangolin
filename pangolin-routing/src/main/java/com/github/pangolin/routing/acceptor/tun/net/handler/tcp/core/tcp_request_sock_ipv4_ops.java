package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpBuffer;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpSock;
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.tcp_request_sock;
import org.pcap4j.packet.IpPacket;
import org.pcap4j.packet.TcpPacket;

import static com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.TcpConstants.TCP_MSS_DEFAULT;

// https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_ipv4.c#L1714
public interface tcp_request_sock_ipv4_ops {
    public int mss_clamp = TCP_MSS_DEFAULT;

    int init_seq(IpPacket.IpHeader ipHdr, TcpPacket.TcpHeader header);

    long init_ts_off(TcpPacket skb);

    void send_synack(TcpSock p, tcp_request_sock req, IpPacket.IpHeader ipHdr, TcpPacket skb);

    void addToHalfQueue(TcpSock p, tcp_request_sock req);


    void INDIRECT_CALL_INET(TcpBuffer buffer);


}
