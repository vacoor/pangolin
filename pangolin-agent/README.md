# pangolin-agent

Pangolin 隧道系统的 **Agent 核心库**,实现内网侧的接入代理。两种部署形态共存:

- **主动模式**(`WebSocketBridgeAgent`):Agent 主动拨号到公网 `pangolin-server`,以 `SERVICE` 子协议建立长连作为控制总线,server 下发 `CMD_CONNECT` 后 Agent 在内网打开目标 TCP 并以 `BACKHAUL` 子协议回拨第二条 WebSocket 完成隧道
- **被动模式**(`servlet/WebSocketBridgeEndpoint`):JSR-356 `@ServerEndpoint`,Agent 跑在某个 Servlet 容器里被动等 Client 用 `/ws/bridge/{tunnelKey}` 接入,与主动模式共享 SOCKS5 风格协议但**不复用任何运行时状态**

打包形态:**普通 Java 库 jar**,不是可执行产物。需要嵌入到 `pangolin-agent-app`(主动模式)或任意 Servlet 容器(被动模式)。

技术栈:Netty 4.2 + JSR-356 WebSocket API + Lombok。

---

## 目录结构

```
com/github/pangolin/agent/
├── WebSocketBridgeAgent.java          主动模式入口:连接 / 重连生命周期
├── WebSocketBridgeAgentHandler.java   SERVICE 注册 + CMD_CONNECT 解析 + backhaul 桥接
├── util/
│   └── Channels2.java                 WS 客户端连接工厂(HttpClientCodec + WS handshaker)
└── servlet/
    ├── WebSocketBridgeEndpoint.java         被动模式 @ServerEndpoint
    └── WebSocketEndpointLoaderListener.java JSR-356 endpoint 注册监听
```

依赖的 `Channels`、`TcpOverWebSocketEncodeHandler`、`TcpOverWebSocketDecodeHandler`、`TcpInboundRedirectHandler` 均位于 `pangolin-common`。

---

## 架构与关键流程

### 1. 模块形态

| 模式     | 入口类                                | 角色                          | 是否依赖外层容器        |
| -------- | ------------------------------------- | ----------------------------- | ----------------------- |
| 主动     | `WebSocketBridgeAgent`                | WebSocket Client 拨号到 server | 否,内嵌 Netty `NioEventLoopGroup` |
| 被动     | `servlet/WebSocketBridgeEndpoint`     | JSR-356 ServerEndpoint        | 是,需要 Servlet + WS 容器 |

`WebSocketEndpointLoaderListener`(`@WebListener`)在 `contextInitialized` 时检测 `javax.websocket.Endpoint` 是否存在,把 `WebSocketBridgeEndpoint` 通过 `ServerContainer.addEndpoint` 部署到当前 web 容器。

### 2. 主动模式生命周期(`WebSocketBridgeAgent`)

```java
new WebSocketBridgeAgent(name, URI.create("ws://server:2345/tunnel/{tunnelKey}"), reconnectIntervalSeconds).start();
```

`start()`(幂等,基于 `AtomicBoolean`):

1. `connect0()` 用 `Channels2.openWs(...)` 打开到 server 的 WebSocket,子协议 `SERVICE`
2. pipeline 末尾装 `IdleStateHandler(60, 60, 60)` + `WebSocketBridgeAgentHandler`
3. `WebSocketBridgeAgentHandler#channelActive` 在握手前**改写 `customHttpHeaders`**,只写一个头:
   - `Authorization: Bearer <urlsafe-base64(SOCKS5-like SERVICE 负载)>` —— 节点名、版本、内网地址全部塞在 base64 负载里(见 §3)
4. server 完成 `WebSocketServerProtocolHandler` 握手,识别到 `SERVICE` 子协议后调 `Engine.registerAgent`,本 Agent 进入"已注册"状态
5. 之后所有 `BinaryWebSocketFrame` 都是 `CMD_CONNECT` 请求(见 §3-4)

**重连策略**:`channelFuture` 失败或 close 后触发 `reconnectIfNecessary` —— 默认 10s 后再调 `connect()`。`reconnectFuture` 用 `null != reconnectFuture && !reconnectFuture.isDone()` 防重排。

### 3. 协议格式(类 SOCKS5)

协议常量集中在 `WebSocketBridgeAgentHandler`:`VER_1 = 0x01`、`CMD_CONNECT = 0x01`、`AGENT_VERSION = "1.2"`、`ATYPE_IPv4/DOMAIN/IPv6 = 0x01/0x03/0x04`、回复码 `REPLY_SUCCESS=0x00 ... REPLY_ADDRESS_UNSUPPORTED=0x08`。

**SERVICE 注册负载**(Agent → Server,放在 `Authorization: Bearer <base64>`):

```
+-----+-------+-------+------+----------+----------+----------+----------+
| VER |  CMD  |  RSV  | ATYP | BND.ADDR | BND.PORT | AGN.NAME |  AGN.VER |
+-----+-------+-------+------+----------+----------+----------+----------+
|  1  | x'FF' | x'00' |  1   | Variable |    2     | Variable | Variable |
+-----+-------+-------+------+----------+----------+----------+----------+
```

`BND.ADDR` 写的是 Agent 自己的 `localAddress.getHostString()`(用 `ATYPE_DOMAIN` 编码),供 server 端日志展示,不参与路由。

**Server → Agent 的 CMD_CONNECT**(到达 SERVICE bus):

```
+-----+----------+-----+-------+------+----------+----------+
| VER | ID       | CMD |  RSV  | ATYP | DST.ADDR | DST.PORT |
+-----+----------+-----+-------+------+----------+----------+
|  1  | Variable |  1  | x'00' |  1   | Variable |    2     |
+-----+----------+-----+-------+------+----------+----------+
```

ID 是 server 侧 access channel 的 Netty channel id 字符串。

**Agent → Server 的 Reply**(通过 backhaul WS 发回,或 backhaul 失败时通过 SERVICE bus 发 `REPLY_HOST_UNREACHABLE`):

```
+-----+----------+-----+-------+------+----------+----------+
| VER | ID       | REP |  RSV  | ATYP | BND.ADDR | BND.PORT |
+-----+----------+-----+-------+------+----------+----------+
|  1  | Variable |  1  | x'00' |  1   | Variable |    2     |
+-----+----------+-----+-------+------+----------+----------+
```

成功的 Reply(`newReply(..., REPLY_SUCCESS)`)被 base64 后塞进 backhaul 握手的 `Authorization` 头作为信令(配合 `?id=<id>` 查询参数),用于在 server 端 `finishHandshake` 配对到对应 `Connection`。

### 4. backhaul 拼接(`WebSocketBridgeAgentHandler#pipe`)

收到 `CMD_CONNECT` 后:

1. 解析 `DST.ADDR / DST.PORT` → `InetSocketAddress destination`
2. 构造 `backhaulHandshaker`:URI 是 `<server-base>/<id>?id=<id>`,子协议 `BACKHAUL`,headers 带成功 Reply 作 token
3. `Channels.open(destination)` 拨内网目标 TCP(autoRead=false)
4. target connect 成功后,在 target 的 `channelActive` 里调 `Channels2.openWs` 拨第二条 WS 到 server
5. backhaul WS 握手完成(`HANDSHAKE_COMPLETE`)后 `setAutoRead(false)`,根据 `rsv` 拼 pipeline:
   - **`rsv == 0`(默认,WS-up-to-target)**:
     - `destinationCtx` 替换为 `TcpOverWebSocketEncodeHandler(backhaulCtx)` —— 把内网 target 的裸 TCP 包成 BinaryFrame 发给 server
     - `backhaulCtx` 替换为 `TcpOverWebSocketDecodeHandler(destinationCtx)` —— 把 server 来的 BinaryFrame 拆成裸字节写给 target
     - 链路:`server <--ws--> agent <--tcp--> target`(backhaul 这段是 WebSocket)
   - **`rsv != 0`(全 TCP 降级)**:双方都装 `TcpInboundRedirectHandler`,backhaul 移除 WS codec(`Utf8FrameValidator`、`wsencoder`、`wsdecoder`)
     - 链路:`server <--tcp--> agent <--tcp--> target`
     - **当前是死代码** —— server 端 `Engine.handshake0` 把 RSV 硬编码为 0(`pangolin-server` README BUG #8),`rsv != 0` 永不成立
6. 写一个 `EMPTY_BUFFER` 触发 backhaul 的 ws codec(必要的话再卸掉),最后 `setAutoRead(true)` 双向放流

backhaul 握手或 target connect 失败:`backhaulPromise` 失败时通过 SERVICE bus 发 `REPLY_HOST_UNREACHABLE` 让 server 知道。

backhaul 在 backhaul WS 客户端 pipeline 上插入了 `FlowControlHandler`(在 `WebSocketClientProtocolHandler` 之前),用于配合 `setAutoRead(false)`,握手完成前不漏帧。

### 5. 被动模式(`servlet/WebSocketBridgeEndpoint`)

```
@ServerEndpoint("/ws/bridge/{tunnelKey}")
```

**与主动模式的关系**:协议常量和帧格式相同,但**代码路径完全独立**(没有 `pipe` 也没有 backhaul,只是单条 WebSocket 直接桥接到内网目标)。

**`@OnOpen` 流程**:

1. 从 `?access_token=...` 取 URL-Safe Base64 编码的 SOCKS5 风格 CONNECT 负载
2. 校验 `VER_1` / `CMD_CONNECT` / `RSV == 0`(校验失败逻辑见 BUG #3)
3. `parseSocketAddress(payload)` → `target`
4. `authenticate(tunnelKey)` —— 与系统属性 `websocket.bridge.access_key`(默认 `c254dacd0cde3be75ac2988f691ec105`,见 BUG #1)比对
5. `new NioEventLoopGroup(1)` + `Channels.open(target)` 拨内网目标
6. `.sync()` 阻塞等待 connect 完成,挂 closeFuture(target 关 → `session.close()` + `brGroup.shutdownGracefully()`)

**`@OnMessage`**:Client 发来的 `byte[]` → `channel.writeAndFlush(Unpooled.wrappedBuffer(bytes))`。
**`@OnClose`**:关闭 target channel(closeFuture 顺势触发 ELG shutdown)。
**target → Client**:在 `Channels.open` 注册的 inbound handler 里把 `ByteBuf` 转 `nioBuffer()` 再 `session.getBasicRemote().sendBinary(...)`。

### 6. 心跳保活

- **主动模式 SERVICE bus**:`WebSocketBridgeAgent#connect0` 装 `IdleStateHandler(60, 60, 60)`,`WebSocketBridgeAgentHandler#userEventTriggered` 收到 `IdleStateEvent` 直接 `writeAndFlush(new PingWebSocketFrame())`。与 server 端 `WebSocketKeepaliveHandler(60,60,60)` 双向互补,任意一端 60s 静默都会主动发 PING
- **主动模式 backhaul**:`Channels2.openWs` 拨号时**没有挂任何 keepalive handler**,server 端会在自己侧装 `WebSocketKeepaliveHandler(60,60,60)`,所以 backhaul 的 PING 是**单向的**(server → agent)。底层裸 TCP `SO_KEEPALIVE` 已由 `pangolin-common/Channels.java#open` 写死开启
- **被动模式**:无心跳。Servlet 容器 + JSR-356 通常自带容器级超时 / PING 处理(取决于 Tomcat / Jetty / Tyrus 的实现),不在本模块范围

---

## 嵌入 / 启动方式

**主动模式(嵌入到自定义 main):**

```java
final WebSocketBridgeAgent agent = new WebSocketBridgeAgent(
    "node-1",
    URI.create("ws://server:2345/tunnel/myGroup"),
    10  // reconnect interval seconds
);
agent.start();
Runtime.getRuntime().addShutdownHook(new Thread(agent::shutdownGracefully));
```

或者直接用 `pangolin-agent-app`(Spring Boot 封装)/ `pangolin-client-spring-boot-starter`(15s watchdog 自动起停)。

**被动模式**:把本 jar 与 `pangolin-common` 一起放到任意支持 JSR-356 的 Servlet 容器(Tomcat 7+ / Jetty / Undertow / WildFly 等)。`@WebListener WebSocketEndpointLoaderListener` 会在 `contextInitialized` 时自动注册 `WebSocketBridgeEndpoint`。

`WebSocketBridgeAgent#main` 是个 demo(`URI("ws://localhost:2345/tunnel/123")` + `LockSupport.park()`),不要直接用作生产入口。

---

## 潜在 BUG 排查

> 以下为代码走读发现的疑似 BUG,按严重程度排序。文件路径相对 `pangolin-agent/src/main/java/com/github/pangolin/agent/`。

### 设计说明(非 BUG,易误读)

**主动模式与被动模式共享协议、不共享代码**
两条路径各自重声明了协议常量(`VER_1` / `CMD_CONNECT` / `ATYPE_*`),数据流也各自实现 —— 主动模式靠 backhaul 拼 pipeline,被动模式直接在 `@OnMessage` / inbound handler 之间穿梭 `byte[]` ↔ `ByteBuf`。改协议时**两边都要同步改**,否则只有一种部署形态会兼容新版本。

**SERVICE 注册负载的 `BND.ADDR` 不参与路由**
Agent 在握手时塞了 `localAddress` 当 `BND.ADDR`,server 解析后存到 `agent.intranet`,但 `Engine.choose(tunnelKey)` 从不读它。仅用于运维 / 日志展示。

### 🔴 高危

**1. 被动模式 `access_key` 默认值硬编码到源码**
`servlet/WebSocketBridgeEndpoint.java:135` —— `authenticate` 与 `System.getProperty("websocket.bridge.access_key", "c254dacd0cde3be75ac2988f691ec105")` 比对。**默认 key 已经写死在公开源码里**,任何拿到 jar 的人都知道默认值。生产部署必须显式 `-Dwebsocket.bridge.access_key=...` 覆盖,且不能用源码里的字面量。

**2. 被动模式 token 仅 Base64 解码,无任何校验**
`WebSocketBridgeEndpoint#onOpen:33-40` 从 `?access_token=<base64>` 解出 SOCKS5 负载直接当作信任输入,没有签名 / HMAC / 时间窗校验。任何人都能凭一个有效 `access_key` 构造 CONNECT 请求,把 Agent 当跳板访问内网任意 host:port。等同于 `pangolin-server` 的 BUG #2,但被动模式更危险(直接落地到内网目标,没有 server 中间环节做日志 / 风控)。

**3. 被动模式协议校验失败后**未** `return`**,后续代码继续执行**
`WebSocketBridgeEndpoint#onOpen:42-62` `VER_1` / `CMD_CONNECT` / `RSV` 三个分支都只 `session.close(...)` 但**不 return**,后续:

- 继续 `payload.readByte()`,可能 `IndexOutOfBoundsException`
- `parseSocketAddress(payload)` 拿到错误结果或抛异常
- 进入 `Channels.open(target, ...)` —— 在已 close 的 session 上发起 TCP 连接

应改为每个 `session.close(...)` 后 `return`,或重构为先校验完成再走主流程。

**4. 被动模式每条 client 连接 `new NioEventLoopGroup(1)`**
`WebSocketBridgeEndpoint#onOpen:70` —— 每个 `@OnOpen` 都创建一个 1 线程的 ELG。高并发场景:线程数 = 在线连接数。同时 `.sync()` 在 `Channels.open(...).addListener(...).sync()` 上阻塞 servlet 容器线程直到 target connect 完成。建议:全局共享一个 `NioEventLoopGroup`,或挪到 endpoint 的 `userProperties` 里复用。

### 🟡 中危

**5. 主动模式 `parseAddress` 做同步 DNS,阻塞 NIO EventLoop**
`WebSocketBridgeAgentHandler.java:179` 注释里就写着 `// FIXME DNS query.` —— `InetAddress.getByName(domain)` 是 OS 阻塞调用,跑在 Netty `NioEventLoop` 上会卡死整组连接的 IO。修法:用 Netty 的 `DnsAddressResolverGroup` + `Channels.open(remoteAddress, resolver, ...)`(server 端 Forwarder 同样问题但不在本模块)。

**6. SERVICE 注册负载用 `AGENT_VERSION.length()` 写帧长度**
`WebSocketBridgeAgentHandler.java:111`:
```java
buf.writeByte(AGENT_VERSION.length());
buf.writeBytes(versionBytes);
```
当前 `AGENT_VERSION = "1.2"` 全 ASCII,字符长度 == 字节长度,巧合正确。但语义错误:应该写 `versionBytes.remaining()`。如果将来版本号改成包含非 ASCII(如 `"1.2-α"`)会写出错误的长度前缀,server 解析直接错位。`AGN.NAME` 字段已经正确用 `nameBytes.remaining()`,这里漏改。

**7. 主动模式重连可能短路**
`WebSocketBridgeAgent#reconnectIfNecessary:90`:

```java
if (!started.get() || (null != reconnectFuture && !reconnectFuture.isDone())) {
    return;
}
```

正常路径下 `reconnectFuture` 在调度的任务跑完后 isDone,无问题。但任务体里 `connect()` 抛 `IOException` 时 catch 后**再次调** `reconnectIfNecessary()` —— 此时**当前任务还没返回**,`reconnectFuture.isDone()` 仍为 `false`,直接 `return`,**永不再排队**。此后 Agent 不会重连。修法:catch 块里直接 `reconnectFuture = workerGroup.next().schedule(...)` 而不是递归调 `reconnectIfNecessary`。

**8. 全 TCP 降级分支(`pipe` 的 `else`)是死代码**
`WebSocketBridgeAgentHandler.java:262-263` 的 `TcpInboundRedirectHandler` + 移除 backhaul WS codec 分支,触发条件 `0 != rsv`。但 server 端 `Engine.handshake0` 写 RSV 硬编码 0(`pangolin-server` README BUG #8),所以这一支永不执行。要么删,要么协调 server 端把 `downgrade` 信号传到 RSV。

### 🟢 低危 / 代码瑕疵

**9. 主动模式 backhaul 没挂 keepalive,只能依赖 server 单向 PING**
`WebSocketBridgeAgentHandler#pipe` 里 `Channels2.openWs(...)` 只装了一个匿名 `ChannelInboundHandlerAdapter`,没有 `IdleStateHandler`。server 端会装 `WebSocketKeepaliveHandler(60,60,60)`,所以 PING 仍能维持链路 —— 但是单向。考虑双向对称地在 backhaul 也挂一个 idle handler。

**10. `WebSocketBridgeAgent` 用默认 `NioEventLoopGroup()`**
`WebSocketBridgeAgent.java:31` —— `new NioEventLoopGroup()` 默认 `2 * cpu` 线程。Agent 只跑一条 SERVICE bus + 若干并发 backhaul,通常 1-2 线程足够,默认值偏多。考虑允许构造器传入 ELG。

**11. `WebSocketBridgeAgent.main` 用 `LockSupport.park()` 阻塞**
仅用于 demo,生产上需要 shutdown hook。已有 `shutdownGracefully()` 但 main 里没挂。

**12. `WebSocketBridgeEndpoint.AuthenticationConfigurator` 是空 customizer**
`servlet/WebSocketBridgeEndpoint.java:138-147` 的 `modifyHandshake` 整体被注释掉,只剩空方法。要么实现握手时校验(BUG #2 的修复点),要么干脆从 `@ServerEndpoint(configurator=...)` 上摘掉。

**13. `pipe` 里挂了两个 failure listener**
`WebSocketBridgeAgentHandler.java:298` —— `.addListener(propagationOnFailure).addListener(ChannelFutureListener.CLOSE_ON_FAILURE)`。同一个 future 触发两次 fail handler:propagation 把异常传给 backhaulPromise,close 顺手关闭 channel。两者顺序虽然 OK,但语义重叠 —— 因为 `Channels.open` 的 `addListener(propagation)` 已经会让 `backhaulPromise` 失败,后续 `pipe` 流程会自己关 channel,显式 CLOSE_ON_FAILURE 是冗余的。

**14. 协议常量与 `pangolin-server` 重复声明**
`WebSocketBridgeAgentHandler` 自己一份 `VER_1` / `CMD_CONNECT` / `ATYPE_*` / `REPLY_*`,与 `WebSocketBridgeServerEngine` 里的同名常量字面量相同但物理独立。`pangolin-server` README 已记录"建议抽到 `pangolin-common`",本模块同样诉求。

---

## 其他 TODO / 非 BUG

- **`AGENT_VERSION = "1.2"` 与 server 不强校验**:server `Engine#registerAgent` 只验 `VER == VER_1`,不读 `AGN.VER`,所以本字段当前仅用于运维侧诊断
- **被动模式 endpoint 路径硬编码**:`@ServerEndpoint("/ws/bridge/{tunnelKey}")` 不可配置;主动模式的 endpoint 路径完全由 URI 决定,自由
- **被动模式 ELG 与主动模式 ELG 不共享**:即便同一进程同时启动两种模式,内部各跑一组线程
- **`WebSocketEndpointLoaderListener#getAnnotatedEndpointClasses` 写死 `{WebSocketBridgeEndpoint.class}`**:当前模块只有一个 endpoint,不是可扩展点
