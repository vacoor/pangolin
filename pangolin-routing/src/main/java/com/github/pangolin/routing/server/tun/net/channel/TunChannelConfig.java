package com.github.pangolin.routing.server.tun.net.channel;

import io.netty.channel.ChannelConfig;

/**
 * A {@link ChannelConfig} for a {@link TunChannel}.
 *
 * <h3>Available options</h3>
 * <p>
 * In addition to the options provided by {@link ChannelConfig}, {@link TunChannelConfig} allows the
 * following options in the option map:
 *
 * <table border="1" cellspacing="0" cellpadding="6">
 * <tr>
 * <th>Name</th><th>Associated setter method</th>
 * </tr><tr>
 * <td>{@link TunChannelOption#TUN_MTU}</td><td>{@link #setMtu(int)}</td>
 * </tr>
 * </table>
 */
public interface TunChannelConfig extends ChannelConfig {
    /**
     * Gets the {@link TunChannelOption#TUN_MTU} option.
     */
    int getMtu();

    /**
     * Sets the {@link TunChannelOption#TUN_MTU} option.
     */
    TunChannelConfig setMtu(int mtu);
}
