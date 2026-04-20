# v2 TCP Phase 4 Gap Audit(2026-04-20)

继 Phase 3 全部收敛后做的广义审计,聚焦 Linux 主干 TCP 特性中 v2 仍缺失或是 stub
的部分。每项按 **当前状态 / 对齐点 / 影响 / 建议优先级** 展开。

> **最近一次更新**:2026-04-20 — 追加 **F11 F-RTO 伪 RTO 检测**
> (`TcpSock.frtoHighmark / frtoCounter` + `onTimeoutByCc` 武装守卫
> `after(sndNxt, undoMarker)` + `tcpProcessFrto` 基于 `!before(sndUna,
> frtoHighmark)` 判定 + `tcpUndoCwndReduction(true)` 清 `lostOut` + `TcpAck`
> 的 FULLUNDO → LOSSUNDO → **SPURIOUSRTOS** → DSACKUNDO else-if 四路链 +
> `TCPSPURIOUSRTOS` MIB);与 F4-2 TSECR 通道互补,覆盖无时戳场景下的伪 RTO
> 回滚。剩余项仅余 F7 Disorder / CWR 子态(与 F10 ECN 打包、F10 已标"触发
> 条件"归档)与外挂批 11-12(F12 MTU probing / F13 ABC)。此前追加的
> **F8-S3 RACK
> `reo_wnd_steps` 1-RTT 门控对齐**(`TcpSkb.txDelivered` +
> `TcpSock.delivered / rackLastDelivered / rackAckPriorDelivered` +
> `tcp_rate_skb_sent` 等价打戳点 + `tcp_clean_rtx_queue` /
> `tcp_sacktag_write_queue` 投递侧 `tp->delivered++` +
> `tcpRackUpdateReoWnd(priorDelivered)` 的 `before(priorDelivered,
> last_delivered)` 忽略 + 步进点 `last_delivered = tp->delivered`)、
> **F8-S4 RACK `reo_wnd` 上限对齐**(`internal.WinMinMax` Kathleen Nichols
> 3 样本时间窗 + `TcpSock.rttMinFilter / minRttUs()` + `TCP_MIN_RTT_WIN_SEC=300` +
> `tcp_rack_detect_loss` 的 `reo_wnd` 改为 `min((min_rtt × steps) >> 2,
> srtt >> 3)`)与 **S-1 OFO prune 分轮 + collapse**(`TcpReceiveBuffer.tcpPruneOfoQueue`
> 先 `collapseOfoQueue` 折叠相邻段再按尾部 seq 降序 tail-drop 至回到 `OFO_MAX_BYTES`
> 预算 + `TCPPRUNECALLED / TCPOFOMERGE / TCPRCVCOLLAPSED / TCPOFODROP` MIB),
> 残余简化清单中的 S-3 / S-4 / S-1 消解。剩余项仅余 F7 Disorder / CWR 子态
> (与 F10 ECN 打包)与外挂批 9–12。

## 状态速览

| 级别      | 数 | 项目 |
|-----------|----|------|
| 🔴 高     | 0 | — |
| 🟡 中     | 2 | F5 PRR · F7 Disorder/CWR 子态 |
| 🟢 低     | 3 | F10 ECN 协商/CE 响应 · F12 MTU probing(RFC 4821)· F13 ABC |
| ✅ 已对齐 | 17 | F1.0 出站 SACK 框架 · F1.1/F1b/F1c DSACK Case 1/3/2 · F1d 发送端 DSACK 识别 · F4-0/F4-1/F4-2/F4-3 Undo 闭环(Recovery + Loss + DSACK)· F6 Limited Transmit · F8 RACK reo_wnd 动态放宽 · F8-S3 RACK reo_wnd 1-RTT 门控 · F8-S4 RACK reo_wnd 上限公式 · F9 tcp_collapse_retrans · F11 F-RTO · S1 OFO prune 分轮 + collapse · F2 tcp_cwnd_validate · F3 slow_start_after_idle |

"高/中/低" 的判据:**高**=协议正确性或明显性能回归;**中**=优化但有明确收益;
**低**=专项能力,可按需启用。

---

## 🔴 高优先级

(全部收敛 — F4-2 于 2026-04-20 已合入,见 "已对齐" 节。)

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
- **推迟理由**:NewReno + RACK + undo 闭环在中等链路已可用,PRR 的增益主要在
  中度丢包 + 高 BDP 场景;独立模块改动需要额外定义 LossRecovery 扩展点,建议
  在 F4-2 Undo + F6 Limited Transmit 等基础策略补齐之后再做。
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
- **建议**:配合 F10 ECN 一起引入 CWR;Disorder 纯计数态优先级低。

---

## 🟢 低优先级

### F10 — ECN 协商与 CE 响应(RFC 3168)

- **当前状态**:只有 `TCPHDR_ECE/CWR` 常量定义,无握手 ECN 协商、无 IP TOS
  CE-bit 检测、无 CWR 响应。
- **对齐点**:`tcp_ecn_init / tcp_ecn_check_ce / tcp_ecn_withdraw_cwr /
  tcp_enter_cwr`(tcp_input.c / tcp_output.c)。
- **影响**:不能参与 ECN 拥塞反馈;仅在网络支持 ECN 并启用时才有收益。
- **建议**:依赖 F7(CWR 子态);多数场景 ECN 不是主干需求,先放后面。
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

### ~~F11 — F-RTO 伪超时检测(RFC 5682)~~

- ~~**当前状态**:完全未实现。RTO 后不区分真丢包和延迟抖动。~~
- **2026-04-20 已完成** — 见下方 "已对齐" 节 F11 条目。

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

**原则(2026-04-19 决策)**:先把 TCP 主干正确性 / 性能相关的标准项全部收敛,再做
"外挂能力"(网络特性协商、特定环境探测、微优化)。F5 PRR / F10 ECN / F12 MTU
probing / F13 ABC 整体推迟至末尾 — 均属于 **主路径不依赖** 的可选特性,不影响
对端兼容或基本可靠性。

### 核心批(TCP 主干)

1. ~~**F2 + F3**~~ ✅ 已完成(见 "已对齐" 节)。
2. ~~**F1.0 出站 SACK 框架 + F1.1 DSACK Case 1**~~ ✅ 已完成(见 "已对齐" 节)。
3. ~~**F4-0/F4-1 Undo 最小闭环 + F1c DSACK Case 2 + F1d 发送端 DSACK 识别**~~
   ✅ 已完成(见 "已对齐" 节)。
4. ~~**F8 reo_wnd 动态调整 + F1b DSACK Case 3**~~ ✅ 已完成(见 "已对齐" 节)。
5. ~~**F6 Limited Transmit**~~ ✅ 已完成(2026-04-20,见 "已对齐" 节)。
6. ~~**F9 tcp_collapse_retrans**~~ ✅ 已完成(2026-04-20,见 "已对齐" 节)。
7. ~~**F4-2 `tcp_try_undo_loss`**~~ ✅ 已完成(2026-04-20)。
8. ~~**F11 F-RTO 伪 RTO 检测**~~ ✅ 已完成(2026-04-20,见 "已对齐" 节)。
9. **F7 Disorder 子态**(纯计数中间态,可选,随 F10 一起或单独做)。

### 外挂批(按需启用,不影响主干正确性)

9. **F10 ECN 协商 + F7 CWR 子态** — 依赖网络路径支持 ECN 才有收益。
10. **F5 PRR** — 作为独立 LossRecovery 模块引入(不挂 NewReno 内),方便未来
    并列 CUBIC/BBR 共用。
11. **F12 MTU probing** — 仅 ICMP 黑洞环境才需要。
12. **F13 ABC** — delayed-ACK 下慢启动微优化,收益有限。

---

## ✅ 已对齐

### F11 — F-RTO 伪 RTO 检测(RFC 5682)(2026-04-20)

- **背景**:F4-0/1/2/3 Undo 闭环落地后,CA_Loss 出口已有 TSECR 通道
  (`tcpTryUndoLoss`),但仅在时戳选项启用且 ACK 对端正确 echo 的窄条件下
  生效。移动 / Wi-Fi / 代理跳板场景下 RTT 突发抖动常见,每次伪 RTO 都会整轮
  塌 `cwnd = 1`,TSECR 分支只能救回"RTO 发了但原始段已在路上被 ACK"的子集,
  不能救"RTO 发了、新数据还没发出去、原来的段其实都到了"的更普遍场景。
  F-RTO(RFC 5682)给出的思路是:RTO 时把 `snd_nxt` 快照为 high mark,若后续
  ACK 的 `snd_una` 追平/越过该水位,则原始在飞段**全部**已被接收端吸收 —
  CA_Loss 期间 `cwnd = 1` 只发重传副本,不可能推高 `snd_una` 到该水位,因此
  RTO 必定是伪触发。
- **落点**:
  - `TcpSock` 新增字段:
    - `frtoHighmark`(对齐 Linux `tp->high_seq` 在 F-RTO 语境下的快照)—
      RTO 瞬间 `snd_nxt` 副本;
    - `frtoCounter`(对齐 `tp->frto`)— `0` 未武装 / `1` 本轮 RTO 已武装;
    - `clearFrto()` 统一清零方法。
    - `initInlineTcpState` 里复位。
  - `onTimeoutByCc`(对齐 `tcp_enter_loss`)武装守卫:
    ```
    tcpInitUndo();
    if (undoMarker != 0 && after(sndNxt, undoMarker)) {
        frtoHighmark = sndNxt;
        frtoCounter = 1;
    } else {
        clearFrto();
    }
    ```
    `after(sndNxt, undoMarker)` 过滤 "单段队列 / 刚建立连接 / snd.nxt ==
    snd.una" 等无参照线的场景 — 没有在飞数据就不存在伪 RTO 的判定空间。
  - `tcpProcessFrto()`(对齐 `tcp_process_loss` 中 F-RTO 分支)判定:
    ```
    if (congestionState != CA_Loss) return false;
    if (frtoCounter == 0) return false;
    if (undoMarker == 0) return false;
    if (before(sndUna, frtoHighmark)) return false;   // 尚未追平 high mark
    tcpUndoCwndReduction(true);                        // unmarkLoss=true 清 lostOut
    congestionState = CA_Open;
    caIncrCounter = 0; dupacks = 0;
    return true;
    ```
    注:`tcpUndoCwndReduction` 内部会顺带 `clearFrto()`,无需显式调用。
  - `tcpUndoCwndReduction` 追加 `clearFrto()` — 确保任何 undo 路径(Recovery /
    Loss / DSACK / F-RTO)都把 F-RTO 水位清零,避免跨 epoch 残留。
  - `onAckedByCc` 自然退出 CA_Loss 分支(非 undo 路径)也追加 `clearFrto()`,
    覆盖"真丢包 + 逐段重传 + 最终 snd_una 推过 high_mark"的收尾,保证
    `frtoHighmark` 不悬挂到下一轮 RTO 复用。
  - `TcpAck.tcpAck` 的 undo else-if 链扩展为
    **FULLUNDO → LOSSUNDO → SPURIOUSRTOS → DSACKUNDO** 四路:F-RTO 排在
    `tcpTryUndoLoss`(TSECR)之后、`tcpTryUndoDsack` 之前 —— TSECR 命中更精确
    (单段级,能早 1 个 ACK 触发),F-RTO 作为无时戳场景的兜底;两者命中后续
    DSACK 兜底不再介入。
- **对齐语义**:Linux `tcp_enter_loss` 的 F-RTO 武装 + `tcp_process_loss`
  的 `!before(snd_una, high_seq)` 触发路径(RFC 5682 的 "Step 1 ACK covers new
  data" 变体)。v2 当前不实现 Linux 的"新数据试探段"(F-RTO Step 2)—
  该步在 `cwnd = 1` 下本就无法发出,且一旦 `snd_una >= snd_nxt_at_rto` 已经
  足够判定伪触发;后续若需更敏感响应(例如配合 SACK-heavy 场景下的 F-RTO
  Step 2 "send 2 new segments"),可作为后续增量。
- **与 F4-2 TSECR 通道协同**:
  - TSECR 路径:`tcpTryUndoLoss(tsecr)` 在 CA_Loss 首 ACK 时通过对端 echo
    的 TSval 确认"被 ACK 的段是原始段";依赖 `timestampEnabled` 且 ACK
    正确 echo。
  - F-RTO 路径:`tcpProcessFrto()` 不依赖时戳,只看累计 ACK 水位;在 TS
    关闭或 ACK 丢 echo 的边缘场景下兜底。
  - 两条互补、不冲突;命中任一即 MIB 记账一次,不会重复。
- **MIB**:`TcpMib.TCPSPURIOUSRTOS` 新增,对齐 `LINUX_MIB_TCPSPURIOUSRTOS`
  (`include/uapi/linux/snmp.h`)。
- **影响**:延迟抖动场景下伪 RTO 可直接回滚 `cwnd/ssthresh` 快照,不再整轮
  塌到 `cwnd = 1` 并逐段慢启重建;与 F4-2 共同闭合 "RTO 侧伪触发"缺口。

### S-1 — OFO prune 分轮 + collapse(2026-04-20)

- **背景**:Phase 4 残余简化清单 **S-1**(OFO prune 只做单层按字节 drop,无
  `tcp_collapse_ofo_queue` 相邻段折叠)。Linux `tcp_prune_queue` 先
  `tcp_collapse_ofo_queue`(把 seq 连续的相邻 skb 合并减少 `sk_rmem_alloc` /
  entry count),再在仍超预算时 `tcp_prune_ofo_queue` 从 `out_of_order_queue`
  的 **rb_last** 端逐条丢弃高 seq 段 —— 丢高 seq 段的假设是"接收方已吸收低
  seq 段的接续更容易推进 `rcv_nxt`"。v2 旧的 `pruneOfoQueue` 跳过折叠直接
  drop,与 Linux 路径完全不同。
- **落点**:
  - `TcpReceiveBuffer.tcpPruneOfoQueue()` 新路径:
    ```
    TcpMibStats.INSTANCE.inc(TcpMib.TCPPRUNECALLED);
    collapseOfoQueue();
    while (ofoBytes >= OFO_MAX_BYTES && !ofoQueue.isEmpty()) {
        Map.Entry<Integer, TcpSkb> last = ofoQueue.pollLastEntry();
        removeOfoBytes(last.getValue().payload().readableBytes());
        last.getValue().release();
    }
    ```
  - `TcpReceiveBuffer.collapseOfoQueue()` 新方法:遍历 `TreeMap<Integer, TcpSkb>`
    (`Integer::compareUnsigned` 已在构造时指定),相邻项在 `prev.endSeq() ==
    next.startSeq()` 且两段均未携带 FIN / 合并后长度 ≤ `OFO_COLLAPSE_MAX_LEN`
    (64 KiB)时,用 `Unpooled.wrappedBuffer(prev.payload().retainedSlice(),
    next.payload().retainedSlice())` 生成 CompositeByteBuf,释放 prev+next 原
    ByteBuf,以 prev 的 seq 重新放回 TreeMap;每次合并计 `TCPOFOMERGE` + `TCPRCVCOLLAPSED`。
  - `TcpReceiveBuffer.offerOfo` 在 `ofoBytes >= OFO_MAX_BYTES` 时调用
    `tcpPruneOfoQueue()`;如果折叠后仍无空间则段被 drop,追加 `TCPOFODROP` MIB。
  - `OFO_COLLAPSE_MAX_LEN = 64 * 1024` 常量 — Linux 没有显式单段合并上限,v2
    为避免 CompositeByteBuf 单段过长影响后续 slice 读取开销,加一个保守的 64 KiB
    阈值;典型 OFO 段 ≤ 16 KiB 左右,上限很少触发。
- **对齐语义**:Linux `tcp_collapse_ofo_queue`(tcp_input.c)+ `tcp_prune_ofo_queue`
  (tcp_input.c)+ `tcp_prune_queue`(tcp_input.c)的路径分层。v2 的
  `ofoBytes` **只计 payload 字节**(不计 truesize / 结构体开销),与 Linux 的
  `sk_rmem_alloc` 计账维度不同 —— 所以 collapse 在 v2 不直接降低字节预算,
  主要起到**减少 entry 数**和**合并相邻段方便后续累计 ACK 一次吸收**的作用;
  真正把 `ofoBytes` 压回到 `OFO_MAX_BYTES` 以下靠 tail-drop 循环。
- **尾丢策略**:改为 `while (ofoBytes >= OFO_MAX_BYTES)` 直到回到预算,而非
  "drop 一半" —— 对齐 Linux 的 `while (READ_ONCE(sk->sk_rmem_alloc) > sk->sk_rcvbuf)`
  循环语义,避免在持续 DoS / 重度乱序场景下过度 tail-drop。
- **MIB**:`TCPPRUNECALLED` / `TCPOFOMERGE` / `TCPRCVCOLLAPSED` / `TCPOFODROP`
  四项计数对齐 `LINUX_MIB_TCPPRUNECALLED` / `LINUX_MIB_TCPOFOMERGE` /
  `LINUX_MIB_TCPRCVCOLLAPSED` / `LINUX_MIB_TCPOFODROP`;前三项先前已登记但只有
  reserved 计数,此次挂载到实际路径。
- **风险点**:`Unpooled.wrappedBuffer` 构造的 CompositeByteBuf 在后续 `readBytes`
  时是跨子 ByteBuf 切分读取,引用计数由 Netty 的 `retainedSlice` 已正确递增;
  任何路径释放合并后的 `payload()` 都会级联释放两个子 slice,与未合并前的生命
  周期等价。

### F8-S4 — RACK `reo_wnd` 上限对齐 `min_rtt` 窗口(2026-04-20)

- **背景**:F8 阶段 RACK 动态 `reo_wnd` 主体已就位,但简化清单 **S-4**
  (`reo_wnd` 上限改用 `2 × srtt` 代替 Linux `srtt >> 3`,基值用 `srtt/4`
  代替 `min_rtt >> 2`)——  Linux 公式为 `min((min_rtt × steps) >> 2,
  srtt >> 3)`,依赖 `tcp_min_rtt(tp)`(由 Kathleen Nichols 3 样本窗口过滤器
  维护的 `tp->rtt_min`,`net/core/../win_minmax.c` + `include/linux/win_minmax.h`
  + `tcp_rtt_estimator` 里 `minmax_running_min` 喂样本)返回 O(1) 的
  `min_rtt_win` 期内运行最小 RTT。v2 缺少这个 `min_rtt` 基础设施,只能用 `srtt`
  凑合,导致 `reo_wnd` 基值显著大于 Linux(srtt 通常远大于 min_rtt),steps
  伸缩空间被压扁。
- **落点**:
  - `pangolin-routing/src/main/java/com/github/pangolin/routing/acceptor/tun/net/v2/tcp/internal/WinMinMax.java`
    新文件 —— Kathleen Nichols 3 样本窗口过滤器的 Java 移植:
    ```
    int t0,t1,t2;   // 三个候选最小点的时间戳(tcp_jiffies32, ms)
    int v0,v1,v2;   // 对应的 RTT 值(µs)
    update(int win, int t, int v);      // 对齐 minmax_running_min
    subwinUpdate(int win, int t, int v);// 对齐 minmax_subwin_update
    reset(int t, int v); min(); minTimestamp();
    ```
    语义与 `lib/win_minmax.c` 一致:新样本更小则重置所有槽位;新样本过期(与
    `t0` 差 > win)则滚动槽位;常规情形按 "最近 win/4 窗口内最小值" 在 v0/v1/v2
    间迁移。
  - `TcpConstants` 新增:
    ```
    TCP_MIN_RTT_WIN_SEC  = 300;         // 对齐 Linux tcp_min_rtt_wlen 默认值
    TCP_MIN_RTT_NO_SAMPLE = Integer.MAX_VALUE; // 对齐 ~0U
    ```
  - `TcpSock`:`final WinMinMax rttMinFilter = new WinMinMax()`(因是引用类型,
    `initInlineTcpState / newInstanceOf` 里用 `rttMinFilter.reset(0,
    TCP_MIN_RTT_NO_SAMPLE)` 重置而非重分配,保留 final)+ `int minRttUs()`
    getter。
  - `TcpSock.addRttSample(long rttUs)` 在现有 SRTT / RTTVAR 更新之外追加:
    ```
    int nowJiffiesMs = (int) TcpClock.tcp_jiffies32();
    int sampleUs = (rttUs > Integer.MAX_VALUE) ? Integer.MAX_VALUE - 1 : (int) rttUs;
    rttMinFilter.update(TCP_MIN_RTT_WIN_SEC * 1_000, nowJiffiesMs, sampleUs);
    ```
    即每次 RTT 观察同时喂入 WinMinMax;300 秒窗口与 Linux 一致。
  - `TcpAck.tcp_rack_detect_loss` 的 `reoWnd` 公式替换为:
    ```
    long baseUs;
    if (minRttUs < TCP_MIN_RTT_NO_SAMPLE) {
        baseUs = ((long) minRttUs * steps) >>> 2;
    } else {
        baseUs = srttUs > 0 ? ((srttUs >>> 2) * steps) : 1_000L;
    }
    long capUs    = srttUs > 0 ? srttUs >>> 3 : 8_000L;
    long reoWndUs = Math.max(Math.min(baseUs, capUs), 1_000L);
    ```
    **乘法先行**(`min_rtt × steps`)再 `>> 2`:避免 `min_rtt < 4 µs` 时
    `(min_rtt >> 2) × steps = 0`(Linux 同 — `(min_rtt_us * rack.reo_wnd_steps)
    >> 2`);无 min_rtt 样本时回落到与旧逻辑近似的 `srtt/4 × steps`,保证连接
    冷启动阶段仍能运行;`srtt >> 3` 作为共同上限。
- **对齐语义**:Linux `tcp_rack_reo_wnd`(tcp_recovery.c):
  ```c
  return min((tcp_min_rtt(tp) * tp->rack.reo_wnd_steps) >> 2,
             tp->srtt_us >> 3);
  ```
  (外层再 `max(reo_wnd, 1000)`)— 公式、量级、优先级全部一致。`tcp_min_rtt`
  在 Linux 里实质是 `minmax_get(&tp->rtt_min)` 读出 300 秒时间窗内的最小 RTT,
  v2 的 `rttMinFilter.min()` 对应同一语义。
- **影响**:
  - `reo_wnd` 的典型 steady-state 基值由 `srtt/4` 降到 `min_rtt/4`(一般 <<
    `srtt/4`),上限由 `2 × srtt` 收紧到 `srtt/8` — steps 伸缩再次有效:
    `steps=1 → min_rtt/4`、`steps=2 → min_rtt/2`、…… 在触顶 `srtt/8` 前能
    完整过渡,乱序响应由此恢复 Linux 级灵敏度。
  - `min_rtt` 只在 300 秒窗口内有效,路径 RTT 长期突变时会自动被 WinMinMax
    滚动遗忘(对齐 Linux 的 `tcp_min_rtt_wlen`)。
- **F8 旧条目的 "未覆盖" 两条同步消解**:
  - "Linux `srtt >> 3` 上限" → 已采用;
  - "1-RTT 门控" 由 F8-S3 消解。F8 原条目的 "未覆盖" 注记在 gap.md / tcp.java.md
    中已相应更新。

### F8-S3 — RACK `reo_wnd_steps` 1-RTT 门控(2026-04-20)

- **背景**:F8 阶段已落地 RACK `reo_wnd` 动态放宽(`reo_wnd_steps / persist /
  dsack_seen`),但受限于简化清单 **S-3**(`tcp_rack_update_reo_wnd` 缺少
  `rs->prior_delivered` 与 `rack.last_delivered` 的 1-RTT 门控)—— 仅靠
  `reo_wnd_persist` 消费稳态放宽,无法区分 "同一 RTT 内的旧 DSACK" 与 "跨越
  一个 RTT 的新 DSACK"。Linux `tcp_rack_update_reo_wnd` 的第一步是
  `before(rs->prior_delivered, rack.last_delivered) → rs->dsack_seen = 0`,
  直接把同一 RTT 内的重复 DSACK 过滤掉,避免 `reo_wnd_steps` 在单 RTT 内
  多次步进被 Linux 封顶逻辑"截断"。
- **落点**:
  - `TcpSkb.txDelivered` 字段对齐 `TCP_SKB_CB(skb)->tx.delivered`(快照
    `tp->delivered`);`splitRtx / collapseRtx / tcp_rtx_fragment` 全量继承
    (拆分时 head + tail 都取父段的 `txDelivered`;合并时取 `head` 的快照,
    因为 `head` 是较早发送段,`rs->prior_delivered` 取 max 语义下无损)。
  - `TcpSock.delivered`(对齐 `tp->delivered`)+ `rackLastDelivered`
    (对齐 `tp->rack.last_delivered`)+ 每 ACK 瞬态 `rackAckPriorDelivered`
    (对齐 `rate_sample.prior_delivered`,`tcpAck` 入口清零)三件套。
  - `TcpOutput.tcp_event_new_data_sent` + `tcp_retransmit_skb` 发送瞬间
    `skb.txDelivered(sock.delivered())` — 对齐 Linux
    `tcp_rate_skb_sent` 的 "发送时打戳 `tp->delivered` 快照" 语义;重传
    时也会刷新快照(Linux 同),这样被 ACK 时参与的是最新 `prior_delivered`
    坐标。
  - `TcpAck.tcp_clean_rtx_queue` 非 SACKED 分支 `sock.incrDelivered(1)`
    (首次投递)+ 所有路径 `sock.updateRackAckPriorDelivered(skb.txDelivered())`
    (单调取最大);`TcpAck.tcp_sacktag_write_queue` 新 tagged 分支
    `sock.incrDelivered(1)` + `sock.updateRackAckPriorDelivered(target.txDelivered())`。
  - `TcpSock.tcpRackUpdateReoWnd(int priorDelivered)` 接口变更:
    ```
    if (priorDelivered == 0) return;          // 本 ACK 未确认已打戳段
    if (rackDsackSeen &&
        before(priorDelivered, rackLastDelivered))
            rackDsackSeen = false;            // 同 RTT 内的旧 DSACK 忽略
    if (rackDsackSeen) {
        rackReoWndSteps = min(steps + 1, 0xFF);
        rackDsackSeen = false;
        rackLastDelivered = delivered;         // 步进点同步基线
        rackReoWndPersist = TCP_RACK_RECOVERY_THRESH;
    } else if (rackReoWndPersist <= 0) {
        rackReoWndSteps = 1;
    } else {
        rackReoWndPersist--;
    }
    ```
  - `TcpAck.tcpAck` 尾部改为 `sock.tcpRackUpdateReoWnd(sock.rackAckPriorDelivered())`
    传入本 ACK 的 scratchpad。
- **对齐语义**:Linux `tcp_rack_update_reo_wnd`(tcp_recovery.c)+
  `tcp_rate_skb_sent`(tcp_rate.c)+ `tcp_rate_skb_delivered` 的
  `rs->prior_delivered` 收集逻辑。v2 把 Linux 的 `struct rate_sample`
  瘦成单个 `rackAckPriorDelivered` 字段 —— 主干 `rs->prior_delivered` 消费
  只为 RACK 1-RTT 门控服务,其它 rate sampling(delivery rate)未进入,
  暂无其它字段需求。
- **影响**:RACK 在 spurious fast retransmit 场景下不再重复步进
  `reo_wnd_steps`,`reo_wnd` 放宽节奏与 Linux 一致。
- **关联**:F8(`reo_wnd` 动态放宽主体)+ F4-3(`undo_retrans` DSACK 抵扣);
  三者共同闭合 RACK + DSACK undo 链。

### F4-3 — `tcp_try_undo_dsack`(DSACK-driven undo)(2026-04-20)

- **背景**:Phase 4 残余简化清单中的 S-2(`undo_retrans` 只有自增,无 DSACK 抵扣)
  和 S-6(`tcp_try_undo_recovery / tcp_try_undo_loss` 只走 TSECR 分支,无
  `!tp->undo_retrans` 兜底)成对消除 — 两者构成 Linux DSACK undo 的 "记账 +
  触发" 双侧,只修单边会留下死代码。
- **落点**:
  - `TcpAck.tcp_clean_rtx_queue` 删除旧的非 Linux-aligned 兜底
    (`decrUndoRetrans(ackedRetrans)` 把 `undoRetrans` 退化为 "待 ACK 重传数"
    计数,以及 `undoRetrans==0 → retransStamp=0` 清零);`undoRetrans` 字段从
    此只在 DSACK 观察到 Case 1/2 时自减,语义切回 Linux 的 "本 epoch 里还有多少
    重传尚未被 DSACK 抵消"。
  - `TcpAck.tcp_sacktag_write_queue` 的 DSACK 命中块追加 Linux `tcp_check_dsack`
    末段守卫递减:
    ```
    if (undoMarker != 0
        && undoRetrans > 0
        && !after(fe, priorSndUna)
        && after(fe, undoMarker))
            decrUndoRetrans(1);
    ```
    `!after(fe, priorSndUna)` 天然滤掉 Case 3 OFO DSACK(首块 end 在
    `prior_snd_una` 之上),只对 Case 1/2(已被累积 ACK 吸收且高于 `undo_marker`,
    可以归因到本 epoch 里的某次重传副本)递减。
  - `TcpSock.tcpTryUndoDsack()`:`CA_Recovery ‖ CA_Loss + undoMarker != 0 +
    retransStamp != 0L + undoRetrans == 0` → `tcpUndoCwndReduction(false)` +
    CA_Open + `dupacks=0, caIncrCounter=0`。与 `tcpTryUndoRecovery` /
    `tcpTryUndoLoss` 一致的出口语义,仅触发条件换成 "本 epoch 所有重传都被
    DSACK 抵消"。
  - `TcpAck.tcpAck` 尾部链 `FULLUNDO → LOSSUNDO → DSACKUNDO` else-if 调用,
    对齐 Linux `tcp_fastretrans_alert` 中 `tcp_packet_delayed`(TSECR)优先、
    `!tp->undo_retrans`(DSACK)兜底的顺序;命中分别记
    `TCPFULLUNDO / TCPLOSSUNDO / TCPDSACKUNDO`。
  - `TcpSock.tcpInitUndo()` 同步重置 `undoRetrans = 0; retransStamp = 0L` —
    因为移除了 `tcp_clean_rtx_queue` 的兜底清零,epoch 边界必须在此重新打基线,
    否则上一次自然退出的残留值会污染本次 DSACK 判定。
- **对齐语义**:Linux `tcp_check_dsack`(tcp_input.c)+ `tcp_try_undo_dsack`
  (tcp_input.c 2611 附近)+ `tcp_fastretrans_alert` 中的 `!tp->undo_retrans`
  分支。`undo_retrans` 语义完全对齐 Linux `tp->undo_retrans`(仅 DSACK 观察侧
  减少 / 重传发出时自增)。
- **MIB**:`TcpMib.TCPDSACKUNDO` 从 reserved 升级为活跃计数器,对齐
  `LINUX_MIB_TCPDSACKUNDO`。

### F4-2 — `tcp_try_undo_loss`(CA_Loss 出口 TSECR undo)(2026-04-20)

- **落点**:
  - `TcpSock.tcpTryUndoLoss(int tsecr)`:CA_Loss + `undoMarker/retransStamp` 有效 +
    `TcpSequence.before(tsecr, retransStamp/1000)` 命中 → `tcpUndoCwndReduction(false)`
    恢复 pre-RTO `cwnd / ssthresh` 快照,迁 CA_Open,重置 `dupacks / caIncrCounter`。
  - `TcpAck.tcpAck` 在 F4-1 未命中时继续尝试 F4-2,命中记 `TCPLOSSUNDO++` MIB。
  - F4-0 `tcpInitUndo()` 已在 `onTimeoutByCc` 里完成快照(F4-0 阶段已落地),F4-2
    属纯消费侧补齐。
- **对齐语义**:Linux `tcp_fastretrans_alert → tcp_try_undo_loss`(tcp_input.c:2722)
  的 TSECR 分支。`!tp->undo_retrans` 兜底由 F4-3 `tcpTryUndoDsack()` 在 `TcpAck.tcpAck`
  尾部的 else-if 链中串联,与本方法共享 CA_Loss 入口。
- **MIB**:`TcpMib.TCPLOSSUNDO` 对齐 `LINUX_MIB_TCPLOSSUNDO`。

### F6 — RFC 3042 Limited Transmit(2026-04-20)

- **落点**:
  - `TcpSock.dupacks()` / `isCaOpen()` getter(`CongestionState` 枚举对 ng 包外
    不可见,以谓词形式暴露)。
  - `TcpOutput.tcp_cwnd_test`:CA_Open + `dupacks ∈ [1,2]` 时 `bonus = min(dupacks, 2)`
    并入配额 `quota = max(0, cwnd + bonus - in_flight)`;等价于 Linux NewReno
    `tcp_add_reno_sack` 缩小 `in_flight` 的效果,保持 ACK clock。
  - 仅当 `in_flight ≥ cwnd` 且 `quota > 0`(真正吃到 Limited Transmit 额度)才计
    `TCPLIMITEDTRANSMIT` MIB,避免普通非受限路径误计。
- **对齐语义**:RFC 3042 / Linux `tcp_fastretrans_alert` 中 Limited Transmit 分支
  (`tp->sacked_out` 未达 `dupthresh` 时的 ACK clock 维持)。
- **MIB**:`TcpMib.TCPLIMITEDTRANSMIT` 对齐 `LINUX_MIB_TCPLIMITEDTRANSMIT`。

### F9 — `tcp_collapse_retrans`(RTX 相邻小段合并)(2026-04-20)

- **落点**:
  - `TcpSendBuffer.collapseRtx(head, mssNow) → CollapseResult{merged, droppedSacked}`:
    - 找到 `head` 在 `rtxQueue` 中的位置与紧邻下一段 `next`;
    - 预判序号严格连续(`head.endSeq() == next.startSeq()`)、两段均非 SYN、均未
      `TCPCB_SACKED_ACKED`、合并后 `dataLen ≤ mssNow`,否则返回 `null` 队列不变;
    - `Unpooled.wrappedBuffer` 拼接两段 `retainedSlice` 生成合并 payload;
    - `tcpFlags` 取两段并集(覆盖 FIN/PSH),`sacked` 继承 head 再 OR 上 next 的
      `TCPCB_EVER_RETRANS`(仅此一位,其他位集不跨越),`sentTimeUs` 取较早者;
    - O(n) 重建 `rtxQueue`,释放原 head / next,返回 `CollapseResult`,额外回带
      next 的 `sacked` 原值给调用方做计数调整(对齐 Linux `tcp_adjust_pcount`)。
  - `TcpOutput.tcp_retransmit_skb`:在 avail_wnd fragment 之后、实际重传之前,若
    `oldest.dataLen() < mss` 且未被 SACK_ACKED 则尝试 `collapseRtx`;合并成功后
    `oldest = cr.merged` 并按 `droppedSacked` 位集递减 `packetsOut`(恒 -1)及
    `lostOut`(next 带 `TCPCB_LOST` 时 -1),再继续后续 `retrans_stamp /
    undo_retrans / markRetransmitted / __tcp_transmit_skb` 路径。
- **对齐语义**:Linux `tcp_collapse_retrans`(tcp_output.c:2829)+
  `tcp_adjust_pcount` + `tcp_skb_collapse_tstamp`;v2 未接入 Linux
  `sk_wmem_queued / tcp_rtx_queue_unlink` 细节(改为在原位重建 `ArrayDeque`,O(n)
  但 RTX 段量级有限,与 `splitRtx` 同策略),也未维护 `retrans_out`(v2 无此计数),
  其余 `packets_out / lost_out` 调整语义与 Linux 一致。

### F1b — 接收端 DSACK Case 3(OFO 重叠)(2026-04-19)

- **落点**:
  - `TcpReceiveBuffer.offerOfo` 返回值由 `boolean` 改为新 `OfoResult{queued,
    dsackStart, dsackEnd, hasDsack()}`;
  - Predecessor 覆盖 → DSACK `[origSeq, min(origEnd, prev.endSeq()))`,新段完全
    被 pred 覆盖时同路径返回 `queued=false`;
  - Successor 完全被新段覆盖(evict 分支)→ DSACK `[succ.start, succ.end)`;
  - Successor 头部与新段尾部重叠(trim 分支)→ DSACK `[succ.start, endSeq)`,再
    把 `endSeq = succ.start`;
  - 多次重叠时 last-writes-wins,最终仅发 1 块 DSACK(对齐 Linux `tp->duplicate_sack[0]`)。
  - `TcpReceiveBuffer.offer` 的 OFO 防御性兜底分支把 `OfoResult.dsackStart/End`
    透传进自己的 `OfferResult`。
  - `TcpMultiplexer.tcp_data_queue_ofo` 消费 `OfoResult`:`hasDsack()` 时 `sk.setDsack(...)`;
    即便段被丢弃(`!queued && hasDsack()`)仍 `enterQuickAckMode + ACK_NOW` 立即回报。
- **对齐语义**:Linux `tcp_data_queue_ofo` 中 "All the bits are present / Partial
  overlap" 分支调用 `tcp_dsack_set`(RFC 2883 Case 3)。v2 的 "succ fully covered
  by new" 是 Linux 没有单独分支的补充 — 新段确实携带了已在 OFO 存在过的字节,
  回报 DSACK 属于 RFC 2883 允许的扩展反馈。

### F8 — RACK `reo_wnd` 动态放宽(`tcp_rack_update_reo_wnd`)(2026-04-19)

- **落点**:
  - `TcpSock` 新增字段:`rackReoWndSteps`(初始 1)/ `rackReoWndPersist`(初始 0)
    / `rackDsackSeen`(transient);`initInlineTcpState` 与 attach 辅助路径均复位。
  - `TcpConstants.TCP_RACK_RECOVERY_THRESH = 16` — 对齐 Linux
    `net/ipv4/tcp_recovery.c` 同名常量。
  - `TcpSock.setRackDsackSeen(boolean)` / `rackDsackSeen()` / `rackReoWndSteps()`;
    `TcpAck.tcp_sacktag_write_queue` 命中 DSACK 首块时 `sock.setRackDsackSeen(true)`
    (与 `TCPDSACKRECV` 计数同点)。
  - `TcpSock.tcpRackUpdateReoWnd()` — DSACK 命中 → `steps = min(steps+1, 0xFF)` +
    `persist = 16` + 清 `dsackSeen`;否则 `persist--`,`persist <= 0` 衰减 `steps = 1`。
    `TcpAck.tcpAck` 在 `onAckedByCc` 之后调用,每个 ACK 驱动一次 step/decay。
  - `TcpAck.tcp_rack_detect_loss` 的 `reoWnd` 由 `max(srtt/4, 1ms)` 改为
    `max(min(srtt/4 × steps, 2×srtt), 1ms)` — base 保留原 `srtt/4`(steps=1 时
    行为不变),上限放宽到 `2×srtt`。
- **对齐语义**:Linux `tcp_rack_update_reo_wnd` 的核心三态(dsack_seen 步进 /
  persist 倒计时 / 到期衰减)已落地。原稿标注的两条 "未覆盖":
  - ~~**1-RTT 门控**(`rs->prior_delivered < tp->rack.last_delivered`)~~ — 已由
    **F8-S3(2026-04-20)** 补齐,见上方 F8-S3 条目。
  - ~~**Linux `srtt >> 3` 上限**~~ — 已由 **F8-S4(2026-04-20)** 补齐,公式替换为
    `min((min_rtt × steps) >> 2, srtt >> 3)`,见上方 F8-S4 条目。
- **与其他模块协同**:MIB 计数 `TCPDSACKRECV` 仍在 sacktag 主路径维护;RACK
  loss 检测本身(`tcp_rack_detect_loss` / `TCPCB_LOST`)不变,只有时间窗口随
  DSACK 事件伸缩。

### F4-0 / F4-1 — Undo 最小闭环(`tcp_try_undo_recovery`, TSECR-based)(2026-04-19)

- **落点**:
  - `TcpMultiplexer.TcpSock` 新增字段:`priorCwnd / priorSsthresh / undoMarker`
    (one-shot inline-only,与 `dsackStart/dsackEnd` 同模式,不镜像到
    `TcpConnection`);`initInlineTcpState` 重置。
  - `TcpSock.tcpInitUndo()` — 快照 `cwnd / ssthresh(非 INT_MAX 才存) / sndUna`;
    在 `onAckedByCc` 的 Recovery 入口(`dupacks==3`)与 `onTimeoutByCc` 的 Loss
    入口(RTO)各调用一次。
  - `TcpSock.tcpUndoCwndReduction(boolean unmarkLoss)` — `cwnd = max(cwnd,
    priorCwnd)` / `ssthresh = max(ssthresh, priorSsthresh)`;清 `undoMarker /
    retransStamp / undoRetrans`;`unmarkLoss=true` 时清空 `lostOut`(留给 F4-2)。
  - `TcpSock.tcpTryUndoRecovery(int tsecr)` — CA_Recovery + 有 undo 机会 +
    `TcpSequence.before(tsecr, retransStamp/1000)` 三条件命中时调
    `tcpUndoCwndReduction(false)` 并直接迁 CA_Open(`dupacks=0`,跳过自然
    `cwnd=ssthresh` deflate)。
  - `TcpAck.tcpAck` 在 `tcp_clean_rtx_queue / tcpProcessTlpAck` 之后、
    `onAckedByCc` 之前调用,命中累加 `TCPFULLUNDO` MIB。
  - `TcpAck.parseTimestampEcho(pkt)` — 取 `parseTimestamp(...)` 的 `ts[1]`(TSECR)。
- **对齐语义**:`tp->prior_cwnd / prior_ssthresh / undo_marker` 快照 +
  `tcp_packet_delayed`(TSECR 早于 retrans_stamp)+ `tcp_undo_cwnd_reduction`;
  `retrans_stamp` (us) 对比时 `/1000` 折成 32-bit ms 以对齐 TSval 域,
  `TcpSequence.before` 做 wrap-safe 比较。
- **F4-3 已补齐**:`!tp->undo_retrans` 兜底路径由 `tcpTryUndoDsack()` + DSACK
  Case 1/2 `tcp_check_dsack` 守卫递减闭环(MIB `TCPDSACKUNDO`),与 F4-1 / F4-2
  的 TSECR 分支 else-if 互斥。F11 F-RTO 作为后续批次。

### F1c — 接收端 DSACK Case 2(partial cross-boundary)(2026-04-19)

- **落点**:`TcpReceiveBuffer.offer` 的 `seq <= rcvNxt < endSeq` 分支,当
  `skip > 0` 时回带 `dsackStart = seq; dsackEnd = rcvNxt`;后续路径与 F1.1
  共享 —— 经 `OfferResult` 传到 `TcpSock.setDsack`,`tcp_build_ack_options` 把它
  置于 SACK 首位通告。
- **对齐语义**:`tcp_dsack_extend` 的 Case 2 — 段跨 `rcv_nxt` 边界,
  `[seq, rcv_nxt)` 是被吸收的重复区段。

### F1d — 发送端 DSACK 识别 + SACK tagging 跳过首块(2026-04-19)

- **落点**:`TcpAck.tcp_sacktag_write_queue` 入口新增 `sackStartIdx` 判定 —
  - Case 1/2:首块 `end` 不超过 `priorSndUna` → DSACK;
  - Case 3:至少两块时首块被第二块包含(反序形态)→ DSACK。
  命中则 `TCPDSACKRECV++`,后续 tagging 循环从 `sackStartIdx = 2` 开始,首块不
  参与 `carveToBlock` / `TCPCB_SACKED_ACKED` 记账。
- **对齐语义**:Linux `tcp_check_dsack` 的 `found_dup_sack` 判定 + `tcp_sacktag_walk`
  跳过首块。
- **与 undo 的关系**:2026-04-20 起 F4-3 已补齐 `!tp->undo_retrans` 兜底 —
  `tcp_sacktag_write_queue` 在首块被识别为 DSACK(Case 1/2)时按 Linux
  `tcp_check_dsack` 守卫 `undoRetrans--`,`TcpAck.tcpAck` 尾部 `tcpTryUndoDsack`
  命中后记 `TCPDSACKUNDO++`;DSACK + TSECR 路径互斥(FULLUNDO → LOSSUNDO →
  DSACKUNDO else-if 链)。
- **新增 MIB**:`TCPDSACKRECV` · `TCPDSACKUNDO` · `TCPFULLUNDO`。

### F1.0 — 出站 SACK 框架(2026-04-19)

- **落点**:
  - `TcpOptionCodec.writeSackOption(out, blocks, nBlocks)` — 写 `NOP + NOP +
    Kind=5 + Length + n × (left, right)`,对齐 Linux `tcp_options_write` SACK 分支。
  - `TcpOutput.tcp_send_ack` 改走 `tcp_build_ack_options(TcpSock)`:同时组合
    TSopt(如启用)+ DSACK(如存在)+ OFO 衍生 SACK 块(上限 3/4 由是否有 DSACK
    决定),并通过 `TcpPacketBuilder.buildRaw(..., options, ...)` 的 options 参数
    发出。
  - `TcpReceiveBuffer.computeSackBlocks(out, maxBlocks)` — O(n) 扫 `ofoQueue`
    合并相邻段,返回 SACK 块数。
- **对齐语义**:数据段保持 TSopt-only(`tcp_established_options` 未改,MSS 不受
  影响);ACK 段独立构建 options,与数据段 MSS 解耦,对齐 Linux `tcp_send_ack` 与
  `tcp_transmit_skb` 对 ACK 段允许附带 SACK 块的做法。
- **RFC 2883 排序**:DSACK 块置于 SACK options 首位,OFO 衍生块随后,上限
  `nBlocks = 4`(含 DSACK 时 3 + 1)。

### F1.1 — 接收端 DSACK Case 1(RFC 2883 / `tcp_dsack_set`)(2026-04-19)

- **落点**:
  - `TcpReceiveBuffer.offer`:当 `!after(endSeq, rcvNxt)` 命中时(整段已被
    cumulative ACK 吸收),直接释放 `data` 并在 `OfferResult` 中带回
    `[dsackStart, dsackEnd] = [seq, endSeq]`。
  - `TcpReceiveBuffer.OfferResult` 扩展 `dsackStart / dsackEnd + hasDsack()`。
  - `TcpMultiplexer.queue_and_out` / `TcpDataHandler.onData`:`r.hasDsack()` 时
    调 `sock.setDsack(...)`,前者额外 `enterQuickAckMode(TCP_MAX_QUICKACKS)` +
    `addAckPending(ACK_NOW)` 立即促发 ACK。
  - `TcpSock` 新增 `dsackStart/dsackEnd` 字段(one-shot,不镜像到
    `TcpConnection`)+ `setDsack / hasPendingDsack / consumeDsack`;
    `initInlineTcpState` 重置。
  - `TcpOutput.tcp_build_ack_options` 在组包时 `consumeDsack` 并置于 SACK options
    首位。
- **对齐语义**:`tcp_dsack_set` 的 Case 1(fully duplicate segment);consume-on-send
  语义与 Linux `tp->duplicate_sack[0]` 只在下一次 `tcp_send_ack` 被写出后清零
  等价。
- **未覆盖**:Case 2 / Case 3 / 发送端 `dup_sack` 消费 — 见 🔴 高 F1 条目的打包
  计划。

### F2 — `tcp_cwnd_validate` / application-limited 追踪(2026-04-19)

- **落点**:
  - `TcpSock.tcpCwndValidate(boolean)` + `tcpCwndApplicationLimited()`
    (`TcpMultiplexer.java`)
  - `TcpSock.onDataSentUpdateCwndUsed()` — 在 `tcp_event_new_data_sent` 中调用,
    跟踪 `snd_cwnd_used` 高水位
  - 新增字段:`sndCwndStampMs / sndCwndUsed / isCwndLimited`
    (在 `TcpConnection` 与 `TcpSock` 双份持有,经 `loadFromConnection` 同步)
- **对齐语义**:当发送路径非 cwnd 受限且距上次标定 >= RTO 时,`cwnd = (cwnd +
  max(snd_cwnd_used, init_cwnd)) / 2`;CA_Open 下同时 clamp `ssthresh` 到
  `max(ssthresh, 3*cwnd/4 + 1)`,对齐 `tcp_current_ssthresh` 近似。
- **外部调用**:`TcpOutput.tcp_write_xmit` 末尾 `sock.tcpCwndValidate(is_cwnd_limited)`。

### F3 — `tcp_slow_start_after_idle` / `tcp_cwnd_restart`(2026-04-19)

- **落点**:
  - `TcpSock.tcpSlowStartAfterIdleCheck()` + `tcpCwndRestart(delta, rtoMs)`
    (`TcpMultiplexer.java`)
  - 新增 sysctl:`SysctlOptions.ipv4_sysctl_tcp_slow_start_after_idle`(默认 true)
- **对齐语义**:入 `tcp_write_xmit` 前检查 `packetsOut == 0 && lastSendTimeMs != 0
  && (now - lastSendTimeMs) > rto`,满足则 `cwnd >>= 1` 按 `delta/rto` 步数衰减,
  下限为 `min(TCP_INIT_CWND, cwnd)`;进入前若 CA_Open 则先把 ssthresh clamp 到
  `max(ssthresh, 3*cwnd/4 + 1)`。
- **外部调用**:`TcpOutput.tcp_write_xmit` 入口 `sock.tcpSlowStartAfterIdleCheck()`。
