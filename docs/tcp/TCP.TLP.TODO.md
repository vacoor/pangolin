# TLP 实现方案

> **规范依据**：RFC 8985 §5 (TLP) + Linux `net/ipv4/tcp_output.c` / `tcp_input.c`（v6.x）  
> **问题编号**：TCP.REVIEW.md § H2  
> **优先级**：P2（影响尾部丢包恢复延迟，不影响连接正确性）

---

## 一、背景

TLP（Tail Loss Probe）解决**尾部丢包**场景：当 TCP 连接最后几个段丢失时，
对端无法产生 dupACK，只能等 RTO 超时恢复（通常 200ms+）。
TLP 在 `2×SRTT` 后主动发送探测包，让恢复时间降至毫秒级。

当前代码缺陷（双重空实现）：
- `TcpOutput.java:738` — 调度端 `tcp_schedule_loss_probe()` 被注释
- `TcpOutput.java:1395` — 执行端 `tcp_send_loss_probe()` 方法体为空
- `TcpInput.java:1194` — ACK 确认端 `tcp_process_tlp_ack()` 被注释

---

## 二、需要修改的文件

| 文件 | 变更内容 |
|------|---------|
| `TcpSock.java` | 新增 `tlp_high_seq` 字段 |
| `TcpOutput.java` | 实现 `tcp_schedule_loss_probe()` + `tcp_send_loss_probe()`，取消注释调用 |
| `TcpInput.java` | 实现 `tcp_process_tlp_ack()`，取消注释调用 |

---

## 三、详细改动

### 3.1 `TcpSock.java` — 新增状态字段

```java
/**
 * SND.NXT at the time the TLP probe was sent.
 * 0 means no probe is in flight.
 * Used by tcp_process_tlp_ack() to detect whether an ACK confirms the probe.
 *
 * @see <a href="https://github.com/torvalds/linux/blob/master/include/linux/tcp.h">tlp_high_seq</a>
 */
public int tlp_high_seq;
```

位置：紧跟 `snd_nxt` 字段（约第 175 行）。

---

### 3.2 `TcpOutput.java` — 实现调度端

**新增方法** `tcp_schedule_loss_probe(boolean advancing_rto)`，参考 Linux
`tcp_output.c:tcp_schedule_loss_probe()`：

```java
/**
 * Schedule a loss probe after 2×SRTT (or RTO if SRTT is unavailable).
 * Called from tcp_write_xmit() after each successful transmission.
 *
 * @param advancing_rto true when called from RTO rescheduling path
 * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2759">tcp_schedule_loss_probe</a>
 */
private boolean tcp_schedule_loss_probe(TcpSock tp, boolean advancing_rto) {
    // 前置条件检查（RFC 8985 §5.2）
    if (tp.icsk_pending == ICSK_TIME_RETRANS) {
        // 已有重传定时器，不抢占
        return false;
    }

    // 必须有在途数据（packets_out > 0）且发送队列已空（真正的"尾部"）
    if (tp.packets_out == 0 || !tp.sk_write_queue_empty()) {
        return false;
    }

    // 计算探测超时：2×SRTT，最小 2×TCP_RTO_MIN
    // 若 SRTT 尚未采样（srtt_us == 0），退化为 RTO
    long timeout;
    if (tp.srtt_us != 0) {
        timeout = usecs_to_jiffies(2L * (tp.srtt_us >> 3));
        timeout = Math.max(timeout, 2L * TCP_RTO_MIN);
    } else {
        // 无 SRTT 样本，用 RTO
        timeout = tp.icsk_rto;
    }
    // 不超过 RTO 上限
    timeout = Math.min(timeout, (long) tp.icsk_rto_max);

    tp.tcp_reset_xmit_timer(multiplexer.timer, ICSK_TIME_LOSS_PROBE, (int) timeout, false);
    return true;
}
```

**取消注释调用**（`TcpOutput.java:738`）：

```java
/* Send one loss probe per tail loss episode. */
if (push_one != 2) {
    tcp_schedule_loss_probe(tp, false);   // ← 取消注释，改为实际调用
}
```

---

### 3.3 `TcpOutput.java` — 实现执行端

**实现** `tcp_send_loss_probe()`，参考 Linux
`tcp_output.c:tcp_send_loss_probe()`：

```java
/**
 * Send a Tail Loss Probe: retransmit the last unacknowledged segment,
 * or send new data if available.  Records tlp_high_seq so that a
 * subsequent ACK can be recognized as a TLP ACK.
 *
 * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c#L2796">tcp_send_loss_probe</a>
 */
public void tcp_send_loss_probe() {
    final TcpSock tp = multiplexer.sock;

    // 若有新数据可发，优先发新数据（作为探测）
    if (!tp.sk_write_queue_empty()) {
        // push_one=1：只发一个段
        if (!tcp_write_xmit(net, tp, tp.tcp_current_mss(), 1, 0)) {
            // 发送成功，tlp_high_seq 已在 tcp_event_new_data_sent 更新为 snd_nxt
            tp.tlp_high_seq = tp.snd_nxt;
            return;
        }
    }

    // 无新数据，重传 RTX 队列尾部（最后一个未确认段）
    TcpBuffer skb = tcp_rtx_queue_tail(tp);
    if (skb == null) {
        // 队列为空，不应到达此处；回退到重置 RTO
        tcp_rearm_rto(tp, multiplexer.timer);
        return;
    }

    // 重传该段
    if (0 != tcp_retransmit_skb(net, tp, skb, 1)) {
        // 重传失败（如被 cwnd 限制），回退到 RTO
        tcp_rearm_rto(tp, multiplexer.timer);
        return;
    }

    // 记录探测序列号：probe 覆盖到 skb 的末尾序号
    tp.tlp_high_seq = determineEndSeq(skb);
}

/** 返回 RTX 队列中最后一个元素（尾部段）。*/
private TcpBuffer tcp_rtx_queue_tail(TcpSock tp) {
    return tp.tcp_rtx_queue.peekLast();
}
```

---

### 3.4 `TcpInput.java` — 实现 ACK 确认端

**实现** `tcp_process_tlp_ack(int ack, int flag)`，参考 Linux
`tcp_input.c:tcp_process_tlp_ack()`：

```java
/**
 * Check whether an incoming ACK confirms a TLP probe.
 * If so, clear tlp_high_seq and allow the normal ACK processing path
 * to handle loss detection (RACK / fast-retransmit).
 *
 * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3495">tcp_process_tlp_ack</a>
 */
private void tcp_process_tlp_ack(TcpSock tp, int ack, int flag) {
    if (tp.tlp_high_seq == 0) {
        return;
    }

    // ACK 覆盖了探测序号（after(ack, tlp_high_seq) 或等于）
    // 说明探测包已被对端确认：连接正常，清除 tlp_high_seq
    if (0 != (flag & (FLAG_DATA_ACKED | FLAG_LOST_RETRANS))) {
        // 探测触发了新的 ACK，尾部丢包已恢复
        tp.tlp_high_seq = 0;
        return;
    }

    // ACK 恰好止步于 tlp_high_seq 之前：说明探测包本身也丢了，
    // 由 RACK 或 RTO 接管，此处只清零避免状态残留
    if (after(ack, tp.tlp_high_seq) || ack == tp.tlp_high_seq) {
        tp.tlp_high_seq = 0;
    }
}
```

**取消注释调用**（`TcpInput.java:1194`）：

```java
if (tp.tlp_high_seq != 0) {
    tcp_process_tlp_ack(tp, ack, flag);   // ← 取消注释，改为实际调用
}
```

---

## 四、改动量汇总

| 步骤 | 文件 | 改动 | 行数估计 |
|------|------|------|---------|
| 3.1 | `TcpSock.java` | 新增 `tlp_high_seq` 字段 | 1 行 |
| 3.2 | `TcpOutput.java` | 新增 `tcp_schedule_loss_probe()` + 取消注释调用 | ~30 行 |
| 3.3 | `TcpOutput.java` | 实现 `tcp_send_loss_probe()` + `tcp_rtx_queue_tail()` | ~30 行 |
| 3.4 | `TcpInput.java` | 实现 `tcp_process_tlp_ack()` + 取消注释调用 | ~25 行 |

---

## 五、注意事项

### 5.1 与 `tcp_event_new_data_sent` 的交互

`TcpOutput.java:73` 已有逻辑：

```java
if (prior_packets <= 0 || tp.icsk_pending == ICSK_TIME_LOSS_PROBE) {
    tcp_rearm_rto(tp, multiplexer.timer);
}
```

当探测发出后 ACK 到来，`tcp_rearm_rto` 会用 RTO 替换 TLP 定时器——这是正确行为，
无需修改。

### 5.2 `tlp_high_seq` 在重置时机

以下情况需清零 `tlp_high_seq`：
- `tcp_process_tlp_ack()` 确认探测（已在 3.4 处理）
- 连接 CLOSE / RESET 时（`TcpSock` 生命周期结束，字段随对象回收，无需额外处理）

### 5.3 `tcp_send_loss_probe` 中发新数据路径

调用 `tcp_write_xmit(push_one=1)` 时，`tcp_event_new_data_sent` 会更新
`snd_nxt`，因此 `tp.tlp_high_seq = tp.snd_nxt` 需在 `tcp_write_xmit` 返回后赋值。

### 5.4 暂不需要实现的部分

- **RACK 联动**（`tcp_rack_reo_timeout`）：`TcpTimer.java:270` 已有 FIXME，
  TLP 实现后尾部丢包可由 TLP 兜底，RACK 可独立后续跟进。
- **`snd_nxt` TLP 新数据路径的 `tlp_high_seq` 更新**：
  如果 `tcp_write_xmit` 内部已设置 `snd_nxt`，在返回后直接用 `tp.snd_nxt` 即可，
  不需要在 `tcp_event_new_data_sent` 中额外记录。

---

## 六、验证方法

手动验证场景（无自动化测试框架时）：

1. 在高丢包环境（tc netem loss 10%）下跑 HTTP 代理请求，对比 TLP 前后
   `netstat -s | grep "fast retransmits"` 与 RTO 触发次数。
2. 在 `tcp_send_loss_probe()` 入口加日志，确认定时器触发且探测包被发出。
3. 在 `tcp_process_tlp_ack()` 加日志，确认探测 ACK 被正确识别并清零 `tlp_high_seq`。
