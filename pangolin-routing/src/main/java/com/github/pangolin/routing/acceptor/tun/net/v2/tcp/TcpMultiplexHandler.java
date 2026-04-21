package com.github.pangolin.routing.acceptor.tun.net.v2.tcp;

import com.github.pangolin.routing.acceptor.tun.fakedns.DnsEngine;
import com.github.pangolin.routing.acceptor.tun.net.handler.support.IpPacketHandler;
import com.github.pangolin.routing.acceptor.tun.net.handler.support.TcpPacketBuf;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.FourTuple;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpConfig;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.Tcp4Multiplexer;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpMultiplexer;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpMultiplexer.DataConsumer;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.netty.TcpChannel;
import com.github.pangolin.routing.acceptor.tun.net.v2.tcp.netty.TcpChannelFactory;
import com.github.pangolin.routing.support.SocketChannelFactory;
import com.github.pangolin.routing.support.StandardSocketChannelFactory;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

import static com.google.common.net.HttpHeaders.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * v2 TCP ingress now follows v1 architecture:
 * TCP logic is handled by {@link TcpMultiplexer}, not Netty pipeline handlers.
 */
public class TcpMultiplexHandler extends IpPacketHandler<TcpPacketBuf> {

    private static final Logger log = LoggerFactory.getLogger(TcpMultiplexHandler.class);
    private static final byte PROTO_TCP = 6;

    private final DnsEngine dnsEngine;
    private final DataConsumer dataConsumer;
    private final SocketChannelFactory socketChannelFactory;
    private final EventLoopGroup childGroup;
    private final TcpMultiplexer multiplexer;

    public TcpMultiplexHandler(final DnsEngine dnsEngine) {
        this(dnsEngine, new StandardSocketChannelFactory(null), null);
    }

    public TcpMultiplexHandler(final DnsEngine dnsEngine, final DataConsumer dataConsumer) {
        this(dnsEngine, new StandardSocketChannelFactory(null), dataConsumer);
    }

    public TcpMultiplexHandler(final DnsEngine dnsEngine, final SocketChannelFactory socketChannelFactory) {
        this(dnsEngine, socketChannelFactory, null);
    }

    public TcpMultiplexHandler(final DnsEngine dnsEngine,
                               final SocketChannelFactory socketChannelFactory,
                               final DataConsumer dataConsumer) {
        super(PROTO_TCP);
        this.dnsEngine = dnsEngine;
        this.dataConsumer = dataConsumer;
        this.socketChannelFactory = socketChannelFactory;
        this.childGroup = new NioEventLoopGroup();
        this.multiplexer = create();
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final TcpPacketBuf rawPkt) {
        try {
            final TcpPacketBuf pkt = prepare(rawPkt);
            multiplexer.consume(ctx, pkt);
        } catch (final Exception ex) {
            log.error("Failed to process TCP packet", ex);
            // fallback reset in ingress layer
            com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpOutput.INSTANCE.tcp_v4_send_reset(ctx, rawPkt);
        }
    }

    protected TcpPacketBuf prepare(final TcpPacketBuf pkt) throws UnknownHostException {
        pkt.resolvedDstAddr(resolveDstAddress(pkt.dstAddrBytes()));
        return pkt;
    }

    protected TcpMultiplexer create() {
        TcpChannelFactory factory = (sock, mux) -> {
            TcpChannel ch = new TcpChannel(sock, mux);
            ch.pipeline().addLast(
                    new HttpServerCodec(),
                    new HttpObjectAggregator(65536),
                    new SimpleChannelInboundHandler<FullHttpRequest>() {
                        @Override protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
                            ByteBuf body = Unpooled.copiedBuffer("Hello from v2 TCP over TUN!\n", StandardCharsets.UTF_8);
                            FullHttpResponse resp = new DefaultFullHttpResponse(HTTP_1_1, OK, body);
                            resp.headers().setInt(CONTENT_LENGTH, body.readableBytes());
                            ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
                        }
                    });
            return ch;
        };
        // return new Tcp4Multiplexer(TcpConfig.builder().build(), socketChannelFactory, childGroup, dataConsumer);
        return new Tcp4Multiplexer(TcpConfig.builder().build(), factory);
    }

    public boolean write(final FourTuple key, final ByteBuf data) {
        return multiplexer.write(key, data);
    }

    public TcpMultiplexer multiplexer() {
        return multiplexer;
    }

    @Override
    public void handlerRemoved(final ChannelHandlerContext ctx) throws Exception {
        try {
            childGroup.shutdownGracefully();
        } finally {
            super.handlerRemoved(ctx);
        }
    }

    protected java.net.InetAddress resolveDstAddress(final byte[] addr) throws UnknownHostException {
        if (dnsEngine.isFakeAddress(addr)) {
            final String hostname = dnsEngine.getHostByAddress(addr);
            if (hostname != null && !hostname.isEmpty()) {
                return java.net.InetAddress.getByAddress(hostname, addr);
            }
        }
        return java.net.InetAddress.getByAddress(io.netty.util.NetUtil.bytesToIpAddress(addr), addr);
    }
}
