# TCP 拥塞控制算法选型方案

> 编写日期：2026-04-08  
> 适用模块：`TcpInput` / `TcpOutput` 拥塞控制路径  
> 关联文档：`TCP.java.md` § `CongestionControl` 接口

---

## 一、pangolin TUN TCP 栈的连接特征

```
本地 App → [TUN] → pangolin TCP 栈 → 上游代理 → 目标服务器
                    ↑
              自定义 TCP 栈负责的连接范围
```

**关键认知**：pangolin TCP 栈看到的 RTT **不是本地回环延迟**，而是包含上游代理的完整往返时间。  
上游为跨境代理时，RTT 通常在 **100–300ms**，且链路丢包率显著高于本地网络（随机丢包、无线衰减）。

---

## 二、主流算法对比

| 算法 | 类型 | cwnd 增长 | 丢包响应 | 高延迟表现 | 实现复杂度 |
|------|------|---------|---------|-----------|-----------|
| **New Reno** | 丢包 | 线性 AI/MD | 激进削减 ×0.5 | 差（窗口恢复慢） | 低（已实现） |
| **CUBIC** | 丢包 | 三次函数 | 温和削减 ×0.7 | 好 | 中 |
| **BBR v1** | 带宽探测 | 基于 BDP | 不以丢包为信号 | 极好 | 高 |
| **BBR v2** | 带宽探测 | 基于 BDP | 轻度响应丢包 | 极好 | 高 |
| **Vegas** | 延迟 | RTT 感知 | 提前降速 | 中（过于保守） | 中 |

---

## 三、核心差异：丢包信号 vs 带宽信号

### 3.1 New Reno / CUBIC（丢包驱动）

将丢包视为拥塞信号，触发 cwnd 削减：

```
丢包 → ssthresh = cwnd × 0.5（Reno）或 × 0.7（CUBIC）→ cwnd 重建
```

**问题**：跨境链路的随机丢包（信号干扰、无线衰减）会被误判为拥塞，导致不必要的降速，  
在高延迟场景下恢复时间尤其长。

### 3.2 BBR（带宽探测驱动）

完全不依赖丢包信号，持续测量两个量：

```
BtlBw（瓶颈带宽）= 最近 10 RTT 内的最大交付速率
RTprop（传播延迟）= 最近 10s 内的最小 RTT
cwnd  = BtlBw × RTprop（BDP）
```

随机丢包不影响 BBR 的速率决策——非常适合高丢包、高延迟的跨境代理链路。

---

## 三·五、CongestionControl 接口（Java 实现约定）

> 本节配合 `TCP.java.md § 5.1 / 5.5` 阅读，接口完整定义见那里。

### 接口设计原则

RFC9293（`TcpAckProcessor`）只向拥塞控制模块发送**事件**并**查询** cwnd，  
不感知 dupACK 计数、ca_state、快速重传触发时机——这些全部封装在 CC 实现内部。

```
TcpAckProcessor.onAck()
    ├─ tcpSndUnaUpdate(conn, pkt.ackSeq())   ← RFC9293：推进 SND.UNA
    ├─ rttEstimator.update(conn, rttSample)  ← RFC6298：RTT 采样
    ├─ cc.onAck(conn, ackedSegs, advanced)   ← RFC5681：全状态机（CC 内部）
    └─ lossDetector.onAck(conn, ack, flag)   ← RFC8985：TLP 确认检查
```

重传执行权在 RFC9293（`TcpRetransmitter`），CC 通过 `init()` 注入的  
`Consumer<TcpConnection> retransmitCallback` 回调触发，决策与执行分离。

### per-connection 状态存储（ConnectionKey 模式）

CC 算法以**单例**形式共享（一个 `TcpMultiplexer` 共享同一实例），  
per-connection 状态通过私有 `ConnectionKey<StateType>` 存入 `TcpConnection.attributes`，  
`TcpConnection` 对状态内容完全不可见：

```java
// CubicCongestionControl（单例）
private static final ConnectionKey<CubicState> KEY =
    ConnectionKey.newKey("rfc5681.cubic");

// init() 时分配 per-conn 状态
conn.attr(KEY, new CubicState());

// onAck() 时读取
CubicState s = conn.attr(KEY);

// onConnectionClosed() 时清理
conn.removeAttr(KEY);
```

---

## 四、算法选型建议

### 短期：升级至 CUBIC

**理由**：
- 改动量小，只需新增 `CubicCongestionControl` 实现，不修改 RFC9293 核心路径
- 对高 BDP 场景（高延迟代理链路）比 New Reno 吞吐量提升明显
- `TCP.java.md § 5.1` 的 `CongestionControl` 接口已支持：实现 `init / onAck / onTimeout / getCwnd / isInRecovery / onConnectionClosed` 即可插入，无需改动 `TcpAckProcessor`

**CUBIC 相对 New Reno 的核心变化**：

```java
// New Reno：纯线性增长，每 RTT cwnd +1
cwnd += 1.0 / cwnd;

// CUBIC：三次函数，以上次丢包点 W_max 为对称中心
// 参数：C = 0.4，β = 0.7
double t = timeSinceCongestionSec();       // 距上次丢包的时间（秒）
double K = Math.cbrt(W_max * (1 - β) / C); // 三次函数零点（收敛到 W_max 的时间）
double W_cubic = C * Math.pow(t - K, 3) + W_max;
cwnd = (int) W_cubic;

// 丢包时：温和削减
ssthresh = (int) (cwnd * 0.7);   // Reno 是 × 0.5
```

**CUBIC cwnd 曲线示意**：

```
cwnd
 │               ╭────  ← 超过 W_max 后继续探测
 │          ╭────
 W_max ─────╯           ← 收敛区（增长放缓，接近 W_max）
 │     ╭────
 │ ╭───
 └────────────────────── 时间（距上次丢包）
       K
```

---

### 长期：实现 BBR（最适合代理场景）

**BBR 对 pangolin 的核心优势**：

1. **跨境高丢包链路**：随机丢包不触发 cwnd 削减，吞吐量稳定
2. **高延迟场景（RTT 100–300ms）**：基于 BDP 直接填满管道，无需慢启动多轮 RTT 迭代
3. **短连接多的代理流量**：BBR 的带宽探测在连接初期就能快速找到合适速率

**BBR 需要额外实现的组件**：

```
BbrState（每连接持有）
├── BtlBwFilter    → MaxFilter，滑动时间窗口 10 RTT，估算瓶颈带宽
├── RTPropFilter   → MinFilter，滑动时间窗口 10s，估算传播延迟
├── pacing_rate    → BtlBw × pacing_gain（发送速率平滑控制）
├── cwnd           → BtlBw × RTprop × cwnd_gain
└── 状态机（4态）  → STARTUP → DRAIN → PROBE_BW → PROBE_RTT
```

**BBR 实现难点**：

- **Pacing**：需要控制发送间隔（而非仅用 cwnd 限制突发），在 Netty 架构下可通过 EventLoop 定时调度近似实现
- **BtlBw 测量**：依赖精准的 `delivered` 字段统计（`TcpSock.delivered`，当前已有字段）
- **与 TLP/RACK 联动**：BBR 需要准确的 RTT 样本，依赖 RFC8985 的丢包检测

---

## 五、实施路径

| 阶段 | 选择 | 收益 | 代价 | 对应接口 |
|------|------|------|------|---------|
| **现在** | 保持 New Reno | 零改动 | 高延迟场景吞吐低 | 已实现 |
| **短期** | 升级 CUBIC | 高 BDP 吞吐提升 30–50%，丢包恢复更快 | ~100 行 | `CubicCongestionControl` |
| **长期** | 实现 BBR | 最适合跨境代理，随机丢包不降速 | 较高（需 pacing + BtlBw 估算） | `BbrCongestionControl` |

结合 `TCP.java.md` 中已设计的 `CongestionControl` 接口，CUBIC 和 BBR 均作为独立实现插入，不影响 RFC9293 核心路径。

**建议先实施 CUBIC，在真实跨境链路上观察吞吐和丢包恢复表现，再决定是否进一步实现 BBR。**

---

## 六、CUBIC 实现检查清单

**类结构**（对应 `TCP.java.md § 5.5` 的 ConnectionKey 模式）：

```
CubicCongestionControl implements CongestionControl（单例）
    private static final ConnectionKey<CubicState> KEY = ...
    CubicState { W_max, K, epoch_start, cwnd, ssthresh, dupacks, caState,
                 highSeq, retransmitCallback }
```

**方法实现**：

- [ ] `init(conn, retransmitCallback)`：构造 `CubicState`，初始化 `cwnd=10, ssthresh=MAX_VALUE`，存入 `conn.attr(KEY, state)`
- [ ] `onAck(conn, ackedSegs, sndUnaAdvanced)`
  - dupACK 路径：计数达 3 触发快速重传（同 NewReno，见 `TCP.java.md § 5.5`）
  - 正常 ACK 路径：
    - 慢启动（`cwnd < ssthresh`）：`cwnd += ackedSegs`
    - 拥塞避免（`cwnd >= ssthresh`）：计算 `W_cubic(t)`，取 `max(W_cubic, W_tcp)`
- [ ] `onTimeout(conn)`：`ssthresh = cwnd × 0.7`（CUBIC 比 Reno 温和），`cwnd = 1`，重置 `epoch_start`
- [ ] `getCwnd(conn)`：返回 `conn.attr(KEY).cwnd`
- [ ] `isInRecovery(conn)`：`conn.attr(KEY).caState != CA_OPEN`
- [ ] `onConnectionClosed(conn)`：`conn.removeAttr(KEY)`

**CUBIC cwnd 核心计算**（在 `onAck` 拥塞避免路径调用）：

```java
private int cubicCwnd(CubicState s, TcpConnection conn) {
    double t = (System.nanoTime() - s.epoch_start) / 1e9;  // 距上次丢包秒数
    double W_cubic = C * Math.pow(t - s.K, 3) + s.W_max;
    // Friendly：对比 TCP-Reno 在同等条件下的 cwnd
    double W_tcp = s.W_max * 0.7 + 3 * (t / conn.rttEstimator().rto(conn));
    return (int) Math.max(W_cubic, W_tcp);
}
```

**单元测试**：

- [ ] `W_cubic(t=K) == W_max`（三次函数零点）
- [ ] `W_cubic(t<K) < W_max`（趋近阶段增长放缓）
- [ ] `W_cubic(t>K) > W_max`（超过后继续探测）
- [ ] 丢包后 `ssthresh = cwnd × 0.7`，而非 Reno 的 × 0.5
- [ ] Hybrid slow start（HyStart，可选）：检测到延迟增加时提前退出慢启动，避免 overshoot
