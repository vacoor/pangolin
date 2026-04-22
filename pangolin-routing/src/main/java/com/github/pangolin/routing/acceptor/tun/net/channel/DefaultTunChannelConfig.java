package com.github.pangolin.routing.acceptor.tun.net.channel;

import static com.github.pangolin.routing.acceptor.tun.net.channel.TunChannelOption.TUN_MTU;
import static com.github.pangolin.routing.acceptor.tun.net.channel.TunChannelOption.WINTUN_TYPE;
import static com.github.pangolin.routing.acceptor.tun.net.channel.TunChannelOption.WINTUN_UUID;

import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultChannelConfig;

/**
 * The default {@link TunChannelConfig} implementation.
 */
public class DefaultTunChannelConfig extends DefaultChannelConfig implements TunChannelConfig {
    private int mtu;
    private String wintunType;
    private String wintunUuid;

    public DefaultTunChannelConfig(final Channel channel) {
        super(channel);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getOption(final ChannelOption<T> option) {
        if (option == TUN_MTU) {
            return (T) Integer.valueOf(getMtu());
        } else if (WINTUN_TYPE.equals(option)) {
            return (T) wintunType;
        } else if (WINTUN_UUID.equals(option)) {
            return (T) wintunUuid;
        }
        return super.getOption(option);
    }

    @Override
    public <T> boolean setOption(final ChannelOption<T> option, final T value) {
        if (!super.setOption(option, value)) {
            if (option == TUN_MTU) {
                setMtu((Integer) value);
            } else if (WINTUN_TYPE.equals(option)) {
                wintunType = (String) value;
            } else if (WINTUN_UUID.equals(option)) {
                wintunUuid = (String) value;
            } else {
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
