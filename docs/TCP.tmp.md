# TCP 握手后接收窗口冻结方案

> 需求：三次握手成功后，异步执行某个操作（如后端连接建立），操作完成前阻止客户端发送任何数据。
> 方案：通过将本端通告接收窗口设为 0 实现，操作完成后恢复窗口并发送 Window Update ACK。

---

## 一、原理

TCP 零窗口是标准的流量控制机制（RFC 9293 §3.8）：

- 本端在 ACK 报文中将 `window` 字段设为 0，对端**必须停止发送数据**
- 对端会启动零窗口探测（Zero Window Probe，ZWP），定期发送 1 字节试探包
- 本端回复 `win=0` 的 ACK 维持冻结状态，直到发出 `win>0` 的 Window Update
- 对端收到 Window Update 后立即恢复发送（包括重传探测期间累积的数据）

收到数据时**正常入队**（推进 `rcv_nxt`、正常 ACK），只是把通告窗口压为 0 阻止对端继续发送。
这与 Linux 内核缩小 `sk_rcvbuf` 的限流机制完全相同。

### 时序图

```
Client                     Pangolin                   Backend
  |                           |                           |
  |---SYN------------------->|                           |
  |<--SYN-ACK (win=N)--------|                           |
  |---ACK------------------->| ← 握手完成                |
  |                           |  rcvWndFrozen = true      |
  |                           |---CONNECT async---------->|
  |                           |                           |
  | (Client 想发数据)          |                           |
  |---DATA------------------>|                           |
  |<--ACK (win=0)------------|  ← 阻止继续发送            |
  |                           |                           |
  |---ZWP------------------->|  ← 零窗口探测              |
  |<--ACK (win=0)------------|                           |
  |                           |<--CONNECTED--------------|
  |                           |  rcvWndFrozen = false     |
  |                           |  tcp_send_ack(win=N) ─────┤
  |<--ACK (win=N)------------|  ← 窗口恢复               |
  |---DATA (resume)--------->|                           |
```

---

## 二、实现方案

### 2.1 `TcpSock` — 新增标志位

**文件**：`sock/TcpSock.java`，在 `rcv_wnd` 字段之后添加：

```java
/**
 * When true, {@code tcp_select_window()} always returns 0 so that all outgoing
 * segments advertise a zero receive window.  The remote peer will stop sending
 * data (keeping only periodic ZWP probes).
 *
 * Set this flag before starting a post-handshake async operation;
 * clear it (via {@code tcp_unfreeze_rcv_wnd}) once the operation completes.
 *
 * Must be written from the channel's event-loop thread or with a
 * happens-before guarantee (hence volatile).
 */
public volatile boolean rcvWndFrozen;
```

`volatile` 的必要性：freeze 在前端 EventLoop 写，unfreeze 在异步回调（可能是后端连接 EventLoop）中清，跨线程可见性必须保证。

---

### 2.2 `TcpOutput.tcp_select_window()` — frozen 时返回 0

**文件**：`core/TcpOutput.java`，`tcp_select_window()` 方法入口处添加短路逻辑：

```java
int tcp_select_window(final TcpSock tp) {
    // Zero-window freeze: advertise 0 without touching rcv_wnd / rcv_wup
    // so the window can be fully restored when the freeze is lifted.
    if (tp.rcvWndFrozen) {
        return 0;
    }
    // ... 原有逻辑不变 ...
}
```

**为什么不能更新 `rcv_wnd`/`rcv_wup`**：

原逻辑在返回 0 时仍会执行 `tp.rcv_wnd = 0` 和 `tp.rcv_wup = tp.rcv_nxt`。
如果这样做，unfreeze 后第一次调用 `tcp_select_window` 会把 `old_win=0` 带入
"never shrink window" 保护逻辑，导致窗口无法正常恢复。
提前 `return 0` 跳过这两行，解冻后窗口完全由 `__tcp_select_window()` 重新计算，干净恢复。

---

### 2.3 `TcpOutput` — 新增 `tcp_unfreeze_rcv_wnd()`

**文件**：`core/TcpOutput.java`，紧跟 `tcp_send_ack()` 之后添加：

```java
/**
 * Lift the zero-window freeze and immediately send a window-update ACK
 * so the remote peer can resume transmission.
 *
 * Must be called from the channel's event-loop, or submitted via:
 *   channel.eventLoop().execute(() -> tcp_unfreeze_rcv_wnd(net, sk));
 */
public void tcp_unfreeze_rcv_wnd(final Channel net, final TcpSock tp) {
    tp.rcvWndFrozen = false;
    tcp_send_ack(net, tp);   // advertises the restored window immediately
}
```

---

### 2.4 `TcpInput.tcp_init_transfer()` — freeze 及 unfreeze 时机

**文件**：`core/TcpInput.java`

在 `innerChannel(sk).pipeline().addLast(...)` 调用**之前**设置 freeze，
在 `ChannelInboundHandlerAdapter.channelActive()` 回调（后端连接就绪）里 unfreeze：

```java
// 握手完成，后端 pipeline 尚未就绪 —— 冻结接收窗口
sk.rcvWndFrozen = true;

innerChannel(sk).closeFuture()
    .addListener(sk.childCloseListener)
    .removeListener(handshakeCloseListener)
    .channel().pipeline().addLast(new ChannelInboundHandlerAdapter() {

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            // 后端连接就绪 —— 切回前端 EventLoop 解冻，避免跨线程竞态
            net.eventLoop().execute(() -> output.tcp_unfreeze_rcv_wnd(net, sk));
            ctx.fireChannelActive();
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            // ... 原有转发逻辑不变 ...
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            // ... 原有异常处理不变 ...
        }
    });
```

---

## 三、设计决策说明

### 为什么用 `volatile` 而不是 `AtomicBoolean`？

`volatile boolean` 读写是原子的，且此处只需要可见性保证（无 CAS 需求），`volatile` 足够且开销更低。

### ZWP 探测期间的处理

freeze 期间客户端发出的 ZWP 探测包会被正常处理：
- `tcp_rcv_established()` → `tcp_ack()` 推进 `snd_una`
- `tcp_send_ack()` 回复 `win=0` 的 ACK（因为 `tcp_select_window` 返回 0）
- 无需特殊处理，标准 TCP 路径即可正确维持零窗口状态

### 冻结期间收到数据怎么办？

正常入队（`tcp_data_queue`），推进 `rcv_nxt`，回复 `win=0` 的 ACK。
不丢弃的原因：窗口是建议而非强制，慢启动、对端 bug、飞行中的数据等情况下对端可能仍会发少量数据。
正确入队后，unfreeze 时窗口恢复，上层读取队列数据即可，不需要对端重传。

### 若后端连接失败（`exceptionCaught` / `channelInactive` without `channelActive`）

需在 `exceptionCaught` 中同样执行 `tcp_send_active_reset` 并清理，`rcvWndFrozen` 随 socket 销毁自然失效，无需单独清理。

---

## 四、涉及文件汇总

| 文件 | 改动位置 | 说明 |
|------|----------|------|
| `sock/TcpSock.java` | `rcv_wnd` 字段之后 | 添加 `volatile boolean rcvWndFrozen` |
| `core/TcpOutput.java` | `tcp_select_window()` 入口 | frozen 时提前 `return 0` |
| `core/TcpOutput.java` | `tcp_send_ack()` 之后 | 添加 `tcp_unfreeze_rcv_wnd()` |
| `core/TcpInput.java` | `tcp_init_transfer()` 内 | freeze + `channelActive` 回调中 unfreeze |
