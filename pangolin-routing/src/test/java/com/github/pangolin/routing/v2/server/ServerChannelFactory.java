package com.github.pangolin.routing.v2.server;

import io.netty.channel.ChannelFuture;

public interface ServerChannelFactory {

    ChannelFuture start();

}