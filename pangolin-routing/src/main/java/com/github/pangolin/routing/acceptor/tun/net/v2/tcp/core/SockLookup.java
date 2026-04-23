package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import com.github.pangolin.routing.acceptor.tun.net.codec.TcpPacketBuf;

import java.util.Map;
import java.util.Objects;

/**
 * 入站段四元组 → {@link SockCommon} 的纯查表函数,对齐 Linux
 * <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/inet_hashtables.c">
 * {@code __inet_lookup_skb}</a>(net/ipv4/inet_hashtables.c)。
 *
 * <p>R4.2b-1:从 {@link SegmentDispatcher} 中剥离,独立为无副作用的查表组件。
 * 后续 R4.2b-3 由 {@code SegmentDispatcher} 调用,{@code SegmentDispatcher} /
 * {@code TcpStack} 不再直接暴露 lookup。
 *
 * <p><b>查找顺序</b>:
 * <ol>
 *   <li>established 槽(按四元组)— 命中即返回 {@link TcpSock};</li>
 *   <li>timewait 槽(按四元组)— 命中返回 {@link TcpTimewaitSock},外层 dispatcher
 *       派发到 {@code timewaitStateProcess};</li>
 *   <li>半连接队列(listener 的 synRegistry)— 命中返回 {@link TcpRequestSock},
 *       外层派发到 {@code checkReq};</li>
 *   <li>以上全 miss → 回落到 {@link Listener#listenSock},LISTEN 状态机接管。</li>
 * </ol>
 *
 * <p><b>线程模型</b>:只读三张注册表,所有 map 在 established / timewait 侧均为
 * {@link java.util.concurrent.ConcurrentHashMap},TUN EL 上可安全并发查找。
 * listener.synRegistry 读写均在 TUN EL,SockLookup 调用点亦在 TUN EL,无需 CAS。
 */
public final class SockLookup {

    private final Map<FourTuple, TcpSock> establishedRegistry;
    private final Map<FourTuple, TcpTimewaitSock> timewaitRegistry;
    private final Listener listener;

    public SockLookup(Map<FourTuple, TcpSock> establishedRegistry,
                      Map<FourTuple, TcpTimewaitSock> timewaitRegistry,
                      Listener listener) {
        this.establishedRegistry = Objects.requireNonNull(establishedRegistry, "establishedRegistry");
        this.timewaitRegistry = Objects.requireNonNull(timewaitRegistry, "timewaitRegistry");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * 按四元组查找入站段的归属 sock。未命中任何动态槽时回落到 listenSock(LISTEN 端)。
     * 不会返回 {@code null} —— listenSock 作为"catch-all"保证外层 dispatcher 总有派发目标。
     *
     * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/inet_hashtables.c">
     *      {@code __inet_lookup_skb}</a>
     */
    public SockCommon lookup(final TcpPacketBuf pkt) {
        final FourTuple key = FourTuple.of(pkt);
        TcpSock established = establishedRegistry.get(key);
        if (established != null) {
            return established;
        }
        TcpTimewaitSock tw = timewaitRegistry.get(key);
        if (tw != null) {
            return tw;
        }
        TcpRequestSock req = listener.findRequest(key);
        if (req != null) {
            return req;
        }
        return listener.listenSock;
    }
}
