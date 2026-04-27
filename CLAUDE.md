# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

Pangolin —— 一个轻量级 Java 隧道,把内网侧 Agent 与公网侧 Client 两条 WebSocket 在公网 Server 上撮合成一条双向通道,可选将 access 侧降级为裸 TCP 以提升性能。Maven 多模块构建,Java 11、Netty 4.2、Spring Boot 2.0、jline、Lombok。

`pangolin-server/README.md` 与 `pangolin-agent/README.md` 是权威设计文档,详细描述了线协议、鉴权机制、已知 BUG(含文件:行号)、以及当前 pipeline 形态背后的取舍 —— 改动协议层之前先读这两份文档。

## 模块划分

- **pangolin-common** —— 共享 Netty handler(`TcpOverWebSocketEncodeHandler`、`TcpOverWebSocketDecodeHandler`、`TcpInboundRedirectHandler`、`WebSocketInboundRedirectHandler`、`WebSocketServerHandshakeNegotiationHandler`)、`NettyServer` 基类、`Channels`、协议常量 `Constants`(VER/CMD/RSV/ATYPE/REPLY、IPv4/IPv6 地址长度)、工具 `Util`(`hmacSha256`、`readSocketAddress` / `skipSocketAddress`、URL-Safe Base64 编解码、`last`)。包名根是 `com.github.pangolin`(没有 `.common` 子包,导入时注意)。
- **pangolin-server** —— 公网隧道服务端,Spring Boot fat jar `pangolind-<version>.jar`。默认监听 `:2345/tunnel`,不开 SSL(写死在 `WebSocketBridgeServer.main`)。Secret 解析通过 `WebSocketBridgeSecretKeyProvider` SPI 暴露,默认实现 `WebSocketBridgeEnvSecretKeyProvider`。
- **pangolin-agent** —— Agent 核心库:`WebSocketBridgeAgent`(主动外联模式,Agent 拨号到 Server)。`servlet/WebSocketBridgeEndpoint`(被动 JSR-356 模式)整体 `@Deprecated`。
- **pangolin-agent-app** —— `pangolin-agent` 的 Spring Boot 封装(入口 `WebSocketBridgeAgentApplication`),依赖 `pangolin-agent-spring-boot-starter`。
- **pangolin-agent-spring-boot-starter** —— 自动装配:在 15s 调度器上跑 `WebSocketBridgeAgent` watchdog(`WebSocketBridgeAgentAutoConfiguration`),从 `spring.application.name` + active profile 派生 `tunnelKey`,从 `spring.management.tunnel` 读隧道地址,从 `spring.management.tunnelSecretKey` 读 secret(未配则用默认派生公式 `reverse(tunnelKey)+"^_^"+tunnelKey`)。
- **pangolin-client-spring-boot-starter** —— 旧坐标 forwarder alias,内部仅一条 dependency 指向 `pangolin-agent-spring-boot-starter`,留着对外 BC,新代码不要再依赖它。

## 构建 / 运行

```bash
mvn -pl pangolin-server -am clean package -DskipTests   # 构建 server fat jar
mvn -pl pangolin-agent-app -am clean package -DskipTests # 构建 agent fat jar
mvn clean install -DskipTests                            # 全量构建,安装到本地仓库
java -jar pangolin-server/target/pangolind-0.0.5-SNAPSHOT.jar
java -jar pangolin-agent-app/target/pangolin-agent-app-0.0.5-SNAPSHOT.jar
```

单模块 / IDE 调试:`WebSocketBridgeServer#main` 与 `WebSocketBridgeAgent#main` 都自带可直接运行的 `main`(server 监听 `:2345`,agent 拨 `ws://localhost:2345/tunnel/123`,demo password `"321^_^123"` 与默认 secret 公式对齐)。

根 `pom.xml` 把 `maven-source-plugin`、`maven-javadoc-plugin`、`maven-gpg-plugin` 挂在 `package` / `verify` 阶段。本地构建一般要加 `-Dgpg.skip=true`(`-DskipTests` 不够,GPG 是 `verify` 阶段触发的)。日常开发优先用 `package`,避免触发 `install` / `verify` 上的 GPG 签名。

JDK 要求:Java 11(`maven.compiler.source/target=11`)。

## 架构要点

### 线协议(动帧解析前必读)

字节格式是 SOCKS5 风格的 TLV,协议常量在 `pangolin-common.Constants`(VER_1=0x01、CMD_CONNECT=0x01、CMD_SERVICE=0xFF、RSV=0x00、ATYPE_*、REPLY_*、IPv4/IPv6 地址长度),server 与 agent 通过 `import static` 共用。

**鉴权尾巴(auth tail)**:`TS(8B 秒) | NONCE(8B SecureRandom) | HMAC(32B HMAC-SHA256)`,共 48B。**仅 WS handshake token 携带**(REGISTER / CONNECT / BACKHAUL REPLY),SERVICE bus 上的 FORWARD / 失败 REPLY **不带**(channel-level trust)。`WebSocketBridgeServerHandler#checkPayload` 统一做帧长 + HMAC + TS 时间窗(30s)校验;**NONCE 缓存仍占位未落地**(`// TODO check nonce`,详见 server README BUG 列表)。HMAC 计算双侧统一调 `Util.hmacSha256(buf, off, len, secret)`。

### WebSocket 子协议负责所有路由分发

`WebSocketBridgeServerHandler#userEventTriggered` 按 `Sec-WebSocket-Protocol` 分发:

| 子协议 | URI | 用途 |
| --- | --- | --- |
| `SERVICE` | `/tunnel/{tunnelKey}` | Agent 注册(长连控制总线),WS handshake token 带 auth tail |
| *(空)* | `/tunnel/{tunnelKey}` | Client 请求 WS 透传隧道,token 带 auth tail |
| `CONNECT` | `/tunnel/{tunnelKey}` | Client 请求隧道 + 自身降级裸 TCP,token 带 auth tail |
| `BACKHAUL` | `/tunnel/{tunnelKey}` | Agent 针对单个 Client 连接的回连支路,token 带 auth tail;ID 完全在帧体里 |
| `CONSOLE` | `/tunnel/*` | 管理 Shell —— 校验 `?access_token=` 是否等于 `-Dwebsocket.bridge.console.access_token`(URL 明文传输,不算强鉴权) |

Token:`Authorization: Bearer <urlsafe-base64>` 或 `?access_token=<urlsafe-base64>`。Base64 解码后字节是 SOCKS5 风格负载 + auth tail,server 端走 `checkPayload` 验签。Secret 通过 `Engine#hmac` → `WebSocketBridgeSecretKeyProvider#getSecretKey(tunnelKey)` 解析。默认实现 `WebSocketBridgeEnvSecretKeyProvider` 优先读 `-Dwebsocket.bridge.<tunnelKey>.secretKey`;未配置时 fallback 派生公式 `reverse(tunnelKey)+"^_^"+tunnelKey`(双侧公式相同,**默认配置可跑通**,但公式公开,生产仍需显式配置 secret)。

### 握手撮合 —— `WebSocketBridgeServerEngine`

两张并发表:

- `registeredAgents`:`agent.id (Netty channel id)` → `Agent`
- `connections`:`accessChannelId` → `Connection { accessCtx, target, handshakePromise }`

`Engine.choose(tunnelKey)` 按 `agent.tunnelKey == tunnelKey` 过滤候选 + 随机选一台。`Agent.name`(SERVICE 负载里的 `AGN.INFO` 字段,内容为 `name + "-v" + AGENT_VERSION`,`AGENT_VERSION` 在 `WebSocketBridgeAgent` 上)**仅用于运维 / 日志,不参与路由**。

每条 Client 连接的时序:

1. `WebSocketBridgeServerHandler#handshake` 解 token → `checkPayload` 鉴权 → 解析 `DST.ADDR/DST.PORT`。
2. `accessCtx.setAutoRead(false)`,分配 `Connection`,登记到 `connections`。
3. 通过选中 Agent 的 `bus` 下发 SOCKS5 风格 FORWARD(`Engine#handshake0`,**无 auth tail**)。
4. 启动 10s 握手超时(`DEFAULT_HANDSHAKE_TIMEOUT_MILLIS`)。
5. 等待 Agent 用 `BACKHAUL` 子协议回连 → `WebSocketBridgeServerHandler#finishHandshake`:`checkPayload` 验 BACKHAUL token + 帧体读 `connectionId` → `Engine#finishHandshake` 校验 `URL tunnelKey == connection.agent.tunnelKey`。
6. 拼接两侧 pipeline,交叉挂 `closeFuture`(一端关 ⇒ 另一端 `EMPTY_BUFFER + CLOSE`),最后双方 `setAutoRead(true)`。

### 握手后 pipeline 形态

- **WS 透传(默认)**:两侧都装 `WebSocketInboundRedirectHandler`,access、backhaul 都保留 WS codec。
- **TCP 降级(仅作用于 access 一段)**:access pipeline 按名字移除 WS codec(`"wsencoder"` / `"wsdecoder"` / `Utf8FrameValidator` / `WebSocketServerProtocolHandler`),装上 `TcpOverWebSocketEncodeHandler`;backhaul 侧 WS codec **完全保留**,只是把终端 handler 换成 `TcpOverWebSocketDecodeHandler`(把 BinaryFrame 内容剥成裸字节写给已无 codec 的 access)。**backhaul 在线路上始终是 WebSocket**,降级仅发生在 client↔server 一段。
- agent 端 `WebSocketBridgeAgentHandler#pipe` 只有单一 WS-to-target 路径,server↔agent 始终是 WebSocket。

### 心跳保活

`WebSocketKeepaliveHandler(60,60,60)` = `IdleStateHandler` + `IdleStateEvent → PingWebSocketFrame`,四处统一 60s:

- **SERVICE bus**(`registerAgent0`):匿名子类**替换**终端 handler,顺带 override `channelRead` 处理 `agentResponded`
- **Backhaul**(`Handler#handshake0`):`addBefore` 终端 redirect/encode handler
- **Access(WS 透传)**(`Handler#handshake0` 的 `!downgrade` 分支):`addBefore` 终端 `WebSocketInboundRedirectHandler`
- **Forwarder backhaul**(`Forwarder#addForwarding`):`addBefore` 终端 `TcpOverWebSocketDecodeHandler`

`WebSocketKeepaliveHandler#handlerAdded` 自动把 `IdleStateHandler` 插在自身前面;但 keepalive 整体**必须挂在终端 redirect handler 之前**,否则 PING 帧会被 `WebSocketInboundRedirectHandler` 直接转发到对端,而非由本地 `WebSocketServerProtocolHandler` 回 PONG。Agent 端 `WebSocketBridgeAgentHandler#userEventTriggered` 也响应 `IdleStateEvent → PING`,server↔agent 双向都主动发 PING。

**access 裸 TCP 段(TCP 降级 + Forwarder 转发)只有 SO_KEEPALIVE 兜底**:`pangolin-common/Channels.java#listen` 写死了 `childOption(SO_KEEPALIVE, true)`,但 Linux 默认 `tcp_keepalive_time=7200s`,需要运维侧调小内核参数才有意义。

**改心跳参数前先看 `pangolin-server/README.md` "保活策略"那节**。

### 端口转发与管理 Shell

- `WebSocketBridgeServerForwarder` 在本机 `ServerSocket` 监听,连接到达时直接调 `engine.handshake(...)` 把 TCP 接进隧道 —— 等价于 Client 走 `CONNECT`,只是 access 侧没了 WS 封帧。
- `shell/WebSocketBridgeServerConsoleHandler` 把 WS 包成 jline 的 stdin/stdout。命令:`agent list|remove`、`forward list|add|remove`、`connection list|kill`、`exit`。`isConsoleAllowed` 校验 `?access_token=` 是否等于 `-Dwebsocket.bridge.console.access_token`,但 token 走 URL query 明文传输(且无 nonce、可重放),只能挡住扫描型攻击,**不要把 `/tunnel` 直接暴露到不可信网络**。

## 约定 / 易踩坑点

- `pangolin-agent-spring-boot-starter` 用 `spring.application.name` + profile 派生 `tunnelKey`(`name@profile1,profile2`),改应用名或切 profile 会改变路由 / secret 默认派生值。
- `application.yaml` 用的是非标准 key `spring.management.tunnel` / `spring.management.tunnelSecretKey`(在 watchdog 里手动读,**没**走 `@ConfigurationProperties`)。
- `WebSocketBridgeServer.boundChannel` 字段、Shell 的 `alias` 子命令是已知死代码(server README "低危"),先比对 BUG 列表再清理。
- 全项目大量使用 Lombok(`@Slf4j`、`@Getter`),IDE 需要装 Lombok 插件。
- 协议常量与工具方法都在 `pangolin-common`,改协议时只改一份。
- `pangolin-agent` 模块的 `servlet/WebSocketBridgeEndpoint` / `WebSocketEndpointLoaderListener` 已 `@Deprecated`,但代码、`javax.websocket-api` / `javax.servlet-api` 依赖、test 用的 `tyrus-container-grizzly-server` 都还留着。改这块前先确认是否要复活。
