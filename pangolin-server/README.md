# pangolin-server

Pangolin 隧道系统的**服务端**。负责在公网暴露 WebSocket 接入点,承接外部访问者(Client)与内网接入代理(Agent),将两条 WebSocket 撮合成一条双向隧道,把流量桥接到内网任意目标;并在此基础上提供本地端口转发与交互式管理 Shell。

打包产物:Spring Boot fat jar —— `pangolind-<version>.jar`
技术栈:Netty 4.2 + jline + Java 11。

---

## 目录结构

```
com/github/pangolin/server/
├── WebSocketBridgeServerApplication.java       Spring Boot 启动入口
├── WebSocketBridgeServer.java                  Netty 服务器(默认 :2345 /tunnel)
├── WebSocketBridgeServerHandler.java           WS 子协议路由 + checkPayload 鉴权统一入口
├── WebSocketBridgeServerEngine.java            Agent 注册表 + 握手撮合引擎
├── WebSocketBridgeServerForwarder.java         本地端口 -> 隧道 转发器
├── WebSocketBridgeSecretKeyProvider.java       secret 解析 SPI(byte[] getSecretKey(tunnelKey))
├── WebSocketBridgeEnvSecretKeyProvider.java    默认实现:系统属性 + 派生 fallback
├── WebSocketKeepaliveHandler.java              IdleStateHandler + 主动 PING 心跳
└── shell/
    ├── ConsoleReaderFactory.java                jline ConsoleReader 构造(带补全)
    ├── WebSocketBridgeServerConsoleHandler.java WebSocket <-> jline 控制台桥接
    └── WebSocketBridgeServerShell.java          命令解析(agent/forward/connection/exit)
```

依赖的 `NettyServer`、`TcpOverWebSocket*Handler`、`WebSocketInboundRedirectHandler`、`Channels`、`Util`、协议常量 `Constants` 均位于 `pangolin-common`。

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
| `BACKHAUL`    | `/tunnel/{tunnelKey}`         | Agent 回连。**ID 完全在帧体里**,URL 上不带 id              |
| `CONSOLE`     | `/tunnel/*`                   | 管理控制台,需要 `?access_token=` 等于 `-Dwebsocket.bridge.console.access_token` |

握手附加负载从 `Authorization: Bearer <token>` 或 `?access_token=<token>` 取出,按 URL-Safe Base64 解码后交给具体分支解析。

### 3. 协议格式(类 SOCKS5)

协议常量在 `pangolin-common.Constants`,Engine / Handler 通过 `import static com.github.pangolin.util.Constants.*` 共享:`VER_1=0x01`、`CMD_CONNECT=0x01`、`CMD_SERVICE=0xFF`、`RSV=0x00`、`ATYPE_IPv4/DOMAIN/IPv6 = 0x01/0x03/0x04`、回复码 `REPLY_SUCCESS=0x00 ... REPLY_ADDRESS_UNSUPPORTED=0x08`、`IPv4_ADDR_SIZE=4` / `IPv6_ADDR_SIZE=16`。地址读写、URL-Safe Base64 编解码、HMAC 计算同样落在 `pangolin-common.Util`。

**鉴权尾巴(auth tail)**:`TS(8B 秒) | NONCE(8B SecureRandom) | HMAC(32B)`,共 48B。**仅 WS handshake token 携带**(REGISTER / CONNECT / BACKHAUL REPLY),SERVICE bus 上的 FORWARD / 失败 REPLY **不带**(channel-level trust,见下)。

#### Agent 注册(SERVICE handshake token)

```
+-----+-------+-------+------+----------+----------+----------+----+-------+------+
| VER |  CMD  |  RSV  | ATYP | BND.ADDR | BND.PORT | AGN.INFO | TS | NONCE | HMAC |
+-----+-------+-------+------+----------+----------+----------+----+-------+------+
|  1  | x'FF' | x'00' |  1   | Variable |    2     | Variable |  8 |   8   |  32  |
+-----+-------+-------+------+----------+----------+----------+----+-------+------+
```

`AGN.INFO` 是 1B LEN + UTF-8 字节,内容为 `name + "-v" + AGENT_VERSION`。`AGENT_VERSION` 在 `WebSocketBridgeAgent` 里是 `"1.3"`。

#### Client → Server 的 CONNECT(WS handshake token)

```
+-----+-----+-------+------+----------+----------+----+-------+------+
| VER | CMD |  RSV  | ATYP | DST.ADDR | DST.PORT | TS | NONCE | HMAC |
+-----+-----+-------+------+----------+----------+----+-------+------+
|  1  |  1  | x'00' |  1   | Variable |    2     |  8 |   8   |  32  |
+-----+-----+-------+------+----------+----------+----+-------+------+
```

由 `WebSocketBridgeServerHandler#handshake` 解析,**不含 ID** —— ID 由 server 用 access channel 的 Netty channel id 字符串自行补齐后下发到 Agent。

#### Server → Agent 的 FORWARD(SERVICE bus,**无 auth tail**)

```
+-----+-----+-------+------+----------+----------+----------+
| VER | CMD |  RSV  | ATYP | DST.ADDR | DST.PORT | ID       |
+-----+-----+-------+------+----------+----------+----------+
|  1  |  1  | x'00' |  1   | Variable |    2     | Variable |
+-----+-----+-------+------+----------+----------+----------+
```

由 `WebSocketBridgeServerEngine#handshake0` 编码。`ID` 在 PORT 之后,1B LEN + UTF-8。SERVICE bus 在 REGISTER 阶段已验签,后续帧不再逐帧验签。

#### Agent → Server 的 REPLY

两种发送路径,**布局相同但 auth tail 取决于信道**:

```
+-----+-----+-------+------+----------+----------+----------+[ TS | NONCE | HMAC ]
| VER | REP |  RSV  | ATYP | BND.ADDR | BND.PORT | ID       |[              48B  ]
+-----+-----+-------+------+----------+----------+----------+
|  1  |  1  | x'00' |  1   | Variable |    2     | Variable |
+-----+-----+-------+------+----------+----------+----------+
```

- **BACKHAUL handshake token**(`REP=0x00 SUCCESS`):agent 拨第二条 WS 子协议 `BACKHAUL` 时,把这帧 base64 后塞进 `Authorization: Bearer ...`。**带 auth tail**,由 `WebSocketBridgeServerHandler#finishHandshake` → `checkPayload` 验签。BACKHAUL URL 与 SERVICE 相同(`/tunnel/{tunnelKey}`),`connectionId` 完全从帧体读,server 通过 `Engine#finishHandshake(tunnelKey, id, ctx)` 撮合并校验 tunnelKey 一致性
- **SERVICE bus 失败 REPLY**(`REP=0x01..0x08`):backhaul 握手或 target connect 失败时,agent 通过 SERVICE bus 直接发回。**不带 auth tail**,由 `Engine#agentResponded` 解析,只读到 ID 即可,`BND.ADDR / BND.PORT` 占位 `0x01 / 0.0.0.0 / 0`

#### 鉴权(`WebSocketBridgeServerHandler#checkPayload`)

三个 handshake token 入口(REGISTER / CONNECT / BACKHAUL REPLY)统一调用 `checkPayload`,做四件事:

1. 帧长 ≥ `MIN_NOT_TRUST_PAYLOAD_LENGTH`(SOCKS5 前缀最小 + 48B auth tail),否则 `INVALID_PAYLOAD_DATA`
2. HMAC 比对(`MessageDigest.isEqual` 常时间)—— 取最末 32B 作 expected,前面字节作签名输入,失败 `POLICY_VIOLATION`(`computed == null` 也算失败)
3. TS 时间窗 `|now - ts| ≤ 30s`(`MAX_TIMESTAMP_DIFF_SECONDS`),失败 `POLICY_VIOLATION`
4. **NONCE 缓存**:代码里仍是 `// TODO check nonce`,占位**未落地**(详见 BUG 列表)

#### Secret 选择 —— `WebSocketBridgeSecretKeyProvider` SPI

```java
public interface WebSocketBridgeSecretKeyProvider {
    byte[] getSecretKey(String tunnelKey);
}
```

`WebSocketBridgeServerEngine#hmac(tunnelKey, ...)` 转发到 provider 拿到字节再走 `Util.hmacSha256`,provider 返回 null 视为验签失败。

`Engine` 默认构造器装的是单例 `WebSocketBridgeEnvSecretKeyProvider`:

1. 优先读系统属性 `websocket.bridge.<tunnelKey>.secretKey`
2. 若未配置,fallback 派生公式 `reverse(tunnelKey) + "^_^" + tunnelKey`,UTF-8 字节即 secret

派生公式只是为了"默认配置可跑通本地 demo"(agent 那边的 watchdog 用同一公式),公式公开,**生产仍必须显式配置 secret**。需要其他 secret 后端(配置中心 / KMS / 数据库)时,实现 `WebSocketBridgeSecretKeyProvider` 后通过 `new WebSocketBridgeServerEngine(provider)` 注入。

`ATYP`:`0x01` IPv4 / `0x03` 域名 / `0x04` IPv6。

### 4. 握手撮合(`WebSocketBridgeServerEngine`)

内部两张并发表:

- `registeredAgents`: `agent.id (Netty channel id)` -> `Agent`
- `connections`: `accessChannelId` -> `Connection { id, agent, source, target, accessCtx, handshakePromise }`

#### Agent 选择策略 — `Engine.choose(tunnelKey)`

```java
final List<Agent> candidates = new ArrayList<>();
for (Agent candidate : registeredAgents.values()) {
    if (candidate.getTunnelKey().equals(tunnelKey)) candidates.add(candidate);
}
return !candidates.isEmpty() ? candidates.get(random) : null;
```

按 `agent.tunnelKey == tunnelKey` 组内随机负载均衡。`Agent.name` 仅用于日志/运维展示,未参与路由。如果业务上需要"指定特定 agent",建议另加稳定 `agent.tag`(目前没做)。

#### Client 请求时序

1. `WebSocketBridgeServerHandler#handshake` 解 token → `checkPayload` 鉴权(HMAC + TS)→ 解析 `DST.ADDR/DST.PORT`(`Util.readSocketAddress(payload, false)`,保持 unresolved)
2. `accessCtx.setAutoRead(false)`,生成 `Connection` 放入 `connections`
3. 通过 Agent 的 `bus` 下发 SOCKS5 风格 FORWARD(`Engine#handshake0`,无 auth tail)
4. 启动 10s 握手超时定时器(`DEFAULT_HANDSHAKE_TIMEOUT_MILLIS`)
5. 等待 Agent 用 `BACKHAUL` 子协议回连 → `WebSocketBridgeServerHandler#finishHandshake`
   - `checkPayload` 验 BACKHAUL token 的 HMAC + TS
   - 解析帧体取 `connectionId`(1B LEN + UTF-8,`readUnsignedByte` 读长度)
   - 调 `Engine#finishHandshake(tunnelKey, connectionId, backhaulCtx)` 校验 `URL tunnelKey == connection.agent.tunnelKey`(否则拒绝接管)
6. `handshakePromise` 完成,双方 closeFuture 互相挂钩:一端关,另一端 `writeAndFlush(EMPTY_BUFFER) + CLOSE`

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
Client <--tcp--> Server <--ws--> Agent       (TCP 降级,仅 client↔server 段是 TCP)
```

agent 端 `WebSocketBridgeAgentHandler#pipe` 只有单一 WS-to-target 路径,server↔agent 始终是 WebSocket(便于做应用层心跳)。

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
- TCP 降级 / Forwarder 转发:裸 TCP 装不了 WS PING,只有 `pangolin-common/Channels.java` 写死的 `childOption(SO_KEEPALIVE, true)` 兜底 —— 但 Linux 默认 `tcp_keepalive_time=7200s`,需运维侧调小内核参数才有意义

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

自定义 secret 后端:

```java
final WebSocketBridgeSecretKeyProvider provider = tunnelKey -> ... ; // 从配置中心 / DB 拉
final WebSocketBridgeServerEngine engine = new WebSocketBridgeServerEngine(provider);
```

`WebSocketBridgeServer` 默认构造里 `engine = new WebSocketBridgeServerEngine()`,要替换 provider 目前需要改这一行(尚未配置化,见 TODO)。

---

## 潜在 BUG 排查

> 文件路径相对 `pangolin-server/src/main/java/com/github/pangolin/server/`。

### 设计说明(非 BUG,易误读)

**`Engine.choose(tunnelKey)` 仅做组内随机**
按 `agent.tunnelKey == tunnelKey` 过滤候选 + 随机选一台。`Agent.name` 仅用于日志/运维。如果将来需要精确路由,建议引入稳定的 `agent.tag` 字段(配置而非 channel id)。

**`SecretKeyProvider` 的 fallback 派生公式公开**
默认实现 `WebSocketBridgeEnvSecretKeyProvider` 在系统属性未配置时会 fallback 到 `reverse(tunnelKey) + "^_^" + tunnelKey`。这是为了让本地 demo 直接跑得通,公式与 agent 端 watchdog 默认一致。**生产部署仍必须显式配置 `-Dwebsocket.bridge.<tunnelKey>.secretKey=<secret>`**;或者注入自定义 provider 让"未配置 = 拒绝"。

### 🔴 高危

**1. 管理控制台是弱鉴权(URL access_token + 系统属性)**
`WebSocketBridgeServerHandler#isConsoleAllowed` 校验 `?access_token=` 是否等于 `-Dwebsocket.bridge.console.access_token`。但是:

- token 走 URL query 明文传输,可能进入访问日志、代理日志、proxychains 缓存
- 只是字符串比对,无 nonce / 时间戳 / 重放保护
- 系统属性配置通常会留在启动命令里,排查泄漏面广

CONSOLE 一旦通过就能列出/杀连接、增删端口转发、踢 Agent。**严禁直接暴露到不可信网络**;真要开,放到 VPN / SSH 隧道之后,再加访问日志审计。

**2. NONCE 缓存未落地,30s 时间窗内可重放**
`WebSocketBridgeServerHandler#checkPayload:496` 仍是 `// TODO check nonce`。同一 token 在 30s 时间窗内可被任意重放(HMAC + TS 校验通过即放行)。NONCE 字段双侧已正确写入(`SecureRandom`),但 server 没缓存比对。修法:以 `(tunnelKey, nonce)` 为 key 入 LRU/Caffeine,TTL=60s,命中即拒。

**3. 裸 TCP access 段只有内核 keepalive 兜底,默认参数等于没开**
应用层 PING 覆盖范围(见 §7):

- agent SERVICE bus + backhaul 都装了 `WebSocketKeepaliveHandler(60,60,60)`,server↔agent 整条 WS 链路双向互补
- WS 透传 access 也已挂 `WebSocketKeepaliveHandler(60,60,60)`(`Handler#handshake0` 的 `!downgrade` 分支)

**仍未覆盖**: TCP 降级后的 access、Forwarder 端口转发的 access 都是裸 TCP,装不了 WS PING。`pangolin-common/Channels.java` 的 `childOption(SO_KEEPALIVE, true)` 提供 OS 层兜底,但 Linux 默认 `tcp_keepalive_time=7200s`,生产上**几乎等于没开**。

修复方向:运维侧调小内核全局参数:

```
sysctl -w net.ipv4.tcp_keepalive_time=60
sysctl -w net.ipv4.tcp_keepalive_intvl=15
sysctl -w net.ipv4.tcp_keepalive_probes=3
```

Netty 4 不支持单连接级别配置 keepalive 间隔,只能依赖内核全局参数。

### 🟡 中危

**4. Spring Boot 与 Netty 双启动,生命周期脱节**
`WebSocketBridgeServerApplication.java` 先 `SpringApplication.run(...)` 再 `WebSocketBridgeServer.main(args)`,后者 `channel.closeFuture().sync()` 永久阻塞主线程。Spring 容器不感知这个 Netty server,`@PreDestroy` / shutdown hook 不会优雅关闭 Netty;端口、event loop 的参数也没走配置化,和 Spring Boot 的价值脱节。

**5. TCP 降级依赖硬编码的 Netty handler 名称**
`WebSocketBridgeServerHandler.java:402-403` 通过字符串 `"wsencoder"` / `"wsdecoder"` 从 pipeline 移除 handler。这些名字来自 Netty `WebSocketServerHandshaker` 内部实现,一旦升级 Netty 改名,运行时直接 `NoSuchElementException`。建议改为按 `Class` 移除或遍历清理(`Utf8FrameValidator`、`WebSocketServerProtocolHandler` 已经是按 Class 移的)。

**6. `WebSocketBinaryOutput#write` 调用 `writeAndFlush(...).sync()` 存在死锁隐患**
`WebSocketBridgeServerConsoleHandler.java`。当前从 Shell 线程调用无事,但若被任何 EventLoop 线程调用(比如将来把命令执行挪到 pipeline),`.sync()` 会等待自己所在 loop 的任务完成,造成死锁。建议去掉 `.sync()`,通过背压/Promise 链处理。

**7. `Engine.handshake` 超时失败时不由 Engine 自身关闭 access 连接**
`WebSocketBridgeServerEngine.java` 超时后仅 `tryFailure`,依赖 Handler/Forwarder 在 listener 里关闭。两处调用方都有关闭逻辑,但 Engine 内部状态(`connections` map)的清理完全依赖 access channel close 事件。如果今后出现某个不关闭 access 的调用方,就会泄漏 `Connection`。

**8. `Engine.choose` 候选列表每次重建**
`WebSocketBridgeServerEngine.java:273` 每次 handshake 都遍历全表生成 ArrayList。低 QPS 没事,但是热点路径,可以维护 `ConcurrentMap<tunnelKey, CopyOnWriteArrayList<Agent>>`,注册/反注册时维护。

### 🟢 低危 / 代码瑕疵

**9. `WebSocketBridgeServer.boundChannel` 字段未使用**
`WebSocketBridgeServer.java:43` 声明但 `start()` 直接返回 `super.start(...).sync().channel()`,该字段从未被赋值 — 属死代码。

**10. `forward` 帮助信息里的 `alias` 子命令无实现**
`WebSocketBridgeServerShell.java` usage 里列出 `alias                                 List aliases for forwarding target hostnames`,但 `doExecuteForwardCommand` 没有对应分支。

**11. `Engine.Connection` 是非静态内部类,无必要地持有外部 Engine 引用**
`Agent` 已是 `static`,`Connection` 应一并改为 static。

**12. `Forwarder.addForwarding` 中 `accessCtx.fireChannelActive()` 调用无意义**
`WebSocketBridgeServerForwarder.java` — 该 handler 是 pipeline 最后一个,`fireChannelActive` 没有下游可传递。

**13. `Engine.handshake` 内部 `setAutoRead(false)` 与 `Handler.handshake0` 中重复设置**
`WebSocketBridgeServerHandler.java:359` + `WebSocketBridgeServerEngine.java:170`,任一处都足够。

**14. `WebSocketBridgeServer.main` 不读 `WebSocketBridgeSecretKeyProvider` 配置**
默认构造直接 `new WebSocketBridgeServerEngine()`,落到 `WebSocketBridgeEnvSecretKeyProvider.INSTANCE`。要替换 provider 必须改源码。后续可以从 `application.yaml` / Spring Boot bean 里注入。

---

## 保活策略(针对 BUG #3 的落地方案)

### 前提判断

- **server↔agent 的 SERVICE bus 与 backhaul**:挂了 `WebSocketKeepaliveHandler(60,60,60)`,agent 端 `IdleStateEvent → PING` 双向互补
- **server↔agent 全程 WebSocket**(agent 端 backhaul 永远是 WS),心跳方案有效
- **client↔server 的 access**:
  - WS 透传:已挂 `WebSocketKeepaliveHandler(60,60,60)`(`Handler#handshake0` 的 `!downgrade` 分支)
  - TCP 降级:裸 TCP(access pipeline 已无 WS codec),**不能**发 `PingWebSocketFrame`(会当普通字节发给 client,破坏上层协议),只能靠 `SO_KEEPALIVE`
- **裸 TCP 段的 `SO_KEEPALIVE`**:在 `pangolin-common/Channels.java` 的 `Channels.listen(...)` 里写死(`childOption(SO_KEEPALIVE, true)` + `TCP_NODELAY`),所以主 server 与 Forwarder 的 access 段都自动开了。问题只剩 Linux 默认 `tcp_keepalive_time=7200s` 太长,需要运维侧调小

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
四处都是硬编码 60s(SERVICE bus / backhaul / Forwarder backhaul / WS 透传 access)。如果需要按部署环境(直连 / 经云 LB / 经反向代理)分别调,可抽到配置项 —— 60s 对多数公网中间链路够用,但部分云 LB 空闲超时本身就在 60s 量级,余量较紧。

---

## 其他 TODO / 非 BUG

- **监听地址/端口/endpoint 未配置化**:`WebSocketBridgeServer.main` 里硬编码 `2345`、`/tunnel`、`useSsl=false`
- **`SecretKeyProvider` 注入未配置化**:见 BUG #14
- **`AGENT_VERSION = "1.3"` 与 server 不强校验**:server `Engine#registerAgent` 只验 `VER == VER_1`,不读 `AGN.INFO` 里的版本号,该字段当前仅用于运维侧诊断
