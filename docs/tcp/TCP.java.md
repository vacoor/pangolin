# TCP 协议栈 Java 重构方案

> 版本：v4.0 | 更新日期：2026-04-10
> 分析基准：branch `feature/v1.2.3-ai-lb`
> 目标：OOP 化、关注点分离、RFC9293 之外的扩展可插拔

---

## 更新日志

### v4.0 — 2026-04-10

**RFC 9293 TCP 协议栈实现完成**（`tun.net.v2.tcp` 包，共 38 个生产类 + 5 个测试类，54 个单元测试全部通过）

- §6 Phase 0 基础设施：`FourTuple`、`TcpConnectionChannel`（继承 `AbstractChannel`，实现 `config()` 抽象方法）、`TcpConnectionRegistry`、`TcpMultiplexHandler`（Worker 分发、引用计数、RST 回复）
- §7 RFC9293 核心：`TcpHandshakeHandler/TcpHandshaker/TcpHandshakerFactory`（3WH）、`TcpEstablishedHandler`（数据传输、延迟 ACK）、`TcpActiveCloseHandler`（FIN_WAIT_1/2 → TIME_WAIT）、`TcpPassiveCloseHandler`（CLOSE_WAIT → LAST_ACK）
- §7 算法层：`TcpSegmenter`（分段、cwnd 窗口控制）、`TcpAckProcessor`（ACK 推进、RTT 采样）、`TcpRetransmitter`（RTO 重传）、`TcpPacketBuilder`（IPv4+TCP 报文组装 + 校验和）
- §8 扩展层：`Rfc6298RttEstimator`（SRTT/RTTVAR/退避）、`NewRenoCongestionControl`（RFC 5681 含全部 v3.9 修正）、`TcpReceiveBuffer`（OFO 乱序重组）、`TcpSendBuffer`（RTX 队列）

**代码审查发现并修复 3 个 Bug**

- §8.1 `TcpHandshaker.sendSynAck()`：`synAckSent` 原在 `writeAndFlush()` 的异步 listener 中设置。`writeAndFlush()` 内部经 TUN EventLoop 完成，listener 回调时 Worker 上可能已处理了重传 SYN 或最终 ACK，导致 `finishHandshake()` 误判"ACK 在 SYN-ACK 之前"而丢弃握手。修复：`synAckSent = true` 改为在调用 `writeAndFlush()` 前同步设置。
- §7.1 `TcpHandshakeHandler.channelRead0()`：注释声明 "Fire the establishing packet into the new handler in case it carries data"，但实际缺少 `ctx.fireChannelRead(pkt)` 调用。RFC 9293 §3.4 允许最终 ACK 携带数据，缺少该调用导致数据静默丢弃。修复：`pipeline().replace()` 后补充 `ctx.fireChannelRead(pkt)`。
- §8.3 `TcpSegmenter.sendPending()`：内循环中 `payload = data.slice().retain()` 后调用 `sendSegment()`，`sendSegment()` 内部通过 `payload.retainedSlice()` 为 RTX 队列保留一份引用，但 `payload` 自身的 `retain()` 贡献始终未被释放，造成底层 `data` ByteBuf 引用计数永远不归零（内存泄漏）。修复：`sendSegment()` 调用后补充 `payload.release()`。

### v3.9 — 2026-04-09

- §6.2 `workers[]` 修复 `Integer.MIN_VALUE` 崩溃 bug：`Math.abs(Integer.MIN_VALUE)` 在 Java 中仍为负数，取模导致 `ArrayIndexOutOfBoundsException`；改为 `(hashCode & Integer.MAX_VALUE) % length`
- §6.2 `TcpConnectionChannel.metadata()` 改为静态常量，避免每次调用创建新对象
- §6.6 `TcpConnectionState` 补充 `SYN_SENT`（RFC 9293 第 11 个标准状态，Phase 4 重命名表中已有对应项但枚举漏列）
- §7.1 `RttEstimator.addSample()` 补充 Karn 算法说明：重传段不能用于 RTT 采样（RFC 6298 §4）；`rttSample()` 须对重传段返回哨兵值，实现层跳过采样
- §7.3 `TcpAckProcessor` 修复 `ackedSegs` 下溢：末包 `ackedBytes < mss` 时整除为 0，导致 `sndUnaAdvanced=true` 但 CC 不增长 cwnd；改为 `ackedBytes > 0 ? Math.max(1, ackedBytes/mss) : 0`
- §7.3 `NewRenoCongestionControl` 修复拥塞避免增长过快：原 `Math.max(1, ackedSegs/cwnd)` 每 ACK 至少 +1，与 RFC 5681 "每 RTT 增长约 1 SMSS" 不符；改用分数计数器 `caIncrCounter`（对标 Linux `snd_cwnd_cnt`）
- §7.3 `NewRenoCongestionControl` 修复 LOSS 状态不退出 bug：`onTimeout()` 进入 LOSS 后，`sndUnaAdvanced=true` 时缺少退出逻辑，导致 cwnd 永久卡在 1；修复：收到新 ACK 时退出 LOSS → OPEN，进入慢启动
- §7.4 `TimerType` 补充 `FIN_WAIT_2_TIMEOUT`：RFC 9293 §3.9.1 要求 FIN_WAIT_2 不可永久等待；Linux 通过 `tcp_fin_timeout` sysctl（默认 60s）实现强制关闭

### v3.8 — 2026-04-09

- §4.3 `TcpActiveCloseHandler` 补全 FIN_WAIT_1 三条转换路径：ACK → FIN_WAIT_2、FIN → CLOSING（同时关闭）、FIN+ACK → TIME_WAIT（同时关闭）；原 "FIN-ACK" 表述歧义消除
- §4.3 被动关闭机制补充：明确 `TcpPassiveCloseHandler` 通过覆盖 `ChannelDuplexHandler.close()` 拦截应用层关闭，延迟 Netty 实际关闭直至 LAST_ACK 收到对端 ACK
- §6.2 `regFuture.addListener` 修复线程安全 bug：listener 在 Worker EventLoop 执行时直接修改 registry 违反单写者约束，改为 `ctx.channel().eventLoop().execute()` 回投 TUN EventLoop
- §6.2 `fireChildRead` 增加 `isActive()` 前置检查：注册失败后 Worker 队列中残留的 `fireChildRead` 任务在无效 Channel 上执行时安全退出，引用释放由调用方 lambda finally 负责
- §6.2 `channelRead` 补充 `RejectedExecutionException` 处理：Worker 已关闭时 `execute()` 抛出异常，`pkt.retain()` 无法配对，catch 块补充 `pkt.release()` 防止引用计数泄漏

### v3.7 — 2026-04-09

- §6.2 `TcpMultiplexHandler.channelRead` 修复引用计数 bug：`pkt.retain()` 后必须在 TUN 线程配对 `pkt.release()`
- §6.2 `worker.register(connCh)` 处理失败路径：注册失败时从 registry 移除 + 发 RST
- §6.2 删除 `@ChannelHandler.Sharable`：`TcpMultiplexHandler` 持有 per-实例 `registry`，不能跨 TUN Channel 共享
- §7.3 `TcpAckProcessor.onAck()` 改为 `void`（原 `return 0` 无语义）
- §9 Builder 补全完整字段定义，明确 `congestionControl(cc, callback)` 两参数方法签名及 `build()` 内 `init()` 调用顺序

### v3.6 — 2026-04-09

- §6.2 `doRegister()` 修复：移除错误的 `pipeline().fireChannelRegistered()` / `fireChannelActive()` 调用（AbstractChannel 已处理，手动调用导致重复触发）
- §6.2 `doDeregister()` 修复：移除错误的 `pipeline().fireChannelInactive()` / `fireChannelUnregistered()` 调用（同上）
- §6.2 `TcpConnectionChannel` 构造函数新增 `deregisterCallback` 参数（替代直接引用 `registry`，解决 `registry` 不可访问问题）
- §6.2 `TcpMultiplexHandler.channelRead` 同步修正构造调用，传入 `() -> registry.remove(fourTuple)` 作为 callback
- §9 Builder 明确 `retransmitCallback` 注入方式：`.congestionControl(impl, retransmitter::retransmit)`，build() 内调用 `cc.init(conn, callback)`
- §6.4 新增"上游代理连接的 EventLoop 绑定"节：说明上游 Bootstrap 应复用 `conn.eventLoop()`（assignedWorker），消除跨线程 execute()，同时废除 S4 中的 `childGroup`

### v3.5 — 2026-04-09

- §6.4 重命名为"Worker EventLoop 分发"，删除选项1（单 EventLoop）和选项对比表，内容聚焦 Worker 分发方案
- §6.2 `TcpConnectionChannel` 构造函数改为必须传入 `assignedWorker`，`isCompatible()` 绑定 `assignedWorker`，`doClose()` 回投 TUN EventLoop 清理 registry
- §6.2 `TcpMultiplexHandler` 构造函数增加 `workerGroup` 参数，`channelRead` 改为 Worker 分发
- §6.5 已对齐设计表 EventLoop 归属行更新为 `assignedWorker`
- §9 Builder 注释去掉方案A引用
- §10 Phase 0 重写，明确采用方案B + Worker 分发

### v3.4 — 2026-04-09

- §5 包结构说明 `pipeline/` 为二选一，标注推荐方案 B
- §6.3 对比表后新增推荐说明（方案 B，含理由）
- §6.4 修正 `workerGroup.next(int)` 不存在的 API，改为 `workers[]` 数组取模，并补充构造函数示例
- §6.2 `TcpConnectionChannel.doClose()` 补充 `TcpConnection.close()` 与 `doClose()` 的调用关系说明
- §6.2 `TcpMultiplexHandler` 代码补充 `registry` 字段声明
- §6.6 借鉴点1 `TcpConnectionState` 补充 `LISTEN` / `SYN_RECEIVED` 不由 `TcpConnection` 持有的说明
- §6.6 借鉴点1 修正 `FIN_WAIT_2` 注释："FIN-ACK" → "对本端 FIN 的 ACK"

### v3.3 — 2026-04-09

- 新增 §6.6 HTTP/2 代码组织结构借鉴：6 个可落地的设计模式（`State` 行为方法、`LifecycleManager`、`Connection.Listener`、`FlowControlled.merge`、`Visitor`、Codec/Multiplexer 分层）

### v3.2 — 2026-04-09

- 新增 §6.5 `AbstractHttp2StreamChannel` 完整对比分析：已对齐项、5 个 Gap、以及各 Gap 在 TUN 场景的适用性判定

### v3.1 — 2026-04-09

- `injectRead()` 重命名为 `fireChildRead()`，遵循 Netty `fire*` 命名约定（对标 `AbstractHttp2StreamChannel.fireChildRead()`）
- 新增 §6.4 EventLoop 线程模型分析：HTTP/2 单线程类比在 TUN 场景的局限性，以及 Worker 分发方案（选项 3）
- 明确 `doWrite()` 中 `parentCtx.write()` 本身线程安全，Worker 线程调用无需额外包装

### v3.0 — 2026-04-08

- 引入 per-connection pipeline：每条四元组对应独立 pipeline，握手 / 传输 / 挥手三阶段通过 `pipeline.replace()` 切换
- `TcpStateMachine`（全状态 Template Method）拆解为 `TcpHandshakeHandler` / `TcpEstablishedHandler` / `TcpActiveCloseHandler` / `TcpPassiveCloseHandler`
- `HalfOpenRegistry` + `ConnectionRegistry` 合并为 `TcpConnectionRegistry`
- 新增 §6 Per-Connection Pipeline 两种实现方案（方案 A 自定义轻量 pipeline；方案 B 继承 `AbstractChannel`，对标 `AbstractHttp2StreamChannel`）
- `TcpMultiplexHandler(childHandler)` 对标 `Http2MultiplexHandler(childHandler)`

### v2.1 — 2026-04-09

| 类别 | 旧名 | 新名 |
|------|------|------|
| 类 | `TcpSegmentizer` | `TcpSegmenter` |
| 类 | `SysctlOptions` | `TcpConfig` |
| 方法 | `RttEstimator.update()` | `addSample()` |
| 方法 | `TcpTimestampExtension.pawsDiscard()` | `isPawsRejected()` |
| 方法 | `TcpConnection.attr(key)` | `getAttr(key)` / `setAttr(key, value)` |
| 方法 | `ConnectionKey.newKey()` | `ConnectionKey.of()` |
| 方法 | `TcpTimerScheduler.scheduleDelAck()` | `scheduleDelayedAck()` |
| 字段 | `TcpConnectionTimers.delackTimer` | `delayedAckTimer` |

### v2.0 — 2026-04-09

- 引入 `TcpConnection` 富域模型替代 `TcpSock` 贫血模型
- 引入可插拔扩展接口：`RttEstimator` / `CongestionControl` / `LossDetector` / `TcpTimestampExtension`
- `TcpTimer` 全局 `ConcurrentMap<Runnable, Future>` → per-conn `TcpConnectionTimers` slot
- `TcpTimerManager` → `TcpTimerScheduler`；`TimerType` 枚举值改为全拼
- `CA_OPEN/CA_RECOVERY/CA_LOSS` int 常量 → `CongestionState` enum
- `TcpConnectionState` 删除 `TCP_` 前缀，与 Java 命名约定对齐

---

## 1. 现状诊断

### 1.1 规模与职责

| 文件 | 行数 | 当前职责 |
|------|------|---------|
| `TcpInput.java` | 2321 | 接收路径全部逻辑：ACK 处理、RTT 采样、拥塞控制、数据入队、状态机 |
| `TcpOutput.java` | 1398 | 发送路径全部逻辑：分段、窗口、重传、探测、定时器 |
| `TcpTimer.java` | 778 | 定时器调度 + 所有定时器 handler |
| `TcpMultiplexer.java` | 705 | 连接分发 + SYN / ESTABLISHED 注册表 + 持有 Input / Output / Timer |
| `TcpSock.java` | 615 | 纯数据结构（贫血模型），50+ 个 public 字段 |
| `TcpHandshaker.java` | 317 | 三次握手状态机 |

### 1.2 核心问题

1. **God Class**：`TcpInput` 同时负责 RTT、拥塞控制、OFO 队列、状态机，每个关注点都没有独立边界
2. **贫血模型**：`TcpSock` 是纯 public 字段集合，没有行为封装，所有算法散落在 Input / Output 中直接修改字段
3. **RFC 混杂**：RFC9293 核心逻辑与 RFC5681 / RFC7323 / RFC8985 耦合在同一方法链，无法单独剥离
4. **命名混乱**：`TcpState` 延用 Linux C 风格（`TCP_ESTABLISHED`、`TCP_FIN_WAIT1`），`TcpTimer` 用匿名 lambda 作 Map key，无可读性
5. **无扩展点**：拥塞控制、RTT 估算、丢包检测均硬编码在方法内

---

## 2. 设计目标

**目标**

1. **RFC9293 核心不可插拔**：状态机、序列号、窗口管理、基本重传——始终存在
2. **其他 RFC 扩展可插拔**：每个 RFC 扩展实现一个接口，通过组合注入，缺省时退化到 Noop
3. **富域模型**：`TcpConnection` 替代贫血的 `TcpSock`，状态只通过方法访问，关键字段 private
4. **单一职责**：每个类只做一件事，行数控制在 400 行以内
5. **保持 Netty 线程模型**：所有 TCP 操作在同一个 EventLoop 中执行，不引入额外锁

**非目标（明确排除）**

- 不引入响应式框架（Reactor / RxJava），保持 Netty EventLoop 单线程模型
- 不实现 CUBIC / BBR 等高级拥塞控制算法（属于 `CongestionControl` 扩展点的未来工作）
- 不改变 `TcpMultiplexHandler` / `Tcp4MultiplexHandler` 的对外接口
- 不合并 `TcpReceiveBuffer` 与 `TcpSendBuffer`（接收侧乱序重组 vs 发送侧排队与重传，语义独立）

---

## 3. RFC 关注点归类

### RFC9293（核心，不可剥离）

- 连接状态机（CLOSED / SYN_SENT / SYN_RECEIVED / ESTABLISHED / FIN_WAIT_1 / FIN_WAIT_2 / CLOSE_WAIT / CLOSING / LAST_ACK / TIME_WAIT）
- 序列号管理（SND.UNA、SND.NXT、RCV.NXT）、发送 / 接收窗口管理
- 三次握手（SYN / SYN-ACK / ACK）、四次挥手（FIN / ACK）
- 数据分段与重组（MSS、OFO 队列）、基础重传、RST / FIN 处理

### 可插拔 RFC 扩展

| RFC | 功能 | 接口名 |
|-----|------|--------|
| RFC 6298 | RTO 自适应（SRTT / RTTVAR / Karn 算法） | `RttEstimator` |
| RFC 5681 | 拥塞控制（慢启动、拥塞避免、快速重传 / 恢复） | `CongestionControl` |
| RFC 7323 | TCP 时间戳 + PAWS | `TcpTimestampExtension` |
| RFC 8985 | RACK / TLP 尾部丢包探测 | `LossDetector` |
| RFC 2018 | SACK（待实现） | `SackExtension` |
| RFC 1122 §4.2.3.6 | Keep-Alive | `KeepAliveExtension` |

---

## 4. 目标架构

### 4.1 整体结构

```
┌─────────────────────────────────────────────────────────────────┐
│                       TUN 设备 I/O                              │
└──────────────────────────────┬──────────────────────────────────┘
                               │ IpPacketBuf
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│  TcpMultiplexHandler（对标 Http2MultiplexHandler）             │
│  TcpConnectionRegistry（统一注册表，含所有生命周期阶段）           │
└──────────────────────────────┬──────────────────────────────────┘
                               │ 按四元组查找 / 创建
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│  per-connection pipeline（每条四元组对应一个独立 pipeline）        │
│                                                                  │
│  [握手阶段]  TcpHandshakeHandler                                 │
│      └── 握手完成 → pipeline.replace("handshake","established")  │
│  [传输阶段]  TcpEstablishedHandler                               │
│      └── 收 FIN  → pipeline.replace("established","close",…)   │
│  [挥手阶段]  TcpActiveCloseHandler / TcpPassiveCloseHandler      │
│      └── 结束    → registry.deregister(fourTuple)               │
└──────────────────────────────┬──────────────────────────────────┘
                               │ 各阶段 handler 共用
                               ▼
┌──────────────────────────────────────────────────────────────────┐
│  TcpConnection（富域模型）                                        │
│  ├── TcpConnectionTimers（per-conn slot，无全局 Map）             │
│  ├── Map<ConnectionKey<?>, Object>（RFC 扩展 per-conn 状态）      │
│  └── RttEstimator / CongestionControl / LossDetector / TimestampExt │
└──────────────────────────────┬──────────────────────────────────┘
                               │
                               ▼
┌──────────────────────────────────────────────────────────────────┐
│  可插拔 RFC 扩展层（ext/）                                        │
│  RttEstimator(RFC6298)  CongestionControl(RFC5681)               │
│  TcpTimestampExt(RFC7323)  LossDetector(RFC8985)                 │
│  每个扩展均有 Noop 实现，关闭时退化为 RFC9293 兜底                │
└──────────────────────────────────────────────────────────────────┘
```

### 4.2 Per-Connection Pipeline 三阶段生命周期

TCP 四元组唯一标识一条连接，因此可为每条连接建立独立 pipeline。握手、传输、挥手三个阶段使用不同 handler，阶段间通过 `pipeline.replace()` 切换——与 Netty WebSocket 握手升级模式完全对应。

```
首次 SYN 到达
  TcpMultiplexHandler 创建 per-connection pipeline
  pipeline: [TcpHandshakeHandler]
      │
      │ 三次握手完成
      │ pipeline.replace("handshake", "established", TcpEstablishedHandler)
      ▼
  pipeline: [TcpEstablishedHandler]
      │                            │
      │ 收到对端 FIN（被动关闭）     │ 本端调用 close()（主动关闭）
      │ replace → PassiveClose      │ sendFin() + replace → ActiveClose
      ▼                            ▼
  [TcpPassiveCloseHandler]    [TcpActiveCloseHandler]
  CLOSE_WAIT → LAST_ACK       FIN_WAIT_1 → FIN_WAIT_2 → TIME_WAIT
      │                            │  ↘ CLOSING ──────────────↗
      └─── registry.deregister() ──┘
```

### 4.3 核心数据流

**入站包分发**

```
TUN 读取 IpPacketBuf
    │
    ▼
TcpMultiplexHandler.channelRead(ctx, pkt)
    │ 解析 IP+TCP 头，得 TcpPacketBuf，按四元组查找 TcpConnectionRegistry
    │
    ├─ 命中 → connChannel.fireChildRead(pkt)     # 交给当前阶段 handler
    │
    └─ 未命中 且 pkt.isSyn()
         → 创建 per-connection pipeline（见 §6）
         → registry.register(fourTuple, connChannel)
         → connChannel.fireChildRead(pkt)
```

**握手阶段**

```
TcpHandshakeHandler.channelRead(ctx, pkt)
    ├─ pkt.isSyn()
    │     ├─ handshaker == null → factory.newHandshaker(config, pkt)   # 首次 SYN
    │     └─ handshaker.handshake(ctx.channel(), pkt)                  # 首次或重传，幂等
    │
    └─ pkt.isAck() && handshaker != null
          └─ conn = handshaker.finishHandshake(ctx.channel(), pkt)
               └─ ctx.pipeline().replace("handshake", "established",
                      new TcpEstablishedHandler(conn, ...))
```

**传输阶段**

```
TcpEstablishedHandler.channelRead(ctx, pkt)
    ├─ TcpSegmentValidator.validate(conn, pkt)   # 序列号、RST、PAWS；失败则 drop
    ├─ TcpAckHandler.onAck(conn, pkt)            # ACK + RttEstimator + CongestionControl
    ├─ TcpDataHandler.onData(conn, pkt)          # 数据入队、OFO（有数据时）
    │
    ├─ pkt.isFin() → conn.state(CLOSE_WAIT)
    │     └─ ctx.pipeline().replace("established","close", TcpPassiveCloseHandler)
    │
    └─ scheduleDelayedAck(conn)
```

**挥手阶段**

```
# 主动关闭（本端 close()）
TcpEstablishedHandler.initiateClose(ctx)
    └─ TcpSegmenter.sendFin(conn) → conn.state(FIN_WAIT_1)
       ctx.pipeline().replace("established","close", TcpActiveCloseHandler)

TcpActiveCloseHandler.channelRead(ctx, pkt)
    FIN_WAIT_1 + ACK（对本端 FIN 的 ACK）      → FIN_WAIT_2，调度 FIN_WAIT_2_TIMEOUT 定时器
    FIN_WAIT_1 + FIN（同时关闭）               → CLOSING，发 ACK
    FIN_WAIT_1 + FIN+ACK（同时关闭）           → TIME_WAIT，发 ACK，调度 2MSL 定时器
    FIN_WAIT_2 + FIN                           → TIME_WAIT，发 ACK，调度 2MSL 定时器
    FIN_WAIT_2 + 超时（FIN_WAIT_2_TIMEOUT）    → TIME_WAIT（RFC9293 §3.9.1，对标 Linux tcp_fin_timeout=60s）
    CLOSING    + ACK                           → TIME_WAIT，调度 2MSL 定时器
    TIME_WAIT  到期                            → registry.deregister(fourTuple)

# 被动关闭（对端 FIN）
TcpPassiveCloseHandler.close(ctx, promise)   # 覆盖 ChannelDuplexHandler.close()
    # 应用层调用 channel.close() → Netty 路由到此方法
    # 不立即调用 super.close()，延迟到 LAST_ACK 收到 ACK 后再触发实际关闭
    └─ TcpSegmenter.sendFin(conn) → conn.state(LAST_ACK)，保存 closePromise

TcpPassiveCloseHandler.channelRead(ctx, pkt)
    LAST_ACK + ACK → ctx.close(closePromise)  # 触发实际 Netty 生命周期 → doClose() → registry.deregister()
```

**定时器触发**

```
EventLoop tick（RTO 到期）
    └─ TcpRetransmitHandler.onTimeout(conn)
            ├─ conn.rttEstimator().backoff(conn)          # RFC6298 指数退避
            ├─ conn.congestionControl().onTimeout(conn)   # RFC5681 cwnd 降为 1
            └─ TcpRetransmitter.retransmit(conn)

EventLoop tick（DelAck 到期）
    └─ TcpDelAckHandler.onTimeout(conn)
            └─ TcpSegmenter.sendAck(conn)

# 所有定时器通过 EventLoop.schedule()，per-conn slot 存于 TcpConnectionTimers，无全局 Map
```

---

## 5. 目标包结构

所有新代码放入独立包，不触碰现有代码：

```
com.github.pangolin.routing.acceptor.tun.net.v2/   ← 新包根，与原 tun.net 并列
│
└── tcp/
├── pipeline/                          # per-connection pipeline 基础设施（见 §6，二选一）
│   ├── [方案 A] TcpConnectionPipeline      # 自定义轻量 pipeline，~120 行
│   ├── [方案 A] TcpConnectionContext       # 对标 ChannelHandlerContext，~80 行
│   ├── [方案 A] TcpConnectionHandler       # 对标 ChannelInboundHandler
│   └── [方案 B] TcpConnectionChannel       # extends AbstractChannel，~230 行（推荐）
│
├── demux/
│   ├── TcpMultiplexHandler          # 对标 Http2MultiplexHandler，分发入站包（~80 行）
│   └── TcpConnectionRegistry          # 统一注册表（合并原 synRegistry + establishedRegistry）
│
├── handshake/
│   ├── TcpHandshakerFactory           # 对标 WebSocketServerHandshakerFactory
│   ├── TcpHandshaker                  # handshake() / finishHandshake() / reject()（~200 行）
│   └── TcpHandshakeHandler            # pipeline 握手阶段 handler，完成后替换自身（~80 行）
│
├── established/
│   ├── TcpEstablishedHandler          # 传输阶段入口，含 mini-pipeline（~150 行）
│   ├── TcpSegmentValidator            # 序列号、RST、PAWS 校验（无状态）
│   ├── TcpAckHandler                  # ACK 处理，调用 RttEstimator / CongestionControl（无状态）
│   └── TcpDataHandler                 # 数据入队、OFO 重组（无状态）
│
├── close/
│   ├── TcpActiveCloseHandler          # FIN_WAIT_1/2 → TIME_WAIT → CLOSED（~150 行）
│   └── TcpPassiveCloseHandler         # CLOSE_WAIT → LAST_ACK → CLOSED（~100 行）
│
├── connection/
│   ├── TcpConnection                  # 富域模型（~250 行）
│   ├── TcpConnectionState             # 枚举：ESTABLISHED / FIN_WAIT_1 / SYN_RECEIVED 等
│   ├── TcpSendBuffer                  # write queue + rtx queue（~150 行）
│   └── TcpReceiveBuffer               # 接收缓冲 + OFO 队列（~100 行）
│
├── core/                              # 无状态工具类，被 established/ 和 close/ 共用
│   ├── TcpSegmenter                   # 分段、发送（~350 行）
│   ├── TcpRetransmitter               # 重传逻辑（~200 行）
│   └── TcpAckProcessor                # ACK 序列号处理（~200 行）
│
├── timer/
│   ├── TcpTimerScheduler              # 无状态调度器（~80 行）
│   ├── TcpConnectionTimers            # per-conn slot：writeTimer / delayedAckTimer / keepaliveTimer
│   └── TimerType                      # RETRANSMIT / DELAYED_ACK / ZERO_WINDOW_PROBE / TLP_PROBE / REORDER_TIMEOUT
│
├── ext/
│   ├── rtt/
│   │   ├── RttEstimator               # 接口
│   │   ├── Rfc6298RttEstimator        # RFC6298 实现（~150 行）
│   │   └── NoopRttEstimator
│   ├── cc/
│   │   ├── CongestionControl          # 接口
│   │   ├── NewRenoCongestionControl   # RFC5681 实现（~200 行）
│   │   └── NoopCongestionControl
│   ├── loss/
│   │   ├── LossDetector               # 接口
│   │   ├── TlpLossDetector            # RFC8985 实现（~100 行）
│   │   └── NoopLossDetector
│   ├── timestamp/
│   │   ├── TcpTimestampExtension      # 接口
│   │   ├── Rfc7323TimestampExtension  # RFC7323 实现（~100 行）
│   │   └── NoopTimestampExtension
│   └── keepalive/
│       ├── KeepAliveExtension         # 接口
│       ├── Rfc1122KeepAliveExtension  # RFC1122 §4.2.3.6 实现
│       └── NoopKeepAliveExtension
│
└── internal/
    ├── TcpConstants
    ├── TcpConfig
    ├── TcpOptionCodec
    ├── TcpSequence                    # after() / before() / between() 序列号比较工具
    └── TcpUtils
```

---

## 6. Per-Connection Pipeline 实现方案

每条 TCP 连接（四元组唯一标识）拥有独立 pipeline。`TcpMultiplexHandler` 收到首个 SYN 时创建
pipeline，装载 `TcpHandshakeHandler`，握手完成后通过 `pipeline.replace()` 切换到传输阶段，挥手时
再次替换，与 Netty WebSocket 升级模式完全同构。

以下提供两种实现方案。

---

### 6.1 方案 A：自定义轻量 Pipeline

不依赖真实 Netty Channel，自行实现与 Netty API 命名对应的轻量类。

**基础设施**

```java
/** 对标 ChannelInboundHandler */
public interface TcpConnectionHandler {
    void channelRead(TcpConnectionContext ctx, TcpPacketBuf pkt);
    default void handlerAdded(TcpConnectionContext ctx)   {}
    default void handlerRemoved(TcpConnectionContext ctx) {}
}

/** 对标 ChannelHandlerContext */
public final class TcpConnectionContext {
    private final String                name;
    private final TcpConnectionHandler  handler;
    private final TcpConnectionPipeline pipeline;
    TcpConnectionContext next;

    /** 对标 ctx.fireChannelRead() */
    public void fireChannelRead(TcpPacketBuf pkt) {
        if (next != null) next.invokeChannelRead(pkt);
    }

    /** 对标 ctx.writeAndFlush()，写回 TUN Channel */
    public ChannelFuture writeAndFlush(Object msg) {
        return pipeline.tunChannel().writeAndFlush(msg);
    }

    public TcpConnectionPipeline pipeline() { return pipeline; }
    public Channel channel()               { return pipeline.tunChannel(); }

    void invokeChannelRead(TcpPacketBuf pkt) { handler.channelRead(this, pkt); }
}

/** 对标 ChannelPipeline，~120 行，所有操作在 EventLoop 线程执行，无锁 */
public final class TcpConnectionPipeline {
    private final Channel            tunChannel;
    private TcpConnectionContext     head;

    public TcpConnectionPipeline(Channel tunChannel) { this.tunChannel = tunChannel; }

    /** 对标 pipeline.addLast() */
    public void addLast(String name, TcpConnectionHandler handler) { ... }

    /** 对标 pipeline.replace()，必须在 EventLoop 线程调用 */
    public void replace(String oldName, String newName, TcpConnectionHandler newHandler) {
        TcpConnectionContext ctx = find(oldName);
        ctx.handler().handlerRemoved(ctx);
        TcpConnectionContext newCtx = new TcpConnectionContext(newName, newHandler, this);
        newCtx.next = ctx.next;
        replacePrev(ctx, newCtx);
        newHandler.handlerAdded(newCtx);
    }

    /** 注入入站包，由 TcpMultiplexHandler 调用 */
    public void fireChannelRead(TcpPacketBuf pkt) {
        if (head != null) head.invokeChannelRead(pkt);
    }

    public Channel tunChannel() { return tunChannel; }
}
```

**TcpHandshakeHandler（方案 A）**

```java
public class TcpHandshakeHandler implements TcpConnectionHandler {

    private final TcpHandshakerFactory factory;
    private final TcpConfig            config;
    private TcpHandshaker handshaker;   // per-pipeline 状态

    @Override
    public void channelRead(TcpConnectionContext ctx, TcpPacketBuf pkt) {
        if (pkt.isSyn()) {
            if (handshaker == null)
                handshaker = factory.newHandshaker(config, pkt);
            handshaker.handshake(ctx.channel(), pkt);   // 首次或重传 SYN，幂等
        } else if (pkt.isAck() && handshaker != null) {
            TcpConnection conn = handshaker.finishHandshake(ctx.channel(), pkt);
            if (conn != null)
                ctx.pipeline().replace("handshake", "established",
                    new TcpEstablishedHandler(conn, ...));
        }
    }
}
```

**TcpMultiplexHandler（方案 A）**

```java
// 首次 SYN：创建 pipeline
TcpConnectionPipeline pipeline = new TcpConnectionPipeline(tunChannel);
pipeline.addLast("handshake", new TcpHandshakeHandler(factory, config));
registry.put(fourTuple, pipeline);
pipeline.fireChannelRead(pkt);

// 后续包：统一分发
registry.get(fourTuple).fireChannelRead(pkt);
```

**方案 A 评估**

| 项目 | 说明 |
|------|------|
| 实现规模 | `TcpConnectionPipeline` ~120 行，`TcpConnectionContext` ~80 行 |
| EventLoop 集成 | 直接在 TUN Channel 的 EventLoop 执行，timer 用 `ctx.channel().eventLoop().schedule()` |
| 出站写回 | `ctx.writeAndFlush()` → `tunChannel.writeAndFlush()` |
| handler 接口 | 需实现自定义 `TcpConnectionHandler`，不能直接复用 Netty 生态 handler |
| 适用场景 | 追求轻量可控，不需要与 Netty 生态 handler 互操作 |

---

### 6.2 方案 B：自实现 Netty Channel

每条 TCP 连接对应一个 `TcpConnectionChannel`（`extends AbstractChannel`），注册在由 `TcpMultiplexHandler` 按四元组哈希分配的 **Worker EventLoop** 上，使用**真实** Netty `ChannelPipeline`。TUN EventLoop 仅做路由，不处理 TCP 状态机（见 §6.4）。
参考先例：Netty HTTP/2 的 `AbstractHttp2StreamChannel`，同样是多路复用在单一物理 Channel 上的虚拟子 Channel。

**TcpConnectionChannel**

```java
public class TcpConnectionChannel extends AbstractChannel {

    private final Channel              tunChannel;          // 父 TUN Channel（仅用于 doWrite 写回）
    private final FourTuple            fourTuple;
    private final EventLoop            assignedWorker;      // 由 TcpMultiplexHandler 按四元组哈希分配
    private final Runnable             deregisterCallback;  // TUN EventLoop 上执行 registry.remove()
    private       ChannelHandlerContext parentCtx;          // 用于 doWrite() 写入父 pipeline
    private volatile boolean           active;

    public TcpConnectionChannel(Channel tunChannel, FourTuple fourTuple,
                                EventLoop assignedWorker, Runnable deregisterCallback) {
        super(null);
        this.tunChannel          = tunChannel;
        this.fourTuple           = fourTuple;
        this.assignedWorker      = assignedWorker;
        this.deregisterCallback  = deregisterCallback;
    }

    /** 在注册前由 TcpMultiplexHandler 设置 */
    public void setParentContext(ChannelHandlerContext parentCtx) {
        this.parentCtx = parentCtx;
    }

    @Override
    protected AbstractUnsafe newUnsafe() { return new TcpConnectionUnsafe(); }

    @Override
    protected boolean isCompatible(EventLoop loop) {
        // 接受构造时按四元组哈希分配的 Worker EventLoop（见 §6.4）。
        // 同一连接的所有 TCP 处理始终在同一个 Worker 线程执行，无需锁。
        return loop == assignedWorker;
    }

    @Override
    protected void doRegister() {
        // 虚拟 Channel，无 selector fd，不需要注册到 Selector。
        // 只需将 active 置 true，AbstractChannel.AbstractUnsafe.register0() 在本方法
        // 返回后自动触发 pipeline.fireChannelRegistered() + fireChannelActive()，
        // 让 TcpHandshakeHandler 可在 channelActive() 中做初始化。
        // ⚠️ 禁止在此处主动调用 pipeline().fireChannelRegistered() / fireChannelActive()，
        //    否则 AbstractChannel 会再触发一次，造成重复调用。
        active = true;
    }

    @Override
    protected void doDeregister() {
        // AbstractChannel.AbstractUnsafe.deregister0() 在本方法返回后自动触发
        // pipeline.fireChannelUnregistered()，channelInactive 由 AbstractUnsafe.close()
        // 在检测到 wasActive && !isActive 后触发。
        // ⚠️ 禁止在此处主动调用 pipeline().fireChannelInactive() / fireChannelUnregistered()。
    }

    @Override protected void doBind(SocketAddress local) { /* no-op */ }
    @Override protected void doDisconnect()              { doClose(); }

    @Override
    protected void doClose() {
        if (!active) return;
        active = false;
        // 调用顺序（由 AbstractUnsafe 驱动）：
        //   1. doClose()（本方法）：将 active 置 false，提交 registry 注销任务
        //   2. AbstractUnsafe 检测到 wasActive && !isActive → pipeline.fireChannelInactive()
        //      → 各 handler.channelInactive()：业务 handler 在此调用 conn.close()
        //        （取消定时器、释放扩展 per-conn 状态）
        //   3. AbstractUnsafe.deregister0() → pipeline.fireChannelUnregistered()
        //
        // 触发时机：TcpActiveCloseHandler 在 TIME_WAIT 到期，或
        //          TcpPassiveCloseHandler 在 LAST_ACK 收到对端 ACK 后，
        //          调用 ctx.channel().close() → pipeline unsafe.close() → 此方法。
        //
        // deregisterCallback 回投到 TUN EventLoop 执行 registry.remove(fourTuple)，
        // 保持 registry 单写者（registry 用普通 HashMap，无需 ConcurrentMap）。
        tunChannel.eventLoop().execute(deregisterCallback);
    }

    @Override
    protected void doBeginRead() {
        // Push 模型：入站包由 TcpMultiplexHandler 主动调用 fireChildRead() 注入。
        // 参考 Http2StreamChannel：此处可与 RCV.WND 联动实现流控（当前 no-op）。
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer buf) throws Exception {
        // 参考 AbstractHttp2StreamChannel.doWrite()：
        // 写入父 pipeline（非直接 flush），使 TUN Channel codec 可处理出站包；批量合并写，减少系统调用。
        //
        // 线程安全说明：本方法运行在 Worker EventLoop（见 §6.4），
        // parentCtx.write() 检测到跨线程后自动将写操作提交到 TUN EventLoop 任务队列，
        // 因此 doWrite() 无需额外跨线程包装。
        for (;;) {
            Object msg = buf.current();
            if (msg == null) break;
            parentCtx.write(msg);
            buf.remove();
        }
        parentCtx.flush();
    }

    private static final ChannelMetadata METADATA = new ChannelMetadata(false);

    @Override protected SocketAddress localAddress0()  { return fourTuple.local(); }
    @Override protected SocketAddress remoteAddress0() { return fourTuple.remote(); }
    @Override public boolean isOpen()   { return active; }
    @Override public boolean isActive() { return active; }
    @Override public ChannelMetadata metadata() { return METADATA; }

    /**
     * 将入站包注入本连接 pipeline，由 TcpMultiplexHandler 调用。
     * 命名对标 AbstractHttp2StreamChannel.fireChildRead()，遵循 Netty fire* 约定。
     * 必须在本 Channel 的 EventLoop 线程调用。
     *
     * <p><b>引用计数契约（本方法负责生命周期）：</b>
     * <ul>
     *   <li>active 路径：所有权转交 pipeline。pipeline handler（SimpleChannelInboundHandler
     *       自动释放）或 TailContext 恰好释放一次。调用方在本方法返回后不得再 release。</li>
     *   <li>inactive 路径：本方法直接调用 ReferenceCountUtil.release() 释放。
     *       调用方同样不得再 release。</li>
     * </ul>
     * TUN EventLoop 侧仅负责其通过 retain() 添加的那份引用以及自身持有的原始引用。
     */
    public void fireChildRead(TcpPacketBuf pkt) {
        assert eventLoop().inEventLoop();
        if (!isActive()) {
            // 注册失败或 Channel 已关闭：释放 Worker lambda 所持有的引用（来自调用方 retain()）
            ReferenceCountUtil.release(pkt);
            return;
        }
        // 将所有权转交 pipeline；handler（或 TailContext）负责最终释放
        pipeline().fireChannelRead(pkt);
        pipeline().fireChannelReadComplete();
    }

    private class TcpConnectionUnsafe extends AbstractUnsafe {
        @Override
        public void connect(SocketAddress remote, SocketAddress local, ChannelPromise p) {
            p.setFailure(new UnsupportedOperationException("passive only"));
        }
    }
}
```

**TcpHandshakeHandler（方案 B）**

```java
// 真实 Netty Handler，直接继承 SimpleChannelInboundHandler
public class TcpHandshakeHandler extends SimpleChannelInboundHandler<TcpPacketBuf> {

    private final TcpHandshakerFactory factory;
    private final TcpConfig            config;
    private TcpHandshaker handshaker;

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TcpPacketBuf pkt) {
        if (pkt.isSyn()) {
            if (handshaker == null)
                handshaker = factory.newHandshaker(config, pkt);
            handshaker.handshake(ctx.channel(), pkt);
        } else if (pkt.isAck() && handshaker != null) {
            TcpConnection conn = handshaker.finishHandshake(ctx.channel(), pkt);
            if (conn != null)
                // 真实 Netty pipeline.replace()，与 WebSocket 升级完全一致
                ctx.pipeline().replace(this, "established",
                    new TcpEstablishedHandler(conn, ...));
        }
    }
}
```

**TcpMultiplexHandler（方案 B，对标 Http2MultiplexHandler）**

```java
/**
 * 构造时注入 childHandler（ChannelInitializer）和 workerGroup，
 * 对标 Http2MultiplexHandler(childHandler)。
 * TUN EventLoop 仅做路由，TCP 处理分发到 workerGroup（见 §6.4）。
 */
// 注意：不加 @ChannelHandler.Sharable。handler 持有 per-实例的 registry 和 workers[]，
// 每个 TUN Channel 必须使用独立实例，不能共享。
public class TcpMultiplexHandler extends ChannelInboundHandlerAdapter {

    private final TcpConfig              config;
    private final ChannelHandler         childHandler;
    private final TcpConnectionRegistry  registry = new TcpConnectionRegistry();
    private final EventLoop[]            workers;   // 从 workerGroup 提取，用于一致性哈希

    public TcpMultiplexHandler(TcpConfig config, ChannelHandler childHandler,
                                EventLoopGroup workerGroup) {
        this.config       = config;
        this.childHandler = childHandler;
        List<EventLoop> list = new ArrayList<>();
        workerGroup.forEach(e -> list.add((EventLoop) e));
        this.workers = list.toArray(new EventLoop[0]);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        TcpPacketBuf pkt    = (TcpPacketBuf) msg;
        FourTuple fourTuple = pkt.fourTuple();

        TcpConnectionChannel connCh = registry.get(fourTuple);
        if (connCh == null) {
            if (!pkt.isSyn()) { sendRst(ctx, pkt); return; }

            // 首次 SYN：按四元组哈希选定 Worker，同一连接始终落同一线程
            // ⚠️ 禁止用 Math.abs()：Math.abs(Integer.MIN_VALUE) == Integer.MIN_VALUE（负数），
            // 取模后仍为负数，导致 ArrayIndexOutOfBoundsException。
            // 正确做法：用位与清除符号位，保证结果非负。
            EventLoop worker = workers[(fourTuple.hashCode() & Integer.MAX_VALUE) % workers.length];
            // deregisterCallback 在 TUN EventLoop 执行 registry.remove，保持单写者
            connCh = new TcpConnectionChannel(ctx.channel(), fourTuple, worker,
                () -> registry.remove(fourTuple));
            connCh.setParentContext(ctx);
            connCh.pipeline().addLast(childHandler);
            // AbstractChannel.AbstractUnsafe.register() 在当前线程（TUN EventLoop）设置
            // this.eventLoop = worker（volatile 写），然后提交 register0 任务到 Worker。
            // 因此后续 connCh.eventLoop() 立即可用，不存在 eventLoop==null 的窗口。
            ChannelFuture regFuture = worker.register(connCh);
            regFuture.addListener((ChannelFutureListener) f -> {
                if (!f.isSuccess()) {
                    // 注册失败（Worker 已关闭等）：从注册表移除，防止后续包投递到损坏 Channel。
                    // ⚠️ listener 执行线程不确定（register0 内部失败时在 Worker EventLoop 执行），
                    // 必须回投 TUN EventLoop 以维持 registry 单写者约束（见 §6.4 线程安全保证）。
                    // ⚠️ 不可在此处访问 pkt：listener 异步触发时 TUN 线程已完成 pkt.release()，
                    // 引用计数可能已归零，访问 pkt 是 use-after-free。
                    ctx.channel().eventLoop().execute(() -> registry.remove(fourTuple));
                }
            });
            registry.put(fourTuple, connCh);  // registry 写只在 TUN EventLoop
        }

        final TcpConnectionChannel ch = connCh;
        // 引用计数契约：
        //   retain()         → Worker lambda 获得一份引用（refcount +1）
        //   execute lambda   → fireChildRead() 负责释放该引用（active: pipeline 释放；inactive: 直接释放）
        //                      lambda 内部【不】额外 release——pipeline（SimpleChannelInboundHandler /
        //                      TailContext）已恰好释放一次，多余的 release 会导致 refcount < 0
        //   RejectedExec     → lambda 未提交，catch 块手动释放 retain() 添加的引用
        //   pkt.release()    → TUN 线程释放自身持有的原始引用
        pkt.retain();
        try {
            ch.eventLoop().execute(() -> ch.fireChildRead(pkt));  // fireChildRead 负责引用生命周期
        } catch (RejectedExecutionException e) {
            pkt.release();   // lambda 未提交，补充释放 retain() 增加的引用
        }
        pkt.release();       // TUN 线程释放自身的引用（与 retain 配对）
    }
}
```

使用侧：

```java
// TUN Channel pipeline 配置，对标 Http2MultiplexHandler 的写法
EventLoopGroup workerGroup = new NioEventLoopGroup();  // 线程数 = CPU 核数
tunPipeline.addLast(new TcpMultiplexHandler(config,
    new ChannelInitializer<TcpConnectionChannel>() {
        protected void initChannel(TcpConnectionChannel ch) {
            ch.pipeline().addLast("handshake",
                new TcpHandshakeHandler(handshakerFactory, config));
        }
    },
    workerGroup
));
```

**方案 B 评估**

| 项目 | 说明 |
|------|------|
| 实现规模 | `TcpConnectionChannel` ~230 行，`TcpMultiplexHandler` ~80 行 |
| EventLoop 集成 | `isCompatible()` 绑定 `assignedWorker`；TUN EventLoop 仅路由，TCP 处理分散到 workerGroup（见 §6.4） |
| 出站写路径 | `doWrite()` → `parentCtx.write()` 写入父 pipeline，批量 flush，不逐包 flush |
| 生命周期事件 | `doRegister()` 触发 `channelActive()`；`doDeregister()` 触发 `channelInactive()` |
| pipeline 配置 | `childHandler`（`ChannelInitializer`）注入构造函数，对标 `Http2MultiplexHandler(childHandler)` |
| handler 复用 | 直接使用 `SimpleChannelInboundHandler` / `ChannelDuplexHandler` |
| `pipeline.replace()` | 真实 Netty API，与 WebSocket 升级代码完全同构 |
| 流控扩展点 | `doBeginRead()` 可与 `RCV.WND` 联动（当前 no-op） |
| 参考先例 | `AbstractHttp2StreamChannel`（写合并、生命周期）；`Http2MultiplexHandler`（childHandler 模式） |

---

### 6.3 两方案对比

| | 方案 A（自定义 Pipeline） | 方案 B（自实现 Channel） |
|---|---|---|
| 基础设施代码量 | ~200 行 | ~310 行 |
| handler 接口 | 自定义 `TcpConnectionHandler` | Netty `ChannelHandler` 体系 |
| `pipeline.replace()` | 自实现，语义相同 | 真实 Netty API |
| EventLoop 访问 | `ctx.channel()` 返回 TUN Channel（间接） | `ctx.channel()` 返回 `TcpConnectionChannel`；`eventLoop()` 即分配的 Worker |
| 出站写路径 | `ctx.writeAndFlush()` → `tunChannel` | `doWrite()` → `parentCtx.write()` + 批量 flush |
| 生命周期事件 | 需手动触发 | `channelActive()` / `channelInactive()` 自动触发 |
| Netty 生态 handler 复用 | ✗ | ✓ |
| 流控扩展点 | 需自行实现 | `doBeginRead()` 天然扩展点 |

**推荐方案 B。** 理由：
- `pipeline.replace()` 直接复用 Netty 真实 API，与 WebSocket 升级完全同构，行为有保证
- `channelActive()` / `channelInactive()` 自动触发，生命周期事件无需手动管理
- `doBeginRead()` 是将来实现 TCP ↔ upstream 流控（Gap 5）的天然扩展点
- 额外基础设施（~110 行 vs 方案A ~200 行）由 Netty 本身提供，维护负担不增加

方案 A 仅在需要将整个 TCP 栈移植到非 Netty 环境时才值得考虑。

---

### 6.4 Worker EventLoop 分发

#### 为什么不能沿用 HTTP/2 的单 EventLoop 模式

TUN 是单一 OS 设备，`AbstractHttp2StreamChannel` 的 "子 channel 共享父 channel EventLoop" 模式在 TUN 场景会产生瓶颈：

```
HTTP/2：
  Client-A → TCP连接1 → EventLoop-1  { Stream-1, Stream-2, ... }
  Client-B → TCP连接2 → EventLoop-2  { Stream-1, Stream-2, ... }
  ✓ N 条物理连接 → N 个线程，天然水平扩展

TUN（若直接套用 HTTP/2 模式）：
  所有应用 → TUN设备(唯一) → EventLoop(唯一)  { conn-A, conn-B, conn-C, ... }
  ✗ 全机所有连接 → 始终 1 个线程，无法利用多核
```

因此采用 Worker 分发：TUN EventLoop **只做路由**，按四元组哈希将每条连接的 TCP 处理固定分派到 workerGroup 中的某个 EventLoop 线程。

#### 线程模型

```
TUN EventLoop（唯一）
     │  channelRead(pkt)
     │  1. 按 fourTuple 查 registry（HashMap，单写者）
     │  2. pkt.retain()                     ← Worker lambda 获得引用
     │  3. worker.execute(() -> connCh.fireChildRead(pkt))
     │                                       // fireChildRead 负责 pkt 生命周期：
     │                                       //   inactive → ReferenceCountUtil.release(pkt)
     │                                       //   active   → pipeline 消费（TailContext release）
     │  4. pkt.release()                     ← TUN 线程释放自己的引用
     └─► （RejectedExecutionException: catch 块 pkt.release() + TUN 底部 pkt.release()）

Worker-0  NioEventLoop  { conn-A, conn-D, ... }   ← (fourTuple.hashCode() & MAX_VALUE) % N == 0
Worker-1  NioEventLoop  { conn-B, conn-E, ... }   ← (fourTuple.hashCode() & MAX_VALUE) % N == 1
Worker-2  NioEventLoop  { conn-C, conn-F, ... }   ← (fourTuple.hashCode() & MAX_VALUE) % N == 2
```

**线程安全保证**（三点，均无需加锁）：
- **入站**：`fireChildRead()` 始终在 `assignedWorker` 线程调用（由 `execute()` 保证）
- **出站**：`doWrite()` 在 Worker 线程调用，`parentCtx.write()` 检测跨线程后自动 submit 到 TUN EventLoop
- **registry**：读写只在 TUN EventLoop（写：首次 SYN；删：`doClose()` 回投），普通 `HashMap` 无需 ConcurrentMap

#### 定时器

`conn.eventLoop().schedule(...)` 直接调度到 `assignedWorker`，与 TCP 处理同线程，无额外同步。

#### 上游代理连接的 EventLoop 绑定

TUN TCP 连接（`TcpConnectionChannel`）处理在 `assignedWorker`；对应的上游代理连接（`NioSocketChannel`）应**复用同一个 `assignedWorker`**，使两端数据交换无跨线程开销：

```java
// TcpEstablishedHandler 在 channelActive() 或首包到达时建立上游连接
Bootstrap upstream = new Bootstrap()
    .group(ctx.channel().eventLoop())   // ← assignedWorker，与 TcpConnectionChannel 同线程
    .channel(NioSocketChannel.class)
    .handler(new UpstreamChannelInitializer(...));

upstream.connect(proxyAddress).addListener((ChannelFutureListener) future -> {
    if (future.isSuccess()) {
        Channel upstreamCh = future.channel();
        // 此时 upstreamCh.eventLoop() == ctx.channel().eventLoop()
        // TcpEstablishedHandler ↔ UpstreamHandler 的数据交换全在同一线程，无锁无 execute()
        conn.setAttr(UPSTREAM_CHANNEL_KEY, upstreamCh);
        ...
    }
});
```

**线程模型对比**

```
改造前（TcpDemultiplexHandler 私有 childGroup）：
  TUN EventLoop   → TCP 处理（单线程，所有连接串行）
  childGroup      → 上游连接（独立线程池）
  数据转发需跨线程 execute()

改造后（Worker 分发 + 复用 assignedWorker）：
  TUN EventLoop        → 路由（仅 registry 查找 + 跨线程投递）
  Worker-0 EventLoop   → conn-A TCP 处理 + conn-A 上游连接  ← 同线程，零锁
  Worker-1 EventLoop   → conn-B TCP 处理 + conn-B 上游连接  ← 同线程，零锁
  ...
```

**原 `TcpDemultiplexHandler.childGroup`（TCP.TODO.01.md S4）因此可删除**，不再需要独立线程池；外层只传入 `workerGroup`，上游连接统一通过 `conn.eventLoop()` 创建。

---

### 6.5 与 `AbstractHttp2StreamChannel` 完整对比

对照 `netty-codec-http2:4.2.7.Final` 源码，系统梳理 `TcpConnectionChannel` / `TcpMultiplexHandler` 与 HTTP/2 的异同，并确认各项在 TUN 场景的适用性。

#### 已对齐的设计

| 设计点 | HTTP/2 实现 | 我们的设计 |
|--------|------------|-----------|
| 入站分发命名 | `fireChildRead(Http2Frame)` | `fireChildRead(TcpPacketBuf)` |
| EventLoop 归属 | `eventLoop() { return parent().eventLoop(); }` | `isCompatible(loop) { return loop == assignedWorker; }`（§6.4 Worker 分发） |
| 出站写路径 | `write0(parentContext(), frame)` | `parentCtx.write(msg)` |
| EventLoop 断言 | `assert eventLoop().inEventLoop()` in `fireChildRead()` | 同上 |
| childHandler 注入 | `Http2MultiplexHandler(ChannelHandler inboundStreamHandler)` | `TcpMultiplexHandler(config, ChannelHandler childHandler)` |
| 注册触发 channelActive | `register()` → `fireChannelRegistered()` + `fireChannelActive()` | `doRegister()` → 同上 |

#### Gap 分析与 TUN 适用性

**Gap 1：`channelReadComplete` 每包触发（设计缺陷，低紧迫）**

HTTP/2 做法（`AbstractHttp2StreamChannel.java:594-619`）：
- `fireChildRead()` 只调 `pipeline().fireChannelRead(frame)`
- `channelReadComplete` 通过 `readCompletePendingQueue` 延迟，**一次 read cycle 整体触发一次**

我们的做法：
```java
public void fireChildRead(TcpPacketBuf pkt) {
    pipeline().fireChannelRead(pkt);
    pipeline().fireChannelReadComplete();  // ← 每个包都触发，违反 Netty 契约
}
```

TUN 适用性：**✅ 缺陷存在，但暂无实际影响。**  
`TunChannel.doRead()`（`TunChannel.java:171-198`）一次读 N 包，同一连接可能在一个 read cycle 内收到多包（burst 场景）。每包触发 `channelReadComplete` 违反"一批读完才通知"的 Netty 契约。  
但当前 TCP handler（`TcpInput` 状态机）不依赖 `channelReadComplete` 做批量汇聚，**实际运行不受影响**。等引入依赖 `channelReadComplete` 的 handler 时再修复。

---

**Gap 2：flush 未批量化（性能优化，TUN 场景收益有限）**

HTTP/2 做法（`Http2MultiplexHandler.java:366-386`）：
- `parentReadInProgress = true` → 子 channel 的 `flush()` 被抑制（NOOP）
- `channelReadComplete` 时统一 `ctx.flush()` — **整个 read cycle 只 flush 一次**

我们的做法：`doWrite()` 末尾 `parentCtx.flush()`，N 个连接写 → N 次 flush。

TUN 适用性：**⚠️ 优化存在，但收益受限。**

HTTP/2 批量 flush 的收益是把 N 次 TCP `writev()` 合并成 1 次。TUN 的情况不同：

```java
// TunChannel.doWrite()：每个 IP 包必须独立写，无法合并
while (true) {
    device.write(((ByteBuf) msg).nioBuffer());  // WinTUN ring buf / Linux tun fd
    in.remove();
}
```

批量 flush 对 TUN 的效果：
- 减少的只是 `doWrite()` 调用次数（pipeline 遍历开销），**不减少实际 `device.write()` 次数**
- WinTUN（内存映射写）：差异可忽略
- Linux tun（fd write）：每个包仍是独立 syscall

另一个障碍：`TunChannel` 用独立 `readLoop` 执行阻塞读（`TunChannel.java:39`），然后跨线程 submit 到 Channel EventLoop。HTTP/2 中批次处理是在同一 EventLoop task 内原子完成，`parentReadInProgress` flag 天然准确；TUN 中 N 包 = N 个 EventLoop task，批次间 timer task 可能插队，flag 跨 task 不可靠。

**结论**：不值得为 TUN 场景引入 `readCompletePendingQueue` 的复杂度。若将来有强需求，可以在 `TunChannel` 层面将整批包包在单个 EventLoop task 里提交，比 handler 层 flag 更可靠。

---

**Gap 3：`channelWritabilityChanged` 传播（不适用）**

HTTP/2 做法（`Http2MultiplexHandler.java:209-217`）：父 channel 可写时，广播给所有子 channel → 触发 `trySetWritable()` → 通知业务层恢复写。

TUN 适用性：**❌ 当前不适用。**  
`TunChannel.doWrite()` 无背压处理：

```java
try {
    device.write(((ByteBuf) msg).nioBuffer());
} finally {
    in.remove();   // ← 写失败静默丢弃，无错误通知
}
```

`TunChannel` 目前不处理写错误、不实现背压，即使 outbound buffer 满也不会触发有意义的 `channelWritabilityChanged`。传播过去无意义。  
前置条件：先修复 `TunChannel.doWrite()` 的错误处理与背压机制。

---

**Gap 4：`isWritable()` 水位追踪（不适用，依赖 Gap 3）**

HTTP/2 做法：`totalPendingSize` + `WriteBufferWaterMark` 高低水位，`isWritable()` 基于 `unwritable == 0`。  
我们的做法：`isWritable()` 直接返回 `active`。

TUN 适用性：**❌ 依赖 Gap 3，Gap 3 不适用则此项同样不适用。**

---

**Gap 5：`inboundBuffer` + `ReadStatus`（未来需要）**

HTTP/2 做法：`ReadStatus`（IDLE / IN_PROGRESS / REQUESTED）+ `ArrayDeque<Object> inboundBuffer`，支持 `autoRead=false` 时缓冲帧，等 `beginRead()` 再排空。

TUN 适用性：**🔮 将来需要，现阶段不适用。**

TUN 是推模型，`TcpConnectionChannel.doBeginRead()` 为 no-op。对 TUN 来说，真正的流控入口不是 `autoRead`，而是 TCP `rcv_wnd`：

```
upstream socket 慢 → 缩 rcv_wnd → 0 → 远端停发
                   ↓ autoRead=false + inboundBuffer
                   在 rcv_wnd 缩窗期间，少量 in-flight 包进 inboundBuffer 缓冲
upstream 恢复     → 展 rcv_wnd → autoRead=true → 排空 inboundBuffer
```

这套联动在实现 TCP ↔ upstream 流控之前不需要。

---

#### 额外发现：`TunChannel` 读写 EventLoop 分离

```
readLoop（独立 DefaultEventLoop）   ← device.read()，阻塞
   │ fireChannelRead(pkt) × N       ← 跨线程，每次 submit 一个 task
   │ fireChannelReadComplete()       ← 跨线程，submit
   ↓
Channel EventLoop                    ← 处理 N+1 个 task，timer task 可能插队
```

这与 HTTP/2（父子 channel 共享同一 EventLoop，批次内原子处理）有本质差异，是 `parentReadInProgress` flag 无法直接移植的根本原因。

#### 汇总

| Gap | TUN 适用？ | 结论 |
|-----|-----------|------|
| Gap 1：`channelReadComplete` 每包 | ✅ 缺陷存在 | 当前 handler 不受影响，低优先级 |
| Gap 2：flush 批量化 | ⚠️ 受限 | TUN 写不可合并，EventLoop 分离使 flag 不可靠，暂不实现 |
| Gap 3：writabilityChanged 传播 | ❌ 不适用 | `TunChannel.doWrite()` 无背压处理 |
| Gap 4：isWritable() 水位 | ❌ 不适用 | 依赖 Gap 3 |
| Gap 5：inboundBuffer + ReadStatus | 🔮 将来 | TCP ↔ upstream 流控实现时的前置依赖 |

---

### 6.6 HTTP/2 代码组织结构借鉴

对照 `netty-codec-http2:4.2.7.Final` 源码，梳理 HTTP/2 的整体分层结构，提取可直接落地到 TUN TCP 栈的 6 个设计模式。

#### HTTP/2 整体分层

```
Http2FrameCodec          ← Netty handler 层：字节 ↔ Http2Frame，持有 Http2Connection
    │
    ├─ Http2Connection           ← 连接级状态管理器
    │    ├─ Http2Stream          ← 单条流的状态机
    │    ├─ Endpoint<FC>         ← 本端/远端对称视图
    │    ├─ Listener             ← 流生命周期事件
    │    └─ PropertyKey          ← 类型安全属性 key
    │
    ├─ Http2LocalFlowController  ← 入站流控（RCV_WND 类比）
    └─ Http2RemoteFlowController ← 出站流控（SND_WND 类比）
             └─ FlowControlled  ← 出站数据单元（支持 merge/coalesce）

Http2MultiplexHandler    ← 独立于 FrameCodec，只做子 Channel 路由
    └─ AbstractHttp2StreamChannel
```

我们现状对照：

```
IpPacketCodec            ← 字节 → IpPacketBuf（对标 Http2FrameCodec 编解码部分）
TcpMultiplexHandler      ← 职责叠加：路由 + 注册表 + 状态机驱动
    TcpMultiplexer       ← 注册表 + TCP 状态机（对标 Http2Connection，但耦合严重）
    TcpSock              ← 连接数据（对标 Http2Stream，贫血模型）
```

---

#### 借鉴点 1：`Http2Stream.State` 加行为方法（**立即可落地**）

HTTP/2 的做法（`Http2Stream.java`）：

```java
enum State {
    IDLE              (false, false),
    OPEN              (true,  true),
    HALF_CLOSED_LOCAL (false, true),    // 本端发了 FIN
    HALF_CLOSED_REMOTE(true,  false),   // 对端发了 FIN
    CLOSED            (false, false);

    final boolean localSideOpen;
    final boolean remoteSideOpen;

    public boolean localSideOpen()  { return localSideOpen; }
    public boolean remoteSideOpen() { return remoteSideOpen; }
}
```

调用侧：`if (state.localSideOpen()) { ... }` 替代 `state == ESTABLISHED || state == FIN_WAIT_1 || ...`

**对应改造**（`TcpConnectionState`）：

```java
public enum TcpConnectionState {
    // 注意：LISTEN / SYN_SENT / SYN_RECEIVED 均不由 TcpConnection 持有；
    // TcpConnection 在 TcpHandshaker.finishHandshake() 完成后创建，初始状态直接为 ESTABLISHED。
    // 三者保留在枚举中仅为与 RFC9293 完整状态机对应（RFC9293 共 11 个标准状态），
    // TcpConnection 实例实际不会处于这些状态。
    // TUN 场景为被动接入（passive open），SYN_SENT 永不使用；保留供枚举完整性。
    CLOSED        (false, false),
    LISTEN        (false, false),   // 不由 TcpConnection 持有
    SYN_SENT      (false, false),   // 不由 TcpConnection 持有（主动侧才有，TUN 场景不使用）
    SYN_RECEIVED  (false, false),   // 不由 TcpConnection 持有（由 TcpHandshaker 内部持有）
    ESTABLISHED   (true,  true),
    FIN_WAIT_1    (false, true),   // 本端已发 FIN，等对端 ACK 或同时收 FIN
    FIN_WAIT_2    (false, true),   // 已收对端对本端 FIN 的 ACK，等对端 FIN
    CLOSE_WAIT    (true,  false),  // 已收对端 FIN，等本端 close()
    CLOSING       (false, false),
    LAST_ACK      (false, false),
    TIME_WAIT     (false, false);

    private final boolean canSend;    // 本端还能发数据
    private final boolean canReceive; // 远端还能发数据

    TcpConnectionState(boolean canSend, boolean canReceive) {
        this.canSend = canSend;
        this.canReceive = canReceive;
    }

    /** 本端仍可发送数据（未发 FIN） */
    public boolean canSend()    { return canSend; }
    /** 远端仍可发送数据（未收其 FIN） */
    public boolean canReceive() { return canReceive; }
    /** 双端均已关闭 */
    public boolean isFullyClosed() { return !canSend && !canReceive; }
}
```

收益：消灭 `TcpInput` / `TcpOutput` 里几十行重复的状态枚举判断。

---

#### 借鉴点 2：`Http2LifecycleManager` — 连接关闭协调接口

HTTP/2 的做法（`Http2LifecycleManager.java`）：

```java
interface Http2LifecycleManager {
    void closeStreamLocal(Http2Stream stream, ChannelFuture future);   // 我方发 END_STREAM
    void closeStreamRemote(Http2Stream stream, ChannelFuture future);  // 对方发 END_STREAM
    void closeStream(Http2Stream stream, ChannelFuture future);        // 双端完成
    ChannelFuture resetStream(ctx, streamId, errorCode, promise);      // 发 RST
    ChannelFuture goAway(ctx, lastStreamId, errorCode, ...);           // 连接级关闭
    void onError(ctx, outbound, cause);                                // 错误处理
}
```

关键设计：所有组件（Input / Output / Timer）统一通过这一个接口触发关闭，不直接操作连接状态。

**我们的现状**：TCP 四次挥手的状态转移分散在 `TcpInput.tcp_fin()` / `TcpOutput.tcp_send_fin()` / `TcpTimer` 各处，没有统一的协调点，难以保证时序和幂等性。

**可借鉴的接口**：

```java
// 对标 Http2LifecycleManager，集中管理 TCP 连接关闭路径
interface TcpConnectionLifecycle {
    /** 本端发出 FIN（进入 FIN_WAIT_1 / LAST_ACK） */
    void closeLocal(TcpConnection conn, ChannelPromise promise);

    /** 收到对端 FIN（进入 CLOSE_WAIT） */
    void closeRemote(TcpConnection conn);

    /** 发送 RST，立即强制关闭 */
    void reset(TcpConnection conn, int errorCode);

    /** 进入 TIME_WAIT，2MSL 后 destroy */
    void enterTimeWait(TcpConnection conn);

    /** 从注册表移除，释放所有资源 */
    void destroy(TcpConnection conn);

    /** 处理连接级错误 */
    void onError(TcpConnection conn, boolean outbound, Throwable cause);
}
```

---

#### 借鉴点 3：`Http2Connection.Listener` + `Http2ConnectionAdapter` — 注册表生命周期通知

HTTP/2 的做法：

```java
// Http2Connection.java
interface Listener {
    void onStreamAdded(Http2Stream stream);      // 进入注册表（SYN_RECEIVED 类比）
    void onStreamActive(Http2Stream stream);     // 变为 ESTABLISHED 类比
    void onStreamHalfClosed(Http2Stream stream); // FIN_WAIT_2 / CLOSE_WAIT
    void onStreamClosed(Http2Stream stream);     // 双端关闭
    void onStreamRemoved(Http2Stream stream);    // 从注册表移除
}

// Http2ConnectionAdapter.java — 空实现，只需重写关心的方法
public class Http2ConnectionAdapter implements Http2Connection.Listener { ... }
```

任何组件（日志、监控、路由表更新）都可以注册 Listener，无需耦合注册表实现本身。

**可借鉴的接口**：

```java
interface TcpConnectionListener {
    default void onConnectionCreated(TcpConnection conn)     {}  // SYN_RECEIVED（半开）
    default void onConnectionEstablished(TcpConnection conn) {}  // ESTABLISHED
    default void onConnectionHalfClosed(TcpConnection conn)  {}  // 单向 FIN
    default void onConnectionClosed(TcpConnection conn)      {}  // TIME_WAIT/CLOSED
    default void onConnectionRemoved(TcpConnection conn)     {}  // 从注册表移除
}
```

用 Java `default` 方法替代 Adapter 类，实现侧只重写关心的方法。

---

#### 借鉴点 4：`FlowControlled.merge()` — 出站数据合并（中期目标）

HTTP/2 的做法（`Http2RemoteFlowController.java`）：

```java
interface FlowControlled {
    int size();
    void write(ChannelHandlerContext ctx, int allowedBytes);
    boolean merge(ChannelHandlerContext ctx, FlowControlled next);  // ← 合并小包
    void writeComplete();
    void error(ChannelHandlerContext ctx, Throwable cause);
}
```

`merge()` 让流控器在窗口打开时，把队列里相邻的小单元合并成一个大单元写出——这是 Nagle 算法的框架级抽象。流控器调用 `writePendingBytes()` 按窗口逐步排空队列，每步都尝试 merge。

**我们的现状**：Nagle 判断、MSS 分段、窗口检查三者硬编码混在 `TcpOutput.tcp_write_xmit()` 里。

**可借鉴的方向**：把出站数据封装为 `TcpSendUnit`（含 `size()`、`write()`、`merge()`），流控器持有发送队列，按 `snd_cwnd` 限额依次调用 `write()` 并按需 `merge()`，使 Nagle 逻辑从 `TcpOutput` 中分离出来。

---

#### 借鉴点 5：`forEachActiveStream(Visitor)` — 可中断的遍历接口（**立即可落地**）

HTTP/2 的做法（`Http2FrameStreamVisitor.java`）：

```java
@FunctionalInterface
interface Http2FrameStreamVisitor {
    boolean visit(Http2FrameStream stream);  // 返回 false 终止遍历
}
// 使用：
forEachActiveStream(WRITABLE_VISITOR);   // 广播可写事件
forEachActiveStream(stream -> {          // lambda，支持提前退出
    if (stream.id() > threshold) {
        doSomething(stream);
    }
    return true;
});
```

`boolean` 返回值支持提前终止，比 `forEach(Consumer)` 更灵活（查找第一个匹配、遍历到满足条件即停）。

**可借鉴的改造**：

```java
@FunctionalInterface
interface TcpConnectionVisitor {
    /** @return false 终止遍历 */
    boolean visit(TcpConnection conn);
}

// TcpMultiplexer 上增加：
void forEachEstablished(TcpConnectionVisitor visitor);
```

---

#### 借鉴点 6：`Http2FrameCodec` / `Http2MultiplexHandler` 职责分离（长期目标）

HTTP/2 的两个 handler 严格单一职责：

| Handler | 职责 |
|---------|------|
| `Http2FrameCodec` | 字节 ↔ 帧对象；持有 `Http2Connection`；处理 SETTINGS / PING / GOAWAY |
| `Http2MultiplexHandler` | 仅做子 Channel 创建与帧分发，不关心协议细节 |

**我们的现状**：`TcpMultiplexHandler` 承担 IP 包过滤 + 四元组路由 + 注册表管理 + 状态机驱动，职责过重。

**长期分层目标**：

```
IpPacketCodec              ← 字节 → IpPacketBuf（已有）
TcpConnectionCodec（新）   ← 对标 Http2FrameCodec：
                               持有 TcpConnectionRegistry
                               处理连接级事件（RST 广播、超时清理）
                               维护 TcpConnectionListener 通知链
    ↓ pipeline
TcpMultiplexHandler（精简）← 对标 Http2MultiplexHandler：
                               只做四元组 → TcpConnectionChannel 路由
                               首包 SYN 时创建子 Channel，装载 childHandler
```

---

#### 汇总

| 借鉴点 | HTTP/2 来源 | 对标组件 | 优先级 |
|--------|------------|---------|--------|
| State 枚举加行为方法 | `Http2Stream.State` | `TcpConnectionState` | **立即** |
| Visitor 可中断遍历 | `Http2FrameStreamVisitor` | `TcpConnectionVisitor` | **立即** |
| LifecycleManager 关闭协调 | `Http2LifecycleManager` | `TcpConnectionLifecycle` | Phase 2 |
| Connection.Listener 注册表通知 | `Http2Connection.Listener` | `TcpConnectionListener` | Phase 2 |
| FlowControlled.merge 发送合并 | `Http2RemoteFlowController` | `TcpSendUnit` | Phase 3 |
| Codec/Multiplexer 分层 | `Http2FrameCodec` vs `Http2MultiplexHandler` | TcpConnectionCodec + 精简 MultiplexHandler | 长期 |

---

## 7. 核心接口与数据模型

### 7.1 可插拔扩展接口

```java
// RFC 6298：RTO 自适应，per-conn 状态通过 ConnectionKey 存于 TcpConnection.attributes
public interface RttEstimator {
    void init(TcpConnection conn);
    /**
     * 加入 RTT 采样，rttUs 单位微秒。
     *
     * <p><b>Karn 算法（RFC 6298 §4）</b>：调用方（TcpAckProcessor）必须确保
     * 仅对<b>未重传</b>的报文做 RTT 采样；若 ACK 对应的段曾被重传（Karn 标记），
     * 则 rttSample() 返回 -1，此方法应忽略该调用（rttUs &lt; 0 时直接返回）。
     * 违反此约束会导致 SRTT / RTTVAR 被重传歧义污染，RTO 估算失准。
     */
    void addSample(TcpConnection conn, long rttUs);
    /** 返回当前 RTO，单位毫秒 */
    long rtoMs(TcpConnection conn);
    /** RTO 指数退避（RFC6298 §5.5） */
    void backoff(TcpConnection conn);
    void resetBackoff(TcpConnection conn);
    void onConnectionClosed(TcpConnection conn);
}
```

```java
// RFC 5681：拥塞控制（含完整状态机）
// 设计要点：RFC9293 层只通知 onAck 事件和查询 cwnd()。
// dupACK 计数、CongestionState 切换、快速重传触发均在实现类内部封装。
// 重传执行由 RFC9293（TcpRetransmitter）完成，CC 通过 init() 注入的 retransmitCallback 触发。
public interface CongestionControl {
    String name();
    void init(TcpConnection conn, Consumer<TcpConnection> retransmitCallback);
    void onAck(TcpConnection conn, int ackedSegments, boolean sndUnaAdvanced);
    void onTimeout(TcpConnection conn);
    int cwnd(TcpConnection conn);
    boolean isInRecovery(TcpConnection conn);
    void onConnectionClosed(TcpConnection conn);
}
```

```java
// RFC 8985：丢包检测（TLP / RACK）
public interface LossDetector {
    void init(TcpConnection conn);
    void scheduleProbe(TcpConnection conn);
    void sendProbe(TcpConnection conn);
    void onAck(TcpConnection conn, int ackSeq, int flags);
    void onConnectionClosed(TcpConnection conn);
}
```

```java
// RFC 7323：时间戳 / PAWS
public interface TcpTimestampExtension {
    void init(TcpConnection conn);
    boolean isEnabled(TcpConnection conn);
    int buildTsval(TcpConnection conn);
    /** @return true 表示该包应被丢弃（PAWS 命中） */
    boolean isPawsRejected(TcpConnection conn, int tsval);
    void updateRecent(TcpConnection conn, int seq);
    void onConnectionClosed(TcpConnection conn);
}
```

### 7.2 `TcpConnection`（富域模型）

```java
public class TcpConnection {

    // ── RFC9293 核心状态（private，只通过方法访问）──
    private TcpConnectionState state;
    private int sndUna;   // SND.UNA
    private int sndNxt;   // SND.NXT
    private int rcvNxt;   // RCV.NXT
    private int sndWnd;   // SND.WND
    private int rcvWnd;   // RCV.WND
    private int mss;

    // ── Netty 绑定（方案 A：TUN Channel；方案 B：TcpConnectionChannel）──
    private final Channel channel;

    // ── RFC 扩展 per-conn 状态存储 ──
    private final Map<ConnectionKey<?>, Object> attributes = new HashMap<>();

    // ── 缓冲区 ──
    private final TcpSendBuffer    sendBuffer;
    private final TcpReceiveBuffer receiveBuffer;

    // ── 可插拔扩展（构造时注入，见 §9 Builder）──
    private final RttEstimator          rttEstimator;
    private final CongestionControl     congestionControl;
    private final LossDetector          lossDetector;
    private final TcpTimestampExtension timestampExt;

    // ── 定时器 slot ──
    private final TcpConnectionTimers timers = new TcpConnectionTimers();

    // ── 访问器 ──
    public Channel    channel()   { return channel; }
    public EventLoop  eventLoop() { return channel.eventLoop(); }

    public TcpConnectionState state() { return state; }
    public int sndUna()               { return sndUna; }
    public int sndNxt()               { return sndNxt; }
    public int mss()                  { return mss; }

    /** 推进 SND.UNA，清理 rtx queue 中已确认的 skb */
    public void acknowledgeUpTo(int ackSeq) { ... }

    @SuppressWarnings("unchecked")
    public <T> T    getAttr(ConnectionKey<T> key)             { return (T) attributes.get(key); }
    public <T> void setAttr(ConnectionKey<T> key, T value)    { attributes.put(key, value); }
    public    void  removeAttr(ConnectionKey<?> key)           { attributes.remove(key); }

    public RttEstimator          rttEstimator()      { return rttEstimator; }
    public CongestionControl     congestionControl() { return congestionControl; }
    public LossDetector          lossDetector()      { return lossDetector; }
    public TcpTimestampExtension timestampExt()      { return timestampExt; }
    public TcpConnectionTimers   timers()            { return timers; }

    /** 连接关闭：取消定时器，释放各扩展 per-conn 状态。必须在 EventLoop 线程调用。 */
    public void close() {
        timers.cancelAll();
        rttEstimator.onConnectionClosed(this);
        congestionControl.onConnectionClosed(this);
        lossDetector.onConnectionClosed(this);
        timestampExt.onConnectionClosed(this);
    }
}
```

### 7.3 `ConnectionKey<T>`（扩展状态隔离）

类比 Netty `AttributeKey<T>`，各 RFC 扩展将 per-connection 状态存入 `TcpConnection.attributes`：

```java
/**
 * 类型安全的连接属性 key，类似 Netty AttributeKey。
 * 命名规范："{rfc-id}.{impl-name}"，如 "rfc5681.newreno"。
 * key 名称须全局唯一，由调用方保证。
 */
public final class ConnectionKey<T> {
    private final String name;
    private ConnectionKey(String name) { this.name = name; }
    public static <T> ConnectionKey<T> of(String name) { return new ConnectionKey<>(name); }
    @Override public String toString() { return name; }
}
```

以 `NewRenoCongestionControl` 为例：

```java
public class NewRenoCongestionControl implements CongestionControl {

    private static final ConnectionKey<RenoState> KEY = ConnectionKey.of("rfc5681.newreno");

    static class RenoState {
        int cwnd = TCP_INIT_CWND, ssthresh = Integer.MAX_VALUE, dupacks = 0;
        // 拥塞避免阶段的分数计数器，对标 Linux tcp_sock.snd_cwnd_cnt。
        // 累积到 cwnd 时才执行 cwnd++，确保每 RTT（约 cwnd 个 ACK）只增长 1 段。
        // 原 Math.max(1, ackedSegs/cwnd) 每 ACK 至少 +1，远快于 RFC 5681 §3.1 要求。
        int caIncrCounter = 0;
        CongestionState caState = CongestionState.OPEN;
        int highSeq;             // 进入 RECOVERY 时的 SND.NXT，用于判断全 ACK 退出恢复
        Consumer<TcpConnection> retransmitCallback;
    }

    enum CongestionState { OPEN, RECOVERY, LOSS }

    @Override
    public void init(TcpConnection conn, Consumer<TcpConnection> retransmit) {
        RenoState s = new RenoState();
        s.retransmitCallback = retransmit;
        conn.setAttr(KEY, s);
    }

    @Override
    public void onAck(TcpConnection conn, int ackedSegments, boolean sndUnaAdvanced) {
        RenoState s = conn.getAttr(KEY);
        if (!sndUnaAdvanced) {
            // 重复 ACK
            if (++s.dupacks == 3 && s.caState == CongestionState.OPEN) {
                // 进入 Fast Recovery（RFC 5681 §3.2）
                s.ssthresh = Math.max(s.cwnd / 2, 2);
                s.cwnd     = s.ssthresh + 3;   // inflate：3 个 dupACK 各代表一个离开网络的段
                s.highSeq  = conn.sndNxt();    // recovery point
                s.caState  = CongestionState.RECOVERY;
                s.caIncrCounter = 0;
                s.retransmitCallback.accept(conn);
            } else if (s.caState == CongestionState.RECOVERY) {
                s.cwnd++;   // RFC 5681 §3.2：每个额外 dupACK inflate 1 段
            }
        } else {
            // 新数据被确认（sndUna 前进）
            if (s.caState == CongestionState.RECOVERY
                    && TcpSequence.after(conn.sndUna(), s.highSeq)) {
                // Full ACK：退出 Fast Recovery（RFC 5681 §3.2 step 6）
                s.cwnd = s.ssthresh;
                s.caState = CongestionState.OPEN;
                s.caIncrCounter = 0;
            } else if (s.caState == CongestionState.LOSS) {
                // 超时重传后首次收到新 ACK：退出 LOSS，重新进入慢启动。
                // ⚠️ 原代码无此分支：LOSS 状态 cwnd 永久卡在 1，连接吞吐无法恢复。
                // 对标 Linux：Loss 状态收到新 ACK 即退回 Open，cwnd 按慢启动增长。
                s.caState = CongestionState.OPEN;
                s.caIncrCounter = 0;
            }
            s.dupacks = 0;
            // 慢启动：指数增长，cwnd < ssthresh（RFC 5681 §3.1）
            if (s.cwnd < s.ssthresh) {
                s.cwnd += ackedSegments;
            } else {
                // 拥塞避免：每 RTT 增长约 1 MSS（RFC 5681 §3.1）
                // 对标 Linux tcp_cong_avoid_ai：累积计数器达到 cwnd 时才 +1，
                // 避免每 ACK 都 +1（后者使 cwnd 每 RTT 增长 cwnd 倍，远超 RFC 要求）。
                s.caIncrCounter += ackedSegments;
                if (s.caIncrCounter >= s.cwnd) {
                    s.cwnd++;
                    s.caIncrCounter = 0;
                }
            }
        }
    }

    @Override
    public void onTimeout(TcpConnection conn) {
        RenoState s = conn.getAttr(KEY);
        // RFC 5681 §3.1：ssthresh = max(FlightSize/2, 2)，cwnd 重置为 1 进入慢启动
        // 注：此处用 cwnd 近似 FlightSize（严格应用 min(cwnd, flightSize) / 2）
        s.ssthresh = Math.max(s.cwnd / 2, 2);
        s.cwnd = 1;
        s.dupacks = 0;
        s.caIncrCounter = 0;
        s.caState = CongestionState.LOSS;
    }

    @Override public int     cwnd(TcpConnection conn)         { return conn.getAttr(KEY).cwnd; }
    @Override public boolean isInRecovery(TcpConnection conn) {
        RenoState s = conn.getAttr(KEY);
        // RECOVERY（Fast Retransmit）或 LOSS（RTO 触发）均视为"非正常路径"
        return s != null && s.caState != CongestionState.OPEN;
    }
    @Override public void onConnectionClosed(TcpConnection conn) { conn.removeAttr(KEY); }
}
```

`TcpAckProcessor`（RFC9293 核心，不含拥塞控制逻辑）：

```java
public class TcpAckProcessor {
    // void：ACK 处理结果通过 conn 状态变更体现，无需返回标志位
    public void onAck(TcpConnection conn, TcpPacketBuf pkt) {
        int prevUna    = conn.sndUna();
        int ackedBytes = tcpSndUnaUpdate(conn, pkt.ackSeq());
        boolean advanced = TcpSequence.after(conn.sndUna(), prevUna);

        // 将字节数折算为段数。
        // ⚠️ 不能直接用 ackedBytes / mss：末尾小包（ackedBytes < mss，如最后一段数据或 FIN）
        //    会导致 ackedSegs=0，此时 advanced=true 但 CC 不增长 cwnd。
        //    修复：有字节被确认时至少计 1 段（对标 Linux min(acked, 1) in slow start）。
        int ackedSegs = ackedBytes > 0 ? Math.max(1, ackedBytes / conn.mss()) : 0;

        // Karn 算法（RFC 6298 §4）：rttSample() 对重传段返回 -1；
        // RttEstimator.addSample() 在 rttUs < 0 时直接跳过，不更新 SRTT/RTTVAR。
        conn.rttEstimator().addSample(conn, rttSample(conn, pkt));                  // RFC6298
        conn.congestionControl().onAck(conn, ackedSegs, advanced);                  // RFC5681
        conn.lossDetector().onAck(conn, pkt.ackSeq(), computeAckFlags(conn, pkt)); // RFC8985
    }
}
```

### 7.4 `TcpTimerScheduler` + `TcpConnectionTimers`

详细设计见 `TCP.timer.md`。核心思路：消除全局 `ConcurrentMap<Runnable, Future>`，改为 per-connection slot。

```java
/** per-conn 定时器 slot，所有字段只在 EventLoop 线程访问，无需同步 */
public final class TcpConnectionTimers {
    // RETRANSMIT / TLP_PROBE / ZERO_WINDOW_PROBE / REORDER_TIMEOUT 共用一个 slot
    ScheduledFuture<?> writeTimer;
    TimerType          writeTimerType;
    long               writeTimerExpires;   // 绝对到期时刻（ms），用于判断是否需要重调

    ScheduledFuture<?> delayedAckTimer;
    ScheduledFuture<?> keepaliveTimer;

    public void cancelAll() {
        cancel(writeTimer); cancel(delayedAckTimer); cancel(keepaliveTimer);
        writeTimer = delayedAckTimer = keepaliveTimer = null;
        writeTimerType = null;
    }
    private static void cancel(ScheduledFuture<?> f) {
        if (f != null && !f.isDone()) f.cancel(false);
    }
}

public enum TimerType {
    RETRANSMIT,           // Linux: ICSK_TIME_RETRANS
    DELAYED_ACK,          // Linux: ICSK_TIME_DACK
    ZERO_WINDOW_PROBE,    // Linux: ICSK_TIME_PROBE0
    TLP_PROBE,            // Linux: ICSK_TIME_LOSS_PROBE
    REORDER_TIMEOUT,      // Linux: ICSK_TIME_REO_TIMEOUT
    /**
     * FIN_WAIT_2 超时（RFC 9293 §3.9.1）：进入 FIN_WAIT_2 后若对端始终不发 FIN，
     * 连接将永占资源。对标 Linux tcp_fin_timeout sysctl（默认 60s），
     * 超时后强制调用 ctx.close() 进入 TIME_WAIT 或直接 CLOSED。
     * 实现上复用 keepaliveTimer slot（与 Linux 共享 keepalive 定时器槽位一致）。
     */
    FIN_WAIT_2_TIMEOUT    // Linux: 通过 inet_csk_reset_keepalive_timer 实现
}

/** 无状态调度器，可被多个 TcpConnection 共享，约 80 行 */
public final class TcpTimerScheduler {

    public void scheduleWriteTimer(TcpConnection conn, TimerType type,
                                   long delayMs, Runnable action) {
        TcpConnectionTimers t = conn.timers();
        if (t.writeTimer != null && !t.writeTimer.isDone()) t.writeTimer.cancel(false);
        long delay          = Math.max(delayMs, 1);
        t.writeTimerType    = type;
        t.writeTimerExpires = System.currentTimeMillis() + delay;
        t.writeTimer        = conn.eventLoop().schedule(action, delay, MILLISECONDS);
    }

    public void scheduleDelayedAck(TcpConnection conn, long delayMs, Runnable action) { ... }
    public void scheduleKeepalive(TcpConnection conn, long delayMs, Runnable action)  { ... }
    public void cancelWriteTimer(TcpConnection conn)                                   { ... }
    public void cancelAll(TcpConnection conn) { conn.timers().cancelAll(); }
}
```

定时器业务逻辑从 `TcpTimer`（778 行）拆出到各 handler：
`TcpRetransmitHandler`、`TcpDelAckHandler`、`TcpProbeHandler`、`TcpKeepAliveHandler`。

---

## 8. 方法迁移映射

### 8.1 `TcpInput`（2321 行）→ 4 个类

| 新类 | 来自 TcpInput 的方法 | RFC |
|------|---------------------|-----|
| `TcpSegmentValidator` | `tcp_validate_incoming`, `tcp_sequence`, `tcp_paws_check` | RFC9293 + RFC7323 |
| `TcpAckHandler` | `tcp_ack`, `tcp_ack_update_window`, `tcp_snd_una_update`, `tcp_ack_snd_check` | RFC9293 |
| `TcpDataHandler` | `tcp_data_queue`, `tcp_ofo_queue`, `tcp_prune_ofo_queue`, `tcp_rcv_established` | RFC9293 |
| `Rfc6298RttEstimator` | `tcp_rtt_estimator`, `tcp_set_rto`, `tcp_ack_update_rtt`, `tcp_rcv_rtt_measure` | RFC6298 |
| `NewRenoCongestionControl` | `tcp_cong_avoid`, `tcp_slow_start`, `tcp_fastretrans_alert`, `tcp_enter_fast_recovery`, `tcp_enter_loss` | RFC5681 |
| `Rfc7323TimestampExtension` | `tcp_store_ts_recent`, `tcp_paws_discard`, `tcp_replace_ts_recent` | RFC7323 |

### 8.2 `TcpOutput`（1398 行）→ 3 个类

| 新类 | 来自 TcpOutput 的方法 | RFC |
|------|----------------------|-----|
| `TcpSegmenter` | `tcp_write_xmit`, `tcp_transmit_skb`, `tcp_current_mss`, `tcp_queue_skb`, `tcp_event_new_data_sent` | RFC9293 |
| `TcpRetransmitter` | `__tcp_retransmit_skb`, `tcp_retransmit_skb`, `tcp_send_fin`, `tcp_send_active_reset` | RFC9293 |
| `TlpLossDetector` | `tcp_send_loss_probe`, `tcp_schedule_loss_probe` | RFC8985 |

> `tcp_receive_window`, `tcp_space`, `tcp_adjust_rcv_ssthresh` → `TcpDataHandler` 或 `TcpConnection` 方法

---

## 9. `TcpConnection` 构建（Builder 模式）

Builder 内部结构（关键字段）：

```java
public static final class Builder {
    private Channel                  channel;
    private RttEstimator             rttEstimator       = NoopRttEstimator.INSTANCE;
    private CongestionControl        congestionControl  = NoopCongestionControl.INSTANCE;
    private Consumer<TcpConnection>  retransmitCallback = conn -> {};  // Noop 默认
    private LossDetector             lossDetector       = NoopLossDetector.INSTANCE;
    private TcpTimestampExtension    timestampExt       = NoopTimestampExtension.INSTANCE;

    public Builder channel(Channel channel)                           { this.channel = channel; return this; }
    public Builder rttEstimator(RttEstimator e)                       { this.rttEstimator = e; return this; }
    /** CC 与 retransmitCallback 必须成对设置：CC 决策触发，RFC9293（TcpRetransmitter）执行 */
    public Builder congestionControl(CongestionControl cc,
                                     Consumer<TcpConnection> callback) {
        this.congestionControl  = cc;
        this.retransmitCallback = callback;
        return this;
    }
    public Builder lossDetector(LossDetector d)                       { this.lossDetector = d; return this; }
    public Builder timestampExt(TcpTimestampExtension t)              { this.timestampExt = t; return this; }

    public TcpConnection build() {
        TcpConnection conn = new TcpConnection(channel, rttEstimator, congestionControl,
                                               lossDetector, timestampExt);
        rttEstimator.init(conn);
        congestionControl.init(conn, retransmitCallback);  // ← callback 在此注入
        lossDetector.init(conn);
        timestampExt.init(conn);
        return conn;
    }
}
```

使用示例：

```java
// 完整配置（所有 RFC 扩展启用）
TcpRetransmitter retransmitter = new TcpRetransmitter();   // RFC9293 执行重传
TcpConnection conn = TcpConnection.builder()
    .channel(channel)                              // TcpConnectionChannel 实例（方案 B）
    .rttEstimator(new Rfc6298RttEstimator())
    .congestionControl(new NewRenoCongestionControl(),
        retransmitter::retransmit)                 // CC 触发重传决策，RFC9293 TcpRetransmitter 执行
    .lossDetector(new TlpLossDetector())
    .timestampExt(new Rfc7323TimestampExtension())
    .build();

// 最简配置（仅 RFC9293，所有扩展退化为 Noop）
TcpConnection minimal = TcpConnection.builder()
    .channel(channel)
    .build();   // 未指定扩展自动填充 Noop 实现；retransmitCallback 默认为空 lambda
```

---

## 10. 重构步骤

### Phase 0 — Per-Connection Pipeline 基础设施

采用方案 B（`TcpConnectionChannel`）+ Worker 分发（§6.4），完成：

1. **`TcpConnectionChannel`**：实现 `extends AbstractChannel`，构造函数接受 `assignedWorker`，`isCompatible()` 绑定 Worker，`doWrite()` 跨线程回写父 pipeline，`doClose()` 回投 TUN EventLoop 清理 registry
2. **合并注册表**：`HalfOpenRegistry` + `ConnectionRegistry` → `TcpConnectionRegistry`（普通 HashMap，读写限定在 TUN EventLoop）
3. **`TcpMultiplexHandler`**：构造时接受 `workerGroup`，首次 SYN 按四元组哈希选 Worker，后续包 `worker.execute()` 跨线程投递
4. **`TcpHandshakerFactory`**：从 `TcpHandshaker` 拆出工厂类，`newHandshaker(config, synPkt)`
5. **`TcpHandshakeHandler`**：握手完成后调用 `pipeline.replace()`；重传 SYN 复用已有 handshaker（幂等）
6. **占位 handler**：`TcpEstablishedHandler` / `TcpActiveCloseHandler` / `TcpPassiveCloseHandler` 先空实现占位

> Phase 0 结束：三次握手可走通，传输 / 挥手逻辑由后续 Phase 补充。

### Phase 1 — 基础设施（无行为变化）

1. **提取扩展接口**：`RttEstimator` / `CongestionControl` / `LossDetector` / `TcpTimestampExtension` / `KeepAliveExtension`，各含 Noop 实现
2. **重构定时器**：per-conn `TcpConnectionTimers` slot 替代 `TcpTimer` 全局 Map；修复连接关闭时 `cancelAll` 缺失
3. **引入 `TcpConnection`**：`TcpSock` 字段按关注点分组，暂留 package-private，逐步收紧

### Phase 2 — 拆分 TcpInput

4. 提取 `Rfc6298RttEstimator`，`TcpInput` 改为通过接口调用
5. 提取 `NewRenoCongestionControl`
6. 提取 `Rfc7323TimestampExtension`
7. 剩余拆分：`TcpInput` → `TcpSegmentValidator` + `TcpAckHandler` + `TcpDataHandler`

### Phase 3 — 拆分 TcpOutput

8. 提取 `TlpLossDetector`，同步取消注释 `tcp_schedule_loss_probe` 调用（TCP.TLP.TODO.md）
9. 剩余拆分：`TcpOutput` → `TcpSegmenter` + `TcpRetransmitter`

### Phase 4 — 命名规范化与收尾

10. **`TcpConnectionState` 重命名**（删除 `TCP_` 前缀）：

    | 旧名（TcpState） | 新名（TcpConnectionState） |
    |-----------------|--------------------------|
    | `TCP_ESTABLISHED` | `ESTABLISHED` |
    | `TCP_SYN_SENT` | `SYN_SENT` |
    | `TCP_SYN_RECV` | `SYN_RECEIVED` |
    | `TCP_FIN_WAIT1` | `FIN_WAIT_1` |
    | `TCP_FIN_WAIT2` | `FIN_WAIT_2` |
    | `TCP_CLOSE_WAIT` | `CLOSE_WAIT` |
    | `TCP_CLOSING` | `CLOSING` |
    | `TCP_LAST_ACK` | `LAST_ACK` |
    | `TCP_TIME_WAIT` | `TIME_WAIT` |
    | `TCP_CLOSE` | `CLOSED` |
    | `TCP_LISTEN` | `LISTEN` |

    > `TCP_NEW_SYN_RECV`、`TCP_BOUND_INACTIVE`、`TCP_MAX_STATES` 是 Linux 内核实现细节，不引入。

11. 序列号比较工具统一迁移到 `internal/TcpSequence`（`after()` / `before()` / `between()`）
12. `inet_connection_sock` / `tcp_request_sock` 字段合并入 `TcpConnection`
13. 收紧 `TcpConnection` 字段访问权限：package-private → private + 访问器
14. **S1 修复**（TCP.TODO.01.md）：重构 `TcpHandshaker` 时同步加入阶段判断和 pkt 生命周期管理

---

## 11. 重构约束

- **新旧代码物理隔离**：所有新代码放入 `com.github.pangolin.routing.acceptor.tun.net.v2` 包，不修改现有包（`tun.net`）中的任何文件；新旧实现可同时编译和运行，切换由上层装配点（`TunAcceptor` 或 pipeline 配置）控制
- **每个 Phase 独立可合并**：每步结束后代码必须可编译，现有功能不回退
- **禁止提前设计**：不为"将来可能需要"的场景增加抽象层，只为已知的 RFC 扩展点设计接口
- **Netty 线程模型不变**：所有 `TcpConnection` 的状态修改必须在其绑定的 EventLoop 中执行，不引入 `synchronized` 或额外锁
- **不改变外部 API**：`TcpMultiplexHandler` / `Tcp4MultiplexHandler` 的对外接口保持不变，重构在内部进行
