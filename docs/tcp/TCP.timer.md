# TCP Timer 重构方案

> 分析基准：`TcpTimer.java`（778 行）  
> 编写日期：2026-04-08  
> 目标：高性能、正确语义、可维护

---

## 一、现状问题分析

### 1.1 全局 `ConcurrentMap<Runnable, Future<?>>` 是核心问题

```java
// TcpTimer.java:132
private final ConcurrentMap<Runnable, Future<?>> timers = Maps.newConcurrentMap();
```

| 问题 | 影响 |
|------|------|
| **ConcurrentMap 是多余的** | 所有 TCP 操作都在同一个 EventLoop 线程中执行，根本不需要并发安全的 Map |
| **Map 无界增长** | 已完成的 Future 如未被 `sk_stop_timer` 移除，条目永久残留 |
| **key 语义模糊** | 用 `Runnable` lambda 实例作 key，依赖对象引用相等，语义不直观 |
| **无法高效批量清理** | 连接关闭时需遍历整个 Map 才能确定哪些是本连接的定时器 |
| **SYN-ACK 定时器有孤儿条目风险** | `scheduleReqskTimer` 每次重传都创建新 `Runnable`，旧 key 已通过 `sk_stop_timer` 移除，但实现路径与 `__mod_timer` 不一致 |

### 1.2 多种定时器类型共用一个 Runnable key

```java
// 四种 timer 类型（RETRANS / PROBE0 / LOSS_PROBE / REO_TIMEOUT）
// 全部用 tp.icsk_retransmit_timer 同一个 Runnable 作 key
sk_reset_timer(tp, tp.icsk_retransmit_timer, when);
```

- 同一时刻只能有一个处于活跃状态（用 `icsk_pending` 区分）
- 定时器类型和 Map key 之间没有显式绑定关系，出 bug 时极难追踪

### 1.3 `cancel(true)` 可能中断正在执行的 callback

```java
// __mod_timer: cancel(true) — 中断语义
future.cancel(true);

// sk_stop_timer: cancel(false) — 不中断
future.cancel(false);
```

callback 在 EventLoop 线程中运行，`cancel(true)` 会设置线程中断标志，可能影响 Netty 内部状态。应统一使用 `cancel(false)`。

### 1.4 负延迟未防护

```java
final long delay = expires - TcpClock.jiffies();
// delay 可能 <= 0（超时已过），直接传入 schedule()
timers.put(timer, schedule(tp.child.channel(), delay, TimeUnit.MILLISECONDS, timer));
```

`EventLoop.schedule(delay <= 0)` 在 Netty 中会立即执行，行为正确但没有日志，调试困难。

### 1.5 机制与业务逻辑混合在一个 778 行的类中

`TcpTimer` 同时承担：
- 定时器调度机制（`__mod_timer`、`sk_reset_timer`、`sk_stop_timer`）
- 延迟 ACK 业务逻辑（`tcp_delack_timer_handler`）
- 重传业务逻辑（`tcp_retransmit_timer`、`tcp_write_timeout`）
- 零窗口探测逻辑（`tcp_probe_timer`）
- Keep-Alive 逻辑（`tcp_keepalive_timer`）
- SYN-ACK 重传逻辑（`reqsk_timer_handler`）

---

## 二、目标设计

### 2.1 核心方向

1. **消除全局 Map**：将 `ScheduledFuture` 直接存在每个连接的 timer slot 中
2. **类型安全**：用枚举 `TimerType` 替代 int 常量，每种类型占用独立 slot
3. **分离机制与业务**：`TcpTimerManager`（调度机制）+ 各 handler 类（业务逻辑）
4. **两级调度**：高频短延迟定时器（DelAck）直接用 EventLoop，低频长延迟定时器（RTO、Keep-Alive）用 `HashedWheelTimer`

### 2.2 调度策略对比

| 定时器类型 | 典型延迟 | 触发频率 | 精度要求 | 推荐调度器 |
|----------|---------|---------|---------|-----------|
| Delayed ACK (`DACK`) | 40–200ms | 极高（每个数据包） | 中 | EventLoop.schedule |
| Loss Probe (`LOSS_PROBE`) | 2×SRTT ≈ 1–100ms | 高 | 中 | EventLoop.schedule |
| 重传 (`RETRANS`) | RTO ≥ 200ms | 中 | 低 | HashedWheelTimer |
| 零窗口探测 (`PROBE0`) | 500ms–2min | 低 | 低 | HashedWheelTimer |
| Keep-Alive | 75s | 极低 | 极低 | HashedWheelTimer |
| SYN-ACK 重传 | 1–64s | 低 | 低 | HashedWheelTimer |

**原则**：DelAck 和 TLP 对延迟敏感，直接用 EventLoop；RTO 及以上对精度不敏感，用 HashedWheelTimer 集中管理，降低 EventLoop 任务队列压力。

---

## 三、新设计

### 3.1 `TimerType` 枚举

```java
public enum TimerType {
    /** 重传 / TLP / ZWP / REO — 共享 write_timer slot（同时只有一个） */
    RETRANS,
    LOSS_PROBE,
    PROBE0,
    REO_TIMEOUT,
    /** 延迟 ACK — 独立 slot */
    DACK,
    /** Keep-Alive — 独立 slot */
    KEEPALIVE;

    /** 是否使用 HashedWheelTimer（false = EventLoop.schedule） */
    public boolean useWheelTimer() {
        return this == RETRANS || this == PROBE0 || this == REO_TIMEOUT || this == KEEPALIVE;
    }
}
```

### 3.2 `TcpConnectionTimers`（per-connection timer slots）

```java
/**
 * 每个 TcpConnection 持有的 timer 状态。
 * 字段只在该 connection 绑定的 EventLoop 线程中访问，无需 volatile/synchronized。
 */
public final class TcpConnectionTimers {

    // write_timer 组（RETRANS / LOSS_PROBE / PROBE0 / REO_TIMEOUT 共用一个 slot）
    TimerHandle writeTimer;
    TimerType   writeTimerType;   // 当前 writeTimer 代表的类型，等价于 icsk_pending
    long        writeTimerExpires; // 绝对到期时间（jiffies），用于判断是否需要重新调度

    // delack timer（独立 slot）
    TimerHandle delackTimer;
    long        delackExpires;

    // keepalive timer（独立 slot）
    TimerHandle keepaliveTimer;

    /** 取消本连接所有定时器（连接关闭时调用） */
    public void cancelAll() {
        cancel(writeTimer);
        cancel(delackTimer);
        cancel(keepaliveTimer);
        writeTimer     = null;
        delackTimer    = null;
        keepaliveTimer = null;
        writeTimerType = null;
    }

    private static void cancel(TimerHandle h) {
        if (h != null) h.cancel();
    }
}
```

### 3.3 `TimerHandle`（统一抽象，屏蔽两种调度器差异）

```java
/**
 * 对 ScheduledFuture（EventLoop）和 Timeout（HashedWheelTimer）的统一抽象。
 */
public interface TimerHandle {
    /** 取消定时器，幂等，不抛异常 */
    void cancel();
    /** 是否已触发或已取消 */
    boolean isDone();

    static TimerHandle ofFuture(ScheduledFuture<?> f) {
        return new TimerHandle() {
            public void cancel()  { f.cancel(false); }
            public boolean isDone() { return f.isDone() || f.isCancelled(); }
        };
    }

    static TimerHandle ofTimeout(Timeout t) {
        return new TimerHandle() {
            public void cancel()  { t.cancel(); }
            public boolean isDone() { return t.isExpired() || t.isCancelled(); }
        };
    }
}
```

### 3.4 `TcpTimerManager`（调度机制，替代 TcpTimer 中的 Map 逻辑）

```java
/**
 * 定时器调度机制。不包含任何 TCP 业务逻辑。
 * 实例共享（全局单例或每个 TunAcceptor 一个），不持有连接状态。
 */
public class TcpTimerManager {

    /** 用于 RTO / Keep-Alive 等长时延、低精度定时器 */
    private final HashedWheelTimer wheelTimer;

    public TcpTimerManager() {
        // tickDuration=10ms, ticksPerWheel=1024 → 覆盖约 10s 的时间轮
        // TCP RTO 通常在 200ms–120s，10ms 精度完全够用
        this.wheelTimer = new HashedWheelTimer(
            new DefaultThreadFactory("tcp-timer-wheel"),
            10, TimeUnit.MILLISECONDS, 1024
        );
    }

    /**
     * 调度或重置 write_timer（RETRANS / LOSS_PROBE / PROBE0 / REO_TIMEOUT）。
     *
     * 只在 connection 绑定的 EventLoop 线程中调用。
     */
    public void scheduleWriteTimer(TcpConnection conn, TimerType type,
                                   long delayMs, Runnable action) {
        TcpConnectionTimers slots = conn.timers();

        // 取消旧定时器
        if (slots.writeTimer != null) {
            slots.writeTimer.cancel();
        }

        long delayActual = Math.max(delayMs, 1); // 防止负延迟
        slots.writeTimerType    = type;
        slots.writeTimerExpires = TcpClock.jiffies() + TcpClock.msecs_to_jiffies(delayActual);
        slots.writeTimer        = schedule(conn, type, delayActual, action);
    }

    /**
     * 调度或重置 delack timer。直接用 EventLoop（低延迟优先）。
     */
    public void scheduleDelAck(TcpConnection conn, long delayMs, Runnable action) {
        TcpConnectionTimers slots = conn.timers();
        if (slots.delackTimer != null) slots.delackTimer.cancel();
        long delayActual = Math.max(delayMs, 1);
        slots.delackExpires = TcpClock.jiffies() + TcpClock.msecs_to_jiffies(delayActual);
        ScheduledFuture<?> f = conn.eventLoop().schedule(action, delayActual, MILLISECONDS);
        slots.delackTimer = TimerHandle.ofFuture(f);
    }

    /**
     * 取消 write_timer。
     */
    public void cancelWriteTimer(TcpConnection conn) {
        TcpConnectionTimers slots = conn.timers();
        if (slots.writeTimer != null) {
            slots.writeTimer.cancel();
            slots.writeTimer     = null;
            slots.writeTimerType = null;
        }
    }

    /**
     * 取消 delack timer。
     */
    public void cancelDelAck(TcpConnection conn) {
        TcpConnectionTimers slots = conn.timers();
        if (slots.delackTimer != null) {
            slots.delackTimer.cancel();
            slots.delackTimer = null;
        }
    }

    /**
     * 连接关闭时取消该连接所有定时器，O(1)。
     */
    public void cancelAll(TcpConnection conn) {
        conn.timers().cancelAll();
    }

    // ── 内部调度 ──────────────────────────────────────────────

    private TimerHandle schedule(TcpConnection conn, TimerType type,
                                 long delayMs, Runnable action) {
        if (type.useWheelTimer()) {
            // HashedWheelTimer callback 在 wheel 线程，需 dispatch 回 EventLoop
            Timeout t = wheelTimer.newTimeout(
                timeout -> conn.eventLoop().execute(action),
                delayMs, MILLISECONDS
            );
            return TimerHandle.ofTimeout(t);
        } else {
            // EventLoop 直接调度
            ScheduledFuture<?> f = conn.eventLoop().schedule(action, delayMs, MILLISECONDS);
            return TimerHandle.ofFuture(f);
        }
    }

    public void shutdown() {
        wheelTimer.stop();
    }
}
```

### 3.5 Timer Handler 按职责拆分

从 `TcpTimer` 中剥离业务逻辑，分布到对应的处理类中：

| Handler 类 | 来自 TcpTimer 的方法 | 归属模块 |
|-----------|---------------------|---------|
| `TcpRetransmitHandler` | `tcp_retransmit_timer`, `tcp_write_timeout`, `retransmits_timed_out`, `tcp_model_timeout` | `core/` |
| `TcpDelAckHandler` | `tcp_delack_timer_handler` | `core/` |
| `TcpProbeHandler` | `tcp_probe_timer` | `core/` |
| `TcpKeepAliveHandler` | `tcp_keepalive_timer`, `keepalive_*` | `ext/keepalive/` |
| `TcpReqskHandler` | `reqsk_timer_handler`, `scheduleReqskTimer` | `handshake/` |

重构后 `TcpTimerManager` 只负责调度机制，不包含任何业务逻辑，约 **120 行**。

---

## 四、关键场景实现对比

### 4.1 调度重传定时器

**现在**：
```java
// TcpTimer.__mod_timer
timers.put(timer, schedule(tp.child.channel(), delay, TimeUnit.MILLISECONDS, timer));
// timer 是 lambda，通过引用相等作为 key
```

**重构后**：
```java
// TcpRetransmitHandler
timerManager.scheduleWriteTimer(
    conn, TimerType.RETRANS, rtoMs,
    () -> conn.eventLoop().execute(() -> retransmitTimerFired(conn))
);
```

### 4.2 连接关闭清理

**现在**：
```java
// inet_csk_clear_xmit_timers：遍历 Map 手动 cancel 三个 Runnable
sk_stop_timer(tp.icsk_retransmit_timer);
sk_stop_timer(tp.icsk_delack_timer);
sk_stop_timer(tp.sk_timer);
```

**重构后**：
```java
// O(1)，3次 cancel()
timerManager.cancelAll(conn);
```

### 4.3 重置定时器（mod_timer 语义）

**现在**：
```java
// 先 get，cancel，再 put —— 有竞态风险（虽然单线程实际不触发）
final Future<?> future = timers.get(timer);
if (null != future && !future.isDone() && !future.isCancelled()) {
    future.cancel(true);  // ← cancel(true) 有中断风险
}
timers.put(timer, schedule(...));
```

**重构后**：
```java
// scheduleWriteTimer 内部：先 cancel slot，再写入新 handle
// 单线程语义天然安全，无竞态，cancel(false) 语义
if (slots.writeTimer != null) slots.writeTimer.cancel(); // cancel(false)
slots.writeTimer = schedule(...);
```

---

## 五、HashedWheelTimer 参数选择

```
tickDuration  = 10ms
ticksPerWheel = 1024
覆盖范围      = 10ms × 1024 = 10.24s
```

- TCP RTO 最小 200ms（`TCP_RTO_MIN`），10ms 精度偏差 ≤5%，完全可接受
- Keep-Alive 75s：超过一圈会回绕，Netty `HashedWheelTimer` 内部用 `remainingRounds` 处理，无问题
- 1024 个 slot，每 slot 管理一个链表，O(1) 插入/删除

对于代理场景，并发连接数通常在 1000–10000 量级：
- 10000 个连接，每连接 2 个定时器 = 20000 个 Timer 节点
- HashedWheelTimer 每 tick 遍历 1 个 slot（平均 20000/1024 ≈ 20 个节点），开销极低

---

## 六、迁移步骤

1. **新增 `TimerType` 枚举、`TimerHandle` 接口、`TcpConnectionTimers` 类**（纯新增，不破坏现有代码）

2. **新增 `TcpTimerManager`**，内部用 `HashedWheelTimer` + per-connection slots，与旧 `TcpTimer` 并存

3. **逐定时器类型迁移**（每次迁移一个 timer，验证后合并）：
   - 先迁移 `DACK`（改动范围最小）
   - 再迁移 `RETRANS`
   - 再迁移 `LOSS_PROBE`（配合 TLP 实现，见 TCP.TLP.TODO.md）
   - 最后迁移 `KEEPALIVE` 和 `REQSK`

4. **删除旧 `ConcurrentMap<Runnable, Future<?>> timers`** 及相关方法

5. **将业务 handler 方法按表格迁移到对应类**，`TcpTimer` 最终只剩 `TcpTimerManager`

---

## 七、改动量预估

| 变更 | 新增/删除 | 估计行数 |
|------|---------|---------|
| `TimerType` 枚举 | 新增 | 30 |
| `TimerHandle` 接口 | 新增 | 30 |
| `TcpConnectionTimers` | 新增 | 60 |
| `TcpTimerManager` | 新增 | 120 |
| `TcpRetransmitHandler` | 从 TcpTimer 迁移 | 150 |
| `TcpDelAckHandler` | 从 TcpTimer 迁移 | 60 |
| `TcpProbeHandler` | 从 TcpTimer 迁移 | 60 |
| `TcpKeepAliveHandler` | 从 TcpTimer 迁移 | 100 |
| `TcpTimer`（原文件）| 删除机制代码 | -400 |

净增约 +210 行，换来：消除全局 Map、O(1) 连接清理、正确的 cancel 语义、机制与业务分离。
