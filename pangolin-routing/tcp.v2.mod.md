# v2 TCP 模块化落地方案

> 目标包: `com.github.pangolin.routing.acceptor.tun.net.v2.tcp`
> 目标: 在不牺牲热路径性能的前提下,把拥塞控制、丢包检测、恢复编排的边界整理清楚,**为 NewReno / CUBIC / BBR 一次性铺好接口**。
> 结论: **拥塞控制做"BBR-ready 统一接口"SPI;丢包检测和恢复保持 TCP core 内部模块化,暂不做插件 SPI。**
> 修订: 2026-04-25 — 基于 codex 原方案,把 CC SPI 升级为 BBR-ready 统一接口(原方案只覆盖到 Reno/CUBIC,新增 BBR 落地路径会被迫改 SPI 两次)。

## 1. 最终决策

| 领域 | 最终形态 | 是否 SPI | 理由 |
| --- | --- | --- | --- |
| 拥塞控制 | `cc/TcpCongestionControl` + `RateSample` + `NewRenoCongestionControl` / `CubicCongestionControl` / `BbrCongestionControl` | 是 | 对齐 Linux `tcp_congestion_ops` 但**统一为单 `onAck(sock, RateSample)` 入口**(不分裂为 `cong_avoid` + `cong_control`),NewReno/CUBIC/BBR/Vegas/DCTCP 都能落 |
| SACK / D-SACK tagging | `recovery/SackTagger` | 否 | 直接操作 RTX queue、`TcpSegment.sacked`、`sackedOut/lostOut/retransOut`,属于 TCP core hot path |
| RACK loss detection | `recovery/RackLossDetector` | 否 | 依赖 per-segment `sentTimeUs/txDelivered` 和 ACK/SACK 进展,不适合暴露成外部 SPI |
| TLP | `recovery/TlpController` | 否 | 与 timer、RTO、RACK、输出路径强耦合,应保持 core 内部模块 |
| Recovery 编排 | `recovery/RecoveryController` | 否 | 对齐 `tcp_fastretrans_alert` / `tcp_enter_loss` / undo 链,需要统一维护状态机 |
| 重传选段 | `recovery/RetransmissionSelector` | 否 | 对齐 `tcp_xmit_retransmit_queue` / `__tcp_retransmit_skb`,必须直接访问 RTX queue、MSS、发送窗口和 per-segment flags |
| PRR | `recovery/PrrController` | 否 | PRR 是 recovery 阶段发送配额控制,与 core accounting 强耦合;后续补齐时作为内部模块 |
| Pacing | `core/PacingTokenBucket`(Sender 内部字段) | 否 | BBR / CUBIC HyStart++ 的发送速率限制,sender 主路径每段消费 token,不是独立 SPI |

不采用"大而全的 `TcpRecoveryStrategy` SPI"。如果未来确实要同时支持两套完整 recovery 栈,例如 RFC6675 conservative recovery 与 Linux RACK recovery 并存,再评估 FreeBSD 风格的"整套 TCP function block"切换,而不是现在提前抽象。

## 2. 设计依据

| 参考 | 结论 |
| --- | --- |
| Linux TCP | 拥塞算法通过 `tcp_congestion_ops` 插入;SACK/RACK/TLP/recovery 主体仍在 TCP core。`tcp_packets_in_flight()` 使用 `packets_out - left_out + retrans_out`,说明 recovery accounting 是 core 状态 |
| Linux `cong_avoid` vs `cong_control` | 历史包袱:早期算法只有 `cong_avoid`(逐 ACK 增 cwnd),BBR 时代不得不加 `cong_control`(整体接管 cwnd + pacing)。**v2 是新设计,直接走统一 `onAck(rs)`,RateSample 同时承载两类信息,避免双 hook 分裂** |
| FreeBSD RACK | FreeBSD RACK 是 alternate TCP stack,不是单独 loss detector SPI;RACK/TLP/PRR/SACK attack detection/burst mitigation 被做成一套完整栈能力 |
| gVisor RACK | RACK 需要 per-packet transmission timestamp、SACK、DSACK;其 loss detection 与 recovery/cwnd reduction 强耦合,但仍可作为 TCP stack 内部机制 |
| smoltcp | 简化栈中拥塞控制可以作为可选算法,但 SACK/RACK 这类复杂 recovery 未插件化;说明轻量栈更应避免过早抽象 |
| lwIP | 小型 TCP/IP 栈强调低内存和完整 TCP 主路径,fast recovery/fast retransmit 等放在 TCP core 中,不是独立插件链 |
| Netty HTTP/2 | Java 结构上适合用 handler/codec/connection/flow-controller 分层,但不适合把 TCP ACK/SACK/RACK 热路径做成 pipeline filter 链 |

参考链接:

1. Linux `include/net/tcp.h`: https://github.com/torvalds/linux/blob/master/include/net/tcp.h
2. Linux `tcp_input.c`: https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c
3. Linux `tcp_recovery.c`: https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_recovery.c
4. Linux `tcp_rate.c`(RateSample 起源): https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_rate.c
5. Linux BBR: https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_bbr.c
6. Linux CUBIC: https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_cubic.c
7. FreeBSD RACK and Alternate TCP Stacks: https://freebsdfoundation.org/our-work/journal/browser-based-edition/networking-10th-anniversary/rack-and-alternate-tcp-stacks-for-freebsd/
8. gVisor RACK: https://gvisor.dev/blog/2021/08/31/gvisor-rack/
9. RFC 9438 CUBIC: https://www.rfc-editor.org/rfc/rfc9438
10. BBR Internet-Draft: https://datatracker.ietf.org/doc/draft-cardwell-iccrg-bbr-congestion-control/

## 3. 目标包结构

```text
com.github.pangolin.routing.acceptor.tun.net.v2.tcp
├── core/
│   ├── TcpSock
│   ├── Sender                 (持有 cwnd/ssthresh/pacingRateBps/appLimited/minRttUs 等)
│   ├── Receiver
│   ├── TcpAck
│   ├── TcpOutput              (writeXmit 主路径增加 pacing token bucket gate)
│   ├── TcpSegment             (已含 sentTimeUs/txDelivered)
│   ├── TcpSendBuffer
│   └── TcpReceiveBuffer
├── cc/
│   ├── TcpCongestionControl   (统一 SPI)
│   ├── RateSample             (mutable + Sender 复用)
│   ├── CongestionState        (从 TcpSock 内部 enum 抽出,SPI 接口需要)
│   ├── NewRenoCongestionControl
│   ├── CubicCongestionControl   (Phase 6 新增)
│   ├── BbrCongestionControl     (Phase 7 新增)
│   └── TcpCongestionControls    (注册表 / 按名查找)
├── recovery/
│   ├── SackTagger
│   ├── RackLossDetector
│   ├── TlpController
│   ├── RecoveryController
│   ├── RetransmissionSelector
│   └── PrrController            (Phase 5 补齐)
├── handshake/
│   ├── TcpHandshaker
│   └── TcpInitialSequenceNumberGenerator
├── option/
│   ├── TcpOptionCodec
│   └── TcpOptionsReceived
└── timewait/
    └── TcpTimewaitSock
```

约束:

1. `cc` 是唯一稳定 SPI 包。
2. `recovery` 类全部先做 `final` + package-private,不暴露 public strategy。
3. `core` 仍持有 `Sender/Receiver/TcpSock/TcpSegment` 状态,不复制状态到 context。
4. v2 不依赖 v1 TCP 工具类。
5. 每次迁移都必须保持现有测试通过。

## 4. 拥塞控制 SPI(BBR-ready 统一接口)

### 4.1 接口

```java
public interface TcpCongestionControl {
    /** 连接初始化(握手完成后调一次)— CC 在这里准备自己的内部状态。 */
    void init(TcpSock sock);

    /**
     * 每次 ACK 处理后调用 — 唯一的"算法入口"。
     *
     * <p>CC 根据 rs 决定要不要改 cwnd / pacing,改的方式是直接写
     * sock.sender().cwnd(...) / sock.sender().pacingRateBps(...)。
     *
     * <p>不同算法读取 rs 的不同子集,无成本忽略不需要的字段:
     *   - NewReno:rs.ackedBytes / rs.appLimited
     *   - CUBIC:同上 + rs.rttUs(用于 epoch / 曲线)
     *   - BBR:rs.delivered / rs.priorDelivered / rs.sendElapsedUs / rs.ackElapsedUs
     *         / rs.appLimited / rs.minRttUs;忽略 ackedBytes
     *   - Vegas:rs.rttUs + rs.minRttUs(估 queueing delay)
     */
    void onAck(TcpSock sock, RateSample rs);

    /** 进入 Recovery 时返回新 ssthresh。Reno: cwnd/2;CUBIC: cwnd*0.7;BBR: 通常返回 cwnd 不变 */
    int ssthresh(TcpSock sock);

    /** undo 路径返回需要回滚到的 cwnd。 */
    int undoCwnd(TcpSock sock);

    /** CA 状态机切换通知(Open/Disorder/Recovery/Loss);CC 可选用。 */
    void onStateChange(TcpSock sock, CongestionState oldS, CongestionState newS);
}
```

### 4.2 RateSample(mutable + 复用)

```java
public final class RateSample {
    // 通用(NewReno / CUBIC / BBR 都用)
    public int  ackedPackets;       // 本次 ACK 释放的段数
    public int  ackedBytes;         // 本次 ACK 释放的字节数
    public long rttUs;              // 本次 ACK 的 RTT 样本(为 0 表示重传段无效样本)
    public boolean appLimited;      // 发送时刻 sender 是否 application-limited
    public boolean ecnMarked;       // 本次 ACK 携带 ECE

    // BBR / 部分 Linux CC 用
    public int  delivered;          // tp->delivered(本次 ACK 后的累计投递字节数)
    public int  priorDelivered;     // 这次释放最旧段的 tx.delivered 快照
    public long sendElapsedUs;      // = nowUs - firstAckedSeg.sentTimeUs
    public long ackElapsedUs;       // = nowUs - priorDeliveredMstamp
    public boolean isAckedRetrans;  // 释放的段含重传段 → BDP 估计应该排除
    public long minRttUs;           // 最近 10s 滑窗的最小 RTT(BBR / Vegas)

    /** Sender 持有单例,每次 ACK 前 reset 然后填,免 GC 压力 */
    public void reset() { /* zero out all fields */ }
}
```

### 4.3 禁止事项

1. `TcpCongestionControl` 不解析 SACK block。
2. `TcpCongestionControl` 不遍历 RTX queue。
3. `TcpCongestionControl` 不设置 `TCPCB_LOST/RETRANS/SACKED_ACKED`。
4. `TcpCongestionControl` 不负责 TLP/RTO timer。
5. `RateSample` 不携带 `TcpSegment` 或 `TcpPacketBuf` 引用,只打快照值。
6. `RateSample` 不在 `onAck` 之外被读(下次 ACK 会 reset 覆盖)。

### 4.4 允许事项 / Sender 协作模板

```java
// Sender.onAckedByCc 内部(简化):
RateSample rs = sock.rateSampleScratch();
rs.reset();
RateSample.fillFromAckResult(rs, sock, ackResult);
sock.congestionControl().onAck(sock, rs);

// State machine transitions(由 RecoveryController 调用):
void enterRecovery(TcpSock sock) {
    int newSsthresh = sock.congestionControl().ssthresh(sock);
    sock.sender().ssthresh(newSsthresh);
    sock.sender().cwnd(newSsthresh + 3);
    sock.congestionControl().onStateChange(sock, OPEN, RECOVERY);
}

// Undo path:
int restoreCwnd = sock.congestionControl().undoCwnd(sock);
sock.sender().cwnd(restoreCwnd);

// TcpOutput.writeXmit 主路径,对每段发送前 pacing 检查:
long pacingBps = sock.sender().pacingRateBps();
if (pacingBps > 0) {
    if (!sock.sender().pacingBucket().tryConsume(pacingBps, segLen, nowNs)) {
        schedulePacingResume(sock, ...);
        break;
    }
}
```

## 5. Recovery 内部模块职责

| 类 | 输入 | 输出/副作用 | 对齐点 |
| --- | --- | --- | --- |
| `SackTagger` | `TcpSock`, ACK packet, prior `sndUna` | 更新 `TcpSegment.sacked`, `sackedOut`, DSACK/RACK hints | `tcp_sacktag_write_queue` |
| `RackLossDetector` | `TcpSock`, ACK/SACK 进展 | 标记 `TCPCB_LOST`,更新 `lostOut`,维护 `rackMstamp/rackRttUs/reoWnd` | `tcp_rack_detect_loss`, `tcp_rack_update_reo_wnd` |
| `TlpController` | `TcpSock`, timers, ACK | 维护 `tlpHighSeq`,发送 TLP probe,处理 TLP ACK | `tcp_schedule_loss_probe`, `tcp_send_loss_probe`, `tcp_process_tlp_ack` |
| `RecoveryController` | ACK 结果、dupACK、RTO、DSACK/F-RTO 信号 | 进入/退出 `DISORDER/RECOVERY/LOSS`,调用 CC 钩子,执行 undo | `tcp_fastretrans_alert`, `tcp_enter_loss`, `tcp_try_undo_*` |
| `RetransmissionSelector` | `TcpSock`, RTX queue, `sndWnd`, MSS | 选择 LOST/RTX head,窗口裁剪,必要时 split/collapse | `tcp_xmit_retransmit_queue`, `__tcp_retransmit_skb` |
| `PrrController` | recovery 状态、delivered/lost/in-flight | 控制 recovery 阶段发送配额 | RFC 6937, Linux PRR |

推荐 ACK 主流程:

```java
TcpAck.onAck(sock, pkt) {
    AckResult ack = AckProcessor.updateWindowAndCleanRtx(sock, pkt);

    SackTagger.tag(sock, pkt, ack.priorSndUna());
    RackLossDetector.detect(sock, ack);
    TlpController.onAck(sock, ack);
    RecoveryController.onAck(sock, ack);    // 内部可能触发 cc.ssthresh / cc.undoCwnd / cc.onStateChange

    RateSample rs = sock.rateSampleScratch().reset();
    RateSample.fillFromAckResult(rs, sock, ack);
    sock.congestionControl().onAck(sock, rs);

    TcpOutput.writeXmit(sock);   // 进入 pacing-aware 发送路径
}
```

推荐重传主流程:

```java
TcpOutput.retransmitSkb(sock) {
    TcpSegment skb = RetransmissionSelector.select(sock);
    if (skb == null) {
        return;
    }
    RetransmissionSelector.prepareForRetransmit(sock, skb);
    __tcp_transmit_skb(sock, skb, sock.receiver().rcvNxt());
}
```

## 6. 状态归属

保持现有状态归属,**不要为模块化搬迁字段**;新增的 BBR-ready 字段归 `Sender`。

| 状态 | 归属 |
| --- | --- |
| `cwnd/ssthresh/priorCwnd/priorSsthresh/congestionState` | `Sender` |
| `packetsOut/sackedOut/lostOut/retransOut/undoRetrans` | `Sender` |
| `rackMstamp/rackRttUs/rackReoWndSteps/rackDsackSeen` | `Sender` |
| `tlpHighSeq/retransStamp/rtoBackoff/frto*` | `Sender` |
| `delivered`(累计投递字节数) | `Sender`(已有) |
| **`pacingRateBps`**(BBR / pacing 路径) | `Sender`(新增,默认 0) |
| **`appLimited`**(发送时刻应用 limited 标记) | `Sender`(新增) |
| **`deliveredMstamp`**(`tp->delivered` 推进到当前值的最近时刻) | `Sender`(新增) |
| **`minRttUs` 滑窗 min(10s)** | `Sender`(已有 minRttUs,需扩成滑窗) |
| **`rateSampleScratch`** | `Sender`(单例 mutable) |
| `sacked/sentTimeUs/txDelivered/tcpFlags` | `TcpSegment`(已有) |
| **`priorMstamp`**(段发送时的 deliveredMstamp 快照) | `TcpSegment`(新增,BBR 必须) |
| RTX/write queue | `TcpSendBuffer` |
| `rcvNxt/rcvWnd/rcvSsthresh/windowClamp` | `Receiver` |

模块只操作这些状态,不拥有状态。

## 7. 性能规则

1. `recovery` 类使用 `final class`。
2. recovery 方法优先 `static` 或 package-private。
3. 不在 ACK/SACK/RACK 热路径使用 `Stream`、lambda、`Optional`。
4. 不为每个 ACK 创建大型 context。
5. **`RateSample` 是 Sender 持有的复用 mutable 对象**(单例 scratch),每次 ACK 前 `reset()`,字段直接重写;不允许在 `onAck` 之外保留引用。
6. 不复制 RTX queue。
7. 不引入 `LossDetector` / `RecoveryStrategy` 接口。
8. `TcpCongestionControl.onAck` 是允许的虚调用边界,每个 ACK 最多调用一次。
9. **Pacing token bucket 在 `pacingRateBps == 0` 时短路返回**(NewReno / 默认配置零开销,不影响现有 UT)。

## 8. 落地顺序

落地分两条主线:**SPI + 基础设施(Phase 1a/1b/1c)** 解锁后续算法插入,**算法落地(Phase 6 / 7)** 验证 SPI。

### Phase 1a: 抽 CC SPI(统一接口)

目标: 不改变任何行为,只把 cwnd/ssthresh 算法边界拆出来,接口形态对齐 BBR。

任务:

1. 新建 `cc/TcpCongestionControl`(统一接口签名,见 §4.1)。
2. 新建 `cc/RateSample`(mutable + reset)。
3. 新建 `cc/CongestionState`(从 `TcpSock` 内部 enum 提到 cc 包,SPI 公开必要)。
4. 新建 `cc/NewRenoCongestionControl`,把当前 `Sender.onAckedByCc` 中 NewReno slow start / congestion avoidance 增长逻辑迁入,**ssthresh / undoCwnd / onStateChange 行为完全保留**。
5. `TcpSock` 或 `Sender` 持有 `TcpCongestionControl congestionControl`,默认装 NewReno 实例。
6. `Sender.onAckedByCc` 改为先聚合 `RateSample` 再调 `cc.onAck`(此时 RateSample 中 BBR 字段填 0 或默认值,NewReno 不读)。
7. `RecoveryController` 进入 Recovery / Loss 时通过 `cc.ssthresh / cc.undoCwnd / cc.onStateChange` 调用,**取代 Sender 内联的 cwnd/ssthresh 操作**。

验收:`SlowStartTest` / `FastRecoveryTest` / `SpuriousRtoUndoTest` / `DsackUndoTest` / `RetransOutAccountingTest` / `RtoBackoffTest` / `TcpEnterLossRtxResetTest` / `DisorderStateTest` 全过。

### Phase 1b: BBR-ready 基础设施

目标: 在 v2 内部加 BBR 必需的状态(`appLimited` / `deliveredMstamp` / `priorMstamp` / `min_rtt` 滑窗),还**不引入 BBR 算法**,所有新字段维护成本默认 0。

任务:

1. `Sender` 增 `appLimited` 字段。`writeXmit` 在 `tcpSendHead() == null` 且 `packetsOut() < cwnd()` 时设置(发送队列空 → app-limited)。
2. `Sender` 增 `deliveredMstamp` 字段。`TcpAck.cleanRtxQueue` 在累计 ACK 推进 `delivered` 时刷新。
3. `TcpSegment` 增 `priorMstamp` 字段。`__tcp_transmit_skb` 发送时复制 `sock.sender().deliveredMstamp()`(类似已有的 `txDelivered`)。
4. `Sender.minRttUs` 改为带 10s 滑窗的最小值(对齐 Linux `minmax_subwin`)。
5. `Sender` 增 `rateSampleScratch` 单例。
6. `RateSample.fillFromAckResult` 实现:遍历本次 ACK 释放的最旧段,聚合 `sendElapsedUs / ackElapsedUs / priorDelivered / isAckedRetrans` 等。

验收:无新行为引入,**所有现有 UT 全过**。新增 `RateSampleAggregationTest` 1-2 个 UT 验证字段填值正确。

### Phase 1c: pacing 基础设施

目标: 加 pacing 路径,默认 NewReno 不启用(`pacingRateBps == 0` 短路),不影响现有行为。

任务:

1. 新建 `core/PacingTokenBucket`(基于 `nanoTime` 的 token 累积器,字节单位)。
2. `Sender` 增 `pacingRateBps`(默认 0)+ `pacingBucket` 字段。
3. `TcpOutput.pacingCheck` 改为真实实现:
   - 若 `pacingRateBps == 0`,直接 return false(不拦截,等价当前行为)。
   - 否则按 token bucket 判定;不足时 `break` 出 writeXmit 循环并 schedule 一个 `pacingResume` task。
4. `pacingResume` task 触发后 re-enter `writeXmit`。
5. `RecoveryController` 在进入 Loss / Recovery 时**不动 pacing**(留给 BBR 自己决定)。

验收:NewReno 不启用 pacing,所有现有 UT 全过。新增 `PacingTokenBucketTest` 验证 token 累积/消费/饥饿/恢复。

### Phase 5: 补 Linux recovery 缺口(并行)

目标: 在 SPI 抽完之前的并行项,继续按 Linux 路线补齐 recovery 缺口。

优先项:

1. PRR 发送配额(放在 `recovery/PrrController` 内部,`RecoveryController` 进入 Recovery 时启用)。
2. partial ACK recovery 推进 / 多洞 SACK 下 LOST 多段重传(`tcp_force_fast_retransmit` 等价)。
3. `tcp_xmit_retransmit_queue` 队列遍历(让单次 retransmitSkb 调用按 cwnd budget 发出多段)。
4. lost retransmit 场景的 RACK 再判收口(已通过 T3 验证过基础行为)。

暂不优先:

1. 显式 RFC 6675 `NextSeg`。
2. 显式 RFC 6675 `RescueRxt`。

理由: 当前 v2 已经是 Linux/RACK 风格,继续补 `tcp_fastretrans_alert` / `tcp_xmit_retransmit_queue` 等价路径比切换到 RFC 6675 conservative recovery 更一致。

### Phase 6: CUBIC

目标: 新增 CC 算法,验证 SPI 在"丢包驱动 + 逐 ACK 增长"路径上的设计。

任务:

1. 新建 `cc/CubicCongestionControl`(epoch_start / bic_K / bic_origin_point / last_max_cwnd / TCP friendliness)。
2. 配置项选择 `newreno/cubic`(`TcpCongestionControls` 注册表)。
3. recovery 仍复用 `SackTagger/RackLossDetector/TlpController/RecoveryController/RetransmissionSelector`。

验收:

1. NewReno 全部旧测试通过。
2. CUBIC 增加 slow start exit、CA 增长、loss reaction、undo 基础 UT。
3. SACK / RACK / TLP 在 CUBIC 下至少跑一组冒烟测试。

### Phase 7: BBR

目标: 落地第一个非"丢包驱动"算法,验证 SPI / pacing / RateSample 设计是否合理。

任务:

1. 新建 `cc/BbrCongestionControl`(StartUp / Drain / ProbeBW / ProbeRTT 状态机)。
2. `BbrCongestionControl.onAck` 走 RateSample 路径,内部维护 `btlBwFilter / rtPropFilter / cyclingPacingGain`。
3. `BbrCongestionControl.pacingRate / cwndTarget` 写到 `Sender.pacingRateBps()` / `Sender.cwnd()`。
4. `init` / `onStateChange` 负责 ProbeRTT 等状态切换。

验收:

1. 启用 BBR 后,在 fixed-bandwidth E2E 测试里 throughput 收敛到 BDP 附近。
2. SACK/RACK/TLP 在 BBR 下不破坏(不依赖 cwnd reduction 的算法,要确认 recovery 状态机仍正常工作)。
3. NewReno / CUBIC 全部旧测试不变。

## 9. 不做事项

1. 不做 public `TcpRecoveryStrategy`。
2. 不做 public `LossDetector`。
3. 不把 SACK/RACK/TLP 拆成 Netty pipeline/filter。
4. 不让 CC 算法遍历 RTX queue。
5. 不复制 `TcpSock`/`Sender` 状态到 context。
6. 不为了类图好看拆出大量 interface。
7. **不把 SPI 拆成 `cong_avoid` + `cong_control` 双 hook**(Linux 历史包袱,我们用统一 `onAck(rs)`)。
8. 不在完成 Phase 1a-1c 前引入 CUBIC / BBR(没有 SPI / 基础设施支撑会被迫改 SPI 两次)。
9. **不抽 `recovery/SackTagger` 等内部模块**作为初版 refactor 目标(原 codex 方案 Phase 2-4),除非真有切换 recovery 栈的诉求 — 当前内部聚合已经清晰。

## 10. 最终结构目标

目标调用链:

```text
ACK input
  -> update snd_una / clean RTX                       (TcpAck.cleanRtxQueue)
  -> SACK / DSACK tagging                             (TcpAck.sacktagWriteQueue)
  -> RACK loss detection                              (TcpAck.rackDetectLoss)
  -> TLP ACK handling                                 (TcpAck.tcpProcessTlpAck)
  -> recovery state / undo / loss entry               (TcpAck.tcpFastretransAlert + Sender.onAckedByCc 内部状态机)
  -> RateSample 聚合                                   (RateSample.fillFromAckResult)
  -> congestion-control onAck (cwnd + pacingRate)     (TcpCongestionControl.onAck)
  -> pacing-aware writeXmit                           (TcpOutput.writeXmit + PacingTokenBucket)
  -> retransmission selector(if needed)              (TcpOutput.retransmitSkb)
  -> output                                           (__tcp_transmit_skb)
```

唯一 SPI:

```text
TcpCongestionControl   (NewReno / CUBIC / BBR / 用户自定义算法)
```

内部模块(均在 v2 包内,非 SPI):

```text
SackTagger
RackLossDetector
TlpController
RecoveryController
RetransmissionSelector
PrrController
PacingTokenBucket
```

这套方案在 codex 原方案基础上把 CC SPI 升级为 BBR-ready 的统一接口,同时把 pacing / appLimited / deliveredMstamp / minRtt 滑窗等基础设施一次性铺好,避免 BBR 落地时被迫改 SPI 两次。NewReno / CUBIC 实现忽略 BBR 专用字段,零成本兼容。
