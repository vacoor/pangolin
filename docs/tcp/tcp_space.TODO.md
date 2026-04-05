# tcp_space 优化方案

> 文件：`TcpOutput.java` — `tcp_space()` / `tcp_full_space()`  
> 分析日期：2026-04-05  
> 关联缺陷：`TCP.TODO2.md` D3

---

## 现状

```java
// TcpOutput.java ~L963
private int tcp_space(TcpSock tp) {
    return tcp_full_space(tp);   // FIXME：未减去已占用部分
}

int tcp_full_space(TcpSock tp) {
    final int sk_rcvbuf = U16_MAX << 6;   // 硬编码 ≈ 4 MB
    return tcp_win_from_space(sk_rcvbuf);
}
```

`tcp_space` 始终返回全量缓冲区（≈ 4 MB），`__tcp_select_window` 以此为基础计算接收窗口广播值。
实际可用空间未扣除已缓存但尚未交付的数据，窗口广播值可能偏高。

### Linux 内核实现

```c
// include/net/tcp.h
static inline int tcp_space(const struct sock *sk)
{
    return tcp_win_from_space(sk,
        READ_ONCE(sk->sk_rcvbuf)        // 接收缓冲区总大小
      - READ_ONCE(sk->sk_backlog.len)   // 等待处理的 backlog 数据
      - atomic_read(&sk->sk_rmem_alloc) // 已分配的接收内存
    );
}
```

### 代理架构下的等价分析

| Linux 字段 | 含义 | 代理等价物 |
|------------|------|-----------|
| `sk_rcvbuf` | 接收缓冲区总大小 | `U16_MAX << 6`（硬编码 ≈ 4 MB） |
| `sk_backlog.len` | 软中断待处理队列 | 不适用（Netty 事件循环无此概念） |
| `sk_rmem_alloc` | 已分配接收内存（含 OFO 队列） | **`tp.ofo_queue_bytes`**（已维护） |

在此代理中，数据到达即通过 `demultiplexer.consume` / `writeAndFlush` 转发，
唯一真正"占用缓冲区"的是尚未投递的 **OFO 队列**。

> **注**：`ofo_queue_bytes` 已在 `tcp_data_queue_ofo`（入队 +）、`tcp_ofo_queue`（出队 −）、
> `tcp_prune_ofo_queue`（剪枝 −）中正确维护，可直接使用。

---

## 方案对比

| 方案 | 改动范围 | 准确性 | 备注 |
|------|---------|--------|------|
| A：减去 OFO 队列字节 | 1 行 | 高（OFO 是唯一真实占用） | **推荐** |
| B：Netty 下游背压 | 中等 | 高（对下游拥塞最灵敏） | 需通过 `innerChannel` 访问 |
| C：A + B 结合 | 中等 | 最高 | 可在 A 之后叠加 |

---

## 方案 A：减去 OFO 队列字节（推荐）✅ 已实施

**改动位置**：`TcpOutput.java` `tcp_space()` 一行。

```java
// 修改前
private int tcp_space(TcpSock tp) {
    return tcp_full_space(tp);
}

// 修改后
private int tcp_space(TcpSock tp) {
    // OFO 队列是代理中唯一真实占用接收缓冲区的数据，对应 Linux sk_rmem_alloc。
    // ofo_queue_bytes 在 tcp_data_queue_ofo / tcp_ofo_queue / tcp_prune_ofo_queue 中维护。
    return Math.max(0, tcp_full_space(tp) - tp.ofo_queue_bytes);
}
```

**修复记录**：已修复 `TcpOutput.java` `tcp_space()`，`return tcp_full_space(tp)` → `return Math.max(0, tcp_full_space(tp) - tp.ofo_queue_bytes)`。

**效果**：
- OFO 积压时窗口收窄，对端减速，避免 OFO 队列无限膨胀
- OFO 投递后窗口随即恢复，无需额外通知
- 改动最小，无新增依赖

**注意**：`tcp_full_space` 的 `sk_rcvbuf` 仍是硬编码 `U16_MAX << 6`（≈ 4 MB）。
如需按连接动态调整缓冲区大小，需另行扩展 `TcpSock.sk_rcvbuf` 字段（超出本次范围）。

---

## 方案 B：Netty 下游背压

**改动位置**：`TcpOutput.java` `tcp_space()`，通过 `TcpDemultiplexer.innerChannel` 读取 Netty 水位。

```java
private int tcp_space(TcpSock tp) {
    Channel child = TcpDemultiplexer.innerChannel(tp);
    if (child == null || !child.isWritable()) {
        // 下游 Channel 不可写（写缓冲已超高水位），广播零窗口暂停接收
        return 0;
    }
    // bytesBeforeUnwritable：距离高水位还剩多少字节可写
    long writable = child.bytesBeforeUnwritable();
    return (int) Math.min(writable, tcp_full_space(tp));
}
```

**Netty 水位默认值**（`WriteBufferWaterMark`）：
- 低水位：32 KB（低于此值时 `isWritable()` 恢复为 true）
- 高水位：64 KB（超过此值时 `isWritable()` 变为 false）

**优点**：对下游拥塞（如上游代理慢、网络慢）响应最灵敏，TCP 流控与 Netty 背压直接联动。

**缺点与风险**：
1. **水位语义与 TCP 窗口不对称**：Netty 水位是写缓冲阈值，不是接收窗口语义，32/64 KB 默认值
   远小于 `tcp_full_space` 的 4 MB，会使窗口长期处于低位，影响吞吐量。
   使用前需调大水位（建议与 `tcp_full_space` 同量级，如 2 MB / 4 MB）。
2. **`isWritable()` 翻转频繁**：每次写入都可能触发水位变化，若未做平滑处理，
   窗口会高频震荡，可能引发 TCP silly window syndrome。
3. **需要平滑**：可结合 `tcp_rcv_ssthresh` 做窗口下限，防止瞬间跌零：
   ```java
   return (int) Math.max(tp.rcv_ssthresh, Math.min(writable, tcp_full_space(tp)));
   ```

---

## 方案 C：A + B 结合

在方案 A 基础上叠加下游背压感知：

```java
private int tcp_space(TcpSock tp) {
    // 1. 减去 OFO 队列（本地积压）
    int space = Math.max(0, tcp_full_space(tp) - tp.ofo_queue_bytes);

    // 2. 感知下游背压（Netty 写缓冲）
    Channel child = TcpDemultiplexer.innerChannel(tp);
    if (child != null && !child.isWritable()) {
        space = (int) Math.min(space, child.bytesBeforeUnwritable());
    }

    return Math.max(0, space);
}
```

**适用场景**：上游代理响应慢（下游 Channel 写缓冲堆积）+ 大量乱序报文同时出现时，
双重约束共同收窄窗口，端到端流控效果最优。

**前提**：需先调整 Netty 水位（见方案 B 注意事项），否则方案 B 的副作用会覆盖方案 A 的收益。

---

## 实施建议

1. **先实施方案 A**：改动一行，无副作用，立即修复 OFO 积压时窗口虚高的问题。
2. **观察效果**：在大量乱序场景下验证窗口收窄是否符合预期。
3. **按需叠加方案 B**：如果遇到下游拥塞导致 OOM 或转发堆积，再引入 Netty 背压，
   同时将 Netty 高水位调整为 `≥ 1 MB`（与 `tcp_rcv_ssthresh` 量级对齐）。
