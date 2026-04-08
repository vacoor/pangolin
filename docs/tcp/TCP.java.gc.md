# Java GC 对 TCP 协议栈的影响与优化

> 文档版本：v2.0 | 更新日期：2026-04-08
> 适用项目：pangolin（TUN 层用户态 TCP 协议栈，Java / Netty 实现）

---

## 1. 问题背景

pangolin TCP 协议栈运行在 JVM 上。所有计时逻辑（RTT 估算、定时器触发、PAWS 时间戳）均依赖 `System.nanoTime()`。JVM GC 的 STW（Stop-The-World）暂停会冻结所有线程，造成：

- 时钟冻结与单次跳变
- RTT 采样虚增 → RTO 膨胀
- 定时器触发延迟

同时，发送路径上存在多处高频堆分配，在高吞吐下引发 Young GC 频率正反馈，进一步加剧上述问题。

---

## 2. GC 对协议栈的影响分析

### 2.1 STW 暂停导致 RTT 采样虚增（最严重）

```
System.nanoTime()  ─────────────冻结──────────────▶ 跳变
                     ← GC STW pause (e.g. 50 ms) →
```

`TcpClock.tcp_clock_ns()` 直接调用 `System.nanoTime()`。STW 期间时钟冻结，暂停结束后时钟单次跳变，导致：

| 效应 | 说明 |
|------|------|
| RTT 样本污染 | `measured_rtt_us` = 真实网络延迟 + GC 暂停时长 |
| SRTT / RTTVAR 持续偏高 | RFC 6298 EWMA 被异常大值拉偏后收敛缓慢 |
| RTO 膨胀 | 10 ms 连接在 50 ms GC 后 RTO 可能达 200 ms+ |

同样受影响的计时字段：`TcpSock.tcp_mstamp`（`TcpSock.java:172`）以及拥塞控制中所有基于时间的判断。

### 2.2 定时器触发延迟

`TcpTimer` 通过 `channel.eventLoop().schedule()` 注册定时器。Netty EventLoop 线程在 STW 期间同样冻结，暂停结束后才能触发已到期任务。

| 定时器 | 正常间隔 | GC 影响 |
|--------|---------|---------|
| 延迟 ACK | ~40 ms | 对端误判 ACK 丢失，触发不必要重传（最敏感） |
| 重传定时器 | RTO（通常 ≥200 ms）| 重传偏晚，保守但不致命 |
| Keepalive | 75 s | 影响可忽略 |

延迟 ACK 最敏感：20–50 ms 的 GC 暂停即可触发对端误判。

### 2.3 高频堆分配引发 GC 正反馈

发送/接收路径的每包堆分配热点：

| 分配点 | 位置 | 类型 | 频率 |
|--------|------|------|------|
| `Unpooled.buffer(ipTotalLen)` | `Tcp4Demultiplexer.java:377` | 非池化 `byte[]`（~1500 B） | 每个出包 |
| `Unpooled.buffer(12)` + `new byte[12]` | `TcpOutput.java:341` | TCP Options 双重分配 | 每个出包 |
| `new TcpBuffer()` | `TcpOutput.java:268, 1179` | 轻量对象 | 每个出包 |
| `Unpooled.buffer(24)` + `new byte[N]` | `TcpOutput.java:294` | SYN-ACK Options | 每次握手 |
| `new OfoEntry()` + `TreeMap.Entry` | `TcpInput.java:1555` | 乱序包对象 | 每个乱序包 |
| lambda 闭包（捕获 TcpSock + Channel） | `TcpTimer.java:81` | 闭包对象 | 每条连接建立 |

正反馈路径：高吞吐 → 频繁 Young GC → 偶发 Mixed / Full GC → STW 时长增加 → 影响加剧。

**已正确规避**：包载荷使用 Netty `ByteBuf` 引用计数池化分配，不经过 GC。

### 2.4 PAWS：不受 GC 影响

`TSval = tcp_clock_us() + tsoffset`。STW 结束后 `nanoTime()` 单调递增地跳变，TSval 依然严格单调递增，不会触发 PAWS 误判。

---

## 3. 优化方案

### 3.1 JVM 配置（零代码改动，立即见效）

所有计时问题均源于 STW 暂停时长。减少 STW 是最直接的全局改善手段。

**首选：ZGC（JDK 15+），STW < 1 ms**

```bash
-XX:+UseZGC
-XX:ZCollectionInterval=5   # 至少每 5s 触发一次 GC，防止垃圾积累导致单次 GC 时间增长
-Xms2g -Xmx2g               # 固定堆大小，消除因堆扩展触发的额外 Full GC
```

> JDK 21+ 可启用 Generational ZGC（`-XX:+ZGenerational`），在保持亚毫秒 STW 的同时显著降低堆占用。

**备选：G1GC（JDK 11+）**

```bash
-XX:+UseG1GC
-XX:MaxGCPauseMillis=10
-XX:G1HeapRegionSize=4m
-XX:InitiatingHeapOccupancyPercent=45
```

> G1 Mixed GC 的实际 STW 仍可达数十毫秒，无法完全消除定时器抖动，仅作降级方案。

固定堆大小（`-Xms == -Xmx`）适用于所有 GC 配置，是通用基线建议。

---

### 3.2 消除 buildIp4Packet() 逐包堆分配（优先级最高）

**问题**：`Tcp4Demultiplexer.java:377`，每个出包执行：

```java
final ByteBuf buf = Unpooled.buffer(ipTotalLen);  // 非池化分配，每次 new byte[ipTotalLen]
```

`Unpooled.buffer()` 绕过 Netty 池化路径，直接在堆上分配。一个 1500 字节的包即一次 ~1500 字节堆分配，高吞吐下直接打满 Young GC。这是发送路径上**单点收益最大的改动**。

**修复**：

```java
// 改为池化分配；引用计数归零时自动归还池，完全不经过 GC
final ByteBuf buf = PooledByteBufAllocator.DEFAULT.buffer(ipTotalLen);
```

> **生命周期**：`buf` 写入 `TunChannel` 后由 Netty pipeline 负责 `release()`，调用方无需手动管理。若调用方需持有引用，须显式 `retain()` 并在不再使用时 `release()`。

> **规范建议**：热路径上禁止使用 `Unpooled.*` 分配 `ByteBuf`。代码审查时应将 `Unpooled.buffer()` / `Unpooled.copiedBuffer()` 视为需要评审的告警项，优先替换为 `PooledByteBufAllocator.DEFAULT` 或 `ctx.alloc()`。

---

### 3.3 消除 TCP Options 逐包冗余分配

**问题**：`TcpOutput.java:337–348`，`tcp_established_options()` 每个出包执行：

```java
ByteBuf optBuf = Unpooled.buffer(12);               // 非池化堆分配
byte[] bytes = new byte[optBuf.readableBytes()];    // 第二次分配
```

TCP Timestamps 选项结构固定（12 字节）；连接建立后只有 TSval / TSecr 数值变化，数组无需逐包重建。

**修复**：在 `TcpSock` 上缓存 `byte[]`，原地覆写 TSval / TSecr：

```java
// TcpSock.java — 按需延迟初始化，连接期间复用
public byte[] cachedEstablishedOptions;  // nullable; 12 bytes when timestamps enabled

// TcpOutput.java — tcp_established_options()
private byte[] tcp_established_options(TcpSock tp) {
    if (!tp.rx_opt.tstamp_ok) {
        return null;
    }
    if (tp.cachedEstablishedOptions == null) {
        tp.cachedEstablishedOptions = new byte[12];
    }
    long tsval = tcp_time_stamp(tp);
    long tsecr = tp.rx_opt.ts_recent;
    TcpOptionCodec.writeTimestampOption(tp.cachedEstablishedOptions, 0, tsval, tsecr);
    return tp.cachedEstablishedOptions;
}
```

> **线程安全**：`TcpSock` 的生命周期绑定于单一 Netty EventLoop，该字段无跨线程竞争，无需 `volatile`。
>
> **前置条件**：`TcpOptionCodec.writeTimestampOption()` 需增加接受 `byte[] dest, int offset` 参数的重载。

---

### 3.4 RTT 异常样本过滤

**问题**：GC 暂停时间被计入 RTT 样本，SRTT / RTTVAR 被持续拉高，RTO 无法自然回落。

**修复**：在 `tcp_rtt_estimator()` 入口过滤超阈值样本：

```java
// TcpInput.java — tcp_rtt_estimator() 入口
// measured_rtt_us: RTT 样本，单位微秒（µs）
// tp.srtt_us:      平滑 RTT，单位微秒（µs），RFC 6298 EWMA 维护

if (tp.srtt_us > 0 && measured_rtt_us > tp.srtt_us * 5) {
    // 单次样本超过 5×SRTT，大概率为 GC 引入的噪声，丢弃
    return;
}
```

> **阈值选择**：`5×SRTT` 在正常网络抖动范围（通常 ≤2×SRTT）与 GC 典型暂停（10–50 ms）之间留有足够余量，可根据实测调整。
>
> **初始化保护**：`srtt_us == 0`（连接初期）时不过滤，保证首批样本正常建立基线。
>
> **单位一致性**：`measured_rtt_us` 与 `srtt_us` 均为微秒，比较前无需转换。

---

### 3.5 TcpBuffer 对象池

**前置条件**：TODO#4（`tcp_write_queue_purge`）——连接关闭时清空发送队列，确保每个 `TcpBuffer` 有明确的生命周期终点。TODO#4 未完成前引入对象池存在 `rawPayload` 泄漏风险。

`TcpBuffer` 是轻量 Java 对象（不持有大块数据），Young GC 回收代价相对低。完成 §3.2 池化分配改造后，若分配分析仍显示 `TcpBuffer` 是分配热点，再使用 Netty `Recycler` 实现对象池：

```java
public class TcpBuffer {
    private static final Recycler<TcpBuffer> RECYCLER = new Recycler<>() {
        @Override
        protected TcpBuffer newObject(Handle<TcpBuffer> handle) {
            return new TcpBuffer(handle);
        }
    };

    private final Recycler.Handle<TcpBuffer> handle;

    private TcpBuffer(Recycler.Handle<TcpBuffer> handle) {
        this.handle = handle;
    }

    public static TcpBuffer acquire() {
        return RECYCLER.get();
    }

    /**
     * Returns this instance to the pool. Must be called exactly once per acquire().
     * Releases rawPayload and resets all fields before recycling to prevent stale data.
     */
    public void recycle() {
        release();   // rawPayload.release()
        reset();     // clear all fields
        handle.recycle(this);
    }
}
```

> **生命周期规范**：`acquire()` 与 `recycle()` 必须成对调用。`recycle()` 后禁止再访问该实例的任何字段。
>
> **迁移说明**：引入 Recycler 后，所有 `new TcpBuffer()` 调用点须改为 `TcpBuffer.acquire()`，并在不再使用时调用 `recycle()` 代替直接丢弃。

---

### 3.6 替换定时器后端为 HashedWheelTimer

**问题**：`TcpTimer.__mod_timer()` 每次触发均执行 cancel + reschedule，对 `ScheduledThreadPoolExecutor`（O(log n) 堆操作）产生频繁写压力；`timers` ConcurrentMap 的读写也存在并发竞争。

**修复**：

```java
// TcpTimer.java
// tickDuration=10ms：延迟 ACK 40ms、RTO ≥200ms，10ms 精度完全满足需求
private static final HashedWheelTimer WHEEL_TIMER =
        new HashedWheelTimer(10, TimeUnit.MILLISECONDS, 512);

private io.netty.util.Timeout scheduleTimeout(Channel channel, long delay,
                                               TimeUnit unit, Runnable task) {
    return WHEEL_TIMER.newTimeout(t -> channel.eventLoop().execute(task), delay, unit);
}
```

`HashedWheelTimer` 插入 / 取消均为 O(1)，适合大量短生命周期定时器的高并发场景。

> **生命周期管理**：`WHEEL_TIMER` 为静态实例，应在应用关闭时调用 `WHEEL_TIMER.stop()` 释放内部线程，否则 JVM 无法正常退出。
>
> **精度权衡**：tick 粒度 10 ms 意味着定时器最多延迟 10 ms 触发，对 TCP 协议行为无实质影响。

---

## 4. 排除方案与决策依据

本节记录已评估但不适用的优化方向，防止后续重复引入。

### 4.1 CompositeByteBuf 零拷贝 payload

**设想**：用 `CompositeByteBuf` 组合 IP/TCP 头与 payload，避免内存拷贝。

**排除原因**：`TunChannel.doWrite()` 实现如下：

```java
// TunChannel.java:138
device.write(((ByteBuf) msg).nioBuffer());
```

`nioBuffer()` 在 `CompositeByteBuf` 上会强制调用 `consolidate()`，将所有 component 合并为一个连续 `ByteBuffer`，零拷贝收益完全抵消，且额外引入合并开销。

根本约束：`TunChannel` 继承 `AbstractChannel`（非 `AbstractNioChannel`），未实现 `GatheringByteChannel`，无法利用 `writev(2)` 系统调用进行 scatter/gather 写。TUN fd 的单包写入语义决定了 payload 拷贝在应用层不可消除。

**结论**：在当前 `TunChannel` 实现下，`CompositeByteBuf` 无收益，不建议引入。如未来 `TunChannel` 改为实现 `GatheringByteChannel`，可重新评估。

### 4.2 TcpBuffer 直接包装 ByteBuf

**设想**：将 `TcpBuffer` 下沉为 `ByteBuf` 子类，消除包装对象开销。

**排除原因**：`TcpBuffer` 包含两类字段：

| 字段 | 是否进入 Wire | 说明 |
|------|-------------|------|
| `srcPort / dstPort / seqNo / ackNo / flags / window` | 是 | TCP/IP 头字段 |
| `rawOptions`（`byte[]`） | 是 | TCP Options |
| `rawPayload`（`ByteBuf`） | 是 | 载荷 |
| `tstamp`（`long`） | **否** | RTT 测量时间戳 |
| `skb_mstamp_ns`（`long`） | **否** | 发送时刻，用于拥塞控制 |
| `sacked`（`int`） | **否** | SACK / 重传状态标记 |

后三个字段是协议栈内部元数据，无对应 Wire 格式偏移，无法编码为 `ByteBuf` 偏移量。即使将报文字段写入 `ByteBuf`，仍需 Java 对象持有元数据。类比 Linux `sk_buff`：本质也是 C struct（含元数据）+ 指向数据区的指针，而非纯字节数组。

**结论**：正确优化方向是 §3.2 的池化分配（消除 `Unpooled` 调用），而非将 `TcpBuffer` 包装为 `ByteBuf`。

---

## 5. 实施优先级

| 优先级 | 优化项 | 涉及文件 | 难度 | 预期收益 |
|--------|--------|---------|------|---------|
| P0 | 换 ZGC / 调 G1 参数 | JVM 启动参数 | 低 | 立即降低 STW，所有计时问题同步缓解 |
| P1 | `buildIp4Packet()` 换池化分配 | `Tcp4Demultiplexer.java:377` | 低 | 消除每包最大堆分配（~1500 B），减压效果最确定 |
| P1 | 缓存 `cachedEstablishedOptions` | `TcpOutput.java`、`TcpSock.java`、`TcpOptionCodec.java` | 低 | 消除每包 Options 双重冗余分配 |
| P2 | RTT 异常样本过滤 | `TcpInput.java` | 低 | 防止 RTO 被 GC 污染，独立于其他改动，可先行落地 |
| P3 | `TcpBuffer` Recycler | `TcpBuffer.java` 及所有调用点 | 中 | 需先完成 TODO#4（write queue purge），优先级低于池化分配 |
| P3 | 替换 HashedWheelTimer | `TcpTimer.java` | 中 | 改善高并发下定时器调度效率 |
| — | CompositeByteBuf 零拷贝 | — | — | **已排除**（见 §4.1） |

**建议实施顺序**：P0 → P1（两项可并行）→ P2 → P3（两项可并行，Recycler 需等 TODO#4）。

---

## 6. 验证与度量

### 6.1 GC 日志

```bash
-Xlog:gc*:file=gc.log:time,uptime:filecount=5,filesize=20m
```

观察 STW 时长分布；ZGC 目标：P99 STW < 1 ms。

### 6.2 分配热点分析

使用 async-profiler 或 JFR 采集分配火焰图，对比优化前后 `buildIp4Packet()` 和 `tcp_established_options()` 的堆分配量：

```bash
# async-profiler（推荐）
./profiler.sh -e alloc -d 30 -f alloc.html <pid>
```

```bash
# JFR
-XX:StartFlightRecording=filename=recording.jfr,duration=60s,settings=profile
```

### 6.3 RTT 样本分布

在 `tcp_rtt_estimator()` 入口临时记录异常样本频率：

```java
// measured_rtt_us / tp.srtt_us 单位均为微秒（µs）
if (tp.srtt_us > 0 && measured_rtt_us > tp.srtt_us * 5) {
    log.debug("rtt_outlier seq={} measured_us={} srtt_us={}",
              tp.snd_nxt, measured_rtt_us, tp.srtt_us);
}
```

GC 优化后该日志应显著减少。

### 6.4 定时器触发精度

在 `tcp_delack_timer_handler` 记录实际触发时刻与预期触发时刻之差（单位 ms）。目标：P99 < 10 ms（HashedWheelTimer tick 粒度上限）。
