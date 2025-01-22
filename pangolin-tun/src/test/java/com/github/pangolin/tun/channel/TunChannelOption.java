package com.github.pangolin.tun.channel;

import io.netty.channel.ChannelOption;

/**
 * Provides {@link ChannelOption}s for {@link TunChannel}s.
 */
public final class TunChannelOption<T> extends ChannelOption<T> {
    /**
     * Defines MTU for the created tun device (not supported on windows).
     */
    public static final ChannelOption<Integer> TUN_MTU = valueOf("TUN_MTU");

    @SuppressWarnings({ "java:S1144", "java:S1874" })
    private TunChannelOption(final String name) {
        super(name);
    }
}
