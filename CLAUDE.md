# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

Pangolin —— 一个轻量级 Java 隧道,把内网侧 Agent 与公网侧 Client 两条 WebSocket 在公网 Server 上撮合成一条双向通道,可选将 access 侧降级为裸 TCP 以提升性能。Maven 多模块构建,Java 11、Netty 4.2、Spring Boot 2.0、jline、Lombok。

`pangolin-server/README.md` 与 `docs/agent/agent.md` 是权威设计文档,详细描述了线协议、所有已知 BUG(含文件:行号)、以及当前 pipeline 形态背后的取舍 —— 改动协议层之前先读这两份文档。

## 模块划分

- **pangolin-common** —— 共享 Netty handler(`TcpOverWebSocketEncodeHandler`、`TcpOverWebSocketDecodeHandler`、`TcpInboundRedirectHandler`、`WebSocketInboundRedirectHandler`、`WebSocketServerHandshakeNegotiationHandler`)、`NettyServer` 基类、`Channels` / `Util`。包名根是 `com.github.pangolin`(没有 `.common` 子包,导入时注意)。
- **pangolin-server** —— 公网隧道服务端,Spring Boot fat jar `pangolind-<version>.jar`。默认监听 `:2345/tunnel`,不开 SSL(写死在 `WebSocketBridgeServer.main`)。
- **pangolin-agent** —— Agent 核心库:`WebSocketBridgeAgent`(主动外联模式,Agent 拨号到 Server)与 `servlet/WebSocketBridgeEndpoint`(被动 JSR-356 模式,在 Servlet 容器中接收连接)。
- **pangolin-agent-app** —— `pangolin-agent` 的 Spring Boot 封装(入口 `WebSocketBridgeAgentApplication`)。
- **pangolin-client-spring-boot-starter** —— 自动装配:在 15s 调度器上跑 `WebSocketBridgeAgent` watchdog(`WebSocketBridgeAgentAutoConfiguration`),从 `spring.application.name` + active profile 派生 `tunnelKey`,从 `spring.management.tunnel` 读隧道地址。

## 构建 / 运行

Maven 多模块,目前没有测试源码:

```bash
mvn -pl pangolin-server -am clean package -DskipTests   # 构建 server fat jar
mvn -pl pangolin-agent-app -am clean package -DskipTests # 构建 agent fat jar
mvn clean install -DskipTests                            # 全量构建,安装到本地仓库
java -jar pangolin-server/target/pangolind-0.0.5-SNAPSHOT.jar
java -jar pangolin-agent-app/target/pangolin-agent-app-0.0.5-SNAPSHOT.jar
```

单模块 / IDE 调试:`WebSocketBridgeServer#main` 与 `WebSocketBridgeAgent#main` 都自带可直接运行的 `main`(server 监听 `:2345`,agent 拨 `ws://localhost:2345/tunnel/123`)。

根 `pom.xml` 把 `maven-source-plugin`、`maven-javadoc-plugin`、`maven-gpg-plugin` 挂在 `package` / `verify` 阶段。本地构建一般要加 `-Dgpg.skip=true`(`-DskipTests` 不够,GPG 是 `verify` 阶段触发的)。日常开发优先用 `package`,避免触发 `install` / `verify` 上的 GPG 签名。

JDK 要求:Java 11(`maven.compiler.source/target=11`)。

## 架构要点

### 线协议(动帧解析前必读)

字节格式是 SOCKS5 风格的 TLV,定义分散在两处:服务端 `WebSocketBridgeServerEngine` / `WebSocketBridgeServerHandler`,Agent 端 `WebSocketBridgeAgentHandler`。常量是重复声明的,**还没**抽到 `pangolin-common`。当前协议版本常量 `VER_1 = 0x01`(`Engine` 内的 `static final byte`)。

### WebSocket 子协议负责所有路由分发

`WebSocketBridgeServerHandler#userEventTriggered` 按 `Sec-WebSocket-Protocol` 分发:

| 子协议 | URI | 用途 |
| --- | --- | --- |
| `SERVICE` | `/tunnel/{tunnelKey}` | Agent 注册(长连控制总线) |
| *(空)* | `/tunnel/{tunnelKey}` | Client 请求 WS 透传隧道 |
| `CONNECT` | `/tunnel/{tunnelKey}` | Client 请求隧道 + 自身降级裸 TCP |
| `BACKHAUL` | `/tunnel/{tunnelKey}/{id}` | Agent 针对单个 Client 连接的回连支路 |
| `CONSOLE` | `/tunnel/*` | 管理 Shell —— `isConsoleAllowed` 当前永远 return true,**仍然无鉴权** |

Token:`Authorization: Bearer <urlsafe-base64>` 或 `?access_token=<urlsafe-base64>`。Base64 解码后字节就是 SOCKS5 风格负载 —— **目前没有任何签名 / 白名单校验**(见 README "高危 #2")。

### 握手撮合 —— `WebSocketBridgeServerEngine`

两张并发表:

- `registeredAgents`:`agent.id (Netty channel id)` → `Agent`
- `connections`:`accessChannelId` → `Connection { accessCtx, target, handshakePromise }`

`Engine.choose(tunnelKey)` 是**有意为之的双重寻址**,**不是 BUG**:先按 `agent.id`(Netty channel id)精确匹配,不中再在 `agent.tunnelKey == tunnelKey` 的候选里随机挑一台(SERVICE 注册 URL 路径作为组名,组内随机负载均衡)。`Agent.name`(SERVICE 负载里的 AGN.NAME 字段)**仅用于运维 / 日志,不参与路由**。

每条 Client 连接的时序:

1. `accessCtx.setAutoRead(false)`,分配 `Connection`,登记到 `connections`。
2. 通过选中 Agent 的 `bus` 下发 SOCKS5 风格 `CMD_CONNECT`。
3. 启动 10s 握手超时(`DEFAULT_HANDSHAKE_TIMEOUT_MILLIS`)。
4. 等待 Agent 用 `BACKHAUL` 子协议回连 → `finishHandshake`(同时校验 URI 上的 `tunnelKey` 与 `connection.agent.tunnelKey` 一致,不一致拒绝接管)。
5. 拼接两侧 pipeline,交叉挂 `closeFuture`(一端关 ⇒ 另一端 `EMPTY_BUFFER + CLOSE`),最后双方 `setAutoRead(true)`。

### 握手后 pipeline 形态

- **WS 透传(默认):** 两侧都装 `WebSocketInboundRedirectHandler`,access、backhaul 都保留 WS codec。
- **TCP 降级(仅作用于 access 一段):** access pipeline 按名字移除 WS codec(`"wsencoder"` / `"wsdecoder"` / `Utf8FrameValidator` / `WebSocketServerProtocolHandler`),装上 `TcpOverWebSocketEncodeHandler`;backhaul 侧 WS codec **完全保留**,只是把终端 handler 配套换成 `TcpOverWebSocketDecodeHandler`(把 BinaryFrame 内容剥成裸字节写给已无 codec 的 access)。**backhaul 在线路上始终是 WebSocket**,降级仅发生在 client↔server 一段。
- 注意:Agent 端有一条按 `RSV != 0` 触发的全 TCP 分支(让 backhaul 也变裸 TCP),但 `Engine.handshake0` 把 RSV 硬编码为 0,所以 server↔agent 始终是 WebSocket(README BUG #8 —— Agent 那边是死代码)。

### 心跳保活

中间链路长时间空闲会被静默丢掉,所以 server↔agent 整条 WS 链路都挂了 `WebSocketKeepaliveHandler(60,60,60)`(`IdleStateHandler` + `IdleStateEvent → PingWebSocketFrame`),三处统一 60s:

- **SERVICE bus**(`registerAgent0`):匿名子类**替换**终端 handler,顺带 override `channelRead` 处理 `agentResponded`
- **Backhaul**(`Handler#handshake0`):`addBefore` 终端 redirect handler
- **Forwarder backhaul**(`Forwarder#addForwarding`):`addBefore` 终端 `TcpOverWebSocketDecodeHandler`

`WebSocketKeepaliveHandler#handlerAdded` 自动把 `IdleStateHandler` 插在自身前面;但 keepalive 整体**必须挂在终端 redirect handler 之前**,否则 PING 帧会被 `WebSocketInboundRedirectHandler` 直接转发到对端,而非由本地 `WebSocketServerProtocolHandler` 回 PONG。Agent 端 `WebSocketBridgeAgentHandler#userEventTriggered` 也实现了 `IdleStateEvent → PING`,所以 server↔agent 双向都会主动发 PING。

**access 段的 SO_KEEPALIVE 已默认开**:`pangolin-common/Channels.java#listen` 写死了 `childOption(SO_KEEPALIVE, true)`,主 server 与 Forwarder 都走它,所以裸 TCP 段在 OS 层有 keepalive 兜底 —— 但 Linux 默认 `tcp_keepalive_time=7200s`,需要运维侧调小内核参数才有意义。

**仍未覆盖的应用层 PING:**

- Client↔Server 的 WS 透传 access:可以挂 `WebSocketKeepaliveHandler`,目前没装(`Handler#handshake0` 的 `!downgrade` 分支没 `addBefore`)
- TCP 降级 access 是裸 TCP,只能靠上述 `SO_KEEPALIVE` + 内核参数

**改心跳参数前先看 `pangolin-server/README.md` "保活策略"那节**,里面有分段的修复方案。

### 端口转发与管理 Shell

- `WebSocketBridgeServerForwarder` 在本机 `ServerSocket` 监听,连接到达时直接调 `engine.handshake(...)` 把 TCP 接进隧道 —— 等价于 Client 走 `CONNECT`,只是 access 侧没了 WS 封帧。
- `shell/WebSocketBridgeServerConsoleHandler` 把 WS 包成 jline 的 stdin/stdout。命令:`agent list|remove`、`forward list|add|remove`、`connection list|kill`、`exit`。**`isConsoleAllowed` 当前永远 return true**(代码里有注释掉的 access_token 校验占位),没有前置鉴权前**严禁**把 `/tunnel` 直接暴露到公网。

## 约定 / 易踩坑点

- Agent 的被动模式走 Servlet JSR-356(`@ServerEndpoint("/ws/bridge/{tunnelKey}")`),服务端走 Netty。两边共享协议常量但**代码路径独立**,改一边要同步改另一边。
- `pangolin-client-spring-boot-starter` 用 `spring.application.name` + profile 派生 `tunnelKey`(`name@profile1,profile2`),改应用名或切 profile 会改变路由。
- `application.yaml` 用的是非标准 key `spring.management.tunnel`(在 watchdog 里手动读,**没**走 `@ConfigurationProperties`)。
- `WebSocketBridgeServer.boundChannel` 字段、Shell 的 `alias` 子命令、Agent 的全 TCP `else` 分支都是已知死代码(README "低危" + "中危 #8")。**别条件反射地清理掉**,先比对 BUG 列表 —— 某些是为将来方向预留的占位。
- 全项目大量使用 Lombok(`@Slf4j`、`@Getter`),IDE 需要装 Lombok 插件。
- ZooKeeper 在 `pangolin-server` 的 classpath 上但目前未使用(为多节点注册中心预留)。
