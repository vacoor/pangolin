# TCP Timestamp 选项改造方案

> 对标基准：Linux 内核 `net/ipv4/tcp_output.c`, `tcp_input.c`（v6.x）  
> RFC：**RFC 7323**（TCP Extensions for High Performance — Timestamps & PAWS）  
> 分析日期：2026-04-03  
> Linux sysctl 对标：`net.ipv4.tcp_timestamps`（`SysctlOptions.ipv4_sysctl_tcp_timestamps`）

---

## 一、现状

| 能力 | 现状 |
|------|------|
| Timestamp 解析（收包） | ✅ `TcpOptionCodec.parseTimestamp()` 已实现 |
| Timestamp 写入 SYN-ACK | ❌ `tcp_synack_options()` 未调用 `writeTimestampOption()` |
| Timestamp 写入数据/ACK 包 | ❌ `tcp_established_options()` 直接返回 null（TODO 注释）|
| `ts_recent` 更新 | ❌ `tcp_validate_incoming()` 中 `tcp_replace_ts_recent()` 被注释 |
| PAWS 检查 | ❌ `tcp_validate_incoming()` 无 PAWS 逻辑 |
| Timestamp RTT 测量 | ❌ `tcp_clean_rtx_queue()` 中 Timestamp 路径未生效 |

**关键代码定位：**
- `TcpOutput.java:291` — `tcp_synack_options()`，未调用 `writeTimestampOption()`
- `TcpOutput.java:310` — `tcp_established_options()`，返回 null
- `TcpOutput.java:354` — `__tcp_transmit_skb()`，调用 `tcp_established_options()`
- `TcpInput.java:1489` — `tcp_validate_incoming()`，PAWS 检查缺失
- `TcpHandshaker.java:73` — `tmp_opt.tstamp_ok` 已正确赋值，握手层已就绪

---

## 二、改造目标

实现 RFC 7323 §3（Timestamp 生成）和 §5（PAWS），并通过全局开关控制特性启用：
1. 新增 `SysctlOptions.ipv4_sysctl_tcp_timestamps` 开关，控制本端是否参与 Timestamp 协商
2. SYN-ACK 携带 Timestamp 选项（tsval=当前时钟, tsecr=对端 tsval）—— **仅当开关开启且对端 SYN 含 TSopt**
3. 所有 ESTABLISHED 状态报文（数据包、纯 ACK）携带 Timestamp —— **仅当协商成功（`tstamp_ok=true`）**
4. 收到报文时更新 `ts_recent`
5. PAWS 检查：利用 Timestamp 过滤回绕序列号的旧报文

---

## 三、改造方案

### 3.0 全局开关

**Linux 对标**：`net.ipv4.tcp_timestamps`（`net/ipv4/tcp_input.c` 中通过 `sock_net(sk)->ipv4.sysctl_tcp_timestamps` 读取）

**协商逻辑**（RFC 7323 §3.2）：

```
本端开关关闭  →  不发送 TSopt，忽略对端 TSopt，tstamp_ok = false
本端开关开启  →  对端 SYN 含 TSopt ? tstamp_ok = true : tstamp_ok = false
```

开关**只影响协商阶段**（握手时是否同意启用 Timestamp）。一旦 `tstamp_ok` 确定后，后续发包/收包逻辑统一依赖 `tstamp_ok` 字段，无需重复读取开关，保持路径简洁。

**第一步：`SysctlOptions.java` 添加开关字段**

```java
// Linux: net.ipv4.tcp_timestamps (default: 1 = enabled)
// 0 = 禁用 Timestamp 协商
// 1 = 启用（默认）：对端 SYN 含 TSopt 则协商，否则不启用
public static boolean ipv4_sysctl_tcp_timestamps = true;
```

**第二步：`TcpHandshaker.tcp_conn_request()` 协商判断（`TcpHandshaker.java:73` 附近）**

```java
// 原：
tmp_opt.tstamp_ok = tmp_opt.saw_tstmap != 0;

// 改为：
tmp_opt.tstamp_ok = SysctlOptions.ipv4_sysctl_tcp_timestamps   // 本端开关开启
                   && tmp_opt.saw_tstmap != 0;                  // 且对端 SYN 含 TSopt
```

开关置于此处的原因：这是服务端唯一的 Timestamp 协商决策点。`req.tstamp_ok` 确定后：
- `tcp_synack_options()` 读 `req.tstamp_ok` 决定 SYN-ACK 是否写 TSopt（§3.1）
- `tcp_create_openreq_child()` 将 `req.tstamp_ok` 传给 `TcpSock.rx_opt.tstamp_ok`
- `tcp_established_options()` 读 `tp.rx_opt.tstamp_ok` 决定数据包是否写 TSopt（§3.2）
- `tcp_paws_discard()` 读 `tp.rx_opt.tstamp_ok` 决定是否执行 PAWS（§3.5）

---

### 3.1 SYN-ACK 携带 Timestamp

**Linux 对标**：`net/ipv4/tcp_output.c:tcp_synack_options()`

**当前代码（`TcpOutput.java:291`）**：
```java
private byte[] tcp_synack_options(TcpSock tp, tcp_request_sock req, int mss) {
    ByteBuf optBuf = Unpooled.buffer(12);
    int wscale = req.wscale_ok ? req.rcv_wscale : -1;
    TcpOptionCodec.writeSynOptions(optBuf, mss, wscale);
    // ← Timestamp 缺失
    ...
}
```

**改造方案**：
```java
private byte[] tcp_synack_options(TcpSock tp, tcp_request_sock req, int mss) {
    // SYN 选项 12B + Timestamp 12B（NOP+NOP+Kind+Len+tsval+tsecr）= 24B
    ByteBuf optBuf = Unpooled.buffer(24);
    int wscale = req.wscale_ok ? req.rcv_wscale : -1;
    TcpOptionCodec.writeSynOptions(optBuf, mss, wscale);
    if (req.tstamp_ok) {                           // 对端 SYN 中携带了 Timestamp
        long tsval = tcp_time_stamp(tp);           // 当前时钟值（见 3.5 节）
        long tsecr = req.ts_recent;                // echo 对端 tsval（握手层已赋值）
        TcpOptionCodec.writeTimestampOption(optBuf, tsval, tsecr);
    }
    byte[] bytes = new byte[optBuf.readableBytes()];
    optBuf.readBytes(bytes);
    return bytes;
}
```

**依赖**：`tcp_request_sock.ts_recent` 在 `TcpHandshaker.tcp_openreq_init()` 中已由解析结果赋值（`tmp_opt.rcv_tsval`）。

---

### 3.2 数据包/ACK 包携带 Timestamp

**Linux 对标**：`net/ipv4/tcp_output.c:tcp_established_options()`

**当前代码（`TcpOutput.java:310`）**：
```java
private byte[] tcp_established_options() {
    // TODO: timestamp option
    return null;
}
```

**改造方案**：
```java
private byte[] tcp_established_options(TcpSock tp) {
    if (!tp.rx_opt.tstamp_ok) {
        return null;                               // 未协商 Timestamp，不发送
    }
    // 12 字节：NOP(1) + NOP(1) + Kind(1) + Len(1) + tsval(4) + tsecr(4)
    ByteBuf optBuf = Unpooled.buffer(12);
    long tsval = tcp_time_stamp(tp);               // 当前时钟
    long tsecr = tp.rx_opt.ts_recent;              // echo 对端最近 tsval
    TcpOptionCodec.writeTimestampOption(optBuf, tsval, tsecr);
    byte[] bytes = new byte[optBuf.readableBytes()];
    optBuf.readBytes(bytes);
    return bytes;
}
```

同步修改 `__tcp_transmit_skb()`（`TcpOutput.java:354`）中的调用：
```java
// 原：
rawOptions = tcp_established_options();
// 改为：
rawOptions = tcp_established_options(tp);
```

---

### 3.3 时钟函数 `tcp_time_stamp()`

**Linux 对标**：`include/net/tcp.h` 中 `tcp_time_stamp()`，单位为毫秒（`tcp_usec_ts=false`）或微秒（`tcp_usec_ts=true`）

**方案**（在 `TcpOutput.java` 或 `TcpClock.java` 中添加）：
```java
/**
 * RFC 7323 §5.1: The timestamp clock MUST tick at least once per second.
 * Linux default: 1ms resolution (jiffies with HZ=1000).
 *
 * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.h">tcp_time_stamp</a>
 */
static long tcp_time_stamp(TcpSock tp) {
    if (tp.tcp_usec_ts != 0) {
        return TcpClock.tcp_clock_us();            // 微秒时钟
    }
    return TcpClock.tcp_clock_ms();                // 毫秒时钟（默认）
}
```

`TcpClock` 中补充：
```java
public static long tcp_clock_ms() {
    return System.nanoTime() / 1_000_000L;         // 纳秒 → 毫秒
}

public static long tcp_clock_us() {
    return System.nanoTime() / 1_000L;             // 纳秒 → 微秒
}
```

---

### 3.4 收包时更新 `ts_recent`

**Linux 对标**：`net/ipv4/tcp_input.c:tcp_replace_ts_recent()`

**现状**：`tcp_ack()`（`TcpInput.java:844`）中已有占位注释：
```java
if (0 != (flag & FLAG_UPDATE_TS_RECENT)) {
    // flag |= tcp_replace_ts_recent(tp, tcpHdr.getSequenceNumber());
}
```

**方案**（在 `TcpInput.java` 中实现 `tcp_replace_ts_recent()`）：
```java
/**
 * RFC 7323 §5.3: Update ts_recent only if the segment advances RCV.NXT
 * and the timestamp is newer than the stored one.
 *
 * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3094">tcp_replace_ts_recent</a>
 */
private void tcp_replace_ts_recent(TcpSock tp, int seq) {
    if (tp.rx_opt.saw_tstmap == 0) {
        return;
    }
    // 只有当序列号在 [rcv_wup, rcv_nxt] 内才更新 ts_recent
    // 避免 OFO 报文覆盖更新的 ts_recent（RFC 7323 §5.3）
    if (before(tp.rcv_wup, seq)) {
        return;
    }
    // 时间戳必须比当前 ts_recent 更新（避免回绕）
    // Linux 使用 after() 或直接赋值（Timestamp 回绕 ~49 天，实际不检查）
    tp.rx_opt.ts_recent        = tp.rx_opt.rcv_tsval;
    tp.rx_opt.ts_recent_stamp  = (int) TcpClock.tcp_jiffies32();
}
```

调用位置：在 `tcp_validate_incoming()` 通过序列号检查后、在 `tcp_ack()` 处理 ACK 更新窗口后各调用一次（与 Linux 一致）。

---

### 3.5 PAWS（Protect Against Wrapped Sequence Numbers）

**Linux 对标**：`net/ipv4/tcp_input.c:tcp_paws_discard()`, `tcp_paws_check()`  
**RFC**：RFC 7323 §5

**方案**（在 `TcpInput.java` 中实现）：

```java
/**
 * RFC 7323 §5.2: PAWS check — discard old segments using timestamps.
 * Returns true if the segment should be discarded (Timestamp too old).
 *
 * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3565">tcp_paws_discard</a>
 */
private boolean tcp_paws_discard(TcpSock tp, TcpPacketBuf pkt) {
    // PAWS 仅在双端均协商了 Timestamp 时生效
    if (!tp.rx_opt.tstamp_ok) {
        return false;
    }
    if (tp.rx_opt.saw_tstmap == 0) {
        return false;
    }
    // RFC 7323 §5.2: 若收到的 tsval < ts_recent - PAWS_WINDOW，则丢弃
    // PAWS_WINDOW = 1 秒（以 Timestamp 时钟单位表示）
    // Linux: (s32)(rcv_tsval - ts_recent) < 0 && ...
    long tsval = tp.rx_opt.rcv_tsval;
    long ts_recent = tp.rx_opt.ts_recent;
    if ((int)(tsval - ts_recent) >= 0) {
        return false;                              // tsval 不比 ts_recent 旧，不丢弃
    }
    // ts_recent 超过 24 天未更新则失效（RFC 7323 §5.5，防止长期空闲连接拒绝新包）
    if (TcpClock.tcp_jiffies32() - tp.rx_opt.ts_recent_stamp > TCP_PAWS_24DAY_J) {
        return false;
    }
    return true;                                   // 丢弃：Timestamp 过旧
}

// 24 天对应的 jiffies 值（HZ=1000）
static final long TCP_PAWS_24DAY_J = 24L * 24 * 3600 * HZ;
```

**集成到 `tcp_validate_incoming()`（`TcpInput.java:1489`）**：

```java
private boolean tcp_validate_incoming(Channel net, TcpSock tp, TcpPacketBuf pkt) {
    // 解析本包的 Timestamp 选项，存入 rx_opt
    long[] ts = TcpOptionCodec.parseTimestamp(pkt.rawOptionsBuffer());
    if (ts != null) {
        tp.rx_opt.saw_tstmap = 1;
        tp.rx_opt.rcv_tsval = ts[0];
        tp.rx_opt.rcv_tsecr = ts[1];
    } else {
        tp.rx_opt.saw_tstmap = 0;
    }

    // PAWS 检查（RFC 7323 §5.2）
    // 注意：RST 报文绕过 PAWS（RFC 7323 §5.2 末段）
    if (!pkt.isRst() && tcp_paws_discard(tp, pkt)) {
        if (!tcp_oow_rate_limited(tp, pkt, 0, tp.last_oow_ack_time)) {
            tcp_send_dupack(net, tp, pkt);         // 发送 dupack（告知对端我们的期望）
        }
        tcp_drop_reason(tp, SKB_DROP_REASON_TCP_RFC7323_PAWS_ACK);
        return false;
    }

    // ... 原有序列号检查、RST 检查、SYN 检查 ...
}
```

---

### 3.6 Timestamp 辅助 RTT 测量

**Linux 对标**：`net/ipv4/tcp_input.c:tcp_ack_update_rtt()` 中的 Timestamp 路径

**现状**：`tcp_clean_rtx_queue()` 使用发包时间戳做 RTT 测量，Timestamp 路径可作为补充：

```java
/**
 * RFC 7323 §4: Use echoed Timestamp (TSecr) for RTT measurement.
 * More accurate than skb timestamps as it reflects actual one-way delay.
 *
 * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c#L3085">tcp_rcv_rtt_measure_ts</a>
 */
private void tcp_rcv_rtt_measure_ts(TcpSock tp) {
    if (!tp.rx_opt.tstamp_ok) {
        return;
    }
    if (tp.rx_opt.rcv_tsecr == 0) {
        return;
    }
    // rtt = now - TSecr（对端 echo 回来的我们之前的 tsval）
    long rtt_us;
    if (tp.tcp_usec_ts != 0) {
        rtt_us = TcpClock.tcp_clock_us() - tp.rx_opt.rcv_tsecr;
    } else {
        rtt_us = (TcpClock.tcp_clock_ms() - tp.rx_opt.rcv_tsecr) * 1000L;
    }
    if (rtt_us > 0) {
        tcp_rtt_estimator(tp, rtt_us);
    }
}
```

调用位置：在 `tcp_ack()` 末尾 `tcp_clean_rtx_queue()` 执行后调用（仅当无其他 RTT 样本时作为补充）。

---

## 四、涉及字段补充

`TcpSock` 中需补充或确认以下字段（对照 `tcp_options_received` 和 `TcpSock`）：

| 字段 | 所在类 | 含义 | 现状 |
|------|--------|------|------|
| `rx_opt.tstamp_ok` | `tcp_options_received` | 双端已协商 Timestamp | ✅ 已有 |
| `rx_opt.ts_recent` | `tcp_options_received` | 对端最近 tsval | ✅ 已有 |
| `rx_opt.ts_recent_stamp` | `tcp_options_received` | ts_recent 更新时的 jiffies | ✅ 已有 |
| `rx_opt.rcv_tsval` | `tcp_options_received` | 本包收到的 tsval | ✅ 已有 |
| `rx_opt.rcv_tsecr` | `tcp_options_received` | 本包收到的 tsecr | ✅ 已有 |
| `rx_opt.saw_tstmap` | `tcp_options_received` | 本包是否含 Timestamp | ✅ 已有（但未在收包路径中赋值） |
| `tcp_usec_ts` | `TcpSock` | Timestamp 精度（0=ms, 1=us） | ✅ 已有 |

**需在 `TcpSock` 补充**：

```java
// 在 TcpHandshaker.tcp_create_openreq_child() 中传递到 TcpSock
// Linux: net/ipv4/tcp_minisocks.c:tcp_create_openreq_child()
newtp.rx_opt.tstamp_ok  = req.tstamp_ok;
newtp.rx_opt.ts_recent  = req.ts_recent;
newtp.rx_opt.ts_recent_stamp = req.ts_recent_stamp;
newtp.rx_opt.snd_wscale = req.snd_wscale;
newtp.rx_opt.wscale_ok  = req.wscale_ok;
```

---

## 五、改造任务清单

### P0（PAWS 正确性依赖于先完成 Timestamp 生成）

- [x] **3.0** `SysctlOptions` — 新增 `ipv4_sysctl_tcp_timestamps = true` ✅ 已完成
- [x] **3.0** `TcpHandshaker.tcp_conn_request()` — 协商判断加入开关检查：`tstamp_ok = sysctl_tcp_timestamps && saw_tstmap != 0` ✅ 已完成
- [x] **3.1** `TcpOutput.tcp_synack_options()` — 当 `req.tstamp_ok` 时调用 `writeTimestampOption()` ✅ 已完成
- [x] **3.2** `TcpOutput.tcp_established_options(TcpSock)` — 实现 Timestamp 写入，`__tcp_transmit_skb()` 改为传入 `tp` ✅ 已完成
- [x] **3.3** `TcpClock` — 添加 `tcp_clock_ms()` / `tcp_clock_us()`；`TcpOutput/TcpInput` 添加 `tcp_time_stamp(tp)` ✅ 已完成
- [x] **3.4** `TcpInput.tcp_replace_ts_recent()` — 实现并在 `tcp_validate_incoming()` 和 `tcp_ack()` 中调用 ✅ 已完成
- [x] **3.5** `TcpInput.tcp_paws_discard()` — 实现并集成到 `tcp_validate_incoming()` 开头 ✅ 已完成
- [x] **3.5** `TcpInput.tcp_validate_incoming()` — 在入口处解析本包 Timestamp 到 `rx_opt`（`saw_tstmap`, `rcv_tsval`, `rcv_tsecr`） ✅ 已完成

### P1（依赖 P0）

- [x] **握手层传递** `TcpMultiplexer.tcp_create_openreq_child()` — 将 `req.tstamp_ok` / `ts_recent` 等传递给 `TcpSock` ✅ 已完成
- [x] **3.6** `TcpInput.tcp_rcv_rtt_measure_ts()` — Timestamp 辅助 RTT 测量，在 `tcp_ack()` 中调用 ✅ 已完成

---

## 六、改造后效果

| 场景 | 改造前 | 改造后 |
|------|--------|--------|
| SYN-ACK 报文头 | MSS+WScale+SACK-P（12B） | MSS+WScale+SACK-P+TS（24B） |
| 数据/ACK 报文头 | 无选项（0B） | TS 选项（12B） |
| PAWS | 无（回绕序列号可能被接受） | 正确过滤（RFC 7323 §5） |
| 对端 RTT 测量 | 依赖 skb 时间戳 | 可用 TSecr 精确测量（RFC 7323 §4） |
| TIME_WAIT 复用 | 不可用 | 修复后可基于 TS 实现 `tcp_tw_reuse`（RFC 6191） |
