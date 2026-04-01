# TCP 协议栈性能优化分析

**分析范围**: `com.github.pangolin.routing.acceptor.tun.net`
**分析日期**: 2026-04-01
**部署场景**: 网关 / 透明代理服务器，客户端通过 WiFi 接入

---

## 一、已识别的性能问题

### P-1: `tcp_sendmsg2` 全局锁导致发送路径串行化（`TcpDemultiplexer.java:434`）

**优先级**: 高

**问题描述**:

```java
public synchronized void tcp_sendmsg2(final Channel net, final TcpSock tp, TcpBuffer skb, boolean flush) {
    skb.sequenceNumber(tp.write_seq);
    tcp_skb_entail(tp, skb);
    if (skb.payloadLength() > 0) {
        skb.dstPort(tp.ir_rmt_port).srcPort(tp.ir_num);
        tp.write_seq += skb.payloadLength();
    }
    if (flush) {
        tcp_push_pending_frames(net, tp);
    }
}
```

`synchronized` 加在 `TcpDemultiplexer` 实例方法上，即全局锁。多个客户端连接运行在不同 Netty
EventLoop 线程上，同时调用同一 `TcpDemultiplexer` 实例的该方法，所有连接的发送路径被完全串行化。

**影响评估**:

- 并发连接越多，锁竞争越激烈，发送吞吐量线性下降
- 50+ 并发连接时吞吐量预估损失 30% 以上，延迟抖动明显
- 是本次分析中**最值得优先修复**的问题

**修复方向**:

需要做的保护范围只有每条连接自己的 `tp.write_seq` 更新与发送队列入队，与其他连接之间没有共享状态。
应将锁从 `TcpDemultiplexer` 实例级别下沉到 `TcpSock` 实例级别（per-connection lock），使不同连接的发送路径可以并行执行。

```java
// 修复方向示意
public void tcp_sendmsg2(final Channel net, final TcpSock tp, TcpBuffer skb, boolean flush) {
    synchronized (tp) {   // 锁粒度下沉到单条连接
        skb.sequenceNumber(tp.write_seq);
        tcp_skb_entail(tp, skb);
        if (skb.payloadLength() > 0) {
            skb.dstPort(tp.ir_rmt_port).srcPort(tp.ir_num);
            tp.write_seq += skb.payloadLength();
        }
        if (flush) {
            tcp_push_pending_frames(net, tp);
        }
    }
}
```

**状态**: ✅ 已修复 — `synchronized` 从方法签名移入方法体，锁对象由 `this`（TcpDemultiplexer）改为 `tp`（TcpSock），不同连接的发送路径现可并行执行。

---

### P-2: OFO 队列使用 TreeMap，热路径存在 O(log n) 开销（`TcpSock.java:212`、`TcpInput.java:1131`）

**优先级**: 中

**问题描述**:

```java
public final TreeMap<Integer, OfoEntry> out_of_order_queue = new TreeMap<>((a, b) -> a - b);
```

OFO 队列热路径操作（`firstEntry`、`pollFirstEntry`、`lowerEntry`、`tailMap().entrySet().iterator()`）均为
O(log n)，其中 `tailMap` 扫描后继条目为 O(n)。

**影响评估**:

- WiFi 场景下突发丢包/乱序时 OFO 队列会被实际填充
- 单连接 OFO 队列上限约 170 条，TreeMap 在此规模下单次操作约几微秒，影响有限
- 大量并发连接同时触发 OFO 处理时，累积开销才显著
- 弱 WiFi 场景下整体吞吐预估提升 5-15%，正常 WiFi 下收益有限

**修复方向**:

用有序链表 + 二分查找替代 TreeMap；重叠检测与驱逐可在链表上线性扫描（实际 OFO 条目数少，
链表局部性更好）。或使用 interval tree 专门加速范围重叠检测。

**状态**: 待评估（建议先 profiling 确认是否是实际瓶颈）

---

### P-3: 定时器全部投入 EventLoop 优先队列，大量连接时调度开销线性增长（`TcpTimer.java:155`）

**优先级**: 中（取决于并发连接数）

**问题描述**:

每条 TCP 连接独立调度 3 个定时器（重传、延迟 ACK、零窗口探测）到 Netty EventLoop 的优先队列，
每次调度操作为 O(log n)。

**影响评估**:

| 并发连接数 | 定时器数量 | 预估收益 |
|-----------|-----------|---------|
| < 200     | < 600     | 可忽略  |
| 500       | ~1500     | < 5%    |
| 2000+     | 6000+     | 明显    |

WiFi 场景下重传定时器触发频率更高，会加重此问题。

**修复方向**:

实现单一 tick timer（如 10ms 间隔），在 callback 中批量检查所有到期定时器；
或引入时间轮（time wheel）数据结构，将调度操作降低到接近 O(1)。

**状态**: 待评估（建议确认并发连接规模后再决定是否实施）

---

## 二、其他性能问题（低优先级）

| 编号 | 位置 | 问题 | 修复方向 |
|------|------|------|---------|
| P-4 | `TcpDemultiplexer.java` `synRegistry`/`establishedRegistry` | EventLoop 单线程上下文使用 `ConcurrentHashMap`，并发保护冗余 | 改为 `HashMap` |
| P-5 | `TcpTimer.java:130` `timers` map | 同上，`ConcurrentMap` 冗余 | 改为 `HashMap` |
| P-6 | `TcpOutput.java:293` `tcp_synack_options` | 每次 SYN-ACK 都 new 12 字节 byte[]，高并发建连时 GC 压力增加 | Netty pooled allocator 或对象池 |
| P-7 | `TcpHandshaker.java:266` | `AtomicInteger` 用作输出参数，有不必要的 volatile 内存屏障 | 改为简单包装对象 |
| P-8 | `TcpInput.java:1278` 等多处 | debug 日志参数在级别检查前已计算，存在无效开销 | 加 `isDebugEnabled()` 保护 |
| P-9 | `TcpTimer.java:380` | timer callback 中每次 `new SimpleDateFormat(...)` | 改用 `DateTimeFormatter`（线程安全）或直接记录毫秒时间戳 |

---

## 三、修复优先顺序

1. **P-1**（全局锁）— 结构性问题，多客户端网关场景下是真实瓶颈，应最先修复
2. **P-2**（OFO 队列）— 建议先 profiling，弱网环境下再考虑实施
3. **P-3**（定时器）— 确认并发连接规模后再决定
4. **P-4 ~ P-9** — 锦上添花，可在有余力时逐步清理
