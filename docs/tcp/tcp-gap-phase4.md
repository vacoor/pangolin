# v2 TCP Phase 4 残留决策档案(最后复核:2026-04-21)

> **本文件性质**:Phase 4 Gap Audit 收敛后,残留可选特性的"暂不处理"决策留痕。
> ✅ 已对齐项目的实施细节见 `tcp.java.md`(结论性快照),本文件不再承载实施日志。
> 保留本文件是为了避免已决策的暂不处理项被未来误列回待办 — 每条都附"触发
> 条件",仅在条件成立时才允许重新立项。

## 状态速览

本节只列 Phase 4 范围内**暂不处理**的 F-编号项;已对齐条目与数量以
`tcp.java.md` 一/二节的对齐表为权威源(本文件不再复述计数,避免随 `tcp.java.md`
迭代而漂移)。

| 级别  | 数 | 项目 |
|-------|----|------|
| 🔴 高 | 0  | — |
| 🟡 中 | 2  | F5 PRR · F7 Disorder/CWR 子态 |
| 🟢 低 | 3  | F10 ECN 协商/CE 响应 · F12 MTU probing(RFC 4821)· F13 ABC |

"高/中/低" 的判据:**高**=协议正确性或明显性能回归;**中**=优化但有明确收益;
**低**=专项能力,可按需启用。

> **范围说明**:本文件只记录 Phase 4 Gap Audit 明确纳入范围的 F-编号项。
> `tcp.java.md` §四 🔴 清单中的其他未实现项(SYN cookie / Pacing / TSO / GSO /
> Urgent / MD5 / TCP AO / TCP Fast Open / User Timeout / AccECN 等)属于后续
> Phase 的性能特性或扩展选项,不在 Phase 4 决策范围,也不构成本文件的反重复
> 护栏对象 —— 它们是否实现另行立项,无需通过本文件归档。

> **2026-04-21 复核结果**:G-6 `tcp_check_req` 整体重写后,`tcp.java.md` §七
> 回审清单 0 未消解偏差;Phase 4 核心批(F1–F4 / F6 / F8 / F9 / F11 + S-1)视为
> 完全收敛。本文件 5 项 🟡/🟢 决策(F5/F7/F10/F12/F13)在代码中的状态无变化,
> 触发条件继续生效。

---

## 🟡 中优先级

### F5 — PRR(Proportional Rate Reduction, RFC 6937)

- **当前状态**:RECOVERY 入口直接 `ssthresh = cwnd/2; cwnd = ssthresh + 3`
  (NewReno 式 inflate/deflate),没有 PRR 的渐进降窗。
- **对齐点**:`tcp_cwnd_reduction`(tcp_input.c:2533)+ `prior_cwnd / prr_delivered
  / prr_out` 状态 — 让 cwnd 在恢复期内平滑落到 ssthresh。
- **影响**:Recovery 期间发包集中在入口,抖动更大,contention 更严重。
- **定位(2026-04-19 决策)**:PRR 是 Linux 中**独立于 CC 算法**的 cwnd 降窗策略
  (与 CUBIC / BBR / Reno 都正交,位于 `tcp_input.c` 主路径而非 CC 模块内),不是
  NewReno 的小修补。v2 落地时也按**独立算法模块**引入,不挂在
  `NewRenoCongestionControl` 内 —— 这样未来并列 CUBIC/BBR 时可共用 PRR。
- **触发条件(2026-04-20 决策)**:仅在以下任一条件成立时重新立项:
  1. 观测到 Recovery 入口出现明显吞吐毛刺(出向包突发集中在 `dupacks==3` 瞬间
     与 `ssthresh` 落定点附近),通过 `TcpMib` / 链路抓包复现;
  2. 引入 CUBIC / BBR 等多 CC 算法,需要通用降窗策略共享 —— 此时 LossRecovery
     抽象接口是前置条件,PRR 随同并入,不单独立项。
  不满足以上条件时保持现状,不被误列回待办。

### F7 — Disorder / CWR 子态(`tcp_ca_state`)

- **当前状态**:v2 只有 `{OPEN, RECOVERY, LOSS}` 三态。Linux 有五态
  `{Open, Disorder, CWR, Recovery, Loss}`。
- **对齐点**:`tcp_ca_event / tcp_enter_cwr` + dupack==1/2 时 `Disorder` 子态
  (仅计数,不降窗)。
- **影响**:ECN CE 响应(CWR)无法独立表达;和 F10 ECN 协同。
- **触发条件**:与 F10 ECN 打包,F10 不重新立项时 F7 也不立项;Disorder 纯计数态
  价值低,不单独投入。

---

## 🟢 低优先级

### F10 — ECN 协商与 CE 响应(RFC 3168)

- **当前状态**:只有 `TCPHDR_ECE/CWR` 常量定义,无握手 ECN 协商、无 IP TOS
  CE-bit 检测、无 CWR 响应。
- **对齐点**:`tcp_ecn_init / tcp_ecn_check_ce / tcp_ecn_withdraw_cwr /
  tcp_enter_cwr`(tcp_input.c / tcp_output.c)。
- **影响**:不能参与 ECN 拥塞反馈;仅在网络支持 ECN 并启用时才有收益。
- **触发条件(2026-04-20 决策)**:v2 是 tunnel proxy,外层承载(WebSocket /
  SOCKS5 / VMess 等上层协议)不保证透传 IP TOS 的 ECN bits;且公网 ECN 普及率
  长期偏低(Linux 默认 `tcp_ecn=2` 被动接受、不主动发起,也是这个原因)。
  仅在以下条件同时成立时重新立项:
  1. 承载链路明确声明支持 ECN 端到端透传(路径 MTU 测试 + CE bit reflection
     验证);
  2. 该链路的拥塞反馈场景下 ECN 信号显著优于 RACK / dupack 丢包信号(可通过
     A/B 实验或 `TcpMib` 观测量化);
  3. F7 CWR 子态已落地。
  当前 RACK + Limited Transmit + dupack 已覆盖拥塞信号需求,不被误列回待办。

### F12 — MTU probing(RFC 4821)

- **当前状态**:`TcpOutput.tcp_mtu_probe()` 为 `return -1` stub。
- **对齐点**:`tcp_mtu_probing` sysctl(0/1/2) + `tcp_mtu_probe`
  (tcp_output.c:2434) — 黑洞 PMTU 时主动试探最大段。
- **影响**:ICMP Needs-Fragment 被过滤的链路无法发现真实 PMTU。
- **触发条件**:仅当 v2 部署在 ICMP 黑洞链路且观察到 PMTUD 频繁失败时立项;
  普通链路 PMTUD 路径已覆盖,边缘需求,不预先实现。

### F13 — Appropriate Byte Counting(RFC 3465)

- **当前状态**:无。慢启动按 ACK 数计 cwnd(每 ACK +1 MSS)。
- **对齐点**:`tcp_slow_start` 中 `cwnd += min(acked_bytes, 2*MSS)`。
- **影响**:在 delayed ACK 场景下慢启动速率略慢于 ABC。收益有限。
- **触发条件**:仅当引入 CUBIC / BBR 等多 CC 算法、慢启动行为可观测且
  delayed ACK 比例显著影响吞吐时立项;与具体 CC 算法绑定,不独立投入。

---

## 推荐顺序(按依赖关系)

**原则(2026-04-19 决策)**:先把 TCP 主干正确性 / 性能相关的标准项全部收敛,再做
"外挂能力"(网络特性协商、特定环境探测、微优化)。F5 PRR / F10 ECN / F12 MTU
probing / F13 ABC 整体推迟至末尾 — 均属于 **主路径不依赖** 的可选特性,不影响
对端兼容或基本可靠性。

核心批(F1–F11 + S-1 共 17 项)已全部收敛,实施细节见 `tcp.java.md`。

剩余外挂批(按依赖顺序,**仅在对应"触发条件"成立时**才启动):

1. **F10 ECN 协商 + F7 CWR 子态** — 依赖网络路径支持 ECN 才有收益。
2. **F5 PRR** — 作为独立 LossRecovery 模块引入(不挂 NewReno 内),方便未来
   并列 CUBIC/BBR 共用。
3. **F12 MTU probing** — 仅 ICMP 黑洞环境才需要。
4. **F13 ABC** — delayed-ACK 下慢启动微优化,与具体 CC 算法绑定。
