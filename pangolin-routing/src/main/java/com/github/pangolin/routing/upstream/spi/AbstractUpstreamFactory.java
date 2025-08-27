package com.github.pangolin.routing.upstream.spi;

import com.github.pangolin.routing.upstream.Upstream;
import com.github.pangolin.routing.upstream.UpstreamFactory;
import com.github.pangolin.routing.util.SocketUtils;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

public abstract class AbstractUpstreamFactory implements UpstreamFactory {
    protected final String[] urlPrefixes;

    protected AbstractUpstreamFactory(final String[] urlPrefixes) {
        this.urlPrefixes = urlPrefixes;
    }

    @Override
    public boolean accept(final String serverUrl) {
        return null != serverUrl && Arrays.stream(urlPrefixes).anyMatch(serverUrl::startsWith);
    }

    @Override
    public Upstream apply(final String name, final String serverUrl) {
        return accept(serverUrl) ? apply0(name, serverUrl) : null;
    }

    protected abstract Upstream apply0(final String name, final String serverUrl);

    protected InetSocketAddress toSocketAddress(final String host, final int port) {
        try {
            final InetAddress addr = InetAddress.getByName(host);
            return new InetSocketAddress(addr, port);
        } catch (final UnknownHostException e) {
            return InetSocketAddress.createUnresolved(host, port);
        }
    }

    protected String[] splitUserInfo(final String userInfo) {
        return null != userInfo ? userInfo.split(":", 2) : new String[0];
    }
}
