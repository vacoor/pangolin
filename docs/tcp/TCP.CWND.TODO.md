# TCP 拥塞控制（CWND）改造方案

> 对标基准：Linux 内核 `net/ipv4/tcp_output.c`, `tcp_input.c`, `tcp_cong.c`（v6.x）  
> RFC：**RFC 5681**（TCP Congestion Control）, **RFC 6582**（NewReno）, **RFC 6928**（IW10）  
> 分析日期：2026-04-03

---

## 一、现状

| 能力 | 现状 |
|------|------|
| `snd_cwnd` / `snd_ssthresh` 字段 | ✅ 已定义（`TcpSock.java`） |
| 初始 cwnd（IW10） | ✅ `tcp_init_cwnd()` 已实现 |
| **发送路径 cwnd 约束** | ❌ `__tcp_transmit_skb()` 无 cwnd 检查，**发送不受 cwnd 限制** |
| 慢启动（Slow Start） | ❌ 未实现 |
| 拥塞避免（Congestion Avoidance） | ❌ 未实现 |
| 快速重传（Fast Retransmit） | ❌ 未实现（3 个重复 ACK 无响应）|
| 快速恢复（Fast Recovery/NewReno） | ❌ 未实现 |
| 重传后 ssthresh 折半 | ❌ 未实现 |
| `tcp_cong_avoid()` 接口 | ❌ 未实现 |

**关键代码定位：**
- `TcpSock.java:75` — `snd_cwnd`
- `TcpSock.java:96` — `snd_ssthresh`
- `TcpOutput.java:329` — `__tcp_transmit_skb()`（无 cwnd 检查）
- `TcpInput.java:818` — `tcp_ack()`（无慢启动/拥塞避免更新）
- `TcpInput.java:528` — `tcp_clean_rtx_queue()`（RTT 测量完成，返回后应更新 cwnd）
- `TcpTimer.java:280` — `tcp_write_timer_handler()`（RTO 超时，应触发 ssthresh 折半）

---

## 二、改造目标

按 RFC 5681 + RFC 6582（NewReno）实现完整拥塞控制：

1. **发送限制**：发送字节数受 `min(snd_wnd, snd_cwnd × mss)` 约束
2. **慢启动**：`cwnd < ssthresh` 时每收一个 ACK，`cwnd += min(N, SMSS)`
3. **拥塞避免**：`cwnd >= ssthresh` 时每个 RTT，`cwnd += SMSS²/cwnd`
4. **快速重传**：3 个重复 ACK 后立即重传最旧未确认段
5. **快速恢复**：进入恢复阶段，`ssthresh = max(FlightSize/2, 2*SMSS)`，`cwnd = ssthresh + 3*SMSS`
6. **恢复退出**：收到新 ACK 后 `cwnd = ssthresh`，退出快速恢复
7. **RTO 超时**：`ssthresh = max(FlightSize/2, 2*SMSS)`，`cwnd = 1*SMSS`，进入慢启动

---

## 三、改造方案

### 3.1 TcpSock 补充拥塞控制字段

**Linux 对标**：`include/linux/tcp.h`

需在 `TcpSock.java` 中补充或确认以下字段：

```java
// --- 拥塞控制状态字段 ---

// 重复 ACK 计数（快速重传触发阈值 = 3）
// @see net/ipv4/tcp_input.c:tcp_ack() → tp->duplicate_sack
public int dupacks;                     // 需新增

// 快速恢复/快速重传的恢复点序列号
// RFC 5681 §3.2: recovery_point = SND.NXT at the time fast retransmit is invoked
public int high_seq;                    // 需确认（Linux: tp->high_seq）

// 当前拥塞控制阶段标志
// CA_Open=0, CA_Disorder=1, CA_CWR=2, CA_Recovery=3, CA_Loss=4
public int icsk_ca_state;              // 需新增

// 已在途（已发送未 ACK）的字节数（近似 = packets_out * mss）
// Linux: tcp_packets_in_flight(tp) = packets_out - retrans_out - ...
// 简化版：直接用 packets_out * mss_cache
// 已有 packets_out 字段，无需新增

// cwnd 已有（TcpSock.java:75）：
// public int snd_cwnd;
// public int snd_ssthresh;
// public int snd_cwnd_clamp;   // 已有（TcpSock.java:174）
```

**拥塞阶段常量**（新建或加入 `TcpConstants.java`）：
```java
int TCP_CA_Open     = 0;  // 正常状态
int TCP_CA_Disorder = 1;  // 检测到重复 ACK 或 SACK，未确认丢失
int TCP_CA_CWR      = 2;  // 拥塞窗口缩减（ECN/SACK 触发）
int TCP_CA_Recovery = 3;  // 快速恢复中
int TCP_CA_Loss     = 4;  // RTO 超时后的丢失恢复
```

---

### 3.2 发送路径：cwnd 约束

**Linux 对标**：`net/ipv4/tcp_output.c:tcp_cwnd_test()`, `tcp_write_xmit()`

**问题**：当前 `__tcp_transmit_skb()` 直接发送，不检查 cwnd。应在调用 `__tcp_transmit_skb()` 的上游（`tcp_write_xmit()` / `__tcp_push_pending_frames()`）添加约束。

**方案**（在 `TcpOutput.java` 中实现 `tcp_cwnd_test()`）：

```java
/**
 * RFC 5681 §3.1: The sender MUST NOT send data such that cwnd + duplicate
 * segments exceed the lesser of the receiver's advertised window and the
 * congestion window.
 *
 * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2335">tcp_cwnd_test</a>
 */
private boolean tcp_cwnd_test(TcpSock tp) {
    // 在途字节数（已发送未确认）
    int in_flight = tp.packets_out * tp.mss_cache;
    // 可用 cwnd（字节数）
    int cwnd_bytes = tp.snd_cwnd * tp.mss_cache;
    return in_flight < cwnd_bytes;
}
```

**集成到 `__tcp_push_pending_frames()`**：

```java
// 在 TcpOutput.java 中，发送循环里：
while (null != (skb = tp.tcp_send_head())) {
    int limit = tcp_mss_split_point(tp, skb, ...);
    // 检查发送窗口和拥塞窗口
    if (!tcp_snd_wnd_test(tp, skb, mss_now)) break;
    if (!tcp_cwnd_test(tp)) break;              // ← 新增 cwnd 检查
    __tcp_transmit_skb(net, tp, skb, true, tp.rcv_nxt);
    tcp_advance_send_head(tp, skb);
}
```

---

### 3.3 慢启动（Slow Start）

**Linux 对标**：`net/ipv4/tcp_cong.c:tcp_slow_start()`  
**RFC**：RFC 5681 §3.1

```java
/**
 * RFC 5681 §3.1 Slow Start:
 *   cwnd += min(N, SMSS)  for each ACK that acknowledges new data
 * where N = bytes newly acknowledged.
 *
 * Returns the number of segments that could not be processed by slow start
 * (carry-over to congestion avoidance).
 *
 * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_cong.c#L392">tcp_slow_start</a>
 */
private int tcp_slow_start(TcpSock tp, int acked) {
    int cwnd = Math.min(tp.snd_cwnd + acked, tp.snd_ssthresh);
    acked -= cwnd - tp.snd_cwnd;
    tp.snd_cwnd = Math.min(cwnd, tp.snd_cwnd_clamp);
    return acked;                                // 剩余 acked 进入拥塞避免
}
```

---

### 3.4 拥塞避免（Congestion Avoidance）

**Linux 对标**：`net/ipv4/tcp_cong.c:tcp_cong_avoid_ai()`  
**RFC**：RFC 5681 §3.1（AI 阶段：每 RTT cwnd += SMSS）

```java
/**
 * RFC 5681 §3.1 Congestion Avoidance (Additive Increase):
 *   cwnd += SMSS * SMSS / cwnd  per ACK (approximates cwnd += 1 per RTT)
 *
 * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_cong.c#L361">tcp_cong_avoid_ai</a>
 */
private void tcp_cong_avoid_ai(TcpSock tp, int w, int acked) {
    // Linux 使用 snd_cwnd_cnt 累加，避免浮点
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

**`TcpSock` 补充字段**：
```java
public int snd_cwnd_cnt;  // cwnd 增量累加器（避免浮点），需新增
```

---

### 3.5 cwnd 更新入口：`tcp_cong_avoid()`

**Linux 对标**：`net/ipv4/tcp_input.c:tcp_cong_avoid()`（调用具体算法）

在 `TcpInput.java` 的 `tcp_ack()` 末尾（清理重传队列后）调用：

```java
/**
 * Called after new ACK is processed. Updates cwnd based on current CA state.
 *
 * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3896">tcp_cong_avoid</a>
 */
private void tcp_cong_avoid(TcpSock tp, int ack, int acked) {
    if (tp.icsk_ca_state != TCP_CA_Open
            && tp.icsk_ca_state != TCP_CA_Disorder) {
        return;                                   // Recovery/Loss 阶段不更新 cwnd
    }
    if (tp.snd_cwnd < tp.snd_ssthresh) {
        // 慢启动阶段
        acked = tcp_slow_start(tp, acked);
        if (acked == 0) return;
    }
    // 拥塞避免阶段
    tcp_cong_avoid_ai(tp, tp.snd_cwnd, acked);
}
```

**集成到 `tcp_ack()`（`TcpInput.java:818`）**：

```java
// tcp_ack() 末尾，tcp_clean_rtx_queue() 之后：
if (0 != (flag & FLAG_SND_UNA_ADVANCED)) {       // 有新数据被 ACK
    int newly_acked_sacked = prior_packets_out - tp.packets_out;
    tcp_cong_avoid(tp, ack, newly_acked_sacked);
}
```

---

### 3.6 快速重传（Fast Retransmit）

**Linux 对标**：`net/ipv4/tcp_input.c:tcp_ack()` → `tcp_fastretrans_alert()` → `tcp_retransmit_skb()`  
**RFC**：RFC 5681 §3.2

**重复 ACK 检测**（在 `tcp_ack()` 中）：

```java
/**
 * RFC 5681 §3.2: Fast Retransmit
 * After 3 duplicate ACKs, retransmit the lost segment immediately.
 *
 * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3386">tcp_fastretrans_alert</a>
 */
private void tcp_fastretrans_alert(Channel net, TcpSock tp, int acked, int flag) {
    boolean is_dupack = 0 == (flag & (FLAG_SND_UNA_ADVANCED | FLAG_NOT_DUP));
    // is_dupack: ACK 号没有前进（snd_una 未变）且不是乱序 ACK

    if (tp.icsk_ca_state == TCP_CA_Recovery) {
        // 已在快速恢复中：处理部分 ACK（见 3.7）
        if (0 != (flag & FLAG_SND_UNA_ADVANCED)) {
            tcp_update_cwnd_recovery(net, tp);   // 部分 ACK：继续重传
        }
        return;
    }

    if (is_dupack) {
        tp.dupacks++;
        if (tp.dupacks == 3) {                   // RFC 5681 §3.2: 3 dup ACKs
            tcp_enter_fast_recovery(net, tp);    // 进入快速恢复（见 3.7）
        }
    } else {
        tp.dupacks = 0;                          // 收到新 ACK，重置重复 ACK 计数
    }
}
```

---

### 3.7 快速恢复（Fast Recovery — NewReno）

**Linux 对标**：`net/ipv4/tcp_input.c:tcp_enter_recovery()`, `tcp_fastretrans_alert()`  
**RFC**：RFC 5681 §3.2, RFC 6582（NewReno 部分 ACK 处理）

```java
/**
 * RFC 5681 §3.2: Enter Fast Recovery.
 *   ssthresh = max(FlightSize / 2, 2 * SMSS)
 *   cwnd     = ssthresh + 3 * SMSS
 *   retransmit the lost segment
 *
 * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3317">tcp_enter_recovery</a>
 */
private void tcp_enter_fast_recovery(Channel net, TcpSock tp) {
    int mss = tp.mss_cache;
    // FlightSize = 在途字节数
    int flight_size = tp.packets_out * mss;

    // RFC 5681 §3.2: ssthresh = max(FlightSize/2, 2*SMSS)
    tp.snd_ssthresh = Math.max(flight_size / 2, 2 * mss);

    // RFC 5681 §3.2: cwnd = ssthresh + 3*SMSS（为 3 个重复 ACK "充气"）
    tp.snd_cwnd = tp.snd_ssthresh + 3 * mss / mss; // 以段为单位
    tp.high_seq = tp.snd_nxt;                        // 记录恢复点

    tp.icsk_ca_state = TCP_CA_Recovery;

    log.info("[CWND] Enter FastRecovery: ssthresh={} cwnd={}", tp.snd_ssthresh, tp.snd_cwnd);

    // 立即重传最旧未确认段（Head of retransmit queue）
    tcp_retransmit_skb(net, tp, tp.tcp_rtx_queue.peek());
}

/**
 * RFC 6582: NewReno partial ACK processing during Fast Recovery.
 * A partial ACK advances SND.UNA but does not exit recovery.
 *
 * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3499">tcp_try_undo_partial</a>
 */
private void tcp_update_cwnd_recovery(Channel net, TcpSock tp) {
    if (!after(tp.snd_una, tp.high_seq)) {
        // 部分 ACK（RFC 6582 §3 step 4）：SND.UNA 前进但未超过 high_seq
        // cwnd 减去已离开网络的段数（收缩窗口）
        int delta = tp.snd_cwnd - (int)((tp.snd_nxt - tp.snd_una) / tp.mss_cache);
        if (delta > 0) {
            tp.snd_cwnd -= delta;
        }
        tp.snd_cwnd = Math.max(tp.snd_cwnd, tp.snd_ssthresh);
        // 重传下一个未确认段
        tcp_retransmit_skb(net, tp, tp.tcp_rtx_queue.peek());
    } else {
        // 完整恢复（SND.UNA 超过 high_seq）：退出快速恢复
        tcp_exit_fast_recovery(tp);
    }
}

/**
 * Exit Fast Recovery: cwnd = ssthresh.
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

**Linux 对标**：`net/ipv4/tcp_timer.c:tcp_enter_loss()`  
**RFC**：RFC 5681 §3.1（Retransmission Timeout 处理）

在 `TcpTimer.java` 的 `tcp_write_timer_handler()` 重传超时分支中调用：

```java
/**
 * RFC 5681 §3.1: On Retransmission Timeout (RTO expiry):
 *   ssthresh = max(FlightSize / 2, 2 * SMSS)
 *   cwnd     = 1 * SMSS   (restart slow start)
 *
 * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3199">tcp_enter_loss</a>
 */
void tcp_enter_loss(TcpSock tp) {
    int mss = tp.mss_cache;
    int flight_size = tp.packets_out * mss;

    // RFC 5681 §3.1
    tp.snd_ssthresh = Math.max(flight_size / 2, 2 * mss);
    tp.snd_cwnd     = 1;                         // 单位：段数，重启慢启动

    tp.icsk_ca_state = TCP_CA_Loss;
    tp.high_seq      = tp.snd_nxt;
    tp.dupacks       = 0;

    log.info("[CWND] RTO Loss: ssthresh={} cwnd=1", tp.snd_ssthresh);
}
```

**集成到 `tcp_write_timer_handler()`（`TcpTimer.java:280`）**：
```java
case ICSK_TIME_RETRANS:
    tcp_retransmit_timer(net, tp);
    // 在 tcp_retransmit_timer() 内部超时确认时调用：
    // demultiplexer.input.tcp_enter_loss(tp);
    break;
```

---

### 3.9 `tcp_retransmit_skb()` 实现

**Linux 对标**：`net/ipv4/tcp_output.c:tcp_retransmit_skb()`

快速重传和 RTO 重传共用此方法：

```java
/**
 * Retransmit the given skb from the retransmit queue.
 *
 * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L3301">tcp_retransmit_skb</a>
 */
private int tcp_retransmit_skb(Channel net, TcpSock tp, TcpBuffer skb) {
    if (skb == null) return -1;

    // 标记为已重传
    skb.sacked |= TCPCB_RETRANS | TCPCB_EVER_RETRANS;
    tp.retrans_out++;
    tp.total_retrans++;

    __tcp_transmit_skb(net, tp, skb, true, tp.rcv_nxt);

    log.info("[CWND] Retransmit seq={}", skb.sequenceNumber());
    return 0;
}
```

---

## 四、改造后调用流图

```
收到 ACK
    │
    ├─ tcp_ack()  [TcpInput.java]
    │       │
    │       ├─ ACK 有效性检查
    │       ├─ tcp_ack_update_window()
    │       ├─ tcp_clean_rtx_queue()       ← RTT 测量、更新 srtt/rttvar/rto
    │       ├─ tcp_fastretrans_alert()     ← 新增：检测重复 ACK，触发快速重传
    │       └─ tcp_cong_avoid()            ← 新增：慢启动 / 拥塞避免 cwnd 更新
    │
RTO 超时
    │
    └─ tcp_write_timer_handler()  [TcpTimer.java]
            │
            └─ tcp_retransmit_timer()
                    │
                    └─ tcp_enter_loss()    ← 新增：ssthresh 折半，cwnd=1

发送数据
    │
    └─ __tcp_push_pending_frames()  [TcpOutput.java]
            │
            ├─ tcp_snd_wnd_test()          ← 检查接收方窗口
            ├─ tcp_cwnd_test()             ← 新增：检查拥塞窗口
            └─ __tcp_transmit_skb()        ← 实际发送
```

---

## 五、涉及字段总结

| 字段 | 所在类 | 含义 | 现状 |
|------|--------|------|------|
| `snd_cwnd` | `TcpSock` | 拥塞窗口（段数） | ✅ 已有 |
| `snd_ssthresh` | `TcpSock` | 慢启动阈值（段数） | ✅ 已有 |
| `snd_cwnd_clamp` | `TcpSock` | cwnd 上限 | ✅ 已有 |
| `snd_cwnd_cnt` | `TcpSock` | AI 阶段累加器 | ❌ 需新增 |
| `dupacks` | `TcpSock` | 重复 ACK 计数 | ❌ 需新增 |
| `high_seq` | `TcpSock` | 进入恢复时的 snd_nxt | ❌ 需新增 |
| `icsk_ca_state` | `TcpSock` | 拥塞控制阶段 | ❌ 需新增 |
| `packets_out` | `TcpSock` | 在途段数 | ✅ 已有 |
| `retrans_out` | `TcpSock` | 重传中段数 | ✅ 已有 |
| `total_retrans` | `TcpSock` | 累计重传次数 | ✅ 已有 |
| `mss_cache` | `TcpSock` | 当前 MSS | ✅ 已有 |

---

## 六、改造任务清单

### P0（基础约束，立即可做）

- [ ] **3.1** `TcpSock` — 新增 `dupacks`, `high_seq`, `icsk_ca_state`, `snd_cwnd_cnt` 字段
- [ ] **3.1** `TcpConstants` — 新增 `TCP_CA_Open/Disorder/CWR/Recovery/Loss` 常量
- [ ] **3.2** `TcpOutput.tcp_cwnd_test()` — 实现 cwnd 检查，集成到 `__tcp_push_pending_frames()`
- [ ] **3.8** `TcpInput.tcp_enter_loss()` — RTO 超时 ssthresh 折半、cwnd=1，集成到 `tcp_retransmit_timer()`

### P1（慢启动/拥塞避免，依赖 P0）

- [ ] **3.3** `TcpInput.tcp_slow_start()` — 实现慢启动 cwnd 增长
- [ ] **3.4** `TcpInput.tcp_cong_avoid_ai()` — 实现拥塞避免 AI 增长
- [ ] **3.5** `TcpInput.tcp_cong_avoid()` — 统一入口，集成到 `tcp_ack()` 末尾

### P1（快速重传/恢复，依赖 P0）

- [ ] **3.9** `TcpOutput.tcp_retransmit_skb()` — 实现段重传，标记 `TCPCB_RETRANS`
- [ ] **3.6** `TcpInput.tcp_fastretrans_alert()` — 重复 ACK 检测，集成到 `tcp_ack()`
- [ ] **3.7** `TcpInput.tcp_enter_fast_recovery()` — 进入快速恢复，调用 `tcp_retransmit_skb()`
- [ ] **3.7** `TcpInput.tcp_update_cwnd_recovery()` — 部分 ACK 处理（NewReno，RFC 6582）
- [ ] **3.7** `TcpInput.tcp_exit_fast_recovery()` — 退出快速恢复，`cwnd = ssthresh`

---

## 七、改造后效果

| 场景 | 改造前 | 改造后 |
|------|--------|--------|
| 连接建立后发送 | 无限速发送 | cwnd 从 IW10 开始约束 |
| 无丢包时 | cwnd 不增长 | 慢启动 → 拥塞避免线性增长 |
| 3 个重复 ACK | 无响应，等待 RTO | 立即重传（快速重传），cwnd 减半进入恢复 |
| RTO 超时 | cwnd 不变，重传后继续 | cwnd=1，ssthresh 折半，重启慢启动 |
| 高带宽延迟网络 | 无法充分利用带宽 | 慢启动快速探测，拥塞避免平稳填充 |
