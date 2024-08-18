package com.github.pangolin.routing.handler.internal.server.socks5.server;

import com.github.pangolin.routing.handler.internal.server.Socks5DatagramServerFactory;
import com.github.pangolin.routing.handler.internal.server.Socks5DatagramServerHandler;
import com.github.pangolin.routing.handler.internal.server.support.StandardDatagramChannelFactory;
import com.google.common.collect.Maps;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.AbstractChannel;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelConfig;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.internal.RecyclableArrayList;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

/**
 */
public class SharableSocks5DatagramServerFactory implements Socks5DatagramServerFactory {
    private ChannelFuture channel;
    private ConcurrentMap<InetSocketAddress, ChannelFuture> childChannels = Maps.newConcurrentMap();

    public SharableSocks5DatagramServerFactory() {
    }

    public void start(final InetSocketAddress localAddress) {
        channel = new Bootstrap().group(new NioEventLoopGroup())
                .channel(NioDatagramChannel.class)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(final Channel ch) throws Exception {
//                        ch.pipeline().addLast(new Socks5DatagramServerHandler());
                        ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
                                super.channelRead(ctx, msg);
                            }
                        });
                    }
                }).bind(localAddress);
    }

    @Override
    public final ChannelFuture createServer(final Channel parent, final InetSocketAddress owner) {
        return childChannels.computeIfAbsent(owner, own -> {
            SharedDatagramChannel c = new SharedDatagramChannel((DatagramChannel) channel.channel(), own);
            DefaultChannelPromise p = new DefaultChannelPromise(c, channel.channel().eventLoop());
            ChannelPipeline cp = channel.channel().pipeline();
            cp.addLast(c.id().toString(), new Socks5DatagramServerHandler(owner, new StandardDatagramChannelFactory()));
            channel.addListener(new GenericFutureListener<Future<? super Void>>() {
                @Override
                public void operationComplete(final Future<? super Void> future) throws Exception {
                    if (future.isSuccess()) {
                        p.trySuccess();
                    } else {
                        p.tryFailure(future.cause());
                    }
                }
            });
            return p;
        });
    }

    protected void doChildChannelClose(final SharedDatagramChannel c) {
        if (null != childChannels.remove(c.owner)) {
            channel.channel().pipeline().remove(c.id().toString());
        }
    }


    class SharedDatagramChannel extends AbstractChannel {
        protected final ChannelMetadata metadata = new ChannelMetadata(false);
        protected final DefaultChannelConfig config = new DefaultChannelConfig(this);

        protected final InetSocketAddress owner;

        protected SharedDatagramChannel(DatagramChannel parent, InetSocketAddress owner) {
            super(parent);
            this.owner = owner;
        }

        @Override
        public ChannelMetadata metadata() {
            return metadata;
        }

        @Override
        public ChannelConfig config() {
            return config;
        }

        protected volatile boolean open = true;

        @Override
        public boolean isActive() {
            return parent().isActive();
        }

        @Override
        public boolean isOpen() {
            return parent().isOpen() && open;
        }

        @Override
        protected void doClose() throws Exception {
            open = false;
            doChildChannelClose(this);
        }

        @Override
        protected void doDisconnect() throws Exception {
            doClose();
        }

        protected final ConcurrentLinkedQueue<ByteBuf> buffers = new ConcurrentLinkedQueue<>();

        protected void addBuffer(ByteBuf buffer) {
            this.buffers.add(buffer);
        }

        protected boolean reading = false;

        @Override
        protected void doBeginRead() throws Exception {
            if (reading) {
                return;
            }
            reading = true;
            try {
                ByteBuf buffer = null;
                while ((buffer = buffers.poll()) != null) {
                    pipeline().fireChannelRead(buffer);
                }
                pipeline().fireChannelReadComplete();
            } finally {
                reading = false;
            }
        }

        @Override
        protected void doWrite(ChannelOutboundBuffer buffer) throws Exception {
            /*
            final RecyclableArrayList list = RecyclableArrayList.newInstance();
            boolean freeList = true;
            try {
                ByteBuf buf = null;
                while ((buf = (ByteBuf) buffer.current()) != null) {
                    list.add(buf.retain());
                    buffer.remove();
                }
                freeList = false;
            } finally {
                if (freeList) {
                    for (Object obj : list) {
                        ReferenceCountUtil.safeRelease(obj);
                    }
                    list.recycle();
                }
            }
            */
            // FIXME
            parent().write(buffer.current()).sync();
//            ((AbstractChannel)parent()).doWrite(buffer);
        }

        @Override
        protected boolean isCompatible(EventLoop eventloop) {
            return true;
        }

        @Override
        protected AbstractUnsafe newUnsafe() {
            return new AbstractUnsafe() {
                @Override
                public void connect(SocketAddress addr1, SocketAddress addr2, ChannelPromise pr) {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Override
        protected SocketAddress localAddress0() {
            return parent().localAddress();
        }

        @Override
        protected SocketAddress remoteAddress0() {
            return owner;
        }

        @Override
        protected void doBind(SocketAddress addr) throws Exception {
            throw new UnsupportedOperationException();
        }
    }

    public static void main(String[] args) throws InterruptedException, UnknownHostException {
        SharableSocks5DatagramServerFactory f = new SharableSocks5DatagramServerFactory();
        f.start(new InetSocketAddress(1080));
        ChannelFuture cf = f.createServer(null, new InetSocketAddress("127.0.0.1", 0));

        System.out.println(cf.sync().channel());
    }
}
