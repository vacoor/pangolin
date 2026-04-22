package com.github.pangolin.routing.acceptor.tun.adapter;

import java.io.IOException;

/**
 * 由 {@link TunAdapterV2#wakeup()} 显式唤醒阻塞在 {@link TunAdapterV2#read()} 中的线程
 * 时抛出。
 *
 * <p>语义上与 JDK {@code java.nio.channels.ClosedByInterruptException} 区分：本异常不
 * 伴随 {@link Thread#interrupt()}，仅表示 tun 被外部主动唤醒——通常意味着即将
 * {@link TunAdapterV2#close()}，调用方应退出 read 循环而不是当作 IO 故障上报。
 *
 * <p><b>⚠️ 捕获顺序</b>：本异常继承 {@link IOException}，在 read 循环里
 * <b>必须先于 {@code IOException} 捕获</b>，否则会被当作普通 IO 错误走 error path，
 * 导致把"主动关闭"误报为"设备故障"。
 * <pre>
 *   try {
 *       while (true) handle(tun.read());
 *   } catch (ClosedByWakeupException e) {   // 必须在前
 *       // 正常退出
 *   } catch (IOException e) {                // 兜底
 *       // 真正的 IO 故障
 *   }
 * </pre>
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
