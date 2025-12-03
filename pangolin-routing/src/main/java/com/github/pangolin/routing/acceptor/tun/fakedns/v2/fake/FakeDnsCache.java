package com.github.pangolin.routing.acceptor.tun.fakedns.v2.fake;

import io.netty.resolver.dns.DnsCacheEntry;

import java.net.InetAddress;

public class FakeDnsCache {

    class FakeDnsCacheEntry implements DnsCacheEntry {

        @Override
        public InetAddress address() {
            return null;
        }

        @Override
        public Throwable cause() {
            return null;
        }

    }
}
