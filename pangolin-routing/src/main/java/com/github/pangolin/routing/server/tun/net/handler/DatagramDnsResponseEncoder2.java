package com.github.pangolin.routing.server.tun.net.handler;

import com.google.common.collect.Lists;
import io.netty.channel.AddressedEnvelope;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.dns.DatagramDnsResponseEncoder;
import io.netty.handler.codec.dns.DnsResponse;
import io.netty.util.ReferenceCountUtil;

import java.net.InetSocketAddress;
import java.util.List;

public class DatagramDnsResponseEncoder2 extends DatagramDnsResponseEncoder {
    @Override
    protected void encode(final ChannelHandlerContext ctx, final AddressedEnvelope<DnsResponse, InetSocketAddress> in, final List<Object> out) throws Exception {
        final List<Object> temp = Lists.newArrayList();
        super.encode(ctx, in, temp);
        for (int i = 0; i < temp.size(); i++) {
            Object o = out.get(i);
            if (o instanceof DatagramPacket) {
                DatagramPacket o1 = (DatagramPacket) o;
                out.add(new DatagramPacket(o1.content(), o1.recipient(), in.sender()));
                ReferenceCountUtil.release(o);
            } else {
                out.add(o);
            }
        }
    }
}