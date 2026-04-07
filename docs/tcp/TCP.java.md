# TCP 协议栈 Java 重构方案

> 分析基准：当前代码（branch `feature/v1.2.3-ai-lb`）  
> 编写日期：2026-04-07  
> 目标：OOP 化、关注点分离、RFC9293 之外的扩展可插拔

---

## 零、架构总览

### 0.1 当前架构（现状）

```
┌─────────────────────────────────────────────────────────────────┐
│                       TUN 设备 I/O                              │
└──────────────────────────────┬──────────────────────────────────┘
                               │ IpPacketBuf
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│              TcpDemultiplexer（God Facade，705行）               │
│  synRegistry │ establishedRegistry │ TcpInput │ TcpOutput │ TcpTimer │
└───────┬───────────────────────────────────────────────┬─────────┘
        │                                               │
        ▼                                               ▼
┌───────────────────┐                      ┌───────────────────────┐
│  TcpInput（2321行）│                      │ TcpOutput（1398行）   │
│ ─────────────────  │                      │ ─────────────────────  │
│ • 状态机           │◄────── TcpSock ──────►│ • 分段/发送           │
│ • ACK处理          │   （615行贫血模型）    │ • 重传                │
│ • RTT采样(RFC6298) │   50+个public字段     │ • 窗口探测            │
│ • 拥塞控制(RFC5681)│                      │ • TLP空实现(RFC8985)  │
│ • PAWS(RFC7323)   │                      │                       │
│ • OFO队列          │                      │                       │
└───────────────────┘                      └───────────────────────┘
        ▲                                               ▲
        └────────────────── TcpTimer ───────────────────┘
                         （778行：全局Map + 所有handler）
```

**问题一览**：
- `TcpInput`/`TcpOutput` 是 God Class，RFC 关注点完全混合
- `TcpSock` 是贫血模型，50+ public 字段被任意修改
- `TcpTimer` 用全局 `ConcurrentMap<Runnable,Future>` 管理所有连接的所有定时器
- 无任何可插拔扩展点，RFC6298/5681/7323/8985 均硬编码

---

### 0.2 目标架构（重构后）

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
           │ 按五元组查找 TcpConnection              │
           ▼                                        ▼
┌──────────────────────────────────┐   ┌───────────────────────────┐
│         TcpConnection            │   │     TcpHandshaker         │
│   （富域模型，private 字段）       │   │  （握手状态机，阶段判断）   │
│ ┌──────────────────────────────┐ │   └───────────────────────────┘
│ │   TcpConnectionTimers        │ │
│ │  retransSlot│delackSlot│...  │ │   ┌───────────────────────────┐
│ └──────────────────────────────┘ │   │     TcpTimerManager       │
│ ┌──────────────────────────────┐ │   │  HashedWheelTimer(RTO)    │
│ │   RFC Extensions（组合注入）  │ │◄──┤  EventLoop.schedule(DACK) │
│ │  RttEstimator                │ │   │  per-conn TimerHandle[]   │
│ │  CongestionControl           │ │   └───────────────────────────┘
│ │  LossDetector                │ │
│ │  TcpTimestampExtension       │ │
│ └──────────────────────────────┘ │
└──────────────────────────────────┘
           │ 入站                         出站
           ▼                              ▼
┌──────────────────┐          ┌──────────────────────┐
│  RFC9293 接收路径 │          │   RFC9293 发送路径    │
│ ┌──────────────┐ │          │ ┌────────────────────┐│
│ │TcpReceiver   │ │          │ │TcpSegmentizer      ││
│ │TcpAckProcessor│ │          │ │TcpRetransmitter    ││
│ │TcpStateMachine│ │          │ └────────────────────┘│
│ └──────────────┘ │          └──────────────────────┘
└──────────────────┘
           │ 调用扩展接口
           ▼
┌──────────────────────────────────────────────────────┐
│               可插拔 RFC 扩展层（ext/）               │
│                                                      │
│  ┌─────────────┐  ┌──────────────────┐               │
│  │RttEstimator │  │CongestionControl │               │
│  │(RFC6298)    │  │(RFC5681 NewReno) │               │
│  └─────────────┘  └──────────────────┘               │
│  ┌──────────────────┐  ┌────────────────────────┐    │
│  │TcpTimestampExt   │  │LossDetector            │    │
│  │(RFC7323 PAWS)    │  │(RFC8985 TLP)           │    │
│  └──────────────────┘  └────────────────────────┘    │
│  每个扩展均有 Noop 实现，关闭时退化为 RFC9293 兜底      │
└──────────────────────────────────────────────────────┘
```

---

### 0.3 数据流：入站包处理

```
TUN 读取 IpPacketBuf
    │
    ▼
TcpDemultiplexer.dispatch(pkt)
    │ 按 <srcIP:srcPort:dstIP:dstPort> 查找
    ├─► HalfOpenRegistry → TcpHandshaker.onSyn(pkt)         [SYN 握手]
    └─► ConnectionRegistry → TcpStateMachine.process(pkt)   [已建立]
            │
            ├─ validate(pkt)          # RFC9293：序列号、RST、PAWS（调 TcpTimestampExt）
            ├─ TcpAckProcessor.onAck  # RFC9293 + RttEstimator + CongestionControl
            ├─ TcpReceiver.onData     # RFC9293：数据入队、OFO
            └─ postProcess            # ACK 发送检查、发送队列推进
```

---

### 0.4 数据流：定时器触发

```
HashedWheelTimer tick（10ms）
    │ RTO 到期
    └─► conn.eventLoop().execute(retransmitAction)
            │
            ▼
        TcpRetransmitHandler.onTimeout(conn)
            ├─ rttEstimator.backoff(conn)         # RFC6298 退避
            ├─ congestionControl.onTimeout(conn)  # RFC5681 cwnd 降为 1
            └─ TcpRetransmitter.retransmit(conn)  # 重传队头 skb

EventLoop.schedule tick（DelAck 到期）
    └─► TcpDelAckHandler.onTimeout(conn)
            └─ TcpSegmentizer.sendAck(conn)
```

---

## 一、现状诊断

### 1.1 规模与职责

| 文件 | 行数 | 当前职责 |
|------|------|---------|
| `TcpInput.java` | 2321 | 接收路径全部逻辑：ACK 处理、RTT 采样、拥塞控制、数据入队、状态机 |
| `TcpOutput.java` | 1398 | 发送路径全部逻辑：分段、窗口、重传、探测、定时器 |
| `TcpTimer.java` | 778 | 定时器调度 + 所有定时器 handler |
| `TcpDemultiplexer.java` | 705 | 连接分发 + SYN/ESTABLISHED 注册表 + 持有 Input/Output/Timer |
| `TcpSock.java` | 615 | 纯数据结构（贫血模型），50+ 个 public 字段 |
| `TcpHandshaker.java` | 317 | 三次握手状态机 |

### 1.2 核心问题

1. **God Class**：`TcpInput` 同时负责 RTT、拥塞控制、OFO 队列、状态机……每个关注点都没有独立边界
2. **贫血模型**：`TcpSock` 是纯 public 字段集合，没有行为封装，所有算法散落在 Input/Output 中直接修改字段
3. **RFC 混杂**：RFC9293 核心逻辑与 RFC5681（拥塞控制）、RFC7323（时间戳/PAWS）、RFC8985（TLP）耦合在同一个方法链中，无法单独剥离
4. **命名混乱**：延续 Linux C 风格（`inet_connection_sock`、`tcp_request_sock`），与 Java 约定不符
5. **无扩展点**：拥塞控制算法、RTT 估算策略、丢包检测策略均硬编码在方法内

---

## 二、设计目标

1. **RFC9293 核心不可插拔**：状态机、序列号、窗口管理、基本重传 —— 始终存在
2. **其他 RFC 扩展可插拔**：每个 RFC 扩展实现一个接口，通过组合注入，缺省时退化到 no-op
3. **富域模型**：`TcpConnection` 替代贫血的 `TcpSock`，状态只通过方法访问，关键字段 private
4. **单一职责**：每个类只做一件事，行数控制在 400 行以内
5. **保持 Netty 线程模型**：所有 TCP 操作在同一个 EventLoop 中执行，不引入额外锁

---

## 三、RFC 关注点归类

### RFC9293（核心，不可剥离）

- 连接状态机（CLOSED / SYN_SENT / SYN_RECV / ESTABLISHED / FIN_WAIT / CLOSE_WAIT / TIME_WAIT ...）
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
| RFC 6298 | RTO 自适应（SRTT/RTTVAR/Karn 算法） | `RttEstimator` |
| RFC 5681 | 拥塞控制（慢启动、拥塞避免、快速重传/恢复） | `CongestionControl` |
| RFC 7323 | TCP 时间戳 + PAWS | `TcpTimestampExtension` |
| RFC 8985 | RACK/TLP 尾部丢包探测 | `LossDetector` |
| RFC 2018 | SACK（未来） | `SackExtension` |
| RFC 1122 §4.2.3.6 | Keep-Alive | `KeepAliveExtension` |

---

## 四、目标包结构

```
tcp/
├── connection/           # 富域模型：连接状态与核心数据
│   ├── TcpConnection     # 替代 TcpSock（字段 private，方法访问）
│   ├── TcpSendBuffer     # 替代 sk_write_queue + tcp_rtx_queue 的封装
│   ├── TcpReceiveBuffer  # OFO 队列封装
│   └── TcpConnectionState# 连接状态枚举（替代 TcpState）
│
├── core/                 # RFC9293 核心，必选
│   ├── TcpStateMachine   # 连接状态机（Template Method）
│   ├── TcpSegmentizer    # 发送路径：分段、窗口检查（替代 TcpOutput 发送部分）
│   ├── TcpReceiver       # 接收路径：校验、序列号、数据入队（替代 TcpInput 接收部分）
│   ├── TcpRetransmitter  # 重传逻辑（替代 TcpOutput 重传部分）
│   └── TcpAckProcessor   # ACK 处理（替代 TcpInput ACK 部分）
│
├── timer/                # 定时器（重构 TcpTimer）
│   ├── TcpTimerManager   # 统一管理，key 为 (Connection, TimerType)
│   └── TimerType         # 枚举：RETRANS / DACK / PROBE0 / LOSS_PROBE / REO
│
├── handshake/            # 三次握手（重构 TcpHandshaker）
│   ├── TcpHandshaker     # 握手状态机
│   └── HalfOpenRegistry  # SYN 半连接表（替代 synRegistry）
│
├── demux/                # 连接分发（重构 TcpDemultiplexer）
│   ├── TcpDemultiplexer  # Facade：分发入站包到对应 Connection
│   └── ConnectionRegistry# ESTABLISHED 连接表
│
├── ext/                  # 可插拔 RFC 扩展
│   ├── rtt/
│   │   ├── RttEstimator              # 接口
│   │   ├── Rfc6298RttEstimator       # RFC6298 实现
│   │   └── NoopRttEstimator          # 退化实现（固定 RTO）
│   ├── cc/
│   │   ├── CongestionControl         # 接口
│   │   ├── NewRenoCongestionControl   # RFC5681 实现
│   │   └── NoopCongestionControl     # 退化实现（cwnd 不变）
│   ├── loss/
│   │   ├── LossDetector              # 接口
│   │   ├── TlpLossDetector           # RFC8985 TLP 实现
│   │   └── NoopLossDetector          # 退化实现（仅靠 RTO）
│   └── timestamp/
│       ├── TcpTimestampExtension      # 接口
│       ├── Rfc7323TimestampExtension  # RFC7323 实现
│       └── NoopTimestampExtension     # 退化实现
│
└── internal/             # 不变：常量、工具、编解码
    ├── TcpConstants
    ├── SysctlOptions
    ├── TcpOptionCodec
    └── TcpUtils
```

---

## 五、核心接口设计

### 5.1 可插拔扩展接口

```java
// RFC 6298: RTO 自适应
public interface RttEstimator {
    /** 新 RTT 样本到达，更新 SRTT/RTTVAR */
    void update(TcpConnection conn, long rttUs);
    /** 返回当前 RTO（jiffies） */
    int rto(TcpConnection conn);
    /** RTO 退避（指数退避） */
    void backoff(TcpConnection conn);
    /** 重置退避计数 */
    void resetBackoff(TcpConnection conn);
}

// RFC 5681: 拥塞控制
public interface CongestionControl {
    /** 新数据被 ACK，更新 cwnd（慢启动或拥塞避免） */
    void onAcked(TcpConnection conn, int ackedSegments);
    /** 进入快速重传/恢复 */
    void onLoss(TcpConnection conn);
    /** 退出快速恢复 */
    void onRecovery(TcpConnection conn);
    /** 超时丢包 */
    void onTimeout(TcpConnection conn);
    /** 返回发送限额（cwnd quota） */
    int cwndQuota(TcpConnection conn);
}

// RFC 8985: 丢包检测（TLP / RACK）
public interface LossDetector {
    /** 每次成功发送后尝试调度 TLP 定时器 */
    void scheduleProbe(TcpConnection conn);
    /** TLP 定时器触发，发送探测包 */
    void sendProbe(Channel net, TcpConnection conn);
    /** ACK 到达时检查是否确认了探测包 */
    void onAck(TcpConnection conn, int ack, int flag);
}

// RFC 7323: 时间戳 / PAWS
public interface TcpTimestampExtension {
    /** 判断是否需要发送时间戳选项 */
    boolean isEnabled(TcpConnection conn);
    /** 构造发送时间戳（TSval） */
    int buildTsval(TcpConnection conn);
    /** PAWS 检查（返回 true 表示丢弃） */
    boolean pawsDiscard(TcpConnection conn);
    /** 更新 ts_recent */
    void updateRecent(TcpConnection conn, int seq);
}
```

### 5.2 `TcpConnection`（富域模型）

```java
public class TcpConnection {

    // ── RFC9293 核心状态（package-private，通过方法访问）──
    private TcpConnectionState state;
    private int sndUna;      // SND.UNA
    private int sndNxt;      // SND.NXT
    private int rcvNxt;      // RCV.NXT
    private int sndWnd;      // SND.WND
    private int rcvWnd;      // RCV.WND
    private int mssCache;

    // ── 扩展状态（由各扩展模块通过 accessor 读写）──
    final RttState rttState = new RttState();          // RFC6298
    final CongestionState ccState = new CongestionState(); // RFC5681
    final TimestampState tsState = new TimestampState();   // RFC7323
    int tlpHighSeq;                                        // RFC8985

    // ── 队列 ──
    private final TcpSendBuffer sendBuffer;
    private final TcpReceiveBuffer receiveBuffer;

    // ── 可插拔扩展（构造时注入）──
    private final RttEstimator rttEstimator;
    private final CongestionControl congestionControl;
    private final LossDetector lossDetector;
    private final TcpTimestampExtension timestampExt;

    // 只暴露必要的方法，不暴露原始字段
    public int getSndUna() { return sndUna; }
    public void advanceSndUna(int ack) { ... }
    public boolean isInRecovery() { return ccState.isRecovering(); }
    // ...
}
```

### 5.3 `TcpStateMachine`（Template Method）

```java
public abstract class TcpStateMachine {

    /** 处理入站包，由子类按状态分发 */
    public final int process(Channel net, TcpConnection conn, TcpPacketBuf pkt) {
        if (!validate(conn, pkt))   return DISCARD;
        int result = dispatch(net, conn, pkt);
        postProcess(net, conn, pkt);
        return result;
    }

    /** 校验：序列号、RST、PAWS 等 RFC9293 §3.10 */
    protected abstract boolean validate(TcpConnection conn, TcpPacketBuf pkt);

    /** 按当前状态分发到具体处理方法 */
    protected abstract int dispatch(Channel net, TcpConnection conn, TcpPacketBuf pkt);

    /** 处理完成后：检查发送队列、ACK 触发 */
    protected abstract void postProcess(Channel net, TcpConnection conn, TcpPacketBuf pkt);
}
```

### 5.4 `TcpTimerManager`（消除 Runnable 作 key 的问题）

```java
public class TcpTimerManager {

    // key = (connectionId, TimerType)，唯一且可 remove
    private final ConcurrentMap<TimerKey, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();

    public void schedule(TcpConnection conn, TimerType type, long delayMs, Runnable action) {
        TimerKey key = new TimerKey(conn.id(), type);
        ScheduledFuture<?> old = timers.put(key, conn.eventLoop().schedule(action, delayMs, MILLISECONDS));
        if (old != null) old.cancel(false);
    }

    public void cancel(TcpConnection conn, TimerType type) {
        ScheduledFuture<?> f = timers.remove(new TimerKey(conn.id(), type));
        if (f != null) f.cancel(false);
    }

    /** 连接关闭时清理该连接所有定时器 */
    public void cancelAll(TcpConnection conn) {
        timers.entrySet().removeIf(e -> {
            if (e.getKey().connectionId().equals(conn.id())) {
                e.getValue().cancel(false);
                return true;
            }
            return false;
        });
    }

    record TimerKey(String connectionId, TimerType type) {}
}
```

---

## 六、TcpInput / TcpOutput 拆分方案

### 当前 `TcpInput`（2321 行）→ 拆分为 4 个类

| 新类 | 来自 TcpInput 的方法 | RFC 归属 |
|------|---------------------|---------|
| `TcpReceiver` | `tcp_validate_incoming`, `tcp_sequence`, `tcp_data_queue`, `tcp_ofo_queue`, `tcp_prune_ofo_queue`, `tcp_rcv_established`, `tcp_rcv_state_process` | RFC9293 |
| `TcpAckProcessor` | `tcp_ack`, `tcp_ack_update_window`, `tcp_clean_rtx_queue`, `tcp_snd_una_update`, `tcp_ack_snd_check` | RFC9293 |
| `Rfc6298RttEstimator` | `tcp_rtt_estimator`, `tcp_set_rto`, `tcp_ack_update_rtt`, `tcp_update_rtt_min`, `tcp_rcv_rtt_measure` | RFC6298 |
| `NewRenoCongestionControl` | `tcp_cong_avoid`, `tcp_slow_start`, `tcp_fastretrans_alert`, `tcp_enter_fast_recovery`, `tcp_exit_fast_recovery`, `tcp_enter_loss` | RFC5681 |

> `tcp_store_ts_recent`, `tcp_paws_check`, `tcp_paws_discard`, `tcp_replace_ts_recent` → `Rfc7323TimestampExtension`

### 当前 `TcpOutput`（1398 行）→ 拆分为 3 个类

| 新类 | 来自 TcpOutput 的方法 | RFC 归属 |
|------|----------------------|---------|
| `TcpSegmentizer` | `tcp_write_xmit`, `tcp_transmit_skb`, `tcp_current_mss`, `tcp_mtu_to_mss`, `tcp_queue_skb`, `tcp_event_new_data_sent` | RFC9293 |
| `TcpRetransmitter` | `__tcp_retransmit_skb`, `tcp_retransmit_skb`, `__tcp_push_pending_frames`, `tcp_send_fin`, `tcp_send_active_reset` | RFC9293 |
| `TlpLossDetector` | `tcp_send_loss_probe`, `tcp_schedule_loss_probe` | RFC8985 |

> `tcp_receive_window`, `tcp_space`, `tcp_adjust_rcv_ssthresh` → `TcpReceiver` 或 `TcpConnection` 方法

---

## 七、TcpConnection 构建（Builder 模式）

```java
TcpConnection conn = TcpConnection.builder()
    .rttEstimator(new Rfc6298RttEstimator())        // RFC6298，可替换
    .congestionControl(new NewRenoCongestionControl()) // RFC5681，可替换
    .lossDetector(new TlpLossDetector())             // RFC8985，可替换
    .timestampExt(new Rfc7323TimestampExtension())   // RFC7323，可关闭
    // 最简配置（仅 RFC9293）：
    // .rttEstimator(NoopRttEstimator.INSTANCE)
    // .congestionControl(NoopCongestionControl.INSTANCE)
    // .lossDetector(NoopLossDetector.INSTANCE)
    // .timestampExt(NoopTimestampExtension.INSTANCE)
    .build();
```

---

## 八、重构步骤（建议顺序）

### Phase 1 — 基础设施（无行为变化）

1. **提取扩展接口**：新建 `RttEstimator`、`CongestionControl`、`LossDetector`、`TcpTimestampExtension` 接口，各含默认 no-op 实现
2. **重构 `TcpTimerManager`**：将 `TcpTimer` 的 `ConcurrentMap<Runnable, Future>` 改为 `ConcurrentMap<TimerKey, Future>`，同步修复 S2 中的定时器 cancelAll 问题
3. **引入 `TcpConnection`**：将 `TcpSock` 字段按关注点分组（`RttState`、`CongestionState`、`TimestampState`），暂时保留所有字段为 package-private，逐步收紧访问权限

### Phase 2 — 拆分 TcpInput

4. **提取 `Rfc6298RttEstimator`**：将 RTT 相关方法（`tcp_rtt_estimator` 等）移入，实现 `RttEstimator` 接口，`TcpInput` 改为通过接口调用
5. **提取 `NewRenoCongestionControl`**：将拥塞控制相关方法移入，实现 `CongestionControl` 接口
6. **提取 `Rfc7323TimestampExtension`**：将 PAWS/时间戳相关方法移入
7. **剩余拆分**：`TcpInput` → `TcpReceiver` + `TcpAckProcessor`

### Phase 3 — 拆分 TcpOutput

8. **提取 `TlpLossDetector`**：将 `tcp_send_loss_probe` 等移入，实现 `LossDetector` 接口，同步取消注释 `tcp_schedule_loss_probe` 调用（TCP.TLP.TODO.md）
9. **剩余拆分**：`TcpOutput` → `TcpSegmentizer` + `TcpRetransmitter`

### Phase 4 — 重命名与收尾

10. **Java 命名规范**：`inet_connection_sock` → `InetConnectionSock`（或直接合并入 `TcpConnection`），`tcp_request_sock` → `TcpRequestSock`
11. **收紧 `TcpConnection` 字段访问权限**：逐步将 public 字段改为 private + getter/setter
12. **S1 修复**（TCP.TODO.01.md）：在 Phase 3 重构 `TcpHandshaker` 时，同步加入阶段判断和 pkt 生命周期管理

---

## 九、重构约束

- **每个 Phase 独立可合并**：每步结束后代码必须可编译、现有功能不回退
- **禁止提前设计**：不为"将来可能需要"的场景增加抽象层，只为已知的 RFC 扩展点设计接口
- **Netty 线程模型不变**：所有 `TcpConnection` 的状态修改必须在其绑定的 `EventLoop` 中执行，不引入 `synchronized` 或额外锁
- **不改变外部 API**：`TcpDemultiplexHandler` / `Tcp4DemultiplexHandler` 的对外接口保持不变，重构在内部进行

---

## 十、重构后类规模预期

| 新类 | 预期行数 | 职责 |
|------|---------|------|
| `TcpConnection` | ~250 | 连接状态容器（富域模型） |
| `TcpReceiver` | ~400 | RFC9293 接收路径 |
| `TcpAckProcessor` | ~350 | RFC9293 ACK 处理 |
| `TcpSegmentizer` | ~350 | RFC9293 发送分段 |
| `TcpRetransmitter` | ~200 | RFC9293 重传 |
| `TcpStateMachine` | ~300 | RFC9293 状态机分发 |
| `Rfc6298RttEstimator` | ~150 | RFC6298 RTT/RTO |
| `NewRenoCongestionControl` | ~200 | RFC5681 拥塞控制 |
| `Rfc7323TimestampExtension` | ~100 | RFC7323 时间戳/PAWS |
| `TlpLossDetector` | ~100 | RFC8985 TLP |
| `TcpTimerManager` | ~100 | 定时器管理 |
| `TcpHandshaker` | ~200 | 三次握手 |
