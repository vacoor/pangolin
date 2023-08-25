package com.github.pangolin.proxy.server;

import com.github.pangolin.util.Channels;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.ssl.SslContext;
import io.netty.util.concurrent.DefaultThreadFactory;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLException;
import java.security.cert.CertificateException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 *
 */
@Slf4j
public class NettyServer {
    /**
     * 服务 event loop group.
     */
    protected final NioEventLoopGroup bossGroup;

    /**
     * 处理 event loop group.
     */
    protected final NioEventLoopGroup workerGroup;

    /**
     *
     */
    protected final AtomicBoolean startup = new AtomicBoolean(false);

    /**
     * 监听端口.
     */
    protected final int listenPort;

    /**
     * 监听主机名.
     */
    protected final String listenHost;

    /**
     *
     */
    protected ChannelFuture serverChannelFuture;

    /**
     * 创建隧道服务实例.
     *
     * @param listenPort 监听端口
     */
    public NettyServer(final int listenPort) {
        this(null, listenPort);
    }

    /**
     * 创建隧道服务实例.
     *
     * @param listenHost 监听地址
     * @param listenPort 监听端口
     */
    public NettyServer(final String listenHost, final int listenPort) {
        this(
                listenHost, listenPort,
                new NioEventLoopGroup(2, new DefaultThreadFactory("NettyServer-boss", true)),
                new NioEventLoopGroup(0, new DefaultThreadFactory("NettyServer-workers", true))
        );
    }

    public NettyServer(final String listenHost, final int listenPort,
                       final NioEventLoopGroup bossGroup, final NioEventLoopGroup workerGroup) {
        this.listenHost = listenHost;
        this.listenPort = listenPort;
        this.bossGroup = bossGroup;
        this.workerGroup = workerGroup;
    }

    /**
     * 启动服务.
     *
     * @return 服务通道
     */
    public ChannelFuture start(final ChannelInboundHandler initializer) throws InterruptedException, CertificateException, SSLException {
        if (!startup.compareAndSet(false, true)) {
            return serverChannelFuture;
        }

        final ChannelFuture cf = Channels.listen(listenHost, listenPort, bossGroup, workerGroup, initializer);
        Channels.shutdownGroupOnClose(cf.channel(), bossGroup);
        Channels.shutdownGroupOnClose(cf.channel(), workerGroup);

        return serverChannelFuture = cf;
    }

    /**
     * Create an ssl context.
     *
     * @return ssl context
     */
    protected SslContext createServerSslContext() throws SSLException, CertificateException {
        return Channels.createServerSslContext();
    }

    protected SslContext createClientSslContext() throws SSLException {
        return Channels.createClientSslContext();
    }


    /**
     * 关闭服务器.
     */
    public void shutdownGracefully() {
        if (null != serverChannelFuture) {
            final Channel channel = serverChannelFuture.channel();
            if (channel.isActive()) {
                channel.close();
            }
        }
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
    }

}
