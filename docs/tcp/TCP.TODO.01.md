# TCP 协议栈性能与稳定性改造清单

> 分析范围：`com.github.pangolin.routing.acceptor.tun`  
> 分析日期：2026-04-07  
> 依据：代码审查 + TCP.REVIEW.md

---

## 一、稳定性问题

### S1. `childCloseListener` 阶段不区分 + `pkt` 生命周期过长

**文件**：`TcpHandshaker.java:110-121,141-142`

```java
// L110: 定义时混用了两种场景
req.childCloseListener = future -> {
    rsk_ops.send_reset(net, parent, pkt, -100);  // pkt 是 SYN 包，握手后早应释放
    multiplexer.inet_csk_destroy_sock(req);
};

// L141-142: 注册时机
af_ops.send_synack(net, parent, req, pkt);
future.channel().closeFuture().addListener(req.childCloseListener);
// ↑ 若后端在 addListener 前已关闭，Netty 会立即触发 listener
```

**说明**：代码中 `FIXME conflict and child close listener send two RESET`（L144）是历史遗留——
`childCloseListener` 仅在 `isSuccess()` 分支注册（L142），连接失败路径（`else` L148）互斥，
**当前实际不会发送两次 RESET**。

真正的问题有两点：

1. **`childCloseListener` 不区分阶段**：后端在握手期间断开（SYN-ACK 已发、ACK 未到）
   和在已建立连接后断开，触发的是同一个 listener，处理逻辑混在一起，缺乏状态判断。

2. **`pkt`（SYN 包）生命周期过长**：`pkt` 在 `childCloseListener` 中被引用，
   导致它必须存活到后端 channel 关闭才能释放。正常情况下 SYN 包在握手完成后即可释放，
   当前实现使其生命周期与后端连接绑定，增加内存压力。

**行动**：
- 在 `childCloseListener` 中加入阶段判断（握手中 vs 已建立），分别处理
- 握手完成后将 `pkt` 引用置空，由握手流程负责释放，不再由 listener 持有

**改动量**：小

---

### S3. 定时器 Map 潜在内存残留

**文件**：`TcpTimer.java:132`

```java
private final ConcurrentMap<Runnable, Future<?>> timers = Maps.newConcurrentMap();
```

**问题**：每个 `TcpSock` 的 `Runnable` key 是独立 lambda 实例，key 不冲突。
但异常关闭的连接若未经过 `sk_stop_timer` → `timers.remove()`，
已完成的 `Future` 会残留在 map 中，间接持有 `TcpSock` 引用，长时间运行下形成慢泄漏。

**行动**：在 `inet_csk_destroy_sock` 中确保调用 `inet_csk_clear_xmit_timers`，
覆盖所有异常关闭路径；或在 `sk_reset_timer` 中清理已完成的旧条目。

**改动量**：小（审查清理路径完整性）

---

### S4. `TcpMultiplexHandler` 每实例创建线程池

**文件**：`TcpMultiplexHandler.java:27`

```java
private final EventLoopGroup childGroup = new NioEventLoopGroup();  // 默认 2×CPU 线程
```

**问题**：若多 TUN 接口或热重载导致多次实例化，每次都创建新线程池且未 `shutdown()`，
线程数爆炸。单实例场景下不触发，属于隐患。

**行动**：`childGroup` 改为外部注入（构造参数传入），由上层统一管理生命周期。

**改动量**：小

---

### S5. `pkt.retain()` 异步释放路径

**文件**：`TcpHandshaker.java:125,152`

```java
pkt.retain();                               // 主线程
req.child = socketChannelFactory.open(...)
    .addListener(future -> {
        try { ... } finally {
            pkt.release();                  // 异步 finally
        }
    });
```

**问题**：若 `socketChannelFactory.open()` 在添加 listener 之前抛出异常，
`retain()` 后无对应 `release()`，造成 ByteBuf 泄漏。

**行动**：用 try-catch 包裹 `open()` 调用，异常时在 catch 中调用 `pkt.release()`。

**改动量**：小

---

## 二、性能问题

### P1. TLP 空实现

**文件**：`TcpOutput.java:738,1395`；`TcpInput.java:1194`

尾部丢包恢复依赖 RTO 超时（200ms+），而非 TLP 的 2×SRTT（通常几毫秒到几十毫秒）。
详见 `docs/tcp/TCP.TLP.TODO.md`。

---

### P2. OFO 队列比较器溢出风险（REVIEW.md § M8）

**文件**：`TcpSock.java:251`

```java
new TreeMap<>((a, b) -> a - b)  // 序列号相减在距离超过 2^31 时溢出
```

**行动**：改为 `Integer.compare(after(a, b) ? 1 : (a == b ? 0 : -1), 0)` 或使用安全比较。

**改动量**：1 行

---

## 三、优先级排序

| 优先级 | 编号 | 问题 | 文件:行 | 改动量 |
|--------|------|------|---------|--------|
| **P0** | S1 | 双 RESET 幂等保护 | `TcpHandshaker.java:116,144` | 小 |
| **P1** | P1 | TLP 实现（调度 + 执行 + ACK 确认） | 见 `TCP.TLP.TODO.md` | ~90 行 |
| **P1** | S2 | 定时器 map 清理路径审查 | `TcpTimer.java:132` | 小 |
| **P2** | P2 | OFO TreeMap 比较器 | `TcpSock.java:251` | 1 行 |
| **P3** | S3 | 线程池生命周期外部注入 | `TcpMultiplexHandler.java:27` | 小 |
| **P3** | S4 | `pkt.retain()` 异常路径保护 | `TcpHandshaker.java:125` | 小 |

---

## 四、建议实施顺序

1. **S1 双 RESET 保护**，加 `AtomicBoolean`，逻辑简单且风险高
2. **P2 OFO 比较器**，1 行，零风险
3. **P1 TLP 实现**，按 `TCP.TLP.TODO.md` 分步实施
4. **S2/S3/S4** 在日常迭代中顺手修复
