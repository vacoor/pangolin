# TCP 乱序重排改造方案

> 参考：Linux kernel `net/ipv4/tcp_input.c` — `tcp_data_queue_ofo()`、`tcp_ofo_queue()`

---

## 1. 现状与缺口

### 已有基础

| 位置 | 内容 |
|------|------|
| `TcpInput.tcp_data_queue()` | 已识别乱序分支（`seq > rcv_nxt`），调用 `tcp_data_queue_ofo()` |
| `TcpInput.tcp_data_queue_ofo()` | 方法骨架存在，完全为空（stub） |
| `TcpInput.queue_and_out()` | 顺序数据交付路径完整 |
| `TcpInput.determineEndSeq()` | 已计算 `endSeq = seq + payloadLen (+ FIN)` |
| `TcpDemultiplexer.consume()` | 数据交付给上层（隧道层）的接口完整 |

### 核心缺口

```java
// TcpInput.java — 第1113行
private void tcp_data_queue_ofo(final TcpSock sk, final TcpPacketBuf pkt) {
    // 完全为空：乱序包被静默丢弃
}
```

```java
// TcpInput.java — 第1214行（注释中的 TODO）
// if (!RB_EMPTY_ROOT(&tp->out_of_order_queue)) {
//     tcp_ofo_queue(sk);
// }
```

1. **`TcpSock` 无乱序队列字段** —— `out_of_order_queue` 完全缺失
2. **`tcp_data_queue_ofo()` 为空** —— 乱序包直接丢弃，对端无限重传
3. **`tcp_ofo_queue()` 不存在** —— `rcv_nxt` 推进后不会尝试从乱序队列中取数据
4. **乱序队列无内存上限** —— 无 `tcp_prune_ofo_queue()` 对应逻辑
5. **无 SACK 生成** —— 对端收不到乱序反馈，只能依赖超时重传（本次方案不实现 SACK，见第 6 节）

---

## 2. Linux 参考实现

### 核心数据结构

```c
// struct tcp_sock（net/ipv4/tcp.h）
struct rb_root  out_of_order_queue;   // 乱序缓冲区（红黑树，key=seq）
struct sk_buff *ooo_last_skb;         // 最近插入的 OOO 节点（插入优化）
```

Linux 使用红黑树（`rb_root`）以序列号为 key 存储乱序段，支持 O(log n) 的有序插入和前向遍历。

### 核心函数调用链

```
收到数据包
    └── tcp_data_queue()
            ├── seq == rcv_nxt  → queue_and_out() → tcp_ofo_queue()  ← 尝试排空 OOO 队列
            └── seq >  rcv_nxt  → tcp_data_queue_ofo()               ← 插入 OOO 队列

tcp_data_queue_ofo()
    ├── 检查队列上限，必要时 tcp_prune_ofo_queue()
    ├── 用 ooo_last_skb 快速定位插入点（最近插入优化）
    ├── 处理重叠：修剪首部（与前驱重叠）、丢弃后继中被覆盖的段
    ├── 插入红黑树
    └── 生成 SACK 块（可选）

tcp_ofo_queue()
    └── 遍历红黑树头部
            ├── before(entry.seq, rcv_nxt)  → 修剪或丢弃（重复数据）
            ├── entry.seq == rcv_nxt        → 取出并交付，rcv_nxt 前进，继续循环
            └── after(entry.seq, rcv_nxt)   → 停止（仍乱序）
```

---

## 3. 改造步骤

### Step 0 — 添加功能开关 `sysctl_tcp_ofo_enabled`

**文件：** `internal/SysctlOptions.java`

```java
/**
 * Enable TCP out-of-order reordering (OFO queue).
 * When false (default), out-of-order segments are silently dropped — existing behavior.
 * Set to true once the OFO implementation is ready.
 */
public static boolean sysctl_tcp_ofo_enabled = false;
```

默认 `false`，保持现有静默丢弃行为不变。实现完成并验证后改为 `true` 即可启用。

---

### Step 1 — 定义乱序队列条目类 `OfoEntry`

**文件：** `internal/OfoEntry.java`（新建）

```java
package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal;

import io.netty.buffer.ByteBuf;

/**
 * A single entry in the TCP out-of-order queue.
 * Holds a retained slice of the TCP payload so the original packet buffer
 * can be released independently.
 *
 * Mirrors the role of struct sk_buff in Linux's out_of_order_queue rbtree.
 */
public final class OfoEntry {
    /** First sequence number of this segment (inclusive). */
    public final int seq;
    /** End sequence number (exclusive): seq + payloadLen [+ 1 if FIN]. */
    public final int endSeq;
    /** Retained payload slice — caller must release() when done. */
    public final ByteBuf payload;
    /** Whether this segment carries a FIN flag. */
    public final boolean fin;

    public OfoEntry(final int seq, final int endSeq,
                    final ByteBuf payload, final boolean fin) {
        this.seq    = seq;
        this.endSeq = endSeq;
        this.payload = payload;
        this.fin    = fin;
    }

    /** Releases the retained payload ByteBuf. Must be called exactly once. */
    public void release() {
        payload.release();
    }
}
```

---

### Step 2 — 为 `TcpSock` 添加乱序队列和统计字段

**文件：** `internal/TcpSock.java`

```java
import com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal.OfoEntry;
import java.util.TreeMap;

public class TcpSock extends inet_connection_sock {

    // ── 已有字段不变 ──

    /**
     * Out-of-order receive queue, keyed by segment start sequence number.
     * Mirrors Linux's tcp_sock.out_of_order_queue (rb_root).
     *
     * Comparator uses signed subtraction to handle 32-bit sequence wraparound,
     * consistent with before()/after() helpers.
     */
    public final TreeMap<Integer, OfoEntry> out_of_order_queue =
            new TreeMap<>((a, b) -> a - b);

    /**
     * Total bytes currently held in out_of_order_queue.
     * Used to enforce OOF_MAX_BYTES budget.
     */
    public int ofo_queue_bytes;
}
```

---

### Step 3 — 守护 `tcp_data_queue()` 中的 OFO 调用 + 实现 `tcp_data_queue_ofo()`

**文件：** `core/TcpInput.java`

#### 3a. `tcp_data_queue()` 调用点无需改动

`tcp_data_queue()` 的 else 分支直接调用 `tcp_data_queue_ofo(tp, pkt)`，
开关判断集中在方法内部（见 3b），调用点保持简洁。

#### 3b. 替换现有的空方法 `tcp_data_queue_ofo()`

> **关键细节 — `fin` 标志的正确性**
>
> 尾部截断时（`endSeq = succ.seq`），若截断点在 FIN 的逻辑序号之前，FIN 已被丢弃。
> 必须用 `pkt.isFin() && (endSeq == determineEndSeq(pkt))` 判断 FIN 是否仍在截断范围内，
> 而不能直接取 `pkt.isFin()`。否则 `OfoEntry.fin=true` 但 FIN 已被裁掉，
> 会导致 `tcp_ofo_queue()` 中 `deliverLen` 计算少 1、并错误触发 `tcp_fin()`。

```java
/** Maximum bytes allowed in the OOO queue (mirrors Linux sk_rcvbuf / 2 heuristic). */
private static final int OFO_MAX_BYTES = 256 * 1024;  // 256 KB

/**
 * Queues an out-of-order segment into tp.out_of_order_queue.
 * Returns immediately (silent drop) when sysctl_tcp_ofo_enabled is false,
 * preserving the original behavior without touching any call sites.
 * Mirrors Linux tcp_data_queue_ofo() in net/ipv4/tcp_input.c.
 *
 * Handles three sub-cases:
 *   1. Pure duplicate  — drop silently
 *   2. Partial overlap — trim the new segment's leading bytes
 *   3. No overlap      — insert; evict overlapped successors
 */
private void tcp_data_queue_ofo(final TcpSock tp, final TcpPacketBuf pkt) {
    // ── 0. Feature gate ──
    if (!SysctlOptions.sysctl_tcp_ofo_enabled) {
        return; // drop silently — existing behavior; queue stays empty so tcp_ofo_queue is never called
    }

    int seq    = pkt.tcpSeq();
    int endSeq = determineEndSeq(pkt);

    // ── 1. Drop if no payload (empty OOO segment is meaningless) ──
    if (seq == endSeq) {
        return;
    }

    // ── 1. Memory budget: prune oldest entries if queue is full ──
    if (tp.ofo_queue_bytes >= OFO_MAX_BYTES) {
        tcp_prune_ofo_queue(tp);
        // If still over budget after pruning, drop this segment
        if (tp.ofo_queue_bytes >= OFO_MAX_BYTES) {
            log.debug("[OFO] queue full, dropping seq={}", seq);
            return;
        }
    }

    final TreeMap<Integer, OfoEntry> ofo = tp.out_of_order_queue;

    // ── 2. Check predecessor: trim leading overlap ──
    // lowerEntry finds the entry with the greatest key strictly less than seq.
    final Map.Entry<Integer, OfoEntry> pred = ofo.lowerEntry(seq);
    if (pred != null) {
        final OfoEntry prev = pred.getValue();
        // Use after() (strictly >) to exclude the no-overlap case prev.endSeq == seq.
        // !before() would include ==, causing a valid contiguous segment to be dropped.
        if (after(prev.endSeq, seq)) {
            // prev.endSeq > seq: genuine overlap at the front
            if (!after(endSeq, prev.endSeq)) {
                // endSeq <= prev.endSeq: new segment is fully covered by predecessor → drop
                return;
            }
            // Partial overlap: trim leading bytes already covered by predecessor
            seq = prev.endSeq;
            if (seq == endSeq) {
                return; // Became empty after trim
            }
        }
    }

    // ── 3. Evict successors that are fully or partially covered ──
    final Iterator<Map.Entry<Integer, OfoEntry>> it =
            ofo.tailMap(seq).entrySet().iterator();
    while (it.hasNext()) {
        final Map.Entry<Integer, OfoEntry> next = it.next();
        final OfoEntry succ = next.getValue();

        if (!before(succ.seq, endSeq)) {
            break; // Successor starts at or after our endSeq → no overlap
        }

        if (!before(endSeq, succ.endSeq)) {
            // Our new segment fully covers this successor → evict it
            tp.ofo_queue_bytes -= succ.payload.readableBytes();
            succ.release();
            it.remove();
        } else {
            // Partial overlap at the tail: our endSeq falls inside successor.
            // Trim our tail to keep successor's unique data intact.
            endSeq = succ.seq;
            break;
        }
    }

    // ── 4. Insert the (trimmed) segment ──
    // Guard: after trimming, the segment may be empty (e.g. fully covered by a successor
    // whose seq == our seq). An empty non-FIN segment must not overwrite an existing entry.
    //
    // The FIN flag is only propagated if the tail was NOT cut off by a successor.
    // If endSeq < determineEndSeq(pkt), we trimmed past the FIN — do not set fin=true.
    final boolean fin = pkt.isFin() && (endSeq == determineEndSeq(pkt));
    if (!before(seq, endSeq) && !fin) {
        return;
    }
    // payloadLen = byte count of actual data (FIN does not occupy a real byte).
    final int payloadOffset = seq - pkt.tcpSeq();
    final int payloadLen    = endSeq - seq - (fin ? 1 : 0);

    final ByteBuf slice;
    if (payloadLen > 0) {
        slice = pkt.tcpPayloadSlice().retainedSlice(payloadOffset, payloadLen);
    } else {
        slice = pkt.tcpPayloadSlice().retainedSlice(0, 0); // FIN-only segment
    }

    final OfoEntry entry = new OfoEntry(seq, endSeq, slice, fin);
    ofo.put(seq, entry);
    tp.ofo_queue_bytes += slice.readableBytes();

    log.debug("[OFO] queued seq={} endSeq={} queueSize={} queueBytes={}",
            seq, endSeq, ofo.size(), tp.ofo_queue_bytes);
}

/**
 * Prunes the oldest entries from the OOO queue to reclaim memory.
 * Mirrors Linux tcp_prune_ofo_queue().
 * Drops up to half the current entries, starting from the highest seq
 * (least useful for delivery).
 */
private void tcp_prune_ofo_queue(final TcpSock tp) {
    final TreeMap<Integer, OfoEntry> ofo = tp.out_of_order_queue;
    final int target = ofo.size() / 2;
    int pruned = 0;
    while (pruned < target && !ofo.isEmpty()) {
        final Map.Entry<Integer, OfoEntry> last = ofo.pollLastEntry();
        tp.ofo_queue_bytes -= last.getValue().payload.readableBytes();
        last.getValue().release();
        pruned++;
    }
    log.debug("[OFO] pruned {} entries, remaining queueBytes={}", pruned, tp.ofo_queue_bytes);
}
```

---

### Step 4 — 实现 `tcp_ofo_queue()`

**文件：** `core/TcpInput.java`

新增方法，在 `rcv_nxt` 推进后尝试排空乱序队列：

```java
/**
 * Drains contiguous entries from the head of the OOO queue into the receive
 * stream now that rcv_nxt has advanced.
 * Mirrors Linux tcp_ofo_queue() in net/ipv4/tcp_input.c.
 *
 * Must be called on the EventLoop thread (same as packet processing).
 */
private void tcp_ofo_queue(final Channel net, final TcpSock tp) throws IOException {
    final TreeMap<Integer, OfoEntry> ofo = tp.out_of_order_queue;

    while (!ofo.isEmpty()) {
        final Map.Entry<Integer, OfoEntry> head = ofo.firstEntry();
        final OfoEntry entry = head.getValue();

        // ── Still out of order ──
        if (after(entry.seq, tp.rcv_nxt)) {
            break;
        }

        ofo.pollFirstEntry();
        tp.ofo_queue_bytes -= entry.payload.readableBytes();

        // ── Pure duplicate: entirely before rcv_nxt ──
        if (!after(entry.endSeq, tp.rcv_nxt)) {
            log.debug("[OFO] discard duplicate seq={} endSeq={}", entry.seq, entry.endSeq);
            entry.release();
            continue;
        }

        // ── Deliver the entry (trim leading already-received bytes) ──
        final int trimOffset = tp.rcv_nxt - entry.seq;  // bytes already received
        final int deliverLen = entry.endSeq - tp.rcv_nxt
                - (entry.fin ? 1 : 0);                   // exclude FIN byte from data

        if (deliverLen > 0) {
            final ByteBuf data = entry.payload.slice(trimOffset, deliverLen);
            demultiplexer.consumeRaw(tp, data);          // deliver to tunnel layer
        }

        // Advance rcv_nxt past this entry
        tcp_rcv_nxt_update(tp, entry.endSeq);

        log.debug("[OFO] delivered seq={} endSeq={} rcv_nxt={}",
                entry.seq, entry.endSeq, tp.rcv_nxt);

        entry.release();

        // FIN carried by an OOO segment
        if (entry.fin) {
            tcp_fin(net, tp);
            break;
        }
    }
}
```

> **`tcp_fin()` 签名说明**
>
> 方法签名为 `tcp_fin(Channel net, TcpSock tp)`，无 `pkt` 参数。
> 端口与地址信息统一从 `tp` 字段读取，与 `pkt.tcpXxxPort()` 等价：
>
> | 字段 | 含义 |
> |------|------|
> | `tp.ir_num` | 本地端口（= `pkt.tcpDstPort()`） |
> | `tp.ir_rmt_port` | 远端端口（= `pkt.tcpSrcPort()`） |
> | `tp.ir_loc_addr` | 本地地址 |
> | `tp.ir_rmt_addr` | 远端地址 |
>
> 日志保持两条独立输出，OFO 路径与正常路径共用同一方法。

---

### Step 5 — 在 `queue_and_out()` 中调用 `tcp_ofo_queue()`

**文件：** `core/TcpInput.java`

顺序数据交付后，立即尝试排空乱序队列：

```java
// 现有 queue_and_out() 末尾追加：
private void queue_and_out(final Channel net, final TcpSock tp,
                           final TcpPacketBuf pkt) throws IOException {
    // ... 现有顺序数据交付逻辑不变 ...
    tcp_queue_rcv(net, tp, pkt);

    // 新增：rcv_nxt 已推进，尝试排空乱序队列。
    // 开关关闭时队列永远为空，isEmpty() 保证不会进入此分支。
    if (!tp.out_of_order_queue.isEmpty()) {
        tcp_ofo_queue(net, tp);
    }
}
```

---

### Step 6 — 在 `TcpDemultiplexer` 中添加 `consumeRaw()`

**文件：** `core/TcpDemultiplexer.java`

现有 `consume()` 以 `TcpPacketBuf` 为参数，乱序队列中存储的是裸 `ByteBuf`，
需要一个接受 `ByteBuf` 的重载：

```java
/**
 * Delivers a raw payload ByteBuf (from the OOO queue) to the tunnel layer.
 * The caller is responsible for the ByteBuf's reference count;
 * this method does NOT release the buffer.
 */
public void consumeRaw(final TcpSock sk, final ByteBuf data) {
    if (sk.child != null && data.isReadable()) {
        innerChannel(sk).writeAndFlush(data.retain());
    }
}
```

---

### Step 7 — 连接关闭时释放乱序队列

**文件：** `core/TcpDemultiplexer.java`（连接清理路径）

在现有的连接关闭/清理逻辑中，追加乱序队列的释放：

```java
/**
 * Releases all resources held by the OOO queue of the given socket.
 * Must be called when the connection is torn down.
 */
private void tcp_ofo_queue_release(final TcpSock tp) {
    for (final OfoEntry entry : tp.out_of_order_queue.values()) {
        entry.release();
    }
    tp.out_of_order_queue.clear();
    tp.ofo_queue_bytes = 0;
}
```

在 `tcp_done()`、`tcp_close()` 或连接从 `establishedRegistry` 移除的位置调用：

```java
tcp_ofo_queue_release(tp);
```

---

## 4. 完整数据流

```
[收到数据包 pkt]
      │
      ▼
tcp_data_queue(net, tp, pkt)
  │
  ├── seq == rcv_nxt  ──────────────────────────────────────────────┐
  │                                                                  │
  │   queue_and_out(net, tp, pkt)                                   │
  │     ├── tcp_queue_rcv()                                         │
  │     │     ├── consume(tp, pkt)  → innerChannel.writeAndFlush()  │
  │     │     └── tcp_rcv_nxt_update(tp, endSeq)                    │
  │     └── out_of_order_queue 非空？                               │
  │               └── tcp_ofo_queue(net, tp)  ◄─────────────────────┘
  │                     └── 循环取队列头 entry
  │                           ├── after(entry.seq, rcv_nxt) → break
  │                           ├── entry 为重复 → release, continue
  │                           └── 可交付 → consumeRaw() + rcv_nxt_update()
  │                                         → 继续循环
  │
  └── seq > rcv_nxt（乱序）
        │
        ▼
  tcp_data_queue_ofo(tp, pkt)
    ├── 超出 OFO_MAX_BYTES → tcp_prune_ofo_queue() 淘汰最高 seq 的条目
    ├── 检查前驱重叠 → 修剪 seq 头部
    ├── 驱逐被覆盖的后继条目 → release() + 从树中删除
    └── retainedSlice() + TreeMap.put(seq, OfoEntry)


[连接关闭]
      │
      ▼
tcp_ofo_queue_release(tp)  → 逐项 release() + clear()
```

---

## 5. 内存管理要点

| 场景 | 操作 |
|------|------|
| `tcp_data_queue_ofo()` 插入条目 | `pkt.tcpPayloadSlice().retainedSlice(...)` —— 引用计数 +1 |
| `tcp_ofo_queue()` 交付条目 | `entry.release()` —— 引用计数 -1；`consumeRaw` 内部再 `retain()` |
| `tcp_prune_ofo_queue()` 淘汰 | `entry.release()` |
| 连接关闭 `tcp_ofo_queue_release()` | 对所有残留条目 `entry.release()` |
| 插入时发现纯重复 | 无 retain，直接 return，原始 pkt 由调用方管理 |

所有操作均在同一个 `net.eventLoop()` 线程上执行，无需额外加锁。

---

## 6. SACK（本次不实现，留作后续）

Linux 在 `tcp_data_queue_ofo()` 末尾会调用 `tcp_sack_new_ofo_skb()` 生成
SACK 块，让发送方精确知道哪些段已收到，从而避免不必要的重传。

本方案不实现 SACK，原因：

1. `TcpOutput.__tcp_send_ack()` 当前不编码 SACK 选项
2. `TcpSock` 缺少 `selective_acks[4]`（SACK 块数组）
3. SACK 需要对端也开启（三次握手时协商），`tcp_options_received` 中无对应字段

无 SACK 时的回退行为：接收方只发送累积 ACK（`rcv_nxt`），对端在 RTO 超时后
重传丢失段，最终仍能正确交付，只是效率较低。

---

## 7. 涉及文件汇总

| 文件 | 变更类型 | 关键改动 |
|------|----------|---------|
| `internal/SysctlOptions.java` | 新增字段 | `static boolean sysctl_tcp_ofo_enabled = false`（默认关闭） |
| `internal/OfoEntry.java` | **新建** | 乱序条目数据类，持有 retained ByteBuf |
| `internal/TcpSock.java` | 新增字段 | `TreeMap<Integer, OfoEntry> out_of_order_queue`、`int ofo_queue_bytes` |
| `core/TcpInput.java` | 实现 stub + 新增方法 | `tcp_data_queue_ofo()`、`tcp_ofo_queue()`、`tcp_prune_ofo_queue()` |
| `core/TcpInput.java` | 修改现有方法 | `queue_and_out()` 末尾追加 `tcp_ofo_queue()` 调用 |
| `core/TcpInput.java` | 简化现有方法 | `tcp_fin(Channel, TcpSock)` 端口/地址统一从 `tp.ir_num`/`tp.ir_rmt_port`/`tp.ir_rmt_addr`/`tp.ir_loc_addr` 取，移除 `pkt` 参数 |
| `core/TcpDemultiplexer.java` | 新增方法 | `consumeRaw(TcpSock, ByteBuf)` |
| `core/TcpDemultiplexer.java` | 修改连接清理 | 调用 `tcp_ofo_queue_release(tp)` |
