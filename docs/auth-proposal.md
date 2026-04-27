# Pangolin 鉴权方案

> 基于 v1 协议的当前实现状态与待解决问题。

---

## 1. 现状

### 1.1 鉴权模型

| 链路场景                       | 是否带 auth tail | 验签方式                                      |
| ------------------------------ | ---------------- | --------------------------------------------- |
| WS handshake token(REGISTER) | 是               | server `WebSocketBridgeServerHandler#registerAgent` 调 `Engine.verifySignature` |
| WS handshake token(CONNECT)  | 是               | server `WebSocketBridgeServerHandler#handshake` 调 `Engine.verifySignature` |
| WS handshake token(BACKHAUL REPLY) | 是           | server `Engine.finishHandshake`(注:**当前路径未调 `verifySignature`**,见待解决问题) |
| SERVICE bus FORWARD(server→agent) | 否        | 不验签,channel-level trust(REGISTER 时已验过 bus) |
| SERVICE bus 失败 REPLY(agent→server) | 否       | 同上                                          |

**核心思路**:`tunnelKey` 仅出现在 URL 路径,server 用它选 per-tunnelKey secret;HMAC 对帧体除尾部 32B 之外的所有字节签名。已建立的 SERVICE bus 视为可信通道,bus 上后续消息不再逐帧验签。

### 1.2 v1 帧格式

```
+-----+--------+-------+------+------+------+--------------+--------+---------+-----------+
| VER | CMD/   | RSV   | ATYP | ADDR | PORT | <extras>     | TS (8) | NONCE(8)| HMAC (32) |
|     |  REP   |       |      |      |      |              |        |         |           |
+-----+--------+-------+------+------+------+--------------+--------+---------+-----------+
| 1   | 1      | 1     | 1    | var  | 2    | var          | 8      | 8       | 32        |
+-----+--------+-------+------+------+------+--------------+--------+---------+-----------+
                                                            └── auth tail(仅 handshake token 带) ──┘
```

| 帧                        | 方向            | 信道                    | `<extras>`        | 带 auth tail |
| ------------------------- | --------------- | ----------------------- | ----------------- | ----------- |
| REGISTER(CMD=0xFF)        | Agent → Server  | WS handshake token      | `LEN+AGN.INFO`    | 是          |
| CONNECT(CMD=0x01)         | Client → Server | WS handshake token      | (无)              | 是          |
| BACKHAUL REPLY(REP=0x00)  | Agent → Server  | WS handshake token      | `LEN+ID`          | 是          |
| FORWARD(CMD=0x01)         | Server → Agent  | SERVICE bus(BinaryFrame) | `LEN+ID`         | 否          |
| BUS REPLY(REP=0x01..0x08) | Agent → Server  | SERVICE bus(BinaryFrame) | `LEN+ID`         | 否          |

字段:`VER=0x01`(常量名 `VER_1`)、`RSV=0x00`、`ATYP` 0x01/0x03/0x04、`AGN.INFO` = `name + "-v" + AGENT_VERSION`(1B LEN + UTF-8)、`ID` = server-side access channel id 字符串(1B LEN + UTF-8)、`TS` = unix seconds(big-endian)、`NONCE` = 8B `SecureRandom`、`HMAC` = `HmacSHA256(secret, frame_bytes_except_last_32B)`。

### 1.3 关键文件 / 入口

| 文件                                       | 关键方法                                                        |
| ------------------------------------------ | --------------------------------------------------------------- |
| `pangolin-server/.../WebSocketBridgeUtil.java` | `writeSignature` / `verifySignature` / `readSocketAddress` / `skipSocketAddress` / `hmacSha256` |
| `pangolin-server/.../WebSocketBridgeServerHandler.java` | `registerAgent` / `handshake` —— handshake token 验签后解析 |
| `pangolin-server/.../WebSocketBridgeServerEngine.java`  | `handshake0` —— 写 FORWARD;`agentResponded` —— 读 BUS REPLY;`verifySignature` —— 取 secret + 调 util;`determineTunnelSecretKey` —— secretStore 占位 |
| `pangolin-agent/.../WebSocketBridgeAgent.java`         | 构造器接受 `byte[] secretKey` 字段并向下传 |
| `pangolin-agent/.../WebSocketBridgeAgentHandler.java`  | `channelActive` —— 写 REGISTER token;`channelRead0` —— 读 FORWARD;`newReply` —— 构造 REPLY 帧体(不带 auth tail);`writeSignature` —— 追加 auth tail;`newBackhaulHandshaker` —— 给 BACKHAUL REPLY 显式追加 auth tail |
| `pangolin-server/.../WebSocketBridgeServerEngine#determineTunnelSecretKey` | secretStore 占位,**当前硬编码 `"Local-v1.2_"`** |

---

## 2. 待解决问题

1. **`determineTunnelSecretKey` 接入真实 secretStore**
   `WebSocketBridgeServerEngine.java:482-484` 当前硬编码 `"Local-v1.2_"`。生产需要 `tunnel-secrets.properties` / ZK / Nacos 等可重载来源,按 tunnelKey 查询。

2. **server 端 TS 时间窗 + NONCE 缓存校验未落地**
   `WebSocketBridgeUtil#verifySignature` 当前只算并比对 HMAC,**没有 TS 时间窗判断**(防长期重放),**没有 NONCE 缓存**(防窗口内重放)。需要:
   - 解析帧尾 TS,校验 `Math.abs(now - ts) <= 30s`(或可配)
   - 解析帧尾 NONCE,以 `(tunnelKey, nonce)` 为 key 入 LRU 缓存,TTL = 时间窗 × 2;命中即拒
   - 现位置:扩展 `verifySignature` 或在 `Engine.verifySignature` 里包一层

3. **BACKHAUL REPLY 验签缺失**
   `Engine.finishHandshake` 当前只校验 `tunnelKey == agent.tunnelKey`,**没有调** `verifySignature` 验证 backhaul handshake token。意味着任何能猜到 connectionId 的人构造一个合法 BACKHAUL token 都能接管别人的握手。
   修法:`WebSocketBridgeServerHandler#finishHandshake` 在调 engine 之前先 `engine.verifySignature(tunnelKey, payload)`;或在 BACKHAUL handshake 入口处统一做。

4. **server / agent 两侧 HMAC 实现重复且容易漂移**
   `WebSocketBridgeUtil#hmacSha256`(server)与 `WebSocketBridgeAgentHandler#writeSignature` 中的 Mac 调用(agent)算法相同但代码各写一份。改协议时两边都要同步改。可抽到 `pangolin-common`(取消 `WebSocketBridgeUtil` 与 agent 的私有副本),单一来源。

5. **demo `main` 的 secretKey 完全可预测**
   `WebSocketBridgeAgent.java:122` `("Local" + "-v" + WebSocketBridgeAgentHandler.AGENT_VERSION).getBytes()`,只能 demo。生产环境从启动参数 / 配置读。
