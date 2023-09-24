package com.github.pangolin.routing.node;

import com.github.pangolin.routing.pattern.ProxyHandlerFactory;
import io.netty.channel.ChannelHandler;

public interface ServerInstance extends ProxyHandlerFactory {

    String name();

    boolean isPassingCheck();

    ChannelHandler newProxyHandler();

//    long getTime();

}
