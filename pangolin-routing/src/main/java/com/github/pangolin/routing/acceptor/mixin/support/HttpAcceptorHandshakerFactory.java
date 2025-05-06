package com.github.pangolin.routing.acceptor.mixin.support;

import com.github.pangolin.routing.support.handler.server.HttpProxyServerHandler;
import com.github.pangolin.routing.support.DatagramChannelFactory;
import com.github.pangolin.routing.support.SocketChannelFactory;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

public class HttpAcceptorHandshakerFactory implements MixinAcceptorHandshakerFactory {

    @Override
    public String name() {
        return "HTTP";
    }

    @Override
    public MixinServerHandshaker createHandshaker(final SocketChannelFactory socketFactory, final DatagramChannelFactory datagramFactory) {
        return HttpMixinServerHandshaker.of(
                new HttpProxyServerHandler(null, null, socketFactory),
                new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
                        ctx.writeAndFlush(new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.NOT_FOUND))
                                .addListener(ChannelFutureListener.CLOSE);
                    }
                }
        );
    }

}