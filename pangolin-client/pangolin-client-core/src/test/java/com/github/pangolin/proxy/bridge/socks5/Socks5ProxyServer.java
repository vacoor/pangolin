package com.github.pangolin.proxy.bridge.socks5;

import com.github.pangolin.proxy.AbstractNettyServer;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLException;
import java.security.cert.CertificateException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket 隧道服务.
 *
 * @author changhe.yang
 * @since 20210825
 */
@Slf4j
public class Socks5ProxyServer extends AbstractNettyServer {

    /**
     * 创建隧道服务实例.
     *
     * @param listenPort 监听端口
     */
    public Socks5ProxyServer(final int listenPort) {
        this(null, listenPort);
    }

    /**
     * 创建隧道服务实例.
     *
     * @param listenHost 监听地址
     * @param listenPort 监听端口
     */
    public Socks5ProxyServer(final String listenHost, final int listenPort) {
        super(listenHost, listenPort);
    }

    /**
     * 启动服务.
     *
     * @return 服务通道
     */
    public Channel start() throws InterruptedException, CertificateException, SSLException {
        return super.start(new Socks5ProxyServerHandler(bossGroup));
    }

    public static void main(String[] args) throws InterruptedException, SSLException, CertificateException, ExecutionException {
        final Socks5ProxyServer server = new Socks5ProxyServer(1008);
        final Channel channel = server.start();
        channel.eventLoop().scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
//                server.expiredCheck();
            }
        }, 60, 60, TimeUnit.SECONDS);

        channel.closeFuture().sync().get();
    }
}
