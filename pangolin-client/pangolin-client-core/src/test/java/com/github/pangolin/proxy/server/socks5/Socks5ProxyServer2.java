package com.github.pangolin.proxy.server.socks5;

import com.github.pangolin.proxy.NettyServer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLException;
import java.security.cert.CertificateException;
import java.util.concurrent.ExecutionException;

/**
 * WebSocket 隧道服务.
 *
 * @author changhe.yang
 * @since 20210825
 */
@Slf4j
public class Socks5ProxyServer2 extends NettyServer {

    /**
     * 创建隧道服务实例.
     *
     * @param listenPort 监听端口
     */
    public Socks5ProxyServer2(final int listenPort) {
        this(null, listenPort);
    }

    /**
     * 创建隧道服务实例.
     *
     * @param listenHost 监听地址
     * @param listenPort 监听端口
     */
    public Socks5ProxyServer2(final String listenHost, final int listenPort) {
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
                ch.pipeline().addLast(new Socks5ProxyServerHandler2(workersGroup));
            }
        });
    }

    public static void main(String[] args) throws InterruptedException, SSLException, CertificateException, ExecutionException {
        final int listenPort = 1007;
        final Socks5ProxyServer2 server = new Socks5ProxyServer2(listenPort);
        final Channel channel = server.start();
        channel.closeFuture().sync().get();
    }
}
