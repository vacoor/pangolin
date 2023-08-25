package com.github.pangolin.proxy.server;

import com.github.pangolin.proxy.NettyServer;
import com.github.pangolin.proxy.server.socks4.Socks4ProxyServerHandler;
import com.github.pangolin.proxy.server.socks5.Socks5ProxyServerHandler;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.socksx.SocksPortUnificationServerHandler;
import io.netty.handler.codec.socksx.v4.Socks4Message;
import io.netty.handler.codec.socksx.v5.Socks5Message;
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
public class SocksProxyServer extends NettyServer {

    /**
     * 创建隧道服务实例.
     *
     * @param listenPort 监听端口
     */
    public SocksProxyServer(final int listenPort) {
        this(null, listenPort);
    }

    /**
     * 创建隧道服务实例.
     *
     * @param listenHost 监听地址
     * @param listenPort 监听端口
     */
    public SocksProxyServer(final String listenHost, final int listenPort) {
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
                ch.pipeline().addLast(new SocksPortUnificationServerHandler());
                ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
                        if (msg instanceof Socks4Message) {
                            ctx.pipeline().addAfter(ctx.name(), null, new Socks4ProxyServerHandler(workerGroup));
                            ctx.pipeline().remove(this);
                        } else if (msg instanceof Socks5Message) {
                            ctx.pipeline().addAfter(ctx.name(), null, new Socks5ProxyServerHandler(workerGroup));
                            ctx.pipeline().remove(this);
                        }
                        super.channelRead(ctx, msg);
                    }
                });
            }
        });
    }

    public static void main(String[] args) throws InterruptedException, SSLException, CertificateException, ExecutionException {
        final int listenPort = 1008;
        final SocksProxyServer server = new SocksProxyServer(listenPort);
        final Channel channel = server.start();
        channel.closeFuture().sync().get();
    }
}
