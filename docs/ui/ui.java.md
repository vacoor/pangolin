# Pangolin Routing UI 后端 Java 设计文档

## 概述

本文档描述为支持 `ui.md` 所设计的 UI 界面，在 `pangolin-routing` 模块中新增 REST API 后端所需的 Java 代码改动。

**核心设计原则：**
- 沿用现有 Netty HTTP 基础设施（`RuleExporterAcceptor` → `NettyServer` 模式），不引入 Spring MVC
- 配置文件（`conf/default.conf`）作为唯一数据源，读写均操作 INI 文件
- 管理 API 与业务 Acceptor 生命周期解耦，支持热重载
- 最小化现有类改动，新增类优先放入独立包

**涉及改动范围：**
- `pom.xml`：新增 1 个依赖
- `Application.java`：扩展，新增热重载与管理 API 启动逻辑
- `acceptor/extra/RuleExporterAcceptor.java`：由 `ManagementApiAcceptor` 整合替代
- 新增约 20 个类，全部位于新包 `management/` 下

---

## 一、依赖变更

**文件：`pangolin-routing/pom.xml`**

新增 Jackson Databind 用于 JSON 序列化/反序列化：

```xml
<!-- 新增：JSON 序列化，Spring Boot BOM 管理版本（2.0.0 对应 jackson 2.9.x） -->
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
</dependency>
```

> 已有 `spring-boot-autoconfigure` 依赖，Spring Boot BOM 已管理 `jackson-databind` 版本，无需指定 `<version>`。

---

## 二、新增包结构

在 `com.github.pangolin.routing` 下新增 `management` 包：

```
com.github.pangolin.routing.management/
├── ManagementApiAcceptor.java        # 管理 HTTP 服务器（整合并替代 RuleExporterAcceptor）
├── ApiRouter.java                    # HTTP 请求路由分发器
├── config/
│   ├── ConfigManager.java            # INI 读写管理器（线程安全）
│   ├── ConfigModel.java              # 全量配置内存模型（所有 INI 节）
│   ├── ProxyConfig.java              # [Proxy] 单条记录
│   ├── ProxyGroupConfig.java         # [Proxy Group] 单条记录
│   ├── RouteRuleConfig.java          # [Rule] 单条记录
│   ├── PortListenerConfig.java       # [Listen] 单条记录
│   ├── TunInterfaceConfig.java       # [Tun] 单条记录
│   ├── FakeDnsConfig.java            # [Fake DNS] 配置
│   └── ExternalConfig.java          # [External] 单条记录
└── handler/
    ├── AbstractApiHandler.java       # 基类：JSON 读写、路径匹配、错误响应
    ├── ProxyApiHandler.java          # CRUD [Proxy]
    ├── ProxyGroupApiHandler.java     # CRUD [Proxy Group]
    ├── RuleApiHandler.java           # CRUD [Rule]，支持排序
    ├── RuleFileApiHandler.java       # 读写 .list 规则文件
    ├── ListenerApiHandler.java       # CRUD [Listen]
    ├── TunApiHandler.java            # 读写 [Tun] + [Fake DNS]
    ├── ConfigApiHandler.java         # GET 完整配置文本预览
    ├── ReloadApiHandler.java         # POST 保存并重载
    └── StatusApiHandler.java         # GET 上游连通性状态
```

---

## 三、配置模型层（`management/config/`）

### 3.1 ConfigModel — 全量配置内存模型

持有所有 INI 节的内存表示，并负责序列化回 INI 格式。

```java
package com.github.pangolin.routing.management.config;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class ConfigModel {

    private List<ProxyConfig>        proxies     = new ArrayList<>();
    private List<ProxyGroupConfig>   proxyGroups = new ArrayList<>();
    private List<RouteRuleConfig>    rules       = new ArrayList<>();
    private List<PortListenerConfig> listeners   = new ArrayList<>();
    private List<TunInterfaceConfig> tunInterfaces = new ArrayList<>();   // 通常只有一条
    private FakeDnsConfig            fakeDns;
    private List<ExternalConfig>     externals   = new ArrayList<>();

    /**
     * 序列化为 INI 格式字符串，用于写回 default.conf。
     * 节顺序固定：External → Proxy → Proxy Group → Rule → Listen → Tun → Fake DNS
     */
    public String toIni() {
        StringBuilder sb = new StringBuilder();

        if (!externals.isEmpty()) {
            sb.append("[External]\n");
            for (ExternalConfig e : externals) {
                sb.append(e.getName()).append(" = ").append(e.getUrl()).append("\n");
            }
            sb.append("\n");
        }

        if (!proxies.isEmpty()) {
            sb.append("[Proxy]\n");
            for (ProxyConfig p : proxies) {
                sb.append(p.getName()).append(" = ").append(p.toUri()).append("\n");
            }
            sb.append("\n");
        }

        if (!proxyGroups.isEmpty()) {
            sb.append("[Proxy Group]\n");
            for (ProxyGroupConfig g : proxyGroups) {
                sb.append(g.getName()).append(" = ").append(g.toCombinerString()).append("\n");
            }
            sb.append("\n");
        }

        if (!rules.isEmpty()) {
            sb.append("[Rule]\n");
            for (RouteRuleConfig r : rules) {
                sb.append(r.toRuleLine()).append("\n");
            }
            sb.append("\n");
        }

        if (!listeners.isEmpty()) {
            sb.append("[Listen]\n");
            for (PortListenerConfig l : listeners) {
                sb.append(l.getPort()).append(" = ").append(l.toDefinitionString()).append("\n");
            }
            sb.append("\n");
        }

        if (!tunInterfaces.isEmpty()) {
            sb.append("[Tun]\n");
            for (TunInterfaceConfig t : tunInterfaces) {
                sb.append(t.getAddress()).append(" = ").append(t.getUpstream()).append("\n");
            }
            sb.append("\n");
        }

        if (fakeDns != null && fakeDns.isEnabled()) {
            sb.append("[Fake DNS]\n");
            if (fakeDns.getInet4() != null) {
                sb.append("INET4 = ").append(fakeDns.getInet4()).append("\n");
            }
            if (fakeDns.getInet6() != null) {
                sb.append("INET6 = ").append(fakeDns.getInet6()).append("\n");
            }
            sb.append("LEASE-TIME = ").append(fakeDns.getLeaseTime()).append("\n");
            sb.append("\n");
        }

        return sb.toString();
    }
}
```

### 3.2 ProxyConfig

对应 `[Proxy]` 节的单条记录，支持所有协议。

```java
@Data
public class ProxyConfig {
    private String name;
    /** ws, wss, ssh, http, https, ss, trojan */
    private String scheme;
    private String host;
    private int    port;
    // ws/wss
    private String path;
    private String alias;      // URI fragment (#后内容)
    // ssh
    private String username;
    private String password;
    // ss/trojan
    private String cipher;     // ss 加密方式

    /**
     * 从 URI 字符串解析为 ProxyConfig。
     * 示例：ws://127.0.0.1:2345/tunnel/path#TUNNEL
     *       ssh://user:pass@1.2.3.4:22
     *       http://10.0.0.1:808
     */
    public static ProxyConfig fromUri(String name, String uri) {
        ProxyConfig cfg = new ProxyConfig();
        cfg.setName(name);
        // 解析 URI 各部分（使用 java.net.URI + 正则处理 fragment）
        try {
            // 处理 fragment（URI 类会正确解析 #后内容）
            java.net.URI u = new java.net.URI(uri);
            cfg.setScheme(u.getScheme());
            cfg.setHost(u.getHost());
            cfg.setPort(u.getPort());
            cfg.setPath(u.getPath());
            cfg.setAlias(u.getFragment());
            String userInfo = u.getUserInfo();
            if (userInfo != null) {
                int colon = userInfo.indexOf(':');
                if (colon >= 0) {
                    cfg.setUsername(userInfo.substring(0, colon));
                    cfg.setPassword(userInfo.substring(colon + 1));
                } else {
                    cfg.setUsername(userInfo);
                }
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid proxy URI: " + uri, e);
        }
        return cfg;
    }

    /**
     * 序列化回 URI 字符串，写入 INI。
     */
    public String toUri() {
        StringBuilder sb = new StringBuilder(scheme).append("://");
        if (username != null && !username.isEmpty()) {
            sb.append(username);
            if (password != null && !password.isEmpty()) {
                sb.append(":").append(password);
            }
            sb.append("@");
        }
        sb.append(host).append(":").append(port);
        if (path != null && !path.isEmpty()) {
            sb.append(path);
        }
        if (alias != null && !alias.isEmpty()) {
            sb.append("#").append(alias);
        }
        return sb.toString();
    }
}
```

### 3.3 ProxyGroupConfig

```java
@Data
public class ProxyGroupConfig {
    private String       name;
    /** chain | select | fallback */
    private String       combiner;
    /** 有序成员列表（引用 ProxyConfig.name 或其他 ProxyGroupConfig.name） */
    private List<String> members = new ArrayList<>();

    /**
     * 序列化为 INI 值字符串。
     * 示例：chain,VPN-REMOTE,VPN-INNER-PROXY
     */
    public String toCombinerString() {
        return combiner + "," + String.join(",", members);
    }
}
```

### 3.4 RouteRuleConfig

```java
@Data
public class RouteRuleConfig {
    /**
     * DOMAIN-SUFFIX | DOMAIN | DOMAIN-KEYWORD |
     * IP-CIDR | IP-CIDR6 | GEOIP | RULE-SET | MATCH
     */
    private String type;
    /** 匹配参数（MATCH 时为 null） */
    private String value;
    /** 目标代理名称 */
    private String upstream;
    /** 对于 RULE-SET：区分本地文件还是外部（通过 [External] 键名引用） */
    private boolean external;

    /**
     * 序列化为 INI 规则行。
     * 示例：
     *   RULE-SET,rule.d/video.list,国外流量
     *   DOMAIN-SUFFIX,github.com,DIRECT
     *   MATCH,DEFAULT
     */
    public String toRuleLine() {
        if ("MATCH".equals(type)) {
            return "MATCH," + upstream;
        }
        return type + "," + value + "," + upstream;
    }
}
```

### 3.5 PortListenerConfig

```java
@Data
public class PortListenerConfig {
    private int          port;
    private String       upstream;  // 默认代理，通常为 "DEFAULT"
    private List<String> protocols; // ["SOCKS4","SOCKS5","HTTP"] 的子集

    /**
     * 序列化为 INI 值字符串。
     * 示例：DEFAULT, SOCKS4, SOCKS5, HTTP
     */
    public String toDefinitionString() {
        return upstream + ", " + String.join(", ", protocols);
    }
}
```

### 3.6 TunInterfaceConfig / FakeDnsConfig

```java
@Data
public class TunInterfaceConfig {
    private String address;    // CIDR 格式，如 198.18.0.1/24
    private String upstream;   // 通常为 DEFAULT
}

@Data
public class FakeDnsConfig {
    private boolean enabled = false;
    private String  inet4;          // 如 198.18.0.1/24
    private String  inet6;          // 如 2001:2::/48
    private int     leaseTime = 60; // 秒
}
```

### 3.7 ExternalConfig

```java
@Data
public class ExternalConfig {
    private String name;   // [External] 中的键名
    private String url;    // 订阅 URL
}
```

---

## 四、ConfigManager — INI 读写管理器

负责从 INI 文件解析到 `ConfigModel`，以及将修改后的 `ConfigModel` 写回文件。线程安全。

```java
package com.github.pangolin.routing.management.config;

import com.github.pangolin.routing.context.spi.Ini;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.locks.*;

@Slf4j
public class ConfigManager {

    private final File configFile;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    /** 内存中的当前配置模型 */
    private ConfigModel model;

    public ConfigManager(File configFile) throws IOException {
        this.configFile = configFile;
        this.model = load(configFile);
    }

    // ── 读取 ───────────────────────────────────────────────────────────────

    public ConfigModel getModel() {
        lock.readLock().lock();
        try {
            return model;
        } finally {
            lock.readLock().unlock();
        }
    }

    // ── 保存（写回文件 + 更新内存模型）─────────────────────────────────────

    public void save(ConfigModel newModel) throws IOException {
        lock.writeLock().lock();
        try {
            String content = newModel.toIni();
            Files.write(configFile.toPath(),
                        content.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);
            this.model = newModel;
            log.info("Config saved to {}", configFile.getAbsolutePath());
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ── INI 解析 → ConfigModel ────────────────────────────────────────────

    public static ConfigModel load(File file) throws IOException {
        try (InputStream is = new FileInputStream(file)) {
            return parse(is);
        }
    }

    /**
     * 将 INI 输入流解析为 ConfigModel。
     * 复用现有 {@link Ini} 解析器，仅负责 INI→Model 的映射。
     */
    public static ConfigModel parse(InputStream is) throws IOException {
        Ini ini = new Ini();
        ini.load(is);
        ConfigModel model = new ConfigModel();

        // [External]
        Ini.Section external = ini.getSection("External");
        if (external != null) {
            external.forEach((k, v) -> {
                ExternalConfig e = new ExternalConfig();
                e.setName(k); e.setUrl(v);
                model.getExternals().add(e);
            });
        }

        // [Proxy]
        Ini.Section proxy = ini.getSection("Proxy");
        if (proxy != null) {
            proxy.forEach((k, v) -> model.getProxies().add(ProxyConfig.fromUri(k, v)));
        }

        // [Proxy Group]
        Ini.Section proxyGroup = ini.getSection("Proxy Group");
        if (proxyGroup != null) {
            proxyGroup.forEach((k, v) -> {
                String[] parts = v.split("\\s*,\\s*");
                ProxyGroupConfig g = new ProxyGroupConfig();
                g.setName(k);
                g.setCombiner(parts[0]);
                g.setMembers(new ArrayList<>(Arrays.asList(Arrays.copyOfRange(parts, 1, parts.length))));
                model.getProxyGroups().add(g);
            });
        }

        // [Rule]
        Ini.Section rule = ini.getSection("Rule");
        if (rule != null) {
            // Ini.Section 保持插入顺序（LinkedHashMap），按序迭代
            rule.keySet().forEach(line -> {
                RouteRuleConfig r = parseRuleLine(line);
                if (r != null) model.getRules().add(r);
            });
        }

        // [Listen]
        Ini.Section listen = ini.getSection("Listen");
        if (listen != null) {
            listen.forEach((port, def) -> {
                String[] parts = def.split("\\s*,\\s*");
                PortListenerConfig l = new PortListenerConfig();
                l.setPort(Integer.parseInt(port));
                l.setUpstream(parts[0]);
                l.setProtocols(new ArrayList<>(Arrays.asList(Arrays.copyOfRange(parts, 1, parts.length))));
                model.getListeners().add(l);
            });
        }

        // [Tun]
        Ini.Section tun = ini.getSection("Tun");
        if (tun != null) {
            tun.forEach((addr, upstream) -> {
                TunInterfaceConfig t = new TunInterfaceConfig();
                t.setAddress(addr); t.setUpstream(upstream);
                model.getTunInterfaces().add(t);
            });
        }

        // [Fake DNS]
        Ini.Section fakeDns = ini.getSection("Fake DNS");
        if (fakeDns != null) {
            FakeDnsConfig f = new FakeDnsConfig();
            f.setEnabled(true);
            f.setInet4(fakeDns.get("INET4"));
            f.setInet6(fakeDns.get("INET6"));
            String lt = fakeDns.get("LEASE-TIME");
            if (lt != null) f.setLeaseTime(Integer.parseInt(lt));
            model.setFakeDns(f);
        }

        return model;
    }

    private static RouteRuleConfig parseRuleLine(String line) {
        // [Rule] 节的 key 即为完整规则行，如 "RULE-SET,rule.d/video.list,国外流量"
        String[] parts = line.split("\\s*,\\s*", 3);
        if (parts.length < 2) return null;
        RouteRuleConfig r = new RouteRuleConfig();
        r.setType(parts[0]);
        if ("MATCH".equals(parts[0])) {
            r.setUpstream(parts[1]);
        } else if (parts.length >= 3) {
            r.setValue(parts[1]);
            r.setUpstream(parts[2]);
        } else {
            return null;
        }
        return r;
    }

    public File getConfigFile() {
        return configFile;
    }
}
```

> **注意：** 现有 `Ini.java` 仅用于解析（只读），`ConfigManager` 的写回通过 `ConfigModel.toIni()` 独立生成 INI 文本，完全不修改 `Ini.java`。

---

## 五、Handler 基类

所有 API Handler 均为 Netty `ChannelInboundHandlerAdapter`，通过路径和方法匹配决定是否处理请求，处理完后调用 `ctx.fireChannelRead(msg)` 将未匹配请求传给下一个 Handler（Pipeline 链式处理）。

```java
package com.github.pangolin.routing.management.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@ChannelHandler.Sharable
public abstract class AbstractApiHandler extends ChannelInboundHandlerAdapter {

    protected static final ObjectMapper MAPPER = new ObjectMapper();

    /** 子类声明自己处理的路径前缀，如 "/api/proxies" */
    protected abstract String pathPrefix();

    /** 子类实现具体的业务逻辑 */
    protected abstract void handle(ChannelHandlerContext ctx,
                                   FullHttpRequest req,
                                   String method,
                                   String path) throws Exception;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof FullHttpRequest)) {
            ctx.fireChannelRead(msg);
            return;
        }
        FullHttpRequest req = (FullHttpRequest) msg;
        String path = new QueryStringDecoder(req.uri()).path();

        if (!path.startsWith(pathPrefix())) {
            ctx.fireChannelRead(msg);  // 不处理，传递给下一个 Handler
            return;
        }

        try {
            handle(ctx, req, req.method().name(), path);
        } catch (Exception e) {
            log.error("Handler error for {} {}", req.method(), req.uri(), e);
            writeError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    // ── 响应工具方法 ──────────────────────────────────────────────────────

    protected void writeJson(ChannelHandlerContext ctx, Object data) throws Exception {
        writeJson(ctx, HttpResponseStatus.OK, data);
    }

    protected void writeJson(ChannelHandlerContext ctx,
                             HttpResponseStatus status, Object data) throws Exception {
        Map<String, Object> resp = new HashMap<>();
        resp.put("code", status.code());
        resp.put("data", data);
        byte[] bytes = MAPPER.writeValueAsBytes(resp);

        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status,
                Unpooled.wrappedBuffer(bytes));
        response.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8")
                .set(HttpHeaderNames.CONTENT_LENGTH, bytes.length)
                .set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                .set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET,POST,PUT,DELETE,OPTIONS")
                .set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type");
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    protected void writeError(ChannelHandlerContext ctx,
                              HttpResponseStatus status, String message) throws Exception {
        Map<String, Object> resp = new HashMap<>();
        resp.put("code", status.code());
        resp.put("error", message);
        byte[] bytes = MAPPER.writeValueAsBytes(resp);

        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status,
                Unpooled.wrappedBuffer(bytes));
        response.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8")
                .set(HttpHeaderNames.CONTENT_LENGTH, bytes.length)
                .set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    protected <T> T readBody(FullHttpRequest req, Class<T> clazz) throws Exception {
        String body = req.content().toString(CharsetUtil.UTF_8);
        return MAPPER.readValue(body, clazz);
    }

    /** 从 /api/proxies/MY-PROXY 中提取 MY-PROXY */
    protected String extractPathParam(String path) {
        String prefix = pathPrefix();
        if (path.length() > prefix.length() + 1) {
            return path.substring(prefix.length() + 1);
        }
        return null;
    }
}
```

---

## 六、各 Handler 实现

### 6.1 ProxyApiHandler — 代理服务器 CRUD

```java
@Slf4j
@ChannelHandler.Sharable
public class ProxyApiHandler extends AbstractApiHandler {

    private final ConfigManager configManager;

    public ProxyApiHandler(ConfigManager configManager) {
        this.configManager = configManager;
    }

    @Override protected String pathPrefix() { return "/api/proxies"; }

    @Override
    protected void handle(ChannelHandlerContext ctx, FullHttpRequest req,
                          String method, String path) throws Exception {
        String name = extractPathParam(path);  // null → 列表操作，非null → 单项操作

        switch (method) {
            case "GET":
                // GET /api/proxies → 列表
                writeJson(ctx, configManager.getModel().getProxies());
                break;

            case "POST":
                // POST /api/proxies → 新增
                ProxyConfig newProxy = readBody(req, ProxyConfig.class);
                validateProxyName(newProxy.getName());
                ConfigModel m1 = configManager.getModel();
                checkNameUnique(m1, newProxy.getName());
                m1.getProxies().add(newProxy);
                configManager.save(m1);
                writeJson(ctx, newProxy);
                break;

            case "PUT":
                // PUT /api/proxies/{name} → 更新
                ProxyConfig updated = readBody(req, ProxyConfig.class);
                ConfigModel m2 = configManager.getModel();
                replaceProxy(m2, name, updated);
                configManager.save(m2);
                writeJson(ctx, updated);
                break;

            case "DELETE":
                // DELETE /api/proxies/{name} → 删除（含引用检查）
                ConfigModel m3 = configManager.getModel();
                checkProxyNotReferenced(m3, name);
                m3.getProxies().removeIf(p -> p.getName().equals(name));
                configManager.save(m3);
                writeJson(ctx, null);
                break;

            case "OPTIONS":
                writeJson(ctx, null);
                break;

            default:
                writeError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "Method not allowed");
        }
    }

    private void validateProxyName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_\\-]+")) {
            throw new IllegalArgumentException("Invalid proxy name: " + name);
        }
    }

    private void checkNameUnique(ConfigModel model, String name) {
        boolean exists = model.getProxies().stream().anyMatch(p -> p.getName().equals(name))
                      || model.getProxyGroups().stream().anyMatch(g -> g.getName().equals(name));
        if (exists) throw new IllegalArgumentException("Name already exists: " + name);
    }

    private void replaceProxy(ConfigModel model, String name, ProxyConfig updated) {
        List<ProxyConfig> proxies = model.getProxies();
        for (int i = 0; i < proxies.size(); i++) {
            if (proxies.get(i).getName().equals(name)) {
                proxies.set(i, updated);
                return;
            }
        }
        throw new IllegalArgumentException("Proxy not found: " + name);
    }

    /**
     * 检查代理是否被 ProxyGroup 或 Rule 引用，若有则拒绝删除。
     * 返回引用列表供前端展示。
     */
    private void checkProxyNotReferenced(ConfigModel model, String name) {
        List<String> refs = new ArrayList<>();
        model.getProxyGroups().stream()
             .filter(g -> g.getMembers().contains(name))
             .forEach(g -> refs.add("代理组: " + g.getName()));
        model.getRules().stream()
             .filter(r -> name.equals(r.getUpstream()))
             .forEach(r -> refs.add("规则: " + r.toRuleLine()));
        if (!refs.isEmpty()) {
            throw new IllegalStateException("Proxy is referenced by: " + String.join(", ", refs));
        }
    }
}
```

### 6.2 ProxyGroupApiHandler — 代理组 CRUD

结构与 `ProxyApiHandler` 一致，路径 `/api/proxy-groups`，操作 `ConfigModel.proxyGroups`。

关键差异点：
- `POST`/`PUT` 时校验 `members` 中每个名称是否存在于代理服务器或其他代理组中
- `DELETE` 时检查是否被 `[Rule]` 引用

```java
@Slf4j
@ChannelHandler.Sharable
public class ProxyGroupApiHandler extends AbstractApiHandler {

    private final ConfigManager configManager;

    public ProxyGroupApiHandler(ConfigManager configManager) {
        this.configManager = configManager;
    }

    @Override protected String pathPrefix() { return "/api/proxy-groups"; }

    @Override
    protected void handle(ChannelHandlerContext ctx, FullHttpRequest req,
                          String method, String path) throws Exception {
        String name = extractPathParam(path);
        switch (method) {
            case "GET":
                writeJson(ctx, configManager.getModel().getProxyGroups());
                break;
            case "POST": {
                ProxyGroupConfig g = readBody(req, ProxyGroupConfig.class);
                ConfigModel m = configManager.getModel();
                validateMembers(m, g.getMembers());
                m.getProxyGroups().add(g);
                configManager.save(m);
                writeJson(ctx, g);
                break;
            }
            case "PUT": {
                ProxyGroupConfig g = readBody(req, ProxyGroupConfig.class);
                ConfigModel m = configManager.getModel();
                validateMembers(m, g.getMembers());
                replaceGroup(m, name, g);
                configManager.save(m);
                writeJson(ctx, g);
                break;
            }
            case "DELETE": {
                ConfigModel m = configManager.getModel();
                checkGroupNotReferenced(m, name);
                m.getProxyGroups().removeIf(g -> g.getName().equals(name));
                configManager.save(m);
                writeJson(ctx, null);
                break;
            }
            default:
                writeError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "Method not allowed");
        }
    }

    private void validateMembers(ConfigModel model, List<String> members) {
        Set<String> known = new HashSet<>();
        model.getProxies().forEach(p -> known.add(p.getName()));
        model.getProxyGroups().forEach(g -> known.add(g.getName()));
        known.addAll(Arrays.asList("DIRECT", "DROP", "REJECT"));
        for (String m : members) {
            if (!known.contains(m)) {
                throw new IllegalArgumentException("Unknown proxy member: " + m);
            }
        }
    }

    private void replaceGroup(ConfigModel model, String name, ProxyGroupConfig updated) {
        List<ProxyGroupConfig> groups = model.getProxyGroups();
        for (int i = 0; i < groups.size(); i++) {
            if (groups.get(i).getName().equals(name)) {
                groups.set(i, updated);
                return;
            }
        }
        throw new IllegalArgumentException("Proxy group not found: " + name);
    }

    private void checkGroupNotReferenced(ConfigModel model, String name) {
        List<String> refs = new ArrayList<>();
        model.getRules().stream()
             .filter(r -> name.equals(r.getUpstream()))
             .forEach(r -> refs.add("规则: " + r.toRuleLine()));
        if (!refs.isEmpty()) {
            throw new IllegalStateException("Group is referenced by: " + String.join(", ", refs));
        }
    }
}
```

### 6.3 RuleApiHandler — 路由规则 CRUD + 排序

路径 `/api/rules`。规则列表是有序的，支持通过索引定位，支持拖拽重排。

```java
@Slf4j
@ChannelHandler.Sharable
public class RuleApiHandler extends AbstractApiHandler {

    private final ConfigManager configManager;

    public RuleApiHandler(ConfigManager configManager) {
        this.configManager = configManager;
    }

    @Override protected String pathPrefix() { return "/api/rules"; }

    @Override
    protected void handle(ChannelHandlerContext ctx, FullHttpRequest req,
                          String method, String path) throws Exception {
        // path: /api/rules, /api/rules/{index}, /api/rules/reorder
        boolean isReorder = path.endsWith("/reorder");
        String indexStr = extractPathParam(path);
        int index = (indexStr != null && !isReorder) ? Integer.parseInt(indexStr) : -1;

        switch (method) {
            case "GET":
                writeJson(ctx, configManager.getModel().getRules());
                break;

            case "POST":
                if (isReorder) {
                    // POST /api/rules/reorder  body: { "order": [0,3,1,2,...] }
                    int[] newOrder = readBody(req, ReorderRequest.class).getOrder();
                    ConfigModel m = configManager.getModel();
                    List<RouteRuleConfig> rules = m.getRules();
                    List<RouteRuleConfig> reordered = new ArrayList<>();
                    for (int i : newOrder) reordered.add(rules.get(i));
                    m.setRules(reordered);
                    configManager.save(m);
                    writeJson(ctx, m.getRules());
                } else {
                    // POST /api/rules  → 追加（MATCH 规则始终置底）
                    RouteRuleConfig r = readBody(req, RouteRuleConfig.class);
                    ConfigModel m = configManager.getModel();
                    insertRule(m, r);
                    configManager.save(m);
                    writeJson(ctx, r);
                }
                break;

            case "PUT":
                // PUT /api/rules/{index}
                RouteRuleConfig r = readBody(req, RouteRuleConfig.class);
                ConfigModel m2 = configManager.getModel();
                m2.getRules().set(index, r);
                configManager.save(m2);
                writeJson(ctx, r);
                break;

            case "DELETE":
                // DELETE /api/rules/{index}
                ConfigModel m3 = configManager.getModel();
                m3.getRules().remove(index);
                configManager.save(m3);
                writeJson(ctx, null);
                break;

            default:
                writeError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "Method not allowed");
        }
    }

    /** 新规则插入到 MATCH 规则之前（MATCH 始终置底） */
    private void insertRule(ConfigModel model, RouteRuleConfig newRule) {
        List<RouteRuleConfig> rules = model.getRules();
        if ("MATCH".equals(newRule.getType())) {
            rules.add(newRule);
            return;
        }
        int matchIndex = -1;
        for (int i = 0; i < rules.size(); i++) {
            if ("MATCH".equals(rules.get(i).getType())) {
                matchIndex = i;
                break;
            }
        }
        if (matchIndex >= 0) {
            rules.add(matchIndex, newRule);
        } else {
            rules.add(newRule);
        }
    }

    @Data
    public static class ReorderRequest {
        private int[] order;
    }
}
```

### 6.4 RuleFileApiHandler — .list 规则文件读写

路径 `/api/rule-files`，文件路径作为查询参数传入（`?path=rule.d/video.list`）。

```java
@Slf4j
@ChannelHandler.Sharable
public class RuleFileApiHandler extends AbstractApiHandler {

    /** 规则文件相对于 conf/ 目录的根目录 */
    private final File confDir;

    public RuleFileApiHandler(File confDir) {
        this.confDir = confDir;
    }

    @Override protected String pathPrefix() { return "/api/rule-files"; }

    @Override
    protected void handle(ChannelHandlerContext ctx, FullHttpRequest req,
                          String method, String path) throws Exception {
        QueryStringDecoder qsd = new QueryStringDecoder(req.uri());
        List<String> pathParam = qsd.parameters().get("path");

        switch (method) {
            case "GET":
                if (pathParam == null || pathParam.isEmpty()) {
                    // GET /api/rule-files → 列出所有 .list 文件
                    writeJson(ctx, listRuleFiles());
                } else {
                    // GET /api/rule-files?path=rule.d/video.list → 文件内容
                    writeJson(ctx, readRuleFile(pathParam.get(0)));
                }
                break;

            case "PUT": {
                // PUT /api/rule-files?path=... body: { "lines": ["DOMAIN-SUFFIX,x.com",...] }
                RuleFileContent content = readBody(req, RuleFileContent.class);
                writeRuleFile(pathParam.get(0), content.getLines());
                writeJson(ctx, null);
                break;
            }

            case "POST": {
                // POST /api/rule-files body: { "path": "rule.d/new.list", "lines": [...] }
                RuleFileContent content = readBody(req, RuleFileContent.class);
                writeRuleFile(content.getPath(), content.getLines());
                writeJson(ctx, null);
                break;
            }

            default:
                writeError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "Method not allowed");
        }
    }

    private List<String> listRuleFiles() {
        List<String> result = new ArrayList<>();
        collectListFiles(confDir, confDir, result);
        return result;
    }

    private void collectListFiles(File base, File dir, List<String> result) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                collectListFiles(base, f, result);
            } else if (f.getName().endsWith(".list")) {
                result.add(base.toURI().relativize(f.toURI()).getPath());
            }
        }
    }

    private List<String> readRuleFile(String relativePath) throws IOException {
        File file = resolveAndValidate(relativePath);
        return Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
    }

    private void writeRuleFile(String relativePath, List<String> lines) throws IOException {
        File file = resolveAndValidate(relativePath);
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), lines, StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /** 防止路径遍历攻击：确保文件在 confDir 范围内 */
    private File resolveAndValidate(String relativePath) throws IOException {
        File f = new File(confDir, relativePath).getCanonicalFile();
        if (!f.getAbsolutePath().startsWith(confDir.getCanonicalPath())) {
            throw new SecurityException("Path traversal detected: " + relativePath);
        }
        return f;
    }

    @Data
    public static class RuleFileContent {
        private String       path;
        private List<String> lines;
    }
}
```

### 6.5 ListenerApiHandler — 端口监听 CRUD

路径 `/api/listeners`，以端口号为 key。

```java
@Slf4j
@ChannelHandler.Sharable
public class ListenerApiHandler extends AbstractApiHandler {

    private final ConfigManager configManager;

    public ListenerApiHandler(ConfigManager configManager) {
        this.configManager = configManager;
    }

    @Override protected String pathPrefix() { return "/api/listeners"; }

    @Override
    protected void handle(ChannelHandlerContext ctx, FullHttpRequest req,
                          String method, String path) throws Exception {
        String portStr = extractPathParam(path);
        int port = portStr != null ? Integer.parseInt(portStr) : -1;

        switch (method) {
            case "GET":
                writeJson(ctx, configManager.getModel().getListeners());
                break;
            case "POST": {
                PortListenerConfig l = readBody(req, PortListenerConfig.class);
                validatePort(l.getPort());
                ConfigModel m = configManager.getModel();
                if (m.getListeners().stream().anyMatch(x -> x.getPort() == l.getPort())) {
                    writeError(ctx, HttpResponseStatus.CONFLICT, "Port already in use: " + l.getPort());
                    return;
                }
                m.getListeners().add(l);
                configManager.save(m);
                writeJson(ctx, l);
                break;
            }
            case "PUT": {
                PortListenerConfig l = readBody(req, PortListenerConfig.class);
                ConfigModel m = configManager.getModel();
                replaceListener(m, port, l);
                configManager.save(m);
                writeJson(ctx, l);
                break;
            }
            case "DELETE": {
                ConfigModel m = configManager.getModel();
                m.getListeners().removeIf(l -> l.getPort() == port);
                configManager.save(m);
                writeJson(ctx, null);
                break;
            }
            default:
                writeError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "Method not allowed");
        }
    }

    private void validatePort(int port) {
        if (port < 1024 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1024 and 65535");
        }
    }

    private void replaceListener(ConfigModel model, int port, PortListenerConfig updated) {
        List<PortListenerConfig> list = model.getListeners();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getPort() == port) {
                list.set(i, updated);
                return;
            }
        }
        throw new IllegalArgumentException("Listener not found for port: " + port);
    }
}
```

### 6.6 TunApiHandler — TUN 设备 + Fake DNS 配置

路径 `/api/tun`，整体读写（无 MATCH by ID）。TUN 接口支持多条，Fake DNS 整体替换。

```java
@Slf4j
@ChannelHandler.Sharable
public class TunApiHandler extends AbstractApiHandler {

    private final ConfigManager configManager;

    public TunApiHandler(ConfigManager configManager) {
        this.configManager = configManager;
    }

    @Override protected String pathPrefix() { return "/api/tun"; }

    @Override
    protected void handle(ChannelHandlerContext ctx, FullHttpRequest req,
                          String method, String path) throws Exception {
        switch (method) {
            case "GET": {
                TunApiResponse resp = new TunApiResponse();
                ConfigModel m = configManager.getModel();
                resp.setInterfaces(m.getTunInterfaces());
                resp.setFakeDns(m.getFakeDns());
                writeJson(ctx, resp);
                break;
            }
            case "PUT": {
                TunApiResponse body = readBody(req, TunApiResponse.class);
                ConfigModel m = configManager.getModel();
                if (body.getInterfaces() != null) m.setTunInterfaces(body.getInterfaces());
                if (body.getFakeDns() != null)    m.setFakeDns(body.getFakeDns());
                configManager.save(m);
                writeJson(ctx, body);
                break;
            }
            default:
                writeError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "Method not allowed");
        }
    }

    @Data
    public static class TunApiResponse {
        private List<TunInterfaceConfig> interfaces;
        private FakeDnsConfig            fakeDns;
    }
}
```

### 6.7 ConfigApiHandler — 配置文件预览

路径 `/api/config`，返回当前配置的完整 INI 文本。

```java
@Slf4j
@ChannelHandler.Sharable
public class ConfigApiHandler extends AbstractApiHandler {

    private final ConfigManager configManager;

    public ConfigApiHandler(ConfigManager configManager) {
        this.configManager = configManager;
    }

    @Override protected String pathPrefix() { return "/api/config"; }

    @Override
    protected void handle(ChannelHandlerContext ctx, FullHttpRequest req,
                          String method, String path) throws Exception {
        if (!"GET".equals(method)) {
            writeError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "Method not allowed");
            return;
        }
        Map<String, String> result = new HashMap<>();
        result.put("content", configManager.getModel().toIni());
        writeJson(ctx, result);
    }
}
```

### 6.8 ReloadApiHandler — 保存并重载

路径 `/api/reload`，触发 `Application.reload()`。需持有 `Application` 引用。

```java
@Slf4j
@ChannelHandler.Sharable
public class ReloadApiHandler extends AbstractApiHandler {

    private final Application application;
    private final URL         configLocation;

    public ReloadApiHandler(Application application, URL configLocation) {
        this.application   = application;
        this.configLocation = configLocation;
    }

    @Override protected String pathPrefix() { return "/api/reload"; }

    @Override
    protected void handle(ChannelHandlerContext ctx, FullHttpRequest req,
                          String method, String path) throws Exception {
        if (!"POST".equals(method)) {
            writeError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "Method not allowed");
            return;
        }
        // 异步执行重载，避免阻塞 Netty IO 线程
        ctx.channel().eventLoop().execute(() -> {
            try {
                application.reload(configLocation);
                try { writeJson(ctx, Collections.singletonMap("status", "ok")); }
                catch (Exception ignored) {}
            } catch (Exception e) {
                log.error("Reload failed", e);
                try { writeError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, e.getMessage()); }
                catch (Exception ignored) {}
            }
        });
    }
}
```

### 6.9 StatusApiHandler — 上游连通性状态

路径 `/api/status`，从 `RouteContext` 读取所有上游并返回 `isAvailable()` 状态。

```java
@Slf4j
@ChannelHandler.Sharable
public class StatusApiHandler extends AbstractApiHandler {

    private final RouteContext context;

    // context 在 reload 后可能更新，用 AtomicReference 保存最新引用
    private final java.util.concurrent.atomic.AtomicReference<RouteContext> contextRef;

    public StatusApiHandler(java.util.concurrent.atomic.AtomicReference<RouteContext> contextRef) {
        this.contextRef = contextRef;
        this.context = null;
    }

    @Override protected String pathPrefix() { return "/api/status"; }

    @Override
    protected void handle(ChannelHandlerContext ctx, FullHttpRequest req,
                          String method, String path) throws Exception {
        if (!"GET".equals(method)) {
            writeError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "Method not allowed");
            return;
        }
        RouteContext current = contextRef.get();
        List<Map<String, Object>> result = new ArrayList<>();
        if (current != null) {
            for (Upstream u : current.upstreams()) {
                Map<String, Object> item = new HashMap<>();
                item.put("name",      u.name());
                item.put("available", u.isAvailable());
                item.put("virtual",   u.isVirtual());
                SocketAddress addr = u.address();
                item.put("address",   addr != null ? addr.toString() : null);
                result.add(item);
            }
        }
        writeJson(ctx, result);
    }
}
```

---

## 七、ManagementApiAcceptor — HTTP 服务器

整合替代 `RuleExporterAcceptor`，将所有 API Handler 和既有的 PAC/SwitchyOmega Handler 组合到同一个 Netty Pipeline。

```java
package com.github.pangolin.routing.management;

import com.github.pangolin.routing.acceptor.Acceptor;
import com.github.pangolin.routing.acceptor.extra.ProxyAutoConfigurationServerHandler;
import com.github.pangolin.routing.acceptor.extra.SwitchyRuleConfigurationServerHandler;
import com.github.pangolin.routing.context.RouteContext;
import com.github.pangolin.routing.management.config.ConfigManager;
import com.github.pangolin.routing.management.handler.*;
import com.github.pangolin.routing.route.RouteRegistry;
import com.github.pangolin.server.NettyServer;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.*;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.net.*;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class ManagementApiAcceptor implements Acceptor {

    /** 管理 API 监听端口，默认 9090 */
    public static final int DEFAULT_PORT = 9090;

    private final int                          listenPort;
    private final int                          pacProxyPort;
    private final ConfigManager                configManager;
    private final Application                  application;
    private final URL                          configLocation;
    private final AtomicReference<RouteContext> contextRef;

    public ManagementApiAcceptor(int pacProxyPort,
                                 ConfigManager configManager,
                                 Application application,
                                 URL configLocation,
                                 AtomicReference<RouteContext> contextRef) {
        this(DEFAULT_PORT, pacProxyPort, configManager, application, configLocation, contextRef);
    }

    public ManagementApiAcceptor(int listenPort, int pacProxyPort,
                                 ConfigManager configManager,
                                 Application application,
                                 URL configLocation,
                                 AtomicReference<RouteContext> contextRef) {
        this.listenPort     = listenPort;
        this.pacProxyPort   = pacProxyPort;
        this.configManager  = configManager;
        this.application    = application;
        this.configLocation = configLocation;
        this.contextRef     = contextRef;
    }

    @Override
    public ChannelFuture start(final RouteContext context) throws Exception {
        // 所有 Handler 为 @Sharable，可在 pipeline 中共享
        final ProxyApiHandler        proxyHandler      = new ProxyApiHandler(configManager);
        final ProxyGroupApiHandler   groupHandler      = new ProxyGroupApiHandler(configManager);
        final RuleApiHandler         ruleHandler       = new RuleApiHandler(configManager);
        final RuleFileApiHandler     fileHandler       = new RuleFileApiHandler(
                                                             new File(configLocation.toURI()).getParentFile());
        final ListenerApiHandler     listenerHandler   = new ListenerApiHandler(configManager);
        final TunApiHandler          tunHandler        = new TunApiHandler(configManager);
        final ConfigApiHandler       configHandler     = new ConfigApiHandler(configManager);
        final ReloadApiHandler       reloadHandler     = new ReloadApiHandler(application, configLocation);
        final StatusApiHandler       statusHandler     = new StatusApiHandler(contextRef);

        return new NettyServer(listenPort).start(true, new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) throws Exception {
                ch.pipeline()
                  .addLast(new HttpServerCodec())
                  .addLast(new HttpObjectAggregator(8 * 1024 * 1024))
                  // ── 管理 REST API ──────────────────────────────────────
                  .addLast(proxyHandler)
                  .addLast(groupHandler)
                  .addLast(ruleHandler)
                  .addLast(fileHandler)
                  .addLast(listenerHandler)
                  .addLast(tunHandler)
                  .addLast(configHandler)
                  .addLast(reloadHandler)
                  .addLast(statusHandler)
                  // ── 兼容既有端点 ───────────────────────────────────────
                  .addLast(new SwitchyRuleConfigurationServerHandler(
                               (RouteRegistry) contextRef.get()))
                  .addLast(new ProxyAutoConfigurationServerHandler(
                               (RouteRegistry) contextRef.get(), pacProxyPort))
                  // ── 404 兜底 ───────────────────────────────────────────
                  .addLast(new ChannelInboundHandlerAdapter() {
                      @Override
                      public void channelRead(ChannelHandlerContext ctx, Object msg) {
                          ctx.writeAndFlush(new DefaultFullHttpResponse(
                              HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND))
                             .addListener(ChannelFutureListener.CLOSE);
                      }
                  });
            }
        }).addListener(f -> {
            if (f.isSuccess()) {
                log.info("Management API started on port: {}", listenPort);
            } else {
                log.error("Management API failed to start", f.cause());
            }
        });
    }
}
```

---

## 八、Application.java 改动

### 改动说明

| 改动点 | 内容 |
|--------|------|
| 新增字段 | `configManager`、`businessChannelGroup`、`managementChannel`、`contextRef` |
| 新增方法 | `reload(URL configLocation)` |
| `run()` 改动 | 启动业务 Acceptor 存入 `businessChannelGroup`，分离管理 API 到独立 Channel |
| `await()` 改动 | 等待管理 API Channel 关闭（而非业务 Channel），确保重载后不退出 |
| `main()` 改动 | 初始化 `ConfigManager` 并传入 `run()` |

### 改动后的关键方法

```java
@Slf4j
public class Application {
    // ── 新增字段 ──────────────────────────────────────────────────────────
    protected ConfigManager configManager;
    /** 管理 API channel，全程存活，不受 reload 影响 */
    private Channel managementChannel;
    /** 业务 Acceptor channel 组，reload 时关闭重建 */
    protected final ChannelGroup businessChannelGroup =
        new DefaultChannelGroup("business-channels", GlobalEventExecutor.INSTANCE);
    /** 持有最新 RouteContext 的引用，供 StatusApiHandler 读取 */
    protected final AtomicReference<RouteContext> contextRef = new AtomicReference<>();
    /** 根 context（内置 DIRECT/DROP/REJECT），跨 reload 保持不变 */
    private InheritableRouteContext root;

    // ── 原有字段保留 ───────────────────────────────────────────────────────
    protected final LoadBalancerStats             stats = new LoadBalancerStats();
    protected final Iterable<UpstreamFactory>     upstreamFactories;
    protected final Map<String, UpstreamCombiner> upstreamCombiners = Maps.newLinkedHashMap();
    protected final Map<String, RoutePredicateFactory> predicateFactories = Maps.newLinkedHashMap();
    // channelGroup 字段保留但不再用于 await（兼容外部引用）
    protected final ChannelGroup channelGroup =
        new DefaultChannelGroup("acceptor-channels", GlobalEventExecutor.INSTANCE);

    // ── run() 改动 ────────────────────────────────────────────────────────
    public RouteContext run(final URL configLocation) throws Exception {
        // 初始化 ConfigManager（若尚未初始化）
        if (configManager == null) {
            configManager = new ConfigManager(new File(configLocation.toURI()));
        }

        // 初始化根 context（首次）
        if (root == null) {
            root = new InheritableRouteContext(null, null);
            for (Upstream u : new Upstream[]{DirectUpstream.INSTANCE, DropUpstream.INSTANCE, RejectUpstream.INSTANCE}) {
                root.addUpstream(u.name(), u);
            }
        }

        log.info("Initializing context from {}", configLocation);
        final RouteContext context = createParentContext(configLocation, root);
        root.addUpstream(RouteUpstream.NAME,
            new RouteUpstream((RouteRegistry<InetSocketAddress>) context, (UpstreamRegistry) context));

        contextRef.set(context);

        // 启动业务 Acceptor
        int proxyPort = startBusinessAcceptors(context);

        // 启动管理 API（首次，后续 reload 不重建）
        if (managementChannel == null) {
            ManagementApiAcceptor mgmt = new ManagementApiAcceptor(
                proxyPort, configManager, this, configLocation, contextRef);
            managementChannel = mgmt.start(context).sync().channel();
        }

        return context;
    }

    /** 启动业务 Acceptor，返回最后绑定的端口（用于 PAC 文件） */
    private int startBusinessAcceptors(RouteContext context) throws Exception {
        int port = 0;
        for (RouteContext ctx = context; ctx != null; ctx = ctx.parent()) {
            if (!(ctx instanceof AcceptorProvider)) continue;
            for (Acceptor acceptor : ((AcceptorProvider) ctx).getAcceptors()) {
                ChannelFuture cf = acceptor.start(context);
                Channel channel = cf.channel();
                businessChannelGroup.add(channel);
                channelGroup.add(channel);  // 兼容旧逻辑
                cf.sync();
                SocketAddress bound = channel.localAddress();
                if (bound instanceof InetSocketAddress) {
                    port = ((InetSocketAddress) bound).getPort();
                    log.info("Business acceptor bound to {}", bound);
                }
            }
        }
        return port;
    }

    // ── reload() — 新增方法 ───────────────────────────────────────────────
    /**
     * 关闭所有业务 Acceptor，重新解析配置并重启。
     * 管理 API (9090) 全程保持运行不受影响。
     * 此方法为同步操作，调用方应确保在非 Netty IO 线程调用。
     */
    public synchronized void reload(final URL configLocation) throws Exception {
        log.info("Reloading configuration...");

        // 1. 关闭现有业务 channel
        businessChannelGroup.close().sync();
        // DefaultChannelGroup 会自动移除已关闭的 channel，无需手动 clear

        // 2. 重新加载配置
        configManager = new ConfigManager(new File(configLocation.toURI()));

        // 3. 重建 context 并启动业务 Acceptor
        run(configLocation);

        log.info("Reload complete.");
    }

    // ── await() 改动 ──────────────────────────────────────────────────────
    /** 等待管理 API Channel 关闭（整个进程生命周期）。 */
    public void await() throws InterruptedException {
        if (managementChannel != null) {
            managementChannel.closeFuture().sync();
        } else {
            channelGroup.newCloseFuture().sync();  // fallback
        }
    }

    // ── main() 改动 ───────────────────────────────────────────────────────
    public static void main(String[] args) throws Exception {
        final ApplicationHome home = new ApplicationHome(Application.class);
        final URL conf = new File(home.getDir(), "conf/default.conf").toURI().toURL();
        final Application app = new Application();
        app.run(conf);
        app.await();
    }
}
```

---

## 九、RuleExporterAcceptor 处置

`RuleExporterAcceptor` 原有功能（PAC 文件、SwitchyOmega 规则）已被 `ManagementApiAcceptor` 整合。

**改动方式：** 在 `Application.createRouteExporterAcceptor()` 中不再创建 `RuleExporterAcceptor`，而是由 `run()` 中直接启动 `ManagementApiAcceptor`。

```java
// Application.run() 中删除以下旧代码：
// channelGroup.add(createRouteExporterAcceptor(port).start(contextToUse).channel());

// 改动后 createRouteExporterAcceptor() 方法可保留（向后兼容）或删除
```

`RuleExporterAcceptor.java` 本身**不删除**（保留以防其他地方引用），但不再被 `Application` 调用。

---

## 十、REST API 端点汇总

| 方法   | 路径                              | 说明                            |
|--------|-----------------------------------|---------------------------------|
| GET    | `/api/proxies`                    | 列出所有代理服务器              |
| POST   | `/api/proxies`                    | 新增代理服务器                  |
| PUT    | `/api/proxies/{name}`             | 更新代理服务器                  |
| DELETE | `/api/proxies/{name}`             | 删除代理服务器（含引用检查）    |
| GET    | `/api/proxy-groups`               | 列出所有代理组                  |
| POST   | `/api/proxy-groups`               | 新增代理组                      |
| PUT    | `/api/proxy-groups/{name}`        | 更新代理组                      |
| DELETE | `/api/proxy-groups/{name}`        | 删除代理组（含引用检查）        |
| GET    | `/api/rules`                      | 列出所有路由规则（有序）        |
| POST   | `/api/rules`                      | 新增路由规则                    |
| PUT    | `/api/rules/{index}`              | 更新指定索引的规则              |
| DELETE | `/api/rules/{index}`              | 删除指定索引的规则              |
| POST   | `/api/rules/reorder`              | 批量重排规则顺序                |
| GET    | `/api/rule-files`                 | 列出所有 .list 文件路径         |
| GET    | `/api/rule-files?path={path}`     | 读取 .list 文件内容             |
| PUT    | `/api/rule-files?path={path}`     | 更新 .list 文件内容             |
| POST   | `/api/rule-files`                 | 新建 .list 文件                 |
| GET    | `/api/listeners`                  | 列出所有端口监听                |
| POST   | `/api/listeners`                  | 新增端口监听                    |
| PUT    | `/api/listeners/{port}`           | 更新端口监听                    |
| DELETE | `/api/listeners/{port}`           | 删除端口监听                    |
| GET    | `/api/tun`                        | 获取 TUN + Fake DNS 配置        |
| PUT    | `/api/tun`                        | 更新 TUN + Fake DNS 配置        |
| GET    | `/api/config`                     | 获取完整 INI 配置文本预览       |
| POST   | `/api/reload`                     | 保存当前内存模型并重载服务      |
| GET    | `/api/status`                     | 获取所有上游连通性状态          |
| GET    | `/switchy.sorl`                   | 既有 SwitchyOmega 规则（保留）  |
| GET    | `/proxy.pac`                      | 既有 PAC 文件（保留）           |

---

## 十一、热重载时序

```
前端 POST /api/reload
        │
        ▼
ReloadApiHandler.handle()
        │  （切换到 eventLoop.execute 异步执行）
        │
        ▼
Application.reload(configLocation)
        │
        ├─ 1. businessChannelGroup.close().sync()
        │       所有 MixinAcceptor / TunAcceptor / FakeDnsAcceptor 端口关闭
        │
        ├─ 2. configManager = new ConfigManager(configFile)
        │       从磁盘重新读取 default.conf（含之前保存的修改）
        │
        ├─ 3. run(configLocation)
        │       重新解析 INI → 新 RouteContext
        │       重启所有业务 Acceptor
        │       contextRef.set(newContext)
        │
        └─ 管理 API (9090) 全程不中断，前端无感知
```

---

## 十二、改动文件汇总

| 操作 | 文件 | 说明 |
|------|------|------|
| 修改 | `pangolin-routing/pom.xml` | 新增 `jackson-databind` 依赖 |
| 修改 | `Application.java` | 新增 `reload()`、`configManager`、`businessChannelGroup`、改 `await()` |
| 保留不动 | `RuleExporterAcceptor.java` | 不再由 Application 调用，但文件保留 |
| **新增** | `management/ManagementApiAcceptor.java` | HTTP 管理服务器 |
| **新增** | `management/config/ConfigManager.java` | INI 读写管理器 |
| **新增** | `management/config/ConfigModel.java` | 全量配置内存模型 + `toIni()` |
| **新增** | `management/config/ProxyConfig.java` | [Proxy] 模型 + URI 序列化 |
| **新增** | `management/config/ProxyGroupConfig.java` | [Proxy Group] 模型 |
| **新增** | `management/config/RouteRuleConfig.java` | [Rule] 模型 |
| **新增** | `management/config/PortListenerConfig.java` | [Listen] 模型 |
| **新增** | `management/config/TunInterfaceConfig.java` | [Tun] 模型 |
| **新增** | `management/config/FakeDnsConfig.java` | [Fake DNS] 模型 |
| **新增** | `management/config/ExternalConfig.java` | [External] 模型 |
| **新增** | `management/handler/AbstractApiHandler.java` | Handler 基类 |
| **新增** | `management/handler/ProxyApiHandler.java` | 代理服务器 CRUD |
| **新增** | `management/handler/ProxyGroupApiHandler.java` | 代理组 CRUD |
| **新增** | `management/handler/RuleApiHandler.java` | 路由规则 CRUD + 排序 |
| **新增** | `management/handler/RuleFileApiHandler.java` | .list 文件读写 |
| **新增** | `management/handler/ListenerApiHandler.java` | 端口监听 CRUD |
| **新增** | `management/handler/TunApiHandler.java` | TUN + Fake DNS 配置 |
| **新增** | `management/handler/ConfigApiHandler.java` | 配置文件预览 |
| **新增** | `management/handler/ReloadApiHandler.java` | 保存并重载 |
| **新增** | `management/handler/StatusApiHandler.java` | 上游连通性状态 |
