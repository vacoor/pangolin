package com.github.pangolin.routing.v2;

import io.netty.channel.ChannelHandler;

import java.util.Set;

public interface Proxy {

    ChannelHandler newHandler();

    interface Configurer {

        Set<Pattern> addRouting(final Pattern... patterns);

    }
}
