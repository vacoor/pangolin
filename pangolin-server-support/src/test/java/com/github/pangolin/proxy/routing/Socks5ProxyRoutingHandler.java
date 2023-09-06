package com.github.pangolin.proxy.routing;

import com.github.pangolin.handler.TcpInboundRedirectHandler;
import com.github.pangolin.proxy.routing.factory.Socks5Proxy;
import com.github.pangolin.proxy.routing.factory.WebSocketProxy;
import com.github.pangolin.proxy.server.socks.v5.Socks5ProxyServerHandler;
import com.github.pangolin.server.NettyServer;
import com.github.pangolin.util.Channels;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequest;
import io.netty.resolver.NoopAddressResolverGroup;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;

/**
 */
@Slf4j
public class Socks5ProxyRoutingHandler extends Socks5ProxyServerHandler {

    @Override
    protected ChannelFuture connect(final ChannelHandlerContext ctx, final Socks5CommandRequest request) throws Exception {
        final InetSocketAddress sourceAddress = (InetSocketAddress) ctx.channel().remoteAddress();
        final InetSocketAddress destinationAddress = new InetSocketAddress(request.dstAddr(), request.dstPort());
        final ChannelHandler routingHandler = newRoutingHandler(sourceAddress, destinationAddress);
        final Socks5AddressType addressType = request.dstAddrType();

        ctx.channel().config().setAutoRead(false);
        return Channels.open(destinationAddress, NoopAddressResolverGroup.INSTANCE, false, ctx.channel().eventLoop(), new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
                ch.pipeline().addFirst(routingHandler);
                ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRegistered(final ChannelHandlerContext delegateCtx) throws Exception {
                        delegateCtx.pipeline().replace(this, null, new TcpInboundRedirectHandler(ctx));
                        ctx.pipeline().replace(ctx.handler(), null, new TcpInboundRedirectHandler(delegateCtx));

                        delegateCtx.channel().config().setAutoRead(true);
                        ctx.channel().config().setAutoRead(true);
                    }
                });
            }
        });
    }

    private ChannelHandler newRoutingHandler(final InetSocketAddress sourceAddress, final InetSocketAddress destinationAddress) {
        final Socks5Proxy socks5Proxy = new Socks5Proxy("127.0.0.1", 1080);
        final WebSocketProxy wsProxy = new WebSocketProxy("ws://192.168.1.201:2345/tunnel?agent=BZ", null);
        final InetSubnetCondition network = new InetSubnetCondition("10.188.71.0", 23);
        if (destinationAddress.isUnresolved()) {
            final String hostString = destinationAddress.getHostString();
            if (hostString.endsWith("foo.com") || hostString.endsWith("bar.cn")) {
                return wsProxy.newProxyHandler();
            }
        } else if (network.matches(destinationAddress)) {
            System.out.println("$proxyAddress => " + destinationAddress);
            return wsProxy.newProxyHandler();
        }
        return new ChannelInboundHandlerAdapter();
    }

    public static void main(String[] args) throws Exception {

        new NettyServer(1080).start(true, new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel channel) throws Exception {
                channel.pipeline().addLast(new Socks5ProxyRoutingHandler());
            }
        }).sync().channel().closeFuture().sync();
    }
}
