package com.github.pangolin.routing.acceptor.tun.adapter.linux.jna;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

import java.nio.ByteBuffer;

/**
 * v2 独立的 libc JNA 绑定：epoll / eventfd / fcntl + read/write/close。
 *
 * <p>只列 v2 需要的符号，不复用 v1 {@code unix/jna/LibC}，两条路径独立演进。
 *
 * <h3>epoll_event 处理</h3>
 * <p>glibc 在 x86 系（i386 / x86_64）把 {@code struct epoll_event} 定义为
 * {@code __attribute__((packed))} 的 12 字节布局，而 JNA {@code Structure} 默认按 8 字节
 * 对齐会排到 16 字节。为避免不同 JNA 版本对 {@code @Aligned(1)} /
 * {@code getNativeAlignment()=1} 的行为差异，本实现直接用 {@link Pointer} +
 * {@link com.sun.jna.Memory} 按字节偏移裸写：
 * <pre>
 *   x86 系（packed, 12B）        其他架构（非 packed, 16B）
 *   offset 0, 4 : uint32_t events  offset 0,  4 : uint32_t events
 *   offset 4, 8 : uint64_t data    offset 4,  4 : padding
 *                                  offset 8,  8 : uint64_t data
 * </pre>
 * 尺寸与偏移由 {@link EpollEventLayout} 在类加载时根据 {@code os.arch} 一次性解析并固定。
 */
public interface LibCEpoll extends Library {

    LibCEpoll INSTANCE = Native.load("c", LibCEpoll.class);

    /** epoll_event 尺寸：x86 系 12，其他架构 16（aarch64 / arm / ppc 等）。 */
    int EPOLL_EVENT_SIZE = EpollEventLayout.SIZE;
    int EPOLL_EVENT_EVENTS_OFFSET = EpollEventLayout.EVENTS_OFFSET;
    int EPOLL_EVENT_DATA_OFFSET = EpollEventLayout.DATA_OFFSET;

    int EPOLL_CTL_ADD = 1;
    int EPOLL_CTL_DEL = 2;
    int EPOLL_CTL_MOD = 3;

    int EPOLLIN = 0x001;

    /** eventfd 非阻塞 flag，等值于 O_NONBLOCK（Linux）。 */
    int EFD_NONBLOCK = 04000;

    /** fcntl F_SETFL cmd。 */
    int F_SETFL = 4;
    int F_GETFL = 3;

    /** Linux O_NONBLOCK（0x800）。 */
    int O_NONBLOCK = 04000;

    /** Linux errno：EAGAIN == EWOULDBLOCK == 11。 */
    int EAGAIN = 11;
    /** Linux errno：EINTR == 4。 */
    int EINTR = 4;

    int close(int fd);

    int read(int fd, ByteBuffer buf, long count);

    int write(int fd, ByteBuffer buf, long count);

    int write(int fd, Pointer buf, long count);

    int fcntl(int fd, int cmd, int arg);

    int eventfd(int initval, int flags);

    int epoll_create1(int flags);

    int epoll_ctl(int epfd, int op, int fd, Pointer event);

    int epoll_wait(int epfd, Pointer events, int maxEvents, int timeout);
}
