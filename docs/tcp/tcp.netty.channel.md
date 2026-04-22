# v2 TCP → Netty Channel 桥接(路径 B:自定义 `AbstractChannel`)

> 目标:把每条 v2 TCP 连接(`TcpSock`)暴露为一个完整的 Netty `Channel`,允许
> 应用层按 Netty 标准方式挂 `ChannelPipeline` / `ChannelHandler`(HTTP、WebSocket、
> 业务编解码等)而无需关心底层 TUN/IP 包路径。
>
> 本方案对应选型的"路径 B",相较 `EmbeddedChannel` 包装(路径 A),提供完整的
> `EventLoop` / 背压 / writability / register 生命周期语义。
>
> 实现已落地:`com.github.pangolin.routing.acceptor.tun.net.v2.tcp.netty.{TcpChannel,
> TcpChannelConfig, TcpChannelFactory}`。v1(`handler/tcp`)完全独立,本方案只触及 v2。

---

## 一、背景与现状

### 1.1 v2 扩展模型(P1/P2 后的最终形态)

v2 的协议栈核心(`core` 包)不感知 Netty,所有上层对接通过 **两个接口**:

| 接口 | 角色 | 调用时机 |
|------|------|----------|
| `TcpSockInitializer` | 一次性**装配钩子** | SYN/握手/ESTABLISHED/req 销毁四个生命周期点 |
| `TcpSockHandler` | 挂在 `sock.handler()` 的**长期事件回调** | 入站数据 / 对端 FIN / RST / writability / sock 销毁 |

核心包对接面:

| 面 | 入口 | 关键坐标 |
|----|------|----------|
| 一次性装配 | `TcpSockInitializer.onEstablished(sock, mux)` | `TcpMultiplexer.tcp_init_transfer` 内调用 — 实现方 **必须** 挂 `TcpSockHandler` |
| 入站 payload 交付 | `TcpSockHandler.onInboundData(ByteBuf)` | `TcpMultiplexer.consume` 单一出口,`sk.handler()` 为空时防御性 release |
| 出站 payload 入队 | `TcpMultiplexer.tcp_sendmsg(sk, data, flush)` | 对齐 Linux `tcp_sendmsg` — 自动跨 EL 跳转 + MSS 切片 + `tcp_push_pending_frames`,调用方不关心 MSS |
| 对端 FIN | `TcpSockHandler.onPeerFin()` | `TcpMultiplexer.tcp_fin` 状态机推进后 |
| RST | `TcpSockHandler.onReset(Throwable)` | `tcp_reset` 路径(本端/对端 RST) |
| Writability | `TcpSockHandler.onWritabilityChanged()` | ACK 推进或发送窗口松动后(钩子当前尚未全链路接入,见 §6.3) |
| Sock 销毁 | `TcpSockHandler.onSocketDestroyed()` | `inet_csk_destroy_sock` 最末路径,用于 `pipeline.fireChannelInactive` |
| EventLoop | `TcpSock.eventLoop()` | 由 `TcpSockInitializer.proposeEventLoop` 推荐,回退 `tcpGroup.next()`;所有入站处理与定时器均绑定该 EL |

### 1.2 `TcpChannelFactory` 的定位

`TcpChannelFactory` **本身就是一种 `TcpSockInitializer`**(`extends TcpSockInitializer`):

```java
@FunctionalInterface
public interface TcpChannelFactory extends TcpSockInitializer {
    TcpChannel create(TcpSock sock, TcpMultiplexer multiplexer);

    @Override
    default void onEstablished(TcpSock sock, TcpMultiplexer multiplexer) {
        TcpChannel ch = create(sock, multiplexer);        // 用户工厂已在 pipeline 上挂 handler
        sock.eventLoop().register(ch);                     // 失败则 closeForcibly
    }
}
```

另一类 initializer 是 `ext.backend.TcpPassthroughInitializer`:覆盖 `onRequest` 先连
backend 再发 SYN-ACK,`onEstablished` 挂 `TcpPassthroughHandler` 做透传。两者是
**并列关系**,而不是像旧设计那样"userChannel vs childChannel 三向分流"。

---

## 二、数据通路

```
    TUN 入站
      │
      ▼
   Tcp4Multiplexer.tcp_v4_rcv
      │
      ▼  (根据 __inet_lookup_skb 结果派发到 sock EL)
      │
   tcp_v4_do_rcv → tcp_rcv_state_process → tcp_data_queue → queue_and_out
      │
      ▼
   TcpReceiveBuffer.offer   (推进 rcv_nxt + OFO 消化)
      │
      ▼
   TcpMultiplexer.consume(sk, data)        ← 单一出口
      │
      ▼
   sk.handler().onInboundData(data)        ← TcpSockHandler 契约
      │
      ▼  (TcpChannel$BridgeImpl)
   TcpChannel.fireChannelReadFromTcp(data)  ← autoRead / pendingInbound 门控
      │
      ▼
   pipeline.fireChannelRead(data)           ← 用户 handler 链
      │
      │   ctx.writeAndFlush(out)
      ▼
   TcpChannel.doWrite(ChannelOutboundBuffer)
      │  (直接 multiplexer.tcp_sendmsg,MSS 切片在栈内)
      ▼
   TcpSock.tcp_queue_skb → tcp_push_pending_frames
      │
      ▼
   tcp_write_xmit → TUN 出站
```

---

## 三、生命周期

```
Tcp4Multiplexer.tcp_v4_conn_request
      │  (addToHalfQueue + req.synPacket retain + req.net(net))
      ▼
   initializer.onRequest(req, this)              ← 默认立发 SYN-ACK
      │                                              (TcpPassthroughInitializer 覆盖为异步)
      ▼
   tcp_check_req + syn_recv_sock (TUN EL)
      │  ├─ initializer.proposeEventLoop(req, this) → child sock 的 EL
      │  └─ buildChildSock (TcpHandshaker)
      ▼
   moveToEstablished (TUN EL)
      │  (synPacket release, synRegistry→establishedRegistry)
      ▼
   切 sock.eventLoop() 执行 tcp_v4_do_rcv
      │
   tcp_try_establish → TCP_ESTABLISHED
      │
   tcp_init_transfer
      │  └─ initializer.onEstablished(sock, this)  ← TcpChannelFactory 默认实现:
      │       ├─ ch = factory.create(sock, mux)      · 用户在 pipeline 挂 handler
      │       ├─ sock.handler(new BridgeImpl())      · TcpChannel 构造时内挂
      │       └─ sock.eventLoop().register(ch)       · register + fireChannelActive
      ▼
   [稳态,双向通信]
      │
      ▼
   对端 FIN → tcp_fin 分支 → sock.handler().onPeerFin()
              → TcpChannel BridgeImpl 触发 ChannelInputShutdownEvent
   对端 RST → tcp_reset → sock.handler().onReset(cause)
              → fireExceptionCaught + unsafe().closeForcibly
   主动关  → user channel.close() → doClose 发 FIN(若可写)+ sock.handler(null)
   被动销毁 → inet_csk_destroy_sock → sock.close() → handler.onSocketDestroyed()
              → closeForcibly(兜底)
```

---

## 四、核心类清单

| 类 | 角色 | 位置 | 状态 |
|----|------|------|------|
| `TcpChannel` | `AbstractChannel` 子类,代表已建立的 TCP 连接 | `v2/tcp/netty/TcpChannel.java` | ✅ 已落地 |
| `TcpChannel$TcpChannelUnsafe` | `AbstractUnsafe` 子类 — 禁用 `connect`(passive-open 语义) | 同上 | ✅ 已落地 |
| `TcpChannel$BridgeImpl` | `TcpSockHandler` 内联实现 — 把 core 事件转成 pipeline 事件 | 同上 | ✅ 已落地 |
| `TcpChannelConfig` | `DefaultChannelConfig` 子类,映射 `SO_KEEPALIVE` / `TCP_NODELAY` 到 `TcpSock` | `v2/tcp/netty/TcpChannelConfig.java` | ✅ 已落地 |
| `TcpChannelFactory` | `TcpSockInitializer` 的函数式派生,默认实现负责 register | `v2/tcp/netty/TcpChannelFactory.java` | ✅ 已落地 |
| `TcpServerChannel`(P5) | `AbstractServerChannel` 子类,让 `ServerBootstrap` 风格可用 | 未来 | 🚧 见 §6.7 |

`localAddress0()/remoteAddress0()` 直接用 `FourTuple.{dstInetAddress,srcInetAddress}`
构造 `InetSocketAddress`,不引入独立包装类 —— 地址语义是 Netty 层关注点,不应
下沉到协议栈 core。

---

## 五、关键实现约定

### 5.1 EventLoop 绑定

**硬约束**:`TcpChannel.eventLoop() == sock.eventLoop()`,由 `isCompatible` 守护:

```java
@Override
protected boolean isCompatible(EventLoop loop) {
    return loop == sock.eventLoop();
}
```

`sock.eventLoop()` 在 `tcp_v4_syn_recv_sock` 阶段由
`initializer.proposeEventLoop(req, this)` 推荐;`TcpChannelFactory` 默认不覆盖
该钩子,回退到 `tcpGroup.next()`(`tcpGroup==null` 时再退回 TUN EL)。

所有 `TcpSock` 字段访问、定时器调度、`tcp_sendmsg` 都假定在这个 EL 上;跨线程
写入由 `tcp_sendmsg` 外层 `owner.execute(...)` 跳转到 `tcp_sendmsg_locked`
(对齐 Linux `lock_sock / tcp_sendmsg_locked` 两段式)。`TcpChannel.doWrite` 直接
复用这条路径,无需自己做 EL 切换。

### 5.2 读路径(入站)

#### 5.2.1 `consume` 单一出口

```java
protected void consume(TcpSock sk, ByteBuf data) {
    TcpSockHandler handler = sk == null ? null : sk.handler();
    if (handler == null) {
        data.release();   // destroy 途中 / 未装配 — 防御性 release,避免泄漏
        return;
    }
    handler.onInboundData(data);
}
```

`TcpChannel.BridgeImpl.onInboundData` 直接转 `fireChannelReadFromTcp(data)`:

```java
void fireChannelReadFromTcp(ByteBuf data) {
    assert eventLoop().inEventLoop();
    if (closing || inputShutdown) {
        data.release();
        return;
    }
    if (!config.isAutoRead() && !readRequested) {
        pendingInbound.addLast(data);
        sock.rcvPaused(true);   // 触发下一次 tcp_select_window 缩窗
        return;
    }
    readRequested = false;
    pipeline().fireChannelRead(data);
    pipeline().fireChannelReadComplete();
}
```

#### 5.2.2 autoRead / 背压

- `autoRead=true`(默认):到数据立即 `fireChannelRead`,用户 handler 同步消费。
- `autoRead=false`:数据暂存到 `pendingInbound: ArrayDeque<ByteBuf>`;**同时**
  `sock.rcvPaused(true)` 抑制 `TcpReceiveBuffer.readAll` 的排水,从而使
  `tcp_receive_window()` 缩窄,对端自然感知到反压。
- `doBeginRead()`:drain 积压,若为空且 `sock.receiveBuffer().isReadable()`
  则放开 `rcvPaused` 让下一次 `tcp_data_queue` 驱动 `consume`。

`sk.rcvPaused` 是 `TcpSock` 的布尔槽;`queue_and_out` 在 `rcvPaused=true` 时仍把
skb 放进 `receiveBuffer`(保持 rcv_nxt 推进的前提 —— **否则违反 TCP 语义**),
**但不**调用 `consume`,让数据滞留接收缓冲,体现为下次窗口通告的 `rcv_wnd` 收缩。

> TCP 反压语义是"接收方慢就缩窗",与 `autoRead=false` 的 Netty 语义一致,但
> 实现要点在 `rcv_wnd` 而非直接停读 —— 简单的入站队列停读不足以通知对端。

#### 5.2.3 ByteBuf 生命周期

`consume → onInboundData → fireChannelRead(data)` 之后 pipeline 的**末端** handler
(或 `SimpleChannelInboundHandler`)负责 release。若用户 handler 忘记 release,
Netty `ResourceLeakDetector` 会抓到 —— 这是标准 Netty 契约。

`pendingInbound` 队列里未被消费的 buf,在 `doClose` 通过
`drainPendingInboundReleasing()` 统一 release;`closing` 或 `inputShutdown` 触发
时,`fireChannelReadFromTcp` 直接 release 新到的段。

### 5.3 写路径(出站)

#### 5.3.1 `doWrite`

```java
@Override
protected void doWrite(ChannelOutboundBuffer in) throws Exception {
    if (outputShutdown || !sock.hasConnection() || !sock.state().canSend()) {
        for (;;) {
            Object msg = in.current();
            if (msg == null) break;
            in.remove(new ClosedChannelException());
        }
        return;
    }
    for (;;) {
        Object msg = in.current();
        if (msg == null) break;
        if (!(msg instanceof ByteBuf)) {
            in.remove(new UnsupportedOperationException("TcpChannel only accepts ByteBuf"));
            continue;
        }
        ByteBuf buf = (ByteBuf) msg;
        if (buf.readableBytes() == 0) { in.remove(); continue; }
        boolean flush = in.size() == 1;
        multiplexer.tcp_sendmsg(sock, buf.retain(), flush);
        in.remove();
    }
    evaluateWritability();
}
```

要点:

- `tcp_sendmsg` 对齐 Linux 的 `tcp_sendmsg` 外层:EL 跳转 + 调 `tcp_sendmsg_locked`,
  由后者完成 MSS 切片(每 MSS 一个 skb)+ `tcp_push_pending_frames`;
- 调用方把 `buf.retain()` 的所有权转给 `tcp_sendmsg`,原 buf 由 `in.remove()` release;
- `tcp_sendmsg_locked` 在 `finally` 里 release 入参,状态不可写 / 长度 0 / 切片完成后
  都能正确释放,调用方无泄漏风险;
- 不支持非 `ByteBuf` 消息(`FileRegion` 没有零拷贝内核路径,收益有限)。

#### 5.3.2 Writability 水位

`TcpChannel.evaluateWritability` 用 `TcpSendBuffer.pendingBytes()`(未发送 + 已发送
未 ACK 合计字节)与 `config.getWriteBufferWaterMark()` 比对,超 high 调
`setUserDefinedWritability(1, false)`,降 low 以下调 `(1, true)`:

```java
long pending = sock.sendBuffer().pendingBytes();
int high = config.getWriteBufferHighWaterMark();
int low  = config.getWriteBufferLowWaterMark();
ChannelOutboundBuffer cob = unsafe().outboundBuffer();
if (lastWritable && pending >= high) {
    cob.setUserDefinedWritability(1, false);
    lastWritable = false;
} else if (!lastWritable && pending <= low) {
    cob.setUserDefinedWritability(1, true);
    lastWritable = true;
}
```

入口两个:`doWrite` 末尾,和 `BridgeImpl.onWritabilityChanged`(ACK 推进后由核心
回调,见 §6.3)。

### 5.4 Close / Shutdown

#### 5.4.1 主动关闭(用户调 `channel.close()`)

```java
@Override
protected void doClose() throws Exception {
    if (closing) return;
    closing = true;
    if (!abortive && sock.hasConnection() && sock.state().canSend()) {
        if (multiplexer.tcp_close_state(sock)) {
            TcpOutput.INSTANCE.tcp_send_fin(sock);
        }
    }
    drainPendingInboundReleasing();
    if (sock.handler() != null) {
        sock.handler(null);   // 解绑,让 inet_csk_destroy_sock 不再回调本 channel
    }
}
```

`abortive=true` 时跳过 FIN(RST 接收路径或 `onSocketDestroyed` 已设此标志),避免
在已被对端 RST 的连接上再次写入。

#### 5.4.2 被动关闭

- **对端 FIN**:`tcp_fin` 推进状态机后 `notifyPeerFin(sk)` → `handler.onPeerFin()`。
  `BridgeImpl` 置 `inputShutdown=true` 并 `fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE)`;
  channel 不立即关,应用仍可写(CLOSE_WAIT)。
- **对端 RST / 本端发 RST**:`handler.onReset(cause)` → `BridgeImpl` 置
  `abortive=true`、`fireExceptionCaught`、`unsafe().closeForcibly()`。
- **`inet_csk_destroy_sock(sock)`**:最终 sock 销毁路径调 `handler.onSocketDestroyed()`
  → `BridgeImpl` `closeForcibly()` 兜底,保证即使前面路径漏调也能回收 channel。

#### 5.4.3 半关闭

当前 `inputShutdown` / `outputShutdown` 两个布尔标志已经预留,但对外的
`shutdownOutput()` / `shutdownInput()` 方法尚未声明(`AbstractChannel` 默认不
暴露)。需要时参考 `NioSocketChannel` 实现 `ChannelShutdownSupport` 并映射到:

- `shutdownOutput()` → `tcp_close_state + tcp_send_fin`,置 `outputShutdown=true`,
  `fireUserEventTriggered(ChannelOutputShutdownEvent.INSTANCE)`;
- `shutdownInput()` → 置 `inputShutdown=true + sock.rcvPaused(true)`,后续 inbound
  段直接丢(但 ACK 仍发,避免对端卡窗),`fire ChannelInputShutdownReadComplete`。

### 5.5 `isActive` / `isOpen` 契约

```java
@Override public boolean isOpen()   { return !closing; }

@Override public boolean isActive() {
    if (closing || !isRegistered()) return false;
    TcpConnectionState st = sock.state();
    switch (st) {
        case TCP_ESTABLISHED:
        case FIN_WAIT_1:
        case FIN_WAIT_2:
        case CLOSE_WAIT:
        case CLOSING:
        case LAST_ACK:
            return sock.hasConnection();
        default:
            return false;
    }
}
```

对齐 Linux `TCPF_ESTABLISHED | TCPF_FIN_WAIT1 | TCPF_FIN_WAIT2 | TCPF_CLOSE_WAIT |
TCPF_CLOSING | TCPF_LAST_ACK`。

---

## 六、关键挑战与风险

### 6.1 EventLoop 一致性(⚠️ 最高优)

所有 `TcpSock` 字段访问都必须在 `sock.eventLoop()`。用户在 handler 里
`ctx.writeAndFlush` 天然在 channel EL(= sock EL),安全;跨线程调
`channel.writeAndFlush` 时 `AbstractChannel` 会走 `eventLoop().execute` 跳转,
到 `doWrite` 时已经在正确 EL,也安全。

**反模式**:用户在业务线程池里直接 `sock.sendBuffer().enqueue(...)` 绕过 Netty ——
文档里必须禁掉。

### 6.2 `TcpChannelFactory` 与 `TcpPassthroughInitializer` 的互斥

两者都是 `TcpSockInitializer` 的实现,一个 `TcpMultiplexer` 构造时只传一个。如
需要"pipeline 处理完再透传 backend",应写一个新的 `TcpSockInitializer` 实现:
`onEstablished` 里既建 `TcpChannel` 又连 backend,在 pipeline 末尾挂
`BackendForwardHandler`。不在本方案骨架内强制。

### 6.3 Writability 钩子的全链路接入(🚧 未完成)

`TcpSockHandler.onWritabilityChanged` 的触发点应在 `tcp_clean_rtx_queue` 推进
`snd_una` 或 `tcp_data_snd_check` 释放发送窗口之后。当前 core 代码**尚未**在这
两处调用 `handler.onWritabilityChanged()`,所以 `BridgeImpl.onWritabilityChanged`
实际上只会被 `doWrite` 末尾的主动调用触发。

**需要补的钩子点**:
- `TcpAck.tcp_clean_rtx_queue` 扣减 `sendBuffer.unackedBytes()` 后;
- `TcpSendBuffer` 在 `acknowledge` / `snd_wnd` 松动后。

补钩子时注意:必须在 sock EL 上 / 必须防止递归(回调中再次写入可能再次触发)。

### 6.4 对端 RST 与 Netty 异常语义

`tcp_reset` 路径必须**先** `handler.onReset(cause)` **再** `tcp_done`/销毁,否则
`BridgeImpl.onReset` 里的 `fireExceptionCaught` 会因 channel 已 inactive 而被
pipeline 丢弃。当前 `tcp_reset` 调用顺序需要对齐这一约束 —— 等同于 §6.3 待补。

### 6.5 FIN 到达后的 writability

对端 FIN 后本端进 `CLOSE_WAIT`,仍可写(对齐 Linux)。`isActive` 继续返回 true。
`inputShutdown` 标志用来屏蔽 inbound 而非全关,出站 `doWrite` 继续走 `canSend()`
路径。

### 6.6 Leak 风险

- `pendingInbound` 队列里的 `ByteBuf` 在 `doClose` 通过
  `drainPendingInboundReleasing()` 释放;`fireChannelReadFromTcp` 在
  `closing || inputShutdown` 时也 release 新到的段。
- `doWrite` 把 `buf.retain()` 交给 `tcp_sendmsg`,若 sock 已关闭,
  `tcp_sendmsg_locked` 的 `finally` 会 release 入参,不会泄漏。
- `TcpChannelFactory.onEstablished` 中 `register` 失败分支调
  `ch.unsafe().closeForcibly()` 回收工厂已挂 pipeline 的资源(标准
  `ReflectiveChannelFactory` 模式)。
- `consume` 单一出口:`sk.handler()==null` 时 `data.release()` 防御泄漏。

### 6.7 ServerBootstrap 集成(P5,新路线)

> **旧版方案作废**:旧 6.7 基于 `DataConsumer`/`childChannel` 双分支路线设计,
> 现路线只基于 `TcpSockInitializer` 一个扩展点,大幅简化。

**目标**:支持以下习惯用法:

```java
EventLoopGroup boss   = new NioEventLoopGroup(1);
EventLoopGroup worker = new NioEventLoopGroup();
new ServerBootstrap()
        .group(boss, worker)
        .channel(TcpServerChannel.class)
        .childHandler(new ChannelInitializer<TcpChannel>() {
            @Override protected void initChannel(TcpChannel ch) {
                ch.pipeline().addLast(new HttpServerCodec(), new MyBizHandler());
            }
        })
        .childOption(ChannelOption.SO_KEEPALIVE, true)
        .bind(tunListenAddress).sync();
```

**实现骨架**:

| 类 | 继承 | 职责 |
|----|------|------|
| `TcpServerChannel` | `AbstractServerChannel` | 绑定 `Tcp4Multiplexer`,作为 `ServerBootstrap.channel()` 的类型 |
| `TcpServerChannel$AcceptorInitializer` | `TcpSockInitializer` | 内部 initializer,替代用户直接传 `TcpChannelFactory` |
| `TcpServerChannelConfig` | `DefaultChannelConfig` | 承载 `childHandler` / `childOption` / `childAttr` |

关键改造点(与当前核心**零耦合**):

1. **新增 `TcpServerChannel`**(`v2/tcp/netty/TcpServerChannel.java`):
   - `doBind(SocketAddress)`:创建 `Tcp4Multiplexer` 并把内部 `AcceptorInitializer`
     传入,挂到 TUN pipeline。
   - `doReadMessages(List<Object> buf)`:从内部 accept queue 取出已 register 的
     `TcpChannel`,交给 `ServerBootstrapAcceptor` 走标准 `fireChannelRead` 流程。
   - `doClose()`:摘 TUN pipeline,关闭 `Tcp4Multiplexer`。

2. **`AcceptorInitializer`**:把 `TcpChannelFactory` 默认的 "create + register"
   拆成两步:
   - `onEstablished(sock, mux)`:用 `childHandler` 构建 `TcpChannel`、挂
     `BridgeImpl`、**不** register;把 channel push 进 `acceptQueue`,触发
     `TcpServerChannel.readIfIsAutoRead()`。
   - `TcpServerChannel.doReadMessages` 拉出 channel → `pipeline.fireChannelRead(ch)` →
     `ServerBootstrapAcceptor` 负责 register 到 worker EL + 应用
     `childOption`/`childAttr`。

3. **worker EL 约束的放松**:`TcpChannel.isCompatible(loop)` 当前要求
   `loop == sock.eventLoop()`。`ServerBootstrap.childGroup` 的 worker EL 和 sock
   EL 不同时,要么:
   - **方案 A(推荐)**:让 `proposeEventLoop` 从 `ServerBootstrap.childGroup.next()`
     取(初始化时由 `AcceptorInitializer` 持 childGroup 引用),保持
     `TcpChannel.eventLoop()==sock.eventLoop()` 不变,这条约束就自然成立;
   - **方案 B**:把 `isCompatible` 放松到 "同一 `EventLoopGroup`" 级别,跨 EL
     调用都走 `execute` 跳转 —— 代价是所有 sock 字段访问都要加 EL 断言,风险高。

4. **生命周期收尾**:`TcpServerChannel.doClose` 时遍历 `establishedRegistry`,
   对每个 sock 调 `sock.handler().onSocketDestroyed()`,触发 `TcpChannel`
   `closeForcibly`;worker EL 关闭由 `ServerBootstrap.childGroup()` 的生命周期
   负责,与 server channel 解耦。

**改动量估算**:`TcpServerChannel` + `Config` 约 250 行;`AcceptorInitializer` 约
80 行;总计 ~330 行,**不触碰 core 任何一个类**。只需 `TcpChannelFactory` 从
`TcpSockInitializer` 独立出来作为 P5 内部使用的 factory(或保留现状共存)。

**验收**:

- `new ServerBootstrap().channel(TcpServerChannel.class).childHandler(...)` 可跑
  `HttpHelloWorldServer` demo;
- `childOption(SO_KEEPALIVE, true)` 等配置透传到 `TcpSock`;
- 并发 10k 连接 + `ResourceLeakDetector.Level=PARANOID` 跑 10 分钟无 leak。

---

## 七、实施分阶段

| 阶段 | 产出 | 状态 | 验收 |
|------|------|------|------|
| **P0-骨架** | `TcpChannel` / `TcpChannelConfig` / `TcpChannelFactory` + `BridgeImpl` | ✅ 已落地 | `new TcpChannel(sock, mux)` 可 register 到 sock.eventLoop() |
| **P1-读路径** | `consume` 单出口 + `fireChannelReadFromTcp` + autoRead + `rcvPaused` | ✅ 已落地(autoRead 分支 + pendingInbound 背压已实现) | 本地 TUN:SYN 握手完成 → handler 收到 `channelActive` / `channelRead`;`autoRead=false` 时对端窗口收缩可观察 |
| **P2-写路径** | `doWrite` MSS 分片 + writability 水位 | ✅ 骨架落地,🚧 writability 回调源未接入 | handler `ctx.writeAndFlush` 100MB 对端顺序收齐;`isWritable` 翻转待 §6.3 补钩子后可观测 |
| **P3-close/shutdown** | doClose 发 FIN + 对端 FIN → `ChannelInputShutdownEvent` + 对端 RST → `exceptionCaught` | ✅ 主动 close / FIN / RST 三路径落地;🚧 shutdownInput/Output 显式 API 未暴露 | 四向 close 覆盖测试 pipeline 事件齐全 |
| **P4-压测 + leak** | 万连接并发 + `-Dio.netty.leakDetection.level=PARANOID` 10 min | ⏳ 待做 | 无 leak,无 EL 抢占,GC 正常 |
| **P5-ServerBootstrap** | `TcpServerChannel` + `childHandler` 风格(见 §6.7) | ⏳ 未开工 | `ServerBootstrap.channel(TcpServerChannel.class)` 跑 `HttpHelloWorldServer` |

---

## 八、使用示例

### 8.1 最小 Echo 服务

```java
TcpConfig cfg = TcpConfig.builder().mss(1460).build();
TcpChannelFactory factory = (sock, mux) -> {
    TcpChannel ch = new TcpChannel(sock, mux);
    ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
        @Override public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ctx.writeAndFlush(msg);
        }
    });
    return ch;
};
Tcp4Multiplexer mux = new Tcp4Multiplexer(cfg, factory);
// mux 挂到 TUN pipeline(TcpMultiplexHandler)
```

### 8.2 HTTP 服务

```java
TcpChannelFactory factory = (sock, mux) -> {
    TcpChannel ch = new TcpChannel(sock, mux);
    ch.pipeline().addLast(
        new HttpServerCodec(),
        new HttpObjectAggregator(65536),
        new SimpleChannelInboundHandler<FullHttpRequest>() {
            @Override protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
                ByteBuf body = Unpooled.copiedBuffer("Hello from v2 TCP over TUN!\n", StandardCharsets.UTF_8);
                FullHttpResponse resp = new DefaultFullHttpResponse(HTTP_1_1, OK, body);
                resp.headers().setInt(CONTENT_LENGTH, body.readableBytes());
                ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
            }
        });
    return ch;
};
```

### 8.3 Backend 透传(等价 v1 默认行为)

```java
TcpPassthroughInitializer initializer =
        new TcpPassthroughInitializer(socketChannelFactory, childGroup, connTimeoutMs);
Tcp4Multiplexer mux = new Tcp4Multiplexer(cfg, initializer);
```

Backend 透传与 `TcpChannelFactory` 是**并列的** `TcpSockInitializer` 实现,
构造 `Tcp4Multiplexer` 时**只能**传其一。

### 8.4 显式拒绝(no listener → RST)

```java
Tcp4Multiplexer mux = new Tcp4Multiplexer(cfg, TcpSockInitializer.DENY);
```

对齐 Linux `__inet_lookup_listener` 返回 null 时的 RST 响应语义 —— SYN 到达即发
RST 并销毁半连接 req。

### 8.5 验收清单

- [ ] `ResourceLeakDetector.Level=PARANOID` 10 分钟压测 0 leak
- [ ] 10k 并发连接 + 每连接 1MB echo 成功,内存曲线平稳
- [ ] `autoRead=false` 时 tcpdump 观察到对端 `Win=0` / 窗口收缩
- [ ] `config.setOption(SO_KEEPALIVE, true)` 等价 `sock.keepaliveEnabled(true)`
- [ ] 主动 close / 对端 FIN / 对端 RST 三路径 pipeline 事件顺序与 `NioSocketChannel` 一致
- [ ] `HttpServerCodec` + `HttpObjectAggregator` 开箱跑通

---

## 九、与 Linux 内核的对齐性

本方案是"应用接口层"改造,**不触及**协议栈逻辑,无 Linux 对齐议题。所有 TCP
行为(PAWS / 拥塞控制 / OFO / FIN 半关 / RST 响应 / keepalive / probe0 / RACK 等)
仍由 v2 核心模块承担,依旧严格对齐 Linux。

间接语义映射:

- `rcvPaused` 机制在语义上对应 Linux "应用层 read 未及时 → `tp->rcv_wnd` 收缩",
  与 `tcp_select_window` 行为一致,只是触发源从 "应用 syscall 未读 socket" 变成
  "Netty autoRead=false 未消费"。
- `TcpChannelFactory.onEstablished` 对应 Linux `accept()` 返回后应用对 fd 的
  装配,只是我们提前在内核态挂好 pipeline,不经过 `accept()` 队列的同步等待。
- `TcpPassthroughInitializer.onRequest` 异步 backend connect 对应 Linux 没有直接
  语义(v1 遗留行为),是 v2 保留的扩展点而非 Linux 对齐项。

---

**维护说明**:本方案与 `ext.backend.TcpPassthroughInitializer` 共用同一个
`TcpSockInitializer` 扩展点。二者并列、互斥,未来可能的 "pipeline + backend
透传" 组合由应用自写第三个 initializer 实现,不在 core/netty/ext.backend 任何
一个包内强制。
