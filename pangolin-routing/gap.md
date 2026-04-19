# v2 TCP 与 v1 / Linux 对齐状态

跟踪 v2 TCP 实现(`com.github.pangolin.routing.acceptor.tun.net.v2.tcp`)相对 Linux
内核 `net/ipv4/tcp_*.c` 的对齐进度。每条残余 gap 按 **当前状态 / 残余 / 影响 / 建议**
四要素展开。

> 最近一次更新:2026-04-19 — Phase 3 批次 6 / 7 / 8 / 10 + D3-a / D3-b + D2 动态 PMTU + D3 SACK tagging 入站主循环 + E3 TIME_WAIT bucket + TcpTimewaitSock mini-sock + E1 MIB / drop-reason 统计骨架 + E3 PAWS / TW_SYN 复用 + D3 任意边界 tcp_fragment + SACK 局部重叠切段记账 + RACK scoreboard + tcp_mark_head_lost + D1 残余 rmem_alloc + tcp_under_memory_pressure + tcp_win_from_space scaling_ratio + E2 tcp_fin 全状态 switch + **E3 FIN_WAIT_2 子状态(tw_substate)** 已合入。

---

## 状态速览

| 级别        | 残余数 | 项目                                   |
|-------------|--------|----------------------------------------|
| ⛔ 阻塞     |   0    | —                                      |
| ⚠️ 降级     |   0    | —                                      |
| 🔧 基础设施 |   0    | —                                      |
| ℹ️ 路径差异 |   2    | E2 SYN_RECV FIN、D3 SYN fix-up(架构差异,不计 gap) |
| ✅ 已对齐   |  22    | 见文末"已对齐清单"                     |

## 推进顺序

(无待办项 — 全部对齐完成。)

---

## 路径差异(行为等价,不计 gap)

### E2 — SYN_RECV 阶段 FIN 的分发点

- **Linux**:`tcp_input.c:4318` `tcp_fin` switch 覆盖 SYN_RECV / ESTABLISHED / CLOSE_WAIT /
  CLOSING / LAST_ACK / FIN_WAIT_1 / FIN_WAIT_2 全部状态。
- **v2**:`TcpMultiplexer.tcp_fin` 只处理 ESTABLISHED → CLOSE_WAIT;其余状态由
  `Tcp4Multiplexer.tcp_fin_state_process` 与各调用点就地处理。SYN_RECV 阶段 FIN 由
  `tcp_rcv_state_process` 的其它分支吸收。
- **结论**:行为与 Linux 等价,只是路径不同,无需补齐。

### D3 — SYN fix-up 分支

- **Linux**:`__tcp_retransmit_skb` 中 `before(seq, snd_una) && skb->syn` → 清 SYN + `seq++`。
- **v2**:SYN 由 `TcpHandshaker` 管理,**不落入数据 RTX 队列**,此分支在 v2 架构下永不触发。
- **结论**:架构差异,无需补齐。

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
| D3-c   | SACK 入站主循环(完全覆盖)         | `TcpIncomingAckHandler.tcp_sacktag_write_queue` + `TcpOptionCodec.parseSackBlocks` + `TcpSendBuffer.rtxView`;`FLAG_DATA_SACKED` 位 + `sock.sackedOut` 计数;`tcp_clean_rtx_queue` 释放时同步递减 |
| D3-d   | RTX 任意边界 `tcp_fragment`         | `TcpSendBuffer.splitRtx(target, splitSeq)`:O(n) 原地重建队列,头/尾段共享 `retainedSlice` 视图,仅尾段继承 FIN/PSH;继承 `sacked / sentTimeUs`,对齐 `tcp_fragment(TCP_FRAG_IN_RTX_QUEUE)` |
| D3-e   | SACK 局部重叠切段记账               | `TcpIncomingAckHandler.carveToBlock` 两次 `splitRtx`(左/右边界),与 SACK 块交集完全匹配后再 tag;对齐 `tcp_match_skb_to_sack / tcp_sacktag_walk` |
| D3-f   | RACK scoreboard + tcp_mark_head_lost | `TcpSock.rackMstamp / rackRttUs / lostOut` + `TcpIncomingAckHandler.tcp_rack_detect_loss`(基于 `sentTimeUs < rackMstamp - reo_wnd` 标 `TCPCB_LOST`)+ `tcp_mark_head_lost`(NewReno 兜底);`tcp_retransmit_skb` 优先选 LOST 段;`tcp_clean_rtx_queue` 释放 LOST 段同步递减 `lostOut` |
| D3     | `retrans_stamp` + `undo_retrans`    | `TcpSock.retransStamp/undoRetrans` + `tcp_retransmit_skb` 打戳 + `tcp_clean_rtx_queue` 归零(批次 8)      |
| E3     | TW bucket + TimewaitSock mini-sock  | `TcpMultiplexer.timewaitRegistry`(独立 TW bucket)+ `TcpTimewaitSock extends SockCommon`(tw_rcv_nxt/tw_snd_nxt/tw_rcv_wnd/tw_rcv_wscale/PAWS 快照);`tcp_time_wait` 构建 twsk 并立即销毁重量级 `TcpSock`;`__inet_lookup_skb` ESTABLISHED→TW 回退;`tcp_timewait_state_process` 处理 RST / 迟到 FIN;`TcpOutput.tcp_timewait_send_ack` 无状态重放 ACK;`inet_twsk_kill` / `inet_twsk_reschedule` 维护 2MSL |
| E3-PAWS | TW PAWS 校验                        | `TcpTimewaitSock.pawsRejected / updateTsRecent` + `tcp_timewait_state_process` 五阶段主循环(timestamps 解析 → TW_SYN 复用 → RST → PAWS reject 重放 ACK + `PAWSESTABREJECTED` MIB → 迟到 FIN/data replay ACK);对齐 `tcp_paws_reject` + `tcp_store_ts_recent` |
| E3-TW_SYN | TW bucket 被动 SYN 复用          | `tcp_timewait_state_process` 在 SYN && !RST && !ACK 且 (seq 推进 ‖ ts 推进) 时 `inet_twsk_kill(tw)` + 递归 `tcp_v4_rcv(net, pkt)` 让 LISTEN 路径接手;对齐 `TCP_TW_SYN` |
| E3-FW2 | FIN_WAIT_2 子状态托管               | `TcpTimewaitSock.tw_substate ∈ {FIN_WAIT_2, TIME_WAIT}` + `tcp_time_wait(sk, state, tmo)` 写入子状态;`tcp_timewait_state_process` 按 `tw_substate` 分派:FIN_WAIT_2 + 期望 FIN → 推进 `tw_rcv_nxt` + 迁入 TIME_WAIT + `inet_twsk_reschedule(2MSL)`,FIN_WAIT_2 + data → 重放 ACK 不迁移,RST → kill;FIN_WAIT_1 → FIN_WAIT_2 分支当段 piggyback FIN 时改用 keepalive 延迟 sink,对齐 Linux `else if (th->fin) reset_keepalive_timer` |
| E1     | MIB / `SKB_DROP_REASON_*` 骨架      | `TcpMib` 枚举(18 条 `LINUX_MIB_*` / `TCP_MIB_*` 对齐项)+ `TcpMibStats`(`AtomicLongArray` 双计数器家族 + snapshot)+ 主路径投递:PAWSESTABREJECTED / TCPSYNCHALLENGE / TCPCHALLENGEACK / OUTOFWINDOWICMPS / TCPTIMEWAITCREATED / TCPRETRANSSEGS + PreValidator / AckHandler / TcpOutput drop_reason 落点 |
| E1     | `linger2 < 0 → tcp_done` 主路径     | FIN_WAIT_1 → FIN_WAIT_2 路径 + `onFinWait2Keepalive`(批次 5)                                            |
| E2     | `tcp_fin` 全状态 switch 合并        | `TcpMultiplexer.tcp_fin(ctx, sk)` 单点覆盖 SYN_RECV/ESTABLISHED/CLOSE_WAIT/CLOSING/LAST_ACK/FIN_WAIT_1/FIN_WAIT_2;删除 `Tcp4Multiplexer.tcp_fin_state_process`,FIN+data 统一走 `tcp_data_queue → queue_and_out → tcp_fin`,FIN_WAIT_1 → CLOSING、FIN_WAIT_2 → TIME_WAIT 由全状态 switch 驱动;对齐 Linux `tcp_fin`(tcp_input.c:4318) |

---

## 与 v1 文件行号关联索引

(所有 Gap 项均已对齐 — 无待补齐索引条目。)
