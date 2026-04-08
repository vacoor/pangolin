# TCP Timer 重构方案

> 分析基准：`TcpTimer.java`（778 行）  
> 编写日期：2026-04-08  
> 目标：正确语义、可维护、消除全局 Map

---

## 一、为什么全部使用 EventLoop.schedule()

TCP timer 重构不需要引入 `HashedWheelTimer`，原因如下：

### 1.1 EventLoop 调度性能已经足够

Netty `EventLoop` 内部使用 `DefaultPriorityQueue`（最小堆），每个 `ScheduledFutureTask` 记录自己在堆中的下标，支持 O(log n) 的插入和取消。

对于 pangolin 的规模（代理路由器，并发连接通常数百到数千）：

```
5000 个连接 × 3 个定时器 = 15000 个堆节点
O(log 15000) ≈ 14 次比较 → 单次操作开销可忽略
```

### 1.2 HashedWheelTimer 反而引入额外跳转

`HashedWheelTimer` 的回调在独立的 wheel 线程触发，必须 dispatch 回 EventLoop：

```
HashedWheelTimer 线程触发
    └─► conn.eventLoop().execute(action)   ← 额外线程切换
            └─► 实际 TCP 处理
```

`EventLoop.schedule()` 则直接在同一线程触发，零额外开销，也不需要任何并发保护。

### 1.3 HashedWheelTimer 适合的场景

HashedWheelTimer 的 O(1) 优势在**百万级**连接、**极高频**重置时才有意义（如 IM 心跳）。  
Netty 自身的 `IdleStateHandler`、`ReadTimeoutHandler` 也全部使用 `EventLoop.schedule()`。

**结论：全部用 `EventLoop.schedule()`，更简单，性能足够。**

---

## 二、现状问题

### 2.1 全局 `ConcurrentMap<Runnable, Future<?>>` 是核心缺陷

```java
// TcpTimer.java:132
private final ConcurrentMap<Runnable, Future<?>> timers = Maps.newConcurrentMap();
```

| 问题 | 影响 |
|------|------|
| **ConcurrentMap 多余** | 所有 TCP 操作在同一 EventLoop 线程，不需要并发安全容器 |
| **Map 无界增长** | 已完成的 Future 若未被 `sk_stop_timer` 移除，条目永久残留 |
| **key 语义模糊** | 用 `Runnable` lambda 实例作 key，依赖对象引用相等，不直观 |
| **无法高效批量清理** | 连接关闭时无法 O(1) 取消该连接的所有定时器 |

### 2.2 多种定时器类型共用一个 Runnable key

```java
// RETRANS / PROBE0 / LOSS_PROBE / REO_TIMEOUT 四种类型
// 全部共用 tp.icsk_retransmit_timer 作为 Map key
sk_reset_timer(tp, tp.icsk_retransmit_timer, when);
```

用 `icsk_pending` int 值区分当前激活的类型——定时器类型与 key 之间没有显式绑定，调试困难。

### 2.3 `cancel(true)` 有中断风险

```java
// __mod_timer（L139）
future.cancel(true);   // ← 会设置 EventLoop 线程的中断标志

// sk_stop_timer（L117）
future.cancel(false);  // ← 正确
```

callback 运行在 EventLoop 线程，`cancel(true)` 可能干扰 Netty 内部 I/O 操作。应统一为 `cancel(false)`。

### 2.4 负延迟未防护

```java
final long delay = expires - TcpClock.jiffies();
// delay 可能 <= 0，直接传入 schedule()
```

Netty 对 `delay <= 0` 会立即执行，行为正确，但缺乏日志，调试时难以发现超时已过的情况。

### 2.5 机制与业务逻辑混合（778 行）

`TcpTimer` 同时承担调度机制和六类业务逻辑，见下节拆分方案。

---

## 三、新设计

### 3.1 核心思路

1. **消除全局 Map**：`ScheduledFuture` 直接存在 per-connection 的 slot 字段中
2. **类型安全**：`TimerType` 枚举替代 int 常量，每种类型对应一个独立 slot
3. **分离机制与业务**：`TcpTimerManager`（调度） + 各 handler 类（业务）
4. **统一使用 `EventLoop.schedule()`**：回调天然在同一线程，无并发问题

### 3.2 `TimerType` 枚举

```java
public enum TimerType {
    /**
     * write_timer 组：同一时刻只有一个激活（等价于 icsk_pending）。
     * RETRANS / LOSS_PROBE / PROBE0 / REO_TIMEOUT 共享一个 slot。
     */
    RETRANS,
    LOSS_PROBE,
    PROBE0,
    REO_TIMEOUT,

    /** 延迟 ACK，独立 slot */
    DACK,

    /** Keep-Alive，独立 slot */
    KEEPALIVE
}
```

### 3.3 `TcpConnectionTimers`（per-connection timer slots）

```java
/**
 * 每个 TcpConnection 持有的定时器状态。
 * 所有字段只在该 connection 绑定的 EventLoop 线程访问，无需任何同步。
 */
public final class TcpConnectionTimers {

    // write_timer 组（RETRANS / LOSS_PROBE / PROBE0 / REO_TIMEOUT 共用）
    ScheduledFuture<?> writeTimer;
    TimerType          writeTimerType;    // 当前激活类型（等价于 icsk_pending）
    long               writeTimerExpires; // 绝对到期时间（jiffies）

    // delack timer（独立 slot）
    ScheduledFuture<?> delackTimer;
    long               delackExpires;

    // keepalive timer（独立 slot）
    ScheduledFuture<?> keepaliveTimer;

    /** 取消本连接所有定时器，O(1)。连接关闭时调用。 */
    public void cancelAll() {
        cancel(writeTimer);
        cancel(delackTimer);
        cancel(keepaliveTimer);
        writeTimer     = null;
        delackTimer    = null;
        keepaliveTimer = null;
        writeTimerType = null;
    }

    private static void cancel(ScheduledFuture<?> f) {
        if (f != null && !f.isDone()) f.cancel(false);
    }
}
```

### 3.4 `TcpTimerManager`（调度机制，纯 EventLoop）

```java
/**
 * 定时器调度机制。不包含任何 TCP 业务逻辑。
 * 无状态，可多个 TcpConnection 共享同一实例。
 */
public final class TcpTimerManager {

    /**
     * 调度或重置 write_timer（RETRANS / LOSS_PROBE / PROBE0 / REO_TIMEOUT）。
     * 取消旧定时器后在 connection 的 EventLoop 上重新调度。
     * 只能在 connection 绑定的 EventLoop 线程调用。
     */
    public void scheduleWriteTimer(TcpConnection conn, TimerType type,
                                   long delayMs, Runnable action) {
        TcpConnectionTimers slots = conn.timers();

        // 取消旧定时器（O(log n) 从堆中移除）
        if (slots.writeTimer != null && !slots.writeTimer.isDone()) {
            slots.writeTimer.cancel(false);
        }

        long delay = Math.max(delayMs, 1); // 防止负延迟立即触发无日志
        slots.writeTimerType    = type;
        slots.writeTimerExpires = TcpClock.jiffies() + TcpClock.msecs_to_jiffies(delay);
        slots.writeTimer        = conn.eventLoop().schedule(action, delay, MILLISECONDS);
    }

    /**
     * 调度或重置 delack timer。
     */
    public void scheduleDelAck(TcpConnection conn, long delayMs, Runnable action) {
        TcpConnectionTimers slots = conn.timers();
        if (slots.delackTimer != null && !slots.delackTimer.isDone()) {
            slots.delackTimer.cancel(false);
        }
        long delay = Math.max(delayMs, 1);
        slots.delackExpires = TcpClock.jiffies() + TcpClock.msecs_to_jiffies(delay);
        slots.delackTimer   = conn.eventLoop().schedule(action, delay, MILLISECONDS);
    }

    /**
     * 调度或重置 keepalive timer。
     */
    public void scheduleKeepalive(TcpConnection conn, long delayMs, Runnable action) {
        TcpConnectionTimers slots = conn.timers();
        if (slots.keepaliveTimer != null && !slots.keepaliveTimer.isDone()) {
            slots.keepaliveTimer.cancel(false);
        }
        slots.keepaliveTimer = conn.eventLoop().schedule(action, Math.max(delayMs, 1), MILLISECONDS);
    }

    /** 取消 write_timer。 */
    public void cancelWriteTimer(TcpConnection conn) {
        TcpConnectionTimers slots = conn.timers();
        if (slots.writeTimer != null && !slots.writeTimer.isDone()) {
            slots.writeTimer.cancel(false);
        }
        slots.writeTimer     = null;
        slots.writeTimerType = null;
    }

    /** 取消 delack timer。 */
    public void cancelDelAck(TcpConnection conn) {
        TcpConnectionTimers slots = conn.timers();
        if (slots.delackTimer != null && !slots.delackTimer.isDone()) {
            slots.delackTimer.cancel(false);
        }
        slots.delackTimer = null;
    }

    /** 连接关闭：取消所有定时器，O(1)。 */
    public void cancelAll(TcpConnection conn) {
        conn.timers().cancelAll();
    }
}
```

### 3.5 Timer Handler 按职责拆分

从 `TcpTimer` 中剥离业务逻辑到对应处理类：

| Handler 类 | 来自 TcpTimer 的方法 | 归属模块 |
|-----------|---------------------|---------|
| `TcpRetransmitHandler` | `tcp_retransmit_timer`, `tcp_write_timeout`, `retransmits_timed_out`, `tcp_model_timeout`, `tcp_update_rto_stats` | `core/` |
| `TcpDelAckHandler` | `tcp_delack_timer_handler`, `tcp_delack_timer` | `core/` |
| `TcpProbeHandler` | `tcp_probe_timer`, `tcp_rtx_probe0_timed_out` | `core/` |
| `TcpKeepAliveHandler` | `tcp_keepalive_timer`, `keepalive_probes/time_when/intvl_when/time_elapsed` | `ext/keepalive/` |
| `TcpReqskHandler` | `reqsk_timer_handler`, `scheduleReqskTimer`, `inet_csk_reqsk_queue_drop` | `handshake/` |

重构后 `TcpTimerManager` 只负责调度机制，**约 80 行**，无任何业务逻辑。

---

## 四、关键场景对比

### 4.1 调度重传定时器

**现在**：
```java
// 全局 Map，Runnable 作 key
final Future<?> future = timers.get(timer);
if (null != future && !future.isDone() && !future.isCancelled()) {
    future.cancel(true);                                    // ← cancel(true) 有风险
}
timers.put(timer, schedule(tp.child.channel(), delay, MILLISECONDS, timer));
```

**重构后**：
```java
// per-connection slot，直接操作
timerManager.scheduleWriteTimer(conn, TimerType.RETRANS, rtoMs,
    () -> retransmitHandler.onTimeout(conn));
```

### 4.2 连接关闭清理

**现在**：
```java
// 三次 Map 查找 + remove
sk_stop_timer(tp.icsk_retransmit_timer);
sk_stop_timer(tp.icsk_delack_timer);
sk_stop_timer(tp.sk_timer);
```

**重构后**：
```java
// 直接取消三个 slot，O(1)
timerManager.cancelAll(conn);
// 或由 TcpConnection.close() 内部调用 timers.cancelAll()
```

### 4.3 判断当前激活的 write_timer 类型

**现在**：
```java
// 读取 icsk_pending int 常量，与 Map key 无关联
int event = tp.icsk_pending;
switch (event) {
    case ICSK_TIME_LOSS_PROBE: ...
    case ICSK_TIME_RETRANS: ...
```

**重构后**：
```java
// 枚举直接表达语义
switch (conn.timers().writeTimerType) {
    case LOSS_PROBE: ...
    case RETRANS: ...
```

---

## 五、改动量预估

| 变更 | 新增/删除 | 估计行数 |
|------|---------|---------|
| `TimerType` 枚举 | 新增 | 25 |
| `TcpConnectionTimers` | 新增 | 50 |
| `TcpTimerManager` | 新增 | 80 |
| `TcpRetransmitHandler` | 从 TcpTimer 迁移 | 150 |
| `TcpDelAckHandler` | 从 TcpTimer 迁移 | 60 |
| `TcpProbeHandler` | 从 TcpTimer 迁移 | 70 |
| `TcpKeepAliveHandler` | 从 TcpTimer 迁移 | 100 |
| `TcpReqskHandler` | 从 TcpTimer 迁移 | 80 |
| `TcpTimer`（原文件） | 删除调度机制代码 | -350 |

净增约 **+265 行**，换来：
- 消除全局 `ConcurrentMap`
- O(1) 连接关闭清理
- `cancel(false)` 统一语义
- 机制与业务彻底分离
- `TimerType` 枚举替代魔术 int 常量
