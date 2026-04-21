package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.netty;

import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultChannelConfig;

import java.util.Map;

/**
 * {@link TcpChannel} 的 {@code ChannelConfig} 实现,承载 TCP options 到底层
 * {@code TcpSock} 状态的映射。
 *
 * <p>支持的 option:
 * <ul>
 *   <li>{@link ChannelOption#SO_KEEPALIVE} — 映射到 {@code TcpSock.keepaliveEnabled};</li>
 *   <li>{@link ChannelOption#TCP_NODELAY} — 当前 v2 发送路径恒用 {@code TCP_NAGLE_OFF},
 *       等价于 TCP_NODELAY=true;setter 记录用户期望值但不影响实际行为(未来接入 Nagle 时启用);</li>
 *   <li>{@link ChannelOption#WRITE_BUFFER_WATER_MARK} / low / high — 复用
 *       {@link DefaultChannelConfig} 默认实现,由 {@link TcpChannel} 在 writability 水位判定时读取。</li>
 * </ul>
 */
public class TcpChannelConfig extends DefaultChannelConfig {

    private final TcpChannel tcpChannel;
    private volatile boolean tcpNoDelay = true;

    public TcpChannelConfig(TcpChannel ch) {
        super(ch);
        this.tcpChannel = ch;
    }

    @Override
    public Map<ChannelOption<?>, Object> getOptions() {
        return getOptions(super.getOptions(),
                ChannelOption.SO_KEEPALIVE,
                ChannelOption.TCP_NODELAY);
    }

    @Override
    @SuppressWarnings({"unchecked", "deprecation"})
    public <T> T getOption(ChannelOption<T> option) {
        if (option == ChannelOption.SO_KEEPALIVE) {
            return (T) Boolean.valueOf(tcpChannel().sock().keepaliveEnabled());
        }
        if (option == ChannelOption.TCP_NODELAY) {
            return (T) Boolean.valueOf(tcpNoDelay);
        }
        return super.getOption(option);
    }

    @Override
    public <T> boolean setOption(ChannelOption<T> option, T value) {
        validate(option, value);
        if (option == ChannelOption.SO_KEEPALIVE) {
            tcpChannel().sock().keepaliveEnabled(Boolean.TRUE.equals(value));
            return true;
        }
        if (option == ChannelOption.TCP_NODELAY) {
            // v2 当前发送路径恒用 TCP_NAGLE_OFF,此值仅作记录,不影响行为
            this.tcpNoDelay = Boolean.TRUE.equals(value);
            return true;
        }
        return super.setOption(option, value);
    }

    private TcpChannel tcpChannel() {
        return tcpChannel;
    }
}
