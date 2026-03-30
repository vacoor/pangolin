# 代理协议改造方案

> 本文档描述在 pangolin-routing 中扩展支持新代理协议的技术方案。
> 项目已实现：HTTP、SOCKS4/5、Shadowsocks、Trojan、SSH、WebSocket。

## 扩展模式

每个新协议遵循统一扩展模式：

```
出站侧（连接远端代理服务器）：
  AbstractUpstreamFactory  ←→  XxxProxyHandler（客户端握手）
  注册到 META-INF/services/...UpstreamFactory

入站侧（接受本地客户端，按需）：
  MixinAcceptorHandshakerFactory  ←→  XxxProxyServerHandler
  注册到 META-INF/services/...MixinAcceptorHandshakerFactory
```

pangolin 主要作为**客户端路由器**使用，大多数新协议只需实现出站侧。

---

## 优先级

| 优先级 | 协议 | 理由 |
|---|---|---|
| P0 | VLESS | 轻量，协议简单，用户量大 |
| P0 | VMess | 存量最大 |
| P1 | Hysteria2 | 弱网场景刚需，项目已有 QUIC 规划空间 |

---

## 一、VMess

### 背景

V2Ray 的核心私有协议，存量用户最多。

### 依赖

无现成 Java 库，有两种方案：

- **方案 A（纯 Java 实现）**：手动实现 VMess 握手与加密，工程量较大。
- **方案 B（进程桥接）**：本地启动 v2ray-core 进程暴露 SOCKS5，pangolin 通过现有 `SocksUpstreamFactory` 连接，快速落地。

推荐优先采用方案 B 验证可用性，再按需做方案 A 的原生实现。

### 出站侧（方案 A 原生实现）

**新增文件：**

| 文件 | 说明 |
|---|---|
| `upstream/spi/VMessUpstreamFactory.java` | 解析 `vmess://` URI，创建 VMessUpstream |
| `handler/client/VMessProxyHandler.java` | VMess 握手与数据加密，extends AbstractProxyHandler |
| `handler/client/VMessCryptCodec.java` | AES-128-CFB 流式加解密 Codec |

**握手流程：**

```
1. 生成 16 字节随机 IV + 16 字节随机 Key
2. 构造 VMessRequestHeader（版本、附加数据、命令、目的地址）
3. 使用 UUID 的 MD5 派生 cmdKey，AES-128-CFB 加密 Header
4. 数据帧：AES-128-CFB(IV, Key) 加密 payload
```

**URI 格式：**

```
vmess://base64({"v":"2","ps":"name","add":"host","port":"443","id":"uuid","aid":"0","net":"tcp","type":"none"})
```

**SPI 注册：**

```
# META-INF/services/com.github.pangolin.routing.upstream.UpstreamFactory
com.github.pangolin.routing.upstream.spi.VMessUpstreamFactory
```

**实现难点：**

- 请求头需要携带当前时间戳的 `fnv1a` 哈希前缀，时钟误差超过 30 秒连接会被拒绝。
- Mux.Cool 多路复用（可选，暂不实现）。

---

## 二、VLESS + Reality

### 背景

VMess 的简化替代，去掉了对称加密层（依赖 TLS 提供加密）。配合 Reality 传输层可完美伪装为真实 TLS 网站，抗探测能力极强，是当前推荐的首选协议。

### 出站侧

**新增文件：**

| 文件 | 说明 |
|---|---|
| `upstream/spi/VlessUpstreamFactory.java` | 解析 `vless://` URI |
| `handler/client/VlessProxyHandler.java` | VLESS 握手，extends AbstractProxyHandler |
| `handler/client/reality/RealityTlsHandler.java` | Reality uTLS 指纹伪装 |

**握手流程（VLESS over Reality TLS）：**

```
1. 建立 TLS 连接（使用 Reality 的 uTLS 指纹伪装特定浏览器）
2. 发送 VLESS Request Header：
     版本(1B) | UUID(16B) | 附加长度(1B) | 附加数据 | 命令(1B) | 端口(2B) | 地址类型(1B) | 地址
3. 接收 VLESS Response Header：
     版本(1B) | 附加长度(1B) | 附加数据
4. 全双工透传（无额外加密层，由 TLS 提供）
```

**URI 格式：**

```
vless://uuid@host:port?encryption=none&security=reality
  &sni=www.example.com&fp=chrome&pbk=公钥&sid=短ID
  &flow=xtls-rprx-vision#name
```

**SPI 注册：**

```
# META-INF/services/com.github.pangolin.routing.upstream.UpstreamFactory
com.github.pangolin.routing.upstream.spi.VlessUpstreamFactory
```

**实现难点：**

Reality 要求伪造特定浏览器的 TLS ClientHello 指纹（uTLS），Netty 的 `SslHandler` 不直接支持，需在 TLS 握手阶段注入自定义 ClientHello 字节。可通过 `netty-tcnative` 或 Conscrypt 在底层 SSL 引擎上做定制。这是本协议最大的技术障碍。

---

## 三、Hysteria2

### 背景

基于 QUIC（UDP）的代理协议，专为高丢包、高延迟网络设计，在弱网环境下性能远超 TCP 类协议。

### 依赖

```xml
<dependency>
    <groupId>io.netty.incubator</groupId>
    <artifactId>netty-incubator-codec-quic</artifactId>
    <version>0.0.66.Final</version>
</dependency>
```

### 出站侧

**新增文件：**

| 文件 | 说明 |
|---|---|
| `upstream/spi/HysteriaUpstreamFactory.java` | 解析 `hysteria2://` URI |
| `upstream/hysteria/HysteriaConnectionPool.java` | QUIC 连接复用池（每个 server 一个连接，多 Stream 复用） |
| `handler/client/HysteriaProxyHandler.java` | 基于 QUIC Stream 的代理握手 |
| `handler/client/HysteriaUdpProxyHandler.java` | UDP over Hysteria2 |

**握手流程：**

```
1. 建立 QUIC 连接（TLS 1.3 + ALPN "h3"）
2. 开启新 QUIC Stream，发送 TCP 请求帧（JSON）：
     { "addr": "host:port", "padding": "<random>" }
3. 接收响应帧：
     { "ok": true, "message": "" }
4. 全双工转发
```

**URI 格式：**

```
hysteria2://password@host:port?sni=example.com&insecure=0#name
```

**SPI 注册：**

```
# META-INF/services/com.github.pangolin.routing.upstream.UpstreamFactory
com.github.pangolin.routing.upstream.spi.HysteriaUpstreamFactory
```

**实现难点：**

`Upstream.newSocketProxyHandler()` 接口假设底层是 TCP SocketChannel，而 Hysteria2 基于 QUIC Stream。需要在 `HysteriaUpstream` 中重写连接建立逻辑，绕过 `SocketChannelFactory` 直接创建 `QuicChannel`，并在抽象层引入新的钩子方法（如 `createChannel()`）供 QUIC 类协议使用。

