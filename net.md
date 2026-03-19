# pangolin-routing TUN 模塊代碼邏輯梳理

> 包路徑：`com.github.pangolin.routing.acceptor.tun`

---

## 整體架構

這是一個跨平台 TUN（虛擬網絡隧道）實現，通過 Netty 框架接入，實現透明代理的核心網絡層。

---

## 入口點：`TunAcceptor`

負責整個 TUN 接入器的生命周期：

1. 根據操作系統選擇對應的 `TunAdapter`（Linux / macOS / Windows）
2. 創建 Netty `TunChannel`，綁定到指定地址
3. 初始化 pipeline：`IpPacketCodec` → `Tcp4DemultiplexHandler`
4. 按路由規則向系統路由表添加路由條目
5. 創建懶加載的 `DnsEngine`，提供 Socket/Datagram 工廠給上游連接使用

---

## 核心抽象層（`adapter/`）

| 類 | 職責 |
|---|---|
| `TunAdapter` | 抽象基類，定義 `read()` / `write()` / `destroy()` |
| `NetworkInterfaceEx` | 抽象接口管理 IP 地址（增刪改查、MTU） |
| `NetworkRoutingTable` | 抽象接口管理系統路由表（增刪查） |
| `InterfaceAddressEx` | 表示一條 IP/前綴長度的地址條目 |

---

## 平台實現

### macOS（Darwin）
- **TunAdapter**: 通過 `ioctl` + Kernel Control Socket 創建 `utun` 接口
- **NetworkInterface**: 使用 `getifaddrs` / `SIOCAIFADDR` / `SIOCDIFADDR` 管理地址
- **RoutingTable**: 通過 `PF_ROUTE` socket 操作 `rt_msghdr` 結構
- **DarwinDns**: 通過 SystemConfiguration 框架管理 DNS，監聽網絡服務變化

### Linux
- **TunAdapter**: 打開 `/dev/net/tun`，設置 `IFF_TUN | IFF_NO_PI`
- **NetworkInterface**: 通過 Netlink socket 管理 IPv4，ioctl 管理 IPv6
- **RoutingTable**: 通過 Netlink `RTM_NEWROUTE` / `RTM_DELROUTE` 操作

### Windows
- **TunAdapter**: 基於 [Wintun](https://www.wintun.net/) 驅動，通過 Session 讀寫包
- **NetworkInterface**: 使用 IP Helper API（`IpHlpApi.dll`）管理地址和 DNS
- **RoutingTable**: 同樣基於 IP Helper API

> 所有平台系統調用均通過 **JNA（Java Native Access）** 包裝，各平台的 JNA 定義放在對應子包的 `jna/` 目錄下。

---

## Netty Channel 層（`channel/`）

```
TunChannel（extends AbstractChannel）
  ├─ TunAddress          # 本地地址（接口名 + IP 綁定）
  ├─ TunChannelConfig    # 配置項（MTU、Wintun UUID 等）
  └─ 讀寫循環            # 通過 DefaultEventLoop 驅動 TunAdapter.read/write
```

**數據流：**
```
TunAdapter.read() → ByteBuf → IpPacketCodec → IpPacket → Tcp4DemultiplexHandler
```

---

## 包處理層（`handler/`）

- **`IpPacketCodec`**: ByteBuf ↔ IpPacket（使用 pcap4j 庫）
- **`Tcp4DemultiplexHandler`**: 對 IPv4 TCP 流量做連接復用，結合 FakeDNS 解析目標地址
- **`Udp4PacketHandler`**: 處理 UDP 包轉發

---

## FakeDNS 子系統（`fakedns/`）

用於實現透明 DNS 劫持：

```
FakeDnsAcceptor
  └─ FakeDnsServer（UDP :53）
       └─ DnsEngine（接口）
            ├─ FakeDns（分配/查詢虛假 IP 映射）
            ├─ FakeNameService（虛假 IP 池管理）
            └─ DnsOverHttpServer（DoH 支持）
```

**工作原理：**
1. 攔截 DNS 查詢，為匹配路由規則的域名分配一個虛假 IP（來自指定子網）
2. TCP 連接到虛假 IP 時，通過 `Tcp4DemultiplexHandler` 反查真實域名
3. 再通過上游代理連接真實目標

---

## 整體數據流

```
系統發出 TCP 包
    ↓
TUN 設備捕獲（TunAdapter.read）
    ↓
Netty Pipeline
    IpPacketCodec（解碼為 IpPacket）
    Tcp4DemultiplexHandler
        ├─ 查詢 FakeDns（虛假 IP → 真實域名）
        └─ 通過 SocketChannelFactory 連接上游代理
    ↓
上游響應經 TunAdapter.write 寫回 TUN 設備
    ↓
系統收到響應
```

---

## 設計亮點

- **平台抽象 + JNA**：三平台統一接口，底層通過 JNA 直調系統 API，零依賴原生庫
- **Netty 深度集成**：TUN 設備被封裝成標準 Netty Channel，可復用 Netty 的 pipeline 機制
- **懶加載 DNS**：DnsEngine 用代理模式延遲初始化，避免啟動時阻塞
- **動態上游選擇**：Socket/Datagram 工廠根據路由規則動態決定使用哪個上游
