# Bug 记录

---

## BUG-001 上游连接失败时 RST 未正确送达客户端

**现象**

- 上游连接失败时，期望向下游（客户端）发送 RST
- Wireshark 抓包显示发出的是 ACK（或 flags 异常），并非预期的 `[RST, ACK]` / `[RST]`
- 下游客户端触发多次 TCP 重传

**涉及文件**

- `TcpHandshaker.java`（异步 lambda 持有 `pkt` 但未 retain）
- `Tcp4Demultiplexer.java` → `tcp_v4_send_reset`（RST 构造逻辑本身正确）
- `IpPacketHandler.java`（`channelRead` 的 finally 块释放 `pkt`）

---

### 根本原因一：`pkt` ByteBuf 生命周期问题（高危）

`IpPacketHandler.channelRead` 在 `channelRead0` 返回后，finally 块里释放了 `pkt`：

```java
// IpPacketHandler.java
try {
    channelRead0(ctx, (P) pkt);
} finally {
    pkt.release();   // channelRead0 返回后立即释放
}
```

`channelRead0` 内部同步调用链最终到达 `TcpHandshaker.tcp_conn_request`，在两处异步 lambda 中直接持有了 `pkt` 引用，**没有 retain**：

```java
// TcpHandshaker.java — 上游关闭监听（包括连接成功后再断开）
req.childCloseListener = new ChannelFutureListener() {
    public void operationComplete(ChannelFuture future) {
        rsk_ops.send_reset(net, parent, pkt, -100);  // pkt 已被 release
    }
};

// TcpHandshaker.java — 上游连接失败监听（connect timeout / refused）
.addListener(new ChannelFutureListener() {
    public void operationComplete(ChannelFuture future) {
        rsk_ops.send_reset(net, parent, pkt, -88);   // pkt 已被 release
    }
});
```

异步回调触发时（尤其是 connect timeout 场景），`pkt` 的底层 ByteBuf 已被 Netty 池回收，读到脏数据：

| 读取字段 | 脏数据后果 |
|---|---|
| `pkt.tcpSrcPort()` / `tcpDstPort()` | RST 发往错误端口，客户端收不到 |
| `pkt.srcAddr()` / `dstAddr()` | RST 发往错误 IP |
| `pkt.isAck()` | 走错分支，RST flags / SEQ 完全错误 |

**修复方案**：在 `TcpHandshaker` 中捕获 `pkt` 进入 async lambda 之前先 retain，回调结束后 release。

```java
// 在进入 async 作用域前 retain，每个 lambda 独立持有引用
pkt.buf().retain();   // 或通过 IpPacketBuf.retainedWrap 重新包装

req.childCloseListener = new ChannelFutureListener() {
    public void operationComplete(ChannelFuture future) {
        try {
            rsk_ops.send_reset(net, parent, pkt, -100);
            demultiplexer.inet_csk_destroy_sock(req);
        } finally {
            pkt.release();   // 回调结束后释放
        }
    }
};

// connect 失败 listener 同理
.addListener(new ChannelFutureListener() {
    public void operationComplete(ChannelFuture future) {
        if (future.isSuccess()) {
            af_ops.send_synack(net, parent, req, pkt);
        } else {
            try {
                rsk_ops.send_reset(net, parent, pkt, -88);
                demultiplexer.inet_csk_destroy_sock(req);
            } finally {
                pkt.release();
            }
        }
    }
});
```

> 注意：两个 lambda 共享同一个 `pkt`，retain 一次只够一个 lambda 使用。若两个 lambda 都可能触发，需要 retain 两次（每个 lambda 各 retain 一次）。实际上 connect listener 与 closeFuture listener 语义上互斥（connect 失败则 close 也不会再携带有效连接），可梳理清楚后决定 retain 次数。

---

### 根本原因二：`childCloseListener` 使用了过时的 `pkt`（次要）

即使 ByteBuf 仍有效（例如快速失败场景），当上游连接**已建立后再断开**时，客户端已处于 `ESTABLISHED` 状态。此时 `childCloseListener` 仍用原始 SYN 的 `pkt` 构造 RST：

- `pkt.isAck()` = false（SYN 无 ACK）→ else 分支 → **SEQ = 0**
- RFC 793：`ESTABLISHED` 状态下客户端只接受 `SEQ ∈ [RCV.NXT, RCV.NXT + RCV.WND)` 的 RST
- SEQ=0 几乎必然在窗口之外 → **RST 被客户端静默丢弃** → 客户端持续重传

**修复方案**：`childCloseListener` 触发时（连接已建立），需要向 `send_reset` 传入**当前连接最新的 pkt**（携带正确的 snd_nxt / rcv_nxt 上下文），而非原始 SYN。或者通过 `TcpSock` 中保存的序列号信息直接构造 RST，不依赖 `pkt`。

---

### `tcp_v4_send_reset` RST 构造逻辑本身是否正确？

**是的，符合 RFC 793。**

| 来包类型 | 发出的 RST | 是否正确 |
|---|---|---|
| 有 ACK（已建立连接的数据包）| RST，SEQ = 来包 ackNum，无 ACK flag | ✓ |
| 无 ACK（SYN）| RST+ACK，SEQ=0，ACK = ISN_C+1 | ✓（RFC 793 §3.4）|

flags 序列化 `buildTcpFlags` 亦正确（RST=0x04，ACK=0x10）。问题不在于 RST 本身的构造，而在于调用时 `pkt` 携带了错误数据。

---

**状态**：待修复  
**优先级**：高（影响所有上游连接失败场景的连接释放）
