# v2 TCP 与 v1 / Linux 对齐状态

跟踪 v2 TCP 实现(`com.github.pangolin.routing.acceptor.tun.net.v2.tcp`)相对 Linux
内核 `net/ipv4/tcp_*.c` 的对齐进度。每条残余 gap 按 **当前状态 / 残余 / 影响 / 建议**
四要素展开。

> 最近一次更新:2026-04-20 — 追加 **Phase 4 F11 F-RTO 伪 RTO 检测**(`TcpSock.frtoHighmark/frtoCounter` + `onTimeoutByCc` 武装守卫 `after(sndNxt, undoMarker)` + `tcpProcessFrto` 基于 `!before(sndUna, frtoHighmark)` 判定 + `tcpUndoCwndReduction(true)` 清 `lostOut` + `TcpAck` 的 FULLUNDO → LOSSUNDO → **SPURIOUSRTOS** → DSACKUNDO else-if 四路链 + `TCPSPURIOUSRTOS` MIB),与 F4-2 TSECR 通道互补,覆盖无时戳场景下的伪 RTO 回滚。此前在 F8-S3 基础上已追加 **F8-S4 RACK `reo_wnd` 上限对齐** 与 **S1-prune OFO 分轮裁剪 + collapse**。Phase 4 残余项见 `gap-phase4.md`。

---

## 状态速览

| 级别        | 残余数 | 项目                                   |
|-------------|--------|----------------------------------------|
| ⛔ 阻塞     |   0    | —                                      |
| ⚠️ 降级     |   0    | —                                      |
| 🔧 基础设施 |   0    | —                                      |
| ℹ️ 路径差异 |   0    | —                                      |
| ✅ 已对齐   |  38    | 见文末"已对齐清单"                     |

> v2 架构前提(不构成差异):SYN / SYN-ACK 由 `TcpHandshaker` 独立管理,不进入
> `TcpSendBuffer` RTX 队列;因此 Linux `__tcp_retransmit_skb` 中
> `before(seq, snd_una) && skb->syn → TCPHDR_SYN 清位 + seq++` 分支对应的竞态
> 在 v2 不可达,不需要也不应该在 `tcp_retransmit_skb` 里保留防御性代码。

## 推进顺序

(无待办项 — 全部对齐完成。)

---

## ✅ 已对齐清单

| 批次   | Gap 项                              | 关键落点                                              |
|--------|-------------------------------------|-------------------------------------------------------|
| B1–B6  | 握手路径                            | `tcp_conn_request / tcp_v4_syn_recv_sock / tcp_create_openreq_child / tcp_rcv_synsent_state_process` 已对齐 |
| C5     | OFO FIN + 内存预算                  | `tcp_data_queue_ofo / tcp_ofo_queue / tcp_prune_ofo_queue`,含 FIN 元数据、`rmem_alloc`、256KB 尾部剪枝 |
| C6     | `ts_recent_stamp` 24 天陈旧分支     | `TcpSock/TcpConnection.tsRecentStamp` + `pawsRejected` 陈旧覆盖(批次 7)                                |
| C7     | SYN-ACK RTT 采样                    | `TcpHandshaker.synAckSentUs` + `tcp_check_req` 调用 `tcp_synack_rtt_meas`(批次 6)                     |
| D1     | `__tcp_select_window` 核心算法      | `TcpSock.rcvBuf/windowClamp/rcvSsthresh` + `tcp_space/tcp_full_space` + 零窗阈值 / rounddown / no-shrink(批次 10) |
| D1-rmem | `sk_rmem_alloc` OFO + in-order       | `TcpReceiveBuffer` 拆分 `ofoBytes / inorderBytes`,`rmemAlloc()` 合并返回,外部 `IntConsumer` 反馈 `tcp_memory_allocated`;`releaseAll` 回收整个 delta |
| D1-pressure | `tcp_under_memory_pressure`      | `SysctlOptions.tcp_memory_allocated` (AtomicLong) + `ipv4_sysctl_tcp_mem_pressure` + `tcp_mem_delta` 钩子;`selectAdvertisedWindow` 压力下把 `rcv_ssthresh` clamp 到 `4*advmss`,对齐 Linux `tcp_clamp_window` |
| D1-win  | `tcp_win_from_space` scaling_ratio  | `TcpOutput.tcp_win_from_space` 定点换算(`TCP_DEFAULT_SCALING_RATIO=205`,`TCP_RMEM_TO_WIN_SCALE_SHIFT=8`);`tcp_full_space / tcp_space` 都过此函数转换 |
| D2     | 动态 PMTU 感知                      | `TcpSock.mssCache/pmtuCookie/tcpHeaderLen/dstMtu` + `tcp_current_mss` 按 `dstMtu ≠ pmtuCookie` 触发 `tcp_sync_mss`;`tcp_established_options` 长度 delta 通过 `tcpHeaderLen` 扣减 |
| D3-a   | RTX 队列 `tcp_fragment`             | `TcpOutput.tcp_rtx_fragment` + `tcp_retransmit_skb` 按 MSS 边界切头段;`TcpSendBuffer.enqueueRtxFirst/pollRtx` |
| D3-b   | SACK 元数据位集                     | `TcpSegmentEntry.sacked` + `TCPCB_SACKED_ACKED/RETRANS/LOST/EVER_RETRANS`;`markRetransmitted()` 已接入 RTX 路径 |
| D3-c   | SACK 入站主循环(完全覆盖)         | `TcpAck.tcp_sacktag_write_queue` + `TcpOptionCodec.parseSackBlocks` + `TcpSendBuffer.rtxView`;`FLAG_DATA_SACKED` 位 + `sock.sackedOut` 计数;`tcp_clean_rtx_queue` 释放时同步递减 |
| D3-d   | RTX 任意边界 `tcp_fragment`         | `TcpSendBuffer.splitRtx(target, splitSeq)`:O(n) 原地重建队列,头/尾段共享 `retainedSlice` 视图,仅尾段继承 FIN/PSH;继承 `sacked / sentTimeUs`,对齐 `tcp_fragment(TCP_FRAG_IN_RTX_QUEUE)` |
| D3-e   | SACK 局部重叠切段记账               | `TcpAck.carveToBlock` 两次 `splitRtx`(左/右边界),与 SACK 块交集完全匹配后再 tag;对齐 `tcp_match_skb_to_sack / tcp_sacktag_walk` |
| D3-f   | RACK scoreboard + tcp_mark_head_lost | `TcpSock.rackMstamp / rackRttUs / lostOut` + `TcpAck.tcp_rack_detect_loss`(基于 `sentTimeUs < rackMstamp - reo_wnd` 标 `TCPCB_LOST`)+ `tcp_mark_head_lost`(NewReno 兜底);`tcp_retransmit_skb` 优先选 LOST 段;`tcp_clean_rtx_queue` 释放 LOST 段同步递减 `lostOut` |
| D3     | `retrans_stamp` + `undo_retrans`    | `TcpSock.retransStamp/undoRetrans` + `tcp_retransmit_skb` 打戳 + `tcp_clean_rtx_queue` 归零(批次 8)      |
| E3     | TW bucket + TimewaitSock mini-sock  | `TcpMultiplexer.timewaitRegistry`(独立 TW bucket)+ `TcpTimewaitSock extends SockCommon`(tw_rcv_nxt/tw_snd_nxt/tw_rcv_wnd/tw_rcv_wscale/PAWS 快照);`tcp_time_wait` 构建 twsk 并立即销毁重量级 `TcpSock`;`__inet_lookup_skb` ESTABLISHED→TW 回退;`tcp_timewait_state_process` 处理 RST / 迟到 FIN;`TcpOutput.tcp_timewait_send_ack` 无状态重放 ACK;`inet_twsk_kill` / `inet_twsk_reschedule` 维护 2MSL |
| E3-PAWS | TW PAWS 校验                        | `TcpTimewaitSock.pawsRejected / updateTsRecent` + `tcp_timewait_state_process` 五阶段主循环(timestamps 解析 → TW_SYN 复用 → RST → PAWS reject 重放 ACK + `PAWSESTABREJECTED` MIB → 迟到 FIN/data replay ACK);对齐 `tcp_paws_reject` + `tcp_store_ts_recent` |
| E3-TW_SYN | TW bucket 被动 SYN 复用          | `tcp_timewait_state_process` 在 SYN && !RST && !ACK 且 (seq 推进 ‖ ts 推进) 时 `inet_twsk_kill(tw)` + 递归 `tcp_v4_rcv(net, pkt)` 让 LISTEN 路径接手;对齐 `TCP_TW_SYN` |
| E3-FW2 | FIN_WAIT_2 子状态托管               | `TcpTimewaitSock.tw_substate ∈ {FIN_WAIT_2, TIME_WAIT}` + `tcp_time_wait(sk, state, tmo)` 写入子状态;`tcp_timewait_state_process` 按 `tw_substate` 分派:FIN_WAIT_2 + 期望 FIN → 推进 `tw_rcv_nxt` + 迁入 TIME_WAIT + `inet_twsk_reschedule(2MSL)`,FIN_WAIT_2 + data → 重放 ACK 不迁移,RST → kill;FIN_WAIT_1 → FIN_WAIT_2 分支当段 piggyback FIN 时改用 keepalive 延迟 sink,对齐 Linux `else if (th->fin) reset_keepalive_timer` |
| E1     | MIB / `SKB_DROP_REASON_*` 骨架      | `TcpMib` 枚举(18 条 `LINUX_MIB_*` / `TCP_MIB_*` 对齐项)+ `TcpMibStats`(`AtomicLongArray` 双计数器家族 + snapshot)+ 主路径投递:PAWSESTABREJECTED / TCPSYNCHALLENGE / TCPCHALLENGEACK / OUTOFWINDOWICMPS / TCPTIMEWAITCREATED / TCPRETRANSSEGS + PreValidator / AckHandler / TcpOutput drop_reason 落点 |
| E1     | `linger2 < 0 → tcp_done` 主路径     | FIN_WAIT_1 → FIN_WAIT_2 路径 + `onFinWait2Keepalive`(批次 5)                                            |
| E2     | `tcp_fin` 全状态 switch 合并        | `TcpMultiplexer.tcp_fin(ctx, sk)` 单点覆盖 SYN_RECV/ESTABLISHED/CLOSE_WAIT/CLOSING/LAST_ACK/FIN_WAIT_1/FIN_WAIT_2;删除 `Tcp4Multiplexer.tcp_fin_state_process`,FIN+data 统一走 `tcp_data_queue → queue_and_out → tcp_fin`,FIN_WAIT_1 → CLOSING、FIN_WAIT_2 → TIME_WAIT 由全状态 switch 驱动;对齐 Linux `tcp_fin`(tcp_input.c:4318) |
| E2-SR  | SYN_RECV + FIN 与 Linux 同构分发    | `tcp_rcv_state_process` SYN_RECV → `tcp_try_establish`(state=ESTABLISHED)→ `tcp_data_queue` → `queue_and_out` → `tcp_fin`(ESTABLISHED 分支)→ CLOSE_WAIT;与 Linux 先 `tcp_set_state(ESTABLISHED)` 再 fall-through 到末尾 `tcp_data_queue` 的路径完全一致,`tcp_fin` switch 的 SYN_RECV/ESTABLISHED 合并 case 在两侧均作为正常命中点 |
| F1.0   | 出站 SACK 框架                      | `TcpOptionCodec.writeSackOption`(NOP + NOP + Kind=5 + Length + n×8B)+ `TcpOutput.tcp_build_ack_options` 组合 TSopt / DSACK / OFO 衍生 SACK 块;数据段(`tcp_established_options`)保持 TSopt-only 不受影响;`TcpReceiveBuffer.computeSackBlocks` O(n) 合并相邻 OFO 段 |
| F1.1   | 接收端 DSACK Case 1(RFC 2883)     | `TcpReceiveBuffer.offer` 在 `!after(endSeq, rcvNxt)` 分支回带 `[dsackStart, dsackEnd]`;`TcpSock.dsackStart/dsackEnd` one-shot 状态 + `setDsack / consumeDsack`;`queue_and_out` 命中 DSACK 时 `enterQuickAckMode` + `ACK_NOW`;`tcp_build_ack_options` 将 DSACK 置于 SACK options 首位,对齐 Linux `tcp_dsack_set` + `tcp_options_write` SACK 分支 |
| F1c    | 接收端 DSACK Case 2(跨边界)       | `TcpReceiveBuffer.offer` 的 `seq <= rcvNxt < endSeq` 分支在 `skip > 0` 时回带 `dsackStart=seq, dsackEnd=rcvNxt`;与 F1.1 共享 `OfferResult → TcpSock.setDsack → tcp_build_ack_options` 路径,对齐 Linux `tcp_dsack_extend` Case 2 |
| F1d    | 发送端 DSACK 识别(`tcp_check_dsack`)| `TcpAck.tcp_sacktag_write_queue` 入口判定首块是否为 DSACK(Case 1/2:`end <= priorSndUna`;Case 3:首块被第二块包含),命中 `TCPDSACKRECV++` 并令 `sackStartIdx = 2` 把首块从 `carveToBlock / TCPCB_SACKED_ACKED` 记账中剔除 |
| F4-0   | Undo prior 快照基础设施             | `TcpSock.priorCwnd / priorSsthresh / undoMarker` one-shot 字段 + `tcpInitUndo()` / `tcpUndoCwndReduction(boolean unmarkLoss)`;`onAckedByCc` Recovery 入口 + `onTimeoutByCc` Loss 入口各调一次 `tcpInitUndo`,对齐 Linux `tp->prior_cwnd / prior_ssthresh / undo_marker` + `tcp_init_undo` |
| F4-1   | `tcp_try_undo_recovery`(TSECR)    | `TcpSock.tcpTryUndoRecovery(tsecr)`:CA_Recovery + `undoMarker/retransStamp` 有效 + `TcpSequence.before(tsecr, retransStamp/1000)` 命中 → 回滚 `cwnd/ssthresh` 并迁 CA_Open(跳过自然 deflate);`TcpAck.tcpAck` 在 `tcp_clean_rtx_queue / tcpProcessTlpAck` 之后调用,命中 `TCPFULLUNDO++`;对齐 Linux `tcp_fastretrans_alert` 中的 `tcp_try_undo_recovery` + `tcp_packet_delayed`(仅 TSECR 分支,不走 `!undo_retrans` 兜底) |
| F1b    | 接收端 DSACK Case 3(OFO 重叠)    | `TcpReceiveBuffer.offerOfo` 返回 `OfoResult{queued, dsackStart, dsackEnd}`:predecessor 覆盖 → DSACK `[origSeq, min(origEnd, prev.end))`,successor 完全被新段覆盖 → DSACK `[succ.start, succ.end)`,successor 头部重叠 → DSACK `[succ.start, endSeq)`(last-wins);`TcpMultiplexer.tcp_data_queue_ofo` 有 DSACK 时 `sk.setDsack(...)` + `enterQuickAckMode` + `ACK_NOW`;对齐 Linux `tcp_data_queue_ofo → tcp_dsack_set`(RFC 2883 Case 3) |
| F8     | RACK `reo_wnd` 动态放宽            | `TcpSock.rackReoWndSteps / rackReoWndPersist / rackDsackSeen` + `tcpRackUpdateReoWnd()`;`tcp_sacktag_write_queue` 命中 DSACK 首块时 `setRackDsackSeen(true)`;`tcpAck` 末尾调 update — DSACK 下 `steps++`(封顶 0xFF)+ `persist=16`,否则 `persist--`,到 0 衰减回 1;`tcp_rack_detect_loss` 的 reo_wnd 改为 `min(srtt/4 × steps, 2×srtt)`,对齐 Linux `tcp_rack_update_reo_wnd` + `tp->rack.reo_wnd_steps/persist/dsack_seen`(TCP_RACK_RECOVERY_THRESH=16) |
| F6     | RFC 3042 Limited Transmit          | `TcpSock.dupacks()` + `isCaOpen()` getter;`TcpOutput.tcp_cwnd_test` 在 CA_Open + `dupacks ∈ [1,2]` 时把 dupacks 当作 `+bonus` 并入配额(等价 Linux NewReno `tcp_add_reno_sack` 缩小 in_flight);真正用到 bonus 时计 `TCPLIMITEDTRANSMIT` MIB,对齐 Linux `tcp_fastretrans_alert` Limited Transmit 分支(RFC 3042) |
| F9     | `tcp_collapse_retrans`              | `TcpSendBuffer.collapseRtx(head, mssNow)`:序号连续 + 两段合并 ≤ MSS + 两段均未 SYN / 未 SACK_ACKED,才以 `Unpooled.wrappedBuffer` 拼接 `retainedSlice` 合并成单段(继承 flags 并集、`sacked` OR EVER_RETRANS、取较早 `sentTimeUs`);`TcpOutput.tcp_retransmit_skb` 在 avail_wnd fragment 之后、实际重传之前调用,对齐 Linux `tcp_collapse_retrans`(tcp_output.c:2829) |
| F4-2   | `tcp_try_undo_loss`(TSECR)        | `TcpSock.tcpTryUndoLoss(tsecr)`:CA_Loss + `undoMarker/retransStamp` 有效 + `TcpSequence.before(tsecr, retransStamp/1000)` 命中 → 回滚 `cwnd/ssthresh` 到 pre-RTO 快照并迁 CA_Open;`TcpAck.tcpAck` 在 F4-1 未命中时继续尝试,命中记 `TCPLOSSUNDO++`;F4-0 `tcpInitUndo()` 已在 `onTimeoutByCc` 里完成快照,F4-2 为纯消费侧;对齐 Linux `tcp_fastretrans_alert → tcp_try_undo_loss`(仅 TSECR 分支) |
| F4-3   | `tcp_try_undo_dsack`(DSACK)       | `undo_retrans` 语义对齐 Linux:`TcpAck.tcp_clean_rtx_queue` 去掉非对齐的 `decrUndoRetrans(ackedRetrans)` / `retransStamp=0` 兜底;`TcpAck.tcp_sacktag_write_queue` 在 DSACK Case 1/2 按 `tcp_check_dsack` 守卫(`undoMarker != 0 && undoRetrans > 0 && !after(fe, priorSndUna) && after(fe, undoMarker)`)递减;`TcpSock.tcpTryUndoDsack()`(CA_Recovery ‖ CA_Loss + `undoMarker != 0` + `retransStamp != 0L` + `undoRetrans == 0`)→ `tcpUndoCwndReduction(false)` + CA_Open;`TcpAck.tcpAck` 尾部按 FULLUNDO → LOSSUNDO → DSACKUNDO 顺序 else-if,命中记 `TCPDSACKUNDO++`;`tcpInitUndo` 同步重置 `undoRetrans / retransStamp` 避免跨 epoch 残留 |
| F8-S3  | RACK `reo_wnd` 1-RTT 门控           | `TcpSkb.txDelivered` 字段对齐 `TCP_SKB_CB->tx.delivered`,`splitRtx / collapseRtx / tcp_rtx_fragment` 全量继承;`TcpSock.delivered / rackLastDelivered / rackAckPriorDelivered` 对齐 `tp->delivered / rack.last_delivered / rs->prior_delivered` 三件套;`tcp_event_new_data_sent` / `tcp_retransmit_skb` 发送瞬间 `skb.txDelivered = sock.delivered`;`tcp_clean_rtx_queue`(非 SACKED 分支)+ `tcp_sacktag_write_queue`(新 tagged 分支)各 `tp->delivered++` 并以 `skb.txDelivered` 单调刷新本 ACK 的 `prior_delivered`;`tcpAck` 入口 `clearRackAckPriorDelivered()`,末尾 `tcpRackUpdateReoWnd(priorDelivered)`:`before(priorDelivered, rackLastDelivered)` 时清 `dsack_seen`(同一 RTT 内的旧 DSACK 忽略),步进点 `rackLastDelivered = delivered`,对齐 Linux `tcp_rack_update_reo_wnd` "Disregard DSACK if a rtt has not passed" 语义(tcp_recovery.c) |
| F8-S4  | RACK `reo_wnd` 上限公式             | `internal.WinMinMax`(Kathleen Nichols 3 样本时间窗,对齐 Linux `struct minmax` / `lib/win_minmax.c`)+ `TcpSock.rttMinFilter / minRttUs()`;`addRttSample` 每次采样喂入 `(tcp_jiffies32, rttUs)`,窗长 `TCP_MIN_RTT_WIN_SEC × 1000` 毫秒;`TcpAck.tcp_rack_detect_loss` 的 reo_wnd 改为 `min((min_rtt × steps) >> 2, srtt >> 3)`,冷启动(无采样)退化到 `srtt/4 × steps` + `srtt >> 3` cap;新增常量 `TCP_MIN_RTT_WIN_SEC = 300` + `TCP_MIN_RTT_NO_SAMPLE = Integer.MAX_VALUE`(对齐 Linux `~0U` sentinel),对齐 Linux `tcp_rack_reo_wnd` + `tp->rtt_min` 窗口最小滤波器 |
| S1-prune | OFO prune 分轮 + collapse          | `TcpReceiveBuffer.tcpPruneOfoQueue` 先 `collapseOfoQueue`(邻接段按 `prev.endSeq == next.startSeq` 合并为单段,封顶 64 KiB,带 FIN 段不合并)+ 若仍超 `OFO_MAX_BYTES` 从队尾(高 seq)逐条丢弃直至回到预算(原 "tail-drop 队列一半" 语义改为 "恰好回到预算");`TCPPRUNECALLED / TCPOFOMERGE / TCPRCVCOLLAPSED / TCPOFODROP` MIB 对齐 Linux `tcp_prune_queue / tcp_collapse_ofo_queue / tcp_prune_ofo_queue` |
| F11    | F-RTO 伪 RTO 检测(RFC 5682)       | `TcpSock.frtoHighmark`(对齐 Linux `tp->high_seq` 的 F-RTO 快照)+ `frtoCounter`(对齐 `tp->frto`)+ `clearFrto()`;`onTimeoutByCc` 在 `undoMarker != 0 && after(sndNxt, undoMarker)` 守卫下武装 `frtoHighmark = sndNxt; frtoCounter = 1`,否则 `clearFrto()`;`tcpProcessFrto()`:CA_Loss + `frtoCounter == 1` + `undoMarker != 0` + `!before(sndUna, frtoHighmark)` → `tcpUndoCwndReduction(true)`(unmarkLoss 清 `lostOut`)+ 迁 CA_Open;`tcpUndoCwndReduction` 追加 `clearFrto()`,`onAckedByCc` 自然退出 CA_Loss 分支同样 `clearFrto()` 避免跨 epoch 残留;`TcpAck.tcpAck` 的 undo else-if 链扩展为 FULLUNDO → LOSSUNDO → **SPURIOUSRTOS** → DSACKUNDO 四路,F-RTO 排在 TSECR 之后作为无时戳场景兜底;`TcpMib.TCPSPURIOUSRTOS` 对齐 `LINUX_MIB_TCPSPURIOUSRTOS`;对齐 Linux `tcp_enter_loss` F-RTO 武装 + `tcp_process_loss` 的 `!before(snd_una, high_seq)` 触发路径(tcp_input.c) |

---

## 与 v1 文件行号关联索引

(所有 Gap 项均已对齐 — 无待补齐索引条目。)
