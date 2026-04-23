package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core;

import java.util.HashMap;
import java.util.Map;

/**
 * LISTEN 端聚合对象 — 把 LISTEN 状态 sock、半连接队列、syn backlog 阈值从
 * {@link SegmentDispatcher} 中独立出来。对齐 gVisor {@code listenEndpoint} 的
 * 职责范围(syn / accept queue),不含 ESTABLISHED 连接管理。
 *
 * <p><b>当前实现形态</b>:Listener 持有 LISTEN 端独有状态。{@link SegmentDispatcher}
 * 通过 {@link SegmentDispatcher#listener()} 访问本对象,所有涉及 syn queue 的方法
 * 路径经过本类或读取本类字段。
 *
 * <p><b>职责</b>:
 * <ul>
 *   <li>{@link #listenSock} — 状态为 {@code TCP_LISTEN} 的 sock,作为 SYN 匹配
 *       的 "parent sock"(Linux {@code struct request_sock_queue} 的 owner)</li>
 *   <li>{@link #synRegistry} — 半连接队列 ({@code FourTuple → TcpRequestSock}),
 *       对齐 Linux {@code inet_csk(sk)->icsk_accept_queue.listen_opt} + ehash<br>
 *       **并发**:读写均在 TUN EL,不需要 ConcurrentHashMap</li>
 *   <li>{@link #maxSynBacklog} — 半连接容量上限,对齐 Linux
 *       {@code inet_csk(sk)->icsk_accept_queue.listen_opt.max_qlen_log}</li>
 * </ul>
 *
 * <p><b>线程模型</b>:所有访问必须在 TUN EL 上。
 */
public final class Listener {

    /** 半连接队列容量默认值 — 对齐 {@link SegmentDispatcher#DEFAULT_MAX_SYN_BACKLOG}。 */
    public static final int DEFAULT_MAX_SYN_BACKLOG = 1024;

    final TcpSock listenSock;
    final Map<FourTuple, TcpRequestSock> synRegistry = new HashMap<>();
    final int maxSynBacklog;

    Listener(TcpSock listenSock) {
        this(listenSock, DEFAULT_MAX_SYN_BACKLOG);
    }

    Listener(TcpSock listenSock, int maxSynBacklog) {
        this.listenSock = listenSock;
        this.maxSynBacklog = maxSynBacklog;
    }

    public TcpSock listenSock() {
        return listenSock;
    }

    public Map<FourTuple, TcpRequestSock> synRegistry() {
        return synRegistry;
    }

    public int maxSynBacklog() {
        return maxSynBacklog;
    }

    /** 半连接队列当前大小。 */
    public int synQueueSize() {
        return synRegistry.size();
    }

    /** 半连接队列是否已满(达到 {@link #maxSynBacklog})。 */
    public boolean synQueueFull() {
        return synRegistry.size() >= maxSynBacklog;
    }

    /**
     * 加入半连接队列。对齐 Linux {@code inet_csk_reqsk_queue_hash_add}。
     * 幂等:同一 fourTuple 的重复 SYN 不会覆盖已有 req(首次入队为准)。
     */
    public void addRequest(TcpRequestSock req) {
        synRegistry.putIfAbsent(req.fourTuple(), req);
    }

    /** 查找半连接队列中的 req;未命中返回 {@code null}。 */
    public TcpRequestSock findRequest(FourTuple key) {
        return synRegistry.get(key);
    }

    /** 从半连接队列移除 req;返回是否确实移除。 */
    public boolean removeRequest(TcpRequestSock req) {
        return synRegistry.remove(req.fourTuple(), req);
    }
}

