package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.harness;

import com.github.pangolin.routing.acceptor.tun.fakedns.DnsEngine;
import io.netty.handler.codec.dns.DatagramDnsQuery;
import io.netty.handler.codec.dns.DatagramDnsResponse;

/**
 * 测试用假 DNS:所有地址均视为"真实地址",不反查主机名。
 *
 * <p>v2 TCP 主干不参与 DNS,本实现仅为满足 {@code TcpMultiplexHandler} 构造器非空契约。
 */
public final class FakeDnsEngine implements DnsEngine {

    public static final FakeDnsEngine INSTANCE = new FakeDnsEngine();

    private FakeDnsEngine() {}

    @Override
    public boolean isFakeAddress(byte[] address) {
        return false;
    }

    @Override
    public String getHostByAddress(byte[] address) {
        return null;
    }

    @Override
    public DatagramDnsResponse lookup(DatagramDnsQuery query) {
        return null;
    }
}
