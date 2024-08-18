package com.github.pangolin.routing.beta.fakedns;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.dns.*;
import io.netty.resolver.dns.DnsNameResolver;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.resolver.dns.SequentialDnsServerAddressStreamProvider;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

public class DatagramDnsProxyServerHandler extends SimpleChannelInboundHandler<DatagramDnsQuery> {
    private final DnsNameResolver resolver;

    public DatagramDnsProxyServerHandler(final DnsNameResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
        final ChannelPipeline cp = ctx.pipeline();
        if (null == cp.get(DatagramDnsQueryDecoder.class)) {
            cp.addBefore(ctx.name(), null, new DatagramDnsQueryDecoder());
        }
        if (null == cp.get(DatagramDnsResponseEncoder.class)) {
            cp.addBefore(ctx.name(), null, new DatagramDnsResponseEncoder());
        }
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final DatagramDnsQuery query) throws Exception {
        final DnsQuestion question = query.recordAt(DnsSection.QUESTION);
        resolver.query(question).addListener(f -> {
            if (f.isSuccess()) {
                final AddressedEnvelope<DnsResponse, InetSocketAddress> envelope = (AddressedEnvelope<DnsResponse, InetSocketAddress>) f.getNow();
                try {
                    ctx.writeAndFlush(getResponse(envelope.content(), query));
                }finally {
//                    ReferenceCountUtil.release(envelope);
                }

            }
        });
    }

    private DnsResponse getResponse(DnsResponse serverResponse, DatagramDnsQuery query) {
        DnsResponse response = new DatagramDnsResponse(query.recipient(), query.sender(), query.id());
        copySections(serverResponse, response);
        return response;
    }

    private void copySections(DnsResponse r1, DnsResponse r2) {
        for(DnsSection section : DnsSection.values()) {
            copySection(r1, r2, section);
        }
    }

    private void copySection(DnsResponse r1, DnsResponse r2, DnsSection section) {
        for (int i = 0; i < r1.count(section); i++) {
            DnsRecord record = r1.recordAt(section, i);
            r2.addRecord(section, record);
        }
    }

    public static void main(String[] args) throws UnknownHostException, InterruptedException {
        final List<InetSocketAddress> addresses = Arrays.asList(
                new InetSocketAddress("192.168.1.1", 53)
        );

        final EventLoopGroup loop = new NioEventLoopGroup();
        final DnsNameResolver resolver =
        new DnsNameResolverBuilder()
                .nameServerProvider(new SequentialDnsServerAddressStreamProvider(addresses))
                .channelFactory(NioDatagramChannel::new)
                .eventLoop(loop.next())
//                .ttl()
                .recursionDesired(true)
                .build();

        EventLoopGroup proxyGroup = new NioEventLoopGroup();
        Bootstrap b = new Bootstrap();
        b.group(proxyGroup).channel(NioDatagramChannel.class)
                .handler(new ChannelInitializer<DatagramChannel>() {
                    @Override
                    protected void initChannel(DatagramChannel ch) {
                        ch.pipeline().addLast(new DatagramDnsProxyServerHandler(resolver));
                    }
                }).option(ChannelOption.SO_BROADCAST, true).bind(53).addListener(new GenericFutureListener<Future<? super Void>>() {
            @Override
            public void operationComplete(final Future<? super Void> future) throws Exception {
                System.out.println("DNS: " + future.isSuccess());
            }
        });
    }
}