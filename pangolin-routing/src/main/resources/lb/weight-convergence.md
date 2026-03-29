# 连接质量收敛算法（RQC）

**RTT-inspired Quality Convergence Algorithm**

> 该算法独立于负载均衡选择策略，专注于描述单个 `(server, destination)` 对的连接质量如何随成功/失败结果及响应时间动态演化并最终收敛。
>
> 核心思想来源于 TCP Jacobson/Karels RTT 估算算法（RFC 6298），针对代理连接场景的信号特点进行了适配。

---

## 一、设计原则

### 与 TCP RTT 的关键差异

| 维度 | TCP RTT | 本算法（RQC） |
|------|---------|--------------|
| 信号类型 | 连续延迟值（ms）| 成功→实测延迟；失败→固定惩罚值 |
| 平滑系数 α | 1/8（慢，TCP 场景稳定）| **1/4**（快，代理场景变化剧烈）|
| 失败处理 | 不涉及 | 任何失败统一映射为 `PENALTY`，与失败速度无关 |
| 用途 | 计算 RTO 超时阈值 | 计算服务器选择分值 |

**失败统一映射的必要性**：若用实际失败时间作为样本，`ECONNREFUSED`（延迟≈1ms）会被误判为极优质服务器。因此，任何原因的失败（超时、拒绝、握手失败）均映射为相同的 `PENALTY` 值，语义上表示"此服务器对此目标不可用"。

---

## 二、状态变量

对每个 `(server s, destination d)` 对维护：

| 变量 | 类型 | 初始值 | 含义 |
|------|------|--------|------|
| `srtt(s,d)` | volatile long (ms×1000) | `INITIAL_SRTT` | 平滑有效延迟 |
| `rttvar(s,d)` | volatile long (ms×1000) | `0`（首次**成功**样本后特殊初始化）| 延迟方差（稳定性指标）|
| `n_tried(s,d)` | AtomicInteger | 0 | 历史尝试次数（探索期判断）|
| `consecutive_failures(s,d)` | int | 0 | 连续失败次数；仅在 synchronized 块内读写 |
| `circuit_open_until(s,d)` | volatile long (unix ms) | 0 | 熔断结束时间戳；0 = 未熔断（需 volatile：effectiveScore 在锁外读取）|

- `srtt` 越低 → 连接越快
- `rttvar` 越低 → 连接越稳定
- `n_tried == 0` → 该服务器从未尝试过此目标，触发探索优先

> **⚠️ 线程安全**：`srtt`、`rttvar`、`consecutive_failures`、`circuit_open_until` 的**写操作**均在同一 `synchronized(RouteQuality)` 块内完成，保证原子性。`srtt`、`rttvar`、`circuit_open_until` 声明为 `volatile`，评分阶段可在锁外直接读取——对概率性选择算法，极低概率的瞬时不一致是可接受的。`consecutive_failures` 仅在锁内读写，无需 `volatile`。

---

## 三、参数

| 参数 | 符号 | 默认值 | 说明 |
|------|------|--------|------|
| 均值平滑系数 | `α` | 1/4 | 比 TCP 的 1/8 更快，适应代理场景的快速变化 |
| 方差平滑系数 | `β` | 1/4 | 与 TCP 相同 |
| 方差权重 | `k` | 4 | 与 TCP 相同，分值中方差的惩罚倍数 |
| 初始平滑延迟 | `INITIAL_SRTT` | `timeout_ms / 2` | 中性起点，不偏好也不排斥 |
| 初始方差 | `INITIAL_RTTVAR` | `0`（首次成功样本后特殊初始化）| 初始为 0；若首个样本为成功，则设为 `sample/2`；若首个样本为失败，则走普通 EWMA |
| 失败惩罚值 | `PENALTY` | `2 × timeout_ms` | 任何失败的虚拟延迟样本 |
| 熔断触发阈值 | `CIRCUIT_OPEN_THRESHOLD` | 5 | 连续失败多少次后触发熔断 |
| 熔断基础冷却 | `CIRCUIT_BASE_MS` | 30000ms（30s）| 首次熔断的冷却时长，也是指数退避的基数 |
| 熔断最长冷却 | `CIRCUIT_MAX_MS` | 600000ms（10min）| 指数退避上限，防止长期无法探测恢复 |

> **参数示例**（timeout = 10000ms）：
> `INITIAL_SRTT` = 5000ms，`INITIAL_RTTVAR` = 0（首次**成功**后为 `sample/2`），`PENALTY` = 20000ms
>
> **INITIAL_RTTVAR 设计原因**：若设为 `INITIAL_SRTT/2`（= 2500ms），则初始 score = `1/(5000 + 4×2500)` = `1/15000`；而首次成功后（假设 elapsed=100ms）srtt 收敛至 ~3825ms，rttvar 升至 ~1919ms，score = `1/11501`——score **变好**了，这是违反直觉的（第一次成功理应降低不确定性、分值提高）。设 `INITIAL_RTTVAR=0` 后，初始 score = `1/5000`，首次**成功**样本后 rttvar 收敛至 `sample/2`，分值单调向真实水平靠拢。

---

## 四、更新函数

### 4.1 `onSuccess(s, d, elapsed_ms)` — 连接成功

`elapsed_ms` 为从发起代理连接到握手完成的实测时间。

以下更新在 `synchronized(RouteQuality)` 块内执行：

```
n_tried(s,d) ← n_tried(s,d) + 1
sample       ← elapsed_ms

if consecutive_failures(s,d) >= CIRCUIT_OPEN_THRESHOLD:
    // 熔断恢复后的首次成功：历史 EWMA 值反映的是不可达期间的状态，已无参考价值。
    // 重新初始化，让 srtt 立刻收敛到实测值，避免因分值过低而陷入慢恢复循环。
    srtt(s,d)   ← sample
    rttvar(s,d) ← sample / 2
else if n_tried(s,d) == 1:
    // 首个样本且为成功：参照 RFC 6298 § 2.2 特殊初始化，srtt/rttvar 直接收敛至实测值
    // 若首个样本为失败（n_tried 已在 onFailure 中递增至 1），此处 n_tried == 2，不进入本分支
    srtt(s,d)   ← sample
    rttvar(s,d) ← sample / 2
else:
    delta        ← sample - srtt(s,d)
    srtt(s,d)    ← srtt(s,d)   + α × delta
    rttvar(s,d)  ← rttvar(s,d) + β × (|delta| - rttvar(s,d))

// 清零连续失败计数，关闭熔断
consecutive_failures(s,d) ← 0
circuit_open_until(s,d)   ← 0
```

### 4.2 `onFailure(s, d)` — 连接失败（任意原因）

以下更新在 `synchronized(RouteQuality)` 块内执行：

```
n_tried(s,d) ← n_tried(s,d) + 1
sample       ← PENALTY                    // 统一映射，与失败原因无关

// 失败不做首次特殊处理：PENALTY 已是固定惩罚值，直接参与 EWMA 即可
delta        ← sample - srtt(s,d)
srtt(s,d)    ← srtt(s,d)   + α × delta
rttvar(s,d)  ← rttvar(s,d) + β × (|delta| - rttvar(s,d))

// 熔断计数递增；超阈值后按指数退避设置冷却时间
consecutive_failures(s,d) ← consecutive_failures(s,d) + 1
if consecutive_failures(s,d) >= CIRCUIT_OPEN_THRESHOLD:
    excess  ← consecutive_failures(s,d) - CIRCUIT_OPEN_THRESHOLD
    backoff ← min(CIRCUIT_BASE_MS × 2^excess, CIRCUIT_MAX_MS)
    circuit_open_until(s,d) ← currentTimeMillis() + backoff
```

### 4.3 熔断机制

#### 设计动机

EWMA 收敛使"持续失败"的 (server, dest) 分值趋近但**永不等于 0**，比例抽样仍会以极低概率将请求路由到该对。对**暂时性故障**这是正确行为（复活机制）；但对**永久不可达**场景，每次被选中必然触发 `timeout_ms`（默认 10 秒）的等待再失败，造成用户可感知的慢请求。

熔断机制在连续失败超过阈值时将该对从选择池**完全排除**（`effectiveScore = 0`），并通过指数退避冷却自动探测恢复，兼顾"避免无效超时等待"与"允许真正恢复后重新参与"两个目标。

#### 状态转移

```
                 consecutive_failures >= CIRCUIT_OPEN_THRESHOLD
正常（未熔断）  ──────────────────────────────────────────────►  熔断中
      ▲                                                              │
      │  onSuccess（任意一次成功）                  冷却期到期后     │
      │◄──────────────────────────────────────────  自动探测        │
      │                                             失败→退避递增   │
      │                                            （至 CIRCUIT_MAX_MS 封顶）│
      └────────────────────────────────────────────────────────────►（重新熔断）
```

#### 冷却时长（指数退避）

```
excess  = consecutive_failures - CIRCUIT_OPEN_THRESHOLD
backoff = min(CIRCUIT_BASE_MS × 2^excess, CIRCUIT_MAX_MS)
```

| consecutive_failures | excess | backoff（CIRCUIT_BASE_MS=30s, CIRCUIT_MAX_MS=10min）|
|---------------------|--------|------------------------------------------------------|
| 5（首次触发）| 0 | 30s |
| 6 | 1 | 60s |
| 7 | 2 | 120s |
| 8 | 3 | 240s |
| 9 | 4 | 480s |
| ≥10 | ≥5 | 600s（上限）|

#### 冷却期到期后的探测

冷却期到期（`circuit_open_until ≤ currentTimeMillis()`）时，`effectiveScore` 恢复正常计算，该 (server, dest) 重新参与比例抽样获得一次探测机会：

- **探测成功** → `onSuccess()` 检测到 `consecutive_failures >= CIRCUIT_OPEN_THRESHOLD`，触发重新初始化：`srtt ← sample`，`rttvar ← sample/2`，随后清零所有熔断状态。srtt 立刻收敛到实测值，服务器分值迅速恢复，避免因历史高 srtt 导致的慢恢复循环。
- **探测失败** → `onFailure()` 再次递增 `consecutive_failures`，触发下一轮退避（冷却时长保持上限或继续翻倍）

> **设计说明**：冷却期结束后不主动发送探测包，而是等待自然流量触发。这避免了主动探测带来的额外连接开销，并与现有的两阶段选择算法完全兼容——探索期（P_EXPLORE）不涉及熔断中的服务器（因为 `effectiveScore=0` 已将其排除在外）。

---

## 五、选择分值函数

```
score(s, d) = 1 / (srtt(s,d) + k × rttvar(s,d))
```

分值越高，服务器越优先。同时惩罚高延迟（srtt 大）和高抖动（rttvar 大）。

**典型分值示例**（k=4, timeout=10000ms, PENALTY=20000ms）：

| 场景 | srtt | rttvar | score | 相对权重 |
|------|------|--------|-------|---------|
| 优质稳定 | 50ms | 5ms | 1/70 ≈ 0.01429 | **~91%** |
| 中等偶抖 | 500ms | 100ms | 1/900 ≈ 0.00111 | ~7.1% |
| 未尝试（初始）| 5000ms | 0ms | 1/5000 ≈ 0.00020 | ~1.3% |
| 持续失败 | 18000ms | 3000ms | 1/30000 ≈ 0.00003 | ~0.2% |

> 未尝试服务器（`INITIAL_RTTVAR=0`）的分值高于持续失败服务器，低于有实测记录的优质服务器，这正是探索期（P_EXPLORE）需要额外干预的原因——让未知服务器有机会积累数据。

---

## 六、收敛性分析

### 6.1 稳态 SRTT 推导

设某 `(s, d)` 对的长期成功率为 `p`，成功时实测延迟稳定为 `L`（ms）。

EWMA 的稳态期望值（`E[srtt] = SRTT_eq`）满足期望增量为零：

```
E[Δsrtt] = p × α × (L - SRTT_eq) + (1-p) × α × (PENALTY - SRTT_eq) = 0

解得：
SRTT_eq = p × L + (1-p) × PENALTY
```

**物理含义**：稳态 SRTT 是成功延迟与惩罚延迟按成功率加权的平均值——完美诠释了"可达性 + 速度"的双重质量评估。

**几种典型场景**（L=50ms, PENALTY=20000ms）：

| 成功率 p | SRTT_eq | 语义 |
|---------|---------|------|
| 1.00 | 50ms | 完全可达，极速 |
| 0.90 | 2045ms | 高度可达，偶发失败 |
| 0.50 | 10025ms | 接近不可达 |
| 0.00 | 20000ms | 完全不可达（等于 PENALTY）— 实践中熔断机制在第 5 次连续失败时即介入，srtt 不会真正收敛至此稳态 |

### 6.2 超越初始分值所需的最低成功率

服务器需达到什么成功率，才能比"未尝试的初始状态"更受青睐？

初始状态（`INITIAL_RTTVAR=0`）的分值为 `1/INITIAL_SRTT = 1/5000`。

稳态 srtt 满足 `SRTT_eq = p×L + (1-p)×PENALTY`；稳态 rttvar 取决于样本方差，在低成功率时因混入大量 PENALTY 样本而偏高。以 srtt 为主要判断依据（忽略稳态 rttvar 的影响，偏乐观估计）：

```
SRTT_eq < INITIAL_SRTT
p × L + (1-p) × PENALTY < timeout / 2

以 L=50ms, PENALTY=20000ms, timeout=10000ms 代入：
p > (PENALTY - INITIAL_SRTT) / (PENALTY - L)
  = (20000 - 5000) / (20000 - 50)
  ≈ 75.2%
```

计入稳态 rttvar 的影响后实际阈值会更高（稳态 rttvar > 0 使分值更低，更难超过初始分值）。这使得探索期（P_EXPLORE）具有合理性：新服务器应当被优先探索，直到积累足够数据、rttvar 收敛至真实水平后再参与公平竞争。

### 6.3 三种典型演化轨迹（α = 1/4）

**轨迹 A：优质服务器（p=1.0, L=50ms）**

```
srtt (ms)
5000 │ ← 初始（未尝试）
     │
  50 ●════════════  ← 第 1 次成功即触发首次特殊初始化，srtt 直接 = 50ms
     t0 t1 t2 t3  →

rttvar (ms)
  25 ●╲ ← 首次初始化 rttvar = L/2 = 25ms
  19  ╲
  14   ╲
   5    ╲╲
   0     ════════  ← 后续 delta≈0，rttvar 以 β=1/4 衰减
     t1 t2 t3 →
```

- **第 1 次成功**：首次特殊初始化，`srtt ← 50ms`，`rttvar ← 25ms`——立即收敛到真实延迟
- 后续每次成功（sample=50ms）：`delta = 0`，`srtt` 不变；`rttvar` 按 `rttvar × (3/4)` 衰减
  - 公式：`rttvar_k = 25 × (3/4)^(k-1)`（k 为第 k 次成功）
  - 约 **10 次成功**后 `rttvar < 2ms`，score 趋近 `1/50 = 0.02`
- 与旧 EWMA（无特殊初始化）的差异：旧算法需约 20 次才能让 srtt 从 5000ms 收敛至 50ms；新算法 **1 次即到位**，大幅加速冷启动阶段

**轨迹 B：中等服务器（p=0.7, L=200ms）**

```
SRTT_eq = 0.7×200 + 0.3×20000 = 140 + 6000 = 6140ms
```

- 稳态高于初始 SRTT（5000ms），但方差可能较低 → score 中等偏低
- 分值低于优质服务器，但高于"持续失败"服务器

**轨迹 C：不可达服务器（p=0.0）**

```
srtt (ms)
 5000 ╱‾ ← 从初始开始上升
10000  ╱
18000   ══════  ← 趋近 PENALTY（20000ms）
        t0 t1 t2  →
```

- 每次失败：`srtt_new = 3/4 × srtt + 1/4 × 20000`
- 约 **10 次失败**后 srtt 达到 PENALTY 的 95%
- **但在第 5 次连续失败时即触发熔断**（`CIRCUIT_OPEN_THRESHOLD=5`）：score=0，彻底排出选择池
- 熔断冷却期到期后自动探测：成功则触发重新初始化，1 次即快速恢复（见 6.4）

### 6.4 服务器恢复速度

恢复速度取决于是否触发了熔断：

**场景一：触发熔断（连续失败 ≥ `CIRCUIT_OPEN_THRESHOLD`）**

冷却期到期后首次探测成功，`onSuccess` 检测到 `consecutive_failures >= CIRCUIT_OPEN_THRESHOLD`，触发重新初始化：

```
srtt   ← sample（实测延迟，如 50ms）
rttvar ← sample / 2
```

**1 次成功即完全恢复**，srtt 立刻收敛至实测值，无需多轮 EWMA 积累。

**场景二：未触发熔断（失败中混有成功，consecutive_failures 未达阈值）**

走普通 EWMA。以 srtt 升至 18000ms 后恢复（L=50ms）为例：

```
srtt_k = 50 + (18000 - 50) × (3/4)^k < 1000
(3/4)^k < 950 / 17950 ≈ 0.053
k > log(0.053) / log(0.75) ≈ 10.3
```

约 **11 次成功**才能将 srtt 降回 1000ms 以内。这依赖选择算法通过比例抽样给低分服务器分配足够的自然流量。

---

## 七、数值追踪示例

3 台服务器 A（稳定优质）/ B（中等偶失败）/ C（偶发失败后恢复），目标 `example.com:443`。
参数：α=1/4, INITIAL_SRTT=5000ms, INITIAL_RTTVAR=0, PENALTY=20000ms, timeout=10000ms。

> **⚠️ 说明**：本示例中 C 仅失败 2 次（consecutive_failures=2，未达到 CIRCUIT_OPEN_THRESHOLD=5），走**普通 EWMA 恢复路径**，演示 EWMA 动态；熔断触发及 1 次恢复场景详见 §6.3 轨迹 C 和 §6.4 场景一。

| 轮次 | 事件 | A srtt/rttvar | B srtt/rttvar | C srtt/rttvar |
|------|------|----------------|----------------|----------------|
| 0 | 初始 | 5000 / 0 | 5000 / 0 | 5000 / 0 |
| 1 | A成功 80ms ★ | **80 / 40** | 5000 / 0 | 5000 / 0 |
| 2 | B成功 200ms ★ | 80 / 40 | **200 / 100** | 5000 / 0 |
| 3 | C失败 | 80 / 40 | 200 / 100 | **8750 / 3750** |
| 4 | A成功 80ms | **80 / 30** | 200 / 100 | 8750 / 3750 |
| 5 | C失败 | 80 / 30 | 200 / 100 | **11563 / 5625** |
| 6 | B失败 | 80 / 30 | **5150 / 5025** | 11563 / 5625 |
| 7 | B成功 200ms | 80 / 30 | **3913 / 5006** | 11563 / 5625 |
| 8 | A成功 90ms | **83 / 25** | 3913 / 5006 | 11563 / 5625 |
| 9 | C成功 150ms（复活）| 83 / 25 | 3913 / 5006 | **8710 / 7072** |
| 10 | C成功 100ms | 83 / 25 | 3913 / 5006 | **6558 / 7457** |

> ★ 标注的轮次触发**首次成功样本**特殊初始化：`srtt ← sample`，`rttvar ← sample/2`，直接收敛到实测值。若首个样本为失败（如 C 的第 3 轮），则走普通 EWMA，不触发特殊初始化。

**对应 score 变化（轮次 10）**：

| 服务器 | srtt | rttvar | score | 流量占比 |
|--------|------|--------|-------|---------|
| A | 83ms | 25ms | 1/183 ≈ 0.005464 | **~98.7%** |
| B | 3913ms | 5006ms | 1/23937 ≈ 0.000042 | ~0.75% |
| C | 6558ms | 7457ms | 1/36386 ≈ 0.000028 | ~0.50% |

> 首次特殊初始化使 A 在第 1 轮即收敛至 srtt=80ms（旧算法需约 20 轮）。B 虽然首次成功后也快速收敛，但一次失败（轮次 6）将 srtt 大幅拉高，score 落后于 A 约 130 倍。C 经 2 次成功后 srtt 已从 11563ms 降至 6558ms，但前期失败累积的高 rttvar（7457ms）使 score 仍低于 B。

---

## 八、测量点说明

`elapsed_ms` 应测量**代理握手完成时间**，而非 TCP 建立时间：

```
时间轴:
  发起连接 ─────── TCP握手 ─────── 代理握手（WebSocket/SOCKS5）─── 可传输数据
  ↑ startTime                                                       ↑ elapsed = now - startTime
```

在 `RouteQualityHandler` 中，通过拦截 `AbstractProxyHandler` 触发的握手事件采集：

```java
// connect() 中记录起点
startTime = System.currentTimeMillis();

// userEventTriggered() 中采集
if (evt instanceof HandshakeSuccessEvent) {
    long elapsed = System.currentTimeMillis() - startTime;
    qualityTable.onSuccess(serverName, destination, elapsed);
} else if (evt instanceof HandshakeFailureEvent) {
    qualityTable.onFailure(serverName, destination);  // 不传 elapsed，统一映射为 PENALTY
}
```

---

## 九、参数调优指南

| 调优目标 | 建议 |
|----------|------|
| 加快对网络变化的响应 | 提高 α（如 1/2），更激进地跟随最新样本 |
| 对偶发失败更宽容 | 提高 PENALTY（如 3×timeout），单次失败影响更小 |
| 加大对抖动的惩罚 | 提高 k（如 6~8），让稳定性更受重视 |
| 加速坏服务器复活 | 配合选择算法给低分服务器更多机会，或降低 α 使历史衰减更快 |
| 降低初始不确定性惩罚 | 当前 INITIAL_RTTVAR=0 + 首次**成功**样本特殊初始化已是最优起点；若需进一步调整，可修改首次初始化的 rttvar 系数（如 `sample/4` 代替 `sample/2`）|
| 减少永久不可达的超时等待 | 降低 `CIRCUIT_OPEN_THRESHOLD`（如 3），更快触发熔断；代价是偶发故障也可能触发熔断，注意所有服务器同时熔断时会直接返回不可达错误 |
| 延长对不可达服务器的观察期 | 提高 `CIRCUIT_OPEN_THRESHOLD`（如 10），允许更多失败后才熔断；适合网络波动频繁的环境 |
| 加快熔断恢复探测频率 | 降低 `CIRCUIT_BASE_MS`（如 10s）或 `CIRCUIT_MAX_MS`（如 5min），适合目标地址可达性变化频繁的场景 |
