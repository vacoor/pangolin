# v2 TCP 实现 ↔ Linux 内核 对齐差异表(2026-04-20)

本文按 **模块 / 功能 / RFC 规范 / Linux 对齐点 / v2 落点 / 对齐状态 / 备注** 逐项
枚举 `com.github.pangolin.routing.acceptor.tun.net.v2.tcp` 相对 Linux
`net/ipv4/tcp_*.c` 主干的落地现状,作为 `gap.md` / `tcp-gap-phase4.md` 的代码级索引。

> 编写口径:
> - **模块**分为 *核心*(协议正确性 / 基本吞吐 / 握手 / 状态机 / ACK / 重传 /
>   SACK / RACK / 拥塞控制 / 窗口 / RTT / RTO / TIME_WAIT)与 *非核心*(可选特性:
>   ECN / F-RTO / PRR / MTU probing / ABC / MIB / 定时器基础设施等)。
> - **对齐状态**:✅ = 语义一致(允许工程化简化);🟡 = 部分对齐(骨架到位但
>   有细节简化,文末"简化清单"有逐项说明);🔴 = 未实现或仅 stub。
> - **Linux 对齐点**引用 `net/ipv4/tcp_input.c` / `tcp_output.c` /
>   `tcp_recovery.c` / `tcp_timer.c` / `tcp_minisocks.c` 等主干文件;RFC 若多份
>   相关则列主要者。

---

## 一、核心模块

### 1. 入站主循环(`tcp_v4_rcv` / `tcp_rcv_state_process`)

| 功能 | RFC | Linux 对齐点 | v2 落点 | 状态 |
|------|-----|--------------|---------|------|
| IP→4 元组路由 + `__inet_lookup_skb` | 9293 | `tcp_v4_rcv` | `Tcp4Multiplexer.tcp_v4_rcv` + `TcpMultiplexer.__inet_lookup_skb` | ✅ |
| 段合法性预校验 / 校验和骨架 | 9293 §3.5 | `tcp_v4_rcv` 前置 | `TcpIncomingPreValidator` | ✅ |
| 状态机分派 | 9293 §3.10 | `tcp_rcv_state_process` | `TcpMultiplexer.tcp_rcv_state_process` | ✅ |
| `tcp_validate_incoming` 窗序校验 | 9293 §3.10.7.4 | `tcp_validate_incoming` | `TcpMultiplexer.tcp_validate_incoming` | ✅ |
| Challenge ACK(Blind-RST / SYN)  | 5961 | `tcp_send_challenge_ack` | `TcpOutput.tcp_send_challenge_ack` + `TCPCHALLENGEACK` / `TCPSYNCHALLENGE` MIB;per-sock 半秒桶 + host 级 `challengeAckHostAllow` 全局桶(1000/s,对齐 `sysctl_tcp_challenge_ack_limit`) | ✅ |
| `tcp_oow_rate_limited` out-of-window 抑制 | 5961 | `tcp_oow_rate_limited` | `TcpMultiplexer.__tcp_oow_rate_limited / tcp_oow_rate_limited` | ✅ |

### 2. 三次握手(LISTEN / SYN_SENT / SYN_RECV)

| 功能 | RFC | Linux 对齐点 | v2 落点 | 状态 |
|------|-----|--------------|---------|------|
| 被动 LISTEN → SYN_RECV | 9293 §3.10.7.2 | `tcp_v4_conn_request` + `tcp_conn_request` | `TcpMultiplexer.tcp_v4_conn_request` + `TcpHandshaker` | ✅ |
| `tcp_check_req` 半连接匹配 | 9293 | `tcp_check_req` | `TcpMultiplexer.tcp_check_req` | ✅ |
| `tcp_v4_syn_recv_sock / tcp_create_openreq_child` | 9293 | `tcp_v4_syn_recv_sock` | `TcpMultiplexer` 子 sock 路径 | ✅ |
| `tcp_openreq_init` 请求 sock 初值 | 9293 | `tcp_openreq_init` | `TcpMultiplexer.tcp_openreq_init` | ✅ |
| SYN-ACK RTT 采样 | 6298 | `tcp_synack_rtt_meas` | `TcpMultiplexer.tcp_synack_rtt_meas` + `TcpHandshaker.synAckSentUs` | ✅ |
| SYN cookie / 队列满保护 | 4987 | `tcp_syn_flood_action` | 未实现(无 SYN cookie) | 🔴 |

### 3. 连接状态机与关闭(`tcp_fin` / TIME_WAIT / FIN_WAIT_2)

| 功能 | RFC | Linux 对齐点 | v2 落点 | 状态 |
|------|-----|--------------|---------|------|
| `tcp_fin` 全状态分派 | 9293 §3.10.6 | `tcp_fin`(tcp_input.c:4318) | `TcpMultiplexer.tcp_fin` | ✅ |
| `tcp_done` / `tcp_done_with_error` | 9293 | `tcp_done` | `TcpMultiplexer.tcp_done / tcp_done_with_error` | ✅ |
| `tcp_shutdown` 半关闭 | 9293 | `tcp_shutdown` | `TcpMultiplexer.tcp_shutdown` | ✅ |
| `tcp_close_state` 迁移表 | 9293 | `tcp_close_state` | `TcpMultiplexer.tcp_close_state` + `NEW_STATE[]` | ✅ |
| `tcp_time_wait` TW bucket mini-sock | 9293 §3.10.6 | `tcp_time_wait / inet_twsk_alloc` | `TcpMultiplexer.tcp_time_wait` + `TcpTimewaitSock` | ✅ |
| `tcp_timewait_state_process`(TW_SYN / PAWS / RST) | 9293 / 7323 | `tcp_timewait_state_process` | `TcpMultiplexer.tcp_timewait_state_process` + `TcpOutput.tcp_timewait_send_ack` | ✅ |
| FIN_WAIT_2 子状态(`tw_substate`) | 9293 | `inet_timewait_sock.tw_substate` | `TcpTimewaitSock.tw_substate` + `tcp_time_wait(state, tmo)` | ✅ |
| `linger2 < 0` 强制 abort | Linux | `tcp_fin_timeout` 路径 | FIN_WAIT_1 / FIN_WAIT_2 keepalive sink + `TCPABORTONLINGER` MIB | ✅ |

### 4. 数据接收与 OFO(`tcp_data_queue`)

| 功能 | RFC | Linux 对齐点 | v2 落点 | 状态 |
|------|-----|--------------|---------|------|
| `queue_and_out` 有序入队 | 9293 | `tcp_data_queue` → `tcp_queue_rcv` | `TcpMultiplexer.tcp_data_queue` + `TcpReceiveBuffer.offer` | ✅ |
| `tcp_data_queue_ofo` OFO 入队 | 9293 | `tcp_data_queue_ofo` | `TcpMultiplexer.tcp_data_queue_ofo` + `TcpReceiveBuffer.offerOfo` | ✅ |
| `tcp_ofo_queue` 有序化晋升 | 9293 | `tcp_ofo_queue` | `TcpReceiveBuffer.drainOfoInto` | ✅ |
| `tcp_prune_ofo_queue` 内存压力裁剪 | — | `tcp_prune_ofo_queue` | `TcpReceiveBuffer.pruneOfoTail` 256KB 尾部剪枝 | 🟡 S-1 |
| FIN 元数据随段保留 | 9293 | OFO FIN bit | `TcpSkb.tcpFlags` + `TcpReceiveBuffer.offer` FIN 识别 | ✅ |
| `sk_rmem_alloc` 内存配额 | — | `sk_rmem_alloc` | `TcpReceiveBuffer.ofoBytes / inorderBytes` + `rmemAlloc()` | ✅ |
| `tcp_under_memory_pressure` 全局压力 | — | `tcp_under_memory_pressure` | `SysctlOptions.tcp_memory_allocated / tcp_mem[]` | ✅ |

### 5. ACK 处理(`tcp_ack`)

| 功能 | RFC | Linux 对齐点 | v2 落点 | 状态 |
|------|-----|--------------|---------|------|
| 主循环 + FLAG_* 语义 | 9293 §3.10.7 | `tcp_ack` | `TcpAck.tcpAck` | ✅ |
| `tcp_clean_rtx_queue` 累计 ACK 清理 | 9293 | `tcp_clean_rtx_queue` | `TcpAck.tcp_clean_rtx_queue` + `TcpSendBuffer.acknowledgeUpTo` | ✅ |
| `tcp_ack_snd_check` / delayed ACK 调度 | 9293 §3.8.6 | `tcp_ack_snd_check` | `TcpOutput.tcp_ack_snd_check / tcp_send_delayed_ack` | ✅ |
| `tcp_data_snd_check` | 9293 | `tcp_data_snd_check` | `TcpOutput.tcp_data_snd_check` | ✅ |
| `tcp_event_ack_sent` / `tcp_event_data_sent` | — | 同名 | `TcpOutput.tcp_event_ack_sent / tcp_event_data_sent` | ✅ |
| `tcp_event_new_data_sent` 段晋升到 RTX 队列 | — | 同名 | `TcpOutput.tcp_event_new_data_sent` | ✅ |
| TLP ACK 处理 | RFC 8985 | `tcp_process_tlp_ack` | `TcpAck.tcpProcessTlpAck` | ✅ |
| Pure-ACK 计数 | — | `LINUX_MIB_TCPPUREACKS` | `TCPPUREACKS` MIB 接入 | ✅ |

### 6. SACK / DSACK / RACK

| 功能 | RFC | Linux 对齐点 | v2 落点 | 状态 |
|------|-----|--------------|---------|------|
| 出站 SACK options 构建 | 2018 | `tcp_options_write / tcp_sack_write` | `TcpOptionCodec.writeSackOption` + `TcpOutput.tcp_build_ack_options` | ✅ |
| 入站 SACK tagging 主循环 | 2018 | `tcp_sacktag_write_queue` | `TcpAck.tcp_sacktag_write_queue` + `TcpSendBuffer.rtxView` | ✅ |
| SACK 局部重叠切段 | 2018 | `tcp_match_skb_to_sack / tcp_sacktag_walk` | `TcpAck.carveToBlock` + `TcpSendBuffer.splitRtx` | ✅ |
| RTX 任意边界 `tcp_fragment` | — | `tcp_fragment(TCP_FRAG_IN_RTX_QUEUE)` | `TcpSendBuffer.splitRtx / TcpOutput.tcp_rtx_fragment` | ✅ |
| 接收端 DSACK Case 1(已被累计 ACK) | 2883 | `tcp_dsack_set` | `TcpReceiveBuffer.offer` + `OfferResult` + `TcpSock.setDsack` | ✅ |
| 接收端 DSACK Case 2(跨边界) | 2883 | `tcp_dsack_extend` | `TcpReceiveBuffer.offer` 跨边界分支 | ✅ |
| 接收端 DSACK Case 3(OFO 重叠) | 2883 | `tcp_data_queue_ofo → tcp_dsack_set` | `TcpReceiveBuffer.offerOfo` + `OfoResult` | ✅ |
| 发送端 DSACK 识别(`tcp_check_dsack`) | 2883 | `tcp_check_dsack`(含 `undo_retrans--` 守卫递减) | `TcpAck.tcp_sacktag_write_queue` 首块 skip + `TCPDSACKRECV` MIB + `decrUndoRetrans(1)`(undoMarker/undoRetrans/fe≤priorSndUna/fe>undoMarker 守卫) | ✅ |
| RACK scoreboard(`tp->rack`) | RACK draft | `tcp_rack_advance / tcp_rack_detect_loss` + `tp->delivered / rs->prior_delivered / rack.last_delivered` 1-RTT 门控 | `TcpSock.rackMstamp / rackRttUs / delivered / rackLastDelivered / rackAckPriorDelivered` + `TcpSkb.txDelivered` + `TcpAck.tcp_rack_detect_loss` + `tcpRackUpdateReoWnd(priorDelivered)` | ✅ |
| RACK `reo_wnd` 动态放宽 | RACK draft | `tcp_rack_update_reo_wnd` + `tcp_rack_reo_wnd` + `tp->rtt_min` Windowed Filter | `TcpSock.rackReoWndSteps / Persist / DsackSeen` + `tcpRackUpdateReoWnd` + `rttMinFilter`(`WinMinMax`)+ `minRttUs()` + `tcp_rack_detect_loss` 的 `min((min_rtt × steps) >> 2, srtt >> 3)` | ✅ |
| `tcp_mark_head_lost`(NewReno 兜底) | 9293 §3.3.2 | `tcp_mark_head_lost` | `TcpAck.tcp_mark_head_lost` | ✅ |

### 7. 重传 / Loss Recovery

| 功能 | RFC | Linux 对齐点 | v2 落点 | 状态 |
|------|-----|--------------|---------|------|
| `__tcp_retransmit_skb` / `tcp_retransmit_skb` 主路径 | 9293 | 同名 | `TcpOutput.tcp_retransmit_skb` | ✅ |
| RTO 定时器 / backoff | 6298 | `tcp_retransmit_timer` | `TcpRetransmitter.onTimeout / backoffRto` | ✅ |
| `tcp_retransmit_skb` 优先 LOST 段 | — | `tcp_xmit_retransmit_queue` LOST 驱动 | `TcpOutput.tcp_retransmit_skb` `sock.lostOut()` 分支 | ✅ |
| `retrans_stamp` / `undo_retrans` 记账 | 9293 | `tp->retrans_stamp / undo_retrans`(重传时自增 + DSACK 观察自减) | `TcpSock.retransStamp / undoRetrans`(`TcpOutput.tcp_retransmit_skb` 自增 + `TcpAck.tcp_sacktag_write_queue` DSACK Case 1/2 自减;`tcpInitUndo` 重置,`tcpUndoCwndReduction` 清零) | ✅ |
| `tcp_collapse_retrans`(相邻小段合并) | — | `tcp_collapse_retrans`(tcp_output.c:2829)+ `tcp_adjust_pcount` | `TcpSendBuffer.collapseRtx → CollapseResult` + `TcpOutput.tcp_retransmit_skb`(按 `droppedSacked` 调整 `packetsOut/lostOut`) | ✅ |
| F4-1 `tcp_try_undo_recovery` (TSECR) | — | `tcp_try_undo_recovery`(TSECR 分支) | `TcpSock.tcpTryUndoRecovery` + `TCPFULLUNDO` MIB | ✅ |
| F4-2 `tcp_try_undo_loss` (TSECR) | — | `tcp_try_undo_loss`(TSECR 分支) | `TcpSock.tcpTryUndoLoss` + `TCPLOSSUNDO` MIB | ✅ |
| F4-3 `tcp_try_undo_dsack` (DSACK) | — | `tcp_try_undo_dsack`(`!tp->undo_retrans` 兜底) | `TcpSock.tcpTryUndoDsack`(CA_Recovery ‖ CA_Loss + undoRetrans==0)+ `TCPDSACKUNDO` MIB;`TcpAck.tcpAck` FULLUNDO → LOSSUNDO → DSACKUNDO else-if 链 | ✅ |
| F4-0 Undo prior 快照 | — | `tcp_init_undo` / `tp->prior_cwnd/ssthresh/undo_marker` | `TcpSock.tcpInitUndo / priorCwnd / priorSsthresh / undoMarker` | ✅ |
| TLP(Tail Loss Probe) | RFC 8985 | `tcp_schedule_loss_probe / tcp_send_loss_probe` | `TcpOutput.tcp_schedule_loss_probe / tcp_send_loss_probe / tcp_tlp_timeout` | ✅ |

### 8. 拥塞控制(NewReno + CC 扩展点)

| 功能 | RFC | Linux 对齐点 | v2 落点 | 状态 |
|------|-----|--------------|---------|------|
| CC plug-in 接口 | 5681 | `struct tcp_congestion_ops` | `ext.cc.CongestionControl` + `NewRenoCongestionControl` | ✅ |
| Fast Retransmit / Recovery 入口 | 5681 / 6582 | `tcp_fastretrans_alert` 入口 | `TcpSock.onAckedByCc` dupacks==3 分支 | ✅ |
| `tcp_cwnd_validate` app-limited 回收 | — | `tcp_cwnd_validate` | `TcpSock.tcpCwndValidate / sndCwndStampMs / sndCwndUsed / isCwndLimited` | ✅ |
| `tcp_slow_start_after_idle_check` | 5681 | 同名 | `TcpSock.tcpSlowStartAfterIdleCheck` | ✅ |
| RFC 3042 Limited Transmit | 3042 | `tcp_fastretrans_alert` LT 分支 | `TcpOutput.tcp_cwnd_test` dupacks bonus + `TCPLIMITEDTRANSMIT` MIB | ✅ |
| ECN 协商 / CE 响应 / CWR | 3168 | `tcp_ecn_*` | 仅 header flag 常量,逻辑未实现 | 🔴 |
| PRR 降窗 | 6937 | `tcp_cwnd_reduction` | 未实现(NewReno 硬降) | 🔴 |
| F-RTO 伪 RTO 检测 | 5682 | `tcp_enter_loss` F-RTO 武装 + `tcp_process_loss` | `TcpSock.frtoHighmark/frtoCounter` + `onTimeoutByCc` 武装 + `tcpProcessFrto` + `TcpAck` 四路 undo else-if 链(SPURIOUSRTOS)+ `TCPSPURIOUSRTOS` MIB | ✅ |
| ABC(Appropriate Byte Counting) | 3465 | `tcp_slow_start` | 未实现(按 ACK 数增 cwnd) | 🔴 |

### 9. 发送主循环(`tcp_write_xmit`)

| 功能 | RFC | Linux 对齐点 | v2 落点 | 状态 |
|------|-----|--------------|---------|------|
| `tcp_write_xmit` 主循环 | 9293 | 同名 | `TcpOutput.tcp_write_xmit` | ✅ |
| `tcp_push_pending_frames` | 9293 | 同名 | `TcpOutput.tcp_push_pending_frames` | ✅ |
| `tcp_send_fin` | 9293 | 同名 | `TcpOutput.tcp_send_fin` | ✅ |
| `tcp_snd_wnd_test` rwnd 门控 | 9293 | 同名 | `TcpOutput.tcp_snd_wnd_test` | ✅ |
| `tcp_cwnd_test` cwnd 门控 + LT bonus | 5681 / 3042 | 同名 | `TcpOutput.tcp_cwnd_test`(in_flight 走 `TcpSock.packetsInFlight()` = `packets_out - sacked_out - lost_out`,对齐 `tcp_packets_in_flight`) | ✅ |
| `tcp_nagle_test` | 896 | 同名 | `TcpOutput.tcp_nagle_test / tcp_minshall_update` | ✅ |
| `tcp_fragment` 写队列切分 | — | `tcp_fragment` | `TcpOutput.tcp_fragment` | ✅ |
| `tcp_small_queue_check` | — | 同名 | `TcpOutput.tcp_small_queue_check` | 🟡 S-7 |
| Pacing(`tcp_pacing_check`) | Linux | 同名 | stub → `return false` | 🔴 |
| TSO / GSO | — | `tcp_tso_segs / tcp_mss_split_point / tcp_tso_should_defer` | stub(`tso_segs = 1`) | 🔴 |
| `tcp_mtu_probe` PLPMTUD | RFC 4821 | 同名 | stub `return -1` | 🔴 |

### 10. 零窗 / persist / keepalive

| 功能 | RFC | Linux 对齐点 | v2 落点 | 状态 |
|------|-----|--------------|---------|------|
| `tcp_send_probe0` 零窗探测 | 9293 | 同名 | `TcpOutput.tcp_send_probe0 / tcp_xmit_probe_skb` | ✅ |
| `tcp_probe_timer` persist 定时器 | 9293 | `tcp_probe_timer` | `TcpMultiplexer.tcp_probe_timer` + `probesOut` | ✅ |
| `tcp_write_wakeup` | 9293 | 同名 | `TcpOutput.tcp_write_wakeup` | ✅ |
| `tcp_keepalive_timer` | 9293 | 同名 | `TcpMultiplexer.tcp_keepalive_timer` + `TCPKEEPALIVE` MIB | ✅ |
| Keepalive 子态(`TCP_KEEPOPEN`) | — | `sk->sk_flags` KEEPOPEN | `TcpSock.keepOpen` flag | ✅ |

### 11. 窗口 / MSS / PMTU

| 功能 | RFC | Linux 对齐点 | v2 落点 | 状态 |
|------|-----|--------------|---------|------|
| `__tcp_select_window` 接收端通告 | 9293 / 7323 | `__tcp_select_window` | `TcpSock.selectAdvertisedWindow` | ✅ |
| `tcp_space / tcp_full_space` | — | 同名 | `TcpSock.tcpSpace / tcpFullSpace` | ✅ |
| `tcp_win_from_space` scaling_ratio | — | `tcp_win_from_space` | `TcpOutput.tcp_win_from_space` 定点换算 | 🟡 S-8 |
| `tcp_receive_window` | 9293 | 同名 | `TcpAck.tcp_receive_window` | ✅ |
| `tcp_bound_to_half_wnd` | — | 同名 | `TcpOutput.tcp_bound_to_half_wnd` | ✅ |
| `tcp_sync_mss / tcp_current_mss` 动态 PMTU | 1191 / 4821 | 同名 | `TcpSock.tcpSyncMss / tcpCurrentMss` + `dstMtu / pmtuCookie` | ✅ |
| `tcp_initialize_rcv_mss` | — | 同名 | `TcpSock.tcpInitializeRcvMss` | ✅ |
| `tcp_established_options` 选项长度 | 7323 | 同名 | `TcpOutput.tcp_established_options / tcp_established_options_len` | ✅ |
| 窗口缩放(`wscale`) | 7323 | `tcp_parse_options` WS 解析 | `TcpHandshaker` WS 协商 + `TcpSock.sndWscale/rcvWscale` | ✅ |

### 12. 时戳 / PAWS / RTT / RTO

| 功能 | RFC | Linux 对齐点 | v2 落点 | 状态 |
|------|-----|--------------|---------|------|
| TSopt 发 / 收 / echo | 7323 | `tcp_parse_options` TS 解析 + `tcp_options_write` | `TcpOptionCodec` + `TcpOutput.tcp_build_ack_options` | ✅ |
| PAWS `tcp_paws_reject` | 7323 §5 | `tcp_paws_reject` | `TcpMultiplexer.tcp_paws_reject` + `TcpSock.tsRecent / tsRecentStamp` | ✅ |
| `ts_recent` 24 天陈旧重置 | 7323 §5.3 | `tcp_paws_reject` | `TcpSock.tsRecentStamp` + `TCP_PAWS_24DAYS_SEC` | ✅ |
| `tcp_store_ts_recent` | 7323 | 同名 | `TcpSock.storeTsRecent` | ✅ |
| RTT 估计(SRTT / mdev) | 6298 | `tcp_rtt_estimator` | `Rfc6298RttEstimator.addSample`(由 `TcpMultiplexer:222` 调用) | ✅ |
| RTO 计算(`RTO = SRTT + 4·mdev`) | 6298 | `tcp_set_rto` | `TcpSock.rtoMs()` 单一入口:base clamp → 逐步 `<<= backoff` + 每步 `RTO_MAX` 截断,对齐 `tcp_set_rto`;`RttEstimator` SPI 仅保留 `addSample / backoff / resetBackoff`,不再内嵌 `rtoMs` 死代码 | ✅ |
| RTO backoff / clamp | 6298 | `tcp_rtx_queue_*` + `inet_csk_reset_xmit_timer` | `TcpRetransmitter.backoffRto / resetRtoBackoff` | ✅ |

### 13. delayed ACK / QuickACK

| 功能 | RFC | Linux 对齐点 | v2 落点 | 状态 |
|------|-----|--------------|---------|------|
| delayed ACK 定时器 | 9293 §3.8.6 | `tcp_delack_timer` | `TcpMultiplexer.tcp_delack_timer` | ✅ |
| QuickACK mode | — | `tcp_incr_quickack / tcp_enter_quickack_mode` | `TcpSock.enterQuickAckMode / quickacks` | ✅ |
| ATO 动态收敛 | — | `tp->rcv_ato` | `TcpSock.rcvAtoMs` | ✅ |
| ACK_SCHED / ACK_TIMER / ACK_NOW 位掩码 | — | `icsk->icsk_ack.pending` | `TcpSock.ackPending` | ✅ |

### 14. TIME_WAIT bucket

| 功能 | RFC | Linux 对齐点 | v2 落点 | 状态 |
|------|-----|--------------|---------|------|
| TW 独立 bucket | 9293 | `inet_timewait_sock` | `TcpMultiplexer.timewaitRegistry` + `TcpTimewaitSock extends SockCommon` | ✅ |
| TW PAWS 校验 | 7323 | `tcp_timewait_state_process` | `TcpTimewaitSock.pawsRejected / updateTsRecent` | ✅ |
| TW_SYN 复用 | 9293 / 1122 | `TCP_TW_SYN` | `tcp_timewait_state_process` SYN 复用 + `inet_twsk_kill` | ✅ |
| 2MSL 定时器 | 9293 | `inet_twsk_reschedule` | `TcpMultiplexer.inet_twsk_schedule` + `TIME_WAIT_MS = 60000` | ✅ |

---

## 二、非核心模块

### 15. MIB / drop-reason

| 功能 | RFC / 参考 | Linux 对齐点 | v2 落点 | 状态 |
|------|-----------|--------------|---------|------|
| `LINUX_MIB_*` 枚举 + AtomicLongArray | — | `include/uapi/linux/snmp.h` | `TcpMib` + `TcpMibStats` | 🟡 S-9 |
| `SKB_DROP_REASON_*` | — | `include/net/dropreason.h` | `SkbDropReasonConstants` + PreValidator / AckHandler / TcpOutput 落点 | 🟡 S-9 |
| snapshot / dump 能力 | — | `nstat` / `netstat -s` | `TcpMibStats.snapshot()` | ✅ |

### 16. 定时器调度

| 功能 | Linux 对齐点 | v2 落点 | 状态 |
|------|--------------|---------|------|
| 统一 TimerWheel | `inet_csk_reset_xmit_timer` 家族 | `TcpTimerScheduler` + `TimerType` 枚举 | ✅ |
| per-sock 定时器生命期 | `sk->sk_timer` | `TcpConnectionTimers` | ✅ |

### 17. ECN / CE 响应

| 功能 | RFC | Linux 对齐点 | v2 落点 | 状态 |
|------|-----|--------------|---------|------|
| 握手 ECN 协商(ECT/CE) | 3168 | `tcp_ecn_init / tcp_syn_ecn` | 仅常量 `TCPHDR_ECE/CWR`,逻辑未实现 | 🔴 |
| CE 响应 CWR | 3168 | `tcp_enter_cwr` | 未实现 | 🔴 |
| AccECN | 8311 | `tcp_accecn_*` | 未实现 | 🔴 |

### 18. F-RTO / PRR / MTU probing / ABC / Pacing / TSO

| 功能 | RFC | Linux 对齐点 | v2 落点 | 状态 |
|------|-----|--------------|---------|------|
| F-RTO 伪 RTO | 5682 | `tcp_enter_loss` F-RTO 武装 + `tcp_process_loss` | `TcpSock.frtoHighmark/frtoCounter` one-shot 武装(对齐 `tp->high_seq` / `tp->frto`)+ `tcpProcessFrto` 基于 `!before(sndUna, frtoHighmark)` 判定 + `tcpUndoCwndReduction(unmarkLoss=true)` 清 `lostOut` + `TcpAck.tcpAck` 四路 undo else-if(SPURIOUSRTOS 排在 TSECR LOSSUNDO 之后、DSACKUNDO 之前)+ `TCPSPURIOUSRTOS` MIB;与 F4-2 TSECR 通道互补(无时戳时兜底) | ✅ |
| PRR 降窗 | 6937 | `tcp_cwnd_reduction` | 未实现(NewReno 直接降) | 🔴 |
| PLPMTUD MTU probing | 4821 | `tcp_mtu_probe` | stub `return -1` | 🔴 |
| ABC slow start | 3465 | `tcp_slow_start` | 未实现(按 ACK 数增) | 🔴 |
| Pacing(fq_codel 协同) | — | `tcp_pacing_check / tp->tcp_wstamp_ns` | stub `return false` | 🔴 |
| TSO / GSO(多段融合) | — | `tcp_tso_segs / tcp_tso_should_defer` | stub(`tso_segs = 1`) | 🔴 |
| Disorder / CWR CA 子态 | 3168 | `tcp_ca_event` / `TCP_CA_Disorder/CWR` | 仅 `OPEN / RECOVERY / LOSS` 三态 | 🔴 |

### 19. Urgent / OOB

| 功能 | RFC | Linux 对齐点 | v2 落点 | 状态 |
|------|-----|--------------|---------|------|
| Urgent 指针 / URG 标志 | 9293 §3.7 | `tcp_urg / tcp_check_urg` | `tcp_urg_mode → return false` stub | 🔴 |

### 20. TCP 选项编解码

| 功能 | RFC | Linux 对齐点 | v2 落点 | 状态 |
|------|-----|--------------|---------|------|
| MSS / WS / SACK Permitted / TS / SACK | 9293 / 7323 / 2018 | `tcp_parse_options / tcp_options_write` | `TcpOptionCodec` + `TcpOutput.tcp_build_ack_options` | ✅ |
| MD5 Signature | 2385 | `tcp_md5_*` | 未实现 | 🔴 |
| TCP AO | 5925 | `tcp_ao_*` | 未实现 | 🔴 |
| Fast Open(cookie) | 7413 | `tcp_fastopen_*` | 未实现 | 🔴 |
| User Timeout option | 5482 | `tcp_user_timeout` | 未实现 | 🔴 |

---

## 三、简化清单(🟡 条目展开)

仅列出**当前仍未消解**的简化项;已消解条目从清单中移除,落点见正文对齐表。

| 编号 | 条目 | 简化点 | 对主干正确性影响 | 未来补齐路径 |
|------|------|--------|------------------|--------------|
| S-7 | `tcp_small_queue_check` | Linux 基于 `sk->sk_pacing_rate` + `tcp_limit_output_bytes` sysctl;v2 以简单段数 guard | 无 pacing 时表现与 Linux 接近;有 pacing 时缺少反压 | 实现 Pacing 时一起 |
| S-8 | `tcp_win_from_space` scaling_ratio | v2 用静态 `TCP_DEFAULT_SCALING_RATIO = 205`;Linux 动态 `tcp_update_scaling_ratio` | 接收侧 buffer 利用率略次于 Linux 自适应 | 增补 `tcp_update_scaling_ratio` |
| S-9 | MIB / drop-reason 覆盖 | 只实装 v2 主路径涉及的计数,`TcpMib` 枚举是 Linux 的子集 | 观测面不完整,不影响协议行为 | 按需追加(枚举末尾扩展) |
---

## 四、未实现(🔴)清单汇总

**核心主干相关**:
- PRR 降窗(F5,外挂批 10 — 已标触发条件归档)
- Disorder / CWR 子态(F7,外挂批 9)
- Pacing / TSO / GSO(与性能相关,保留 stub 接口)
- `tcp_small_queue_check` 的 pacing 联动(S-7 依赖 Pacing)

**可选特性**:
- ECN 协商 / CE 响应 / AccECN(F10,外挂批 9)
- PLPMTUD MTU probing(F12,外挂批 11)
- ABC slow start(F13,外挂批 12)
- Urgent / OOB(主干极少使用,仅 stub)
- MD5 / TCP AO / TCP Fast Open / User Timeout(扩展选项,按需实现)
- SYN cookie(高负载 LISTEN 防护,现有预校验 + TW 已满足单连接场景)

---

## 五、关键数据结构对齐

| Linux 类型 | v2 类型 / 字段 | 状态 |
|-----------|---------------|------|
| `struct sk_buff` + `TCP_SKB_CB(skb)` | `TcpSkb`(write / rtx / OFO 三路复用) | ✅ |
| `struct tcp_sock / inet_connection_sock` | `TcpMultiplexer.TcpSock`(inline 镜像 `TcpConnection`) | ✅ |
| `struct inet_timewait_sock` | `TcpTimewaitSock extends SockCommon` | ✅ |
| `sk->sk_write_queue` | `TcpSendBuffer.writeQueue` | ✅ |
| `sk->tcp_rtx_queue` | `TcpSendBuffer.rtxQueue` | ✅ |
| `tp->out_of_order_queue` | `TcpReceiveBuffer.ofoQueue` | ✅ |
| `sk->sk_receive_queue` | `TcpReceiveBuffer.readBuffer`(合并入 inorder 缓冲) | 🟡 刻意保留(暂不处理)— `CompositeByteBuf` 在 v2 proxy 场景下行为等价于 Linux per-skb 链表,`addComponent(true,…) + discardReadComponents()` 的生命周期已近似 per-skb;丢失的 seq/TSval/sacked 在 inorder 路径无读者(Linux 自身也不回访),MSG_PEEK / splice / tcp_diag 不属 v2 使用域。若未来需要协议层元数据,最低成本做法是并行 `Deque<SkbMeta>` 索引 component,而非重写缓冲 |
| `struct request_sock` | `TcpMultiplexer.tcp_request_sock` | ✅ |
| `TCP_SKB_CB->sacked` 位集 | `TcpSkb.sacked`(TCPCB_SACKED_ACKED / RETRANS / LOST / EVER_RETRANS) | ✅ |
| `tp->rcv_nxt / snd_una / snd_nxt / snd_wnd / rcv_wnd` | `TcpSock` 同名字段 | ✅ |
| `tp->rack.{mstamp, rtt_us, reo_wnd_steps, reo_wnd_persist, dsack_seen, last_delivered}` | `TcpSock.rackMstamp / rackRttUs / rackReoWndSteps / rackReoWndPersist / rackDsackSeen / rackLastDelivered` | ✅ |
| `tp->delivered` + `rate_sample.prior_delivered` + `TCP_SKB_CB->tx.delivered` | `TcpSock.delivered / rackAckPriorDelivered` + `TcpSkb.txDelivered` | ✅ |
| `tp->{prior_cwnd, prior_ssthresh, undo_marker, undo_retrans, retrans_stamp}` | `TcpSock` 同名字段 | ✅ |

---

## 六、MIB 计数对照

v2 当前投递的 `LINUX_MIB_*` 子集(`TcpMib` 枚举定义顺序):

| v2 常量 | Linux 对齐 | 触发点 |
|---------|-----------|--------|
| `PAWSESTABREJECTED` | `LINUX_MIB_PAWSESTABREJECTED` | `tcp_paws_reject` / TW PAWS |
| `TCPCHALLENGEACK` | `LINUX_MIB_TCPCHALLENGEACK` | `tcp_send_challenge_ack` |
| `TCPSYNCHALLENGE` | `LINUX_MIB_TCPSYNCHALLENGE` | 非 LISTEN 收 SYN |
| `OUTOFWINDOWICMPS` | `LINUX_MIB_OUTOFWINDOWICMPS` | 越窗段丢弃 |
| `TCPABORTONDATA` | `LINUX_MIB_TCPABORTONDATA` | 已 shutdown 收数据 |
| `TCPABORTONTIMEOUT` | `LINUX_MIB_TCPABORTONTIMEOUT` | 用户 timeout / linger2 |
| `TCPABORTONLINGER` | `LINUX_MIB_TCPABORTONLINGER` | FIN_WAIT_2 linger2<0 |
| `TCPPUREACKS` | `LINUX_MIB_TCPPUREACKS` | 纯 ACK 段 |
| `TCPOFOQUEUE` | `LINUX_MIB_TCPOFOQUEUE` | OFO 入队 |
| `TCPOFODROP` | `LINUX_MIB_TCPOFODROP` | OFO 预算耗尽 |
| `TCPOFOMERGE` | `LINUX_MIB_TCPOFOMERGE` | OFO 合并 |
| `TCPPRUNECALLED` | `LINUX_MIB_TCPPRUNECALLED` | `tcp_prune_queue` |
| `TCPRCVCOLLAPSED` | `LINUX_MIB_TCPRCVCOLLAPSED` | prune 阶段合并 |
| `TCPKEEPALIVE` | `LINUX_MIB_TCPKEEPALIVE` | keepalive 发送 |
| `TCPRETRANSSEGS` | `LINUX_MIB_TCPRETRANSSEGS` | `__tcp_retransmit_skb` |
| `TCPTIMEWAITCREATED` | `LINUX_MIB_TCPTIMEWAITCREATED` | TW bucket 入账 |
| `TCPTIMEWAITKILLED` | `LINUX_MIB_TCPTIMEWAITKILLED` | TW 提前 kill |
| `TCPTIMEWAITRECYCLED` | `LINUX_MIB_TCPTIMEWAITRECYCLED` | TW_SYN 复用(v2 暂 0) |
| `TCPDSACKRECV` | `LINUX_MIB_TCPDSACKRECV` | `tcp_check_dsack` 首块命中 |
| `TCPDSACKUNDO` | `LINUX_MIB_TCPDSACKUNDO` | `tcp_try_undo_dsack` 命中(`undoRetrans==0` 且在 CA_Recovery/Loss) |
| `TCPFULLUNDO` | `LINUX_MIB_TCPFULLUNDO` | `tcp_try_undo_recovery` 命中 |
| `TCPLOSSUNDO` | `LINUX_MIB_TCPLOSSUNDO` | `tcp_try_undo_loss` 命中 |
| `TCPLIMITEDTRANSMIT` | `LINUX_MIB_TCPLIMITEDTRANSMIT` | Limited Transmit 吃到 bonus |
| `TCPSPURIOUSRTOS` | `LINUX_MIB_TCPSPURIOUSRTOS` | F-RTO `tcpProcessFrto` 命中(CA_Loss 首个 ACK 追平 `frtoHighmark`) |

---

## 七、回审未对齐清单(2026-04-21)

本节为对 `core/` 主路径做代码级二次对比后识别的偏差,与一~六节的对齐表形成互补。每
条均已在源码 + Linux 引用点处逐行复核,排除了 Agent 自动扫描阶段的疑似误报(见本节末
"已核验的误报清单")。

2026-04-21 起,G-6 经 `tcp_check_req` 整体重写后消解:v2 现已完整对齐 Linux
`tcp_check_req` 的 11 步判定序(TS 解析 → PAWS → TSECR → 纯 SYN 重传 + OOW 限流 →
OOW/PAWS 分支 Challenge ACK → `ts_recent` 推进 → `seq==rcv_isn` 截断 SYN → RST/SYN
embryonic_reset → 非 ACK 丢弃 → ACK# 校验 → `syn_recv_sock` + `tcp_synack_rtt_meas`),
本节当前无未消解偏差。

### 已核验的误报清单(Agent 自动扫描阶段曾列出,经源码复核后排除)

| 误报 | 代码反证 |
|------|----------|
| `TcpReceiveBuffer.offerOfo` 的 `seq >= endSeq && retainedFin == true` 产生 `dataLen=-1` | L270 `retainedFin = fin && !before(endSeq, origEndSeq)`;L264 trim trailing 把 endSeq 左移时必有 `endSeq < origEndSeq` → `retainedFin` 必为 `false`。bug 路径不可达。 |
| `TcpOutput.tcp_collapse_retrans` 漏递减 `sackedOut` | `TcpSendBuffer.collapseRtx:279` 前置守卫 `head.isSackAcked() || next.isSackAcked() → return null`,droppedSacked 中不会含 `TCPCB_SACKED_ACKED`。 |
| CLOSING 状态未发 FIN | Linux `tcp_close_state(CLOSING)=0`(无 FIN 操作)— CLOSING 的 FIN 本就由 RTX 定时器负责,`tcp_fin` 不发。 |
| TSECR 转换 `(int)(retransStamp/1000L)` 溢出 | Linux `rcv_tsecr` 本身即 32-bit u32,`TcpSequence.before` 做环形比较 — 截断是 **刻意对齐**,非 BUG。 |
| RACK `tcpRackUpdateReoWnd(priorDelivered==0)` 早返未清 `rackDsackSeen` | Linux 亦跳过 no-delivery 的 ACK(`rs->prior_delivered == 0` 的 ACK 不是 RTT 推进源);早返合理。 |
| `delivered` 用 int 可能翻负 | `TcpSequence.before/after` 为 32-bit 环形比较,翻负后仍然正确(等价 Linux `tp->delivered` u32)。 |
| RACK `reo_wnd_steps` cap `0xFF` 偏离 Linux | Linux `tcp_recovery.c` 同为 `min_t(u32, 0xFF, ...)`;v2 一致。 |

### 本节约束

- 本节只列仍未消解的偏差:历史 G-1~G-4 于 2026-04-20 修复后已移除;G-5 原拟作测试覆盖建议项,因代码路径对齐 Linux、不属于实现偏差,不再列入本节;G-6 于 2026-04-21 通过 `tcp_check_req` 整体重写(PAWS / TSECR / OOW 限流 / PAWSESTABREJECTED / TCPACKSKIPPEDSYNRECV / TSECRREJECTED MIB、`snt_tsval_first/last`、`ts_recent` 推进)消解。
- 对比口径:逐行读 v2 源码 + 按章节查阅 Linux `net/ipv4/tcp_{input,output,recovery,minisocks}.c` master 分支(2026-04-20 抓取)。
- 风险分级口径同文首:高=协议正确性/明显回归,中=特定场景偏差,低=微优化/架构/测试覆盖。
- 未纳入本节:
  - 已在 §四 🔴 清单的 ECN / PRR / F-RTO 外的未实现项(有 `tcp-gap-phase4.md` 触发条件归档);
  - 已在 §三 S-x 标注的工程化简化;
  - Phase E 刚建的 `TcpFeature` / `LossRecovery` SPI 骨架(未接通是**预期**,见 `tcp.java.packages.md` §7 提交 3)。

