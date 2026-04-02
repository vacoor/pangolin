# Linux 内核 TCP 服务端 RST 处理路径梳理

> 内核版本参考：Linux 5.x / 6.x  
> 核心源文件：`net/ipv4/tcp.c`、`net/ipv4/tcp_ipv4.c`、`net/ipv4/tcp_input.c`、`net/ipv4/tcp_output.c`、`net/ipv4/tcp_minisocks.c`  
> **RFC 说明**：RFC 9293（2022-08）已正式废止 RFC 793，本文以 RFC 9293 为准；RFC 793 仅在需要对照旧描述时标注。

---

## 一、RST 报文的两大类型

| 类型 | 描述 | 发起方向 |
|------|------|----------|
| **主动 RST**（Active RST） | 本端主动发起，通常是本地操作或状态异常触发 | 本端 → 对端 |
| **被动 RST**（Passive RST / RST-in-response） | 收到对端报文后，作为响应发出 | 本端 → 对端（响应触发） |

---

## 二、主动发送 RST 的场景

### 场景 A：`close()` 时接收缓冲区存在未读数据

**路径：**
```
tcp_close()                          [net/ipv4/tcp.c]
  └─ sk->sk_receive_queue 非空
       └─ tcp_send_active_reset()    [net/ipv4/tcp_output.c]
            └─ tcp_transmit_skb()
```

**行为：** 内核检测到 `sk->sk_receive_queue` 非空，意味着对端发来的数据未被应用层读取就关闭了连接，内核认为此连接是异常终止，直接发 RST 而非 FIN。

**依据：** RFC 9293 §3.6.1 —— "If the user closes the connection while there is data in the receive queue, the TCP endpoint MUST send a RST segment to abort the connection."  
（RFC 793 §3.4 的表述较模糊，RFC 9293 将此行为升级为 MUST 并明确了触发条件。）

**内核判断条件（tcp_close 中）：**
```c
/* tcp.c: tcp_close() */
if (data_was_unread) {
    /* Unread data was present, abort the connection. */
    tcp_send_active_reset(sk, GFP_KERNEL);
}
```

---

### 场景 B：`SO_LINGER`（`l_linger = 0`）关闭连接

**路径：**
```
tcp_close()                          [net/ipv4/tcp.c]
  └─ sock_flag(sk, SOCK_LINGER) && !sk->sk_lingertime
       └─ tcp_send_active_reset()    [net/ipv4/tcp_output.c]
            └─ tcp_transmit_skb()
```

**行为：** 应用层设置 `SO_LINGER` 且超时为 0，`close()` 调用后立即发 RST，跳过 FIN/FIN-ACK 四次挥手，连接立即终止。TIME_WAIT 状态也被跳过。

**依据：** POSIX `SO_LINGER` 语义 + RFC 9293 §3.8.3。RFC 9293 将此场景描述为"abortive close"，明确指出实现可以通过发送 RST 代替正常关闭序列来立即终止连接，并丢弃所有待发数据。

**与场景 A 的区别：**
- 场景 A 是内核自动判断（缓冲区未读）触发；
- 场景 B 是应用层显式要求（`setsockopt`）触发；
- 场景 B 即使缓冲区为空也会发 RST。

---

### 场景 C：半关闭状态（`CLOSE_WAIT`）下收到新数据

**路径：**
```
tcp_rcv_state_process()              [net/ipv4/tcp_input.c]
  └─ 状态为 CLOSE_WAIT/CLOSING/LAST_ACK
       └─ 收到非期望的 data segment
            └─ tcp_send_active_reset() 或 tcp_reset()
```

**行为：** 服务端已发出 FIN（或收到对端 FIN 进入 CLOSE_WAIT），此时对端仍发来数据，属于非法序列，内核发 RST 终止。

---

## 三、被动发送 RST 的场景（响应对端报文）

### 场景 D：目标端口无监听（Port Unreachable）

**路径：**
```
tcp_v4_rcv()                         [net/ipv4/tcp_ipv4.c]
  └─ __inet_lookup() 查不到 socket
       └─ tcp_v4_send_reset(NULL, skb) [net/ipv4/tcp_ipv4.c]
            └─ ip_send_unicast_reply()
```

**行为：** 收到一个 SYN 或其他报文，但本机对应端口没有处于 LISTEN 状态的 socket，内核直接回复 RST。

**依据：** RFC 9293 §3.5.2 —— 对 CLOSED 状态的处理规则如下（比 RFC 793 §3.4 更精确）：

> "An incoming segment not containing a RST causes a RST to be sent in response. The acknowledgment and sequence field values are selected to make the reset sequence acceptable to the TCP peer receiving the reset."
>
> - 若来包**无 ACK 位**：回复 `<SEQ=0><ACK=SEG.SEQ+SEG.LEN><CTL=RST,ACK>`
> - 若来包**有 ACK 位**：回复 `<SEQ=SEG.ACK><CTL=RST>`

Linux 内核 `tcp_v4_send_reset()` 的实现即遵循此规则：  
有 ACK → `RST.seq = th->ack_seq`；无 ACK → `RST.seq = 0, RST.ack = th->seq + th->syn + th->fin + skb->len - th->doff*4`

**与场景 A/B 的区别：** 此路径不涉及任何 socket 的生命周期，是纯粹的无状态响应；RST 报文的 seq/ack 从来包的 seq/ack 派生，不依赖本端状态机。

---

### 场景 E：accept 队列满 + `tcp_abort_on_overflow=1`

**路径（三次握手完成后入 accept queue 时）：**
```
tcp_v4_syn_recv_sock() / tcp_check_req()   [net/ipv4/tcp_minisocks.c]
  └─ sk_acceptq_is_full(sk)
       └─ tcp_abort_on_overflow == 1
            └─ tcp_v4_send_reset(NULL, skb) [net/ipv4/tcp_ipv4.c]
```

**行为对比：**

| `tcp_abort_on_overflow` | 行为 |
|------------------------|------|
| `0`（默认） | 静默丢包，客户端会重传 SYN-ACK 的 ACK，等队列腾出空间后再接受 |
| `1` | 发 RST，客户端立即感知连接被拒绝，不会重试 |

**配置方式：**
```bash
sysctl -w net.ipv4.tcp_abort_on_overflow=1
```

**依据：** RFC 9293 对此场景没有强制规定（队列满属于实现层面），属于 Linux 的扩展行为。RFC 9293 §3.9.1 仅规定实现"MAY"在资源不足时拒绝连接，但未指定用 RST 还是静默丢弃。默认 `0`（丢包）更符合"优雅降级"语义；`1`（RST）适合快速失败场景（如负载均衡探活）。

**与场景 D 的区别：** 场景 D 是端口完全没有监听；场景 E 是端口在监听但 accept 队列已满，属于瞬时过载，两者的 RST 发送路径相同（`tcp_v4_send_reset`），但触发条件和上下文不同。

---

### 场景 F：`TIME_WAIT` 状态收到非法报文

**路径：**
```
tcp_v4_rcv()
  └─ __inet_lookup_established() 命中 TIME_WAIT socket
       └─ tcp_timewait_state_process()  [net/ipv4/tcp_minisocks.c]
            └─ 收到 SYN（且不满足 RFC 6191 时间戳复用条件）
                 └─ TCP_TW_RST
                      └─ tcp_v4_send_reset()
```

**行为：** `TIME_WAIT` 期间（2MSL）收到新 SYN，若不满足时间戳检查（`tcp_tw_reuse` / `tcp_tw_recycle`），内核认为这是一个"老连接的重复包"或"旧端口复用企图"，回 RST。

**依据：** RFC 9293 §3.6.1（TIME-WAIT 状态）明确规定：TIME-WAIT 期间收到 SYN，若其序列号在当前窗口之外或时间戳不满足条件，则发送 RST。RFC 9293 还澄清了 RFC 793 中含糊的"the only thing that can arrive in this state is a retransmission"表述，指出新 SYN 也需要通过时间戳检查（RFC 7323）才能复用端口。

**与场景 D 的区别：** 场景 D 的 socket 根本不存在；场景 F 存在 TIME_WAIT socket，是状态机的正常处理分支。

### 场景 F 补充：TIME_WAIT 收到 RST（TIME-WAIT Assassination 防护）

**路径：**
```
tcp_v4_rcv()
  └─ 命中 TIME_WAIT socket
       └─ tcp_timewait_state_process()  [net/ipv4/tcp_minisocks.c]
            └─ 收到 RST
                 └─ tcp_validate_incoming() 校验 seq
                      ├─ seq == RCV.NXT → 接受，销毁 TIME_WAIT（TCP_TW_RST）
                      ├─ seq 在窗口内但 != RCV.NXT → 发挑战 ACK，忽略 RST
                      └─ seq 窗口外 → 静默丢弃
```

**行为：** TIME-WAIT Assassination（TWA）是 RFC 2525 §2.17 记录的一类已知实现缺陷：若实现对 TIME-WAIT 期间收到的 RST 不加校验直接接受，TIME-WAIT 会被提前销毁，导致后续新连接可能受到属于旧连接的延迟报文干扰。

Linux 的防护策略（遵循 RFC 9293 §3.5.3）：

| RST 的 seq | Linux 行为 |
|---|---|
| 精确等于 `RCV.NXT` | 接受，正常结束 TIME-WAIT |
| 在窗口内，但 `!= RCV.NXT` | 发挑战 ACK，RST 被忽略，TIME-WAIT 保持 |
| 窗口外 | 静默丢弃 |

**依据：** RFC 2525 §2.17 将"无条件接受 TIME-WAIT 中的 RST"列为已知 bug；RFC 9293 §3.5.3 将精确序号校验作为防 TWA 的规范要求，明确收紧了 RFC 793 "in the window"的模糊表述。

---

### 场景 G：收到序列号超出窗口的报文

**路径：**
```
tcp_rcv_established() / tcp_rcv_state_process()  [net/ipv4/tcp_input.c]
  └─ tcp_validate_incoming()
       └─ SEQ 不在 [rcv_nxt, rcv_nxt + rcv_wnd) 范围内
            └─ 发送 ACK（挑战 ACK，非 RST）
```

> **注意（RFC 9293 §3.5.3 澄清）：**  
> RFC 793 对此的描述是"if an incoming segment is not acceptable, an acknowledgment should be sent in reply"，但未区分 RST 报文与普通数据报文的处理差异，也未明确窗口校验的精确语义。  
> RFC 9293 §3.5.3 整合了 RFC 5961 的要求，明确规定：
> - 收到 **RST 报文**：sequence number 必须精确等于 `RCV.NXT`（ESTABLISHED 等状态）才能被接受；落在窗口内但不等于 `RCV.NXT` 时，发**挑战 ACK**而非直接接受（防盲注入攻击）；窗口外则静默丢弃。
> - 收到**普通数据报文**（SEQ 超窗）：发 ACK（挑战 ACK），不发 RST。
> 
> 这是对 RFC 793 "in the window" 描述的重要收紧。

---

### 场景 H：半打开连接（Half-Open Connection）检测

**路径：**
```
tcp_rcv_established()                [net/ipv4/tcp_input.c]
  └─ 对端重启，发来的 SYN/数据 seq 不匹配本端状态
       └─ tcp_send_active_reset() 或回 RST
```

**行为：** 本端处于 `ESTABLISHED`，但对端已重启（不知道此连接），发来的报文 seq 不符合本端期望，本端发 RST 通知对端连接已失效。

**依据：** RFC 9293 §3.5.3（Half-Open Connections）。RFC 9293 比 RFC 793 §3.4 更清晰地描述了此场景的处理流程：

> "If the TCP endpoint is in ESTABLISHED state and receives a SYN with a valid timestamp but an unacceptable sequence number, it SHOULD send a challenge ACK. If the SYN arrives with no timestamp or a stale timestamp and an out-of-window sequence number, the TCP endpoint MUST send a RST."

对于对端重启后发来数据（非 SYN）的情况，本端处于 ESTABLISHED，收到的 SEQ 不在窗口内，按 §3.5.3 发挑战 ACK；若对端再发 RST 响应，本端才关闭连接。

---

## 四、汇总对比

| 场景 | 触发条件 | 内核函数 | RST 类型 | 是否可配置 |
|------|----------|----------|----------|------------|
| A. close() + 未读数据 | 接收缓冲区非空 | `tcp_send_active_reset()` | 主动 | 否（内核强制） |
| B. SO_LINGER l=0 | 应用层显式设置 | `tcp_send_active_reset()` | 主动 | 应用层控制 |
| C. 半关闭后收数据 | 状态机非法转换 | `tcp_send_active_reset()` | 主动 | 否 |
| D. 端口无监听 | 无对应 socket | `tcp_v4_send_reset()` | 被动（响应） | 否 |
| E. accept 队列满 | 队列溢出 | `tcp_v4_send_reset()` | 被动（响应） | `tcp_abort_on_overflow` |
| F. TIME_WAIT 收 SYN | 2MSL 内复用 | `tcp_v4_send_reset()` | 被动（响应） | `tcp_tw_reuse` |
| G. 序列号超窗 | SEQ 不在窗口内 | （挑战 ACK，非 RST） | — | RFC 5961 |
| H. 半打开检测 | 对端重启后发包 | `tcp_send_active_reset()` | 主动 | 否 |

---

## 五、核心函数说明

### `tcp_send_active_reset()`（主动 RST）
```c
// net/ipv4/tcp_output.c
void tcp_send_active_reset(struct sock *sk, gfp_t priority)
```
- 构造带 RST 标志的 skb，seq 使用本端当前发送序号
- 用于本端主动终止：场景 A、B、C、H

### `tcp_v4_send_reset()`（响应式 RST）
```c
// net/ipv4/tcp_ipv4.c
static void tcp_v4_send_reset(const struct sock *sk, struct sk_buff *skb)
```
- 从接收包（`skb`）反推 seq/ack，不需要 socket 状态
- 用于无 socket 或 socket 不活跃时回复：场景 D、E、F
- **seq/ack 计算规则（来自 RFC 9293 §3.5.2）：**
  - 来包有 ACK 位：`RST.seq = SEG.ACK`，不带 ACK
  - 来包无 ACK 位：`RST.seq = 0`，`RST.ack = SEG.SEQ + SEG.LEN`，带 ACK 位

---

## 六、相关内核参数

```bash
# accept 队列满时是否发 RST（默认 0=丢包，1=发RST）
net.ipv4.tcp_abort_on_overflow = 0

# TIME_WAIT socket 复用（需配合时间戳）
net.ipv4.tcp_tw_reuse = 1

# SYN backlog 大小（影响场景 E 的触发频率）
net.ipv4.tcp_max_syn_backlog = 1024

# 挑战 ACK 速率限制（场景 G，防 RST 注入攻击）
net.ipv4.tcp_challenge_ack_limit = 1000
```

---

## 七、参考依据

| 文档 | 说明 |
|------|------|
| **RFC 9293** (2022) | **现行 TCP 规范**，废止 RFC 793，澄清了 RST 生成（§3.5.2）、RST 校验（§3.5.3）、半打开连接（§3.5.3）、TIME-WAIT（§3.6.1）等模糊表述 |
| RFC 793 (1981) | 原始 TCP 规范，已废止；部分历史实现仍以此为参照 |
| RFC 5961 (2010) | 防 RST/SYN 盲注入攻击；其核心内容（挑战 ACK、精确窗口校验）已被 RFC 9293 §3.5.3 整合 |
| RFC 7323 (2014) | TCP Extensions for High Performance（时间戳/PAWS），TIME-WAIT 复用依赖其时间戳机制 |
| RFC 6191 (2011) | 利用时间戳减少 TIME-WAIT 状态，被 RFC 7323 引用 |
| `net/ipv4/tcp.c` | `tcp_close()` 主动 RST 逻辑 |
| `net/ipv4/tcp_ipv4.c` | `tcp_v4_send_reset()` 被动 RST |
| `net/ipv4/tcp_output.c` | `tcp_send_active_reset()` |
| `net/ipv4/tcp_minisocks.c` | TIME_WAIT / SYN_RECV 状态处理 |
| `net/ipv4/tcp_input.c` | `tcp_validate_incoming()` 序列号校验 |