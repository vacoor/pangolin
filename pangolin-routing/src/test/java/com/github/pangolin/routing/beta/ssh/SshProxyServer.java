package com.github.pangolin.routing.beta.ssh;

import com.github.pangolin.routing.proxy.ProxyServer;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelOption;
import io.netty.channel.oio.OioEventLoopGroup;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

public class SshProxyServer implements ProxyServer {
    private final String name;
    private final String host;
    private final int port;
    private final String username;
    private final String password;

    public SshProxyServer(final String name, final String host, final int port, final String username, final String password) {
        this.name = name;
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public ChannelHandler newProxyHandler(final InetSocketAddress sa) {
        throw new UnsupportedOperationException();
    }

    public ChannelFuture open(final SocketAddress remoteAddress, final int connectTimeoutMillis, final boolean autoRead, final ChannelHandler initializer) {
        final Bootstrap b = new Bootstrap();
        b.option(ChannelOption.AUTO_READ, autoRead);
        b.option(ChannelOption.TCP_NODELAY, true);
        b.option(ChannelOption.SO_KEEPALIVE, true);
        b.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis);
        b.option(ChannelOption.SO_RCVBUF, 32 * 1024);// 读缓冲区为32k
        b.resolver(null).group(new OioEventLoopGroup())
                .channelFactory(new SshProxyChannelFactory(host, port, username, password))
                .handler(initializer);
        return b.connect(remoteAddress);
    }
}