# pangolin-agent

Pangolin 隧道系统的 **Agent 核心库**,实现内网侧的接入代理。

主动模式(`WebSocketBridgeAgent`):Agent 主动拨号到公网 `pangolin-server`,以 `SERVICE` 子协议建立长连作为控制总线,server 下发 `CMD_CONNECT` 后 Agent 在内网打开目标 TCP 并以 `BACKHAUL` 子协议回拨第二条 WebSocket 完成隧道。

被动模式 `servlet/WebSocketBridgeEndpoint`(JSR-356 `@ServerEndpoint`)整体 `@Deprecated`,代码、`javax.websocket-api` / `javax.servlet-api` 依赖、test 用的 `tyrus-container-grizzly-server` 都还留着,但**不维护、不参与协议演进**。下文仅作存档式描述。

打包形态:**普通 Java 库 jar**,不是可执行产物。需要嵌入到 `pangolin-agent-app`(主动模式)使用。

技术栈:Netty 4.2 + Lombok。

---

## 目录结构

```
com/github/pangolin/agent/
├── WebSocketBridgeAgent.java          主动模式入口:连接 / 重连生命周期、AGENT_VERSION
├── WebSocketBridgeAgentHandler.java   SERVICE 注册 + CMD_CONNECT 解析 + backhaul 桥接
├── util/
│   └── Channels2.java                 WS 客户端连接工厂(HttpClientCodec + WS handshaker)
└── servlet/                                       (@Deprecated)
    ├── WebSocketBridgeEndpoint.java         被动模式 @ServerEndpoint
    └── WebSocketEndpointLoaderListener.java JSR-356 endpoint 注册监听
```

依赖的 `Channels`、`TcpOverWebSocketEncodeHandler`、`TcpOverWebSocketDecodeHandler`、协议常量 `Constants`、工具方法 `Util`(`hmacSha256`、`readSocketAddress`、URL-Safe Base64 编解码)均位于 `pangolin-common`。

---

## 架构与关键流程

### 1. 模块形态

| 模式     | 入口类                                | 角色                          | 是否依赖外层容器        |
| -------- | ------------------------------------- | ----------------------------- | ----------------------- |
| 主动     | `WebSocketBridgeAgent`                | WebSocket Client 拨号到 server | 否,内嵌 Netty `NioEventLoopGroup` |
| 被动 *(@Deprecated)*  | `servlet/WebSocketBridgeEndpoint`     | JSR-356 ServerEndpoint        | 是,需要 Servlet + WS 容器 |

### 2. 主动模式生命周期(`WebSocketBridgeAgent`)

构造器(secret 由调用方传入):

```java
// 默认重连间隔 10s
new WebSocketBridgeAgent(name, password, URI.create("ws://server:2345/tunnel/{tunnelKey}")).start();

// 自定义重连间隔
new WebSocketBridgeAgent(name, password, endpoint, reconnectIntervalSeconds).start();
```

`password` 为 `String`,内部 `password.getBytes(UTF_8)` 转 `byte[]` 作为 HMAC key;`null` 会变成长度 0 的 secret(等同于"几乎肯定签不上"),因此调用方必须传值。

`start()`(基于 `AtomicBoolean`,重复调用抛 `IllegalStateException("Already started")`):

1. `connect0()` 用 `Channels2.openWs(...)` 打开到 server 的 WebSocket,子协议 `SERVICE`
2. pipeline 末尾装 `IdleStateHandler(60, 60, 60)` + `WebSocketBridgeAgentHandler`
3. handler 内的 `name` 已是 `name + "-v" + AGENT_VERSION`(`AGENT_VERSION = "1.3"`,在 `WebSocketBridgeAgent` 上,`public static final` 暴露)
4. `WebSocketBridgeAgentHandler#channelActive` 在握手前**改写 `customHttpHeaders`**,只写一个头:
   - `Authorization: Bearer <urlsafe-base64(SOCKS5-like SERVICE 负载 + TS+NONCE+HMAC)>` —— 节点名/版本(`AGN.INFO` 单字段)、内网地址塞在 base64 负载里(见 §3),尾部 48 字节 auth tail 是 `writeSignature(buf, secretKey)` 追加的(TS = unix 秒、NONCE = `SecureRandom` 8B、HMAC = `Util.hmacSha256(buf, off, len, secret)`)
5. server 完成 `WebSocketServerProtocolHandler` 握手,识别到 `SERVICE` 子协议后调 `WebSocketBridgeServerHandler#registerAgent` → `checkPayload` 验签 → `Engine.registerAgent`,本 Agent 进入"已注册"状态
6. 之后所有 `BinaryWebSocketFrame` 都是 `CMD_CONNECT`(FORWARD)请求,**不带 auth tail**(SERVICE bus 信道级信任,见 §3-4)

**重连策略**:`channelFuture` 失败或 close 后触发 `reconnectIfNecessary` —— 默认 10s 后再调 `connect()`。`reconnectFuture` 用 `null != reconnectFuture && !reconnectFuture.isDone()` 防重排。

### 3. 协议格式(类 SOCKS5)

协议常量在 `pangolin-common.Constants`(`VER_1=0x01` / `CMD_CONNECT=0x01` / `CMD_SERVICE=0xFF` / `RSV=0x00` / `ATYPE_*` / `REPLY_*` / `IPv4_ADDR_SIZE=4` / `IPv6_ADDR_SIZE=16`),agent 端通过 `import static com.github.pangolin.util.Constants.*` 共享。`AGENT_VERSION` 不在 Constants,而是在 `WebSocketBridgeAgent` 上(`"1.3"`)。

**鉴权尾巴(auth tail)**:`TS(8B 秒) | NONCE(8B SecureRandom) | HMAC(32B HMAC-SHA256)`,共 48B。**仅 WS handshake token 携带**(REGISTER / BACKHAUL REPLY),SERVICE bus 上的 FORWARD / 失败 REPLY **不带**(channel-level trust:bus 在 REGISTER 阶段已验签,后续认 bus 本身可信)。

#### SERVICE 注册负载(Agent → Server,WS handshake token)

```
+-----+-------+-------+------+----------+----------+----------+----+-------+------+
| VER |  CMD  |  RSV  | ATYP | BND.ADDR | BND.PORT | AGN.INFO | TS | NONCE | HMAC |
+-----+-------+-------+------+----------+----------+----------+----+-------+------+
|  1  | x'FF' | x'00' |  1   | Variable |    2     | Variable |  8 |   8   |  32  |
+-----+-------+-------+------+----------+----------+----------+----+-------+------+
```

- `BND.ADDR` 是 Agent 自己的 `localAddress.getHostString()`(用 `ATYPE_DOMAIN` 编码),供 server 端日志展示,不参与路由
- `AGN.INFO` 是 1B LEN + UTF-8 字节,内容为 `name + "-v" + AGENT_VERSION`(单字段)

#### Server → Agent 的 FORWARD(SERVICE bus,**无 auth tail**)

```
+-----+-----+-------+------+----------+----------+----------+
| VER | CMD |  RSV  | ATYP | DST.ADDR | DST.PORT | ID       |
+-----+-----+-------+------+----------+----------+----------+
|  1  |  1  | x'00' |  1   | Variable |    2     | Variable |
+-----+-----+-------+------+----------+----------+----------+
```

ID 在 PORT 之后(1B LEN + UTF-8),是 server 侧 access channel 的 Netty channel id 字符串。`channelRead0` 联合校验 `VER == VER_1 && CMD == CMD_CONNECT`,失败直接忽略(不发 reply、不关 bus)。

#### Agent → Server 的 REPLY

两种发送路径,**布局相同但 auth tail 取决于信道**:

```
+-----+-----+-------+------+----------+----------+----------+[ TS | NONCE | HMAC ]
| VER | REP |  RSV  | ATYP | BND.ADDR | BND.PORT | ID       |[              48B  ]
+-----+-----+-------+------+----------+----------+----------+
|  1  |  1  | x'00' |  1   | Variable |    2     | Variable |
+-----+-----+-------+------+----------+----------+----------+
```

- **BACKHAUL handshake token**(`REP=0x00 SUCCESS`):agent 拨第二条 WS 子协议 `BACKHAUL` 时,把这帧 base64 后塞进 `Authorization: Bearer ...`。**带 auth tail**(`newBackhaulHandshaker` 显式调 `writeSignature(buf, secretKey)`)。BACKHAUL URI 直接复用 SERVICE bus 的 `handshaker.uri()`(`/tunnel/{tunnelKey}`),**没有把 connection id 拼到路径**;server 端从帧体读 `ID`,通过 `Engine.finishHandshake(tunnelKey, id, ctx)` 撮合
- **SERVICE bus 失败 REPLY**(`REP=0x01..0x08`):backhaul 握手或 target connect 失败时,agent 通过 `ctx.writeAndFlush(BinaryWebSocketFrame(newReply(...)))` 直接发回。**不带 auth tail**(`newReply` 本身不追加 sig)

#### Secret 配置

`password` 由调用方传入:

- 嵌入到自定义 main 时直接构造器传字符串
- Spring Boot 集成走 `pangolin-agent-spring-boot-starter`,watchdog 优先读 `spring.management.tunnelSecretKey`,未配置时 fallback 到 `reverse(tunnelKey) + "^_^" + tunnelKey`(与 server 端 `WebSocketBridgeEnvSecretKeyProvider` 公式一致,**默认配置可跑通**;但公式公开,生产仍需显式配置 secret)

### 4. backhaul 拼接(`WebSocketBridgeAgentHandler#pipe`)

收到 SERVICE bus 上的 FORWARD 帧后:

1. 校验 `VER_1 == version && CMD_CONNECT == command`(否则忽略)
2. 解析 `DST.ADDR / DST.PORT` → 调 `Util.readSocketAddress(in, false)` 拿到 **unresolved** `InetSocketAddress`(避免在 NioEventLoop 上做同步 DNS,后续 `Channels.open` 自己异步解析),再读 `ID`(1B LEN + UTF-8)
3. 构造 `backhaulHandshaker`:URI 与 SERVICE bus 相同,子协议 `BACKHAUL`,headers 带 `writeSignature(newReply(...REPLY_SUCCESS), secretKey)` 的 base64 作 token
4. `Channels.open(destination)` 拨内网目标 TCP(autoRead=false)
5. target connect 成功后,在 target 的 `channelActive` 里调 `Channels2.openWs` 拨第二条 WS 到 server
6. backhaul WS 握手完成(`HANDSHAKE_COMPLETE`)后 `setAutoRead(false)`,装 pipeline:
   - `destinationCtx` 替换为 `TcpOverWebSocketEncodeHandler(backhaulCtx)` —— 把内网 target 的裸 TCP 包成 BinaryFrame 发给 server
   - `backhaulCtx` 替换为 `TcpOverWebSocketDecodeHandler(destinationCtx)` —— 把 server 来的 BinaryFrame 拆成裸字节写给 target
   - 链路:`server <--ws--> agent <--tcp--> target`(backhaul 这段始终是 WebSocket,只有这一条路径)
7. 写一个 `EMPTY_BUFFER` 触发 backhaul 写就绪,最后 `setAutoRead(true)` 双向放流。同时挂 `destinationCtx.closeFuture()` → `backhaulCtx.close()`,target 关 → backhaul 也关

backhaul 握手或 target connect 失败:`backhaulPromise` 失败时通过 SERVICE bus 发 `REPLY_HOST_UNREACHABLE` 让 server 知道(无 auth tail);backhaul WS 失败也会顺手 `destinationCtx.close()`(条件 `isActive()`)。

backhaul WS 客户端 pipeline 上插了 `FlowControlHandler`(在 `WebSocketClientProtocolHandler` 之前),用于配合 `setAutoRead(false)`,握手完成前不漏帧。

### 5. 被动模式(`servlet/WebSocketBridgeEndpoint`,@Deprecated)

```
@ServerEndpoint("/ws/bridge")
```

协议常量和帧格式与主动模式一致(也走 `pangolin-common.Constants`),鉴权机制对齐主动模式(`Util.hmacSha256` + 同样的 `checkPayload` 实现);代码路径独立 —— 没有 `pipe` 也没有 backhaul,只是单条 WebSocket 直接桥接到内网目标。

**`@OnOpen` 流程**:

1. 从 `?access_token=...` 取 URL-Safe Base64 编码的 CONNECT 帧(带 auth tail)
2. `checkPayload(payload)`:帧长 + HMAC + TS 时间窗校验(NONCE 占位未落地,同 server 端)
3. 校验 `VER_1` / `CMD_CONNECT` / `RSV == 0`(失败 `session.close(...)` 后 return)
4. `Util.readSocketAddress(payload, true)` → `target`
5. `new NioEventLoopGroup(1)` + `Channels.open(target)` 拨内网目标
6. `.sync()` 阻塞等待 connect 完成,挂 closeFuture(target 关 → `session.close()` + `brGroup.shutdownGracefully()`)

**已知缺陷(已弃用,**不修复**)**:secret 用旧硬编码 fallback `"c254dacd0cde3be75ac2988f691ec105"`,与主动模式 / server 端的 `reverse(tunnelKey)+"^_^"+tunnelKey` 公式不一致,实际跑不通;每条连接 `new NioEventLoopGroup(1)`;无心跳。

### 6. 心跳保活

- **主动模式 SERVICE bus**:`WebSocketBridgeAgent#connect0` 装 `IdleStateHandler(60, 60, 60)`,`WebSocketBridgeAgentHandler#userEventTriggered` 收到 `IdleStateEvent` 直接 `writeAndFlush(new PingWebSocketFrame())`。与 server 端 `WebSocketKeepaliveHandler(60,60,60)` 双向互补,任意一端 60s 静默都会主动发 PING
- **主动模式 backhaul**:`Channels2.openWs` 拨号时**没有挂任何 keepalive handler**,server 端会在自己侧装 `WebSocketKeepaliveHandler(60,60,60)`,所以 backhaul 的 PING 是**单向的**(server → agent)。底层裸 TCP `SO_KEEPALIVE` 由 `pangolin-common/Channels.java#open` 写死开启
- **被动模式**:无心跳(已弃用)

---

## 嵌入 / 启动方式

**主动模式(嵌入到自定义 main)**:

```java
final WebSocketBridgeAgent agent = new WebSocketBridgeAgent(
    "node-1",
    "<secret-string>",                                             // 与 server 端对应 tunnelKey 的 secret 一致
    URI.create("ws://server:2345/tunnel/myGroup")
);
agent.start();
Runtime.getRuntime().addShutdownHook(new Thread(agent::shutdownGracefully));
```

或者直接用 `pangolin-agent-app`(Spring Boot 封装)/ `pangolin-agent-spring-boot-starter`(15s watchdog 自动起停;旧坐标 `pangolin-client-spring-boot-starter` 是 forwarder alias,新代码用 agent 这个)。

`WebSocketBridgeAgent#main` 是个 demo:

```java
new WebSocketBridgeAgent("Local", "321^_^123", URI.create("ws://localhost:2345/tunnel/123"))
```

`"321^_^123"` 与 server 端默认 secret 公式 `reverse("123") + "^_^" + "123"` 对齐,本机 `WebSocketBridgeServer#main` 直接跑得通。生产入口请走 agent-app,不要直接用 demo main。

---

## 潜在 BUG 排查

> 文件路径相对 `pangolin-agent/src/main/java/com/github/pangolin/agent/`。

### 设计说明(非 BUG,易误读)

**主动模式与被动模式协议相同但代码路径独立**
被动模式 `@Deprecated`,不跟进协议演进。不要在被动模式上叠加新功能;真要回头维护时,先评估是否值得复活。

**SERVICE 注册负载的 `BND.ADDR` 不参与路由**
Agent 在握手时塞了 `localAddress` 当 `BND.ADDR`,server 解析后存到 `agent.intranet`,但 `Engine.choose(tunnelKey)` 从不读它。仅用于运维 / 日志展示。

**默认 secret 公式公开**
agent watchdog 与 server 默认 provider 的 fallback 都是 `reverse(tunnelKey) + "^_^" + tunnelKey`,**用途仅是默认配置可跑通本地 demo**。生产部署必须显式覆盖(agent 侧 `spring.management.tunnelSecretKey`,server 侧 `-Dwebsocket.bridge.<tunnelKey>.secretKey`)。

### 🔴 高危

**1. NONCE 缓存未落地,30s 时间窗内可重放**
`WebSocketBridgeServerHandler#checkPayload` 与 `WebSocketBridgeEndpoint#checkPayload` 都是 `// TODO check nonce` 占位。同一 token 在 30s 时间窗内可被任意重放(HMAC + TS 校验通过即放行)。NONCE 字段双侧已写入(`SecureRandom`),但 server 没缓存比对。修法:以 `(tunnelKey, nonce)` 为 key 入 LRU/Caffeine,TTL=60s,命中即拒。

### 🟡 中危

**2. 主动模式重连可能短路**
`WebSocketBridgeAgent#reconnectIfNecessary`:

```java
if (!started.get() || (null != reconnectFuture && !reconnectFuture.isDone())) {
    return;
}
```

正常路径下 `reconnectFuture` 在调度的任务跑完后 isDone,无问题。但任务体里 `connect()` 抛 `IOException` 时 catch 后**再次调** `reconnectIfNecessary()` —— 此时**当前任务还没返回**,`reconnectFuture.isDone()` 仍为 `false`,直接 `return`,**永不再排队**。此后 Agent 不会重连。修法:catch 块里直接 `reconnectFuture = workerGroup.next().schedule(...)` 而不是递归调 `reconnectIfNecessary`。

**3. `WebSocketBridgeAgent` start/stop 加锁不对称**
`start()` 是 `synchronized`,`shutdownGracefully()` 不是。极端情况下 start 还在跑就被 stop。两者都用 `started.compareAndSet` + 状态机处理就够了。

### 🟢 低危 / 代码瑕疵

**4. 主动模式 backhaul 没挂 keepalive,只能依赖 server 单向 PING**
`WebSocketBridgeAgentHandler#pipe` 里 `Channels2.openWs(...)` 只装了一个匿名 `ChannelInboundHandlerAdapter`,没有 `IdleStateHandler`。server 端会装 `WebSocketKeepaliveHandler(60,60,60)`,所以 PING 仍能维持链路 —— 但是单向。考虑双向对称地在 backhaul 也挂一个 idle handler。

**5. `WebSocketBridgeAgent` 用默认 `NioEventLoopGroup()`**
`new NioEventLoopGroup()` 默认 `2 * cpu` 线程。Agent 只跑一条 SERVICE bus + 若干并发 backhaul,通常 1-2 线程足够,默认值偏多。考虑允许构造器传入 ELG。

**6. `WebSocketBridgeAgent.main` 用 `LockSupport.park()` 阻塞**
仅用于 demo,生产上需要 shutdown hook。已有 `shutdownGracefully()` 但 main 里没挂。

**7. `pipe` 里挂了两个 failure listener**
`WebSocketBridgeAgentHandler` 末尾 `.addListener(propagationOnFailure).addListener(ChannelFutureListener.CLOSE_ON_FAILURE)`。同一个 future 触发两次 fail handler:propagation 把异常传给 backhaulPromise,close 顺手关闭 channel。两者顺序虽然 OK,但语义重叠 —— `Channels.open` 的 `addListener(propagation)` 已经会让 `backhaulPromise` 失败,后续 `pipe` 流程会自己关 channel,显式 CLOSE_ON_FAILURE 是冗余的。

**8. 被动模式遗留代码**(已弃用,**不修**):
- `WebSocketBridgeEndpoint` 用旧硬编码默认 secret,无法与新版 server 对齐
- `WebSocketBridgeEndpoint.AuthenticationConfigurator` 是空 customizer
- `WebSocketEndpointLoaderListener#getAnnotatedEndpointClasses` 写死 `{WebSocketBridgeEndpoint.class}`
- 每条 client 连接 `new NioEventLoopGroup(1)`,无心跳

如要复活被动模式,这些都要重写。

---

## 其他 TODO / 非 BUG

- **`AGENT_VERSION = "1.3"` 与 server 不强校验**:server `Engine#registerAgent` 只验 `VER == VER_1`,不读 `AGN.INFO` 里的版本号,该字段当前仅用于运维侧诊断
- **`pangolin-agent-spring-boot-starter` 包名仍是 `com.github.pangolin.client.spring.boot.autoconfigure`**:模块改名时 Java 包没跟着改,内部代码无影响,只是不一致
- **被动模式的 `javax.websocket-api` / `javax.servlet-api` / `tyrus-container-grizzly-server` 依赖仍在 `pangolin-agent/pom.xml`**,即便实际不再用。如果决定彻底删被动模式,这些一并清掉
