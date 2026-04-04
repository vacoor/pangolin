# Fake DNS 模块优化方案（v2）

**基于版本**: fakedns.md（当前实现分析）
**文档日期**: 2026-04-01

---

## 一、问题全景

深度阅读代码后，识别出以下三类问题，按影响程度排列：

| 类别 | 数量 | 说明 |
|------|------|------|
| 正确性问题 | 3 | 当前行为与预期不符，会影响功能 |
| 架构问题 | 3 | 模块边界不清晰，职责混乱 |
| 性能问题 | 4 | 可量化的热路径开销 |

---

## 二、正确性问题（应优先修复）

### ~~C-1: 上游 DNS 查询失败时客户端无响应~~ ✅ 已修复

**位置**: `DatagramDnsProxyServerHandler.java:98`

```java
resolver.query(question).addListener(f -> {
    if (f.isSuccess()) {
        // ... 正常处理
    }
    // f.isSuccess() == false 时什么都不做
    // 客户端收不到任何响应，等到超时
});
```

上游 DNS 超时、拒绝、网络异常时，客户端 DNS 查询会挂起直到应用层超时（通常 5-30 秒），期间页面/连接无响应。

**修复方向**: 失败时返回 `SERVFAIL` 响应：

```java
resolver.query(question).addListener(f -> {
    if (f.isSuccess()) {
        // ... 正常处理
    } else {
        DatagramDnsResponse servfail = new DatagramDnsResponse(recipient, sender, id);
        servfail.setCode(DnsResponseCode.SERVFAIL);
        ctx.writeAndFlush(servfail);
    }
});
```

---

### ~~C-2: IP 池耗尽时客户端同样无响应~~ ✅ 分析有误，无需修复

**位置**: `FakeNameService.java` → `AbstractFakeDns.doAcquire()`

```java
// doAcquire() 返回 null → lookup() 返回 null
// DatagramFakeDnsServerHandler.java:61
DatagramDnsResponse lookup = engine.lookup(query);
if (null != lookup) {
    ctx.writeAndFlush(lookup);
    return;
}
// lookup == null 时 fallthrough 到 fireChannelRead，
// 但此时 query 已被上面逻辑处理，行为未定义
```

重新阅读代码后确认，当 `engine.lookup(query)` 返回 null 时，代码自然 fallthrough 到 `ctx.fireChannelRead(query.retain())`，会正确转发给上游 DNS。此问题不存在，原分析有误。

---

### ~~C-3: 持久化 `remove()` 的正则 Bug~~ ✅ 已修复

**位置**: `AbstractFakeDns.java:104`

```java
// 错误：\\s* 匹配零个或多个空白，会将 "example.com 1.2.3.4" 按每个字符分割
final String[] m = line.split("\\s*", 2);

// 正确：\\s+ 匹配一个或多个空白
final String[] m = line.split("\\s+", 2);
```

虽然 `write()` 和 `remove()` 的调用处目前被注释掉了，但一旦启用持久化，此 Bug 会导致文件解析出错，所有保存的映射丢失。

**修复方向**: 将 `\\s*` 改为 `\\s+`，同时补充 `m.length < 2` 的防御判断。

---

## 三、架构问题

### A-1: 每次 DNS 查询都触发路由计算，无缓存

**位置**: `FakeDnsAcceptor.java:34` + `DatagramFakeDnsServerHandler.java:60`

```java
// FakeDnsAcceptor：
Predicate<String> domainFakePredicate = domain -> !isDirect(context, domain);

// isDirect() 每次都调用 context.getRoute()
// 如果路由系统使用正则匹配、trie 查找，每次 DNS 查询都走完整的规则链
```

DNS 查询频率远高于路由规则的变更频率（规则通常几分钟甚至更长时间才更新一次）。对同一域名的判断结果在短时间内是稳定的，重复计算没有意义。

**修复方向**: 在 `DatagramFakeDnsServerHandler` 内部加一个短 TTL 的本地缓存，缓存路由判断结果：

```java
// 伪代码：加 Guava Cache 做结果缓存
private final Cache<String, Boolean> routeCache = CacheBuilder.newBuilder()
    .expireAfterWrite(30, TimeUnit.SECONDS)
    .maximumSize(10_000)
    .build();

boolean shouldFake = routeCache.get(domain, () -> checker.test(domain));
```

路由规则变更时需要清空此缓存（可通过事件通知）。

---

### A-2: TUN 层与 FakeDns 模块的接口不清晰

**当前状态**: TUN 层通过调用 `DnsEngine.getHostByAddress()` 还原域名，但 `DnsEngine` 接口还包含了 DNS 协议处理方法（`lookup`），两类职责混在一个接口里。

**影响**: TUN 层只关心"IP → 域名"这一件事，却需要依赖整个 `DnsEngine` 接口，导致编译依赖过重、测试困难。

**修复方向**: 拆分为两个接口：

```java
// 供 TUN 层使用：只关心地址映射
public interface FakeAddressResolver {
    boolean isFakeAddress(byte[] address);
    String getHostByAddress(byte[] address);  // IP → 域名
}

// 供 DNS 服务器使用：DNS 协议处理
public interface DnsQueryHandler {
    DatagramDnsResponse lookup(DatagramDnsQuery query);
}

// FakeNameService 同时实现两个接口
public class FakeNameService implements FakeAddressResolver, DnsQueryHandler { ... }
```

---

### ~~A-3: IPv4/IPv6 双栈实现之间的代码重复~~ ✅ 已修复

**位置**: `SimpleInet4FakeDns.java` 和 `SimpleInet6FakeDns.java`

两个类除了地址类型（`int` vs `BigInteger`、`Inet4Address` vs `Inet6Address`、AtomicInteger vs AtomicReference）之外，CIDR 解析、IP 范围计算、`isFakeAddress` 检查逻辑高度重复。

**影响**: 修改一个实现时需要同步修改另一个，维护成本高，容易出现不一致（当前已有一处：`read()` 持久化方法只支持 IPv4，IPv6 实现被静默忽略）。

**修复方向**: 将 CIDR 解析、范围检查、IP 计算提取到 `AbstractFakeDns` 或工具类，子类只提供类型相关的转换方法（`int → Inet4Address`，`BigInteger → Inet6Address`）。

---

## 四、性能问题

### ~~P-1: `SimplePool.acquire()` 的 `synchronized` 包含了对象创建~~ ✅ 已修复

**位置**: `AbstractFakeDns.java:173`

```java
synchronized T acquire() {
    final T address = idle.poll();
    if (null != address) {
        return address;
    }
    return factory.next();  // InetAddress.getByAddress() 在锁内执行
}
```

`idle` 已经是 `ConcurrentLinkedQueue`（线程安全），`poll()` 不需要额外锁。`synchronized` 的唯一作用是保护 `factory.next()`，但 `factory.next()` 内部（`InetAddress.getByAddress()`）是纯函数，也不需要互斥。

**影响**: 所有 DNS 查询共享同一把锁，高并发时 IP 分配成为串行瓶颈。

**修复方向**: 去掉 `synchronized`，利用 `ConcurrentLinkedQueue` 的无锁特性 + IP 生成器的原子操作：

```java
T acquire() {
    T address = idle.poll();           // ConcurrentLinkedQueue 自身是线程安全的
    return address != null ? address : factory.next();  // factory.next() 使用 CAS，无需加锁
}
```

`factory.next()` 在 IPv4（AtomicInteger CAS）和 IPv6（AtomicReference CAS）下都是无锁的，可以安全并发调用。

---

### ~~P-2: DNS 热路径上使用 `Unpooled.buffer()`~~ ✅ 已修复

**位置**: `FakeNameService.java:79`（HTTPS 记录构建）、`DoHServerHandler.java:107`

```java
// 每个 HTTPS DNS 查询都分配非池化 Heap ByteBuf
ByteBuf buf = Unpooled.buffer();
// ... 写入约 20 字节数据 ...
DefaultDnsRawRecord httpsRecord = new DefaultDnsRawRecord(..., buf);
```

非池化分配会绕过 Netty 的内存池，每次 GC 才回收，高并发时造成大量短生命周期对象堆积。

**修复方向**: 使用 `ChannelHandlerContext` 的 allocator，或通过参数传入 `ByteBufAllocator`：

```java
// 使用 pooled allocator
ByteBuf buf = ctx.alloc().buffer(32);  // 预估容量，减少扩容
```

对于 HTTPS 记录，内容长度固定（约 14 字节），可以进一步预分配精确大小。

---

### P-3: `DnsRecordCache.add()` 的 CAS 自旋创建大量临时 List

**位置**: `DnsRecordCache.java:68`

```java
while (true) {
    final List<DnsRecord> entries = this.get();
    if (!entries.isEmpty()) {
        // 每次 CAS 失败都 new 一个 ArrayList
        final List<DnsRecord> newEntries = Lists.newArrayListWithExpectedSize(entries.size() + 1);
        newEntries.add(dnsRecord);
        newEntries.addAll(entries);
        if (compareAndSet(entries, Collections.unmodifiableList(newEntries))) {
            // ...
        }
        // CAS 失败，newEntries 直接丢弃，下次循环重新 new
    }
}
```

上游 DNS 响应通常在单线程（EventLoop）中处理，实际上很少有真正的并发冲突，但 CAS 的设计让每次操作都承担了对象创建的代价。

**修复方向**: `DnsRecordCache` 只在 `DatagramDnsProxyServerHandler` 中使用，而后者运行在 EventLoop 中，实际上是单线程场景。可将 `DnsRecordCache` 改为非线程安全实现，利用 EventLoop 单线程保证替代 CAS：

```java
// 确保只在 EventLoop 线程调用，去掉 CAS，直接操作
void add(final DnsRecord record) {
    assert loop.inEventLoop();
    records.add(record);  // 普通 ArrayList，单线程安全
    rescheduleExpiration(record.timeToLive());
}
```

---

### P-4: IPv6 IP 生成在每次 CAS 失败时创建 BigInteger 对象

**位置**: `SimpleInet6FakeDns.java:83`

```java
do {
    value = current.get();
    if (value.compareTo(max) > 0) return null;
} while (!current.compareAndSet(value, value.add(BigInteger.ONE)));
// value.add(BigInteger.ONE) 每次自旋都创建新的 BigInteger 对象
```

在高并发下 CAS 失败率上升时，频繁创建 `BigInteger` 对象对 GC 有一定压力。

**修复方向**: 将 `BigInteger.ONE` 替换为预先声明的常量（已有），或考虑改用 `long` 存储 IPv6 后 64 位（代理场景下 `/48` 子网的后 80 位地址空间远超实际需求）。

```java
// 如果子网前缀 >= 64，后 64 位用 AtomicLong 即可
// 避免 BigInteger 对象创建，性能接近 IPv4 的 AtomicInteger 方案
private final AtomicLong current = new AtomicLong(minSuffix);
```

---

## 五、改进优先级与建议路径

### 第一阶段：修正正确性问题 ✅ 已完成

1. ~~**C-1** 上游 DNS 失败返回 SERVFAIL~~ — `DatagramDnsProxyServerHandler.java`，加 else 分支返回 SERVFAIL
2. ~~**C-2** IP 池耗尽 fallback 到上游 DNS~~ — 分析有误，已有正确处理，无需修改
3. ~~**C-3** 持久化 `remove()` 正则 Bug~~ — `AbstractFakeDns.java`，`\\s*` → `\\s+`，补 `m.length` 防御

### 第二阶段：性能优化（除 A-1 外）✅ 已完成

4. ~~**P-1** 去掉 `SimplePool.acquire()` 的 `synchronized`~~ — `AbstractFakeDns.java`，ConcurrentLinkedQueue + CAS 生成器无需加锁
5. ~~**P-2** 热路径 ByteBuf 改用 pooled allocator~~ — `FakeNameService.java`，HTTPS 记录构建改用 `PooledByteBufAllocator.DEFAULT.buffer(18)`
6. **A-1** DNS 查询结果缓存路由计算 — 待实施

### 第三阶段：架构重构

7. **A-2** 拆分 `DnsEngine` 接口 — 需要更新 TUN 层引用，待评估
8. ~~**A-3** 消除 IPv4/IPv6 重复代码~~ — `FakeNameService.java`，`create()` 委托给子类工厂，构造器改为接受 pre-built 实例，删除 ~20 行重复 CIDR 解析 ✅ 已完成
9. **P-3** `DnsRecordCache` 去 CAS — 需确认调用点均在 EventLoop 线程
10. **P-4** IPv6 后缀改用 AtomicLong — 仅在子网前缀 ≥ 64 时适用

---

## 六、不建议改动的部分

- **IP 生成器的 CAS 设计**（IPv4 AtomicInteger）：设计本身没有问题，仅 `SimplePool.acquire()` 的 synchronized 包裹是多余的
- **Guava Cache 的 TTL 机制**：`expireAfterAccess` + `RemovalListener` 的组合适合本场景，替换收益不明显
- **双向映射结构**（leases + hostnames）：这是 Fake DNS 的核心数据结构，设计合理，改动风险大于收益
- **MS 域名过滤**：硬编码列表虽然不优雅，但改为可配置会引入额外复杂度，收益有限
