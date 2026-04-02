# TCP RST 包生成速查表 (遵循 RFC 9293)

## 核心原则（RFC 9293 统一规则）：
1.  **响应不带 ACK 的段 (ACK=0)**：发送 **`RST, ACK=1`**，其序列号为 0，确认号为 `SEG.SEQ + SEG.LEN`。
2.  **响应带 ACK 的段 (ACK=1)**：发送 **`RST, ACK=0`**，其序列号为接收段中的 `SEG.ACK`。
3.  **特殊状态 SYN-SENT** 的处理规则略有不同。

## 速查表

| 序号 | 当前状态 | 接收到的段特征 (触发条件) | RFC 9293 定义的 RST 段格式 | RST | ACK | SEQ | ACK_NUM | 依据与说明 |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| 1 | **CLOSED** (无TCB) | 任何非RST段，且 **`ACK=0`** (如SYN) | `<SEQ=0><ACK=SEG.SEQ+SEG.LEN><CTL=RST,ACK>` | 1 | 1 | 0 | `SEG.SEQ + SEG.LEN` | 3.10.7.2 节。响应不带 ACK 的段，必须设置 ACK 位。 |
| 2 | **CLOSED** (无TCB) | 任何非RST段，且 **`ACK=1`** (如数据/FIN) | `<SEQ=SEG.ACK><CTL=RST>` | 1 | 0 | `SEG.ACK` | (无) | 3.10.7.2 节。响应带 ACK 的段，不设置 ACK 位，序列号为 SEG.ACK。 |
| 3 | **LISTEN** | 任何非RST段，且 **`ACK=1`** (如一个纯ACK) | `<SEQ=SEG.ACK><CTL=RST>` | 1 | 0 | `SEG.ACK` | (无) | 3.10.7.3 节。LISTEN 状态视同同步状态处理，遵循响应 ACK=1 段的通用规则。 |
| 4 | **LISTEN** | 任何非RST段，且 **`ACK=0`** (如SYN)，且连接需被拒绝时 *(注：LISTEN 正常处理 SYN 会进入 SYN-RECEIVED，不发 RST；此行为对称性原则的推论，RFC 9293 无直接明文)* | `<SEQ=0><ACK=SEG.SEQ+SEG.LEN><CTL=RST,ACK>` | 1 | 1 | 0 | `SEG.SEQ + SEG.LEN` | 3.10.7.3 节对称性原则推论。LISTEN 状态视同同步状态，响应 ACK=0 的段，必须设置 ACK 位。**这修正了 RFC 793 中可能的笔误**。 |
| 5 | **SYN-SENT** | 任何非RST段，且 **`ACK=1`**，但其 `SEG.ACK` 不可接受 (如 `SEG.ACK =< ISS` 或 `SEG.ACK > SND.NXT`) | `<SEQ=SEG.ACK><CTL=RST>` | 1 | 0 | `SEG.ACK` | (无) | 3.10.7.3 节。SYN-SENT 状态，响应带 ACK 的段，不设置 ACK 位。 |
| 6 | **SYN-SENT** | 任何非RST段，且 **`ACK=0`** (如SYN)，但连接条件不满足需拒绝 *(注：RFC 793 中的安全级别/优先级触发已在 RFC 9293 中移除)* | `<SEQ=0><ACK=SEG.SEQ+SEG.LEN><CTL=RST,ACK>` | 1 | 1 | 0 | `SEG.SEQ + SEG.LEN` | 3.10.7.3 节。SYN-SENT 状态，响应不带 ACK 的段，必须设置 ACK 位。 |
| 7 | **SYN-RECEIVED** | 任何非RST段，其 `ACK` 字段确认了尚未发送的数据 (不可接受 ACK)。(通常指对端ACK号未确认本端的SYN) | `<SEQ=SEG.ACK><CTL=RST>` | 1 | 0 | `SEG.ACK` | (无) | 3.10.4 节。接收段有 ACK 字段，遵循对称性原则：不设置 ACK 位，序列号为 SEG.ACK。RFC 9293 与 RFC 793 均有相同明文规定。 |
| 8 | **所有同步状态**<br>(ESTABLISHED, FIN-WAIT-1, CLOSE-WAIT, CLOSING, LAST-ACK, TIME-WAIT) | 任何需要发送 RST 的非法段 (如在已建立连接中收到 SYN)。*(注：RFC 793 中基于安全级别不匹配的触发已在 RFC 9293 中移除)* | 如果接收段 **`ACK=1`**：`<SEQ=SEG.ACK><CTL=RST>`<br>如果接收段 **`ACK=0`**：`<SEQ=0><ACK=SEG.SEQ+SEG.LEN><CTL=RST,ACK>` | 1 | **同左**<br>ACK=1: 0<br>ACK=0: 1 | ACK=1: `SEG.ACK`<br>ACK=0: 0 | ACK=0 时: `SEG.SEQ+SEG.LEN` | 3.10.7.1 节与 3.10.7.3 节。同步状态下，RST 必须遵守与 CLOSED/LISTEN 状态相同的“对称性”规则。**这修正了 RFC 793 中同步状态 RST 可能不带 ACK 的模糊描述**。 |
| 9 | **所有状态** | 用户主动中止连接 (ABORT) | `<SEQ=SND.NXT><CTL=RST>` | 1 | 0 | `SND.NXT` | (无) | 3.10.7.5 节。这是唯一明确由本地主动发起、且不设置 ACK 位的 RST。 |

## RFC 9293 核心更新与澄清 (与 RFC 793 的主要差异)

1.  **状态统一处理 (3.10.7.3节)**：
    *   **“In all states except SYN-SENT, all reset segments MUST conform to the rules for a connection in the synchronized state.”**
    *   这意味着除了 **SYN-SENT** 状态，**所有其他状态**（包括 **CLOSED, LISTEN, SYN-RECEIVED 和所有同步状态**）的 RST 生成，都必须遵循**同步状态的规则**，即必须符合上述“核心原则”的对称性。

2.  **同步状态的规则 (3.10.7.1节)**：
    *   该节定义了同步状态下 RST 生成的通用规则，其本质就是“对称性原则”：
        *   `(1)` 如果接收段有 ACK 字段，则 RST 的序列号取自 `SEG.ACK`，且 RST 不设置 ACK 位 (`ACK=0`)。
        *   `(2)` 如果接收段没有 ACK 字段，则 RST 序列号为 0，并设置 `ACK=SEG.SEQ+SEG.LEN` 和 `ACK=1`。

3.  **对 LISTEN 状态的明确修正**：
    *   根据第1点，LISTEN 状态必须遵循同步状态规则。因此，对于 `ACK=0` 的 SYN 段，响应的 RST 必须是 `<SEQ=0><ACK=SEG.SEQ+SEG.LEN><CTL=RST,ACK>` (`ACK=1`)，**而不是 RFC 793 中可能暗示的 `<SEQ=SEG.ACK><CTL=RST>` (`ACK=0`)**。这消除了 RFC 793 的模糊性。

4.  **对 SYN-SENT 状态的特殊规定 (3.10.7.3节)**：
    *   **“In the SYN-SENT state, if the incoming segment has an ACK field, the reset MUST have a sequence number of SEG.ACK; if the incoming segment does not have an ACK field, the reset MUST have a sequence number of zero and an ACK field set to SEG.SEQ+SEG.LEN, with the ACK bit set.”**
    *   尽管其描述与通用规则听起来一致，但 RFC 9293 仍将 SYN-SENT 列为例外。在实践中，这通常意味着 SYN-SENT 状态下的处理逻辑**结果**与通用规则相同，但实现时需按该状态的特殊逻辑判断是否发送 RST。

5.  **对“连接不存在”的确认 (3.10.7.2节)**：
    *   完全重申了 RFC 793 的规则，明确分为 `ACK=0` 和 `ACK=1` 两种情况，与上表规则1、2一致。

## 总结
RFC 9293 极大地简化了 RST 生成的决策逻辑。**绝大多数情况只需判断接收段是否有 ACK 位**，然后应用简单的对称规则。唯一的“例外”是 SYN-SENT 状态需要单独判断触发条件，但生成 RST 包的规则本身与通用规则相同。用户主动 ABORT 是唯一主动发送且 `ACK=0` 的 RST。
