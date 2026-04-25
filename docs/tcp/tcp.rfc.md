# TCP 相关 RFC 规范总览与最小化实现清单

> IETF 把"TCP 规范"拆成几十份 RFC,彼此间通过 Updates / Obsoletes 关系形成一张大图。本文按主题梳理最核心的文档,并在最后给出两档"最小化实现"建议。
> 官方导览:**RFC 7414 — A Roadmap for Transmission Control Protocol (TCP) Specification Documents**(读这一份可以快速了解全局)。

---

## 1. 核心规范(State Machine / 段格式 / 基本行为)

| RFC | 年份 | 状态 | 内容 |
| --- | --- | --- | --- |
| **RFC 793** | 1981 | 历史(已被 9293 Obsolete) | TCP 最初规范。段格式、三次握手、四次挥手、11 状态 FSM、滑动窗口、URG、RST。所有后续 RFC 都在此之上扩展 |
| **RFC 9293** | 2022 | **现行** Internet Standard | 在 793 基础上吸收 40 余份 RFC 的勘误与澄清,一次性替代 793/879/2873/6093/6429/6528/6691 等,是当前唯一权威的 TCP 核心规范 |
| **RFC 1122** | 1989 | Standards Track | Host Requirements(与 IP/TCP/UDP 相关部分)。大量"MUST/SHOULD"强制行为:ISN 选择、Delayed ACK(§4.2.3.2)、Nagle(§4.2.3.4)、Keep-alive(§4.2.3.6)、RTO 初值、TCP 选项处理等 |
| **RFC 879** | 1983 | 被 9293 合并 | MSS 选项与相关讨论 |
| **RFC 6691** | 2012 | 被 9293 合并 | MSS 计算:MSS = MTU - IP 头 - TCP 头(不含选项) |

**实现重点**:段格式、FSM、可靠传输(RTO 重传 + 累计 ACK)、流量控制(接收窗口)、三次握手 / 挥手 / RST、连接标识(四元组)。

---

## 2. 重传与计时(RTO)

| RFC | 内容 |
| --- | --- |
| **RFC 6298** | 计算 RTO 的当前标准:SRTT/RTTVAR 指数加权,`RTO = SRTT + max(G, K*RTTVAR)`,最小 1s,上限 ≥ 60s;回退到 2*RTO。替代旧的 RFC 2988 |
| **RFC 7323** § 3/4 | Timestamps 选项(TSval/TSecr),使 RTT 采样准确、并支持 PAWS |
| **RFC 8961** | 通用"基于时间的丢包检测"要求(对 Tail Loss Probe、RACK 等的前置规范) |
| **RFC 8985** | **RACK-TLP**:基于发送时间的丢包检测算法,Linux 当前默认,能替代大部分 RTO 场景,显著降低尾部丢包恢复时延 |

---

## 3. 拥塞控制

| RFC | 内容 |
| --- | --- |
| **RFC 5681** | **必读**。Slow Start、Congestion Avoidance、Fast Retransmit、Fast Recovery(基础"Reno");cwnd/ssthresh 定义。替代 2581 / 2001 |
| **RFC 3390** | IW(初始拥塞窗口)放宽到 `min(4*MSS, max(2*MSS, 4380))` |
| **RFC 6928** | **IW10**:初始窗口放宽到 10*MSS(现代内核默认) |
| **RFC 6582** | **NewReno** 快速恢复:在无 SACK 的情况下修正 Reno 对多包丢失的退化行为。替代 3782 |
| **RFC 3042** | Limited Transmit:在收到第 1~2 个重复 ACK 时允许发送"新"数据以触发更多 dup-ACK |
| **RFC 9438** | **CUBIC**(Linux 默认拥塞算法,替代 RFC 8312) |
| **RFC 3168** | **ECN**:通过 IP/TCP 协同做"主动拥塞通告",避免纯丢包信号 |
| **RFC 8311** | ECN 的实验性用法(L4S 等)的澄清框架 |
| **RFC 9002** / `BBR` | 注:QUIC 的损失恢复(9002)与 BBR(Internet-Draft,尚未 RFC)不属于 TCP 规范本身,但实现思想相关 |

---

## 4. SACK(选择性确认)

| RFC | 内容 |
| --- | --- |
| **RFC 2018** | SACK 选项的定义:接收方用 SACK 通告哪些段已经收到 |
| **RFC 2883** | **D-SACK**:扩展 SACK,可报告"重复收到"的段,辅助发送方判断是否冗余重传 |
| **RFC 6675** | **保守的 SACK-based 丢包恢复算法**(PipeSize / NextSeg / 重传判定),当前主流 TCP 栈都用它替代纯 Reno/NewReno |

---

## 5. 窗口扩展 / 高性能扩展

| RFC | 内容 |
| --- | --- |
| **RFC 7323** | **必读**。TCP 高性能扩展,替代 RFC 1323: <br>• **WScale**:窗口扩大因子(最多 2^14 倍,支持 >64KB 接收窗口) <br>• **Timestamps**:TSval/TSecr,用于 RTT 与 PAWS <br>• **PAWS**:Protection Against Wrapped Sequences,防序号回绕造成错误接收 |
| **RFC 1146** | 可选校验和(历史) |

---

## 6. Path MTU / 报文大小

| RFC | 内容 |
| --- | --- |
| **RFC 1191** | IPv4 Path MTU Discovery(基于 ICMP "Fragmentation Needed") |
| **RFC 8201** | IPv6 PMTUD(替代 RFC 1981) |
| **RFC 4821** | Packetization Layer PMTUD:不依赖 ICMP,通过探测包大小主动发现,适合 ICMP 被过滤的网络 |
| **RFC 2923** | PMTUD 在真实网络中常见问题("黑洞") |

---

## 7. TCP 选项 / 控制扩展

| RFC | 内容 |
| --- | --- |
| **RFC 7413** | **TCP Fast Open (TFO)**:携带数据完成握手,降低首包延迟 |
| **RFC 5482** | User Timeout Option:协商更灵活的连接超时 |
| **RFC 5925** | **TCP-AO**:TCP 认证选项,替代过时的 MD5 |
| **RFC 2385** | TCP MD5 签名(BGP 时代遗留,已被 AO 替代但仍广泛部署) |
| **RFC 2675** | IPv6 Jumbogram 下的 TCP(MSS 表达) |
| **RFC 8684** | **MPTCP v1**(多路径 TCP,替代 6824) |

---

## 8. 安全加固

| RFC | 内容 |
| --- | --- |
| **RFC 5961** | 抗盲攻击(In-Window RST/SYN/DATA):要求严格 SEG.SEQ 校验、challenge ACK 机制 |
| **RFC 6528** | ISN 生成应随机化(防序号预测攻击),给出推荐公式 |
| **RFC 4987** | SYN flood 攻击防御综述(SYN Cookies 等) |
| **RFC 7323** § 5.3 | PAWS 对重复老段的防护 |

---

## 9. 其他常见行为

| RFC | 内容 |
| --- | --- |
| **RFC 813** | Window and Acknowledgement Strategy(ACK/窗口更新策略,影响吞吐) |
| **RFC 896** | Nagle 算法(合并小包) |
| **RFC 6093** | URG 指针语义澄清(现代栈一般禁用 URG) |
| **RFC 6633** | 删除 Source Quench 的 TCP 响应 |
| **RFC 1337** | TIME-WAIT 危害与建议 |
| **RFC 2140** | 连接间共享控制块(TCB sharing,性能优化) |
| **RFC 2861** | 慢启动后的 cwnd 递减("congestion window validation") |
| **RFC 3465** | Appropriate Byte Counting(cwnd 增长基于确认字节数而非 ACK 个数) |
| **RFC 3522** | Eifel 检测(伪重传检测) |

---

## 10. 导览 / 元文档

- **RFC 7414** — TCP 规范文档路线图(定期更新的总目录)
- **RFC 4614** — 7414 的早期版本
- **RFC 2525** — 已知的 TCP 实现问题清单(调试时很有用)

---

## 最小化实现清单

按"能工作"→"可用于生产"分两档。

### 档位 A:教学 / 玩具级(能在受控局域网里通信)

只求协议状态机能跑通、能可靠传输、有基本的重传和流量控制。

| 必须实现 | 说明 |
| --- | --- |
| **RFC 9293**(或 RFC 793) | 段格式、FSM、三次握手/挥手、RST、累计 ACK、接收窗口、URG(可不支持)、MSS 选项协商 |
| **RFC 1122** 的 TCP 章节 | MUST 级的主机行为:Nagle(可配置)、Delayed ACK(建议 ≤ 500ms)、Keep-alive(可选)、RTO 初值/最小值 |
| **RFC 6298** | RTO 计算(SRTT/RTTVAR/回退) |
| **RFC 6691** / 9293 § 3.7 | MSS 计算与 MSS 选项 |

这一档几乎没有拥塞控制,在广域网上会表现得很糟糕,但足以验证协议栈骨架。

### 档位 B:可上生产 / 与真实互联网共存(Must-Have)

在档位 A 之上再叠加拥塞控制、SACK、窗口扩展、PMTU 等,这是**任何面向真实网络的 TCP 实现的最低门槛**。

| 必须实现 | 理由 |
| --- | --- |
| **RFC 5681** | 必须有拥塞控制(Slow Start + Congestion Avoidance + Fast Retransmit + Fast Recovery),否则无公平性、易造成拥塞崩溃 |
| **RFC 6582**(NewReno) | 无 SACK 情况下的快速恢复修正 |
| **RFC 3042** | Limited Transmit,对少量重复 ACK 触发重传 |
| **RFC 3390** / **6928** | 合理的初始窗口(IW10 已是事实标准) |
| **RFC 2018** | SACK 选项。没 SACK 的发送端在丢多包时恢复效率极差 |
| **RFC 6675** | 基于 SACK 的保守丢包恢复(配合 2018 才真正发挥作用) |
| **RFC 2883** | D-SACK(扩展小、收益大,多数栈都开) |
| **RFC 7323** | 窗口扩大因子 + Timestamps + PAWS。BDP 大于 64KB 的链路必需 |
| **RFC 1191** + **RFC 8201** | IPv4/IPv6 的 PMTUD,避免分片造成性能塌陷 |
| **RFC 4821** | PLPMTUD,应对 ICMP 黑洞网络 |
| **RFC 5961** | 抗盲注 RST/SYN,否则公网跑几天就可能被暴力干扰 |
| **RFC 6528** | 随机化 ISN |

### 档位 C:现代栈的推荐补充(Nice-to-Have)

可以在档位 B 的基础上逐步补齐,每一项带来明显性能或安全收益。

- **RFC 8985 (RACK-TLP)** — 近年主流替代 NewReno 的丢包检测,显著改善尾部延迟
- **RFC 9438 (CUBIC)** — 长肥管道下的默认拥塞控制
- **RFC 3168 (ECN)** — 减少因丢包信号造成的尾延迟
- **RFC 7413 (TFO)** — 短连接首包延迟优化
- **RFC 5925 (TCP-AO)** — 替代 TCP MD5,用于 BGP/管理协议
- **RFC 8684 (MPTCP v1)** — 多路径聚合 / 无缝切换
- **RFC 3465 (ABC)** — 精细化的 cwnd 增长

---

## 对照:当前 pangolin-routing/v2 的 RFC 覆盖(建议对齐时参考)

> 用作开发"v2 TCP"时对齐 Linux 内核实现的 checklist。详细 RFC × 模块矩阵见 `tcp.v2.rfc.report.md`。

### 接收侧 / 流量控制

- ✅ **基础 FSM、段格式、三次握手/挥手**:RFC 9293
- ✅ **累计 ACK / 接收窗口 / 窗口更新 / 重传**:RFC 9293 + 6298
  - 接收窗口主路径已覆盖:`__tcp_select_window` 中间分支(零窗 / SWS 避免 / `rcv_ssthresh` 限幅)、no-shrink 右边沿守卫、`tcp_grow_window`(顺序段 + OFO)、`tcp_clamp_window`(OFO prune 压力记忆)、`tcp_cleanup_rbuf`(反压解除主动开窗)
  - SYN-ACK window 字段对齐 RFC 7323 §2.2:不被 wscale 缩放(已修历史 right-shift bug)
- ✅ **SACK / D-SACK**:RFC 2018 / 2883
- ✅ **窗口扩展 / Timestamps / PAWS**:RFC 7323

### 拥塞控制 / 恢复

- ✅ **NewReno / Limited Transmit / IW10**:RFC 5681 / 6582 / 3042 / 6928
  - CA 状态机已含 `Open / Disorder / Recovery / Loss`(对齐 Linux `tcp_ca_state`,2026-04 R4)
  - Limited Transmit 在 `Open` 与 `Disorder` 都生效
- ✅ **RACK-TLP**:RFC 8985
  - RACK 时间窗判丢、reo_wnd 自适应(DSACK feedback 推 `reo_wnd_steps`)
  - TLP 公式严格对齐 Linux `tcp_schedule_loss_probe`:`packets_out==1 → 2*srtt+RTO_MIN`(让单段 tail 走 RTO);`packets_out>1 → 2*srtt+2ms`
- ⚠️ **SACK-based 恢复**:RFC 6675 — 部分覆盖,实际形态是 Linux/RACK 子集
  - 已有:SACK tagging、per-segment state、`retrans_out` 维护、`packets_in_flight = packets_out - sacked_out - lost_out + retrans_out`(对齐 Linux 公式)、`tcp_enter_loss` 完整 rtx 队列重置(R3)、`tcp_fastretrans_alert` undo 链聚合(R2)、LOST/RTX 头段优先重传
  - 缺口:**PRR (RFC 6937)**(Recovery 期 cwnd 平滑下降)、partial ACK 重扫 LOST、`tcp_xmit_retransmit_queue` 队列遍历(当前为单次单段)、显式 RFC 6675 `NextSeg` / `RescueRxt`(选择不实现,跟随 Linux)
- ✅ **F-RTO / DSACK undo**:RFC 5682 / 3708
- ❌ **CUBIC**:RFC 9438 — 未实现 Linux 默认 CUBIC

### 安全

- ✅ **抗盲攻击 / Challenge ACK**:RFC 5961
  - host-wide netns 桶(对齐 Linux `net->ipv4.tcp_challenge_*`,使用 `sysctl_tcp_challenge_ack_limit`)
  - per-socket OOW 限速通过 `tp->last_oow_ack_time` + `sysctl_tcp_invalid_ratelimit`
  - challenge ACK 与 OOW ACK 桶严格分离(2026-04 S3)
- ✅ **ISN 随机化**:RFC 6528 — `TcpUtils.secureSeq` 使用 SipHash(四元组 + SecureRandom key) + 时间分量,仍需补专项测试

### 未实现

- ❌ **PMTU / PLPMTUD**:RFC 1191 / 8201 / 4821 — 配置字段存在但主路径未消费,MTU probing 未实现;ICMP Fragmentation Needed 未接入 v2 栈
- ❌ **ECN**:RFC 3168 / 8311 — 仅常量定义,未接路径
- ❌ **TFO**:RFC 7413 — 未实现
- ❌ **TCP-AO / MD5**:RFC 5925 / 2385 — 未实现
- ❌ **MPTCP**:RFC 8684 — 未实现
- ❌ **主动 connect / SYN_SENT**:v2 当前仅被动打开(passive open)
- ❌ **IPv6 v2 TCP**:仅 IPv4 dispatcher;IPv6 PMTUD 不可达
- ❌ **完整 Nagle**:`TCP_NODELAY` setter 仅记录,发送路径恒 `TCP_NAGLE_OFF`
- ❌ **URG**:`TCPHDR_URG` 标记未消费

### 其他次要

- ⚠️ **RFC 813** ACK/window strategy:已覆盖 delayed ACK / quickack / no-shrink / zero-window threshold / `tcpCleanupRbuf` / `grow_window` 主路径
- ⚠️ **RFC 1337** TIME-WAIT 危害:TIME_WAIT bucket、迟到段重放、TW SYN 重用
- ⚠️ **RFC 2861** cwnd validation:`slow_start_after_idle` + `tcpCwndValidate` 存在,需逐条核对
- ❌ **RFC 3522 Eifel**:未实现 timestamp-based 伪重传检测
- ❌ **TSO / GSO / Pacing / ABC (RFC 3465)**:未实现

对齐原则参见 CLAUDE.md(按 Linux 内核 `net/ipv4/tcp_input.c` / `tcp_output.c` 实现)。

---

## 参考阅读顺序(强烈推荐)

1. **RFC 7414** — 先读路线图,建立索引
2. **RFC 9293** — 当前权威核心
3. **RFC 1122** §4.2 — 行为要求
4. **RFC 5681** + **RFC 6582** — 拥塞控制基础
5. **RFC 6298** + **RFC 7323** — RTO 与高性能扩展
6. **RFC 2018** + **RFC 6675** — SACK
7. **RFC 5961** — 安全
8. **RFC 8985 / 9438** — 现代丢包检测与拥塞控制



