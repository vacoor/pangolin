# 目标感知负载均衡策略设计

> 设计时间：2026-03-29

解决现有 `UpstreamSelectFactory`（基于 Ribbon `WeightedResponseTimeRule`）无法感知目标地址（destination）的问题，实现**按目标地址动态调整各服务器权重**的负载均衡策略。

---

## 一、设计目标

| 目标 | 说明 |
|------|------|
| 目标感知 | 不同 destination 对同一 server 的权重独立维护 |
| 探索保证 | 初始阶段所有 server 均有机会被尝试 |
| 质量收敛 | 连接质量好的 server 逐渐获得更高权重 |
| 自适应恢复 | 连接质量差的 server 权重衰减，但保留复活机会 |
| 追赶机制 | 低权重 server 成功时获得更大收益，避免马太效应 |

---

## 二、核心数据结构

### 2.1 单条统计记录 `RouteQuality`

每个 `(serverName, destKey)` 对应一条记录，基于 RTT 启发的质量收敛算法（详见 [`weight-convergence.md`](./weight-convergence.md)）：

```
RouteQuality {
    volatile long srtt              // 平滑有效延迟（×1000 存储，避免浮点），初始 = INITIAL_SRTT
    volatile long rttvar            // 延迟方差（×1000 存储），初始 = INITIAL_RTTVAR
    AtomicInteger nTried            // 累计尝试次数，初始 = 0
    int consecutiveFailures         // 连续失败次数；仅在 synchronized 块内读写，不需要 volatile
    volatile long circuitOpenUntil  // 熔断结束时间戳（ms）；0 = 未熔断
                                    // ⚠️ 需要 volatile：effectiveScore 在 synchronized 块外读取它，
                                    //    若非 volatile 可能读到陈旧的 0，导致熔断未及时生效

    // ⚠️ srtt、rttvar、consecutiveFailures、circuitOpenUntil 的写操作均在
    //    同一 synchronized(this) 块内完成，保证原子性。
    //    srtt、rttvar、circuitOpenUntil 借助 volatile 在锁外可见；
    //    consecutiveFailures 仅在锁内使用，无需 volatile。
}
```

- `nTried == 0`：从未尝试过该目标，触发探索优先
- `srtt` 越低 + `rttvar` 越低 → 连接越快且越稳定 → 分值越高

**选择分值**（越高越优先）：

```
score(s, d) = 1 / (srtt(s,d) + 4 × rttvar(s,d))
```

### 2.2 全局统计表 `RouteQualityTable`

#### 内存估算

每条 `RouteQuality` 对象约占 **200 字节**（volatile long × 3 + int + AtomicInteger + 对象头 + Map.Entry + String key）。以 5 台服务器、10 万个不同目标域名为例：

```
5 servers × 100,000 dests × 200 bytes ≈ 100 MB
```

在代理场景下，用户访问的域名数量长期累积可能轻松突破 10 万，若不加限制**确实存在内存溢出风险**。

#### LRU 淘汰策略

每台服务器独立维护一个容量上限为 `MAX_DESTINATIONS` 的 LRU Cache，按访问时间淘汰最久未使用的目标。

使用 **Caffeine** 实现并发安全的 LRU，替代 `Collections.synchronizedMap(LinkedHashMap)` ——后者所有操作共用单把锁，高并发下是性能瓶颈，且 `get-then-put` 的新条目写入不是原子操作：

```
RouteQualityTable {
    // serverName -> Caffeine LRU Cache(destKey -> RouteQuality)
    table: ConcurrentHashMap<String, Cache<String, RouteQuality>>

    newLruCache() = Caffeine.newBuilder()
                        .maximumSize(MAX_DESTINATIONS)   // LRU 淘汰，超出后驱逐最久未访问条目
                        .build()
}

destKey = host + ":" + port  // 例如 "1.2.3.4:443" 或 "example.com:80"
```

**读与写使用不同的 Cache 访问方式**，避免评分时创建幽灵条目：

```
// 评分（选择阶段）：不存在则返回 null，用初始参数计算，不创建条目
RouteQuality q = cache.getIfPresent(destKey)
score = q != null ? q.score() : initialScore()

// 发起连接前（确定选中某 server 后）：保证条目存在
RouteQuality q = cache.get(destKey, k -> new RouteQuality(initial))
// 连接结束后再更新 q
```

若用 `cache.get(key, loader)` 做评分，会为所有 server 的所有 dest 创建初始条目，无效地消耗 LRU 容量。`getIfPresent` 纯读、不创建，符合"只有真正发起过连接才记录"的语义。

**淘汰后的行为**：被淘汰的目标再次访问时，以初始值（`INITIAL_SRTT` / `INITIAL_RTTVAR`）重新开始统计，触发探索期逻辑——这是合理的，说明该目标长期未使用，历史数据已无参考意义。

**内存上限**（`MAX_DESTINATIONS` 默认 5000）：

```
5 servers × 5,000 dests × 200 bytes ≈ 5 MB   // 可忽略不计
```

---

## 三、质量更新规则

质量指标的更新逻辑由 **RTT 启发的连接质量收敛算法（RQC）** 定义，详见 [`weight-convergence.md`](./weight-convergence.md)。

此处仅列出与本负载均衡策略的调用接口：

| 事件 | 调用 | 说明 |
|------|------|------|
| 连接成功（握手完成）| `qualityTable.onSuccess(serverName, dest, elapsedMs)` | srtt/rttvar 向实测延迟收敛 |
| 连接失败（任意原因）| `qualityTable.onFailure(serverName, dest)` | srtt/rttvar 向 PENALTY 收敛；连续失败计数递增，达阈值后触发指数退避熔断 |

> **测量点**：`elapsedMs` 为从发起代理连接到握手完成的时间，在 `RouteQualityHandler` 拦截的握手事件中采集。

**关键参数**（与 RQC 算法共享，详见第六节）：

| 参数 | 默认值 |
|------|--------|
| α（均值平滑）| 1/4 |
| β（方差平滑）| 1/4 |
| k（方差权重）| 4 |
| `INITIAL_SRTT` | `timeout_ms / 2` |
| `PENALTY` | `2 × timeout_ms` |

---

## 四、服务器选择算法

采用两阶段策略：**探索期**保证所有服务器有机会积累数据，**利用期**按分值比例分配流量，低分服务器自然获得少量流量实现复活。

### 4.1 有效分值计算

```
effectiveScore(s, dest):
    q = qualityTable.getIfPresent(s, dest)  // 纯读，不创建条目
    if q == null or q.nTried == 0:
        // 未尝试过：用初始参数（INITIAL_SRTT=timeout/2, INITIAL_RTTVAR=0）
        return 1 / INITIAL_SRTT
    if q.circuitOpenUntil > currentTimeMillis():
        return 0    // 熔断中：从选择池完全排除，避免用户请求等待 timeout 后才失败
    return 1 / (q.srtt + 4 × q.rttvar)
```

### 4.2 两阶段选择

```
select(servers, destination):

    // === 阶段一：探索期 ===
    // 存在从未尝试过该目标的服务器时，以较高概率优先探索，
    // 避免未知服务器因初始分值过低而永远得不到机会
    untried = {s | nTried(s, dest) == 0}
    if untried is not empty:
        if random() < P_EXPLORE:          // 默认 0.6
            return randomFrom(untried)
        // 未触发探索，继续参与阶段二竞争

    // === 阶段二：分值比例选择 ===
    // 所有服务器（含 untried）均按有效分值参与比例抽样
    // 高分服务器获得多数流量，低分服务器获得少量流量（自然复活机制）
    // 若所有服务器均熔断，scoreProportionalRandom 返回 null，上层直接报错
    return scoreProportionalRandom(servers, dest)
```

### 4.3 分值比例抽样（`scoreProportionalRandom`）

```
scoreProportionalRandom(servers, dest):
    pairs = [(s, effectiveScore(s, dest)) for s in servers]
    pairs = [(s, sc) for s, sc in pairs if sc > 0]  // 过滤熔断中的服务器（score=0）
    if pairs is empty:
        return null                         // 所有服务器均熔断：无可用服务器，上层直接报错
    shuffle(pairs)                         // 对 (server, score) 整体打乱，消除顺序偏差
                                           // ⚠️ 不可先 shuffle(servers) 再 zip(scores)——会错位
    total = sum(sc for _, sc in pairs)
    r = uniform(0, total)
    cumulative = 0
    for s, sc in pairs:
        cumulative += sc
        if cumulative >= r:
            return s
```

### 4.4 选择机制说明

| 机制 | 实现方式 | 说明 |
|------|---------|------|
| 探索保证 | 阶段一 P_EXPLORE | 未尝试服务器以 60% 概率被优先选中 |
| 质量优先 | 分值比例 | 优质服务器（低延迟低抖动）自然获得多数流量 |
| 复活机制 | 比例的自然结果 | 低分服务器仍有少量流量，恢复后分值自动上升 |
| 稳定性感知 | rttvar 惩罚 | 高抖动服务器即使平均延迟相同也得分更低 |

> **与原方案的差异**：不再对服务器做 positive/neutral/negative 硬性分组，改为连续分值的比例选择。低分服务器（曾大量失败）通过比例抽样获得自然的复活机会，无需显式 P_NEGATIVE 参数。

---

## 五、实现方案

### 5.1 类结构

```
com.github.pangolin.routing.upstream.spi
└── UpstreamAdaptiveFactory                    // 实现 UpstreamCombiner，注册名 "select"（替换原 UpstreamSelectFactory）
    └── AdaptiveUpstream extends AbstractUpstream
        ├── RouteQualityTable                           // 全局质量统计表（单例共享）
        │   └── ConcurrentHashMap<server, Cache<dest, RouteQuality>>  // Caffeine LRU per server
        │       RouteQuality { volatile srtt, volatile rttvar, nTried,         // EWMA 质量指标
        │                      consecutiveFailures, volatile circuitOpenUntil } // 熔断状态
        ├── select(destination) : Upstream         // 执行上述两阶段选择算法
        └── newSocketProxyHandlers(destination) : List<ChannelHandler>
            // 1. 调用 select(destination) 选出目标 upstream
            //    若返回 null（所有服务器均熔断）→ 直接向客户端返回连接失败，不发起任何上游连接
            // 2. 取该 upstream 自身的 handler 列表
            // 3. 在列表头部插入 RouteQualityHandler（拦截握手事件 + 记录质量数据）
            // 4. 返回完整的 handler 链

RouteQualityHandler extends ChannelDuplexHandler
    ├── serverName        : String
    ├── destination       : InetSocketAddress
    ├── qualityTable      : RouteQualityTable
    ├── startTime         : long     // 连接发起时间（ms）
    ├── handshakeCompleted: boolean  // 握手是否已完成
    └── failureRecorded   : boolean  // 防止 TCP 失败 + channelInactive 双重记录
    // 事件处理：
    //   connect()          → 记录 startTime，TCP 失败时调用 onFailure（含 failureRecorded 保护）
    //   userEventTriggered → HandshakeSuccessEvent → onSuccess(elapsed)
    //                        HandshakeFailureEvent → onFailure
    //   channelInactive    → 握手未完成且未记录失败 → onFailure

// UpstreamUrlTestFactory 应 extends UpstreamAdaptiveFactory（而非 UpstreamSelectFactory），
// 以复用目标感知选择逻辑替代原 Ribbon 实现。
```

### 5.2 与现有框架的集成点

`UpstreamAdaptiveFactory` 实现 `UpstreamCombiner`，**保持 `name()` 返回 `"select"`**，直接替换原有实现，无需修改任何业务配置：

```java
public class UpstreamAdaptiveFactory implements UpstreamCombiner {

    @Override
    public String name() {
        return "select";   // 保持与 UpstreamSelectFactory 相同的名称，透明替换
    }

    @Override
    public Upstream combine(String name, Iterable<String> serverNames, UpstreamRegistry registry) {
        RouteQualityTable table = new RouteQualityTable();
        return new AdaptiveUpstream(name, serverNames, registry, table);
    }
}
```

**通过 SPI 文件切换**，注释掉原有实现，添加新实现：

```
# 文件：META-INF/services/com.github.pangolin.routing.upstream.UpstreamCombiner

#com.github.pangolin.routing.upstream.spi.UpstreamSelectFactory   ← 注释掉
com.github.pangolin.routing.upstream.spi.UpstreamAdaptiveFactory  ← 新增
com.github.pangolin.routing.upstream.spi.UpstreamChainFactory
com.github.pangolin.routing.upstream.spi.UpstreamUrlTestFactory
```

不需要修改任何业务配置，原来使用 `type: select` 的配置项自动切换到新实现。

### 5.3 RouteQualityHandler 关键逻辑

#### 握手事件传递方式

`AbstractProxyHandler` 中的 `handshakePromise` 是 `private` 字段，外部无法直接监听。因此采用 **`userEventTriggered` 事件机制**：

- `AbstractProxyHandler.setHandshakeSuccess()` 末尾追加 `ctx.fireUserEventTriggered(HandshakeSuccessEvent.INSTANCE)`
- `AbstractProxyHandler.setHandshakeFailure()` 末尾追加 `ctx.fireUserEventTriggered(HandshakeFailureEvent.INSTANCE)`
- `RouteQualityHandler` 通过 `userEventTriggered()` 拦截这两个事件

```java
// 握手事件标记类（定义在 AbstractProxyHandler 旁）
public final class HandshakeSuccessEvent { public static final HandshakeSuccessEvent INSTANCE = new HandshakeSuccessEvent(); }
public final class HandshakeFailureEvent { public static final HandshakeFailureEvent INSTANCE = new HandshakeFailureEvent(); }
```

#### RouteQualityHandler 实现

```java
public class RouteQualityHandler extends ChannelDuplexHandler {
    private long startTime;
    private boolean handshakeCompleted;
    private boolean failureRecorded;   // 防止 TCP 失败 + channelInactive 双重记录

    @Override
    public void connect(ChannelHandlerContext ctx, SocketAddress remote,
                        SocketAddress local, ChannelPromise promise) throws Exception {
        startTime = System.currentTimeMillis();           // 记录发起时间
        promise.addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess() && !failureRecorded) {
                // TCP 建立失败（ECONNREFUSED、timeout 等）→ 记录失败
                failureRecorded = true;
                qualityTable.onFailure(serverName, destination);
            }
            // TCP 成功不代表握手成功，继续等待握手事件
        });
        super.connect(ctx, remote, local, promise);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof HandshakeSuccessEvent) {
            handshakeCompleted = true;
            long elapsed = System.currentTimeMillis() - startTime;
            qualityTable.onSuccess(serverName, destination, elapsed);
        } else if (evt instanceof HandshakeFailureEvent) {
            handshakeCompleted = true;
            if (!failureRecorded) {
                failureRecorded = true;
                qualityTable.onFailure(serverName, destination);  // 握手失败，不传 elapsed
            }
        }
        ctx.fireUserEventTriggered(evt);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (!handshakeCompleted && !failureRecorded) {
            // 握手完成前连接异常断开，视为失败
            failureRecorded = true;
            qualityTable.onFailure(serverName, destination);
        }
        ctx.fireChannelInactive();
    }
}
```

> **双重记录防护**：`failureRecorded` 标志确保同一次连接尝试至多记录一次失败——TCP 层失败会触发 connect 监听器，随后还会触发 `channelInactive`，`failureRecorded` 阻止第二次 `onFailure()` 调用。
>
> **关键点**：任何失败（TCP 拒绝、超时、握手失败）均调用 `onFailure()`，不传实测时间，统一映射为 `PENALTY`。只有握手成功才调用 `onSuccess()` 并传入从发起到完成的实测 `elapsed`。

---

## 六、关键参数

**质量收敛参数**（由 RQC 算法使用，详见 `weight-convergence.md`）：

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `ALPHA` (α) | 0.25 | 均值平滑系数，控制 srtt 对新样本的跟随速度 |
| `BETA` (β) | 0.25 | 方差平滑系数，控制 rttvar 的收敛速度 |
| `K` | 4 | 方差在分值中的惩罚倍数 |
| `INITIAL_SRTT` | `timeout_ms / 2` | 未尝试服务器的初始平滑延迟 |
| `INITIAL_RTTVAR` | `0` | 初始方差为 0；首次**成功**样本后按 `sample/2` 特殊初始化（见 `weight-convergence.md` §四）|
| `PENALTY` | `2 × timeout_ms` | 任意失败的虚拟延迟惩罚值 |

**选择策略参数**：

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `P_EXPLORE` | 0.60 | 探索期优先选择未尝试服务器的概率 |
| `MAX_DESTINATIONS` | 5000 | 每台服务器最多统计的目标域名数，超出后 LRU 淘汰 |

**熔断参数**（详见 `weight-convergence.md` §四.3）：

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `CIRCUIT_OPEN_THRESHOLD` | 5 | 触发熔断所需的连续失败次数 |
| `CIRCUIT_BASE_MS` | 30000ms（30s）| 首次触发熔断的冷却时长（指数退避基数）|
| `CIRCUIT_MAX_MS` | 600000ms（10min）| 熔断冷却时长上限 |

---

## 七、与现有 StatsUpstream 的关系

现有 `StatsUpstream` 基于 Ribbon 做全局统计（不区分 destination，不感知响应时间）：
- 通过 SPI 文件切换后，`UpstreamAdaptiveFactory` 替代 `UpstreamSelectFactory`，**不依赖 Ribbon**，独立维护 `RouteQualityTable`
- `StatsUpstream` 类本身不再被 `select` 组合器使用，可保留但不影响新逻辑
- 切换完全透明，无需修改业务配置

---

## 八、质量收敛示意

详细收敛分析（含数学推导、数值追踪、三种典型轨迹）见 [`weight-convergence.md`](./weight-convergence.md)。

以 3 台服务器（A 优质稳定 / B 中等 / C 持续失败后恢复），稳定后典型状态：

```
srtt/rttvar 演化（timeout=10s, PENALTY=20s）：

  A: 首次成功 80ms → 首次特殊初始化：srtt=80ms, rttvar=40ms（立即收敛）
     后续持续成功  → delta≈0，srtt 稳定，rttvar 逐渐衰减至趋近 0
     稳定后：srtt≈80ms, rttvar≈0ms → score ≈ 1/80

  B: 偶发失败      → SRTT_eq = p×L + (1-p)×PENALTY，稳定在中间水平
     稳定后：srtt≈2000ms, rttvar 中等 → score 中等

  C: 持续失败      → 连续失败 ≥ CIRCUIT_OPEN_THRESHOLD（默认 5）次后触发熔断
     熔断中：score=0，彻底排出选择池，冷却期（30s 起，指数退避至 10min）到期后允许探测
     恢复后：首次成功触发重新初始化（srtt←sample, rttvar←sample/2），1 次即快速恢复

流量分布（score 比例抽样）：

  ① 熔断期（C circuit-open）：
     A:  score ≈ 1/80    → ~99% 流量
     B:  score ≈ 1/8000  → ~1%  流量
     C:  score = 0       →   0% 流量（彻底排除，无超时等待）

  ② 恢复后（C 首次探测成功，srtt 重新初始化，sample ≈ 50ms）：
     C:  score = 1/(50 + 4×25) = 1/150 → 与 A 量级相当，短期内获得大量流量
     随后 C 持续成功，rttvar 衰减，score 逐渐稳定
     A、B 按各自实测水平参与竞争，无需人工干预
```

---

## 九、边界情况处理

| 情况 | 处理方式 |
|------|---------|
| 所有服务器均未尝试过该目标 | 纯随机（等同于探索期 `P_EXPLORE = 1.0`） |
| 单台服务器 | 无需选择，直接使用，仍记录质量数据供后续参考 |
| 目标域名数量超过 `MAX_DESTINATIONS` | LRU 淘汰最久未访问的条目，被淘汰目标下次访问时重新以初始值探索 |
| 并发写 `RouteQuality` | `srtt`/`rttvar` 在 `synchronized(quality)` 块内原子更新；内层 Cache 使用 Caffeine 保证并发安全 |
| 服务器全部 score 极低（持续失败）| 比例抽样仍可工作（各服务器按相对比例分配），不会退化为无服务可用 |
| 某服务器对特定目标永久不可达 | 连续失败 ≥ `CIRCUIT_OPEN_THRESHOLD` 后触发熔断，`effectiveScore=0` 彻底排出选择池；冷却期到期后自动探测，失败则退避翻倍，成功则熔断关闭 |
| 所有服务器对某目标均熔断 | `select()` 返回 `null`，上层直接向客户端报错（目标不可达），避免无意义的超时等待 |
