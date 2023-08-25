package com.github.pangolin.proxy.server.socks4;

import com.github.pangolin.proxy.NettyServer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLException;
import java.security.cert.CertificateException;

@Slf4j
public class Socks4ProxyServer extends NettyServer {

    public Socks4ProxyServer(final int listenPort) {
        this(null, listenPort);
    }

    public Socks4ProxyServer(final String listenHost, final int listenPort) {
        super(listenHost, listenPort);
    }

    /**
     * 启动服务.
     *
     * @return 服务通道
     */
    public Channel start() throws InterruptedException, CertificateException, SSLException {
        return super.start(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
                ch.pipeline().addLast(new Socks4ProxyServerHandler(workerGroup));
            }
        });
    }

    public static void main(String[] args) throws Exception {
        new Socks4ProxyServer(1008).start().closeFuture().sync().await();
    }

}
