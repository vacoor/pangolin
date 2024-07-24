package com.github.pangolin.routing.v2.server;

import com.github.pangolin.routing.v2.context.RouteContext;
import io.netty.channel.ChannelFuture;

public interface Acceptor {

    ChannelFuture start(final RouteContext context) throws Exception;

}