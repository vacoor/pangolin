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
├── WebSocketKeepaliveHandler.java          IdleStateHandler + 主动 PING 心跳
└── shell/
    ├── ConsoleReaderFactory.java                jline ConsoleReader 构造(带补全)
    ├── WebSocketBridgeServerConsoleHandler.java WebSocket <-> jline 控制台桥接
    └── WebSocketBridgeServerShell.java          命令解析(agent/forward/connection/exit)
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

`WebSocketBridgeServerHandler#userEventTriggered` 根据 WebSocket `Sec-WebSocket-Protocol` 将连接分为五类:

| 子协议        | URI 形态                      | 作用                                                        |
| ------------- | ----------------------------- | ----------------------------------------------------------- |
| `SERVICE`     | `/tunnel/{tunnelKey}`         | Agent 注册隧道                                              |
| `""`          | `/tunnel/{tunnelKey}`         | Client 请求建立 WebSocket 透传隧道                          |
| `CONNECT`     | `/tunnel/{tunnelKey}`         | Client 请求建立隧道,并把自身降级为裸 TCP                   |
| `BACKHAUL`    | `/tunnel/{tunnelKey}/{id}`    | Agent 回连,对应 Engine 中待握手的 Connection               |
| `CONSOLE`     | `/tunnel/*`                   | 管理控制台(`isConsoleAllowed` 当前永远 return true,见 BUG #2) |

握手附加负载从 `Authorization: Bearer <token>` 或 `?access_token=<token>` 取出,按 URL-Safe Base64 解码后交给具体分支解析。

### 3. 协议格式(类 SOCKS5)

协议常量集中在 `WebSocketBridgeServerEngine`:`VER_1 = 0x01`、`CMD_CONNECT = 0x01`、`CMD_SERVICE = 0xFF`、`RSV = 0x00`、`ATYPE_IPv4/DOMAIN/IPv6 = 0x01/0x03/0x04`、回复码 `REPLY_SUCCESS=0x00 / REPLY_FAILURE=0x01 / REPLY_FORBIDDEN=0x02 / REPLY_NETWORK_UNREACHABLE=0x03 / REPLY_HOST_UNREACHABLE=0x04 / REPLY_CONNECTION_REFUSED=0x05 / REPLY_TTL_EXPIRED=0x06 / REPLY_COMMAND_UNSUPPORTED=0x07 / REPLY_ADDRESS_UNSUPPORTED=0x08`。

**Agent 注册(SERVICE):**

```
+-----+-------+-------+------+----------+----------+----------+----------+
| VER |  CMD  |  RSV  | ATYP | BND.ADDR | BND.PORT | AGN.NAME |  AGN.VER |
+-----+-------+-------+------+----------+----------+----------+----------+
|  1  | x'FF' | x'00' |  1   | Variable |    2     | Variable | Variable |
+-----+-------+-------+------+----------+----------+----------+----------+
```

**Client → Server 的 CONNECT 请求**(WS 握手时通过 `Authorization: Bearer <base64>` 或 `?access_token=<base64>` 携带,**不含 ID**):

```
+-----+-----+-------+------+----------+----------+
| VER | CMD |  RSV  | ATYP | DST.ADDR | DST.PORT |
+-----+-----+-------+------+----------+----------+
|  1  |  1  | x'00' |  1   | Variable |    2     |
+-----+-----+-------+------+----------+----------+
```

由 `WebSocketBridgeServerHandler#handshake` 解析。ID 字段由 server 用 access channel 的 Netty channel id 字符串自行补齐。

**Server → Agent 的 CONNECT 转发**(通过 Agent 的 SERVICE bus 下发,**带上 ID**):

```
+-----+----------+-----+-------+------+----------+----------+
| VER | ID       | CMD |  RSV  | ATYP | DST.ADDR | DST.PORT |
+-----+----------+-----+-------+------+----------+----------+
|  1  | Variable |  1  | x'00' |  1   | Variable |    2     |
+-----+----------+-----+-------+------+----------+----------+
```

由 `WebSocketBridgeServerEngine#handshake0` 编码。`ID` 是 1 字节长度前缀 + UTF-8 字节(用 access channel 的 Netty channel id 字符串)。

**Agent → Server 的 Reply:**

```
+-----+----------+-----+-------+------+----------+----------+
| VER | ID       | REP |  RSV  | ATYP | BND.ADDR | BND.PORT |
+-----+----------+-----+-------+------+----------+----------+
|  1  | Variable |  1  | x'00' |  1   | Variable |    2     |
+-----+----------+-----+-------+------+----------+----------+
```

由 `WebSocketBridgeServerEngine#agentResponded` 解析,但只读到 `RSV` 为止 —— `ATYP / BND.ADDR / BND.PORT` 当前未被服务端使用(agent 端 `newReply` 仍会写,占位 `0x01 / 0.0.0.0 / 0`)。

`ATYP`:`0x01` IPv4 / `0x03` 域名 / `0x04` IPv6。

### 4. 握手撮合(`WebSocketBridgeServerEngine`)

内部两张并发表:

- `registeredAgents`: `agent.id (Netty channel id)` -> `Agent`
- `connections`: `accessChannelId` -> `Connection { id, agent, source, target, accessCtx, handshakePromise }`

#### Agent 选择策略 — `Engine.choose(tunnelKey)` 双重寻址

```java
// 1) 精确匹配 agent.id
Agent agent = registeredAgents.get(tunnelKey);
if (null != agent) return agent;

// 2) 按 agent.tunnelKey(SERVICE 注册 URL 路径)分组,组内随机
for (Agent candidate : registeredAgents.values()) {
    if (candidate.getTunnelKey().equals(tunnelKey)) candidates.add(candidate);
}
return !candidates.isEmpty() ? candidates.get(random) : null;
```

即 client 端 `tunnelKey` 语义被重载:**既可以是 Agent id(精确指定一台),也可以是 SERVICE 注册时使用的 tunnelKey(组,组内随机负载均衡)**。`Agent.name`(SERVICE 负载里的 AGN.NAME 字段)目前**仅用于日志/运维展示,未参与路由**。

#### Client 请求时序

1. `accessCtx.setAutoRead(false)`,生成 `Connection` 放入 `connections`
2. 通过 Agent 的 `bus` 下发 SOCKS5 风格 CONNECT
3. 启动 10s 握手超时定时器(`DEFAULT_HANDSHAKE_TIMEOUT_MILLIS`)
4. 等待对端 `BACKHAUL` 子协议回连,触发 `finishHandshake`
   - **同时校验 URI 上的 `tunnelKey` 与 `connection.agent.tunnelKey` 一致**(否则拒绝接管)
5. `handshakePromise` 完成,双方 closeFuture 互相挂钩:一端关,另一端 `writeAndFlush(EMPTY_BUFFER) + CLOSE`

### 5. 管道拼接

握手成功后,`WebSocketBridgeServerHandler#handshake0` 替换 pipeline 终端 handler:

- **WebSocket 透传**:access、backhaul 两侧均装 `WebSocketInboundRedirectHandler`,WS 帧原样转发,两段都保留 WS codec
- **TCP 降级(仅作用于 access 一段)**:
  - access 侧:**移除** WS codec(`wsencoder` / `wsdecoder` / `Utf8FrameValidator` / `WebSocketServerProtocolHandler`),终端 handler 换成 `TcpOverWebSocketEncodeHandler`,把 access 收到的裸 TCP 字节包装成 `BinaryWebSocketFrame` 转给 backhaul
  - backhaul 侧:WS codec **完全保留**,只是把对端转发逻辑从"原样转发 WS 帧"换成"剥出 BinaryFrame 内容作为裸字节写到 access"(即终端 handler 换成 `TcpOverWebSocketDecodeHandler`)
  - 即 **backhaul 在线路上始终是 WebSocket**,降级只改变 client↔server 这一段的线路形态

无论是否降级,backhaul 都会在终端 handler **之前**插入 `WebSocketKeepaliveHandler(60, 60, 60)` 作为隧道侧心跳(详见 §7)。

最后两端 `setAutoRead(true)` 开启数据流。实际链路形态:

```
Client <--ws--> Server <--ws--> Agent        (WS 透传)
Client <--tcp--> Server <--ws--> Agent       (当前代码下的 "TCP 降级",仅 client↔server 段是 TCP)
```

注意:Agent 端 `WebSocketBridgeAgentHandler#pipe` 其实支持**整条链路全 TCP**(`server<--tcp-->agent<--tcp-->target`),触发条件是 CMD_CONNECT 帧的 **RSV != 0**。但 server 端 `Engine.handshake0` 写 RSV 时硬编码 0,`Handler.handshake0` 的 `downgrade` 标志也未经 `Engine.handshake` 传递下去。**结果是 agent 侧 backhaul 始终保持 WebSocket**,全 TCP 分支是**死代码**(详见 BUG #8)。

### 6. 端口转发(`WebSocketBridgeServerForwarder`)

本地 `ServerSocket` 监听,连接到达时直接复用 `engine.handshake(...)` 走 TCP 降级通道(access 侧装 `TcpOverWebSocketEncodeHandler`,backhaul 侧装 `TcpOverWebSocketDecodeHandler`,并同样在 backhaul 终端 handler 前 `addBefore` 一个 `WebSocketKeepaliveHandler(60, 60, 60)`);等价于 Client 通过 `CONNECT` 进来,但省掉 WebSocket 协议本身。

### 7. 心跳保活(`WebSocketKeepaliveHandler`)

`IdleStateHandler` + `IdleStateEvent → PingWebSocketFrame` 的最小封装(PONG 由 `WebSocketServerProtocolHandler` 自动处理)。

**安装位置(全部 60/60/60):**

| 位置                                          | 形态                                                                                          |
| --------------------------------------------- | --------------------------------------------------------------------------------------------- |
| Agent SERVICE bus(`registerAgent0`)           | 匿名子类**替换** agent 终端 handler,顺带 override `channelRead` 处理 `agentResponded` 帧 |
| Backhaul(`Handler#handshake0`)                | `addBefore` backhaul 终端 redirect/encode handler(WS 透传与 TCP 降级两条路径都装)       |
| Access(`Handler#handshake0`,**仅 WS 透传**)  | `addBefore` access 终端 `WebSocketInboundRedirectHandler`(`!downgrade` 分支)               |
| Forwarder backhaul(`Forwarder#addForwarding`) | `addBefore` backhaul 终端 `TcpOverWebSocketDecodeHandler`                                     |

约束:必须挂在终端 redirect handler **之前**,否则 PING 会被直接转发到对端而非本地回 PONG。agent 端 `WebSocketBridgeAgentHandler#userEventTriggered` 同样响应 `IdleStateEvent → PING`,server↔agent 双向都主动发 PING。

**access 段保活现状(详见 BUG #3):**

- WS 透传:已挂 `WebSocketKeepaliveHandler(60,60,60)`
- TCP 降级 / Forwarder 转发:裸 TCP 装不了 WS PING,只有 `pangolin-common/Channels.java:53` 写死的 `childOption(SO_KEEPALIVE, true)` 兜底 —— 但 Linux 默认 `tcp_keepalive_time=7200s`,需运维侧调小内核参数才有意义

### 8. 管理 Shell

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
- fallback 遍历 `candidate.getTunnelKey().equals(tunnelKey)` → 将 `tunnelKey` 视为**组名**,在 SERVICE 注册时用同一 `tunnelKey` 的多实例中随机挑一台

即 client 端 `tunnelKey` 语义被重载:**既可以是 Agent id(精确),也可以是 Agent 注册路径上的 tunnelKey(组,按组做随机负载均衡)**。`Agent.name` 当前未参与路由。

### 🔴 高危

**1. 管理控制台无实质鉴权**
`WebSocketBridgeServerHandler.java:156` `isConsoleAllowed` 当前永远 `return true`,代码里有注释掉的 `access_token` 校验占位但未启用。任何能连接 `/tunnel` 并在 `Sec-WebSocket-Protocol` 选上 `CONSOLE` 的客户端都能进入管理 Shell,可以列出/杀连接、增删端口转发、踢 Agent。**严禁直接暴露到公网**。

**2. 握手 token 仅 Base64 解码,无任何校验**
`WebSocketBridgeServerHandler.java:170` `getHandshakePayload` 从 `Authorization: Bearer <x>` 或 `?access_token=<x>` 取出 URL-Safe Base64,解码后直接当 SOCKS5 负载用;没有签名/白名单/密钥比对。任意客户端都可以注册 Agent / 发起 CONNECT。

**3. 裸 TCP access 段只有内核 keepalive 兜底,默认参数等于没开**
当前已有的应用层 PING(见 §7):

- agent SERVICE bus + backhaul 都装了 `WebSocketKeepaliveHandler(60,60,60)`,server↔agent 整条 WS 链路双向互补
- WS 透传 access 也已挂 `WebSocketKeepaliveHandler(60,60,60)`(`Handler#handshake0` 的 `!downgrade` 分支)

**仍未覆盖的:** TCP 降级后的 access、Forwarder 端口转发的 access 都是裸 TCP,装不了 WS PING。`pangolin-common/Channels.java:53` 的 `childOption(SO_KEEPALIVE, true)` 提供 OS 层兜底,但 Linux 默认 `tcp_keepalive_time=7200s`,生产上**几乎等于没开**。

修复方向:运维侧调小内核全局参数:

```
sysctl -w net.ipv4.tcp_keepalive_time=60
sysctl -w net.ipv4.tcp_keepalive_intvl=15
sysctl -w net.ipv4.tcp_keepalive_probes=3
```

Netty 4 不支持单连接级别配置 keepalive 间隔,只能依赖内核全局参数。

### 🟡 中危

**4. Spring Boot 与 Netty 双启动,生命周期脱节**
`WebSocketBridgeServerApplication.java:13` 先 `SpringApplication.run(...)` 再 `WebSocketBridgeServer.main(args)`,后者 `channel.closeFuture().sync()` 永久阻塞主线程。Spring 容器不感知这个 Netty server,`@PreDestroy` / shutdown hook 不会优雅关闭 Netty;端口、event loop 的参数也没走配置化,和 Spring Boot 的价值脱节。

**5. TCP 降级依赖硬编码的 Netty handler 名称**
`WebSocketBridgeServerHandler.java:402-403` 通过字符串 `"wsencoder"` / `"wsdecoder"` 从 pipeline 移除 handler。这些名字来自 Netty `WebSocketServerHandshaker` 内部实现,一旦升级 Netty 改名,运行时直接 `NoSuchElementException`。建议改为按 `Class` 移除或遍历清理(`Utf8FrameValidator`、`WebSocketServerProtocolHandler` 已经是按 Class 移的)。

**6. `WebSocketBinaryOutput#write` 调用 `writeAndFlush(...).sync()` 存在死锁隐患**
`WebSocketBridgeServerConsoleHandler.java:102`。当前从 Shell 线程调用无事,但若被任何 EventLoop 线程调用(比如将来把命令执行挪到 pipeline),`.sync()` 会等待自己所在 loop 的任务完成,造成死锁。建议去掉 `.sync()`,通过背压/Promise 链处理。

**7. `Engine.handshake` 超时失败时不由 Engine 自身关闭 access 连接**
`WebSocketBridgeServerEngine.java:194-205` 超时后仅 `tryFailure`,依赖 Handler/Forwarder 在 listener 里关闭。目前两处调用方都有关闭逻辑,但 Engine 内部状态(`connections` map)的清理完全依赖 access channel close 事件。如果今后出现某个不关闭 access 的调用方,就会泄漏 `Connection`。

**8. TCP 降级意图未经 RSV 传递给 Agent,Agent 全 TCP 降级分支是死代码**
`WebSocketBridgeServerHandler.java:117` 识别 `CONNECT` 子协议时得到 `downgrade=true`,只用来改造自己和 client 之间的 pipeline;但 `Engine.handshake(...)` 签名里没有 `downgrade` 参数,`Engine.handshake0` 写 RSV 时硬编码:
```java
buffer.writeByte(CMD_CONNECT);
buffer.writeByte(0);   // RSV 恒为 0
```
而 agent 端 `WebSocketBridgeAgentHandler` 判断是否全 TCP 降级的条件是 `0 != rsv`:
```java
pipe(destination, backhaulHandshaker, ..., 0 != rsv);
```
永不成立。结果:
- agent `pipe` 的 `else` 分支(`TcpInboundRedirectHandler` + 移除 backhaul WS codec)**从未被触发**,属死代码
- 实际 "TCP 降级" 只作用于 **client↔server** 一段,server↔agent 仍然是 WebSocket,**与 agent 源码注释画的全 TCP 链路不一致**

两种修法二选一:
- 若保留 agent 死代码作为未来能力:让 `Handler.handshake0` 把 `downgrade` 传入 `Engine.handshake`,在 `handshake0` 里写 `buffer.writeByte(downgrade ? 1 : 0)`
- 若不再需要全 TCP:直接删掉 agent `pipe` 的 else 分支与 `TcpInboundRedirectHandler`,server↔agent 保持 WS 反而**便于实现 WebSocket 心跳**(见 BUG #3 修复方向)

### 🟢 低危 / 代码瑕疵

**9. `WebSocketBridgeServer.boundChannel` 字段未使用**
`WebSocketBridgeServer.java:43` 声明但 `start()` 直接返回 `super.start(...).sync().channel()`,该字段从未被赋值 — 属死代码。

**10. `forward` 帮助信息里的 `alias` 子命令无实现**
`WebSocketBridgeServerShell.java:202` usage 里列出 `alias                                 List aliases for forwarding target hostnames`,但 `doExecuteForwardCommand` 没有对应分支。

**11. `Engine.Connection` 是非静态内部类,无必要地持有外部 Engine 引用**
`Agent` 已是 `static`,`Connection` 应一并改为 static。

**12. `Forwarder.addForwarding` 中 `accessCtx.fireChannelActive()` 调用无意义**
`WebSocketBridgeServerForwarder.java:118` — 该 handler 是 pipeline 最后一个,`fireChannelActive` 没有下游可传递。

**13. `Engine.handshake` 内部 `setAutoRead(false)` 与 `Handler.handshake0` 中重复设置**
`WebSocketBridgeServerHandler.java:360` + `WebSocketBridgeServerEngine.java:189`,逻辑不错但显得冗余,任一处都足够。

---

## 保活策略(针对 BUG #3 的落地方案)

### 前提判断

- **server↔agent 的 SERVICE bus 与 backhaul**:挂了 `WebSocketKeepaliveHandler(60,60,60)`,agent 端 `IdleStateEvent → PING` 双向互补
- **server↔agent 全程 WebSocket**(因为 BUG #8 中 agent 全 TCP 降级永不触发),心跳方案有效
- **client↔server 的 access**:
  - WS 透传:已挂 `WebSocketKeepaliveHandler(60,60,60)`(`Handler#handshake0` 的 `!downgrade` 分支)
  - TCP 降级:裸 TCP(access pipeline 已无 WS codec),**不能**发 `PingWebSocketFrame`(会当普通字节发给 client,破坏上层协议),只能靠 `SO_KEEPALIVE`
- **裸 TCP 段的 `SO_KEEPALIVE`**:已经在 `pangolin-common/Channels.java:53` 的 `Channels.listen(...)` 里写死(`childOption(SO_KEEPALIVE, true)` + `TCP_NODELAY`),所以主 server 与 Forwarder 的 access 段都自动开了。问题只剩 Linux 默认 `tcp_keepalive_time=7200s` 太长,需要运维侧调小

### 推荐方案

**A. 调小内核 keepalive 参数,激活已有的 `SO_KEEPALIVE`**

`Channels.listen` 已经开了 `SO_KEEPALIVE`,但 Linux 默认值导致它几乎不生效。运维侧:

```
sysctl -w net.ipv4.tcp_keepalive_time=60
sysctl -w net.ipv4.tcp_keepalive_intvl=15
sysctl -w net.ipv4.tcp_keepalive_probes=3
```

Netty 4 不支持单连接级别设置 keepalive 间隔,只能靠内核全局参数,精度不如 WS 层 PING。

**B. 不要在裸 TCP 流里注入应用层心跳字节**
会破坏被隧道的上层协议(SSH/DB/HTTP),除非重新定义一个带封帧的子协议 — 和"裸 TCP 透传"的设计初衷冲突。

**C. keepalive 周期参数化(可选)**
当前四处都是硬编码 60s(SERVICE bus / backhaul / Forwarder backhaul / WS 透传 access)。如果需要按部署环境(直连 / 经云 LB / 经反向代理)分别调,可抽到配置项 —— 60s 对多数公网中间链路够用,但部分云 LB 空闲超时本身就在 60s 量级,余量较紧。

### 真正做到全 TCP 降级时(若未来修 BUG #8)

backhaul 也变裸 TCP 后,WS PING 方案全部失效,只剩:
- 依赖 `Channels.listen` / `Channels.open` 已开的 `SO_KEEPALIVE`(server 入站、agent 出站都默认开)+ 调小内核参数
- 或者放弃"纯透传"语义,在 backhaul 上加一个带心跳的自定义封帧

比较之下,**保留 server↔agent 的 WebSocket 通道**(即删掉 agent 死代码分支)是更干净的路径:便于心跳、便于将来加控制帧、对性能几乎无损(WS 封帧开销极小)。

---

## 其他 TODO / 非 BUG

- **ZooKeeper 依赖未使用**:`pom.xml` 引入 `zookeeper:3.6.0`,疑似预留给多节点注册中心
- **监听地址/端口/endpoint 未配置化**:`WebSocketBridgeServer.main` 里硬编码 `2345`、`/tunnel`、`useSsl=false`
- **缺少统一的协议常量**:`WebSocketBridgeServerHandler` 与 `WebSocketBridgeServerEngine` 各自声明了一套 `VER/CMD/ATYP/REPLY_*`,建议抽到 `pangolin-common`
