package com.github.pangolin.routing.acceptor.tun.adapter;

import java.io.IOException;

/**
 * 由 {@link TunAdapterV2#destroy()} 显式唤醒阻塞在 read/write 中的线程时抛出。
 *
 * <p>语义上与 JDK {@code java.nio.channels.ClosedByInterruptException} 区分：
 * 本异常不伴随 {@link Thread#interrupt()}，仅表示 tun 设备已进入 CLOSING/CLOSED 状态。
 */
public class ClosedByWakeupException extends IOException {

    private static final long serialVersionUID = 1L;

    public ClosedByWakeupException() {
        super("TUN device closed by wakeup");
    }

    public ClosedByWakeupException(final String message) {
        super(message);
    }
}
