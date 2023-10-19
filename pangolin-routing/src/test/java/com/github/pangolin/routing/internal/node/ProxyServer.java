package com.github.pangolin.routing.internal.node;

import com.github.pangolin.routing.ProxyHandlerFactory;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;

import java.net.InetSocketAddress;

public interface ProxyServer extends ProxyHandlerFactory {
    ProxyServer DIRECT = new ProxyServer() {
        @Override
        public String getName() {
            return "DIRECT";
        }

        @Override
        public ChannelHandler newProxyHandler(InetSocketAddress sa) {
            return null;
        }

        @Override
        public String toString() {
            return getName();
        }
    };

    ProxyServer REJECT = new ProxyServer() {
        @Override
        public String getName() {
            return "REJECT";
        }

        @Override
        public ChannelHandler newProxyHandler(InetSocketAddress sa) {
            return new ChannelDuplexHandler() {
                @Override
                public void channelRegistered(final ChannelHandlerContext ctx) throws Exception {
                    ctx.close();
                }

                @Override
                public void channelActive(final ChannelHandlerContext ctx) throws Exception {
                    throw new IllegalStateException("reject a channel: " + ctx.channel());
                }
            };
        }

        @Override
        public String toString() {
            return getName();
        }
    };

    String getName();

    ChannelHandler newProxyHandler(InetSocketAddress sa);

}
