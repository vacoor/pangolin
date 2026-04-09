# TCP 协议栈 Java 重构方案

> 版本：v3.1 | 更新日期：2026-04-09
> 分析基准：branch `feature/v1.2.3-ai-lb`
> 目标：OOP 化、关注点分离、RFC9293 之外的扩展可插拔

---

## 更新日志

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
      │                            │
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
    FIN_WAIT_1 + FIN-ACK → FIN_WAIT_2
    FIN_WAIT_2 + FIN     → TIME_WAIT，调度 2MSL 定时器
    CLOSING    + ACK     → TIME_WAIT，调度 2MSL 定时器
    TIME_WAIT  到期      → registry.deregister(fourTuple)

# 被动关闭（对端 FIN）
TcpPassiveCloseHandler.initiateClose()   # 应用层 close() 时调用
    └─ TcpSegmenter.sendFin(conn) → conn.state(LAST_ACK)

TcpPassiveCloseHandler.channelRead(ctx, pkt)
    LAST_ACK + ACK → registry.deregister(fourTuple)
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

```
tcp/
├── pipeline/                          # per-connection pipeline 基础设施（见 §6）
│   ├── 方案 A（自定义轻量 pipeline）
│   │   ├── TcpConnectionPipeline      # 对标 ChannelPipeline（~120 行）
│   │   ├── TcpConnectionContext       # 对标 ChannelHandlerContext（~80 行）
│   │   └── TcpConnectionHandler       # 对标 ChannelInboundHandler
│   └── 方案 B（自实现 Netty Channel）
│       └── TcpConnectionChannel       # extends AbstractChannel（~230 行）
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

每条 TCP 连接对应一个 `TcpConnectionChannel`（`extends AbstractChannel`），注册在 TUN Channel
的 EventLoop 上，使用**真实** Netty `ChannelPipeline`。
参考先例：Netty HTTP/2 的 `AbstractHttp2StreamChannel`，同样是多路复用在单一物理 Channel 上的虚拟子 Channel。

**TcpConnectionChannel**

```java
public class TcpConnectionChannel extends AbstractChannel {

    private final Channel              tunChannel;   // 父 TUN Channel
    private final FourTuple            fourTuple;
    private       ChannelHandlerContext parentCtx;   // 用于 doWrite() 写入父 pipeline
    private volatile boolean           active;

    public TcpConnectionChannel(Channel tunChannel, FourTuple fourTuple) {
        super(null);
        this.tunChannel = tunChannel;
        this.fourTuple  = fourTuple;
    }

    /** 在注册前由 TcpMultiplexHandler 设置 */
    public void setParentContext(ChannelHandlerContext parentCtx) {
        this.parentCtx = parentCtx;
    }

    @Override
    protected AbstractUnsafe newUnsafe() { return new TcpConnectionUnsafe(); }

    @Override
    protected boolean isCompatible(EventLoop loop) {
        // 默认：共享 TUN Channel 的 EventLoop，所有连接单线程无锁。
        // 若需 Worker 分发（见 §6.4），改为接受 workerGroup 中的任意 EventLoop。
        return loop == tunChannel.eventLoop();
    }

    @Override
    protected void doRegister() {
        // 虚拟 Channel，无 selector fd。
        // 参考 AbstractHttp2StreamChannel：注册后触发 channelActive，
        // 让 TcpHandshakeHandler 可在 channelActive() 中做初始化。
        active = true;
        pipeline().fireChannelRegistered();
        pipeline().fireChannelActive();
    }

    @Override
    protected void doDeregister() {
        // 参考 Http2StreamChannel：注销时触发 channelInactive，
        // 让各阶段 handler 在 channelInactive() 中取消定时器、释放 buffer。
        active = false;
        pipeline().fireChannelInactive();
        pipeline().fireChannelUnregistered();
    }

    @Override protected void doBind(SocketAddress local) { /* no-op */ }
    @Override protected void doDisconnect()              { doClose(); }

    @Override
    protected void doClose() {
        if (!active) return;
        active = false;
        // 从 TcpConnectionRegistry 注销，触发 doDeregister()
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
        // 线程安全说明：Netty Channel.write() / ChannelHandlerContext.write() 本身线程安全——
        // 若调用方不在目标 EventLoop，Netty 内部自动将写操作提交到 TUN EventLoop 的任务队列。
        // 因此 Worker 分发方案（§6.4）下 doWrite() 无需额外跨线程包装。
        for (;;) {
            Object msg = buf.current();
            if (msg == null) break;
            parentCtx.write(msg);
            buf.remove();
        }
        parentCtx.flush();
    }

    @Override protected SocketAddress localAddress0()  { return fourTuple.local(); }
    @Override protected SocketAddress remoteAddress0() { return fourTuple.remote(); }
    @Override public boolean isOpen()   { return active; }
    @Override public boolean isActive() { return active; }
    @Override public ChannelMetadata metadata() { return new ChannelMetadata(false); }

    /**
     * 将入站包注入本连接 pipeline，由 TcpMultiplexHandler 调用。
     * 命名对标 AbstractHttp2StreamChannel.fireChildRead()，遵循 Netty fire* 约定。
     * 必须在本 Channel 的 EventLoop 线程调用。
     */
    public void fireChildRead(TcpPacketBuf pkt) {
        assert eventLoop().inEventLoop();
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
 * 构造时注入 childHandler（ChannelInitializer），对标 Http2MultiplexHandler(childHandler)。
 * 收到首次 SYN 时创建 TcpConnectionChannel 并装载 childHandler。
 */
@ChannelHandler.Sharable
public class TcpMultiplexHandler extends ChannelInboundHandlerAdapter {

    private final TcpConfig      config;
    private final ChannelHandler childHandler;

    public TcpMultiplexHandler(TcpConfig config, ChannelHandler childHandler) {
        this.config      = config;
        this.childHandler = childHandler;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        TcpPacketBuf pkt    = (TcpPacketBuf) msg;
        FourTuple fourTuple = pkt.fourTuple();

        TcpConnectionChannel connCh = registry.get(fourTuple);
        if (connCh != null) {
            connCh.fireChildRead(pkt);
            return;
        }

        if (!pkt.isSyn()) { sendRst(ctx, pkt); return; }

        // 首次 SYN：创建子 Channel，装载 childHandler
        connCh = new TcpConnectionChannel(ctx.channel(), fourTuple);
        connCh.setParentContext(ctx);
        connCh.pipeline().addLast(childHandler);
        ctx.channel().eventLoop().register(connCh);  // doRegister() → channelActive()
        registry.put(fourTuple, connCh);
        connCh.fireChildRead(pkt);
    }
}
```

使用侧：

```java
// TUN Channel pipeline 配置，对标 Http2MultiplexHandler 的写法
tunPipeline.addLast(new TcpMultiplexHandler(config,
    new ChannelInitializer<TcpConnectionChannel>() {
        protected void initChannel(TcpConnectionChannel ch) {
            ch.pipeline().addLast("handshake",
                new TcpHandshakeHandler(handshakerFactory, config));
        }
    }
));
```

**方案 B 评估**

| 项目 | 说明 |
|------|------|
| 实现规模 | `TcpConnectionChannel` ~230 行，`TcpMultiplexHandler` ~80 行 |
| EventLoop 集成 | 默认 `isCompatible()` 共用 TUN EventLoop（单线程无锁）；可扩展为 Worker 分发（见 §6.4） |
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
| EventLoop 访问 | `ctx.channel()` 返回 TUN Channel（间接） | `ctx.channel()` 返回 `TcpConnectionChannel`，`eventLoop()` 直接可用 |
| 出站写路径 | `ctx.writeAndFlush()` → `tunChannel` | `doWrite()` → `parentCtx.write()` + 批量 flush |
| 生命周期事件 | 需手动触发 | `channelActive()` / `channelInactive()` 自动触发 |
| Netty 生态 handler 复用 | ✗ | ✓ |
| 流控扩展点 | 需自行实现 | `doBeginRead()` 天然扩展点 |

---

### 6.4 EventLoop 线程模型分析

#### HTTP/2 类比的局限性

方案 B 的 `isCompatible()` 设计参考了 `AbstractHttp2StreamChannel`，但两者的多路复用层级不同：

```
HTTP/2（适配）：
  Client-A → TCP连接1 → EventLoop-1  { Stream-1, Stream-2, ... }
  Client-B → TCP连接2 → EventLoop-2  { Stream-1, Stream-2, ... }
  可水平扩展：N 连接 → N 个 EventLoop 线程

TUN（不适配）：
  所有应用 → TUN设备(唯一) → EventLoop(唯一)  { conn-A, conn-B, conn-C, ... }
  无法水平扩展：全机所有连接 → 始终 1 个线程
```

HTTP/2 中每条物理连接独占一个 EventLoop，多连接天然分散到 EventLoopGroup 的多个线程；TUN 是单一 OS 设备，所有连接共用同一个 EventLoop，无法通过增加连接数来分散负载。

#### 选项 1：维持单 EventLoop（当前默认）

所有连接共享 TUN EventLoop，无锁，适合轻载或连接数较少的场景。

#### 选项 2：Worker EventLoop 分发

```
TUN EventLoop（唯一，仅做路由）
     │  channelRead(pkt)
     │  1. 按 fourTuple 查 registry
     │  2. pkt.retain()
     └─► workerLoop.execute(() -> connCh.fireChildRead(pkt); pkt.release())

Worker-0  { conn-A, conn-D, ... }   ← fourTuple.hashCode() % N == 0
Worker-1  { conn-B, conn-E, ... }   ← fourTuple.hashCode() % N == 1
Worker-2  { conn-C, conn-F, ... }   ← fourTuple.hashCode() % N == 2
```

**TcpMultiplexHandler 改动**（仅路由层变化）：

```java
@Override
public void channelRead(ChannelHandlerContext ctx, Object msg) {
    TcpPacketBuf pkt = (TcpPacketBuf) msg;
    FourTuple fourTuple = pkt.fourTuple();

    TcpConnectionChannel connCh = registry.get(fourTuple);
    if (connCh == null) {
        if (!pkt.isSyn()) { sendRst(ctx, pkt); return; }
        // 按四元组 hash 选定 Worker，同一连接始终落同一线程
        EventLoop worker = workerGroup.next(Math.abs(fourTuple.hashCode()));
        connCh = new TcpConnectionChannel(ctx.channel(), fourTuple, worker);
        connCh.pipeline().addLast(childHandler);
        worker.register(connCh);          // doRegister() → channelActive()
        registry.put(fourTuple, connCh);  // registry 写只在 TUN EventLoop
    }

    final TcpConnectionChannel ch = connCh;
    pkt.retain();
    ch.eventLoop().execute(() -> {        // TUN线程 ≠ Worker线程，始终跨线程投递
        try { ch.fireChildRead(pkt); }
        finally { pkt.release(); }
    });
}
```

**`TcpConnectionChannel` 改动**（仅两处）：

```java
// 1. isCompatible() 放开限制
@Override
protected boolean isCompatible(EventLoop loop) {
    return loop == assignedWorker;  // 接受构造时分配的 Worker，而非 TUN EventLoop
}

// 2. doWrite() 无需改动
// parentCtx.write() 本身线程安全：Netty 内部检测跨线程后自动提交到 TUN EventLoop 任务队列
@Override
protected void doWrite(ChannelOutboundBuffer buf) throws Exception {
    for (;;) {
        Object msg = buf.current();
        if (msg == null) break;
        parentCtx.write(msg);   // 跨线程安全，Netty 自动 execute()
        buf.remove();
    }
    parentCtx.flush();
}
```

**连接关闭时的 registry 清理**（回投 TUN EventLoop 保持单写者）：

```java
@Override
protected void doClose() {
    if (!active) return;
    active = false;
    tunChannel.eventLoop().execute(() -> registry.remove(fourTuple));
}
```

**选项对比：**

| | 选项 1（单 EventLoop） | 选项 2（Worker 分发） |
|---|---|---|
| `isCompatible()` | `loop == tunChannel.eventLoop()` | `loop == assignedWorker` |
| `TcpMultiplexHandler` | 直接调用 `fireChildRead()` | `workerLoop.execute()` 投递 |
| `doWrite()` | 不变 | **不变**（`parentCtx.write()` 已线程安全） |
| registry | 单线程 `HashMap` | 单线程 `HashMap`（写操作回投 TUN） |
| timer | `tunLoop.schedule()` | `workerLoop.schedule()`（自动） |
| 适用场景 | 轻载、连接数少 | 高并发、多核利用 |

---

## 7. 核心接口与数据模型

### 7.1 可插拔扩展接口

```java
// RFC 6298：RTO 自适应，per-conn 状态通过 ConnectionKey 存于 TcpConnection.attributes
public interface RttEstimator {
    void init(TcpConnection conn);
    /** 加入 RTT 采样，rttUs 单位微秒 */
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
        CongestionState caState = CongestionState.OPEN;
        int highSeq;
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
            if (++s.dupacks == 3 && s.caState == CongestionState.OPEN) {
                s.ssthresh = Math.max(s.cwnd / 2, 2);
                s.cwnd     = s.ssthresh + 3;
                s.highSeq  = conn.sndNxt();
                s.caState  = CongestionState.RECOVERY;
                s.retransmitCallback.accept(conn);
            } else if (s.caState == CongestionState.RECOVERY) {
                s.cwnd++;
            }
        } else {
            if (s.caState == CongestionState.RECOVERY
                    && TcpSequence.after(conn.sndUna(), s.highSeq)) {
                s.cwnd = s.ssthresh;
                s.caState = CongestionState.OPEN;
            }
            s.dupacks = 0;
            s.cwnd += (s.cwnd < s.ssthresh)
                ? ackedSegments
                : Math.max(1, ackedSegments / s.cwnd);
        }
    }

    @Override
    public void onTimeout(TcpConnection conn) {
        RenoState s = conn.getAttr(KEY);
        s.ssthresh = Math.max(s.cwnd / 2, 2);
        s.cwnd = 1; s.dupacks = 0; s.caState = CongestionState.LOSS;
    }

    @Override public int     cwnd(TcpConnection conn)         { return conn.getAttr(KEY).cwnd; }
    @Override public boolean isInRecovery(TcpConnection conn) {
        RenoState s = conn.getAttr(KEY);
        return s != null && s.caState != CongestionState.OPEN;
    }
    @Override public void onConnectionClosed(TcpConnection conn) { conn.removeAttr(KEY); }
}
```

`TcpAckProcessor`（RFC9293 核心，不含拥塞控制逻辑）：

```java
public class TcpAckProcessor {
    public int onAck(TcpConnection conn, TcpPacketBuf pkt) {
        int prevUna    = conn.sndUna();
        int ackedBytes = tcpSndUnaUpdate(conn, pkt.ackSeq());
        boolean advanced = TcpSequence.after(conn.sndUna(), prevUna);
        int ackedSegs    = ackedBytes / conn.mss();

        conn.rttEstimator().addSample(conn, rttSample(conn, pkt));          // RFC6298
        conn.congestionControl().onAck(conn, ackedSegs, advanced);          // RFC5681
        conn.lossDetector().onAck(conn, pkt.ackSeq(), computeAckFlags(conn, pkt)); // RFC8985
        return 0;
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
    RETRANSMIT,         // Linux: ICSK_TIME_RETRANS
    DELAYED_ACK,        // Linux: ICSK_TIME_DACK
    ZERO_WINDOW_PROBE,  // Linux: ICSK_TIME_PROBE0
    TLP_PROBE,          // Linux: ICSK_TIME_LOSS_PROBE
    REORDER_TIMEOUT     // Linux: ICSK_TIME_REO_TIMEOUT
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

```java
// 完整配置（所有 RFC 扩展启用）
TcpConnection conn = TcpConnection.builder()
    .channel(channel)                              // 方案A：tunChannel；方案B：TcpConnectionChannel
    .rttEstimator(new Rfc6298RttEstimator())
    .congestionControl(new NewRenoCongestionControl())
    .lossDetector(new TlpLossDetector())
    .timestampExt(new Rfc7323TimestampExtension())
    .build();   // build() 内部依次调用各扩展的 init(conn)

// 最简配置（仅 RFC9293，所有扩展退化为 Noop）
TcpConnection minimal = TcpConnection.builder()
    .channel(channel)
    .build();   // 未指定的扩展自动填充对应 Noop 实现
```

---

## 10. 重构步骤

### Phase 0 — Per-Connection Pipeline 基础设施

选定方案 A 或方案 B（见 §6），完成：

1. **pipeline 基础设施**：方案 A 实现 `TcpConnectionHandler` / `TcpConnectionContext` / `TcpConnectionPipeline`；方案 B 实现 `TcpConnectionChannel`
2. **合并注册表**：`HalfOpenRegistry` + `ConnectionRegistry` → `TcpConnectionRegistry`
3. **`TcpHandshakerFactory`**：从 `TcpHandshaker` 拆出工厂类，`newHandshaker(config, synPkt)`
4. **`TcpHandshakeHandler`**：握手完成后调用 `pipeline.replace()`；重传 SYN 复用已有 handshaker（幂等）
5. **占位 handler**：`TcpEstablishedHandler` / `TcpActiveCloseHandler` / `TcpPassiveCloseHandler` 先空实现占位
6. **更新 `TcpMultiplexHandler`**：首次 SYN 创建 pipeline，后续包直接分发

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

- **每个 Phase 独立可合并**：每步结束后代码必须可编译，现有功能不回退
- **禁止提前设计**：不为"将来可能需要"的场景增加抽象层，只为已知的 RFC 扩展点设计接口
- **Netty 线程模型不变**：所有 `TcpConnection` 的状态修改必须在其绑定的 EventLoop 中执行，不引入 `synchronized` 或额外锁
- **不改变外部 API**：`TcpMultiplexHandler` / `Tcp4MultiplexHandler` 的对外接口保持不变，重构在内部进行
