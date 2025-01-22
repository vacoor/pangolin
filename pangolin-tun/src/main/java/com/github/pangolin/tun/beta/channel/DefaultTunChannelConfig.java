package com.github.pangolin.tun.beta.channel;

import static com.github.pangolin.tun.beta.channel.TunChannelOption.TUN_MTU;

import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultChannelConfig;

/**
 * The default {@link TunChannelConfig} implementation.
 */
public class DefaultTunChannelConfig extends DefaultChannelConfig implements TunChannelConfig {
    private int mtu;

    public DefaultTunChannelConfig(final TunChannel channel) {
        super(channel);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getOption(final ChannelOption<T> option) {
        if (option == TUN_MTU) {
            return (T) Integer.valueOf(getMtu());
        }
        return super.getOption(option);
    }

    @Override
    public <T> boolean setOption(final ChannelOption<T> option, final T value) {
        if (!super.setOption(option, value)) {
            if (option == TUN_MTU) {
                setMtu((Integer) value);
            }
            else {
                return false;
            }
        }

        return true;
    }

    @Override
    public int getMtu() {
        return mtu;
    }

    @Override
    public TunChannelConfig setMtu(final int mtu) {
        if (mtu < 0) {
            throw new IllegalArgumentException("mtu must be non-negative.");
        }
        this.mtu = mtu;
        return null;
    }
}
