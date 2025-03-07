package com.github.pangolin.routing.server.acceptor;

import com.github.pangolin.routing.context.RouteContext;
import io.netty.channel.ChannelFuture;

public interface Acceptor {

    ChannelFuture start(final RouteContext context) throws Exception;

}