package com.github.pangolin.routing.acceptor.tun.adapter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 跨平台 TUN 适配器基类。
 *
 * <h3>并发语义（best-effort close）</h3>
 * <p>{@link #read(ByteBuffer)}/{@link #write(ByteBuffer[])} 与 {@link #destroy()}
 * 之间是 <b>best-effort</b> 关系：
 * <ul>
 *   <li>{@link #destroy()} 幂等，仅负责关闭底层 fd/session。</li>
 *   <li>调用方须自行保证 <b>destroy 之后不再发起</b> 新的 read/write；
 *       否则会命中 {@code closed} 检查抛 {@link IllegalStateException}，
 *       或者极小的 TOCTOU 窗口内传到 OS 层——Linux/Darwin 的 {@code read(2)}
 *       会返回 {@code EBADF}（安全），Windows Wintun 在 session 结束后会返回
 *       {@code ERROR_HANDLE_EOF}。</li>
 *   <li>对于 <b>已阻塞在 read() 中的线程</b>，当前实现依赖上述 OS 层错误自然退出；
 *       Linux 上 {@code close(fd)} 并不会主动唤醒阻塞的 read，调用方若需确定性
 *       唤醒必须自行 interrupt 或依赖上层 I/O 超时。如需强语义见 tun.todo.md 的
 *       v2 唤醒方案（pipe/eventfd/WaitForMultipleObjects）。</li>
 * </ul>
 */
public abstract class TunAdapter {
    private final AtomicBoolean closed = new AtomicBoolean();

    public abstract String name();

    public ByteBuffer read() throws IOException {
        checkOpen();
        return read0();
    }

    public void write(final ByteBuffer[] packet) throws IOException {
        checkOpen();
        if (0 < packet.length) {
            write0(packet);
        }
    }

    private void checkOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Device is closed.");
        }
    }

    /**
     * 关闭底层 TUN 设备。幂等；调用前请确保不再有新的 read/write 进入。
     * 已阻塞在 read 中的线程通过 OS 层 EBADF / ERROR_HANDLE_EOF 退出（best-effort）。
     */
    public void destroy() throws IOException {
        if (closed.compareAndSet(false, true)) {
            destroy0();
        }
    }

    protected abstract ByteBuffer read0() throws IOException;

    protected abstract void write0(final ByteBuffer[] packet) throws IOException;

    protected abstract void destroy0() throws IOException;

}
