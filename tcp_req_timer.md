# TCP 连接建立定时器实现方案

> 参考：Linux kernel `net/ipv4/tcp_minisocks.c`、`net/ipv4/inet_connection_sock.c`

---

## 1. 现状与缺口

### 已有基础

| 位置 | 内容 |
|------|------|
| `request_sock.rsk_timer` | 字段已声明，类型为 `ScheduledFuture<?>` |
| `TcpHandshaker.inet_csk_reqsk_queue_hash_add()` | 调用了 `reqsk_queue_hash_req(req, timeout)` |
| `SysctlOptions.sysctl_tcp_synack_retries` | 默认值 `5` 已定义 |
| `TcpDemultiplexer.synRegistry` | 半开连接存储 `Map<String, tcp_request_sock>` |
| `Tcp4Demultiplexer.tcp_v4_send_synack()` | SYN-ACK 发送逻辑已完整 |

### 核心缺口

```java
// TcpHandshaker.java reqsk_queue_hash_req()
private static boolean reqsk_queue_hash_req(tcp_request_sock req, long timeout) {
    req.timeout = timeout;
    req.rsk_timer = null;   // ← 定时器被清空，什么都没有启动
    return true;
}
```

1. **`rsk_timer` 从未启动** —— SYN-ACK 发出后无任何重传机制
2. **`tcp_request_sock` 缺少重传计数字段** `num_retrans`
3. **半开连接无超时淘汰** —— `synRegistry` 只增不删（除非收到 ACK）
4. **SYN-ACK 重传入口不存在** —— `TcpTimer` 中无 `reqsk_timer_handler` 对应逻辑

---

## 2. Linux 参考实现

Linux 连接建立定时器的核心链路（`net/ipv4/inet_connection_sock.c`）：

```
tcp_conn_request()
    ├── inet_csk_reqsk_queue_hash_add()
    │       └── reqsk_queue_hash_req()
    │               └── mod_timer(&req->rsk_timer, timeout)  // 先启动定时器
    └── af_ops->send_synack()                                 // 再发 SYN-ACK（同步）

                    ↓ 超时触发
            reqsk_timer_handler()
                ├── 未超过最大重试次数
                │       ├── mod_timer(next_timeout)   // 1. 先重调度
                │       ├── inet_rtx_syn_ack()        // 2. 再发包
                │       └── req->num_retrans++        // 3. 最后计数
                └── 超过最大重试次数
                        └── inet_csk_reqsk_queue_drop_and_put()
```

**超时序列（指数退避）：**

```
第 1 次 SYN-ACK：  T₀          （初始 RTO，tcp_timeout_init()）
第 1 次重传：       T₀ × 2
第 2 次重传：       T₀ × 4
第 k 次重传：       T₀ × 2^k
最大重试次数：      sysctl_tcp_synack_retries（默认 5）
```

**与本项目的架构差异（合理偏离）：**

Linux 的 `send_synack()` 是内核内同步调用，因此可以先注册定时器再发包。
本项目在发 SYN-ACK 之前需要先**异步连通后端服务器**，若沿用 Linux 的顺序
（在 `reqsk_queue_hash_req` 中启动定时器），定时器会在 SYN-ACK 发出之前就
开始计时并触发重传。因此本方案将定时器启动推迟到首次 SYN-ACK 发送成功的
回调中，属于针对异步架构的合理调整。

---

## 3. 改造步骤

### Step 1 — 为 `tcp_request_sock` 添加重传计数字段

**文件：** `internal/tcp_request_sock.java`

```java
public class tcp_request_sock extends inet_request_sock {
    // ... 现有字段不变 ...

    /** Number of SYN-ACK retransmissions sent (0 = only the initial send). */
    public int num_retrans;
}
```

---

### Step 2 — 在 `TcpTimer` 中实现 `reqsk_timer_handler`

**文件：** `core/TcpTimer.java`

新增方法，作为 SYN-ACK 重传定时器的回调：

```java
/**
 * Timer callback for a half-open connection (TCP_NEW_SYN_RECV).
 * Re-arms the timer first (Linux order: mod_timer → inet_rtx_syn_ack → num_retrans++),
 * then retransmits SYN-ACK with exponential back-off.
 * Drops the request via inet_csk_reqsk_queue_drop after sysctl_tcp_synack_retries exhausted.
 *
 * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/inet_connection_sock.c#L760">reqsk_timer_handler</a>
 */
public void reqsk_timer_handler(final Channel net,
                                final TcpSock listenSock,
                                final tcp_request_sock req) {
    // Remove the fired Runnable from the map (it is done, but clean up eagerly)
    timers.remove(req.rsk_timer);

    if (demultiplexer.synRegistry.get(req.uniqueKey()) != req) {
        return; // connection already completed or dropped
    }

    final int maxRetries = SysctlOptions.sysctl_tcp_synack_retries;
    if (req.num_retrans >= maxRetries) {
        log.warn("[REQ-TIMER] {}:{} SYN-ACK retries exhausted ({}), dropping",
                req.ir_rmt_addr.getHostAddress(), req.ir_rmt_port, maxRetries);
        inet_csk_reqsk_queue_drop(req);
        return;
    }

    // 1. Re-arm timer first (mirrors Linux: mod_timer before inet_rtx_syn_ack)
    req.timeout = Math.min(req.timeout << 1, TCP_RTO_MAX);
    scheduleReqskTimer(net, listenSock, req);

    // 2. Retransmit SYN-ACK, then increment counter
    demultiplexer.inet_rtx_syn_ack(net, listenSock, req);
    req.num_retrans++;

    log.debug("[REQ-TIMER] {}:{} retransmit SYN-ACK #{}/{}",
            req.ir_rmt_addr.getHostAddress(), req.ir_rmt_port,
            req.num_retrans, maxRetries);
}

/**
 * Schedules (or re-schedules) the SYN-ACK retransmission timer for req.
 * rsk_timer holds the Runnable key; the Future is tracked in the internal timers map
 * so sk_stop_timer() can cancel it.
 *
 * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/inet_connection_sock.c#L1170">reqsk_queue_hash_req</a>
 */
public void scheduleReqskTimer(final Channel net,
                               final TcpSock listenSock,
                               final tcp_request_sock req) {
    if (req.rsk_timer != null) {
        sk_stop_timer(req.rsk_timer);
    }
    final Runnable callback = () -> reqsk_timer_handler(net, listenSock, req);
    req.rsk_timer = callback;
    timers.put(callback, net.eventLoop().schedule(callback, req.timeout, TimeUnit.MILLISECONDS));
}

/**
 * Drops a half-open connection: cancels its timer, closes the backend channel,
 * and removes it from synRegistry.
 *
 * Delegates entirely to inet_csk_destroy_sock which covers all three cleanup steps.
 * No RST is sent — mirrors Linux: if SYN-ACK retries are exhausted, RST is unlikely
 * to reach the client either.
 *
 * @see <a href="https://github.com/torvalds/linux/blob/master/net/ipv4/inet_connection_sock.c#L989">inet_csk_reqsk_queue_drop</a>
 */
public void inet_csk_reqsk_queue_drop(final tcp_request_sock req) {
    demultiplexer.inet_csk_destroy_sock(req);
}
```

---

### Step 3 — 在 `TcpDemultiplexer` 中添加 `inet_rtx_syn_ack`

**文件：** `core/TcpDemultiplexer.java`

`inet_rtx_syn_ack` 是平台无关的重传入口，具体发包委托给子类：

```java
/**
 * Retransmits a SYN-ACK for the given half-open connection.
 * Mirrors Linux inet_rtx_syn_ack() → af_specific->send_synack().
 */
public abstract void inet_rtx_syn_ack(Channel net,
                                      TcpSock listenSock,
                                      tcp_request_sock req);
```

---

### Step 4 — 在 `Tcp4Demultiplexer` 中实现 `inet_rtx_syn_ack`，并在首次发送后启动定时器

**文件：** `core/Tcp4Demultiplexer.java`

`tcp_v4_send_synack` 是首次发送 SYN-ACK 的方法，**必须在发送成功的回调里**启动定时器，
而不是在 `reqsk_queue_hash_req` 中（那时 SYN-ACK 尚未发出）。

```java
// 首次发送 SYN-ACK —— 发送成功后才启动重传定时器
protected void tcp_v4_send_synack(final Channel net,
                                  final TcpSock listenSock,
                                  final tcp_request_sock req,
                                  final TcpPacketBuf syn) {
    final TcpBuffer skb = output.tcp_make_synack(listenSock, req, syn);
    skb.srcPort(req.ir_num);
    skb.dstPort(req.ir_rmt_port);
    net.writeAndFlush(buildIp4Packet(skb,
                    (Inet4Address) req.ir_loc_addr,
                    (Inet4Address) req.ir_rmt_addr))
       .addListener(f -> {
           if (f.isSuccess()) {
               // 首次 SYN-ACK 已发出，现在才启动重传定时器
               timer.scheduleReqskTimer(net, listenSock, req);
           } else {
               // 发送失败：通知客户端并销毁半开连接（关闭后端 channel + 清 registry）
               // 与后端连接失败的处理保持一致
               tcp_v4_send_reset(net, syn, -99);
               inet_csk_destroy_sock(req);
           }
       });
}

// 重传入口（由 reqsk_timer_handler 调用）—— 不再启动新定时器，由 handler 负责重调度
@Override
public void inet_rtx_syn_ack(final Channel net,
                              final TcpSock listenSock,
                              final tcp_request_sock req) {
    final TcpBuffer skb = output.tcp_make_synack(listenSock, req, null);
    skb.srcPort(req.ir_num);
    skb.dstPort(req.ir_rmt_port);
    net.writeAndFlush(buildIp4Packet(skb,
                    (Inet4Address) req.ir_loc_addr,
                    (Inet4Address) req.ir_rmt_addr));
}
```

> **注意：** `tcp_make_synack` 已能根据 `req` 重建 SYN-ACK，无需原始 SYN 包。
> 如果当前实现依赖 `TcpPacketBuf syn` 读取某些字段（如 MSS、窗口缩放），
> 需确认这些值在 `tcp_request_sock` 中已保存（`mss`、`wscale_ok`、`snd_wnd`
> 字段均已存在）。

---

### Step 5 — 修复 `reqsk_queue_hash_req`，只保存 timeout，不启动定时器

**文件：** `core/TcpHandshaker.java`

`reqsk_queue_hash_req` 在 `tcp_conn_request()` 中**同步调用**，此时后端连接尚未建立，
SYN-ACK 还未发出。因此这里只做注册，不启动定时器：

```java
// 修改前：
private static boolean reqsk_queue_hash_req(tcp_request_sock req, long timeout) {
    req.timeout = timeout;
    req.rsk_timer = null;   // FIXME
    return true;
}

// 修改后：保持 static，只初始化字段，定时器由 SYN-ACK 发送回调启动
private static boolean reqsk_queue_hash_req(tcp_request_sock req, long timeout) {
    req.timeout = timeout;
    req.num_retrans = 0;
    req.rsk_timer = null;   // 定时器将在首次 SYN-ACK 发送成功后启动（见 Step 4）
    return true;
}
```

---

### Step 6 — ACK 完成时取消定时器

**文件：** `core/Tcp4Demultiplexer.java`（`tcp_v4_rcv` 中处理第三次握手的分支）

```java
if (TcpState.TCP_NEW_SYN_RECV.equals(sk.state())) {
    final tcp_request_sock request = (tcp_request_sock) sk;

    // 取消 SYN-ACK 重传定时器 — 三次握手即将完成
    if (request.rsk_timer != null) {
        timer.sk_stop_timer(request.rsk_timer);
        request.rsk_timer = null;
    }

    final TcpSock nsk = tcp_check_req(net, (TcpSock) request.skc_listener, pkt, request);
    // ... 后续握手完成逻辑不变 ...
}
```

---

## 4. 完整数据流

```
[Client SYN]
      │
      ▼
tcp_conn_request()
  ├── 创建 tcp_request_sock（num_retrans=0, rsk_timer=null）
  ├── reqsk_queue_hash_req()   ← 只注册，不启动定时器
  └── 异步连接后端（connTimeoutMs）
              │
              │ 后端连接成功
              ▼
      tcp_v4_send_synack()     首次发出 SYN-ACK
              │
              │ writeAndFlush 回调 isSuccess
              ▼
      scheduleReqskTimer(T₀)  ← 此刻才启动定时器，rsk_timer 在此赋值
              │
              │ T₀ 后触发
              ▼
      reqsk_timer_handler()
        ├── num_retrans < synack_retries
        │     ├── inet_rtx_syn_ack()          重发 SYN-ACK
        │     └── scheduleReqskTimer(T₀<<1)  指数退避重调度
        └── num_retrans >= synack_retries
              └── inet_csk_reqsk_queue_drop() 清理半开连接

[Client ACK 到达]
      │
      ▼
tcp_v4_rcv() → TCP_NEW_SYN_RECV 分支
  ├── rsk_timer.cancel()     取消定时器
  ├── tcp_check_req()        验证 ACK
  └── moveToEstablished()    连接建立完成
```

---

## 5. 涉及文件汇总

| 文件 | 变更类型 | 关键改动 |
|------|----------|----------|
| `internal/SysctlOptions.java` | 新增常量 | `sysctl_tcp_synack_retries = 5` |
| `internal/request_sock.java` | 字段修改 | `rsk_timer` 加 `volatile` |
| `internal/tcp_request_sock.java` | 新增字段 | `int num_retrans` |
| `core/TcpTimer.java` | 新增方法 | `scheduleReqskTimer`、`reqsk_timer_handler`、`inet_csk_reqsk_queue_drop`（委托 `inet_csk_destroy_sock`） |
| `core/TcpDemultiplexer.java` | 新增抽象方法 + 修改清理 | `inet_rtx_syn_ack`；`inet_csk_destroy_sock` 补 timer cancel |
| `core/Tcp4Demultiplexer.java` | 多处修改 | `inet_rtx_syn_ack` 实现；`tcp_v4_send_synack` 失败发 RST + `inet_csk_destroy_sock`；ACK 分支 `sk_stop_timer` |
| `core/TcpHandshaker.java` | 修复 FIXME | `reqsk_queue_hash_req` 初始化 `num_retrans = 0`，注释说明定时器由发送回调负责 |

---

## 6. 注意事项

**线程安全**：`scheduleReqskTimer` 和 `reqsk_timer_handler` 均运行在同一个
`net.eventLoop()` 上，与 `tcp_v4_rcv` 的包处理线程相同，无需额外加锁。

**`tcp_make_synack` 的 null 处理**：Step 4 中重传时传入 `null` 作为 syn 参数，
需确认 `TcpOutput.tcp_make_synack` 对 null 的容忍度。若当前实现有 NPE 风险，
可在 `tcp_request_sock` 中缓存首次 SYN 包的关键字段（MSS、窗口缩放等已有），
或为重传路径单独提取一个 `tcp_make_synack_retransmit(listenSock, req)` 重载。

**`TCP_RTO_MAX`**：退避上限复用 `TcpConstants.TCP_RTO_MAX`（已定义为 120 秒），
与 Linux 保持一致。
