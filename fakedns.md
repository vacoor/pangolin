# Fake DNS 模块技术文档

**模块路径**: `com.github.pangolin.routing.acceptor.tun.fakedns`
**文档日期**: 2026-04-01

---

## 一、设计目标

在 TUN 透明代理场景下，应用发起 TCP/UDP 连接时目标是 IP 地址，代理需要知道目标**域名**才能做路由决策（如走哪条代理链）。Fake DNS 通过以下机制解决这个问题：

1. 拦截所有 DNS 查询（UDP 53）
2. 对需要代理的域名返回一个虚假 IP（Fake IP），不查询真实 DNS
3. 应用拿到假 IP 后发起连接，TUN 拦截该连接并通过假 IP 反查出原始域名
4. 代理层拿到域名后做路由决策，再向远端发起真实连接

```
应用 DNS 查询 example.com
        ↓
FakeDnsServer (UDP:53) 拦截
        ↓
返回假 IP 198.18.0.1（不查真实 DNS）
        ↓
应用连接 198.18.0.1:443
        ↓
TUN 拦截 → getHostByAddress(198.18.0.1) → "example.com"
        ↓
路由决策 → 代理转发到真实 example.com
```

---

## 二、整体架构

### 2.1 模块分层

```
┌─────────────────────────────────────────────┐
│  FakeDnsAcceptor  （与路由系统集成，启动入口）    │
└────────────────────┬────────────────────────┘
                     │
┌────────────────────▼────────────────────────┐
│  FakeDnsServer  （Netty UDP:53 服务）          │
│  ├── DatagramFakeDnsServerHandler            │
│  │    ├── 判断域名是否需要 fake（Predicate）   │
│  │    └── 调用 DnsEngine                     │
│  └── DatagramDnsProxyServerHandler           │
│       └── 转发到上游 DNS（fallback）           │
└────────────────────┬────────────────────────┘
                     │
┌────────────────────▼────────────────────────┐
│  FakeNameService  （DnsEngine 实现）           │
│  ├── SimpleInet4FakeDns  （IPv4 IP 池）        │
│  └── SimpleInet6FakeDns  （IPv6 IP 池）        │
│       └── AbstractFakeDns                    │
│            ├── Guava Cache（域名→IP，带 TTL）  │
│            ├── ConcurrentHashMap（IP→域名）   │
│            └── SimplePool（空闲 IP 队列）      │
└─────────────────────────────────────────────┘
```

### 2.2 接口定义

**`FakeDns`** — 假 IP 的生命周期管理

| 方法 | 说明 |
|------|------|
| `isFake(byte[])` | 判断 IP 是否属于假 IP 段 |
| `lookup(byte[])` | 假 IP → 域名（反向查询） |
| `tryAcquire(hostname, ttl)` | 为域名分配假 IP |
| `tryReference(byte[])` | 增加 IP 引用计数 |
| `tryRelease(byte[], hostname)` | 释放 IP（减引用计数） |

**`DnsEngine`** — DNS 协议层

| 方法 | 说明 |
|------|------|
| `lookup(DatagramDnsQuery)` | 处理 DNS 查询，返回携带假 IP 的响应 |
| `isFakeAddress(byte[])` | 判断是否为假 IP |
| `getHostByAddress(byte[])` | IP → 域名（供 TUN 层还原域名使用） |

---

## 三、核心数据结构

### 3.1 双向映射（AbstractFakeDns）

```
leases:    "example.com" → 198.18.0.1    （Guava Cache，有 TTL，访问后重置倒计时）
hostnames: 198.18.0.1 → "example.com"    （ConcurrentHashMap，永久，TTL 过期时同步清理）
```

两张表保持强一致：分配时同时写入，释放时同时删除。

### 3.2 IP 池（SimplePool）

```
idles:     ConcurrentLinkedQueue（无锁，存放空闲 IP）
generator: Generator<T>（IP 地址生成器，延迟分配）
```

- 优先复用 idles 中回收的 IP
- idles 为空时才调用 generator 生成新 IP
- generator 返回 null 表示 IP 段已耗尽

### 3.3 IP 范围计算

**IPv4**（如 `198.18.0.0/24`）:
```
lowerBound = networkAddress + 2          // 198.18.0.2（跳过网络地址）
upperBound = networkAddress + hostMask - 2  // 198.18.0.254（跳过广播地址）
可分配数量：253 个
```

**IPv6**（如 `2001:2::/48`）：使用 BigInteger 处理 128 位地址，可分配数量极大。

---

## 四、主要流程

### 4.1 DNS 查询处理

```
DatagramFakeDnsServerHandler.channelRead0()
    │
    ├─ 域名在 MS 网络检测列表中？ ──YES──► fireChannelRead（上游 DNS）
    │   (dns.msftncsi.com 等)
    │
    ├─ checker.test(domain) == false？ ──YES──► fireChannelRead（上游 DNS）
    │   (direct 路由的域名不 fake)
    │
    └─ FakeNameService.lookup(query)
            │
            ├─ A 记录 ──► inet4Dns.doResolve(hostname) ──► 返回 Inet4Address
            ├─ AAAA 记录（当前注释，预留）
            └─ HTTPS(65) ──► 分配 IPv4 + 构建 SVCB 记录（含 ALPN=h2 和 IPv4 Hint）
```

### 4.2 IP 分配流程

```java
// AbstractFakeDns.doResolve(hostname)
leases.get(hostname, () -> doAcquire(hostname))
// Cache hit → 返回现有 IP，刷新 TTL
// Cache miss → 调用 doAcquire

// doAcquire：
do {
    address = idles.acquire()          // 从空闲池取或生成新 IP
    if (address == null) return null   // IP 池耗尽
    if (hostnames.putIfAbsent(address, hostname) == null)
        return address                 // putIfAbsent 成功，IP 未被占用
    // 否则该 IP 已被其他域名占用，继续申请下一个
} while (true)
```

IPv4 生成使用 `AtomicInteger` CAS，IPv6 使用 `AtomicReference<BigInteger>` CAS，保证无锁并发安全。

### 4.3 TTL 过期与 IP 回收

```
分配时：leases.put(hostname, ip)
访问时：leases.get(hostname) 刷新 lastAccessTime（expireAfterAccess 语义）
过期时：Guava RemovalListener 自动触发
    → hostnames.remove(ip)    清理正向映射
    → idles.release(ip)       IP 归还空闲池
```

TTL 过期后同一域名再次访问，会重新分配 IP（可能与之前不同）。

### 4.4 域名还原（TUN 层使用）

```java
// FakeNameService.getHostByAddress(byte[] address)
// 当应用连接假 IP 时，TUN 层调用此方法还原域名
hostnames.get(InetAddress.getByAddress(address))
// 同时调用 leases.getIfPresent(hostname) 刷新 TTL
```

---

## 五、Handler 处理链

### DatagramFakeDnsServerHandler
- 判断是否使用 fake 解析（路由规则 + MS 域名过滤）
- 命中则调用 FakeNameService 生成响应
- 未命中则 `fireChannelRead` 交给下一个 Handler

### DatagramDnsProxyServerHandler
- fallback：将 DNS 查询转发给上游真实 DNS
- 上游地址通过系统 API 自动检测（Windows / macOS），Linux 目前硬编码
- 响应写入 `MyDnsCache` 缓存后返回客户端

### DatagramDnsResponseEncoder2
- 修正响应包的源/目地址（将发送方和接收方互换），确保 UDP 响应正确回送给发起方

---

## 六、DNS 响应缓存（上游 DNS 缓存）

```
MyDnsCache
├── DnsResponseCache (A 记录)
├── DnsResponseCache (AAAA 记录)
└── DnsResponseCache (HTTPS 记录)
     └── DnsRecordCache
          ├── ConcurrentMap<hostname, Entries>
          └── 每条记录按 TTL 调度过期任务（EventLoop.schedule）
               └── 过期时 resolveCache.remove(hostname)
```

此缓存仅用于上游 DNS fallback 路径，不影响假 IP 分配逻辑。

---

## 七、边界处理

| 情况 | 处理方式 |
|------|---------|
| IP 池耗尽 | `doAcquire` 返回 null，`lookup` 返回 null，降级到上游 DNS |
| 并发写入同一域名 | Guava Cache 内部加锁，loader 只执行一次 |
| IP 被多个域名竞争 | `putIfAbsent` 失败则重新申请，循环直到成功或池耗尽 |
| TTL 过期后重新访问 | 重新分配 IP，可能与之前不同 |
| MS 网络检测域名 | 强制走上游 DNS，避免影响系统网络状态检测 |
| HTTPS(type=65) 记录 | 分配 IPv4 假 IP 并构建 SVCB 响应（含 ALPN=h2 hint） |

---

## 八、配置参数

| 参数 | 类型 | 示例 | 说明 |
|------|------|------|------|
| `inet4Subnet` | String | `198.18.0.0/24` | IPv4 假 IP 子网，需为保留段 |
| `inet6Subnet` | String | `2001:2::/48` | IPv6 假 IP 子网 |
| `leaseTime` | int (秒) | `60` | IP 租期（即 DNS TTL），无访问超时后回收 |

子网建议使用 RFC 5737 / RFC 3849 保留地址段，避免与真实路由冲突。

---

## 九、与路由系统的集成

`FakeDnsAcceptor` 是本模块与路由系统的唯一接入点：

```java
// 启动时根据路由规则构建 Predicate
Predicate<String> checker = domain -> {
    Route r = context.getRoute(InetSocketAddress.createUnresolved(domain, 0));
    // direct 路由的域名不 fake，让系统正常解析
    return r == null || !DirectUpstream.INSTANCE.name().equalsIgnoreCase(r.getUpstream());
};

FakeDnsServer.startFakeDns(engine, checker);
```

TUN 层建立连接时调用 `DnsEngine.getHostByAddress()` 还原域名，再交回路由系统做决策。

---

## 十、类职责一览

| 类 | 层次 | 职责 |
|----|------|------|
| `FakeDnsAcceptor` | 入口 | 与路由系统集成，启动 FakeDns 服务 |
| `FakeDnsServer` | 网络层 | 创建 Netty UDP:53 服务，组装 Handler 链 |
| `DatagramFakeDnsServerHandler` | Handler | 拦截 DNS 查询，判断是否 fake |
| `DatagramDnsProxyServerHandler` | Handler | 转发到上游 DNS（fallback） |
| `DatagramDnsResponseEncoder2` | Handler | 编码 DNS 响应，修正源/目地址 |
| `FakeNameService` | 核心 | `DnsEngine` 实现，协调 IPv4/IPv6 假 DNS |
| `AbstractFakeDns` | 核心 | 假 DNS 通用逻辑（映射、TTL、IP 池） |
| `SimpleInet4FakeDns` | 核心 | IPv4 IP 池，AtomicInteger CAS 分配 |
| `SimpleInet6FakeDns` | 核心 | IPv6 IP 池，BigInteger CAS 分配 |
| `Generator<T>` | 工具 | IP 地址生成策略接口 |
| `MyDnsCache` | 缓存 | 上游 DNS 响应多类型缓存 |
| `DnsResponseCache` | 缓存 | 单类型 DNS 响应缓存 |
| `DnsRecordCache` | 缓存 | 单条 DNS 记录缓存，支持 TTL 自动过期 |
| `DnsCodecUtil` | 工具 | DNS 域名编解码 |
| `DnsOverHttpServer` | DoH | DNS over HTTPS 服务器（独立启动） |
