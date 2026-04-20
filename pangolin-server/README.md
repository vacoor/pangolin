# pangolin-server

Pangolin 隧道系统的**服务端**。负责在公网暴露 WebSocket 接入点,承接外部访问者(Client)与内网接入代理(Agent),将两条 WebSocket 撮合成一条双向隧道,把流量桥接到内网任意目标;并在此基础上提供本地端口转发与交互式管理 Shell。

打包产物:Spring Boot fat jar —— `pangolind-<version>.jar`
技术栈:Netty + jline + Guava,依赖引入 ZooKeeper(当前源码未使用)。

---

## 目录结构

```
com/github/pangolin/server/
├── WebSocketBridgeServerApplication.java   Spring Boot 启动入口
├── WebSocketBridgeServer.java              Netty 服务器(默认 :2345 /tunnel)
├── WebSocketBridgeServerHandler.java       WS 子协议路由
├── WebSocketBridgeServerEngine.java        Agent 注册表 + 握手撮合引擎
├── WebSocketBridgeServerForwarder.java     本地端口 -> 隧道 转发器
└── shell/
    ├── ConsoleReaderFactory.java            jline ConsoleReader 构造(带补全)
    ├── WebSocketBridgeServerConsoleHandler  WebSocket <-> jline 控制台桥接
    └── WebSocketBridgeServerShell.java      命令解析(agent/forward/connection/exit)
```

依赖的 `NettyServer`、`TcpOverWebSocket*Handler`、`WebSocketInboundRedirectHandler`、`Channels`、`Util` 均位于 `pangolin-common`。

---

## 架构与关键流程

### 1. 启动

`WebSocketBridgeServer#start` 构造 Netty pipeline:

```
[SSL?]
  -> HttpServerCodec
  -> HttpObjectAggregator(max=8MB)
  -> WebSocketServerProtocolHandler(path=/tunnel, subprotocols="*")
  -> WebSocketBridgeServerHandler
```

`main` 默认在 `:2345` 监听 `/tunnel`,不启用 SSL。
注:出于浏览器 `permessage-deflate` 兼容性问题,压缩 Handler 被显式禁用。

### 2. 子协议分发

`WebSocketBridgeServerHandler#userEventTriggered` 根据 WebSocket `Sec-WebSocket-Protocol` 将连接分为四类:

| 子协议        | URI 形态                      | 作用                                                        |
| ------------- | ----------------------------- | ----------------------------------------------------------- |
| `SERVICE`     | `/tunnel/{tunnelKey}`         | Agent 注册隧道                                              |
| `""`          | `/tunnel/{tunnelKey}`         | Client 请求建立 WebSocket 透传隧道                          |
| `CONNECT`     | `/tunnel/{tunnelKey}`         | Client 请求建立隧道,并把自身降级为裸 TCP                   |
| `BACKHAUL`    | `/tunnel/{tunnelKey}/{id}`    | Agent 回连,对应 Engine 中待握手的 Connection               |
| `CONSOLE`     | `/tunnel/*`                   | 管理控制台(当前未接入鉴权)                                |

握手附加负载从 `Authorization: Bearer <token>` 或 `?access_token=<token>` 取出,按 URL-Safe Base64 解码后交给具体分支解析。

### 3. 协议格式(类 SOCKS5)

**Agent 注册(SERVICE):**

```
+-----+-------+-------+------+----------+----------+----------+----------+
| VER |  CMD  |  RSV  | ATYP | BND.ADDR | BND.PORT | AGN.NAME |  AGN.VER |
+-----+-------+-------+------+----------+----------+----------+----------+
|  1  | x'FF' | x'00' |  1   | Variable |    2     | Variable | Variable |
+-----+-------+-------+------+----------+----------+----------+----------+
```

**Client 建立连接(CONNECT,握手阶段由服务端发给 Agent):**

```
+-----+----------+-----+-------+------+----------+----------+
| VER | ID       | CMD |  RSV  | ATYP | DST.ADDR | DST.PORT |
+-----+----------+-----+-------+------+----------+----------+
|  1  | Variable |  1  | x'00' |  1   | Variable |    2     |
+-----+----------+-----+-------+------+----------+----------+
```

**Agent 响应:**

```
+-----+----------+-----+-------+------+----------+----------+
| VER | ID       | REP |  RSV  | ATYP | BND.ADDR | BND.PORT |
+-----+----------+-----+-------+------+----------+----------+
|  1  | Variable |  1  | x'00' |  1   | Variable |    2     |
+-----+----------+-----+-------+------+----------+----------+
```

`ATYP`:`0x01` IPv4 / `0x03` 域名 / `0x04` IPv6。

兼容旧版 Agent(`version == "1.0"`):服务端改发文本帧 `<id>->tcp://<host>:<port>`。

### 4. 握手撮合(`WebSocketBridgeServerEngine`)

内部两张并发表:

- `registeredAgents`: `tunnelKey -> Agent`
- `connections`: `accessChannelId -> Connection`(accessCtx、target、handshakePromise)

Agent 选择策略:先按 `tunnelKey` 精确匹配;不中则在所有 `agent.name == tunnelKey` 的候选里随机挑一台。

Client 请求到达后的时序:

1. `accessCtx.setAutoRead(false)`,生成 `Connection` 放入 `connections`
2. 通过 Agent 的 `bus` 下发 SOCKS5 风格 CONNECT
3. 启动 10s 握手超时定时器(`DEFAULT_HANDSHAKE_TIMEOUT_MILLIS`)
4. 等待对端 `BACKHAUL` 子协议回连,触发 `finishHandshake`
5. `handshakePromise` 完成,双方 closeFuture 互相挂钩:一端关,另一端 `writeAndFlush(EMPTY_BUFFER) + CLOSE`

### 5. 管道拼接

握手成功后,`WebSocketBridgeServerHandler#handshake0` 替换 pipeline 终端 handler:

- **WebSocket 透传**:双向 `WebSocketInboundRedirectHandler`
- **TCP 降级**:Client 侧切成 `TcpOverWebSocketEncodeHandler`,**移除 access 侧 WS codec**(`wsencoder` / `wsdecoder` / `Utf8FrameValidator` / `WebSocketServerProtocolHandler`);Agent 侧(backhaul) 切成 `TcpOverWebSocketDecodeHandler`,**WS codec 保留**

最后两端 `setAutoRead(true)` 开启数据流。实际链路形态:

```
Client <--ws--> Server <--ws--> Agent        (WS 透传)
Client <--tcp--> Server <--ws--> Agent       (当前代码下的 "TCP 降级")
```

注意:Agent 端 `WebSocketBridgeAgentHandler#pipe` 其实支持**整条链路全 TCP**(`server<--tcp-->agent<--tcp-->target`),触发条件是 CMD_CONNECT 帧的 **RSV != 0**。但 server 端 `Engine.handshake0` 写 RSV 时硬编码 0,`Handler.handshake0` 的 `downgrade` 标志也未经 `Engine.handshake` 传递下去。**结果是 agent 侧 backhaul 始终保持 WebSocket**,全 TCP 分支是**死代码**(详见 BUG #14)。

### 6. 端口转发(`WebSocketBridgeServerForwarder`)

本地 `ServerSocket` 监听,连接到达时直接复用 `engine.handshake(...)` 走 TCP 降级通道;等价于 Client 通过 `CONNECT` 进来,但省掉 WebSocket 协议本身。

### 7. 管理 Shell

`CONSOLE` 子协议被替换为 `WebSocketBridgeServerConsoleHandler`:

- 入站:WS 文本/二进制帧写入 `PipedOutputStream`,喂给 jline `ConsoleReader`
- 识别 `ESC[8;rows;cols t` 序列动态调整窗口尺寸
- 出站:`WebSocketBinaryOutput` 使用 `writeAndFlush(...).sync()`(代码注释提示不同步会丢数据)

支持命令:

```
agent list
agent remove <id...>

forward list
forward add local_port:agent_key:remote_host:remote_port
forward remove local_port

connection list
connection kill <id...>

exit | quit
```

---

## 启动方式

```bash
# 默认配置:监听 :2345 /tunnel,不启用 SSL
java -jar pangolind-<version>.jar
```

也可在代码中直接实例化 `new WebSocketBridgeServer(host, port, "/tunnel", useSsl).start()`。

---

## 潜在 BUG 排查

> 以下为代码走读发现的疑似 BUG,按严重程度排序。文件路径相对 `pangolin-server/src/main/java/com/github/pangolin/server/`。

### 设计说明(非 BUG,易误读)

**`Engine.choose(tunnelKey)` 的双重寻址模式**
`registeredAgents` 以 `agent.id`(Netty channel id)为 key,`choose` 的两个分支分别对应两种寻址:

- `registeredAgents.get(tunnelKey)` → 当 client 传入的 `tunnelKey` **恰好是某个 Agent 的 id** 时精确命中,等价于"指定某一台 Agent"
- fallback 遍历 `candidate.getName().equals(tunnelKey)` → 将 `tunnelKey` 视为**组名**,在同 `agent.name` 的多实例中随机挑一台

即 client 端 `tunnelKey` 语义被重载:**既可以是 Agent id(精确),也可以是 Agent name(组,按组做随机负载均衡)**。`Agent.tunnelKey`(SERVICE 注册 URL 的 `{tunnelKey}`)仅用于日志/运维展示,未参与路由。

### 🔴 高危

**1. `agentResponded` 在协议不匹配时直接抛异常**
`WebSocketBridgeServerEngine.java:406` `Preconditions.checkState(VER_1_1 == version, ...)`。Agent 若发来任意非 v1.1 的 `BinaryWebSocketFrame`(v1.0 Agent、伪造/畸形负载)会抛 `IllegalStateException`,走到 `exceptionCaught`,连带关闭整个 WebSocket。应该改为校验失败就丢弃该帧或按握手失败处理,不要影响后续流量。

**2. 管理控制台完全无身份认证**
`WebSocketBridgeServerHandler.java:124` `CONSOLE` 分支注释 `XXX authorize / FIXME authenticate`,任何能连接 `/tunnel` 并在 `Sec-WebSocket-Protocol` 选上 `CONSOLE` 的客户端都能进入管理 Shell,可以列出/杀连接、增删端口转发、踢 Agent。**严禁直接暴露到公网**。

**3. 握手 token 仅 Base64 解码,无任何校验**
`WebSocketBridgeServerHandler.java:143` `getHandshakePayload` 从 `Authorization: Bearer <x>` 或 `?access_token=<x>` 取出 URL-Safe Base64,解码后直接当 SOCKS5 负载用;没有签名/白名单/密钥比对。任意客户端都可以注册 Agent / 发起 CONNECT。

**4. 长连接无心跳/空闲保活,经过中间链路会被静默断开**
`WebSocketBridgeServer.java:81` pipeline 没有 `IdleStateHandler`,代码也未主动发 `PingWebSocketFrame`(Netty 的 `WebSocketServerProtocolHandler` 只被动回 PONG,不主动发 PING);TCP 降级后的 access 侧也未开启 `SO_KEEPALIVE`。
现象:通过 pangolin-server 建立的隧道空闲一段时间(通常 30s~5min,取决于中间 NAT/LB/防火墙/云 LB 的空闲超时)就被静默断开。一端被中间设备丢掉,另一端因 `Engine.handshake` / Forwarder 里挂的双向 closeFuture 级联被 server 主动关闭,给人的感觉就是"只要空闲就断"。
修复方向(见"保活策略"小节):
- server↔agent 的 WS 链路:`IdleStateHandler` + 周期性 `PingWebSocketFrame`(agent 端 `WebSocketBridgeAgentHandler#userEventTriggered` 已经处理 `IdleStateEvent`,互补即可);
- access 侧降级 TCP:`ServerBootstrap.childOption(SO_KEEPALIVE, true)` + 运维侧调小 `tcp_keepalive_time`。

### 🟡 中危

**5. Spring Boot 与 Netty 双启动,生命周期脱节**
`WebSocketBridgeServerApplication.java:11` 先 `SpringApplication.run(...)` 再 `WebSocketBridgeServer.main(args)`,后者 `channel.closeFuture().sync()` 永久阻塞主线程。Spring 容器不感知这个 Netty server,`@PreDestroy` / shutdown hook 不会优雅关闭 Netty;端口、event loop 的参数也没走配置化,和 Spring Boot 的价值脱节。

**6. TCP 降级依赖硬编码的 Netty handler 名称**
`WebSocketBridgeServerHandler.java:333-337` 通过字符串 `"wsencoder"` / `"wsdecoder"` 从 pipeline 移除 handler。这些名字来自 Netty `WebSocketServerHandshaker` 内部实现,一旦升级 Netty 改名,运行时直接 `NoSuchElementException`。建议改为按 `Class` 移除或遍历清理。

**7. `WebSocketBinaryOutput#write` 调用 `writeAndFlush(...).sync()` 存在死锁隐患**
`WebSocketBridgeServerConsoleHandler.java:102`。当前从 Shell 线程调用无事,但若被任何 EventLoop 线程调用(比如将来把命令执行挪到 pipeline),`.sync()` 会等待自己所在 loop 的任务完成,造成死锁。建议去掉 `.sync()`,通过背压/Promise 链处理。

**8. `Engine.handshake` 超时失败时不由 Engine 自身关闭 access 连接**
`WebSocketBridgeServerEngine.java:182-197` 超时后仅 `tryFailure`,依赖 Handler/Forwarder 在 listener 里关闭。目前两处调用方都有关闭逻辑,但 Engine 内部状态(`connections` map)的清理完全依赖 access channel close 事件。如果今后出现某个不关闭 access 的调用方,就会泄漏 `Connection`。

**9. `finishHandshake` 未校验 tunnelKey**
`WebSocketBridgeServerHandler.java:356-359` 和 `Engine.java:427` 仅按 `id` 查表匹配,URI 中的 `tunnelKey` 只用于日志。攻击者如果能猜到或穷举 connection id,理论上可以用任意 `tunnelKey` 完成 BACKHAUL,接管别人的握手。

**10. TCP 降级意图未经 RSV 传递给 Agent,Agent 全 TCP 降级分支是死代码**
`WebSocketBridgeServerHandler.java:107` 识别 `CONNECT` 子协议时得到 `downgrade=true`,只用来改造自己和 client 之间的 pipeline;但 `Engine.handshake(...)` 签名里没有 `downgrade` 参数,`Engine.handshake0` 写 RSV 时硬编码:
```java
buffer.writeByte(CMD_CONNECT);
buffer.writeByte(0);   // RSV 恒为 0
```
而 agent 端 `WebSocketBridgeAgentHandler.java:180` 判断是否全 TCP 降级的条件是 `0 != rsv`:
```java
pipe(destination, backhaulHandshaker, ..., 0 != rsv);
```
永不成立。结果:
- agent `pipe` 的 `else` 分支(`TcpInboundRedirectHandler` + 移除 backhaul WS codec)**从未被触发**,属死代码
- 实际 "TCP 降级" 只作用于 **client↔server** 一段,server↔agent 仍然是 WebSocket,**与 agent 源码注释画的全 TCP 链路不一致**

两种修法二选一:
- 若保留 agent 死代码作为未来能力:让 `Handler.handshake0` 把 `downgrade` 传入 `Engine.handshake`,在 `handshake0` 里写 `buffer.writeByte(downgrade ? 1 : 0)`
- 若不再需要全 TCP:直接删掉 agent `pipe` 的 else 分支与 `TcpInboundRedirectHandler`,server↔agent 保持 WS 反而**便于实现 WebSocket 心跳**(见 BUG #4 修复方向)

### 🟢 低危 / 代码瑕疵

**11. `WebSocketBridgeServer.boundChannel` 字段未使用**
`WebSocketBridgeServer.java:43` 声明但 `start()` 直接返回 `super.start(...).sync().channel()`,该字段从未被赋值 — 属死代码。

**12. `forward` 帮助信息里的 `alias` 子命令无实现**
`WebSocketBridgeServerShell.java:202` usage 里列出 `alias                                 List alias for forward target hostname`,但 `doExecuteForwardCommand` 没有对应分支。

**13. `Engine.Connection` 是非静态内部类,无必要地持有外部 Engine 引用**
`Agent` 已是 `static`,`Connection` 应一并改为 static。

**14. `Forwarder.addForwarding` 中 `accessCtx.fireChannelActive()` 调用无意义**
`WebSocketBridgeServerForwarder.java:93` — 该 handler 是 pipeline 最后一个,`fireChannelActive` 没有下游可传递。

**15. `Engine.handshake` 内部 `setAutoRead(false)` 与 `Handler.handshake0` 中重复设置**
`WebSocketBridgeServerHandler.java:291` + `WebSocketBridgeServerEngine.java:177`,逻辑不错但显得冗余,任一处都足够。

---

## 保活策略(针对 BUG #4 的落地方案)

### 前提判断

- **server↔agent 的 backhaul**:当前代码下恒为 WebSocket(因为 BUG #10 中 agent 全 TCP 降级永不触发)。此链路通常跨公网/代理,是空闲断开的主要源头
- **agent 的注册 bus(SERVICE 子协议)**:同样跨公网、恒为 WebSocket,需要同等心跳
- **client↔server 的 access**:
  - WS 透传:WebSocket,可以挂 WS PING
  - TCP 降级:裸 TCP(access pipeline 已无 WS codec),**不能**发 `PingWebSocketFrame`(会当普通字节发给 client,破坏上层协议)
- **Forwarder 本地端口转发的 access**:裸 TCP,且通常在本机/内网,NAT 问题较小

### 推荐方案

**A. WebSocket 侧(backhaul + SERVICE bus):主动 PING**
在 `WebSocketBridgeServerHandler` 处理 `SERVICE` 注册与 `BACKHAUL` 回连成功、替换终端 handler **之前**插入:

```java
ctx.pipeline().addBefore(/*终端 handler 之前*/,
    "idle", new IdleStateHandler(0, 30, 0));  // 30s writer idle
ctx.pipeline().addBefore(/*终端 handler 之前*/,
    "heartbeat", new ChannelDuplexHandler() {
        @Override public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            if (evt instanceof IdleStateEvent && ((IdleStateEvent) evt).state() == WRITER_IDLE) {
                ctx.writeAndFlush(new PingWebSocketFrame());
            } else {
                super.userEventTriggered(ctx, evt);
            }
        }
    });
```

注意事项:
- 必须加在 `TcpOverWebSocketDecodeHandler` / `WebSocketInboundRedirectHandler` **之前**,避免被上述终端 handler 的 `channelRead` 短路(例如 `TcpOverWebSocketDecodeHandler` 对 WS 帧是直接转发 content,对 PING 已有回 PONG 逻辑)
- 周期 ≤ 中间链路最短空闲超时 / 2。公网场景通常选 15~30s
- agent 端 `WebSocketBridgeAgentHandler#userEventTriggered` 已实现 `IdleStateEvent` → PING 的逻辑,双向心跳自动互补
- Netty 的 `WebSocketServerProtocolHandler` 会自动把 PONG 吞掉,不会污染业务 handler

**B. TCP 降级/Forwarder 的 access 侧:SO_KEEPALIVE 兜底**

```java
// WebSocketBridgeServer.start(...) 里
serverBootstrap.childOption(ChannelOption.SO_KEEPALIVE, true);

// WebSocketBridgeServerForwarder.addForwarding(...) 里
Channels.listen(...) 同样需要设置 childOption
```

配合运维侧调小内核参数(Linux):
```
sysctl -w net.ipv4.tcp_keepalive_time=60
sysctl -w net.ipv4.tcp_keepalive_intvl=15
sysctl -w net.ipv4.tcp_keepalive_probes=3
```

Netty 4 不支持单连接级别设置 keepalive 间隔,只能靠内核全局参数,精度不如 WS 层 PING。

**C. 不要在裸 TCP 流里注入应用层心跳字节**
会破坏被隧道的上层协议(SSH/DB/HTTP),除非重新定义一个带封帧的子协议 — 和"裸 TCP 透传"的设计初衷冲突。

### 真正做到全 TCP 降级时(若未来修 BUG #10)

backhaul 也变裸 TCP 后,WS PING 方案全部失效,只剩:
- 三段都开 `SO_KEEPALIVE`
- 或者放弃"纯透传"语义,在 backhaul 上加一个带心跳的自定义封帧

比较之下,**保留 server↔agent 的 WebSocket 通道**(即删掉 agent 死代码分支)是更干净的路径:便于心跳、便于将来加控制帧、对性能几乎无损(WS 封帧开销极小)。

---

## 其他 TODO / 非 BUG

- **ZooKeeper 依赖未使用**:`pom.xml` 引入 `zookeeper:3.6.0`,疑似预留给多节点注册中心
- **监听地址/端口/endpoint 未配置化**:`WebSocketBridgeServer.main` 里硬编码 `2345`、`/tunnel`、`useSsl=false`
- **缺少统一的协议常量**:`WebSocketBridgeServerHandler` 与 `WebSocketBridgeServerEngine` 各自声明了一套 `VER/CMD/ATYP/REPLY_*`,建议抽到 `pangolin-common`
