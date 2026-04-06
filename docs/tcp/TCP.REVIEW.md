# TCP 协议栈对比审查报告

> 对标基准：Linux 内核 `net/ipv4/tcp_*.c`（v6.x）  
> 审查日期：2026-04-05  
> 审查范围：`TcpInput.java`, `TcpOutput.java`, `TcpTimer.java`, `TcpDemultiplexer.java`, `TcpSock.java`, `SysctlOptions.java`  
> 前置修复：Bug 1–4、D2、D3 已在本轮修复（详见 `TCP.TODO2.md`、`tcp_space.TODO.md`）

---

## 一、已修复问题汇总（本轮）

| 编号 | 问题 | 修复 commit | 文件:行 |
|------|------|-------------|---------|
| Bug 1 | RTO 无指数退避 | `129793e5` | `TcpTimer.java:372-373` |
| Bug 2 | delayed ACK timeout 自赋值（no-op） | `6090bfe4` | `TcpOutput.java:1259` |
| Bug 3 | `seq_rtt_us`/`ca_rtt_us` 初始值 0 而非 -1 | `ea8cfae4` | `TcpInput.java:810-811` |
| Bug 4 | 零窗口下裸 FIN 被拒 | `9c3dd9dd` | `TcpInput.java:1618` |
| D2 | `__tcp_ack_snd_check` 两段 ACK 规则缺失 | `68df47a6` | `TcpInput.java:1723-1726` |
| D3 | `tcp_space` 未减去 OFO 队列占用 | `d71c0458` | `TcpOutput.java:972` |
| H1 | `tcp_retries2 = 5`（应为 15） | — | `SysctlOptions.java:14` |
| L7 | `tcp_clean_rtx_queue` 返回 0 丢失 flag | — | `TcpInput.java:893` |

---

## 二、新发现问题

### 严重程度定义

- **High**：影响连接可用性、数据正确性或导致资源耗尽
- **Medium**：影响性能或与 Linux 行为明显偏差，但不影响正确性
- **Low**：空实现（有 stub）或细节差异，实际影响有限

---

### HIGH — 影响连接可用性

#### ~~H1. `ipv4_sysctl_tcp_retries2 = 5`（应为 15）~~ — ✅ 已修复
**文件**：`SysctlOptions.java:14`  
**Linux 对标**：`/proc/sys/net/ipv4/tcp_retries2` 默认值 15

- ~~当前值 5 导致 `tcp_write_timeout()` 在约 30 秒后放弃连接（模型超时 ~31s），
  而 Linux 默认 15 在约 15 分钟后才超时。~~
- **已修复**：`ipv4_sysctl_tcp_retries2` 改为 15，与 Linux 默认值一致。
- `tcp_retries1` 设为 5（Linux 默认 3），当前代码中 retries1 相关路径（MTU 探测、路由重置）被注释，影响较小。

---

#### H2. `tcp_send_loss_probe()` 空实现
**文件**：`TcpOutput.java:1395-1397`  
**Linux 对标**：`tcp_output.c:tcp_send_loss_probe()`

- TLP 定时器 `ICSK_TIME_LOSS_PROBE` 已定义并在 `tcp_write_timer_handler` 中调度，
  但触发后调用的 `tcp_send_loss_probe()` 是空方法，不发送任何探测报文。
- 尾部丢包（连接最后几个段）只能等待 RTO 超时（通常 200ms+），而 TLP 本应在 2×SRTT 内探测。
- 与 `TCP.TODO.md` §2.6 已记录。

---

#### H3. `__tcp_retransmit_skb` 无分片/裁剪
**文件**：`TcpOutput.java:1096-1101`  
**Linux 对标**：`tcp_output.c:__tcp_retransmit_skb()` 中的 `tcp_fragment()` 调用

```java
if (skbLen > len) {
    // TODO
    // fragment
} else {
```

- 当重传段大于可用窗口时，Linux 会调用 `tcp_fragment()` 将 skb 拆分。
  当前代码跳过分片，原始大段被完整重传，可能超出接收方窗口导致被丢弃。
- 影响场景：窗口收缩后的重传、MSS 变化后的重传。

---

#### H4. `tso_fragment()` 始终返回 false
**文件**：`TcpOutput.java:757-759`  
**Linux 对标**：`tcp_output.c:tso_fragment()`

- `tcp_write_xmit()` 中 `if (skbLen > limit && tso_fragment(...))` 永远不触发分片。
- 当单个 skb 包含多个 MSS 的数据时（通过 `tcp_grow_skb` 合并），发送路径无法将其拆分。
- 与 H3 结合，大段的发送和重传都缺乏分片支持。

---

### MEDIUM — 影响性能

#### M1. `tcp_nagle_test()` 始终返回 true
**文件**：`TcpOutput.java:778-792`  
**Linux 对标**：`tcp_output.c:tcp_nagle_test()`

- 仅检查 `TCP_NAGLE_PUSH` 后直接 `return true`，Nagle 算法的核心逻辑（小包合并）被跳过。
- 所有小包立即发送，增加不必要的报文数量。
- 对于代理场景，数据到达即转发，Nagle 反而有害（增加延迟），所以实际影响有限。

---

#### M2. `tcp_skb_is_last()` 始终返回 false
**文件**：`TcpOutput.java:773-776`  
**Linux 对标**：`tcp_output.c:tcp_skb_is_last()`

- 被 `tcp_write_xmit()` 中 Nagle 调用链使用：`tcp_nagle_test(skb, mss_now, tcp_skb_is_last(skb) ? nonagle : TCP_NAGLE_PUSH)`。
- 由于 M1（Nagle 总返回 true），当前无实际影响。
- 如果将来修复 Nagle 实现，此处需同步修复。

---

#### M3. `tcp_cwnd_validate()` 空实现
**文件**：`TcpOutput.java:749-751`  
**Linux 对标**：`tcp_output.c:tcp_cwnd_validate()`

- Linux 在空闲后会衰减 cwnd（RFC 7661 / Linux CWV），防止突发大量报文。
- 当前代码中 cwnd 在连接空闲后保持不变，恢复发送时可能造成短暂突发。

---

#### M4. `tcp_in_cwnd_reduction()` 始终返回 false
**文件**：`TcpOutput.java:745-747`  
**Linux 对标**：`tcp_output.c:tcp_in_cwnd_reduction()`

- 导致 PRR（Proportional Rate Reduction）逻辑永远不生效。
- 快速恢复期间 `prr_out` 不会被更新。
- 实际影响较小——当前使用 NewReno 恢复，PRR 是更精细的速率控制。

---

#### M5. `tcp_mss_split_point()` 返回 mss_now
**文件**：`TcpOutput.java:761-763`  
**Linux 对标**：`tcp_output.c:tcp_mss_split_point()`

- Linux 实现计算 `min(cwnd_quota * mss, window_end - seq)`，确保不超过 cwnd 和窗口限制。
- 当前固定返回 mss_now，tso 段的拆分点不正确。
- 与 H4 (`tso_fragment` 空) 结合，大段既不拆分也不限制。

---

#### M6. `tcp_tso_segs()` 返回 `Integer.MAX_VALUE`
**文件**：`TcpOutput.java:812-816`  
**Linux 对标**：`tcp_output.c:tcp_tso_segs()`

- Linux 返回 `min(gso_max_segs, cwnd>>1)` 以限制单次发送的段数（突发控制）。
- `MAX_VALUE` 意味着无突发限制。
- `cwnd_quota` 已在 `tcp_cwnd_test()` 中限制，实际突发受 cwnd 约束，影响有限。

---

#### M7. `tcp_skb_pcount()` 始终返回 1
**文件**：`TcpOutput.java:1016-1018`  
**Linux 对标**：`tcp_output.c:tcp_skb_pcount()`

- 不支持 TSO/GSO——每个 skb 只计为 1 个段。
- `packets_out` 等于 skb 数量而非逻辑段数。
- 由于不支持 TSO（Java/pcap4j 限制），这是一致的设计选择，但会影响将来的 TSO 支持。

---

#### M8. OFO `TreeMap` 比较器溢出风险
**文件**：`TcpSock.java:251`

```java
new TreeMap<>((a, b) -> a - b)
```

- 32 位有符号减法在序列号距离超过 `Integer.MAX_VALUE` 时会溢出，违反 `Comparator` 的传递性约定。
- 例如：`a = 0x00000001`, `b = 0x80000002` → `a - b = 0x7FFFFFFF`（正数，认为 a > b），但实际上 `after(a, b)` 可能为 false。
- TreeMap 在违反传递性时可能抛出异常或返回错误结果。
- 正常情况下 OFO 队列内序列号距离远小于 2^31（窗口最大 ~4MB），实际触发概率很低。

**建议**：改为 `Integer.compare(a - base, b - base)` 或使用 `before(a, b) ? -1 : (a == b ? 0 : 1)`。

---

#### M9. `tcp_left_out()` 返回 0
**文件**：`TcpSock.java:268-271`  
**Linux 对标**：`tcp.h:tcp_left_out()` → `sacked_out + lost_out`

- `tcp_packets_in_flight() = packets_out - tcp_left_out() + retrans_out`。
- `tcp_left_out()` 返回 0 导致 in_flight 略有高估（不扣除 SACK 标记的已离开网络的段）。
- 是 SACK 未实现的直接后果，已在 `TCP.TODO.md` §2.1/§2.13 记录。

---

#### M10. `tcp_update_rtt_min()` 实质无效
**文件**：`TcpInput.java:676-690`  
**Linux 对标**：`tcp_input.c:tcp_update_rtt_min()`

- 核心调用 `minmax_running_min()` 被注释掉（标注 FIXME）。
- `rtt_min` 不会被更新，影响 BBR 等依赖 min_rtt 的拥塞控制算法。
- 当前使用 Reno，影响有限。

---

#### M11. `tcp_check_space()` 空实现
**文件**：`TcpInput.java:1698-1700`  
**Linux 对标**：`tcp_input.c:tcp_check_space()`

- Linux 中此函数触发接收缓冲区自动调整（autotuning）和唤醒接收方。
- 代理模式下数据到达即转发，不存在接收队列堆积，影响有限。

---

#### M12. `tcp_grow_window()` 被注释
**文件**：`TcpInput.java:306-308`

```java
if (pkt.tcpPayloadLength() >= 128) {
    // tcp_grow_window(sk, skb, true);
}
```

- 接收窗口不会根据数据量动态增长。
- `rcv_ssthresh` 在初始值后只会被 `tcp_adjust_rcv_ssthresh` 缩小，不会增大。
- 长连接吞吐量可能受限于初始 `rcv_ssthresh` 值。

---

#### M13. `tcp_pacing_check()` 逻辑
**文件**：`TcpOutput.java:818-823`

```java
private boolean tcp_pacing_check(TcpSock tp) {
    if (tp.tcp_wstamp_ns <= tp.tcp_clock_cache) {
        return false;
    }
    return true;
}
```

- Linux 中 pacing 由 fq qdisc 或 internal pacing 实现，此函数检查是否需要延迟发送。
- 当 `tcp_wstamp_ns > tcp_clock_cache` 时返回 true（暂停发送），但由于 `tcp_mstamp_refresh()` 在 `tcp_write_xmit()` 开头刷新了 `tcp_clock_cache`，此条件几乎不会成立。
- 效果等同于无 pacing。对于代理场景不是问题。

---

### LOW — 空桩或细节差异

#### L1. `tcp_send_fin()` 不合并最后一个 skb
**文件**：`TcpOutput.java:1151-1155`  
**Linux 对标**：`tcp_output.c:tcp_send_fin()` 尝试将 FIN 合并到发送队列最后一个 skb

- 当前总是创建新的 FIN skb。多一个独立段，但不影响正确性。

---

#### L2. `tcp_small_queue_check()` 返回 false
**文件**：`TcpOutput.java:753-755`  
**Linux 对标**：`tcp_output.c:tcp_small_queue_check()`

- 小队列检查用于限制每个 socket 的发送缓冲，防止单连接独占 qdisc。
- 代理通过 Netty Channel 写入 TUN，无 qdisc，不适用。

---

#### L3. `tcp_rcv_established()` 空（无快速路径）
**文件**：`TcpInput.java:1922-1933`  
**Linux 对标**：`tcp_input.c:tcp_rcv_established()` 的 header prediction 快速路径

- 所有报文走 `tcp_rcv_state_process()` 慢路径。
- Java 代理中快速路径的收益远低于内核（无系统调用开销、JIT 会优化热路径）。
- 已在 `TCP.TODO2.md` D1 分析中确认不需要实现。

---

#### L4. `tcp_in_ack_event()` 空
**文件**：`TcpInput.java:1079-1081`  
**Linux 对标**：`tcp_input.c:tcp_in_ack_event()`

- 用于通知拥塞控制模块 ACK 事件（`icsk_ca_ops->in_ack_event`）。
- 当前使用硬编码 Reno，不需要此回调。
- 引入可插拔拥塞控制（CUBIC/BBR）时需实现。

---

#### L5. `tcp_count_delivered()` 不完整
**文件**：`TcpInput.java:160-163`

```java
private void tcp_count_delivered(TcpSock tp, int delivered, boolean ece_ack) {
    tp.delivered += delivered;
    // FIXME
}
```

- ECE ACK 计数（`delivered_ce`）缺失。
- 与 ECN 未实现一致，ECN 实现时需补全。

---

#### L6. `tcp_sack_compress_send_ack()` 空
**文件**：`TcpInput.java:1403-1405`  
**Linux 对标**：`tcp_input.c:tcp_sack_compress_send_ack()`

- SACK 压缩 ACK 功能，依赖 SACK 完整实现。

---

#### ~~L7. `tcp_clean_rtx_queue()` 返回值始终为 0~~ — ✅ 已修复
**文件**：`TcpInput.java:893`

- Linux 返回 flag（含 FLAG_DATA_ACKED 等），当前返回 0 但 flag 通过 `|=` 合并到 `tcp_ack()` 的 flag 中。
- 实际上 `tcp_ack()` 行 1188 `flag |= tcp_clean_rtx_queue(...)` 会将 0 合并，不影响结果——因为 flag 已在 `tcp_ack_update_window()` 中设置了 `FLAG_DATA_ACKED`。
- **等等**——再看一遍：`tcp_clean_rtx_queue` 内部计算了 `flag` 但只 `return 0`，没有返回 flag！这意味着 `FLAG_DATA_ACKED`、`FLAG_SYN_ACKED`、`FLAG_RETRANS_DATA_ACKED` 不会传递到 `tcp_ack()`。

**影响**：
- `FLAG_DATA_ACKED` 不会被设置 → `tcp_ack_update_rtt()` 中 timestamp RTT 路径检查 `flag & FLAG_DATA_ACKED` 失败 → **当 seq_rtt 和 sack_rtt 均为 -1 时，timestamp RTT 不会被采样**。
- 实际上，`seq_rtt_us` 在 `tcp_clean_rtx_queue` 中通过 `first_ackt` 计算并传给 `tcp_ack_update_rtt()`，所以直接 RTT 采样不受影响。
- 但 `FLAG_RETRANS_DATA_ACKED` 丢失可能影响拥塞控制逻辑（当前未使用此 flag）。
- **严重性调整为 Medium**：~~应修复返回值，改为 `return flag`~~ ✅ 已修复。

---

## 三、按优先级排序的修复建议

### P0 — 立即修复（影响连接可用性）

| # | 问题 | 修复方案 | 改动量 |
|---|------|---------|--------|
| ~~H1~~ | ~~`tcp_retries2 = 5`~~ | ✅ 已改为 15 | 1 行 |
| ~~L7→M~~ | ~~`tcp_clean_rtx_queue` 返回 0~~ | ✅ 已改为 `return flag` | 1 行 |

### P1 — 短期修复（影响性能/正确性）

| # | 问题 | 修复方案 | 改动量 |
|---|------|---------|--------|
| H3 | 重传无分片 | 实现简化的 `tcp_fragment()` | 中等 |
| H4 | `tso_fragment` 空 | 同上，或至少在 `tcp_write_xmit` 中防止 skb 超过 limit | 中等 |
| M8 | OFO TreeMap 比较器 | 改为安全的序列号比较 | 1 行 |
| M12 | `tcp_grow_window` 注释 | 取消注释并实现简化版 | 小 |

### P2 — 中期优化

| # | 问题 | 说明 |
|---|------|------|
| H2 | TLP 空实现 | 需实现 `tcp_schedule_loss_probe` + `tcp_send_loss_probe` |
| M3 | cwnd 空闲衰减 | 实现 `tcp_cwnd_validate` |
| M10 | min_rtt 无效 | 实现 `minmax_running_min`，为 BBR 做准备 |
| M6 | tso_segs 无限制 | 改为 `min(gso_max, cwnd >> 1)` |

### P3 — 长期/低优先级

M1 (Nagle)、M2、M4 (PRR)、M5、M7、M11、M13 及所有 Low 项。

---

## 四、与 TCP.TODO.md 校准

以下条目需在 `TCP.TODO.md` 中修正：

| 条目 | 现状描述 | 修正 |
|------|---------|------|
| §2.0.3 OFO 队列无大小限制 | "ofo_queue_bytes 从未被检查或更新" | **已修正**：`ofo_queue_bytes` 在 `tcp_data_queue_ofo`（+）、`tcp_ofo_queue`（-）、`tcp_prune_ofo_queue`（-）中正确维护；`OFO_MAX_BYTES = 256KB` 预算检查已实现 |
| §三 RFC 9293 行 | "OFO 队列无大小限制" | **已修正**：OFO 队列有 `OFO_MAX_BYTES` 限制 |
| §三 RFC 6298 行 | — | 应注明 Bug 1（RTO 退避）已修复 |
| §四 P1 OFO 队列 | "无限堆积存在内存耗尽风险" | **已修正**：`OFO_MAX_BYTES` 限制 + `tcp_prune_ofo_queue` 裁剪 |

---

## 五、总结

| 严重程度 | 数量 | 需立即修复 |
|---------|------|-----------|
| High | 4 | H1 ✅、H3/H4（中等）、H2（已记录） |
| Medium | 13 | M8（1行）、L7→M ✅ |
| Low | 6 | 无急迫需求 |

~~最小改动修复路径：**H1 + L7→M**（共 2 行代码），可立即提升连接稳定性和 RTT 采样准确性。~~ ✅ 均已修复。
