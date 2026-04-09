# TCP 拥塞控制（CWND）改造方案

> 对标基准：Linux 内核 `net/ipv4/tcp_output.c`, `tcp_input.c`, `tcp_cong.c`（v6.x）  
> RFC：**RFC 5681**（TCP Congestion Control）, **RFC 6582**（NewReno）, **RFC 6928**（IW10）  
> 分析日期：2026-04-03  
> 修订日期：2026-04-04（代码对比核查：§3.6 补充 Loss 分支、§3.7/§3.8 ssthresh/cwnd 修正为内核实际行为、§3.9 更新为双层调用结构）

---

## 一、现状

| 能力 | 现状 |
|------|------|
| `snd_cwnd` / `snd_ssthresh` 字段 | ✅ 已定义（`TcpSock.java`） |
| 初始 cwnd（IW10） | ✅ `tcp_init_cwnd()` 已实现 |
| **发送路径 cwnd 约束** | ✅ `tcp_cwnd_test()` 已实现（`TcpOutput.java:587`），`tcp_write_xmit()` 已集成 |
| 慢启动（Slow Start） | ❌ 未实现 |
| 拥塞避免（Congestion Avoidance） | ❌ 未实现 |
| 快速重传（Fast Retransmit） | ❌ 未实现（3 个重复 ACK 无响应）|
| 快速恢复（Fast Recovery/NewReno） | ❌ 未实现 |
| 重传后 ssthresh 折半 | ❌ 未实现 |
| `tcp_cong_avoid()` 接口 | ❌ 未实现 |

**关键代码定位：**
- `TcpSock.java:75` — `snd_cwnd`
- `TcpSock.java:96` — `snd_ssthresh`
- `TcpOutput.java:587` — `tcp_cwnd_test()`（已实现，返回可发段数）
- `TcpOutput.java:635` — `tcp_write_xmit()` 发送循环（已调用 `tcp_cwnd_test`）
- `TcpInput.java:920` — `tcp_ack()`（无慢启动/拥塞避免更新，`prior_packets_out` 已声明）
- `TcpInput.java:697` — `tcp_clean_rtx_queue()`（RTT 测量完成，返回后应更新 cwnd）
- `TcpTimer.java:280` — `tcp_write_timer_handler()`（RTO 超时，应触发 ssthresh 折半）

---

## 二、改造目标

按 RFC 5681 + RFC 6582（NewReno）实现完整拥塞控制：

1. **慢启动**：`cwnd < ssthresh` 时每收一个 ACK，`cwnd += min(N, SMSS)`
2. **拥塞避免**：`cwnd >= ssthresh` 时每个 RTT，`cwnd += SMSS²/cwnd`
3. **快速重传**：3 个重复 ACK 后立即重传最旧未确认段
4. **快速恢复**：进入恢复阶段，`ssthresh = max(FlightSize/2, 2*SMSS)`，`cwnd = ssthresh + 3*SMSS`
5. **恢复退出**：收到新 ACK 后 `cwnd = ssthresh`，退出快速恢复
6. **RTO 超时**：`ssthresh = max(FlightSize/2, 2*SMSS)`，`cwnd = 1*SMSS`，进入慢启动

---

## 三、改造方案

### 3.1 TcpSock 补充拥塞控制字段

**Linux 对标**：`include/linux/tcp.h`

需在 `TcpSock.java` 中补充以下字段（`snd_cwnd`、`snd_ssthresh`、`snd_cwnd_clamp`、`packets_out`、`retrans_out` 均已存在）：

```java
// --- 拥塞控制状态字段（均需新增）---

// 重复 ACK 计数，达到 3 触发快速重传（RFC 5681 §3.2）
// Linux: tp->duplicate_sack / tp->icsk_ack.rcv_mss 路径
public int dupacks;

// 进入快速恢复 / RTO 恢复时记录的 snd_nxt，标识恢复点
// Linux: tp->high_seq
public int high_seq;

// 当前拥塞控制阶段：CA_Open=0, CA_Disorder=1, CA_CWR=2, CA_Recovery=3, CA_Loss=4
// Linux: tp->icsk_ca_state
public int icsk_ca_state;

// 拥塞避免 AI 阶段的字节累加器（避免浮点运算）
// Linux: tp->snd_cwnd_cnt
public int snd_cwnd_cnt;
```

**拥塞阶段常量**（加入 `TcpConstants.java`）：

```java
// Linux: include/net/tcp.h enum tcp_ca_state
int TCP_CA_Open     = 0;  // 正常状态
int TCP_CA_Disorder = 1;  // 检测到重复 ACK，未确认丢失
int TCP_CA_CWR      = 2;  // 拥塞窗口缩减（ECN/SACK 触发）
int TCP_CA_Recovery = 3;  // 快速恢复中（RFC 5681 §3.2）
int TCP_CA_Loss     = 4;  // RTO 超时后的丢失恢复
```

---

### 3.2 发送路径：cwnd 约束（已实现，无需改动）

`tcp_cwnd_test()` 已在 `TcpOutput.java:587` 实现，单位为段数，与 Linux 内核一致：

```java
// TcpOutput.java:587 — 已有实现，勿重复添加
private int tcp_cwnd_test(final TcpSock tp) {
    int in_flight = tp.tcp_packets_in_flight();   // 以段为单位
    int cwnd = tp.tcp_snd_cwnd();                 // 以段为单位
    if (in_flight >= cwnd) {
        return 0;
    }
    int halfcwnd = Math.max(cwnd >> 1, 1);
    return Math.min(halfcwnd, cwnd - in_flight);
}
```

`tcp_write_xmit()` 在 `TcpOutput.java:642` 已正确调用，**此节无需改动**。

---

### 3.3 慢启动（Slow Start）

**Linux 对标**：`net/ipv4/tcp_cong.c:tcp_slow_start()`  
**RFC**：RFC 5681 §3.1

```java
/**
 * RFC 5681 §3.1 Slow Start:
 *   cwnd += min(N, SMSS)  for each ACK acknowledging new data
 * where N = number of newly acknowledged segments.
 * Returns the carry-over acked count for congestion avoidance.
 *
 * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_cong.c#L392">tcp_slow_start</a>
 */
private int tcp_slow_start(TcpSock tp, int acked) {
    int cwnd = Math.min(tp.snd_cwnd + acked, tp.snd_ssthresh);
    acked -= cwnd - tp.snd_cwnd;
    tp.snd_cwnd = Math.min(cwnd, tp.snd_cwnd_clamp);
    return acked;   // 剩余 acked 进入拥塞避免
}
```

---

### 3.4 拥塞避免（Congestion Avoidance）

**Linux 对标**：`net/ipv4/tcp_cong.c:tcp_cong_avoid_ai()`  
**RFC**：RFC 5681 §3.1（AI 阶段：每 RTT cwnd += SMSS）

```java
/**
 * RFC 5681 §3.1 Congestion Avoidance (Additive Increase):
 *   approximates cwnd += 1 per RTT via per-ACK increment of SMSS²/cwnd.
 * Uses snd_cwnd_cnt to accumulate fractional increments without floating point.
 *
 * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_cong.c#L361">tcp_cong_avoid_ai</a>
 */
private void tcp_cong_avoid_ai(TcpSock tp, int w, int acked) {
    if (tp.snd_cwnd_cnt >= w) {
        tp.snd_cwnd_cnt = 0;
        if (tp.snd_cwnd < tp.snd_cwnd_clamp) {
            tp.snd_cwnd++;
        }
    }
    tp.snd_cwnd_cnt += acked;
    if (tp.snd_cwnd_cnt >= w) {
        int delta = tp.snd_cwnd_cnt / w;
        tp.snd_cwnd_cnt -= delta * w;
        tp.snd_cwnd = Math.min(tp.snd_cwnd + delta, tp.snd_cwnd_clamp);
    }
}
```

---

### 3.5 cwnd 更新入口：`tcp_cong_avoid()`

**Linux 对标**：`net/ipv4/tcp_input.c:tcp_cong_avoid()`

```java
/**
 * Called after a valid ACK. Drives cwnd through Slow Start or Congestion Avoidance
 * depending on the current CA state and cwnd vs ssthresh.
 *
 * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3896">tcp_cong_avoid</a>
 */
private void tcp_cong_avoid(TcpSock tp, int acked) {
    if (tp.icsk_ca_state != TCP_CA_Open
            && tp.icsk_ca_state != TCP_CA_Disorder) {
        return;  // Recovery / Loss 阶段不在此更新 cwnd
    }
    if (tp.snd_cwnd < tp.snd_ssthresh) {
        acked = tcp_slow_start(tp, acked);
        if (acked == 0) return;
    }
    tcp_cong_avoid_ai(tp, tp.snd_cwnd, acked);
}
```

**集成到 `tcp_ack()`（`TcpInput.java:920`）**，在 `tcp_clean_rtx_queue()` 之后：

```java
// tcp_ack() 末尾，flag |= tcp_clean_rtx_queue() 之后：
if (0 != (flag & FLAG_SND_UNA_ADVANCED)) {
    // prior_packets_out 在 tcp_ack() 开头已声明（TcpInput.java:922）
    int newly_acked = prior_packets_out - tp.packets_out;
    if (newly_acked > 0) {
        tcp_cong_avoid(tp, newly_acked);
    }
}
```

---

### 3.6 快速重传（Fast Retransmit）

**Linux 对标**：`net/ipv4/tcp_input.c:tcp_ack()` → `tcp_fastretrans_alert()`  
**RFC**：RFC 5681 §3.2

**重复 ACK 判断**：Linux 内核没有 `FLAG_NOT_DUP`。
重复 ACK 的判定条件为：**ACK 号未前进**（`!(flag & FLAG_SND_UNA_ADVANCED)`）且**报文无数据载荷**（`!(flag & FLAG_DATA)`）。

**Loss 状态处理**：RTO 触发后处于 `TCP_CA_Loss` 状态，此时收到的重复 ACK 不应再次触发快速恢复（避免 cwnd 二次折半）；仅在 `snd_una` 超过 `high_seq` 时才退出 Loss 状态，与 Linux 内核行为一致。

```java
/**
 * RFC 5681 §3.2: Duplicate ACK detection and Fast Retransmit.
 * A duplicate ACK is an ACK that does not advance SND.UNA and carries no data.
 *
 * Loss 状态下的 dup ACK 不触发快速恢复：只有当 snd_una 超过 high_seq（所有丢失段
 * 均已 ACK）时才退出 Loss，切换回 CA_Open。
 *
 * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3386">tcp_fastretrans_alert</a>
 */
private void tcp_fastretrans_alert(Channel net, TcpSock tp, int flag) {
    if (tp.icsk_ca_state == TCP_CA_Recovery) {
        // 已在快速恢复中：处理部分 ACK（NewReno §3.7）
        if (0 != (flag & FLAG_SND_UNA_ADVANCED)) {
            tcp_update_cwnd_recovery(net, tp);
        }
        return;
    }

    // Loss 状态：dup ACK 不触发快速恢复，仅在恢复完成后回到 Open
    // https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3386
    if (tp.icsk_ca_state == TCP_CA_Loss) {
        if (0 != (flag & FLAG_SND_UNA_ADVANCED) && after(tp.snd_una, tp.high_seq)) {
            tp.icsk_ca_state = TCP_CA_Open;
            tp.dupacks = 0;
        }
        return;
    }

    // 重复 ACK：SND.UNA 未前进 且 报文不携带数据
    boolean is_dupack = 0 == (flag & FLAG_SND_UNA_ADVANCED)
                     && 0 == (flag & FLAG_DATA);
    if (is_dupack) {
        tp.dupacks++;
        if (tp.dupacks == 3) {               // RFC 5681 §3.2: dupthresh = 3
            tcp_enter_fast_recovery(net, tp);
        }
    } else if (0 != (flag & FLAG_SND_UNA_ADVANCED)) {
        tp.dupacks = 0;                      // 新 ACK 重置计数
    }
}
```

**集成到 `tcp_ack()` 末尾**（`tcp_clean_rtx_queue()` 之后，`tcp_cong_avoid()` 之前）：

```java
tcp_fastretrans_alert(net, tp, flag);
```

---

### 3.7 快速恢复（Fast Recovery — NewReno）

**Linux 对标**：`net/ipv4/tcp_input.c:tcp_enter_recovery()`, `tcp_fastretrans_alert()`  
**RFC**：RFC 5681 §3.2, RFC 6582

```java
/**
 * RFC 5681 §3.2: Enter Fast Recovery.
 *   ssthresh = max(snd_cwnd / 2, 2)   ← Linux tcp_reno_ssthresh()，基于 snd_cwnd 而非 packets_out
 *   cwnd     = ssthresh + 3            (3 duplicate ACKs have left the network)
 *
 * 注意：Linux 内核 tcp_reno_ssthresh()（net/ipv4/tcp_cong.c#L416）使用 snd_cwnd >> 1，
 * 而非 RFC 5681 §3.2 原文的 FlightSize/2（packets_out/2）。
 * 当存在 SACK 时两者不同；Reno 无 SACK，因此内核以 snd_cwnd 近似 FlightSize。
 *
 * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3317">tcp_enter_recovery</a>
 * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_cong.c#L416">tcp_reno_ssthresh</a>
 */
private void tcp_enter_fast_recovery(Channel net, TcpSock tp) {
    // tcp_reno_ssthresh: max(snd_cwnd >> 1, 2) — 基于 snd_cwnd，与 Linux 内核一致
    tp.snd_ssthresh = Math.max(tp.snd_cwnd >> 1, 2);

    // RFC 5681 §3.2: cwnd = ssthresh + 3（以段为单位；+3 对应 3 个离开网络的 dup ACK）
    tp.snd_cwnd = tp.snd_ssthresh + 3;
    tp.snd_cwnd_cnt = 0;

    tp.high_seq = tp.snd_nxt;               // 记录恢复点
    tp.icsk_ca_state = TCP_CA_Recovery;

    log.info("[CWND] Enter FastRecovery: ssthresh={} cwnd={}", tp.snd_ssthresh, tp.snd_cwnd);

    // 立即重传最旧未确认段（Head of retransmit queue）
    output.tcp_retransmit_skb(net, tp, tp.tcp_rtx_queue.peek(), 1);
}

/**
 * RFC 6582 §3 step 4: NewReno partial ACK processing during Fast Recovery.
 * A partial ACK advances SND.UNA but does not cover high_seq, so recovery continues.
 *
 * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3499">tcp_try_undo_partial</a>
 */
private void tcp_update_cwnd_recovery(Channel net, TcpSock tp) {
    if (!after(tp.snd_una, tp.high_seq)) {
        // 部分 ACK：SND.UNA 前进但未超过 high_seq
        // 每收到一个部分 ACK，cwnd 减 1（已有一个段离开网络）
        if (tp.snd_cwnd > tp.snd_ssthresh) {
            tp.snd_cwnd--;
        }
        // 重传下一个未确认段（NewReno: retransmit the first unacknowledged segment）
        tcp_retransmit_skb(net, tp, tp.tcp_rtx_queue.peek());
    } else {
        // 完整恢复：SND.UNA 已超过 high_seq
        tcp_exit_fast_recovery(tp);
    }
}

/**
 * Exit Fast Recovery: set cwnd = ssthresh (deflate the window).
 *
 * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3494">tcp_try_to_open</a>
 */
private void tcp_exit_fast_recovery(TcpSock tp) {
    tp.snd_cwnd = tp.snd_ssthresh;
    tp.icsk_ca_state = TCP_CA_Open;
    tp.dupacks = 0;
    log.info("[CWND] Exit FastRecovery: cwnd={}", tp.snd_cwnd);
}
```

---

### 3.8 RTO 超时后的 cwnd 处理

**Linux 对标**：`net/ipv4/tcp_input.c:tcp_enter_loss()`（注意：函数实际在 `tcp_input.c`，不是 `tcp_timer.c`）  
**RFC**：RFC 5681 §3.1

```java
/**
 * RFC 5681 §3.1: On Retransmission Timeout (RTO expiry):
 *   ssthresh = max(snd_cwnd / 2, 2)        ← tcp_reno_ssthresh()，基于 snd_cwnd
 *   cwnd     = tcp_packets_in_flight() + 1  ← Linux v6.x 行为，非 RFC 原文的 1
 *
 * 注意：Linux 内核 tcp_enter_loss()（net/ipv4/tcp_input.c#L2229）设置：
 *   tp->snd_cwnd = tcp_packets_in_flight(tp) + 1;
 * 而非 RFC 5681 §3.1 原文的 cwnd=1。这样可以避免 cwnd 突然降为 1 导致的
 * 发送中断（允许已在途的段完成，然后重传一个段）。
 *
 * ssthresh 同样使用 snd_cwnd >> 1（tcp_reno_ssthresh），而非 packets_out/2，
 * 理由同 §3.7：Reno 无 SACK，以 snd_cwnd 近似 FlightSize。
 *
 * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L2190">tcp_enter_loss</a>
 * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_cong.c#L416">tcp_reno_ssthresh</a>
 */
public void tcp_enter_loss(TcpSock tp) {
    // tcp_reno_ssthresh: max(snd_cwnd >> 1, 2)
    tp.snd_ssthresh  = Math.max(tp.snd_cwnd >> 1, 2);
    // Linux v6.x: cwnd = in_flight + 1（允许当前在途段完成，再重传一个）
    tp.snd_cwnd      = tp.tcp_packets_in_flight() + 1;
    tp.snd_cwnd_cnt  = 0;
    tp.snd_cwnd_stamp = tcp_jiffies32();

    tp.icsk_ca_state = TCP_CA_Loss;
    tp.high_seq      = tp.snd_nxt;
    tp.dupacks       = 0;

    log.info("[CWND] RTO Loss: ssthresh={} cwnd={}", tp.snd_ssthresh, tp.snd_cwnd);
}
```

**集成到 `tcp_retransmit_timer()`（`TcpTimer.java`，RTO 超时确认处）**：

```java
// 在确认需要 RTO 重传时调用（已集成到 TcpTimer.java:330 和 TcpTimer.java:343）：
multiplexer.input.tcp_enter_loss(tp);
multiplexer.output.tcp_retransmit_skb(net, tp, tp.tcp_rtx_queue_head(), 1);
```

---

### 3.9 `tcp_retransmit_skb()` 实现

**Linux 对标**：`net/ipv4/tcp_output.c:tcp_retransmit_skb()`（`TcpOutput.java:1124`）

实际实现分为两层：`tcp_retransmit_skb()` 包装 `__tcp_retransmit_skb()`，与内核结构一致：

```java
/**
 * Inner retransmit: validates window, transmits with clone=true,
 * marks TCPCB_EVER_RETRANS, updates bytes_retrans / total_retrans.
 *
 * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L3321">__tcp_retransmit_skb</a>
 */
private int __tcp_retransmit_skb(Channel net, TcpSock tp, TcpBuffer skb, int segs) {
    // 检查 snd_una / 窗口边界 ...
    int err = tcp_transmit_skb(net, tp, skb, true /*clone*/);
    skb.sacked |= TCPCB_EVER_RETRANS;   // 永久标记（用于 RTT 过滤）
    tp.total_retrans += segs;
    tp.bytes_retrans += skbLen;
    return err;
}

/**
 * Outer retransmit: marks TCPCB_RETRANS, increments retrans_out,
 * saves retrans_stamp.
 *
 * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L3448">tcp_retransmit_skb</a>
 */
int tcp_retransmit_skb(Channel net, TcpSock tp, TcpBuffer skb, int segs) {
    int err = __tcp_retransmit_skb(net, tp, skb, segs);
    if (0 == err) {
        skb.sacked |= TCPCB_RETRANS;         // 标记本次重传
        tp.retrans_out += tcp_skb_pcount(skb);
    }
    if (tp.retrans_stamp == 0) {
        tp.retrans_stamp = tp.tcp_skb_timestamp_ts(tp.tcp_usec_ts, skb);
    }
    tp.undo_retrans += tcp_skb_pcount(skb);
    return err;
}
```

调用方式（已在 `TcpInput` 中使用）：
```java
output.tcp_retransmit_skb(net, tp, tp.tcp_rtx_queue.peek(), 1);
```

---

## 四、改造后调用流图

```
收到 ACK
    │
    └─ tcp_ack()  [TcpInput.java:920]
            │
            ├─ ACK 有效性检查 / 窗口更新
            ├─ tcp_replace_ts_recent()
            ├─ tcp_clean_rtx_queue()         ← RTT 测量，更新 srtt/rttvar/rto
            ├─ tcp_fastretrans_alert()        ← 新增：重复 ACK 检测 → 快速重传/恢复
            └─ tcp_cong_avoid()              ← 新增：慢启动 / 拥塞避免 cwnd 更新

RTO 超时
    └─ tcp_retransmit_timer()  [TcpTimer.java]
            └─ tcp_enter_loss()              ← 新增：ssthresh 折半，cwnd=1

发送数据（已实现，无需改动）
    └─ tcp_write_xmit()  [TcpOutput.java:616]
            ├─ tcp_cwnd_test()               ✅ 已有
            └─ __tcp_transmit_skb()
```

---

## 五、涉及字段总结

| 字段 | 所在类 | 含义 | 现状 |
|------|--------|------|------|
| `snd_cwnd` | `TcpSock` | 拥塞窗口（段数） | ✅ 已有 |
| `snd_ssthresh` | `TcpSock` | 慢启动阈值（段数） | ✅ 已有 |
| `snd_cwnd_clamp` | `TcpSock` | cwnd 上限 | ✅ 已有 |
| `packets_out` | `TcpSock` | 在途段数 | ✅ 已有 |
| `retrans_out` | `TcpSock` | 重传中段数 | ✅ 已有 |
| `total_retrans` | `TcpSock` | 累计重传次数 | ✅ 已有 |
| `mss_cache` | `TcpSock` | 当前 MSS（字节） | ✅ 已有 |
| `snd_cwnd_cnt` | `TcpSock` | AI 阶段累加器 | ❌ 需新增 |
| `dupacks` | `TcpSock` | 重复 ACK 计数 | ❌ 需新增 |
| `high_seq` | `TcpSock` | 进入恢复时的 snd_nxt | ❌ 需新增 |
| `icsk_ca_state` | `TcpSock` | 拥塞控制阶段 | ❌ 需新增 |

---

## 六、改造任务清单

### P0（基础字段与 RTO 处理）

- [x] **3.1** `TcpSock` — 新增 `dupacks`, `high_seq`, `icsk_ca_state`, `snd_cwnd_cnt` 字段 ✅ 已完成
- [x] **3.1** `TcpConstants` — 新增 `TCP_CA_Open/Disorder/CWR/Recovery/Loss` 常量 ✅ 已完成
- [x] **3.8** `TcpInput.tcp_enter_loss()` — ssthresh=`max(snd_cwnd>>1,2)`（Reno），cwnd=`in_flight+1`，`snd_cwnd_stamp` 已更新，集成到 `tcp_retransmit_timer()` ✅ 已完成（内核对标修正）
- [x] **3.2** `TcpOutput.tcp_cwnd_test()` — ✅ 已实现（`TcpOutput.java:587`），发送路径已约束

### P1（慢启动/拥塞避免，依赖 P0）

- [x] **3.3** `TcpInput.tcp_slow_start()` — 实现慢启动 cwnd 增长 ✅ 已完成
- [x] **3.4** `TcpInput.tcp_cong_avoid_ai()` — 实现拥塞避免 AI 增长 ✅ 已完成
- [x] **3.5** `TcpInput.tcp_cong_avoid()` — 统一入口，集成到 `tcp_ack()` 末尾 ✅ 已完成

### P1（快速重传/恢复，依赖 P0）

- [x] **3.9** `TcpOutput.tcp_retransmit_skb()` — 实现段重传，标记 `TCPCB_RETRANS` ✅ 已完成
- [x] **3.6** `TcpInput.tcp_fastretrans_alert()` — 重复 ACK 检测，集成到 `tcp_ack()`；新增 Loss 状态分支（Loss 期间 dup ACK 不触发快速恢复）✅ 已完成（内核对标修正）
- [x] **3.7** `TcpInput.tcp_enter_fast_recovery()` — 进入快速恢复，ssthresh=`max(snd_cwnd>>1,2)`（Reno），调用 `tcp_retransmit_skb()` ✅ 已完成（内核对标修正）
- [x] **3.7** `TcpInput.tcp_update_cwnd_recovery()` — 部分 ACK 处理（NewReno，RFC 6582）✅ 已完成
- [x] **3.7** `TcpInput.tcp_exit_fast_recovery()` — 退出快速恢复，`cwnd = ssthresh` ✅ 已完成

---

## 七、改造后效果

| 场景 | 改造前 | 改造后 |
|------|--------|--------|
| 连接建立后发送 | cwnd 已约束（IW10）| 慢启动线性增长后进入拥塞避免 |
| 3 个重复 ACK | 无响应，等待 RTO | 立即重传（快速重传），cwnd 减半进入恢复 |
| RTO 超时 | cwnd 不变，重传后继续 | cwnd=in_flight+1，ssthresh 折半，重启慢启动（Linux v6.x 行为）|
| 高带宽延迟网络 | cwnd 不增长 | 慢启动快速探测，拥塞避免平稳填充 |
