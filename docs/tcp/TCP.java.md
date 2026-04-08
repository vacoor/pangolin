# TCP 协议栈 Java 重构方案

> 版本：v2.1 | 更新日期：2026-04-09
> 分析基准：branch `feature/v1.2.3-ai-lb`
> 目标：OOP 化、关注点分离、RFC9293 之外的扩展可插拔

---

## 更新日志

### v2.1 — 2026-04-09　接口 / 类 / 方法命名专项优化

| 类别 | 旧名 | 新名 | 原因 |
|------|------|------|------|
| 类 | `TcpSegmentizer` | `TcpSegmenter` | `segmentizer` 不是真实英语单词，`segmenter` 是标准形式 |
| 类 | `SysctlOptions` | `TcpConfig` | 去除 Linux 内核专属词 `sysctl`，Java 通用称法更清晰 |
| 方法 | `RttEstimator.update(conn, rttUs)` | `addSample(conn, rttUs)` | `update` 语义模糊；实际动作是「加入一条 RTT 测量样本」 |
| 方法 | `TcpTimestampExtension.pawsDiscard(conn, tsval)` | `isPawsRejected(conn, tsval)` | Java 布尔谓词规范：返回 `boolean` 的方法名应以 `is-` 开头 |
| 方法 | `TcpConnection.attr(key)` | `getAttr(key)` | 同名重载 get / set 易混淆，显式命名区分语义 |
| 方法 | `TcpConnection.attr(key, value)` | `setAttr(key, value)` | 同上 |
| 方法 | `ConnectionKey.newKey(name)` | `ConnectionKey.of(name)` | 现代 Java 工厂方法惯例：`Optional.of()`、`Map.of()` 同风格 |
| 方法 | `TcpTimerScheduler.scheduleDelAck()` | `scheduleDelayedAck()` | 缩写 `DelAck` 不应出现在 Java 公共方法名中，展开为完整词 |
| 字段 | `TcpConnectionTimers.delackTimer` | `delayedAckTimer` | 与 `scheduleDelayedAck()` 保持命名一致 |

### v2.0 — 2026-04-09　结构重组与全面优化

- 节号统一为阿拉伯数字（0. / 1. / 2. …），消除中文序数与阿拉伯子节混用
- 合并原 §0.3（入站数据流）与 §0.4（定时器数据流）为单节 §0.3
- 原 §十（类规模预期）内嵌至 §4 包结构树，删除独立章节
- §2 设计目标增加「非目标」4 条，明确范围边界
- `TcpTimerManager` → `TcpTimerScheduler`（Manager 含义过宽，Scheduler 描述无状态调度器）
- `TimerType` 枚举值全拼：`RETRANS/DACK/PROBE0/REO` → `RETRANSMIT/DELAYED_ACK/ZERO_WINDOW_PROBE/REORDER_TIMEOUT`
- `CA_OPEN/CA_RECOVERY/CA_LOSS` int 常量 → `CongestionState` enum（OPEN / RECOVERY / LOSS）
- `getSndUna()` / `getSndNxt()` → `sndUna()` / `sndNxt()`（去冗余 `get` 前缀，对齐 Netty 风格）
- `advanceSndUna(int ack)` → `acknowledgeUpTo(int ackSeq)`（名称表达语义而非机制）
- `rto()` → `rtoMs()`（单位内嵌方法名，避免 jiffies 抽象泄漏）
- `pawsDiscard(conn)` → 增加 `int tsval` 参数（PAWS 检查需入包时间戳）
- `LossDetector.sendProbe(Channel, conn)` → `sendProbe(conn)`（Channel 经 `conn.channel()` 获取，接口参数统一）
- `TcpStateMachine.process()` 返回类型注明为 TcpDropReason 码
- `TcpConnection` 增加 `channel()` / `eventLoop()` 访问器与 `close()` 生命周期方法
- Phase 1 修正：直接引入 per-conn slots，删除「改为 `ConcurrentMap<TimerKey, Future>`」中间态
- Phase 4 增加 `TcpConnectionState` 完整重命名对照表，明确 Linux 特有状态不引入
- `KeepAliveExtension` 补入 §3 RFC 表与 §4 包结构
- `ConnectionKey.newKey()` Javadoc 增加唯一性保证说明

---

## 0. 架构总览

### 0.1 当前架构

```
┌─────────────────────────────────────────────────────────────────┐
│                       TUN 设备 I/O                              │
└──────────────────────────────┬──────────────────────────────────┘
                               │ IpPacketBuf
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│              TcpDemultiplexer（God Facade，705 行）              │
│  synRegistry │ establishedRegistry │ TcpInput │ TcpOutput │ TcpTimer │
└───────┬───────────────────────────────────────────────┬─────────┘
        │                                               │
        ▼                                               ▼
┌───────────────────┐                      ┌───────────────────────┐
│  TcpInput（2321行）│                      │ TcpOutput（1398行）   │
│ ─────────────────  │                      │ ─────────────────────  │
│ • 状态机           │◄────── TcpSock ──────►│ • 分段/发送           │
│ • ACK 处理         │   （615行贫血模型）    │ • 重传                │
│ • RTT 采样（RFC6298）│  50+ 个 public 字段  │ • 窗口探测            │
│ • 拥塞控制（RFC5681）│                     │ • TLP 空实现（RFC8985）│
│ • PAWS（RFC7323）  │                      │                       │
│ • OFO 队列         │                      │                       │
└───────────────────┘                      └───────────────────────┘
        ▲                                               ▲
        └────────────────── TcpTimer ───────────────────┘
                         （778行：全局 ConcurrentMap<Runnable,Future> + 所有 handler）
```

**问题一览**：
- `TcpInput` / `TcpOutput` 是 God Class，RFC 关注点完全混合
- `TcpSock` 是贫血模型，50+ 个 public 字段被任意修改
- `TcpTimer` 用全局 `ConcurrentMap<Runnable, Future>` 管理所有连接的所有定时器
- 无任何可插拔扩展点，RFC6298 / 5681 / 7323 / 8985 均硬编码
- `TcpState` 延用 Linux 风格命名（`TCP_ESTABLISHED`、`TCP_FIN_WAIT1` 等），与 Java 约定不符

---

### 0.2 目标架构

```
┌─────────────────────────────────────────────────────────────────┐
│                       TUN 设备 I/O                              │
└──────────────────────────────┬──────────────────────────────────┘
                               │ IpPacketBuf
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│              TcpDemultiplexer（轻量 Facade）                     │
│              HalfOpenRegistry │ ConnectionRegistry               │
└──────────┬────────────────────────────────────────┬─────────────┘
           │ 解析 IP+TCP 头，转 TcpPacketBuf          │
           ▼                                        ▼
┌──────────────────────────────────┐   ┌───────────────────────────┐
│         TcpConnection            │   │     TcpHandshaker         │
│   （富域模型，private 字段）       │   │  （握手状态机，阶段判断）   │
│ ┌──────────────────────────────┐ │   └───────────────────────────┘
│ │   TcpConnectionTimers        │ │
│ │  writeSlot│delackSlot│...    │ │   ┌───────────────────────────┐
│ └──────────────────────────────┘ │   │     TcpTimerScheduler     │
│ ┌──────────────────────────────┐ │   │  EventLoop.schedule()     │
│ │ Map<ConnectionKey<?>, Object> │ │◄──┤  per-conn ScheduledFuture │
│ │  （RFC 扩展状态存储）          │ │   │  slots（无全局 Map）       │
│ └──────────────────────────────┘ │   └───────────────────────────┘
│ ┌──────────────────────────────┐ │
│ │   RFC 扩展（组合注入）        │ │
│ │  RttEstimator                │ │
│ │  CongestionControl           │ │
│ │  LossDetector                │ │
│ │  TcpTimestampExtension       │ │
│ └──────────────────────────────┘ │
└──────────────────────────────────┘
           │ 入站                        出站
           ▼                             ▼
┌──────────────────┐         ┌──────────────────────┐
│  RFC9293 接收路径 │         │   RFC9293 发送路径    │
│ ┌──────────────┐ │         │ ┌────────────────────┐│
│ │TcpReceiver   │ │         │ │TcpSegmenter      ││
│ │TcpAckProcessor│ │         │ │TcpRetransmitter    ││
│ │TcpStateMachine│ │         │ └────────────────────┘│
│ └──────────────┘ │         └──────────────────────┘
└──────────────────┘
           │ 调用扩展接口
           ▼
┌──────────────────────────────────────────────────────┐
│               可插拔 RFC 扩展层（ext/）               │
│                                                      │
│  ┌─────────────┐  ┌──────────────────┐               │
│  │RttEstimator │  │CongestionControl │               │
│  │(RFC 6298)   │  │(RFC 5681 NewReno)│               │
│  └─────────────┘  └──────────────────┘               │
│  ┌──────────────────┐  ┌────────────────────────┐    │
│  │TcpTimestampExt   │  │LossDetector            │    │
│  │(RFC 7323 PAWS)   │  │(RFC 8985 TLP)          │    │
│  └──────────────────┘  └────────────────────────┘    │
│  每个扩展均有 Noop 实现，关闭时退化为 RFC9293 兜底      │
└──────────────────────────────────────────────────────┘
```

---

### 0.3 核心数据流

> `TcpDemultiplexer` 收到 `IpPacketBuf`（IP 层原始包），解析 IP + TCP 头后得到 `TcpPacketBuf`（TCP 层视图），交给下游处理器。

**入站包处理**

```
TUN 读取 IpPacketBuf
    │
    ▼
TcpDemultiplexer.dispatch(pkt: IpPacketBuf)
    │ 解析 IP+TCP 头，得 TcpPacketBuf
    │ 按 <srcIP:srcPort:dstIP:dstPort> 查找
    ├─► HalfOpenRegistry → TcpHandshaker.onSyn(pkt)           [SYN 握手]
    └─► ConnectionRegistry → TcpStateMachine.process(conn, pkt) [已建立]
            │
            ├─ validate(conn, pkt)      # RFC9293：序列号、RST、PAWS（调 timestampExt）
            ├─ TcpAckProcessor.onAck    # RFC9293 + RttEstimator + CongestionControl
            ├─ TcpReceiver.onData       # RFC9293：数据入队、OFO
            └─ postProcess             # ACK 发送检查、发送队列推进
```

**定时器触发**

```
EventLoop tick（RTO 到期）
    └─► TcpRetransmitHandler.onTimeout(conn)
            ├─ conn.rttEstimator().backoff(conn)          # RFC6298 指数退避
            ├─ conn.congestionControl().onTimeout(conn)   # RFC5681 cwnd 降为 1
            └─ TcpRetransmitter.retransmit(conn)          # 重传队头 skb

EventLoop tick（DelAck 到期）
    └─► TcpDelAckHandler.onTimeout(conn)
            └─ TcpSegmenter.sendAck(conn)

# 所有定时器均通过 EventLoop.schedule()
# per-conn ScheduledFuture slot 直接存于 TcpConnectionTimers，无全局 Map
```

---

## 1. 现状诊断

### 1.1 规模与职责

| 文件 | 行数 | 当前职责 |
|------|------|---------|
| `TcpInput.java` | 2321 | 接收路径全部逻辑：ACK 处理、RTT 采样、拥塞控制、数据入队、状态机 |
| `TcpOutput.java` | 1398 | 发送路径全部逻辑：分段、窗口、重传、探测、定时器 |
| `TcpTimer.java` | 778 | 定时器调度 + 所有定时器 handler |
| `TcpDemultiplexer.java` | 705 | 连接分发 + SYN / ESTABLISHED 注册表 + 持有 Input / Output / Timer |
| `TcpSock.java` | 615 | 纯数据结构（贫血模型），50+ 个 public 字段 |
| `TcpHandshaker.java` | 317 | 三次握手状态机 |

### 1.2 核心问题

1. **God Class**：`TcpInput` 同时负责 RTT、拥塞控制、OFO 队列、状态机，每个关注点都没有独立边界
2. **贫血模型**：`TcpSock` 是纯 public 字段集合，没有行为封装，所有算法散落在 Input / Output 中直接修改字段
3. **RFC 混杂**：RFC9293 核心逻辑与 RFC5681（拥塞控制）、RFC7323（时间戳 / PAWS）、RFC8985（TLP）耦合在同一方法链中，无法单独剥离
4. **命名混乱**：`TcpState` 延用 Linux C 风格（`TCP_ESTABLISHED`、`TCP_FIN_WAIT1`），`TcpTimer` 的 `ConcurrentMap<Runnable, Future>` 用匿名 lambda 作 key，无可读性
5. **无扩展点**：拥塞控制算法、RTT 估算策略、丢包检测策略均硬编码在方法内

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
- 不改变 `TcpDemultiplexHandler` / `Tcp4DemultiplexHandler` 的对外接口
- 不合并 `TcpReceiveBuffer` 与 `TcpSendBuffer`（接收侧乱序重组 vs 发送侧排队与重传，语义独立）

---

## 3. RFC 关注点归类

### RFC9293（核心，不可剥离）

- 连接状态机（CLOSED / SYN_SENT / SYN_RECEIVED / ESTABLISHED / FIN_WAIT_1 / FIN_WAIT_2 / CLOSE_WAIT / CLOSING / LAST_ACK / TIME_WAIT）
- 序列号管理（SND.UNA、SND.NXT、RCV.NXT）
- 发送窗口管理（SND.WND）
- 接收窗口管理（RCV.WND、RCV.WUP）
- 三次握手（SYN / SYN-ACK / ACK）
- 数据分段与重组（MSS、OFO 队列）
- 基础重传（固定 RTO 兜底）
- RST / FIN 处理

### 可插拔 RFC 扩展

| RFC | 功能 | 接口名 |
|-----|------|--------|
| RFC 6298 | RTO 自适应（SRTT / RTTVAR / Karn 算法） | `RttEstimator` |
| RFC 5681 | 拥塞控制（慢启动、拥塞避免、快速重传 / 恢复） | `CongestionControl` |
| RFC 7323 | TCP 时间戳 + PAWS | `TcpTimestampExtension` |
| RFC 8985 | RACK / TLP 尾部丢包探测 | `LossDetector` |
| RFC 2018 | SACK（待实现；需扩展 `TcpRetransmitter` 接口，与 CC 有交互） | `SackExtension` |
| RFC 1122 §4.2.3.6 | Keep-Alive | `KeepAliveExtension` |

---

## 4. 目标包结构

```
tcp/
├── connection/
│   ├── TcpConnection          # 富域模型（~250 行）
│   ├── TcpSendBuffer          # write queue + rtx queue 合并封装（~150 行）
│   ├── TcpReceiveBuffer       # 接收缓冲 + OFO 队列封装（~100 行）
│   └── TcpConnectionState     # 连接状态枚举，替代 TcpState
│                              #   删除 TCP_ 前缀：ESTABLISHED / FIN_WAIT_1 / SYN_RECEIVED 等
│
├── core/
│   ├── TcpStateMachine        # 连接状态机（Template Method，~300 行）
│   ├── TcpSegmenter         # 发送路径：分段、窗口检查（~350 行）
│   ├── TcpReceiver            # 接收路径：校验、序列号、数据入队（~400 行）
│   ├── TcpRetransmitter       # 重传逻辑（~200 行）
│   └── TcpAckProcessor        # ACK 处理（~350 行）
│
├── timer/
│   ├── TcpTimerScheduler      # 无状态调度器，可被多个 TcpConnection 共享（~80 行）
│   ├── TcpConnectionTimers    # per-conn ScheduledFuture slot（writeTimer / delayedAckTimer / keepaliveTimer）
│   └── TimerType              # 枚举：RETRANSMIT / DELAYED_ACK / ZERO_WINDOW_PROBE / TLP_PROBE / REORDER_TIMEOUT
│
├── handshake/
│   ├── TcpHandshaker          # 握手状态机（~200 行）
│   └── HalfOpenRegistry       # SYN 半连接表，替代 synRegistry
│
├── demux/
│   ├── TcpDemultiplexer       # Facade：分发入站 IpPacketBuf，解析后转 TcpPacketBuf（~100 行）
│   └── ConnectionRegistry     # ESTABLISHED 连接表，替代 establishedRegistry
│
├── ext/
│   ├── rtt/
│   │   ├── RttEstimator
│   │   ├── Rfc6298RttEstimator    # RFC6298 实现（~150 行）
│   │   └── NoopRttEstimator       # 退化实现（固定 RTO）
│   ├── cc/
│   │   ├── CongestionControl
│   │   ├── NewRenoCongestionControl   # RFC5681 实现（~200 行）
│   │   └── NoopCongestionControl
│   ├── loss/
│   │   ├── LossDetector
│   │   ├── TlpLossDetector        # RFC8985 实现（~100 行）
│   │   └── NoopLossDetector
│   ├── timestamp/
│   │   ├── TcpTimestampExtension
│   │   ├── Rfc7323TimestampExtension  # RFC7323 实现（~100 行）
│   │   └── NoopTimestampExtension
│   └── keepalive/
│       ├── KeepAliveExtension
│       ├── Rfc1122KeepAliveExtension  # RFC1122 §4.2.3.6 实现
│       └── NoopKeepAliveExtension
│
└── internal/
    ├── TcpConstants
    ├── TcpConfig
    ├── TcpOptionCodec
    ├── TcpSequence            # after() / before() / between() 序列号比较工具
    └── TcpUtils
```

---

## 5. 核心接口与数据模型

### 5.1 可插拔扩展接口

```java
// RFC 6298: RTO 自适应
// per-conn 状态通过 ConnectionKey 存于 TcpConnection.attributes
public interface RttEstimator {
    void init(TcpConnection conn);
    /** 加入一个新的 RTT 采样，更新 SRTT / RTTVAR；rttUs 单位为微秒（µs） */
    void addSample(TcpConnection conn, long rttUs);
    /** 返回当前 RTO，单位毫秒（ms） */
    long rtoMs(TcpConnection conn);
    /** RTO 指数退避（RFC6298 §5.5） */
    void backoff(TcpConnection conn);
    void resetBackoff(TcpConnection conn);
    void onConnectionClosed(TcpConnection conn);
}
```

```java
// RFC 5681: 拥塞控制（含完整状态机）
//
// 设计要点：RFC9293（TcpAckProcessor）只通知 onAck 事件和查询 cwnd()。
// dupACK 计数、CongestionState 切换、快速重传触发、cwnd 变化等 RFC5681 全部
// 状态机逻辑封装在实现类内部，RFC9293 层无需感知。
// 重传执行仍由 RFC9293（TcpRetransmitter）完成，CC 通过 init() 注入的
// retransmitCallback 回调触发，实现决策与执行的分离。
public interface CongestionControl {
    String name();

    /**
     * 连接初始化：分配 per-conn 状态，绑定重传回调。
     *
     * @param retransmitCallback 当 CC 决定快速重传时回调，由 RFC9293 TcpRetransmitter 实际执行
     */
    void init(TcpConnection conn, Consumer<TcpConnection> retransmitCallback);

    /**
     * ACK 到达通知（RFC5681 全状态机入口）。
     *
     * @param ackedSegments  本次确认的新 segment 数（0 表示 dupACK）
     * @param sndUnaAdvanced SND.UNA 是否前进（区分新 ACK 与 dupACK）
     */
    void onAck(TcpConnection conn, int ackedSegments, boolean sndUnaAdvanced);

    /** RTO 超时（RFC5681 §5.4）：ssthresh = max(cwnd/2, 2)，cwnd = 1 */
    void onTimeout(TcpConnection conn);

    /** 返回当前 cwnd（MSS 个数） */
    int cwnd(TcpConnection conn);

    /** 是否处于快速恢复或丢包恢复阶段 */
    boolean isInRecovery(TcpConnection conn);

    void onConnectionClosed(TcpConnection conn);
}
```

```java
// RFC 8985: 丢包检测（TLP / RACK）
public interface LossDetector {
    void init(TcpConnection conn);
    /** 每次成功发送后尝试调度 TLP 定时器 */
    void scheduleProbe(TcpConnection conn);
    /** TLP 定时器触发，通过 conn.channel() 发送探测包 */
    void sendProbe(TcpConnection conn);
    /**
     * ACK 到达时检查是否确认了探测包。
     *
     * @param ackSeq 收到的 ACK 序列号
     * @param flags  ACK 处理标志（TcpAckFlags bitmask，如 FLAG_DATA_ACKED）
     */
    void onAck(TcpConnection conn, int ackSeq, int flags);
    void onConnectionClosed(TcpConnection conn);
}
```

```java
// RFC 7323: 时间戳 / PAWS
public interface TcpTimestampExtension {
    void init(TcpConnection conn);
    boolean isEnabled(TcpConnection conn);
    int buildTsval(TcpConnection conn);
    /**
     * PAWS 检查（Protection Against Wrapped Sequences）。
     *
     * @param tsval 入站包的 TSval 字段
     * @return true 表示该包应被丢弃（PAWS 命中）
     */
    boolean isPawsRejected(TcpConnection conn, int tsval);
    void updateRecent(TcpConnection conn, int seq);
    void onConnectionClosed(TcpConnection conn);
}
```

---

### 5.2 `TcpConnection`（富域模型）

```java
public class TcpConnection {

    // ── RFC9293 核心状态（private，只通过方法访问）──
    private TcpConnectionState state;
    private int sndUna;      // SND.UNA
    private int sndNxt;      // SND.NXT
    private int rcvNxt;      // RCV.NXT
    private int sndWnd;      // SND.WND
    private int rcvWnd;      // RCV.WND
    private int mss;

    // ── Netty 绑定──
    private final Channel channel;

    // ── 可插拔扩展状态存储（key 见各扩展模块）──
    private final Map<ConnectionKey<?>, Object> attributes = new HashMap<>();

    // ── 队列 ──
    private final TcpSendBuffer sendBuffer;       // write queue + rtx queue 合并封装
    private final TcpReceiveBuffer receiveBuffer;  // 接收缓冲 + OFO 队列

    // ── 可插拔扩展实例（构造时注入）──
    private final RttEstimator rttEstimator;
    private final CongestionControl congestionControl;
    private final LossDetector lossDetector;
    private final TcpTimestampExtension timestampExt;

    // ── 定时器（per-conn slot，无全局 Map）──
    private final TcpConnectionTimers timers = new TcpConnectionTimers();

    // ── Netty 访问器 ──
    public Channel channel()      { return channel; }
    public EventLoop eventLoop()  { return channel.eventLoop(); }

    // ── 泛型属性访问（供 RFC 扩展模块使用）──
    @SuppressWarnings("unchecked")
    public <T> T getAttr(ConnectionKey<T> key)             { return (T) attributes.get(key); }
    public <T> void setAttr(ConnectionKey<T> key, T value) { attributes.put(key, value); }
    public void removeAttr(ConnectionKey<?> key)            { attributes.remove(key); }

    // ── 核心字段访问器 ──
    public TcpConnectionState state() { return state; }
    public int sndUna()               { return sndUna; }
    public int sndNxt()               { return sndNxt; }
    public int mss()                  { return mss; }

    /** 推进 SND.UNA，清理 rtx queue 中已确认的 skb */
    public void acknowledgeUpTo(int ackSeq) { ... }

    public boolean isInRecovery() { return congestionControl.isInRecovery(this); }

    // ── 扩展实例访问器 ──
    public RttEstimator rttEstimator()               { return rttEstimator; }
    public CongestionControl congestionControl()     { return congestionControl; }
    public LossDetector lossDetector()               { return lossDetector; }
    public TcpTimestampExtension timestampExt()      { return timestampExt; }
    public TcpConnectionTimers timers()              { return timers; }

    // ── 生命周期 ──
    /**
     * 连接关闭时调用：取消所有定时器，通知各扩展释放 per-conn 状态。
     * 必须在该连接绑定的 EventLoop 线程调用。
     */
    public void close() {
        timers.cancelAll();
        rttEstimator.onConnectionClosed(this);
        congestionControl.onConnectionClosed(this);
        lossDetector.onConnectionClosed(this);
        timestampExt.onConnectionClosed(this);
    }
}
```

---

### 5.3 `TcpStateMachine`（Template Method）

```java
public abstract class TcpStateMachine {

    /**
     * 处理入站包。
     *
     * @return TcpDropReason 码（0 = 正常处理，非 0 = 丢弃原因，与现有 TcpDropReason 对齐）
     */
    public final int process(TcpConnection conn, TcpPacketBuf pkt) {
        if (!validate(conn, pkt)) return DISCARD;
        int result = dispatch(conn, pkt);
        postProcess(conn, pkt);
        return result;
    }

    /** 校验：序列号合法性、RST、PAWS 等（RFC9293 §3.10） */
    protected abstract boolean validate(TcpConnection conn, TcpPacketBuf pkt);

    /**
     * 按当前状态分发到具体处理方法。
     *
     * @return TcpDropReason 码（0 = 正常）
     */
    protected abstract int dispatch(TcpConnection conn, TcpPacketBuf pkt);

    /** 处理完成后：检查发送队列、ACK 触发 */
    protected abstract void postProcess(TcpConnection conn, TcpPacketBuf pkt);
}
```

---

### 5.4 `TcpTimerScheduler` + `TcpConnectionTimers`

详细设计见 `TCP.timer.md`。核心思路：消除全局 `ConcurrentMap<Runnable, Future>`，  
改为 per-connection `ScheduledFuture` slot，所有定时器通过 `EventLoop.schedule()`。

```java
/**
 * 每个 TcpConnection 持有的定时器 slot。
 * 所有字段只在该 connection 的 EventLoop 线程访问，无需同步。
 */
public final class TcpConnectionTimers {
    // write_timer 组（RETRANSMIT / TLP_PROBE / ZERO_WINDOW_PROBE / REORDER_TIMEOUT 共用一个 slot）
    ScheduledFuture<?> writeTimer;
    TimerType          writeTimerType;     // 当前激活类型
    long               writeTimerExpires;  // 绝对到期时刻（ms），用于 __mod_timer 判断是否需要重调

    ScheduledFuture<?> delayedAckTimer;
    ScheduledFuture<?> keepaliveTimer;

    /** 连接关闭：O(1) 取消所有定时器 */
    public void cancelAll() {
        cancel(writeTimer); cancel(delayedAckTimer); cancel(keepaliveTimer);
        writeTimer = delayedAckTimer = keepaliveTimer = null;
        writeTimerType = null;
    }
    private static void cancel(ScheduledFuture<?> f) {
        if (f != null && !f.isDone()) f.cancel(false);  // cancel(false)：不中断 EventLoop 线程
    }
}
```

```java
/**
 * 无状态调度器，可被多个 TcpConnection 共享。
 * 不包含任何 TCP 业务逻辑，约 80 行。
 */
public final class TcpTimerScheduler {

    public void scheduleWriteTimer(TcpConnection conn, TimerType type,
                                   long delayMs, Runnable action) {
        TcpConnectionTimers t = conn.timers();
        if (t.writeTimer != null && !t.writeTimer.isDone()) {
            t.writeTimer.cancel(false);
        }
        long delay = Math.max(delayMs, 1);
        t.writeTimerType    = type;
        t.writeTimerExpires = System.currentTimeMillis() + delay;
        t.writeTimer        = conn.eventLoop().schedule(action, delay, MILLISECONDS);
    }

    public void scheduleDelayedAck(TcpConnection conn, long delayMs, Runnable action)    { ... }
    public void scheduleKeepalive(TcpConnection conn, long delayMs, Runnable action) { ... }
    public void cancelWriteTimer(TcpConnection conn)                                  { ... }
    public void cancelAll(TcpConnection conn) { conn.timers().cancelAll(); }
}
```

**TimerType 枚举**（替代 Linux `ICSK_TIME_*` 整型常量）

```java
public enum TimerType {
    RETRANSMIT,         // RTO 超时重传      （Linux: ICSK_TIME_RETRANS）
    DELAYED_ACK,        // 延迟 ACK          （Linux: ICSK_TIME_DACK）
    ZERO_WINDOW_PROBE,  // 零窗口探测        （Linux: ICSK_TIME_PROBE0）
    TLP_PROBE,          // Tail Loss Probe   （Linux: ICSK_TIME_LOSS_PROBE）
    REORDER_TIMEOUT     // RACK 乱序超时     （Linux: ICSK_TIME_REO_TIMEOUT）
}
```

定时器业务逻辑从 `TcpTimer`（778 行）剥离到对应 Handler：`TcpRetransmitHandler`、  
`TcpDelAckHandler`、`TcpProbeHandler`、`TcpKeepAliveHandler`、`TcpReqskHandler`。

---

### 5.5 `ConnectionKey<T>`（扩展状态隔离）

类比 Netty `AttributeKey<T>`，各 RFC 扩展将 per-connection 状态存入  
`TcpConnection.attributes`，同时保持类型安全和模块隔离：

```java
/**
 * 类型安全的连接属性 key，类似 Netty AttributeKey。
 * 每个 RFC 扩展模块声明自己的私有静态 key，TcpConnection 对值内容不可见。
 *
 * 命名规范："{rfc-id}.{impl-name}"，如 "rfc5681.newreno"、"rfc6298.default"。
 * key 名称须全局唯一：两个扩展使用相同名称将导致运行时状态覆盖。
 * 与 Netty AttributeKey 不同，此处不做全局注册校验，由调用方保证唯一性。
 */
public final class ConnectionKey<T> {
    private final String name;
    private ConnectionKey(String name) { this.name = name; }
    /** 工厂方法，风格同 Optional.of() / Map.of() */
    public static <T> ConnectionKey<T> of(String name) {
        return new ConnectionKey<>(name);
    }
    @Override public String toString() { return name; }
}
```

**各扩展如何使用**（以 NewReno 为例）：

```java
public class NewRenoCongestionControl implements CongestionControl {

    // 私有 key——只有本类能访问 RenoState，TcpConnection 完全不感知
    private static final ConnectionKey<RenoState> KEY =
        ConnectionKey.of("rfc5681.newreno");

    static class RenoState {
        int cwnd     = TCP_INIT_CWND;
        int ssthresh = Integer.MAX_VALUE;
        int dupacks  = 0;
        // CongestionState 替代 Linux ca_state int 常量（CA_OPEN / CA_RECOVERY / CA_LOSS）
        CongestionState caState = CongestionState.OPEN;
        int highSeq;   // recovery point：进入快速恢复时的 SND.NXT
        Consumer<TcpConnection> retransmitCallback;
    }

    /** 拥塞控制状态，替代 Linux ca_state int 常量 */
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
            // dupACK
            if (++s.dupacks == 3 && s.caState == CongestionState.OPEN) {
                // 进入快速恢复（RFC5681 §3.2）
                s.ssthresh = Math.max(s.cwnd / 2, 2);
                s.cwnd     = s.ssthresh + 3;
                s.highSeq  = conn.sndNxt();
                s.caState  = CongestionState.RECOVERY;
                s.retransmitCallback.accept(conn);   // 触发 RFC9293 执行重传
            } else if (s.caState == CongestionState.RECOVERY) {
                s.cwnd++;    // 每收一个 dupACK，cwnd 膨胀 1
            }
        } else {
            // 新 ACK
            if (s.caState == CongestionState.RECOVERY
                    && TcpSequence.after(conn.sndUna(), s.highSeq)) {
                s.cwnd    = s.ssthresh;   // 退出快速恢复
                s.caState = CongestionState.OPEN;
            }
            s.dupacks = 0;
            if (s.cwnd < s.ssthresh) {
                s.cwnd += ackedSegments;                         // 慢启动
            } else {
                s.cwnd += Math.max(1, ackedSegments / s.cwnd);  // 拥塞避免
            }
        }
    }

    @Override
    public void onTimeout(TcpConnection conn) {
        RenoState s = conn.getAttr(KEY);
        s.ssthresh = Math.max(s.cwnd / 2, 2);
        s.cwnd     = 1;
        s.dupacks  = 0;
        s.caState  = CongestionState.LOSS;
    }

    @Override public int     cwnd(TcpConnection conn)          { return conn.getAttr(KEY).cwnd; }
    @Override public boolean isInRecovery(TcpConnection conn)  {
        RenoState s = conn.getAttr(KEY);
        return s != null && s.caState != CongestionState.OPEN;
    }
    @Override public void onConnectionClosed(TcpConnection conn) { conn.removeAttr(KEY); }
}
```

**`TcpAckProcessor`（RFC9293 核心，无 CongestionState / dupACK 感知）**：

```java
public class TcpAckProcessor {
    /**
     * 处理入站 ACK。
     *
     * @return TcpDropReason 码（0 = 正常处理）
     */
    public int onAck(TcpConnection conn, TcpPacketBuf pkt) {
        int prevUna      = conn.sndUna();
        int ackedBytes   = tcpSndUnaUpdate(conn, pkt.ackSeq());
        boolean advanced = TcpSequence.after(conn.sndUna(), prevUna);
        int ackedSegs    = ackedBytes / conn.mss();

        // RFC6298：RTT 采样（rttUs 单位微秒）
        conn.rttEstimator().addSample(conn, rttSample(conn, pkt));

        // RFC5681：全状态机（dupACK / 快速重传 / cwnd）由 CC 内部处理
        conn.congestionControl().onAck(conn, ackedSegs, advanced);

        // RFC8985：TLP ACK 确认检查
        conn.lossDetector().onAck(conn, pkt.ackSeq(), computeAckFlags(conn, pkt));

        return 0;
    }
}
```

---

## 6. TcpInput / TcpOutput 拆分方案

### 6.1 当前 `TcpInput`（2321 行）→ 4 个类

> `TcpDemultiplexer` 收到 `IpPacketBuf` 后，解析 IP + TCP 头提取 `TcpPacketBuf`（TCP 层视图）再向下传递。

| 新类 | 来自 TcpInput 的方法 | RFC 归属 |
|------|---------------------|---------|
| `TcpReceiver` | `tcp_validate_incoming`, `tcp_sequence`, `tcp_data_queue`, `tcp_ofo_queue`, `tcp_prune_ofo_queue`, `tcp_rcv_established`, `tcp_rcv_state_process` | RFC9293 |
| `TcpAckProcessor` | `tcp_ack`, `tcp_ack_update_window`, `tcp_clean_rtx_queue`, `tcp_snd_una_update`, `tcp_ack_snd_check` | RFC9293，仅调用 `cc.onAck()` 事件，不含 RFC5681 逻辑 |
| `Rfc6298RttEstimator` | `tcp_rtt_estimator`, `tcp_set_rto`, `tcp_ack_update_rtt`, `tcp_update_rtt_min`, `tcp_rcv_rtt_measure` | RFC6298 |
| `NewRenoCongestionControl` | `tcp_cong_avoid`, `tcp_slow_start`, `tcp_cong_avoid_ai`, `tcp_fastretrans_alert`, `tcp_enter_fast_recovery`, `tcp_update_cwnd_recovery`, `tcp_exit_fast_recovery`, `tcp_enter_loss` | RFC5681（含 dupACK 计数、CongestionState 状态机） |

> `tcp_store_ts_recent`, `tcp_paws_check`, `tcp_paws_discard`, `tcp_replace_ts_recent` → `Rfc7323TimestampExtension`

### 6.2 当前 `TcpOutput`（1398 行）→ 3 个类

| 新类 | 来自 TcpOutput 的方法 | RFC 归属 |
|------|----------------------|---------|
| `TcpSegmenter` | `tcp_write_xmit`, `tcp_transmit_skb`, `tcp_current_mss`, `tcp_mtu_to_mss`, `tcp_queue_skb`, `tcp_event_new_data_sent` | RFC9293 |
| `TcpRetransmitter` | `__tcp_retransmit_skb`, `tcp_retransmit_skb`, `__tcp_push_pending_frames`, `tcp_send_fin`, `tcp_send_active_reset` | RFC9293 |
| `TlpLossDetector` | `tcp_send_loss_probe`, `tcp_schedule_loss_probe` | RFC8985 |

> `tcp_receive_window`, `tcp_space`, `tcp_adjust_rcv_ssthresh` → `TcpReceiver` 或 `TcpConnection` 方法

---

## 7. TcpConnection 构建（Builder 模式）

```java
// 完整配置（所有 RFC 扩展启用）
TcpConnection conn = TcpConnection.builder()
    .channel(channel)
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

## 8. 重构步骤

### Phase 1 — 基础设施（无行为变化）

1. **提取扩展接口**：新建 `RttEstimator`、`CongestionControl`、`LossDetector`、`TcpTimestampExtension`、`KeepAliveExtension` 接口，各含对应 Noop 实现
2. **重构定时器**：用 per-conn `TcpConnectionTimers` slot 直接替代 `TcpTimer` 的全局 `ConcurrentMap<Runnable, Future>`；同步修复连接关闭时缺失的 `cancelAll` 问题
3. **引入 `TcpConnection`**：将 `TcpSock` 字段按关注点分组，暂时保留为 package-private，逐步收紧访问权限

### Phase 2 — 拆分 TcpInput

4. **提取 `Rfc6298RttEstimator`**：将 RTT 相关方法移入，实现 `RttEstimator` 接口，`TcpInput` 改为通过接口调用
5. **提取 `NewRenoCongestionControl`**：将拥塞控制相关方法移入，实现 `CongestionControl` 接口
6. **提取 `Rfc7323TimestampExtension`**：将 PAWS / 时间戳相关方法移入
7. **剩余拆分**：`TcpInput` → `TcpReceiver` + `TcpAckProcessor`

### Phase 3 — 拆分 TcpOutput

8. **提取 `TlpLossDetector`**：将 `tcp_send_loss_probe` 等移入，实现 `LossDetector` 接口；同步取消注释 `tcp_schedule_loss_probe` 调用（TCP.TLP.TODO.md）
9. **剩余拆分**：`TcpOutput` → `TcpSegmenter` + `TcpRetransmitter`

### Phase 4 — 命名规范化与收尾

10. **`TcpConnectionState` 重命名规则**（删除 `TCP_` 前缀，规范下划线格式）：

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

    > Linux 特有状态 `TCP_NEW_SYN_RECV`、`TCP_BOUND_INACTIVE`、`TCP_MAX_STATES` 是内核实现细节，不属于 RFC9293 状态机，`TcpConnectionState` 不引入。

11. **序列号比较工具方法迁移至 `TcpSequence`**：将散落在 `TcpInput` / `TcpOutput` 中的 `after()`、`before()`、`between()` 内联调用，统一提取到 `internal/TcpSequence`，以静态方法暴露，全局引用
12. **`inet_connection_sock` / `tcp_request_sock` 字段合并入 `TcpConnection`**（不单独保留类，所有字段在 Phase 1–3 重构过程中已逐步转移）
13. **收紧 `TcpConnection` 字段访问权限**：将 package-private 字段改为 private + 访问器
14. **S1 修复**（TCP.TODO.01.md）：在重构 `TcpHandshaker` 时，同步加入阶段判断和 pkt 生命周期管理

---

## 9. 重构约束

- **每个 Phase 独立可合并**：每步结束后代码必须可编译、现有功能不回退
- **禁止提前设计**：不为"将来可能需要"的场景增加抽象层，只为已知的 RFC 扩展点设计接口
- **Netty 线程模型不变**：所有 `TcpConnection` 的状态修改必须在其绑定的 EventLoop 中执行，不引入 `synchronized` 或额外锁
- **不改变外部 API**：`TcpDemultiplexHandler` / `Tcp4DemultiplexHandler` 的对外接口保持不变，重构在内部进行
