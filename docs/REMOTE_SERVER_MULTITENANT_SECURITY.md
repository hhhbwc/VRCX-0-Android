# 远程服务端：多租户与安全设计

> 触发背景：服务端不再只服务单人，而是**多个用户各自用自己的 VRChat 账号**，且需要**局域网 + 外网**两种接入。
> 这两条任意一条，都让"单租户 + 明文 + 共用 token"的现有形态不成立。
>
> **状态（2026-09-18）**：第 2 节三层模型的**第二层（身份）与第三层（越权）已在服务端 + 桌面客户端实施完毕**；
> 第一层（传输加密）与 Android 客户端接入尚未落地。第 1 节表格记录的是**改造前的现状**，保留下来是为了让后来者理解每个改动为什么必要。

---

## 1. 改造前现状：实测证据（不是推测）

| 项 | 改造前 | 证据位置 |
|---|---|---|
| 传输加密 | **无**。裸 HTTP | `settings.rs` `DEFAULT_BIND_ADDRESS="0.0.0.0:8790"`；`main.rs` `TcpListener::bind` → `axum::serve` |
| rustls 依赖 | **不是给服务端用的** | 唯一用法是 `main.rs::init_tls_crypto_provider()`，为**出站** reqwest（打 VRChat 官方 HTTPS）装 crypto provider |
| 免鉴权端点 | `/v1/health`、`/v1/auth/status\|accounts\|login\|2fa` | `api.rs`（旧路由表） |
| 账号列表泄露 | `/v1/auth/accounts` **免 token** 即可读取（displayName / username / 头像） | 旧 `api.rs` 路由 + `auth.rs` |
| 身份凭据 | **全服务器共用一把静态 token**，登录成功即发放，不轮换、不可单吊销 | 旧 `auth.rs`（`token: state.token().to_string()`） |
| 静态存储 | token 仅 **XOR 混淆**（非加密，读文件即可还原） | DB `config:vrcx_remoteservertoken`，格式 `cfgobf1:<len>:<sha256前8B>:<xor hex>` |
| 租户隔离 | **不存在**。进程内只有一个运行时 | 旧 `ApiState{rpc, token, events, relay, runtime, auth}` 全是单例；旧 `RpcContext` 持有唯一 `Arc<RuntimeHostState>` |
| 设计前提 | 代码注释明确要求**不得暴露公网** | 旧 `auth.rs` / `api.rs` 注释 |

**最关键的一条**：旧 `auth.rs` 把同一把 token 交给每个登录成功者。所以即使把链路加密做到极致，**任何一个用户仍然能完全冒充其他用户**。加密只解决"谁在偷听"，解决不了"你是谁"——这就是为什么本方案把工作主体放在第二、三层，而不是先上 TLS。

---

## 2. 三层模型

"对用户隐私负责"需要三层，缺一层都不成立：

| 层 | 回答的问题 | 改造前 | 目标 | 现在 |
|---|---|---|---|---|
| 第一层 传输 | 谁在偷听 | 明文 HTTP，同网段可嗅探密码 / 验证码 / token | TLS 或 WireGuard 加密 | ⏳ **未做** |
| 第二层 身份 | 你是谁 | 全服务器共用一把静态 token | **每租户独立凭据，可单独吊销** | ✅ **已实施** |
| 第三层 越权 | 你能看什么 | 无。持 token 者可读全部数据 | 按租户过滤，别人的会话与账号列表不返回 | ✅ **已实施** |

---

## 3. 目标架构（已落地形态）

```
用户 A / B / C 的客户端
        │  ① 传输层加密（⏳ 未落地：Tailscale / nginx TLS 反代）
        ▼
vrcx-0-remote-server
  ┌──────────────────────────────────────────────────────┐
  │ TenantRegistry   token 摘要 → Arc<TenantRuntime>      │
  │   注册表：<data_root>/tenants.json（无明文密钥）       │
  ├──────────────────────────────────────────────────────┤
  │ 租户 A          租户 B          租户 C                 │
  │ 独立 token      独立 token      独立 token（可单独吊销）│
  │ 独立 SQLite     独立 SQLite     独立 SQLite            │
  │ 独立会话        独立会话        独立会话                │
  │ 独立事件流      独立事件流      独立事件流              │
  └──────────────────────────────────────────────────────┘
        │
        ▼  <data_root>/tenants/<tenantId>/
```

### 3.1 可行性依据（已核实）

`RuntimeHostState` **没有任何进程级全局状态** —— 全部是实例字段，事件总线也是实例上的 `runtime_context.event_bus`，并且已经有按数据目录加锁的 `profile_lock`。

结论：**一个租户 = 一个 `app_data_dir` = 一个 `RuntimeHostState` = 一套完全独立的 DB / 会话 / 事件流**。不需要动 composition 层，改动集中在 `remote-server` crate 的装配与路由层 —— 最终确实如此，composition 一行未改。

### 3.2 接入层：现状与两种落地形态（⏳ 部分落地）

外网接入让"不得暴露公网"这个前提失效。三条路：

| 方案 | 加密 | 外网可达 | 设备身份 | 应用改动 | 结论 |
|---|---|---|---|---|---|
| **Tailscale / WireGuard** | ✅ | ✅ 免端口转发 | ✅ 独立密钥、可单独吊销、带 ACL | **零** | 首选 |
| **nginx 终止 TLS + 自签证书 + 客户端 SPKI 固定** | ✅ | ⚠️ 要自己做端口映射 | ❌ 应用凭据承担 | 中（客户端要能信任自签证书） | 备选，**已在做** |
| 服务端原生 TLS | ✅ | ⚠️ 要域名 + 真实证书 | ❌ 需自己做 | 最大（+交叉编译风险） | 不选 |

两层的分工要说清：**接入层解决的是"谁能连上"和"路上有没有人听"，应用凭据（第一、二层的身份部分）解决的是"连上之后你是谁"**。两者互补，谁也替代不了谁 —— Tailscale 上仍然需要每租户 token，公网 TLS + token 也仍然需要接入层限制可达范围。

#### 部署形态与端口（这是运维实际要动的东西）

```
外部 → NAT 映射(外部端口 → 容器 443) → nginx 443 (终止 TLS)
                                        └→ http://127.0.0.1:8790 → vrcx-0-remote-server
```

| 监听 | 谁在听 | 对外映射吗 |
|---|---|---|
| `127.0.0.1:8790` | 服务端自己（`vps-provision.sh` 里写死） | ❌ **永远不要**。它是明文口，映射它等于把 TLS 白做 |
| `0.0.0.0:443` | nginx（`vps-tls-setup.sh`） | ✅ **映射到这里**；`ufw` 也只放 22 / 80 / 443 |
| `0.0.0.0:80` | nginx（仅为 ACME 校验 / 跳转） | 可选 |

外部端口由运维自选（容器 443 ← 外部任意端口，例如 8443）；客户端按 `https://主机:外部端口` 填写，`normalizeBaseUrl` 对 `https` **不会**补 `8790`。

#### ⚠️ 客户端信任自签证书：桌面端尚未解决

**Android 已做**：`data/remote/TlsPinning.kt` —— 解析 SPKI SHA-256 → OkHttp `CertificatePinner`，`CommandClient` / `AuthClient` / `TenantClient` / `EventStreamClient`（含 wss）全部带 `certificatePin`，登录页可填指纹。三个刻意的约束：

1. 指纹只在 **https** 地址下才生效，填在 http 上会**明确报错**（否则用户会以为加密了，而实际什么都没校验）；
2. 解析不出来（比如误填了 `openssl -fingerprint` 那枚**带冒号的证书指纹**）时**拒绝连接**，不做"静默不固定"——那是比不固定更坏的状态；
3. `apply()` 只在 https 下装 pinner。**"没有 pinner" 恰好等于 "没有固定"**，不留第三态。

**桌面端（Tauri / WebView2）目前做不到**，因为 `src/platform/tauri/remoteTransport.ts` 用页面 `fetch`、`remoteEventStreamService.ts` 用页面 `WebSocket`，走的是 WebView2 自己的证书栈，项目里没有任何 pinning 配置。自签证书会被直接拒绝（`ERR_CERT_AUTHORITY_INVALID`），**开 TLS 后桌面端连不上**。

| 路线 | 做法 | 代价 |
|---|---|---|
| **(a) Chromium 原生 SPKI 列表** | 建窗口时给 WebView2 传 `--ignore-certificate-errors-spki-list=<Base64 SPKI>`（与 Android 同一枚指纹），入口 `src-tauri/src/bootstrap/window.rs::create_main_window` | 小。⚠️ **陷阱**：`WebviewWindowBuilder::additional_browser_args` 是**整体替换** wry 的默认参数集，而默认集里含 `--disable-features=msWebOOUI,msPdfOOUI,msSmartScreenProtection` **以及应用的 `--proxy-server=`**（`builder.proxy_url(...)` 只在默认路径上生效）。直接调用会**静默丢掉代理设置**，必须自己拼回去。另外 WebView2 环境是进程级创建 ⇒ 换指纹要重启应用 |
| **(b) 远程流量改走 Rust** | reqwest + `add_root_certificate` 固定自签证书；事件流用 tokio-tungstenite（两个 crate 都已是依赖），页面只收 Tauri 事件 | 大（含 WS 改造 + bindings 重生成 + 12 分钟发布构建），但最稳、跨平台 |
| **(c) 网络层加密** | WireGuard / Tailscale，应用零改动 | 前提是该主机能连上控制面 —— 当前 VPS 的 Tailscale 控制面不可达（同在 Cloudflare） |

> 若最终保留局域网直连路径（不走 Tailscale），应把服务端 bind 收窄到 `127.0.0.1:8790`，由 stunnel/nginx 监听 TLS 端口对外，否则明文口还开着等于白做。（`vps-provision.sh` 已经这样做了。）

---

## 4. 已实施的改造（文件级）

| # | 改动 | 位置 | 完成情况 |
|---|---|---|---|
| 1 | `TenantRegistry` / `TenantRuntime` | `remote-server/src/tenants.rs`（新增） | ✅ `Arc<RwLock<HashMap<..>>>` 语义；`load/create/adopt_legacy/rotate_token/authenticate/health/start_all` |
| 2 | 租户凭据存储 | `tenants.rs::TenantRecord` | ✅ **只存 SHA-256 摘要**（`token_sha256`），明文仅在注册响应返回一次；取代旧 XOR 混淆 |
| 3 | `ApiState` 去单例 | `api.rs` | ✅ 现为 `{ tenants: Arc<TenantRegistry>, app_version: Arc<str> }`，`rpc/events/runtime/auth/relay` 全下沉到租户 |
| 4 | 鉴权中间件解析租户 | `api.rs::require_tenant` | ✅ 由 token 反查租户 → `Extension<Arc<TenantRuntime>>`，handler 只从 extension 取，**结构上不可能拿错租户的运行时** |
| 5 | 启动流程改造 | `main.rs::async_main` | ✅ `TenantRegistry::load` → 旧 profile 自动 adopt → `start_all`；单租户起不来不影响其它（`health.first_failure`） |
| 6 | 登录流程改造 | `auth.rs` | ✅ 所有 handler 由 `State<ApiState>` 改为 `Extension<Arc<TenantRuntime>>`；登录在指定租户内建会话，限流按租户 |
| 7 | `/v1/auth/*` 加鉴权 | `api.rs::router` | ✅ **开放面已收缩到只剩 `/v1/health` + `/v1/tenants`**；status/accounts/login/2fa/logout 全在保护面内 |
| 8 | 每租户 token 签发 / 吊销 | `api.rs::handle_tenant_register` + `handle_tenant_token_rotate` | ✅ `POST /v1/tenants` 领取，`POST /v1/tenants/token` 轮换（token 在 `TenantRuntime` 里 `RwLock` 可运行时替换，无需重建会话） |
| 9 | 事件流隔离 | `events.rs` + `api.rs::handle_stream` | ✅ `EventHub` 每租户一个；`ConsoleEchoSink` 给每行日志打 `[label]` 前缀 |
| 10 | 数据根目录配置 | `settings.rs` + `tenants.rs::tenant_data_dir` | ✅ `--data-dir` 语义由"单 profile"变为"数据根"；每租户一个子目录，`data_dir` 字段可为旧部署覆写 |
| 11 | 掉线恢复 | `main.rs` / `api.rs::handle_health` | ✅ `/v1/health` 增加 `tenants{registered,live,running}`；无租户 / 有租户但未登录 / 服务端真故障三种状态可区分 |
| 12 | 准入控制（邀请码） | `settings.rs` + `tenants.rs` | ✅ 首租户免邀请码（bootstrap）；后续必须带 `--invite` / `VRCX_SERVER_INVITE`，否则 403 `admissionClosed` |
| 13 | 运维命令 | `main.rs::run_operator_command` | ✅ `--list-tenants` / `--revoke-tenant <id>`：只读写注册表文件，可与运行中进程共存；两者同时给出 → 拒绝（`Ambiguous`） |
| 14 | 旧部署平滑升级 | `main.rs::looks_like_pre_tenant_profile` + `tenants.rs::adopt_legacy` | ✅ 数据根里若已有 `*.db` / `remote-relay` / `storage`，首个租户**就地认领不搬文件**（避免搬一半毁掉会话）；`--token` 可把旧客户端手里的共用 token 认作首租户凭据 |
| 15 | 桌面客户端 | `src-tauri/src/commands/remote.rs` + 前端 service 层 | ✅ 新增 `app__remote_server_health/claim/rotate_token`；auth 全命令改为带 `token`；前端 `ensureRemoteTenant()` 在登录前领 token |
| 16 | 协议层 | `crates/remote-protocol/src/lib.rs` | ✅ `paths::TENANTS` / `TENANT_TOKEN`；`TenantRegistrationRequest` / `TenantCredential` / `TenantFailure{kind}`；`AuthOutcome::Authenticated.token` 改为 `Option<String>`（不再回传） |
| 17 | 启动顺序：**先 bind 再起租户** | `main.rs` | ✅ 原为 `start_all().await` 在 `TcpListener::bind` 之前。恢复 VRChat 会话是网络操作，在**连不上 VRChat 的主机上**会一直阻塞到超时 ⇒ 端口长时间不监听，外部与"进程已死"无法区分，看门狗会开始杀一个健康的服务器——正是 `/v1/health` 要消除的那种混淆。现改为 spawn 后台启动，与认领新租户的路径一致 |
| 18 | 运行时状态改为**三态** | `runtime_status.rs` / `tenants.rs` / `api.rs` | ✅ 原来 `is_running()` = "没记录失败"，于是**从未启动过的租户被算作正在运行**，`/v1/health` 会在什么都没起来时报 `ok`。现为 pending / running / failed；`TenantHealth` 增加 `starting`，`/v1/health` 在启动窗口内给 "N tenant(s) are still starting" 而不是"degraded 但无理由" |

| 19 | Android 客户端 | `android/.../data/remote/` + `SessionViewModel.kt` | ✅ 新增 `TenantClient`；`AuthClient` 每个请求带 `Authorization`；新增 `Phase.NEED_CLAIM`（**必须排在 NEED_AUTH 之前**，顺序由服务端强制：地址 → `/v1/health` 读 `tenants.registered` → `/v1/tenants` 领凭据 → 账号列表 → 登录）。拆开 `signOut()`（丢会话留租户）与 `forgetServer()`（全清），合并会在运维注册表里留死租户 |
| 20 | 客户端证书固定（① 的客户端一半） | `android/.../data/remote/TlsPinning.kt`（新增）+ 四个客户端 | ✅ SPKI 解析 → OkHttp `CertificatePinner`，含 wss；指纹只在 https 下生效，**误填或填在 http 上会拒绝连接而不是静默不固定**。22 条 JVM 单测 |

### 4.1 错误语义（客户端可据此分流，不必解析文案）

| HTTP | `kind` | 含义 |
|---|---|---|
| 401 | — | 无 token / 错 token / 租户已吊销（三者统一，避免被用来探测 token 是否存在） |
| 403 | `admissionClosed` | 已过首租户，且邀请码缺失/错误 |
| 400 | `invalidLabel` | label 去空白后为空 |
| 409 | `duplicateLabel` | label 已被占用 |
| 500 | `other` | 其它 |

> 未认证调用者对**任何**受保护路径都得到 401——包括不存在的方法和路径。
> 这是有意为之：不给未认证者枚举路由的机会。带凭据后才看得到 404 / 405。

### 4.2 验收：端到端测试

单元测试覆盖不到"真实进程是否拒绝了未认证调用"。因此有
`.workbuddy/scripts/e2e_remote_server.py`：在临时数据根上起真进程，逐个断言
开放面收缩、首租户免邀请码、后续认领需邀请码、凭据按租户隔离、轮换即吊销旧凭据、
注册表不含明文凭据、运维命令可在运行中执行、撤销在重启后生效且不删数据、
以及旧 profile 就地接管。**75 项，全绿**。

它不需要 VRChat 账号：登录之前的链路全部可测，而"未登录的租户应报 degraded"
本身就是一条要断言的用例。

---

## 5. 未决问题 → 决策记录

| # | 问题 | 决策 | 状态 |
|---|---|---|---|
| 1 | 租户怎么创建？ | **首租户免邀请码自动 bootstrap，后续需 `VRCX_SERVER_INVITE`**。未做管理员批准流程（先满足"一台服务器给几个熟人用"）。 | ✅ 已定 |
| 2 | 数据隔离粒度 | **每租户独立 SQLite 文件**（`tenants/<id>/`），隔离彻底、可单独备份/删除。单库加 `tenant_id` 列方案被否（容易漏过滤）。 | ✅ 已定 |
| 3 | 共享数据（世界/模型） | **暂不抽公共缓存**。各租户库里各存一份，接受存储与 API 配额放大，换取隔离简单。若配额成问题再议。 | ⏳ 观察 |
| 4 | VRChat API 配额 | 每租户各自打官方 API，限流按账号计，理论上互不挤占。**尚未在多租户并发下实测**。 | ⏳ 待验证 |
| 5 | 存储盘风险 | 原 OpenWrt 路线是 USB2.0 U 盘（写入抖动剧烈），**已随部署目标变更到 VPS 而作废**。VPS 上是容器盘，无此问题。 | ✅ 已消解 |
| 6 | 传输层与接入层 | 见 3.2。服务端侧齐备（loopback bind + nginx TLS + ufw + 自签证书 + SPKI 输出）；**Android 客户端固定已落地**；桌面端仍缺信任自签证书的路径，三条路线待选。 | ⏳ 差桌面端 |
| 7 | Android 客户端租户认领 | 桌面端已接，**Android 端尚未调用 `/v1/tenants`**。 | ✅ 已做（`TenantClient` + `Phase.NEED_CLAIM`） |
| 8 | Android 客户端证书固定 | 见 3.2。`TlsPinning.kt` + 四个客户端全部带 `certificatePin`；22 条单测（2026-09-18）。 | ✅ 已做 |

---

## 6. 建议顺序（不返工）与进度

**先做与加密无关的基础修复，再做多租户，最后才选传输加密方案** —— 这个顺序不会返工：

| # | 步骤 | 状态 |
|---|---|---|
| 1 | 改掉设备 root 弱口令 | ⏳ 随部署目标变更重来（VPS root 口令待改） |
| 2 | `/v1/auth/accounts` 加鉴权 | ✅ 整个 `/v1/auth/*` 已移入保护面 |
| 3 | token 从"全服务器共用"改成"每租户独立 + 可吊销" | ✅ |
| 4 | 多租户改造（第 4 节清单）——工作量主体 | ✅ 服务端 + 桌面客户端 |
| 5 | 接入层选型落地（推荐 Tailscale） | ⏳ |
| 6 | 传输加密细节（指纹固定 / 证书轮换 / bind 收窄到 127.0.0.1） | ⏳ |
| 7 | Android 客户端租户认领 | ⏳ |

> ⚠️ **战略提醒**：Android 客户端的 P1（动态 + 好友）建立在单租户数据面之上。**多租户改造应在 P1 之前做** —— 服务端与桌面端已完成，Android 端的租户认领应在 P1 走完之前补上，否则数据面接入层仍要返工。

---

## 附录 A：OpenWrt 设备实测能力（部署路线已作废，仅存档）

部署目标已从 Cudy TR3000（MT7981B / OpenWrt 24.10.5）改为 x86_64 KVM VPS，本节结论不再适用于当前目标，保留仅供将来回到路由器路线时参考。

- 内核 `6.12.95`；CPU 支持 **`aes` / `sha2` 硬件指令** → TLS 有硬件加速，性能不是顾虑。
- overlay 余 **143.6M**（装下列组件是零头）。
- ⚠️ **查包前必须先 `opkg update`**：不刷新时 `opkg list` 只返回 450 行（实际 7374），`grep wireguard` 全空，会误判成"包不存在"。
- 可用：`stunnel 5.72`、`tailscale 1.80.3`、`wireguard-tools`、`luci-proto-wireguard`、`nginx-full 1.26.1`、`zerotier 1.16.0`、`ca-bundle`。
- ⚠️ `distfeeds.conf` 里 luci 源被钉在 18.06.9（其余源 24.10.5），配置漂移会让该源查询失真。
- ⚠️ 数据盘是 USB2.0 U 盘，写入延迟剧烈波动（4MB `fsync` 实测 2.5~50s）→ 多租户并发 SQLite 会放大风险；**换 USB3 盘**。

## 附录 B：当前部署目标（KVM VPS）

- `<old-host>:52051`（SSH）= 容器 `172.16.1.102`；Ubuntu 22.04 / x86_64 / 2vCPU / 2GB。**x86_64 ⇒ 不交叉编译**，产物 `x86_64-unknown-linux-musl`。
- ⛔ **该机器当前不可用**：只有国内线路，国际流量全网阻断，`api.vrchat.cloud` 与 Tailscale 控制面（同在 Cloudflare）均不可达 ⇒ 服务端核心功能全废。
  换机器的代价很低（静态 musl 产物通用，`.workbuddy/scripts/vps-*.sh` 均已实测），**但换机器前必须先验证目标机能否访问 VRChat API**。
- 接入方案（待落地）：NAT 转发外部端口 → nginx 终止 TLS 反代 `127.0.0.1:8790`。**服务端必须绑 `127.0.0.1`** —— 公网可达主机绝不能绑 `0.0.0.0`。无域名则自签证书 + 客户端 SPKI 指纹固定（沿用 SSH host-key 信任模型）；有域名再换 Let's Encrypt。
