package com.github.pangolin.routing.acceptor.tun.adapter.linux.jna;

/**
 * Linux {@code struct epoll_event} 按架构切换的内存布局。
 *
 * <p>glibc 在 <b>x86 系</b>（i386 / x86_64）上给 {@code epoll_event} 加了
 * {@code __attribute__((packed))}，大小 12 字节，{@code data} 紧跟在 {@code events}
 * 后面不补齐：
 * <pre>
 *   offset 0, 4 : uint32_t events
 *   offset 4, 8 : epoll_data_t data   // 非对齐 8-byte
 * </pre>
 *
 * <p>其他架构（aarch64 / arm / ppc 等）没有 packed，{@code data} 按 8-byte 自然对齐，
 * 总大小 16 字节：
 * <pre>
 *   offset 0,  4 : uint32_t events
 *   offset 4,  4 : padding
 *   offset 8,  8 : epoll_data_t data
 * </pre>
 *
 * <p>本类在类加载期一次性解析 {@code os.arch} 并固定布局常量，避免每次 read0 去判分支。
 */
final class EpollEventLayout {

    static final int SIZE;
    static final int EVENTS_OFFSET = 0;
    static final int DATA_OFFSET;

    static {
        final String arch = normalize(System.getProperty("os.arch", ""));
        // x86 系(32/64)在 glibc 里是 packed 的,其他架构不 packed
        if ("x86_64".equals(arch) || "i386".equals(arch)) {
            SIZE = 12;
            DATA_OFFSET = 4;
        } else {
            SIZE = 16;
            DATA_OFFSET = 8;
        }
    }

    private EpollEventLayout() {
    }

    private static String normalize(final String arch) {
        final String lower = arch.toLowerCase();
        // Java 在不同发行版 / 架构上的别名统一到 glibc 的两类
        if ("amd64".equals(lower) || "x86_64".equals(lower)) {
            return "x86_64";
        }
        if ("i386".equals(lower) || "i486".equals(lower) || "i586".equals(lower)
                || "i686".equals(lower) || "x86".equals(lower)) {
            return "i386";
        }
        return lower;
    }
}
