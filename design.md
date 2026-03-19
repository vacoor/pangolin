# TCP/IP 协议栈优化设计文档

> 分析范围：`com.github.pangolin.routing.acceptor.tun.net.handler.tcp`
> 分析版本：feature/v1.2.2-backhaul-ng-refactor

---

## 概览

当前 TCP 协议栈忠实对照 Linux 内核 `net/ipv4/tcp_*.c` 实现，协议正确性较好，但在 Java 环境下存在若干性能瓶颈和功能缺失，以下按优先级分级列出。

---

## P0 — 高优先级（影响吞吐或正确性）

### 1. `TcpBuffer.asBuilder().build()` 反复触发全量序列化

**问题位置**
- `TcpOutput#tcp_write_xmit` (L641)：`skb.asBuilder().build().length()` 仅为计算长度
- `TcpOutput#tcp_snd_wnd_test` (L787)：同上
- `TcpOutput#__tcp_retransmit_skb` (L1073)：同上
- `TcpOutput#tcp_minshall_update` (L557)：同上
- `TcpOutput#tcp_write_wakeup` (L1323)：同上

**根因**
`TcpBuffer` 没有缓存 payload 长度，每次需要知道数据长度时都要通过 pcap4j 完整序列化一个 `TcpPacket` 对象，包含所有字段填充、checksum 计算等，这在 critical path 上非常昂贵。

**设计方案**

在 `TcpBuffer` 中增加一个懒加载的 `cachedPayloadLength` 字段：

```java
// TcpBuffer.java
private transient int cachedPayloadLength = -1;

public int payloadLength() {
    if (cachedPayloadLength < 0) {
        Packet.Builder p = payloadBuilder;
        cachedPayloadLength = (p != null) ? p.build().length() : 0;
    }
    return cachedPayloadLength;
}

public TcpBuffer payloadBuilder(Packet.Builder payloadBuilder) {
    this.payloadBuilder = payloadBuilder;
    this.cachedPayloadLength = -1; // invalidate cache
    return this;
}
```

调用侧改为 `skb.payloadLength()` 替代 `skb.asBuilder().build().length()`。

---

### 2. 定时器管理：全局 `ConcurrentMap<Runnable, Future<?>>` 设计问题

**问题位置**
`TcpTimer.java:129`

```java
private final ConcurrentMap<Runnable, Future<?>> timers = Maps.newConcurrentMap();
```

**根因**
`TcpTimer` 是所有 socket 共享的单例，每个连接的3个定时器（retransmit、delack、keepalive）都以 `Runnable` 为 key 存入同一个全局 Map。在高并发连接（如数千路）场景下：
1. Map 条目持续增长（已关闭连接的条目未清理）
2. 每次 `mod_timer` 都需要 `get + cancel + put` 3次 CAS 操作
3. 定时器 cancel 使用 `future.cancel(true)` 有线程中断风险

**设计方案**

将各个定时器的 `ScheduledFuture` 直接存在 `TcpSock` 中，彻底消除全局 Map：

```java
// TcpSock.java 新增字段
public ScheduledFuture<?> retransmit_future;
public ScheduledFuture<?> delack_future;
public ScheduledFuture<?> keepalive_future;
```

```java
// TcpTimer.java
private int __mod_timer(TcpSock tp, Runnable timer, long expires, int options) {
    final long delay = expires - TcpClock.jiffies();
    ScheduledFuture<?> old = getFuture(tp, timer);
    if (old != null && !old.isDone()) {
        old.cancel(false);
    }
    ScheduledFuture<?> next = tp.child.channel().eventLoop()
            .schedule(timer, delay, TimeUnit.MILLISECONDS);
    setFuture(tp, timer, next);
    return 0;
}

private ScheduledFuture<?> getFuture(TcpSock tp, Runnable timer) {
    if (timer == tp.icsk_retransmit_timer) return tp.retransmit_future;
    if (timer == tp.icsk_delack_timer)    return tp.delack_future;
    if (timer == tp.sk_timer)             return tp.keepalive_future;
    return null;
}
```

---

### 3. `tcp_sendmsg2` synchronized 全局锁

**问题位置**
`TcpDemultiplexer.java:410`

```java
public synchronized void tcp_sendmsg2(final Channel net, final TcpSock tp, TcpBuffer skb, boolean flush) {
```

**根因**
Netty 的每个 `Channel` 绑定在固定的 `EventLoop` 线程，同一 socket 的 `channelRead`（接收上游数据）和 `tcp_sendmsg2`（回写 TUN 数据）天然串行。`synchronized` 只在跨线程 write 场景才必要，但这里的调用已在 EventLoop 内，synchronized 带来了不必要的锁竞争。

**设计方案**

移除 `synchronized`，改为确保所有调用在 EventLoop 线程执行：

```java
// 如需跨线程调用时，通过 eventLoop 投递
public void tcp_sendmsg2(final Channel net, final TcpSock tp, TcpBuffer skb, boolean flush) {
    // assert net.eventLoop().inEventLoop();
    skb.sequenceNumber(tp.write_seq);
    tcp_skb_entail(tp, skb);
    if (null != skb.payloadBuilder()) {
        skb.dstPort(tp.ir_rmt_port).srcPort(tp.ir_num);
        tp.write_seq += skb.payloadLength();
    }
    if (flush) {
        tcp_push_pending_frames(net, tp);
    }
}
```

---

### 4. `consume` 方法中的双重数据拷贝

**问题位置**
`TcpDemultiplexer.java:534-542`

```java
public void consume(final TcpSock sk, final TcpPacket skb) {
    final byte[] bytes = skb.getPayload().getRawData();  // 拷贝 #1
    ...
    innerChannel(sk).writeAndFlush(Unpooled.wrappedBuffer(bytes, offset, length)); // 不会再次拷贝，但前面已经拷贝了
}
```

**根因**
pcap4j 的 `getRawData()` 返回一个**新分配的** `byte[]`，每个入包都触发一次堆内存分配和数据拷贝，在高吞吐场景下 GC 压力大。

**设计方案**

在 `IpPacketCodec`（或上游）解码时直接保留 Netty `ByteBuf` 引用，下传到 consume 层，通过 `ByteBuf.slice()` 实现零拷贝转发：

```java
// 传递 ByteBuf 而不是 pcap4j 的 IpPacket payload
public void consume(final TcpSock sk, final ByteBuf payload, int offset, int length) {
    if (sk.child != null && length > 0) {
        int window = output.tcp_receive_window(sk);
        int actualLen = Math.min(window, length);
        // slice 零拷贝，retain 管理引用计数
        innerChannel(sk).writeAndFlush(payload.retainedSlice(offset, actualLen));
    }
}
```

---

### 5. 乱序包队列（Out-of-Order Queue）未实现

**问题位置**
`TcpInput.java:1134`

```java
private void tcp_data_queue_ofo(final TcpSock sk, final T ipPacket) {
    // 空实现：乱序包被直接丢弃
}
```

**根因**
在不稳定的网络环境（高延迟、多路由）下，包乱序是常态。未能缓冲乱序包意味着发送方必须重传，使得有效吞吐量大幅降低。

**设计方案**

使用 `TreeMap<Integer, IpPacket>` 按 seq 排序缓存乱序包，在每次 `queue_and_out` 后尝试消费：

```java
// TcpSock.java 新增
public final TreeMap<Integer, T> out_of_order_queue = new TreeMap<>(TcpUtils::compareSeq);

// TcpInput.java
private void tcp_data_queue_ofo(final TcpSock sk, final T ipPacket) {
    final TcpPacket tcpPacket = ipPacket.get(TcpPacket.class);
    final int seq = tcpPacket.getHeader().getSequenceNumber();
    sk.out_of_order_queue.putIfAbsent(seq, ipPacket);
}

// 在 queue_and_out 末尾调用 tcp_ofo_queue
private void tcp_ofo_queue(Channel net, TcpSock tp) throws IOException {
    while (!tp.out_of_order_queue.isEmpty()) {
        Map.Entry<Integer, T> entry = tp.out_of_order_queue.firstEntry();
        int seq = entry.getKey();
        if (seq != tp.rcv_nxt) break;
        tp.out_of_order_queue.pollFirstEntry();
        queue_and_out(net, tp, entry.getValue());
    }
}
```

---

## P1 — 中优先级（影响性能或健壮性）

### 6. `tcp_under_memory_pressure` 硬编码返回 `true`

**问题位置**
`TcpOutput.java:921-925`

```java
private boolean tcp_under_memory_pressure() {
    return true; // 始终认为内存紧张
}
```

**影响**
导致 `__tcp_select_window` 中 `icsk_ack.quick = 0`（关闭快速 ACK），且 `tcp_adjust_rcv_ssthresh` 被频繁调用。接收方窗口被过早压缩，降低了可接受的最大发送速率。

**设计方案**

引入简单的 watermark 机制。可以根据当前出站数据缓冲量与 socket 配置的 `rcvbuf` 之比来判断：

```java
private boolean tcp_under_memory_pressure(TcpSock tp) {
    // 以 out-of-order 队列大小作为内存压力指标
    return tp.out_of_order_queue.size() > SysctlOptions.tcp_rmem_pressure_threshold;
}
```

短期可先改为 `return false`，消除窗口误压缩，待接收缓冲管理完善后再引入动态判断。

---

### 7. `tcp_full_space` 接收缓冲区硬编码

**问题位置**
`TcpOutput.java:957-959`

```java
int tcp_full_space(TcpSock tp) {
    final int sk_rcvbuf = U16_MAX << 6;  // 硬编码 4MB
    return tcp_win_from_space(sk_rcvbuf);
}
```

**影响**
接收窗口不随实际消费速率动态收缩，无法有效进行流量控制。

**设计方案**

在 `TcpSock` 中引入 `sk_rcvbuf`（可配置）和 `sk_rmem_alloc`（当前已分配），使接收窗口反映真实可用缓冲：

```java
// TcpSock.java
public int sk_rcvbuf = TcpConstants.U16_MAX << 6; // 默认 4MB，可通过 SysctlOptions 配置
public volatile int sk_rmem_alloc = 0;  // 当前 OFO 队列已占用

int tcp_full_space(TcpSock tp) {
    return tcp_win_from_space(tp.sk_rcvbuf - tp.sk_rmem_alloc);
}
```

---

### 8. `tcp_win_from_space` 整数溢出

**问题位置**
`TcpOutput.java:981-983`

```java
private int __tcp_win_from_space(int scaling_ratio, int space) {
    int scaled_space = space * scaling_ratio; // int * int 可能溢出
    return scaled_space >> TCP_RMEM_TO_WIN_SCALE;
}
```

当 `space = 4MB（4194304）`、`scaling_ratio = 128` 时，`space * scaling_ratio` = 536870912，接近 `Integer.MAX_VALUE`，部分边界场景会溢出为负数导致窗口计算错误。

**设计方案**

```java
private int __tcp_win_from_space(int scaling_ratio, int space) {
    long scaled_space = (long) space * scaling_ratio;
    return (int) (scaled_space >> TCP_RMEM_TO_WIN_SCALE);
}
```

---

### 9. `tcp_current_mss` 每次都执行 Stream 计算

**问题位置**
`TcpOutput.java:535-539`

```java
int optLen = tcp_established_options()
        .stream()
        .mapToInt(TcpOption::length)
        .reduce(Integer::sum)
        .orElse(0);
```

`tcp_established_options()` 目前返回空列表，但仍然每次建立 Stream Pipeline，分配 `OptionalInt` 对象，在高频调用路径（每个 ACK/DATA 包都触发）上产生不必要的 GC。

**设计方案**

短期：用 `isEmpty()` 快速路径短路：

```java
List<TcpOption> opts = tcp_established_options();
int optLen = opts.isEmpty() ? 0 : opts.stream()
        .mapToInt(TcpOption::length).sum();
```

长期：缓存 `tcp_header_len`，选项不变时直接复用，避免每包重算。

---

### 10. `TcpTimer` 日志中反复创建 `SimpleDateFormat`

**问题位置**
`TcpTimer.java:379`

```java
new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date(...))
```

`SimpleDateFormat` 非线程安全且每次创建开销较大。

**设计方案**

使用 `java.time.format.DateTimeFormatter`（线程安全，可静态共享）：

```java
private static final DateTimeFormatter DATETIME_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                         .withZone(ZoneId.systemDefault());

// 使用
DATETIME_FMT.format(Instant.ofEpochMilli(System.currentTimeMillis() + l))
```

---

## P2 — 低优先级（架构改善，长期收益）

### 11. `sk_write_queue` / `tcp_rtx_queue` 使用 `ConcurrentLinkedQueue`

**问题位置**
`TcpSock.java:203-204`

```java
public ConcurrentLinkedQueue<TcpBuffer> sk_write_queue = new ConcurrentLinkedQueue<>();
public ConcurrentLinkedQueue<TcpBuffer> tcp_rtx_queue  = new ConcurrentLinkedQueue<>();
```

**根因**
每个 `TcpSock` 只被一个 Netty `EventLoop` 线程访问（TUN 读取和 childChannel 回调均在同一 EventLoop），无需线程安全容器。`ConcurrentLinkedQueue` 的 CAS 操作和 `volatile` 读是纯开销。

**设计方案**

改用 `ArrayDeque`：

```java
public final ArrayDeque<TcpBuffer> sk_write_queue = new ArrayDeque<>();
public final ArrayDeque<TcpBuffer> tcp_rtx_queue  = new ArrayDeque<>();
```

同时确认所有访问路径均在同一 EventLoop（可加 `assert channel.eventLoop().inEventLoop()`）。

---

### 12. `tcp_rcv_established` Fast Path 未启用

**问题位置**
`TcpInput.java:1467-1478`

```java
public void tcp_rcv_established(final TcpSock sk, final T ipPacket) throws IOException {
    // 整个 fast path 注释掉了，所有 ESTABLISHED 包走 tcp_rcv_state_process slow path
}
```

Linux 内核的 Fast Path 在确认包序列号连续、窗口正常、无特殊标志等条件下跳过大量 `tcp_validate_incoming`、`tcp_sequence` 等校验，直接进入 ACK 处理和数据队列。

**设计方案**

实现基础 Fast Path 判断：

```java
public void tcp_rcv_established(Channel net, TcpSock sk, T ipPacket) throws IOException {
    final TcpPacket tcp = ipPacket.get(TcpPacket.class);
    final TcpPacket.TcpHeader th = tcp.getHeader();

    // Fast path condition: pure ACK or in-order data, no unusual flags
    if (th.getAck() && !th.getRst() && !th.getSyn() && !th.getFin()
            && th.getSequenceNumber() == sk.rcv_nxt) {
        tcp_ack(net, sk, ipPacket, 0);
        if (tcp.length() - th.length() > 0) {
            tcp_data_queue(net, sk, ipPacket);
        }
        tcp_data_snd_check(net, sk);
        tcp_ack_snd_check(net, sk);
        return;
    }
    // Slow path
    tcp_rcv_state_process(net, sk, ipPacket);
}
```

---

### 13. `minmax_running_min` RTT 最小值追踪未实现

**问题位置**
`TcpInput.java:452`

```java
// FIXME
// minmax_running_min(rtt_min, wlen, tcp_jiffies32(), 0 != rtt_us ? rtt_us : jiffies_to_usecs(1));
```

RTT 最小值是 BBR、RACK-TLP 等现代拥塞控制算法的关键输入，目前缺失导致未来引入这些算法时缺少基础。

**设计方案**

实现 sliding window min filter（Kathey Nichols 算法），在 `TcpSock` 中增加 `rtt_min` 字段：

```java
// 简化版：只保留观察窗口内的最小值
public void updateRttMin(long rtt_us, long now_jiffies, int window_jiffies) {
    if (rtt_us < rtt_min || now_jiffies - rtt_min_stamp > window_jiffies) {
        rtt_min = rtt_us;
        rtt_min_stamp = now_jiffies;
    }
}
```

---

### 14. `tcp_init_transfer` 数据分片中的多次 `byte[]` 拷贝

**问题位置**
`TcpInput.java:1535-1565`

```java
final byte[] payload = ByteBufUtil.getBytes(buf); // 全量拷贝
for (int offset = 0; offset < payload.length; ) {
    ...
    UnknownPacket.newPacket(payload, offset, len); // 又一次子数组拷贝
    ...
}
```

每个 upstream 数据包触发至少 2 次内存拷贝（全量 + 分片），加上后续的 pcap4j 序列化。

**设计方案**

考虑引入「直接 ByteBuf 发送」路径，绕过 pcap4j 序列化：
1. 发送队列接受 `ByteBuf` 而非 `TcpBuffer.payloadBuilder`
2. 在 `__tcp_transmit_skb` 中组装 TCP/IP 头后，通过 `CompositeByteBuf` 将头部和 payload 拼接成一个逻辑包
3. 直接写入 TUN Channel

这是一个较大的重构，建议作为独立迭代。

---

## 优化优先级汇总

| # | 问题 | 优先级 | 影响面 | 改动量 |
|---|------|--------|--------|--------|
| 1 | TcpBuffer 反复序列化计算长度 | P0 | 吞吐量 | 小 |
| 2 | 全局定时器 Map | P0 | 高连接数稳定性 | 中 |
| 3 | tcp_sendmsg2 synchronized | P0 | 并发吞吐 | 小 |
| 4 | consume 双重数据拷贝 | P0 | 吞吐量/GC | 中 |
| 5 | 乱序包队列未实现 | P0 | 弱网吞吐 | 大 |
| 6 | tcp_under_memory_pressure 硬编码 true | P1 | 接收窗口 | 小 |
| 7 | tcp_full_space 硬编码接收缓冲 | P1 | 流量控制 | 中 |
| 8 | tcp_win_from_space 溢出 | P1 | 正确性 | 小 |
| 9 | tcp_current_mss 冗余 Stream | P1 | CPU | 小 |
| 10 | SimpleDateFormat 每次新建 | P1 | GC | 小 |
| 11 | ConcurrentLinkedQueue 多余锁 | P2 | CPU | 小 |
| 12 | Fast Path 未启用 | P2 | 吞吐量 | 中 |
| 13 | RTT 最小值未追踪 | P2 | 拥塞控制 | 中 |
| 14 | 分片多次 byte[] 拷贝 | P2 | 吞吐量/GC | 大 |
