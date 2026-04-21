# v2 TCP → Netty Channel 桥接改造方案(路径 B:自定义 `AbstractChannel`)

> 目标:为 `com.github.pangolin.routing.acceptor.tun.net.v2.tcp.core.TcpSock` 写一个
> 继承 `io.netty.channel.AbstractChannel` 的宿主类型 `TcpChannel`,把每个 v2 TCP
> 连接暴露为一个完整的 Netty `Channel`,从而允许应用层按 Netty 标准方式挂
> `ChannelPipeline` / `ChannelHandler`(编解码、HTTP、WebSocket 等)而无需关心
> 底层 TUN/IP 包路径。
>
> 本方案对应用户选型的"路径 B",相较 `EmbeddedChannel` 包装(路径 A),
> 提供完整的 `EventLoop` / 背压 / writability / register 生命周期语义。
>
> v1(`com.github.pangolin.routing.acceptor.tun.net.handler.tcp`)与本方案
> **完全独立**,本方案只触及 v2 代码。

---

## 一、背景与现状盘点

### 1.1 v2 当前对外接口

经源码复核(2026-04-21)确定以下 5 个关键对接面:

| 面 | 入口 | 关键坐标 |
|----|------|----------|
| 入站 payload 交付 | `TcpMultiplexer.consume(sk, data)` | `TcpMultiplexer.java:966-972` — 若 `sk.hasBackendChannel()` 则 `childChannel.writeAndFlush`,否则回落到 `DataConsumer.onData` |
| 出站 payload 入队 | `TcpMultiplexer.enqueueWrite(sk, data, flush)` | `TcpMultiplexer.java:974-997` — 自动跨 EL 跳转,MSS 分片由调用方负责,`flush=true` 触发 `tcp_push_pending_frames` |
| 连接关闭 | `TcpSock.close()` + `sk.childCloseListener` | `TcpMultiplexer.java:773-778` — child channel 关闭时若 `tcp_close_state` 允许则 `tcp_send_fin` |
| EventLoop 绑定 | `TcpSock.channel.eventLoop()` | `TcpSock.java:576`;所有入站处理(`tcp_rcv → tcp_v4_do_rcv → tcp_rcv_state_process`)与定时器(`TcpTimerScheduler`)均绑定该 EL |
| 后端 childChannel | `TcpSock.childChannel` | `Tcp4Multiplexer.java:501-504` 建立,`TcpMultiplexer.java:784-812` 安装 pipeline 做"后端读→v2 写"透传 |

### 1.2 现 childChannel 的角色

当前 `childChannel` 是**出站到 backend 的真实 socket**(通过 `SocketChannelFactory.open()`
在 SYN 到达时即建连),其作用有三:

1. 作为 backend 载荷通路 — v2 收到的 payload 直接 `writeAndFlush` 给它;
2. 承载 close 语义 — close 时反向触发 v2 `tcp_send_fin`;
3. 暴露后端异常 — `exceptionCaught` 时 v2 发 RST 并 `tcp_done`。

**本方案不废除 `childChannel`**,而是在 `TcpSock` 上增加**平行的** `TcpChannel` 槽位
(`sk.userChannel()`),用户可选:

- **模式 A**(现状):只有 `childChannel`,做 backend 透传;
- **模式 B**(新增):只有 `userChannel`,挂用户 pipeline;
- **模式 C**(进阶):两者并存 — `userChannel` 的 handler 处理完后仍可 `ctx.writeAndFlush(msg)` 到 backend。

模式 C 由 `userChannel.pipeline()` 尾部接一个 `BackendForwardHandler` 实现,不在
本方案骨架内强制。

---

## 二、目标设计总览

### 2.1 数据通路(模式 B)

```
    TUN 入站
      │
      ▼
   Tcp4Multiplexer.tcp_v4_rcv
      │
      ▼
   tcp_rcv_state_process ── (ACK/数据/FIN 处理)
      │
      ▼
   TcpReceiveBuffer.offer  ←─ rcv_nxt 推进
      │
      ▼
   TcpMultiplexer.consume(sk, data)           ← 分流点
      │
      ▼
   sk.userChannel().pipeline()                ← 本方案注入
      │  .fireChannelRead(data)
      ▼
   [用户的 ChannelHandler 链:HttpServerCodec / YourBizHandler / ...]
      │
      │   ctx.writeAndFlush(out)
      ▼
   TcpChannel.doWrite(ChannelOutboundBuffer)
      │
      ▼
   TcpMultiplexer.enqueueWrite(sk, slice, flush)
      │
      ▼
   TcpSock.tcp_queue_skb → tcp_push_pending_frames
      │
      ▼
   tcp_write_xmit → TUN 出站
```

### 2.2 生命周期

```
Tcp4Multiplexer.tcp_v4_syn_recv_sock
      │
      ▼
   buildChildSock (TcpHandshaker:265)
      │
      ▼
   init(TcpSock)                     ← 本方案切入点
      │  └─ factory.createUserChannel(sk)  ← 若配置 TcpChannelFactory
      │         ├─ new TcpChannel(sk, multiplexer)
      │         ├─ initializer.initChannel(ch)    ← 用户装 handler
      │         ├─ sk.eventLoop().register(ch)
      │         └─ ch.pipeline().fireChannelActive()
      ▼
   moveToEstablished
      │
   [稳态,双向通信]
      │
      ▼
   对端 FIN / 对端 RST / 本端 tcp_done
      │
      ▼
   TcpSock.close()
      │
      └─ userChannel.unsafe().closeForcibly() / close()
            └─ pipeline.fireChannelInactive + fireChannelUnregistered
```

---

## 三、核心类清单

| 类 | 角色 | 文件 | 行数估算 |
|----|------|------|---------|
| `TcpChannel` | `AbstractChannel` 子类,代表已建立的 TCP 连接 | 新增 `v2/tcp/netty/TcpChannel.java` | ~350 |
| `TcpChannel$TcpChannelUnsafe` | `AbstractUnsafe` 子类,内部类 | 同上 | ~120 |
| `TcpChannelConfig` | `DefaultChannelConfig` 子类,承载 TCP options(TCP_NODELAY / SO_KEEPALIVE / write watermark) | 新增 `v2/tcp/netty/TcpChannelConfig.java` | ~80 |
| `TcpChannelFactory` | 工厂接口:`TcpChannel create(TcpSock sk, ChannelInitializer<TcpChannel> init)` | 新增 `v2/tcp/netty/TcpChannelFactory.java` | ~30 |
| `TcpServerChannel`(**P1**) | `AbstractServerChannel` 子类,包 `Tcp4Multiplexer`,让 `ServerBootstrap` 风格可用 | 后续阶段 | ~200 |

> `localAddress0()/remoteAddress0()` 直接在 `TcpChannel` 里用 `FourTuple.dstInetAddress()/srcInetAddress()` 构造 `java.net.InetSocketAddress`,不再引入独立的 `TcpSocketAddress` 包装类 —— 地址语义是 Netty 层关注点,不应下沉到协议栈 core 层。

总骨架约 580 行(不含 `TcpServerChannel`)。

---

## 四、关键实现约定

### 4.1 EventLoop 绑定

**硬约束**:`TcpChannel.eventLoop() == sock.eventLoop() == TUN channel.eventLoop()`。
所有 `TcpSock` 字段访问、定时器调度、`enqueueWrite` 都假定在这个 EL 上;任何跨
线程写入必须通过 `owner.execute(task)` 跳转,这点 `enqueueWrite`
(`TcpMultiplexer.java:991-996`)已实现,`TcpChannel.doWrite` 可直接复用。

```java
@Override
protected boolean isCompatible(EventLoop loop) {
    return loop == sock.eventLoop();
}

@Override
protected AbstractUnsafe newUnsafe() {
    return new TcpChannelUnsafe();
}
```

注册时直接用 `sock.eventLoop().register(tcpChannel)` 即可,不需要通过
`Bootstrap`,因为 TCP 连接是被动接受的(SYN 到达触发)。

### 4.2 读路径(入站)

#### 4.2.1 交付点改造

`TcpMultiplexer.consume` 改造为三向分流(优先级:`userChannel` > `childChannel` > `DataConsumer`):

```java
protected void consume(TcpSock sk, ByteBuf data) {
    TcpChannel uc = sk.userChannel();
    if (uc != null && uc.isActive()) {
        uc.fireChannelReadFromTcp(data);
        return;
    }
    if (sk.hasBackendChannel()) {
        sk.childChannel().writeAndFlush(data);
        return;
    }
    dataConsumer.onData(sk.fourTuple(), data);
}
```

`fireChannelReadFromTcp` 是 `TcpChannel` 上的 package-private 辅助,内部:

```java
void fireChannelReadFromTcp(ByteBuf data) {
    assert eventLoop().inEventLoop();
    if (!autoRead && !readRequested) {
        pendingInbound.addLast(data);
        return;
    }
    readRequested = false;
    pipeline().fireChannelRead(data);
    pipeline().fireChannelReadComplete();
}
```

#### 4.2.2 autoRead / 背压

- `autoRead=true`(默认):到数据立即 `fireChannelRead`,用户 handler 同步消费。
- `autoRead=false`:数据暂存到 `pendingInbound: ArrayDeque<ByteBuf>`;**同时**设
  `sk.rcvPaused(true)` 抑制 `TcpReceiveBuffer.readAll` 推进 `rcv_nxt`,从而使
  `tcp_receive_window()` 缩窄,对端自然感知到反压。
- `doBeginRead()`:drain `pendingInbound`,若为空且 `sk.receiveBuffer().isReadable()`
  则主动触发一次 `consume`。

`sk.rcvPaused` 是 `TcpSock` 新增的布尔标志,`queue_and_out`
(`Tcp4Multiplexer.java:440` 附近)在 `rcvPaused=true` 时:

- 仍把 skb 放进 `receiveBuffer`(保持 rcv_nxt 推进的前提 — **否则违反 TCP 语义**);
- **但不**调用 `consume`,让数据滞留 socket 接收缓冲;
- 在 `tcp_cleanup_rbuf` / `tcp_select_window` 时体现为 `rcv_wnd` 收缩。

> **注意**:TCP 反压语义是"接收方慢就缩窗",与 `autoRead=false` 的 Netty 语义
> 一致但实现要点在 `rcv_wnd` 而非直接停读;简单的"入站队列停读"不足以通知对端。

#### 4.2.3 ByteBuf 生命周期

现状(`TcpMultiplexer.java:968`):交付 buf 后由下游(`childChannel` / `DataConsumer`)
负责 release。

新路径下,`fireChannelRead(data)` 之后 pipeline 的**末端** handler(或
`SimpleChannelInboundHandler`)负责 release。若用户 handler 忘记 release,Netty
`ResourceLeakDetector` 会抓到 — 这是标准 Netty 契约。

### 4.3 写路径(出站)

#### 4.3.1 `doWrite` 实现

```java
@Override
protected void doWrite(ChannelOutboundBuffer in) throws Exception {
    for (;;) {
        Object msg = in.current();
        if (msg == null) break;
        if (!(msg instanceof ByteBuf)) {
            in.remove(new UnsupportedOperationException("TcpChannel only accepts ByteBuf"));
            continue;
        }
        ByteBuf buf = (ByteBuf) msg;
        int remaining = buf.readableBytes();
        if (remaining == 0) { in.remove(); continue; }

        int mss = TcpOutput.INSTANCE.tcp_current_mss(sock);
        while (remaining > 0) {
            int len = Math.min(remaining, mss);
            boolean lastSliceOfMsg = (len == remaining);
            boolean lastMsgOfBatch = lastSliceOfMsg && in.size() == 1;
            boolean flush = lastMsgOfBatch;
            multiplexer.enqueueWrite(
                    sock,
                    buf.retainedSlice(buf.readerIndex() + (buf.readableBytes() - remaining), len),
                    flush);
            remaining -= len;
        }
        in.remove();  // release 原 buf;retainedSlice 已持独立引用
    }
}
```

要点:

- `enqueueWrite` 已做 EL 跳转 + `tcp_push_pending_frames`,我们只负责 MSS 切片;
- `retainedSlice` 的引用计数独立于 `in.remove` release 的原 buf,不会错 release;
- 不支持非 `ByteBuf` 消息(未来如要支持 `FileRegion` 再扩展 — 无零拷贝内核路径,收益有限)。

#### 4.3.2 Writability 水位

`TcpSendBuffer` 目前无界(调研报告 §2),`enqueueWrite` 不会 block。我们要在
Netty 层加水位:

- 在 `TcpSock` 增 `pendingBytes()`(未发送 + 已发送未 ACK 合计,字节单位);
- `TcpChannel` 在每次 `doWrite` 后比较 `sock.pendingBytes()` vs
  `config.getWriteBufferWaterMark()`,超 high 则 `unsafe().outboundBuffer().setUserDefinedWritability(0, false)`;
- `tcp_clean_rtx_queue`(v2 已有)ACK 推进后调 `sock.notifyWritability()` 回调,
  该回调在 EL 上检查水位,降 low 以下则 `setUserDefinedWritability(0, true)`。

`notifyWritability` 是新增的一个 `Runnable` 槽,由 `TcpChannel` 在 register 时设,
close 时清。

### 4.4 Close / Shutdown

#### 4.4.1 主动关闭(用户调 `channel.close()`)

```java
@Override
protected void doClose() throws Exception {
    // 触发 FIN — 对齐 v2 现有 child channel 关闭语义
    if (sock.hasConnection() && sock.state().canSend()) {
        if (multiplexer.tcp_close_state(sock)) {
            TcpOutput.INSTANCE.tcp_send_fin(sock);
        }
    }
    drainPendingInbound();
    sock.userChannel(null);
}
```

#### 4.4.2 半关闭

- `shutdownOutput()` → 发 FIN,不销毁 channel(应用仍可读);pipeline 上
  `fireUserEventTriggered(ChannelOutputShutdownEvent.INSTANCE)`。
- `shutdownInput()` → 设 `sock.rcvPaused(true) + shutdownRead=true`,
  之后 `consume` 丢弃数据但仍 ACK(避免对端卡窗);fire `ChannelInputShutdownEvent`。

实现上 `AbstractChannel` 默认不支持半关,需在 `TcpChannel` 声明并覆写
`Channel.shutdownOutput()`(参考 `NioSocketChannel`)。

#### 4.4.3 被动关闭(对端 FIN / RST / 本端 tcp_done)

**对端 FIN**:`tcp_fin` 状态机推进时加钩子 `sock.userChannel().fireInputShutdown()`
→ pipeline `fireUserEventTriggered(ChannelInputShutdownEvent)`;channel 不立即关,
应用可继续写直到自己 close。

**对端 RST**:`tcp_reset` 路径加钩子
`sock.userChannel().unsafe().closeForcibly()` + pipeline
`fireExceptionCaught(new SocketException("Connection reset"))`。

**本端 `tcp_done`**:`inet_csk_destroy_sock` 前先
`userChannel.unsafe().close(unsafe.voidPromise())`。

### 4.5 `isActive` / `isOpen` 契约

```java
@Override public boolean isOpen()   { return !closed; }
@Override public boolean isActive() { return registered && sock.hasConnection()
                                           && sock.state().isEstablishedPhase(); }
```

`isEstablishedPhase()` 需在 `TcpConnectionState` 上新增辅助(等价于 v1 的
`ESTABLISHED | FIN_WAIT_1 | FIN_WAIT_2 | CLOSE_WAIT | CLOSING | LAST_ACK`),对齐
Linux `(1 << state) & (TCPF_ESTABLISHED | TCPF_CLOSE_WAIT | ...)`。

---

## 五、具体改动清单

### 5.1 新增文件

#### ① `pangolin-routing/src/main/java/com/github/pangolin/routing/acceptor/tun/net/v2/tcp/netty/TcpChannel.java`

```java
package com.github.pangolin.routing.acceptor.tun.net.v2.tcp.netty;

public class TcpChannel extends AbstractChannel {
    private final TcpSock sock;
    private final TcpMultiplexer multiplexer;
    private final TcpChannelConfig config;
    private final ArrayDeque<ByteBuf> pendingInbound = new ArrayDeque<>();
    private volatile boolean closed;
    private boolean readRequested;
    private boolean shutdownRead;
    private boolean shutdownWrite;

    public TcpChannel(TcpSock sock, TcpMultiplexer multiplexer) {
        super(null);  // no parent; acceptor 模式用 TcpServerChannel 时传
        this.sock = sock;
        this.multiplexer = multiplexer;
        this.config = new TcpChannelConfig(this);
    }

    // --- AbstractChannel template methods ---
    @Override protected AbstractUnsafe newUnsafe()         { return new TcpChannelUnsafe(); }
    @Override protected boolean isCompatible(EventLoop l)  { return l == sock.eventLoop(); }
    @Override protected SocketAddress localAddress0()      { FourTuple t = sock.fourTuple(); return new InetSocketAddress(t.dstInetAddress(), t.dstPort()); }
    @Override protected SocketAddress remoteAddress0()     { FourTuple t = sock.fourTuple(); return new InetSocketAddress(t.srcInetAddress(), t.srcPort()); }
    @Override protected void doRegister()                  { /* no-op: EL 已在构造时绑定 */ }
    @Override protected void doBind(SocketAddress a)       { throw new UnsupportedOperationException(); }
    @Override protected void doDisconnect()                { doClose(); }
    @Override protected void doClose()                     { /* §4.4.1 */ }
    @Override protected void doBeginRead()                 { /* §4.2.2 drain */ }
    @Override protected void doWrite(ChannelOutboundBuffer in) { /* §4.3.1 */ }

    @Override public ChannelConfig config()                { return config; }
    @Override public boolean isOpen()                      { return !closed; }
    @Override public boolean isActive()                    { /* §4.5 */ }
    @Override public ChannelMetadata metadata()            { return METADATA; }
    private static final ChannelMetadata METADATA = new ChannelMetadata(false);

    // --- package-private bridges (由 TcpMultiplexer / TcpSock 回调) ---
    void fireChannelReadFromTcp(ByteBuf data)              { /* §4.2.1 */ }
    void fireInputShutdown()                               { /* §4.4.3 FIN */ }
    void fireConnectionReset(Throwable cause)              { /* §4.4.3 RST */ }
    void notifyWritability()                               { /* §4.3.2 */ }

    TcpSock sock()                                         { return sock; }

    private final class TcpChannelUnsafe extends AbstractUnsafe {
        @Override public void connect(SocketAddress remote, SocketAddress local, ChannelPromise promise) {
            promise.setFailure(new UnsupportedOperationException(
                    "TcpChannel is passive; use listen path"));
        }
    }
}
```

#### ② `TcpChannelConfig.java`

```java
public class TcpChannelConfig extends DefaultChannelConfig {
    public TcpChannelConfig(TcpChannel ch) {
        super(ch);
        // 复用 Netty 现成 WriteBufferWaterMark:默认 32k low / 64k high
    }

    @Override public <T> boolean setOption(ChannelOption<T> option, T value) {
        if (option == ChannelOption.TCP_NODELAY) {
            // 映射到 sock.nagleEnabled
            boolean nagleOff = Boolean.TRUE.equals(value);
            ((TcpChannel) channel()).sock().nagleEnabled(!nagleOff);
            return true;
        }
        if (option == ChannelOption.SO_KEEPALIVE) {
            ((TcpChannel) channel()).sock().keepaliveEnabled(Boolean.TRUE.equals(value));
            return true;
        }
        return super.setOption(option, value);
    }
    // getOption 对称
}
```

> **地址构造说明**:`localAddress0()/remoteAddress0()` 直接用 `FourTuple.dstInetAddress()/srcInetAddress()` 构造 `java.net.InetSocketAddress`。早期草案里设计过 `TcpSocketAddress` 包装类,最终判定为冗余 —— `InetSocketAddress` 本身就是 `SocketAddress` 子类,且 Netty pipeline 读取时只关心 `toString()/equals()` 语义。地址语义是 Netty 层关注点,不应在协议栈 core 层(`FourTuple`)挂 `InetSocketAddress` 依赖。

#### ④ `TcpChannelFactory.java`

```java
@FunctionalInterface
public interface TcpChannelFactory {
    TcpChannel create(TcpSock sock, TcpMultiplexer multiplexer);
}
```

用户侧典型用法:

```java
TcpChannelFactory factory = (sock, mux) -> {
    TcpChannel ch = new TcpChannel(sock, mux);
    ch.pipeline().addLast(new HttpServerCodec(), new HttpObjectAggregator(65536), new MyBizHandler());
    return ch;
};
new Tcp4Multiplexer(config, factory);
```

### 5.2 修改的已有文件

#### ① `TcpSock.java`

| 改动 | 位置 |
|------|------|
| 新增字段 `private TcpChannel userChannel;` | 字段区 |
| 新增 `public TcpChannel userChannel()` / `void userChannel(TcpChannel)` | getter/setter |
| 新增字段 `private boolean rcvPaused;` + getter/setter | 字段区 |
| 新增字段 `private Runnable writabilityObserver;` + setter | 字段区 |
| `close()`:调用 `userChannel != null → userChannel.unsafe().close(voidPromise())`(仅当非 userChannel-initiated close) | `TcpSock.java:891` 附近 |
| 新增 `pendingBytes()` — 返回 `sendBuffer.enqueuedBytes() + sendBuffer.unackedBytes()` | §5.2-② 一并增 |

#### ② `TcpSendBuffer.java`

| 改动 | 位置 |
|------|------|
| 新增 `long enqueuedBytes()` / `long unackedBytes()` | 类末尾 |
| `tcp_clean_rtx_queue` 路径每次扣减后调 `sock.notifyWritability()` | 在 v2 `TcpAck.tcp_clean_rtx_queue` 找到扣减点插一行 |

#### ③ `TcpMultiplexer.java`

| 改动 | 位置 |
|------|------|
| `consume()` 增 userChannel 分支 | `TcpMultiplexer.java:966`(§4.2.1) |
| `enqueueWrite` 改可见性为 package(或保持 protected,由 `Tcp4Multiplexer` 暴露 public 方法) | `TcpMultiplexer.java:974` |
| `tcp_init_transfer` 增 userChannel 分支:有 userChannel 则 register + fireChannelActive,跳过 `childChannel.pipeline().addLast(...)` | `TcpMultiplexer.java:764` |
| `inet_csk_destroy_sock(TcpSock)` 在销毁前 `if (sk.userChannel() != null) sk.userChannel().unsafe().close(voidPromise())` | `TcpMultiplexer.java:247-253` |
| 新增 public `tcp_close_state(TcpSock)` 包装(供 `TcpChannel.doClose` 调) | 工具方法区 |

#### ④ `Tcp4Multiplexer.java`

| 改动 | 位置 |
|------|------|
| 新增构造重载:`Tcp4Multiplexer(TcpConfig, TcpChannelFactory)`;原 `DataConsumer` / `SocketChannelFactory` 构造保留 | `Tcp4Multiplexer.java:33-49` |
| `tcp_v4_conn_request` 逻辑岔口:若 `tcpChannelFactory != null` 则**跳过** `socketChannelFactory.open` backend 连接,在 `syn_recv_sock` 成功后调 `factory.create(sock)` 并 register | `Tcp4Multiplexer.java:396-504` |
| `tcp_reset` / RST 接收路径:若有 `userChannel` 则 `fireConnectionReset` | FIN/RST 处理函数 |
| `tcp_fin` 状态推进后:若有 `userChannel` 则 `fireInputShutdown` | 状态迁移函数 |

#### ⑤ `TcpConnectionState.java`

新增 `boolean isEstablishedPhase()` 工具方法(§4.5)。

### 5.3 不触碰的内容

- `TcpReceiveBuffer` — 缓冲区本身无须改造,只加一个"消费者要不要拿"的外部门控。
- 所有拥塞控制 / RACK / 重传 / OFO / PAWS / RFC5961 路径 — 本方案纯对外接口,
  核心协议栈零改动。
- v1 代码(`handler/tcp`)。

---

## 六、关键挑战与风险

### 6.1 EventLoop 一致性(⚠️ 最高优)

所有 `TcpSock` 字段访问都必须在 `sock.eventLoop()`。用户在 handler 里
`ctx.writeAndFlush` 天然在 channel EL(= sock EL),安全;但若用户跨线程调
`channel.writeAndFlush`,Netty `AbstractChannel` 会走 `eventLoop().execute` 跳转,
这条路径在 `doWrite` 时已经在正确 EL,也安全。

**风险点**:用户在业务线程池里自己 `sock.sendBuffer().enqueue(...)`(绕过 Netty)
— 这种反模式必须在文档里禁掉。

### 6.2 backend childChannel 与 userChannel 互斥

`consume` 分流优先级 `userChannel > childChannel > DataConsumer`。
若构造 `Tcp4Multiplexer` 时**同时**传了 `TcpChannelFactory` 和 `SocketChannelFactory`:

- 方案一(推荐):拒绝此组合,构造抛 `IllegalStateException`;
- 方案二:`userChannel` 上默认挂一个 `BackendForwardHandler` 做二级转发 — 复杂且
  容易引起资源泄漏,不建议默认开启。

### 6.3 Writability 水位粒度

`pendingBytes = enqueuedBytes + unackedBytes` 在拥塞窗口很小的场景会迅速打满,
导致 writability 频繁翻转。建议:

- 默认 high=256KB / low=64KB(远高于 TCP 发送缓冲常见量级);
- 允许用户 `config.setOption(ChannelOption.WRITE_BUFFER_WATER_MARK, ...)` 覆写。

### 6.4 对端 RST 与 Netty 异常语义

v2 的 `tcp_reset` 路径会直接 `tcp_done` 销毁 sock。必须**先** `fireConnectionReset`
**再** destroy,否则用户 handler 的 `exceptionCaught` 会因 channel 已 inactive 而
丢失 cause。改动点:`tcp_reset` 内调用顺序重排 — userChannel 通知 → tcp_done。

### 6.5 FIN 到达后的 writability

对端 FIN 后本端进 CLOSE_WAIT,仍可写(对齐 Linux)。`isActive` 继续返回 true。
`shutdownInput` 标志用来屏蔽 inbound 而非全关。

### 6.6 Leak 风险

- `pendingInbound` 队列里的 `ByteBuf` 在 `doClose` 时必须 drain-release;
- `doWrite` 的 `retainedSlice` 失败(enqueueWrite 因 sock 已关闭提前 release)
  不会泄漏,因为 `enqueueWrite` 内部 `!canSend` 分支已 `data.release()`
  (`TcpMultiplexer.java:976-978`);
- 构造 `TcpChannel` 后若 register 失败(EL 未启动等异常),factory 必须 release
  factory-created pipeline 中可能已持有的引用 — 标准 `ReflectiveChannelFactory` 模式。

### 6.7 ServerBootstrap 集成(P1,不在本次范围)

后续要支持 `new ServerBootstrap().channel(TcpServerChannel.class).childHandler(...)` 风格,
需额外实现 `TcpServerChannel extends AbstractServerChannel`:`doReadMessages` 从
`Tcp4Multiplexer` 的 accept 队列取出 `TcpChannel`,通过 `pipeline.fireChannelRead(childCh)`
走标准 `ServerBootstrapAcceptor` 流程。这条线会把 `TcpChannelFactory` 替换为
`childHandler + childOptions`,改动面更大,建议作为 Phase 2。

---

## 七、实施分阶段

| 阶段 | 产出 | 预估 | 验收 |
|------|------|------|------|
| **P0-骨架** | `TcpChannel` / `TcpChannelConfig` / `TcpChannelFactory` 空实现;`TcpSock.userChannel` 字段(`localAddress0/remoteAddress0` 直接构造 `InetSocketAddress`) | 0.5d | 单元测试:`new TcpChannel(mockSock, mockMux)` 可 register 到 EmbeddedEventLoop,`isOpen=true` |
| **P1-读路径** | `consume` 分流 + `fireChannelReadFromTcp` + autoRead | 1d | 本地 TUN 回环:SYN 握手完成 → 用户 handler 的 `channelActive` / `channelRead` 触发;`autoRead=false` 时对端感知窗口收缩 |
| **P2-写路径** | `doWrite` + MSS 分片 + writability 水位 | 1d | handler `ctx.writeAndFlush` 100MB,对端全量收到且顺序正确;`isWritable` 按水位翻转 |
| **P3-close/shutdown** | 主动 close → FIN;对端 FIN → `ChannelInputShutdownEvent`;对端 RST → `exceptionCaught` | 1d | 四向 close 覆盖测试(主动半关、主动全关、对端 FIN、对端 RST)pipeline 事件齐全 |
| **P4-压测 + leak** | 万连接并发 + `-Dio.netty.leakDetection.level=PARANOID` 跑 10 min | 1d | 无 leak,无 EL 抢占,GC 正常 |
| **P5(可选)- ServerBootstrap** | `TcpServerChannel` + `ServerBootstrap.childHandler` 风格 | 1.5d | `new ServerBootstrap().channel(TcpServerChannel.class)` 可跑 `HttpHelloWorldServer` |

合计 P0-P4 约 4.5 天;P5 再 1.5 天。

---

## 八、验收 / 使用示例

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
// mux 挂到 TUN pipeline(与现状一致)
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

### 8.3 验收清单

- [ ] `ResourceLeakDetector.Level=PARANOID` 下 10 分钟压测 0 leak
- [ ] 10k 并发连接 + 每连接 1MB echo 成功,内存曲线平稳
- [ ] `autoRead=false` 时 tcpdump 观察到对端 `Win=0` / 窗口收缩
- [ ] `config.setOption(TCP_NODELAY, true)` 等价 `sock.nagleEnabled(false)`
      (用 tcpdump 观察没有 200ms 延迟合批)
- [ ] 主动 close / 对端 FIN / 对端 RST 三条线路 `pipeline` 事件顺序与
      `NioSocketChannel` 一致
- [ ] `HttpServerCodec` + `HttpObjectAggregator` 开箱直接跑通

---

## 九、与 Linux 内核的对齐性

本方案纯属"应用接口层"改造,不触及协议栈逻辑,无 Linux 对齐议题。所有
TCP 行为(PAWS / 拥塞控制 / OFO / FIN 半关 / RST 响应 / keepalive / probe0 / RACK 等)
仍由 v2 核心模块承担,依旧严格对齐 Linux(见 `tcp.java.md`)。

唯一的间接影响:`rcvPaused` 机制在语义上对应 Linux 的**应用层 read 未及时**
导致 `tp->rcv_wnd` 收缩,与 Linux `tcp_select_window` 行为一致,只是触发源从
"应用 syscall 未读 socket" 变成 "Netty autoRead=false 未消费"。

---

**维护说明**:本方案如实施后,在 `tcp.java.md` §三(S-x 工程化简化)中追加一条
`S-N`:v2 对外接口由 `TcpChannel` 替代原 `DataConsumer` + `childChannel` 双分支,
并把原 `DataConsumer` / `SocketChannelFactory` 路径降级为 compat fallback。
