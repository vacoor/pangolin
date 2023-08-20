package com.github.pangolin.proxy.bridge.socks5;

import com.github.pangolin.proxy.AbstractNettyServer;
import com.github.pangolin.util.Channels;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.socksx.v5.Socks5ServerEncoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.concurrent.DefaultThreadFactory;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLException;
import java.security.cert.CertificateException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebSocket 隧道服务.
 *
 * @author changhe.yang
 * @since 20210825
 */
@Slf4j
public class Socks5Server extends AbstractNettyServer {

    /**
     * 创建隧道服务实例.
     *
     * @param listenPort 监听端口
     */
    public Socks5Server(final int listenPort) {
        this(null, listenPort);
    }

    /**
     * 创建隧道服务实例.
     *
     * @param listenHost 监听地址
     * @param listenPort 监听端口
     */
    public Socks5Server(final String listenHost, final int listenPort) {
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
                final ChannelPipeline pipeline = ch.pipeline();
                pipeline.addLast(Socks5ServerEncoder.DEFAULT);
                pipeline.addLast(new Socks5InitialHandler());

                //处理认证请求
                /*
                if(configProperties.isAuthentication()){
                    pipeline.addLast(new Socks5PasswordAuthRequestInboundHandler(configUtil));
                }
                */

                //处理connection请求
                pipeline.addLast(new Socks5CommandHandler(bossGroup));
            }
//            }
        });
    }

    public static void main(String[] args) throws
            InterruptedException, SSLException, CertificateException, ExecutionException {
        final Socks5Server server = new Socks5Server(1008);
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
