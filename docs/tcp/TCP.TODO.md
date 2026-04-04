# TCP 实现状态与缺口分析

> 对标基准：Linux 内核 `net/ipv4/tcp_*.c`（v6.x）+ RFC 9293（TCP 规范）  
> 分析日期：2026-04-03  
> 修订日期：2026-04-04（核查修正：拥塞控制 RFC 5681/6582 全部已实现，cwnd 约束已集成；RFC 7323 Timestamp 生成 + PAWS 已完整实现）  
> 包路径：`com.github.pangolin.routing.acceptor.tun.net.handler.tcp`

---

## 一、已实现功能汇总

### 1. TCP 状态机
**Linux 对标**：`include/net/tcp_states.h`, `net/ipv4/tcp_input.c:tcp_rcv_state_process()`  
**RFC**：RFC 9293 §3.3.2, §3.6

| 状态 | 实现类 | 状态 |
|------|--------|------|
| `TCP_LISTEN` | `Tcp4Demultiplexer` | ✅ |
| `TCP_SYN_SENT` | `TcpInput` | ❌ 仅枚举/掩码，处理路径明确标注 `client mode not supported` |
| `TCP_SYN_RECV` / `TCP_NEW_SYN_RECV` | `TcpHandshaker`, `Tcp4Demultiplexer` | ✅ |
| `TCP_ESTABLISHED` | `TcpInput.tcp_rcv_established()` | ✅ |
| `TCP_FIN_WAIT1` / `FIN_WAIT2` | `TcpInput.tcp_fin()` | ✅ |
| `TCP_CLOSE_WAIT` / `LAST_ACK` | `TcpInput.tcp_fin()` | ✅ |
| `TCP_CLOSING` | `TcpInput.tcp_fin()` | ✅ |
| `TCP_TIME_WAIT` | `tcp_timewait_sock`, `inet_timewait_sock` | ❌ 入口已接通但立即跳转 CLOSE（标注 FIXME，2MSL 等待未实现） |
| `TCP_CLOSE` | `TcpInput` | ✅ |

---

### 2. 连接建立（三次握手）
**Linux 对标**：`net/ipv4/tcp_input.c:tcp_rcv_state_process()`, `tcp_conn_request()`, `tcp_check_req()`  
**RFC**：RFC 9293 §3.5, §3.10

- ✅ SYN 请求接收与验证，创建半连接 `tcp_request_sock`
- ✅ ISN 生成（`secureSeq()` 使用 SipHash，对标 Linux `secure_tcp_seq()`）
- ✅ SYN-ACK 发送，包含 MSS/WScale/SACK-Permitted 选项（`TcpOutput.tcp_synack_options()` → `TcpOptionCodec.writeSynOptions()`）
- ✅ SYN-ACK 携带 Timestamp 选项 — `tcp_synack_options()` 在 `req.tstamp_ok` 时调用 `writeTimestampOption()`（590f393e）
- ✅ SYN-ACK 超时重传
- ✅ 第三次 ACK 验证（`req.snt_isn + 1`），升级为完整连接
- ✅ 半连接队列 `synRegistry` / 全连接队列 `establishedRegistry`

---

### 3. 数据传输
**Linux 对标**：`tcp_data_queue()`, `tcp_rcv_established()`, `__tcp_transmit_skb()`  
**RFC**：RFC 9293 §3.4, §3.7, §3.8

- ✅ 序列号窗口验证（`tcp_sequence()`, `tcp_validate_incoming()`）
- ✅ 发送队列 `sk_write_queue` + 重传队列 `tcp_rtx_queue`
- ✅ Out-of-Order 队列（`TreeMap<Integer, OfoEntry>`）
- ❌ OFO 队列大小限制 — `ofo_queue_bytes` 字段仅声明，从未被实际检查（无溢出丢弃逻辑）
- ❌ PSH 标志接收端处理 — 接收路径无 PSH 处理逻辑（无立即 flush / 数据推送），发送端 `__tcp_transmit_skb()` 会设置 PSH 位，但属于发送标记而非 RFC 9293 §3.7.4 的接收端行为
- ✅ 更新 `snd_nxt`, `rcv_nxt`

---

### 4. 流量控制（窗口管理）
**Linux 对标**：`tcp_select_window()`, `tcp_ack_update_window()`  
**RFC**：RFC 9293 §3.8, RFC 7323 §2（Window Scaling）

- ✅ 发送窗口跟踪（`snd_wnd`）
- ✅ 接收窗口广告（`tcp_select_window()`）
- ✅ 零窗口探测（Zero Window Probe）
- ✅ 窗口扩展选项协商（`rx_opt.rcv_wscale`, `snd_wscale`，最大 14 位）
- ✅ 初始窗口计算（`tcp_select_initial_window()`，对标 `net/ipv4/tcp_output.c`）
- ✅ signed-window workaround（`sysctl_tcp_workaround_signed_windows`）

---

### 5. ACK 处理
**Linux 对标**：`tcp_ack()`, `tcp_ack_update_window()`  
**RFC**：RFC 9293 §3.4, RFC 5961 §5

- ✅ ACK 有效性检查（太旧 / 未发送数据的 ACK）
- ✅ 重传队列清理（`tcp_clean_rtx_queue()`）
- ✅ 挑战 ACK（Challenge ACK，`tcp_send_challenge_ack()`，RFC 5961 §7）
- ✅ 延迟 ACK（`ICSK_TIME_DACK`，40ms–200ms）
- ✅ 快速 ACK 模式（`TCP_MAX_QUICKACKS = 16`）
- ✅ Pingpong 模式检测（`inet_csk_in_pingpong_model()`）

---

### 6. RTT 测量与 RTO 计算
**Linux 对标**：`tcp_rtt_estimator()`, `tcp_set_rto()`  
**RFC**：RFC 6298（RTO 计算）, RFC 7323 §4（Timestamps RTTM）

- ✅ Van Jacobson 平滑 RTT 算法（`srtt_us`, `mdev_us`, `rttvar_us`）
- ✅ RTO = `SRTT + 4×RTTVAR`，限制在 \[200ms, 120s\]（符合 RFC 6298 §2）— `__tcp_set_rto()` 代码为 `(srtt_us >> 3) + rttvar_us`，因 `srtt_us` 以 8×SRTT 存储、`rttvar_us` 以 4×RTTVAR 存储，展开后等价于 `SRTT + 4×RTTVAR`，与 Linux 内核实现完全一致
- ✅ RTT 最小值跟踪（`tcp_update_rtt_min()`）
- ✅ 首次测量初始化（`srtt = m << 3`）
- ✅ Timestamp RTTM（`tcp_rtt_tsopt_us()`，RFC 7323 §4）— 590f393e 实现，330ecb8c 修复溢出保护（delta ≥ ~35 min 时丢弃样本）和 min_delta=1μs 下限

---

### 7. TCP 选项
**Linux 对标**：`tcp_parse_options()`, `tcp_synack_options()`  
**RFC**：RFC 9293 §3.2（Options）, RFC 7323（WScale/Timestamps）, RFC 2018（SACK）

| 选项 | Kind | 解析 | 生成 | 备注 |
|------|------|------|------|------|
| MSS | 2 | ✅ | ✅ | 默认 536B |
| Window Scale | 3 | ✅ | ✅ | 仅 SYN/SYN-ACK |
| SACK Permitted | 4 | ✅ | ✅ | 2 字节 |
| SACK | 5 | ⚠️ 框架 | ❌ | 选项解析框架已有，未完整处理 |
| Timestamp | 8 | ✅ | ✅ | 解析 + 生成均已实现；SYN-ACK 通过 `req.tstamp_ok` 控制；数据包通过 `tcp_established_options(tp)` 写入；PAWS 已集成 |

---

### 8. 错误处理与报文验证
**Linux 对标**：`tcp_validate_incoming()`, `tcp_reset()`  
**RFC**：RFC 9293 §3.5.3, RFC 5961（Blind Attacks）

- ✅ 序列号窗口检查（Out-of-window 检测）
- ✅ RST 处理（`tcp_reset()`，立即关闭）
- ✅ OOW 速率限制（`tcp_oow_rate_limited()`，防 DoS）
- ✅ `TcpDropReason` 与 Linux 内核 `SKB_DROP_REASON_*` 对齐
- ✅ RFC 5961 §4：SYN 挑战 ACK
- ✅ RFC 5961 §5：ACK 序列号验证

---

### 9. 定时器
**Linux 对标**：`include/net/inet_connection_sock.h`, `tcp_timer.c`  
**RFC**：RFC 9293 §3.8, RFC 6298

| 定时器 | 常量 | 实现 |
|--------|------|------|
| 重传定时器 | `ICSK_TIME_RETRANS` | ✅ |
| 延迟 ACK 定时器 | `ICSK_TIME_DACK` | ✅ |
| 零窗口探测定时器 | `ICSK_TIME_PROBE0` | ✅ |
| 尾部丢失探测 (TLP) | `ICSK_TIME_LOSS_PROBE` | ⚠️ 框架 |
| 重排序超时 | `ICSK_TIME_REO_TIMEOUT` | ⚠️ 框架 |
| Keepalive 定时器 | `ICSK_TIME_KEEPOPEN` | ⚠️ 主体已实现，3 处差异 |

---

## 二、未实现功能与缺口分析

> 标注格式：**功能名** — Linux 内核文件 / 函数 — 对应 RFC

---

### ❌ 2.0 经核查发现的实现缺陷
**来源**：对"已实现"功能逐项代码核查后发现

~~**2.0.1 SYN-ACK 不携带 Timestamp 选项**~~ — ✅ **已实现（590f393e）**  
**Linux 对标**：`net/ipv4/tcp_output.c:tcp_synack_options()`  
**RFC**：**RFC 7323 §3**

- `TcpOutput.tcp_synack_options()` 在 `req.tstamp_ok` 时调用 `TcpOptionCodec.writeTimestampOption()`
- `tcp_time_stamp_req()` 计算 tsval（含 `ts_off` 混淆），`req.ts_recent` 作为 tsecr

---

~~**2.0.2 数据包（ESTABLISHED 状态）不携带 Timestamp 选项**~~ — ✅ **已实现（590f393e）**  
**Linux 对标**：`net/ipv4/tcp_output.c:tcp_established_options()`  
**RFC**：**RFC 7323 §3**

- `TcpOutput.tcp_established_options(tp)` 在 `tp.rx_opt.tstamp_ok` 时写入 Timestamp（tsval=`tcp_time_stamp(tp)`, tsecr=`tp.rx_opt.ts_recent`）
- `__tcp_transmit_skb()` 同步更新为传入 `tp` 参数

---

**2.0.3 OFO 队列无大小限制**  
**Linux 对标**：`net/ipv4/tcp_input.c:tcp_data_queue_ofo()` 中的 `tcp_ofo_queue_prune()`  
**RFC**：**RFC 9293 §3.7**

- `TcpSock.ofo_queue_bytes` 字段声明存在，但在 `tcp_data_queue_ofo()` 中从未被检查或更新
- 导致：失序报文可无限堆积，存在内存耗尽风险
- [ ] 在入队时更新 `ofo_queue_bytes`，超限时调用 `tcp_ofo_queue_prune()` 裁剪

---

~~**2.0.4 RTO 计算公式**~~ — ✅ **核查结论：正确，已移除缺陷**  
`__tcp_set_rto()` 的 `(srtt_us >> 3) + rttvar_us` 与 Linux 内核完全一致：`srtt_us` 以 `8×SRTT` 存储（`TcpOutput.java:1183` 注释及 `TcpInput.java:381` 日志 `sk.srtt_us >> 3` 印证），`rttvar_us` 以 `4×RTTVAR` 存储，展开后等价于 `SRTT + 4×RTTVAR`，符合 RFC 6298 §2。

---

**2.0.5 接收端 PSH 标志无处理**  
**Linux 对标**：`net/ipv4/tcp_input.c:tcp_data_queue()` 中对 PSH 的处理  
**RFC**：**RFC 9293 §3.7.4**

- 接收路径中未对 PSH 标志做任何处理（无立即唤醒、无 flush 触发）
- 发送端 `__tcp_transmit_skb()` 会正确设置 PSH 位，接收端行为缺失
- [ ] 收到 PSH 时立即将数据推送给上层，不等待缓冲区填满

---

### ❌ 2.1 SACK（选择性确认）完整实现
**Linux 对标**：`net/ipv4/tcp_input.c:tcp_sacktag_write_queue()`, `tcp_ack_sack()`, `tcp_mark_lost_skb()`  
**RFC**：**RFC 2018**（SACK 选项）, **RFC 6675**（SACK 丢失恢复算法）

**现状**：
- `TcpOptionCodec` 中 SACK 选项解析框架已存在
- `TCPCB_SACKED_ACKED`, `TCPCB_SACKED_RETRANS`, `TCPCB_LOST` 标志位已定义
- 完整的 SACK block 解析、乱序重传恢复逻辑未实现

**待实现**：
- [ ] `tcp_sacktag_write_queue()` — SACK block 标记重传队列
- [ ] `tcp_sack_extend()` — SACK block 合并
- [ ] `tcp_mark_lost_skb()` — 基于 SACK 判断丢失段
- [ ] SACK 重传（优先重传 SACK hole 中的段）
- [ ] SACK 记分板（Scoreboard）维护

---

### ✅ 2.2 拥塞控制算法（Reno 已完整实现）
**Linux 对标**：`net/ipv4/tcp_cong.c`, `tcp_input.c`  
**RFC**：**RFC 5681**（TCP 拥塞控制）, **RFC 6582**（NewReno）, **RFC 6928**（IW10）

**现状**（2026-04-04 核查）：
- ✅ **慢启动**：`TcpInput.tcp_slow_start()` — `cwnd = min(cwnd + acked, ssthresh)`（RFC 5681 §3.1）
- ✅ **拥塞避免**：`TcpInput.tcp_cong_avoid_ai()` — `snd_cwnd_cnt` 累加器实现整数 AI 增长（RFC 5681 §3.1）
- ✅ **cwnd 更新入口**：`TcpInput.tcp_cong_avoid()` — 集成到 `tcp_ack()` 末尾
- ✅ **快速重传**：`TcpInput.tcp_fastretrans_alert()` — 3 个重复 ACK 触发（RFC 5681 §3.2）
- ✅ **快速恢复（NewReno）**：`TcpInput.tcp_enter_fast_recovery()` / `tcp_update_cwnd_recovery()` / `tcp_exit_fast_recovery()`（RFC 6582）
- ✅ **RTO 丢失恢复**：`TcpInput.tcp_enter_loss()` — `ssthresh = max(cwnd>>1, 2)`，`cwnd = in_flight+1`（Linux v6.x `tcp_input.c:2229`）
- ✅ **Loss 状态保护**：`tcp_fastretrans_alert()` 中 `TCP_CA_Loss` 分支防止 dup ACK 二次触发恢复

**详细改造记录**：见 `docs/tcp/TCP.CWND.TODO.md`

**仍未实现**：
- [ ] **ECN 通知响应**（RFC 3168 §6.1）— 见 §2.3
- [ ] CUBIC（RFC 8312）、BBR — 框架已预留，高 BDP 网络下吞吐受限

---

### ❌ 2.3 ECN（显式拥塞通知）
**Linux 对标**：`net/ipv4/tcp_input.c:tcp_ecn_rcv_ecn_echo()`, `tcp_ecn_send()`  
**RFC**：**RFC 3168**（ECN in TCP/IP）, **RFC 8311**（ECN 更新）

**现状**：代码注释中提及，未实现任何逻辑

**待实现**：
- [ ] SYN 中 ECE+CWR 标志协商（RFC 3168 §6.1.1）
- [ ] 数据包中 ECE 标志处理（通告对端网络拥塞）
- [ ] CWR 标志发送（确认已收到拥塞信号并降低发送速率）
- [ ] `ecn_flags` 字段管理（`TCP_ECN_OK`, `TCP_ECN_QUEUE_CWR`, `TCP_ECN_DEMAND_CWR`）

---

### ⚠️ 2.4 Keepalive（心跳）
**Linux 对标**：`net/ipv4/tcp_timer.c:tcp_keepalive_timer()`, `net/ipv4/tcp.c:tcp_set_keepalive()`  
**RFC**：**RFC 9293 §3.8.4**, **RFC 1122 §4.2.3.6**

**现状**：核心逻辑**已实现**，存在三处差异：
- `TcpTimer.tcp_keepalive_timer()` 主体逻辑完整：idle 检测、探测计数、超时发 RST、定时器重置
- `keepalive_probes()` / `keepalive_time_when()` / `keepalive_intvl_when()` / `keepalive_time_elapsed()` 全部实现
- `SysctlOptions` 已定义：`keepalive_probes=9`, `keepalive_time=7200s`, `keepalive_intvl=75s`
- `TcpSock` 有 `keepalive_intvl` / `keepalive_probes` / `rcv_tstamp` 字段

**与 Linux 的差异（待修复）**：
- [ ] **`sk_keepopen` 标志检查缺失** — Linux `tcp_keepalive_timer()` 进入后首先检查 `sock_flag(sk, SOCK_KEEPOPEN)`（即 `SO_KEEPALIVE` socket 选项），当前代码跳过此检查，会对所有连接触发 keepalive
- [ ] **per-socket `keepalive_time` 未启用** — `keepalive_time_when()` 中 `tp.keepalive_time` 被注释掉，固定使用 sysctl 全局值（7200s），不支持应用层设置 `TCP_KEEPIDLE`
- [ ] **ESTABLISHED 时未启动 keepalive 定时器** — 连接进入 ESTABLISHED 后未调用 `tcp_reset_keepalive_timer()`，定时器只在 FIN_WAIT2 场景下被触发

---

### ❌ 2.5 TIME_WAIT 完整处理
**Linux 对标**：`net/ipv4/tcp_minisocks.c:tcp_timewait_state_process()`, `tcp_time_wait()`  
**RFC**：**RFC 9293 §3.3.2**, **RFC 6191**（减少 TIME_WAIT）

**现状**：入口已接通，但实际是空实现——`tcp_time_wait()` 进入后被标注 `// FIXME`，
状态先被设为 `TCP_TIME_WAIT` 后立即覆盖为 `TCP_CLOSE` 并销毁连接，完全跳过了 2MSL 等待：
```
// TcpDemultiplexer.java:567-571  // FIXME
if (TCP_TIME_WAIT.equals(state)) {
    tp.state(TCP_CLOSE);   // 直接关闭，无 2MSL 等待
    tcp_done(tp);
}
```
传入 `TCP_FIN_WAIT2` 时只设置状态后返回，`timeout` 参数被完全忽略，无定时器启动。

**待实现**：
- [ ] **2MSL 定时器**：`tcp_time_wait()` 中启动 60s 定时器，超时后调用 `tcp_done()`
- [ ] **`TcpSock` 降级为 `tcp_timewait_sock`**：释放发送/接收缓冲区等大部分资源
- [ ] **TIME_WAIT 期间报文处理**（`tcp_timewait_state_process()` 等价实现）：
  - 收到重复 FIN → 重发 ACK，重置定时器
  - 收到 RST → 直接关闭
  - 收到 SYN → RST 或允许新连接（RFC 9293 §3.10.7.3）
- [ ] TIME_WAIT 复用（`tcp_tw_reuse` / RFC 6191，依赖 Timestamp）

---

### ❌ 2.6 TLP（Tail Loss Probe）
**Linux 对标**：`net/ipv4/tcp_timer.c:tcp_send_loss_probe()`, `tcp_input.c:tcp_process_tlp()`  
**RFC**：**RFC 8985**（RACK-TLP 算法）

**现状**：`ICSK_TIME_LOSS_PROBE` 定时器常量已定义，触发逻辑未实现

**待实现**：
- [ ] `tcp_schedule_loss_probe()` — 调度 TLP 定时器（2 * SRTT 或 RTO，取较小值）
- [ ] `tcp_send_loss_probe()` — 发送 TLP 探测报文（重传队列最后一个段）
- [ ] `tcp_process_tlp()` — 处理 TLP ACK，与 RACK 配合确认或触发恢复

---

### ❌ 2.7 RACK（Recent ACK）重排序检测
**Linux 对标**：`net/ipv4/tcp_rack.c`  
**RFC**：**RFC 8985**（RACK-TLP）

**现状**：`ICSK_TIME_REO_TIMEOUT` 定时器框架已定义，未实现

**待实现**：
- [ ] `tcp_rack_detect_loss()` — 基于时间的丢失检测（取代 dupthresh 计数法）
- [ ] `tcp_rack_update_reo_wnd()` — 动态调整重排序窗口
- [ ] `tcp_rack_advance()` — 根据最近 ACK 推进 RACK.xmit_time
- [ ] 与 SACK + TLP 协同工作

---

### ✅ 2.8 PAWS（防序列号回绕）完整实现
**Linux 对标**：`net/ipv4/tcp_input.c:tcp_paws_discard()`, `tcp_paws_check()`  
**RFC**：**RFC 7323 §5**（PAWS）

**现状（2026-04-04 核查）**：已在 590f393e 完整实现，330ecb8c 修复 `ts_recent==0` 初始报文误判 bug：
- ✅ `tcp_paws_check()` — 签名比较 `ts_recent - rcv_tsval`，24 天 stale 跳过，`ts_recent==0` 时直接接受（330ecb8c 修复）
- ✅ `tcp_paws_discard()` — 集成到 `tcp_validate_incoming()`，`ts_recent_stamp != 0 && saw_tstmap != 0` 时执行检查
- ✅ `tcp_store_ts_recent()` / `tcp_replace_ts_recent()` — 收到合法报文后更新 `ts_recent` 和 `ts_recent_stamp`

---

### ❌ 2.9 D-SACK（重复 SACK）
**Linux 对标**：`net/ipv4/tcp_input.c:tcp_check_dsack()`, `tcp_dsack_set()`  
**RFC**：**RFC 2883**（D-SACK 扩展）

**现状**：未实现

**待实现**：
- [ ] `tcp_dsack_set()` — 发送 D-SACK block（报告收到的重复段）
- [ ] `tcp_check_dsack()` — 检测并处理对端发来的 D-SACK（撤销不必要的重传）
- [ ] 与 SACK block 合并逻辑

---

### ❌ 2.10 PMTU 发现
**Linux 对标**：`net/ipv4/tcp_timer.c:tcp_mtu_probing()`, `net/ipv4/route.c`  
**RFC**：**RFC 4821**（Packetization Layer PMTU Discovery）, **RFC 1191**（IPv4 PMTUD）

**现状**：`tcp_sync_mss()` 已实现，但依赖外部 PMTU 信息，主动探测未实现

**待实现**：
- [ ] MTU 探测定时器（`tcp_mtu_probing` sysctl 控制）
- [ ] Black hole 检测（`tcp_mtu_probe()`）
- [ ] `icsk_mtup` 结构（MTU probe 状态）
- [ ] 接收 ICMP Fragmentation Needed 后动态降低 MSS

---

### ❌ 2.11 TCP 快速打开（TFO）
**Linux 对标**：`net/ipv4/tcp_fastopen.c`, `net/ipv4/tcp_input.c:tcp_fastopen_cookie_gen()`  
**RFC**：**RFC 7413**（TCP Fast Open）

**现状**：未实现

**待实现**：
- [ ] TFO Cookie 生成与验证（AES-based）
- [ ] SYN + data 一起发送（客户端）
- [ ] SYN-ACK 中携带 Cookie（服务端）
- [ ] TFO 选项（Kind=34）解析与生成

---

### ❌ 2.12 连接队列管理（SYN Flood 防护）
**Linux 对标**：`net/ipv4/tcp_input.c:tcp_syn_flood_action()`, SYN Cookies  
**RFC**：**RFC 4987**（SYN Flooding Attacks）

**现状**：`synRegistry` 无大小限制，无 SYN Cookie 实现

**待实现**：
- [ ] 半连接队列上限（`tcp_max_syn_backlog` 对应）
- [ ] SYN Cookie 生成与验证（无状态握手防 SYN Flood）
- [ ] SYN 速率限制

---

### ✅ 2.13 拥塞窗口（cwnd）实际约束发送
**Linux 对标**：`net/ipv4/tcp_output.c:tcp_cwnd_test()`, `tcp_snd_wnd_test()`  
**RFC**：**RFC 5681 §3.1**

**现状**（2026-04-04 核查）：
- ✅ `TcpOutput.tcp_cwnd_test()` 已实现（`TcpOutput.java:587`）：返回可发送的最大段数 `min(cwnd>>1, cwnd-in_flight)`
- ✅ 已集成到 `tcp_write_xmit()` 发送循环（`TcpOutput.java:642`）：`cwnd_quota == 0` 时跳出发送循环
- ✅ `tcp_packets_in_flight()` 已正确计算在途段数（`packets_out - tcp_left_out() + retrans_out`）

**注意**：`tcp_left_out()` 目前仍返回 0（SACK 未实现），导致 in_flight 略有高估，但不影响 cwnd 约束的基本正确性，详见 §2.1。

---

### ⚠️ 2.14 TIME_WAIT Recycling / tcp_tw_reuse
**Linux 对标**：`net/ipv4/tcp_minisocks.c:tcp_twsk_unique()`  
**RFC**：**RFC 6191**（Reducing TIME_WAIT with Timestamps）

**待实现**：
- [ ] Timestamp 辅助的 TIME_WAIT 重用（避免 60s 等待）

---

## 三、RFC 参考文档索引

| RFC | 标题 | 相关功能 | 实现状态 | 未实现简述 |
|-----|------|---------|---------|---------|
| **RFC 9293** | Transmission Control Protocol | 核心 TCP 规范（替代 RFC 793） | ⚠️ 部分实现 | SYN_SENT（client 模式）未实现；TIME_WAIT 2MSL 等待跳过；PSH 接收端未处理；OFO 队列无大小限制 |
| **RFC 1122** | Requirements for Internet Hosts | Keepalive, 错误处理 | ⚠️ 部分实现 | Keepalive 主体已有但 `sk_keepopen` 检查缺失、per-socket `keepalive_time` 未启用、ESTABLISHED 后未启动定时器 |
| **RFC 2018** | TCP Selective Acknowledgment Options | SACK | ❌ 框架已有 | SACK block 解析框架存在但未处理；`tcp_sacktag_write_queue()`、SACK 重传、记分板均未实现 |
| **RFC 2883** | An Extension to the Selective Acknowledgement (SACK) Option | D-SACK | ❌ 未实现 | `tcp_dsack_set()` / `tcp_check_dsack()` 均未实现；无法通知对端重复收到的段，无法撤销不必要重传 |
| **RFC 3168** | The Addition of ECN to IP | ECN | ❌ 未实现 | SYN 中 ECE+CWR 协商、数据包 ECE 处理、CWR 标志发送、`ecn_flags` 管理均未实现 |
| **RFC 3390** | Increasing TCP's Initial Window | 初始拥塞窗口 | ✅ 已实现 | `tcp_init_cwnd()` 实现 IW10；`tcp_cwnd_test()` 已集成到发送路径，cwnd 正确约束发送 |
| **RFC 4821** | Packetization Layer PMTU Discovery | PMTUD | ⚠️ 被动支持 | `tcp_sync_mss()` 已有；主动 MTU 探测（`tcp_mtu_probe()`）、Black hole 检测、ICMP Frag Needed 响应未实现 |
| **RFC 4987** | TCP SYN Flooding Attacks | SYN Flood 防护 | ❌ 未实现 | `synRegistry` 无大小限制；SYN Cookie 生成与验证未实现；无 SYN 速率限制 |
| **RFC 5681** | TCP Congestion Control | 慢启动/拥塞避免/快重传/快恢复 | ✅ 已实现（Reno） | 慢启动（`tcp_slow_start`）、拥塞避免（`tcp_cong_avoid_ai`）、快速重传（3 dup ACK）、RTO 丢失恢复（`tcp_enter_loss`，cwnd=in_flight+1）均已实现；Cubic/BBR 未实现；详见 `TCP.CWND.TODO.md` |
| **RFC 5961** | Improving TCP's Robustness to Blind In-Window Attacks | Challenge ACK | ✅ 已实现 | — |
| **RFC 6191** | Reducing the TIME-WAIT State | TIME_WAIT 优化 | ❌ 未实现 | 依赖 Timestamp；TIME_WAIT 本身跳过 2MSL，`tcp_tw_reuse` 亦未实现 |
| **RFC 6298** | Computing TCP's Retransmission Timer | RTO 计算 | ✅ 已实现 | — |
| **RFC 6582** | The NewReno Modification to TCP's Fast Recovery | NewReno 快速恢复 | ✅ 已实现 | `tcp_enter_fast_recovery()`（ssthresh=cwnd>>1，cwnd=ssthresh+3）、`tcp_update_cwnd_recovery()`（部分 ACK 处理）、`tcp_exit_fast_recovery()`（cwnd=ssthresh）均已实现 |
| **RFC 6928** | Increasing TCP's Initial Window | IW10 | ✅ 已实现 | `TCP_INIT_CWND=10`，`tcp_init_cwnd()` 已实现；发送路径 cwnd 约束已集成，IW10 正常生效 |
| **RFC 7323** | TCP Extensions for High Performance | Timestamps / PAWS / WScale | ✅ 已实现 | WScale、Timestamp 生成（SYN-ACK + 数据包）、PAWS 全部实现（590f393e）；`ts_recent==0` 初始报文 bug 修复（330ecb8c）；详见 `TCP.TS.TODO.md` |
| **RFC 7413** | TCP Fast Open | TFO | ❌ 未实现 | Cookie 生成/验证、SYN+data 发送、Kind=34 选项解析均未实现 |
| **RFC 8311** | Relaxing Restrictions on ECN Experimentation | ECN 更新 | ❌ 未实现 | 同 RFC 3168，ECN 完全未实现 |
| **RFC 8312** | CUBIC for Fast Long-Distance Networks | CUBIC 拥塞控制 | ❌ 未实现 | 需先完成 RFC 5681 基础拥塞控制框架后再实现 CUBIC 算法（`tcp_cubic.c` 对应） |
| **RFC 8985** | The RACK-TLP Loss Detection Algorithm | RACK / TLP | ❌ 框架已有 | `ICSK_TIME_LOSS_PROBE` / `ICSK_TIME_REO_TIMEOUT` 定时器常量已定义；`tcp_rack_detect_loss()`、`tcp_send_loss_probe()`、`tcp_process_tlp()` 均未实现 |

---

## 四、优先级建议

> 更新日期：2026-04-04（已完成：拥塞控制 Reno、cwnd 约束发送、RFC 7323 Timestamp 生成、PAWS）

按实现收益和复杂度排序：

| 优先级 | 功能 | 理由 |
|--------|------|------|
| ~~P0~~ | ~~拥塞控制（cwnd 约束发送）~~ | ✅ **已完成**（2026-04-04）：RFC 5681 Reno 全部实现 |
| ~~P0~~ | ~~Timestamp 生成补全（2.0.1/2.0.2）~~ | ✅ **已完成**（590f393e）：SYN-ACK 和数据包均已写入 Timestamp |
| ~~P0~~ | ~~PAWS 完整实现（2.8）~~ | ✅ **已完成**（590f393e + 330ecb8c）：`tcp_paws_check/discard/replace_ts_recent` 全部实现 |
| P1 | **OFO 队列大小限制（2.0.3）** | 无限堆积存在内存耗尽风险 |
| P1 | **SACK 完整实现（2.1）** | 框架已有；`tcp_left_out()` 返回 0 导致 in_flight 略有高估，丢包场景恢复效率低 |
| P1 | **TIME_WAIT 状态机完整处理（2.5）** | 当前立即跳过 2MSL 直接 CLOSE，违反 RFC 9293 |
| P2 | **接收端 PSH 处理（2.0.5）** | 数据无法及时推送给上层，影响交互式应用延迟 |
| P2 | **TLP（Tail Loss Probe）（2.6）** | 框架已有，改善尾部丢包检测延迟 |
| P2 | **Keepalive 修复（2.4）** | 主体逻辑已有，缺 `sk_keepopen` 检查、per-socket `keepalive_time`、ESTABLISHED 时启动定时器 |
| P2 | **RACK（2.7）** | 框架已有，现代 Linux 默认启用 |
| P2 | **Cubic 拥塞控制** | 高 BDP 网络下 Reno 吞吐受限；框架已预留 |
| P3 | **ECN（2.3）** | 数据中心场景有益 |
| P3 | **D-SACK（2.9）** | 减少不必要重传，依赖 SACK 先实现 |
| P3 | **SYN Flood 防护（2.12）** | 面向公网时需要 |
| P4 | **TFO（2.11）** | 延迟优化，可选 |
| P4 | **PMTU 主动探测（2.10）** | `tcp_sync_mss()` 已有被动支持 |
