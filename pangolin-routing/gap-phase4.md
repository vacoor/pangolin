# v2 TCP Phase 4 Gap Audit(2026-04-19)

继 Phase 3 全部收敛后做的广义审计,聚焦 Linux 主干 TCP 特性中 v2 仍缺失或是 stub
的部分。每项按 **当前状态 / 对齐点 / 影响 / 建议优先级** 展开。

## 状态速览

| 级别      | 数 | 项目 |
|-----------|----|------|
| 🔴 高     | 4 | F1 DSACK · F2 tcp_cwnd_validate · F3 slow_start_after_idle · F4 Undo 机制 |
| 🟡 中     | 5 | F5 PRR · F6 Limited Transmit · F7 Disorder/CWR 子态 · F8 reo_wnd 动态调整 · F9 tcp_collapse_retrans |
| 🟢 低     | 4 | F10 ECN 协商/CE 响应 · F11 F-RTO · F12 MTU probing(RFC 4821)· F13 ABC |

"高/中/低" 的判据:**高**=协议正确性或明显性能回归;**中**=优化但有明确收益;
**低**=专项能力,可按需启用。

---

## 🔴 高优先级

### F1 — DSACK 生成与消费(RFC 2883)

- **当前状态**:`TcpOptionsReceived.dsack` 字段存在但**从未被读/写**;接收端收到
  重复段时不会在 ACK 中附带 DSACK 块;发送端解析 SACK 时也未区分 DSACK。
- **对齐点**:`tcp_dsack_set / tcp_send_dsack`(接收端在 ACK 中写入重复段序号)
  + `tcp_sacktag_write_queue` 的 `dup_sack` 分支(发送端识别 DSACK 后
  `tp->sacked_out--` 或触发 `tcp_try_undo_dsack`)。
- **影响**:无法检测伪重传(spurious retransmission);RACK 的 reo_wnd 失去自适应
  依据(见 F8);RTT 样本可能被重传段污染。
- **建议**:先做接收端 DSACK 生成(代价小、对方能直接受益),再做发送端消费。

### F2 — `tcp_cwnd_validate` / application-limited 追踪

- **当前状态**:`TcpOutput.tcp_cwnd_validate` 是空 stub(`// v2 does not persist
  is_cwnd_limited/max_packets_out yet`)。
- **对齐点**:`tcp_cwnd_validate`(tcp_output.c:2359) — 追踪 `is_cwnd_limited`,
  在 app 长时间低于 cwnd 时通过 `tcp_cwnd_application_limited` 回落 `snd_cwnd`,
  防止 cwnd 无上限膨胀。
- **影响**:低速应用不再受 cwnd 约束,下一次 burst 可能触发网络丢包;与 F3
  配合时 idle 恢复行为异常。
- **建议**:先记录 `is_cwnd_limited` 标志 + `lsndtime`,再补 idle 条件下的
  `tcp_cwnd_application_limited` 收敛。

### F3 — `tcp_slow_start_after_idle` / `tcp_cwnd_restart`

- **当前状态**:完全未实现。idle 后下次 burst 直接以历史 cwnd 发送。
- **对齐点**:`tcp_cwnd_restart`(tcp_output.c:150) — `idle > RTO` 时
  `cwnd = max(cwnd / 2^(idle/RTO), restart_cwnd)`(其中 restart_cwnd 取
  `min(init_cwnd, cwnd)`)。
- **影响**:空闲后恢复发包时 cwnd 偏大,易引发 loss;这是 Linux 默认开启的
  (`sysctl_tcp_slow_start_after_idle=1`)。
- **建议**:与 F2 打包做;`lastSendTimeMs` 字段已存在,直接扩展 `tcp_write_xmit`
  入口判断即可。

### F4 — Undo 机制(`tcp_try_undo_*`)

- **当前状态**:`retransStamp / undoRetrans` 字段存在(D3 批次已补),但**未接入**
  任何 undo 判定。发生伪重传后 cwnd/ssthresh 不会恢复。
- **对齐点**:`tcp_try_undo_recovery / tcp_try_undo_loss / tcp_try_undo_dsack`
  (tcp_input.c:2672 等) — 基于 `prior_cwnd / prior_ssthresh / undo_marker` 判定
  Recovery 是否为伪,是则恢复到 pre-loss 值。
- **影响**:小量乱序触发的伪快速重传后 cwnd 持续偏小,吞吐性能下降。
- **建议**:先加 `prior_cwnd / prior_ssthresh` 快照,再接 `tcp_try_undo_recovery`
  的最小闭环(TSECR-based 回滚)。

---

## 🟡 中优先级

### F5 — PRR(Proportional Rate Reduction, RFC 6937)

- **当前状态**:RECOVERY 入口直接 `ssthresh = cwnd/2; cwnd = ssthresh + 3`
  (NewReno 式 inflate/deflate),没有 PRR 的渐进降窗。
- **对齐点**:`tcp_cwnd_reduction`(tcp_input.c:2533)+ `prior_cwnd / prr_delivered
  / prr_out` 状态 — 让 cwnd 在恢复期内平滑落到 ssthresh。
- **影响**:Recovery 期间发包集中在入口,抖动更大,contention 更严重。
- **建议**:PRR 与 NewReno 对业务行为影响较大,改动范围集中在 `tcp_ack` 尾部
  + `tcp_xmit_retransmit_queue`,可以作为独立补丁。

### F6 — Limited Transmit(RFC 3042)

- **当前状态**:dupacks &lt; 3 不发新段。Linux 在 dupacks == 1/2 允许发送 1 个新段
  维持 ACK clock。
- **对齐点**:`tcp_mark_head_lost` 前 `tcp_limited_output_check_and_queue`
  (tcp_input.c 旧路径)或 PRR 内的 `sndcnt` 预算。
- **影响**:轻度乱序场景下 fast retransmit 延后;单段丢失的重传滞后 1 RTT。
- **建议**:代价小,可和 F1 DSACK 一起做。

### F7 — Disorder / CWR 子态(`tcp_ca_state`)

- **当前状态**:v2 只有 `{OPEN, RECOVERY, LOSS}` 三态。Linux 有五态
  `{Open, Disorder, CWR, Recovery, Loss}`。
- **对齐点**:`tcp_ca_event / tcp_enter_cwr` + dupack==1/2 时 `Disorder` 子态
  (仅计数,不降窗)。
- **影响**:ECN CE 响应(CWR)无法独立表达;和 F10 ECN 协同。
- **建议**:配合 F10 ECN 一起引入 CWR;Disorder 纯计数态优先级低。

### F8 — RACK reo_wnd 动态调整

- **当前状态**:固定 `reo_wnd = max(srtt/4, 1ms)`。
- **对齐点**:`tcp_rack_update_reo_wnd`(tcp_recovery.c)+ `reo_wnd_steps /
  reo_wnd_persist` — 在看到 DSACK 或 reorder 事件时步进,并持续若干 RTT。
- **影响**:严格 reo_wnd 在 mild reorder 下误判;动态升降能减少伪 RACK 重传。
- **建议**:依赖 F1 DSACK 的数据源,所以先做 F1。

### F9 — `tcp_collapse_retrans`(RTX 队列相邻小段合并)

- **当前状态**:无。RTX 队列不合并,重传仍按原切片发送。
- **对齐点**:`tcp_collapse_retrans`(tcp_output.c:2829) — 相邻小段在重传时
  合并到 MSS 以减少头开销。
- **影响**:小包丢包恢复期间带宽利用率降低。
- **建议**:改动主要在 `TcpSendBuffer`,实现形态与 `splitRtx` 对偶,可独立做。

---

## 🟢 低优先级

### F10 — ECN 协商与 CE 响应(RFC 3168)

- **当前状态**:只有 `TCPHDR_ECE/CWR` 常量定义,无握手 ECN 协商、无 IP TOS
  CE-bit 检测、无 CWR 响应。
- **对齐点**:`tcp_ecn_init / tcp_ecn_check_ce / tcp_ecn_withdraw_cwr /
  tcp_enter_cwr`(tcp_input.c / tcp_output.c)。
- **影响**:不能参与 ECN 拥塞反馈;仅在网络支持 ECN 并启用时才有收益。
- **建议**:依赖 F7(CWR 子态);多数场景 ECN 不是主干需求,先放后面。

### F11 — F-RTO 伪超时检测(RFC 5682)

- **当前状态**:完全未实现。RTO 后不区分真丢包和延迟抖动。
- **对齐点**:`tcp_enter_frto / tcp_process_frto`(tcp_recovery.c / tcp_input.c)
  — RTO 后发一个新段,若后续 ACK 表明网络通畅则判定伪 RTO 并 undo。
- **影响**:延迟抖动型场景过度 RTO;有 F4 Undo 后 F-RTO 是其自然扩展。
- **建议**:建议放在 F4 Undo 之后。

### F12 — MTU probing(RFC 4821)

- **当前状态**:`TcpOutput.tcp_mtu_probe()` 为 `return -1` stub。
- **对齐点**:`tcp_mtu_probing` sysctl(0/1/2) + `tcp_mtu_probe`
  (tcp_output.c:2434) — 黑洞 PMTU 时主动试探最大段。
- **影响**:ICMP Needs-Fragment 被过滤的链路无法发现真实 PMTU。
- **建议**:PMTUD 路径(F5 组完成)已覆盖多数场景,MTU probing 是边缘需求。

### F13 — Appropriate Byte Counting(RFC 3465)

- **当前状态**:无。慢启动按 ACK 数计 cwnd(每 ACK +1 MSS)。
- **对齐点**:`tcp_slow_start` 中 `cwnd += min(acked_bytes, 2*MSS)`。
- **影响**:在 delayed ACK 场景下慢启动速率略慢于 ABC。收益有限。
- **建议**:优先级最低,且与具体 CC 算法绑定。

---

## 推荐顺序(按依赖关系)

1. **F2 + F3** — application-limited 追踪 + idle restart:字段已基本齐备,
   代价小,立即解决"idle 后 burst 引发 loss"的实际回归。
2. **F1 接收端 DSACK 生成** — 代价小、对方受益,可独立发布。
3. **F4 Undo 最小闭环**(TSECR-based) — 解决伪快速重传后吞吐塌陷。
4. **F1 发送端 DSACK 消费 → F8 reo_wnd 动态调整** — 数据链路。
5. **F6 Limited Transmit** — 小代价、独立。
6. **F5 PRR** — 替换 NewReno 式 inflate/deflate,改动面大但性能收益明显。
7. **F9 tcp_collapse_retrans** — RTX 合并,独立。
8. **F11 F-RTO** — 建在 F4 之上。
9. **F7 Disorder/CWR + F10 ECN** — 打包一起。
10. **F12 MTU probing / F13 ABC** — 按需启用。
