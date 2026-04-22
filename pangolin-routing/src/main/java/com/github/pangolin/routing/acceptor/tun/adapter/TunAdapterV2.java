package com.github.pangolin.routing.acceptor.tun.adapter;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TUN 适配器 v2：给阻塞 {@link #read()} 配一个可被外部唤醒的信号源（eventfd / kqueue
 * EVFILT_USER / Windows Event），从而把 tun fd 变成"可中断的 blocking channel"。
 *
 * <p>典型用法（读线程自释放，推荐）：
 * <pre>
 *   Thread reader = new Thread(() -&gt; {
 *       try {
 *           while (true) handle(tun.read());
 *       } catch (ClosedByWakeupException e) {
 *           // 被 wakeup，优雅退出
 *       } catch (IOException e) {
 *           // 其他 IO 异常
 *       } finally {
 *           try { tun.close(); } catch (IOException ignore) {}
 *       }
 *   });
 *   reader.start();
 *
 *   // 外部关闭：
 *   tun.wakeup();
 *   // 可选：reader.join(timeoutMs);
 * </pre>
 *
 * <p>也支持 netty EventLoop 模式（在 EventLoop 线程里调 {@link #close()}）或外部编排
 * （{@code wakeup → reader.join → close}）。
 *
 * <h3>线程契约</h3>
 * <ul>
 *   <li>{@link #read()} 与 {@link #write(ByteBuffer[])} <b>各自单线程</b>——不允许两个线程
 *       同时调 {@code read}，也不允许两个线程同时调 {@code write}；但 <b>读线程与写线程
 *       可以是不同的两个线程并发跑</b>（全双工）。</li>
 *   <li>{@link #wakeup()} <b>线程安全</b>，可从任意线程调；关 closed 后为 no-op，永远不会
 *       碰 fd / handle。</li>
 *   <li>{@link #close()} 由调用方承诺：<b>读/写线程已退出或从未启动</b>。违反会 UAF
 *       （fd/handle 已被 OS 回收并可能重用给无关资源，然后读写继续调就会踩到别人的 fd）。</li>
 *   <li>推荐在 read 循环的 {@code finally} 里调 {@code close()} —— 物理上不可能与 read 并发；
 *       如果有独立写线程，需先让写线程退出再 close。</li>
 * </ul>
 */
public abstract class TunAdapterV2 implements Closeable {

    private final AtomicBoolean closed = new AtomicBoolean(false);

    public abstract String name();

    public final ByteBuffer read() throws IOException {
        checkOpen();
        return read0();
    }

    public final void write(final ByteBuffer[] packet) throws IOException {
        checkOpen();
        if (0 < packet.length) {
            write0(packet);
        }
    }

    private void checkOpen() throws ClosedChannelException {
        if (closed.get()) {
            throw new ClosedChannelException();
        }
    }

    /**
     * 让阻塞在 {@link #read()} 的线程立即返回并抛出 {@link ClosedByWakeupException}。
     * 线程安全；不释放 fd / handle；可重复调用；已 {@link #close()} 后为 no-op。
     *
     * <p>这是把 tun 从 "blocking syscall" 变成 "可中断 syscall" 的唯一机制，
     * 也是 netty EventLoop 的 {@code Selector.wakeup()} 在 tun 上的等价物。
     *
     * <p>已 closed 时直接返回而不是调用 {@link #wakeup0()}，避免对已释放的 eventfd /
     * kqueue / HANDLE 操作 —— 这些 fd/handle 数字在 OS 里可能已被其它资源重用，
     * 踩中会静默破坏无关资源。
     */
    public final void wakeup() throws IOException {
        if (!closed.get()) {
            wakeup0();
        }
    }

    /**
     * 释放底层 fd / handle / session。幂等。
     *
     * <p><b>契约</b>：调用方保证读线程已退出或从未启动。推荐在 read 循环的
     * {@code finally} 里调用。
     */
    @Override
    public final void close() throws IOException {
        if (closed.compareAndSet(false, true)) {
            destroy0();
        }
    }


    public final boolean isClosed() {
        return closed.get();
    }

    /**
     * 从 tun 读一个包。仅在未 close 状态被调用。收到 wakeup 信号时抛
     * {@link ClosedByWakeupException}。
     */
    protected abstract ByteBuffer read0() throws IOException;

    /**
     * 向 tun 写一组 buffer。仅在未 close 状态被调用。
     */
    protected abstract void write0(final ByteBuffer[] packet) throws IOException;

    /**
     * 发唤醒信号：让 {@link #read0()} 立即就绪并抛 {@link ClosedByWakeupException}。
     * <b>不得释放 tun fd / handle。</b>
     */
    protected abstract void wakeup0() throws IOException;

    /**
     * 释放 fd / session / 唤醒通道。仅被 {@link #close()} 调用一次。
     */
    protected abstract void destroy0() throws IOException;
}
