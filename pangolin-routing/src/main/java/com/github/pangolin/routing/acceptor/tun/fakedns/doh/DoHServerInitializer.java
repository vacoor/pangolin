package com.github.pangolin.routing.acceptor.tun.fakedns.doh;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.cors.CorsConfig;
import io.netty.handler.codec.http.cors.CorsConfigBuilder;
import io.netty.handler.codec.http.cors.CorsHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;

public class DoHServerInitializer extends ChannelInitializer<SocketChannel> {
    
    @Override
    protected void initChannel(SocketChannel ch) throws Exception {
        SelfSignedCertificate ssc = new SelfSignedCertificate();
        SslContext sslCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();

        CorsConfig corsConfig = CorsConfigBuilder.forAnyOrigin()
                .allowedRequestMethods(HttpMethod.GET, HttpMethod.POST)
                .allowedRequestHeaders("Content-Type")
                .build();
                
        ch.pipeline()
                .addLast(sslCtx.newHandler(ch.alloc()))
                .addLast(new HttpServerCodec())
                .addLast(new HttpObjectAggregator(65536))
//                .addLast(new CorsHandler(corsConfig))
                .addLast(new DoHServerHandler());
    }
}
