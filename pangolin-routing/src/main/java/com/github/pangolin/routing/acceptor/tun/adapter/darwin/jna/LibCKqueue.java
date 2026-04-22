package com.github.pangolin.routing.acceptor.tun.adapter.darwin.jna;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

/**
 * v2 独立的 libc JNA 绑定（Darwin）：kqueue / kevent / fcntl。
 *
 * <h3>struct kevent 布局（Darwin x86_64 / arm64 一致，32 字节，自然对齐）</h3>
 * <pre>
 *   offset  0, len 8 : uintptr_t ident
 *   offset  8, len 2 : int16_t   filter
 *   offset 10, len 2 : uint16_t  flags
 *   offset 12, len 4 : uint32_t  fflags
 *   offset 16, len 8 : intptr_t  data
 *   offset 24, len 8 : void     *udata
 * </pre>
 * 全部字段都是自然对齐，不会踩 packed 坑；但本实现仍采用 {@link com.sun.jna.Memory}
 * 按偏移裸写以避免 {@code Structure} 的 autoRead/autoWrite 开销。
 */
public interface LibCKqueue extends Library {

    LibCKqueue INSTANCE = Native.load("c", LibCKqueue.class);

    /** struct kevent 尺寸（Darwin 64-bit）。 */
    int KEVENT_SIZE = 32;
    int KEVENT_IDENT_OFFSET = 0;
    int KEVENT_FILTER_OFFSET = 8;
    int KEVENT_FLAGS_OFFSET = 10;
    int KEVENT_FFLAGS_OFFSET = 12;
    int KEVENT_DATA_OFFSET = 16;
    int KEVENT_UDATA_OFFSET = 24;

    // filter
    short EVFILT_READ = -1;
    short EVFILT_USER = -10;

    // flags
    short EV_ADD = 0x0001;
    short EV_DELETE = 0x0002;
    short EV_ENABLE = 0x0004;
    short EV_ONESHOT = 0x0010;
    short EV_CLEAR = 0x0020;

    // fflags for EVFILT_USER
    int NOTE_TRIGGER = 0x01000000;

    /** fcntl F_SETFL / F_GETFL。 */
    int F_SETFL = 4;
    int F_GETFL = 3;

    /** Darwin O_NONBLOCK（0x0004）— 与 Linux 的 04000 不同。 */
    int O_NONBLOCK = 0x0004;

    /** Darwin errno：EAGAIN == 35（与 Linux 11 不同）。 */
    int EAGAIN = 35;
    /** Darwin errno：EINTR == 4。 */
    int EINTR = 4;

    int close(int fd);

    int fcntl(int fd, int cmd, int arg);

    int kqueue();

    /**
     * @param kq         kqueue descriptor
     * @param changelist 本次要注册 / 修改的 kevent 数组（可为 null，数组大小由 nchanges 指定）
     * @param nchanges   changelist 条数
     * @param eventlist  用于接收触发的 kevent（可为 null）
     * @param nevents    eventlist 容量
     * @param timeout    {@code struct timespec *}，null 表示永久阻塞
     * @return 触发事件数；-1 表示错误，errno 说明原因
     */
    int kevent(int kq, Pointer changelist, int nchanges,
               Pointer eventlist, int nevents, Pointer timeout);
}
