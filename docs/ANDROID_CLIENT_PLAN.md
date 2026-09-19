# VRCX-0 Android 客户端实施计划

> 配套文档：
> - `docs/ANDROID_CLIENT_API.md`（接口参考，由脚本生成，勿手改）
> - `docs/ANDROID_UI_SPEC.md`（**界面规格**：五个 Tab 的布局与内容）
>
> 本文是**计划**，尚未开始编码。技术栈、UI 风格、复用策略已定，见第 9 节。

---

## 1. 定位

Android 客户端是**纯瘦客户端**：

- 不持有 VRChat 会话、不做业务计算、不跑本地数据库
- 所有数据来自用户自部署的 VRCX-0 服务端（路由器 / NAS 常驻）
- 服务端**已完成 278 条命令**（桌面全量 498 条），覆盖绝大多数数据面

一个必须提前说清的约束：**游戏日志 / 房间历史的数据由"与 VRChat 同机的桌面客户端"上传**，
手机只是读取方。若服务端查不到这类数据，不是接口坏了，是还没有桌面客户端上报过。

---

## 2. 技术栈（已定：A）

**原生 Kotlin + Jetpack Compose。**

选它的实际理由是"效果最好"这一条：

- 平板/手机双模式的自适应布局，**只有 Compose 有官方现成答案**（`NavigationSuiteScaffold`
  + `ListDetailPaneScaffold`）。WebView / 跨平台方案都要自己实现这套切换。
- 本项目的数据模型是"命令请求 + 事件流订阅"，Kotlin `Flow` + WebSocket 表达最直接。
- 长列表（feed / 好友 / 通知）在原生 `LazyColumn` 上的滚动与内存表现，
  是 WebView 方案追不上的。

代价明确：现有 Vue/TS 前端不复用，Android 端独立一套代码。

<details>
<summary>被否决的两个备选（点击展开）</summary>

| 方案 | 优点 | 否决理由 |
|---|---|---|
| B. WebView 套壳复用现有 Vue 前端 | 省去重写 | 非原生体验；且平板/手机自适应要回到 Web 生态自己实现，拿不到 Compose 的官方答案 |
| C. Compose Multiplatform | 一套 Kotlin 出 Android/iOS/桌面 | 目前无 iOS 计划，跨平台收益打折 |

</details>

---

## 3. UI 参考（GitHub 调研结论）

### 3.0 先分清：用"组件库"还是"App 模板"

这两件事常被混为一谈，但结论完全相反：

| | 是什么 | 采用 |
|---|---|---|
| **UI 组件库**（Material 3） | Google 官方现成零件：按钮、卡片、导航栏、列表、对话框、日期选择器 | **直接用**。装了 Compose 就自带，一个像素都不用自己画 |
| **App 模板 / 成品界面** | 网上现成的 dashboard、社交 App 源码 | **不套**。它们的假数据模型（用户/订单/图表）与 VRCX 的好友/动态/世界对不上，改结构比从零写更累 |

类比：跟桌面端现在用 shadcn/ui 是一回事——零件用现成的，界面仍是 VRCX 的样子。
Android 端这个"零件库"就是 Material 3。

下面列的 ReadYou / Reply 是**参考布局写法与视觉风格**，不是搬它们的界面。

**许可证无阻碍**：VRCX 为 GPL-3.0-only；Reply 与 Material 3（Compose）为 Apache-2.0
（与 GPLv3 单向兼容）；ReadYou、Seal 为 GPL-3.0。均可使用。

### 3.1 设计语言（已定）

| 参考 | 说明 |
|---|---|
| **Material 3 (Material You)** `m3.material.io` | 简约扁平、留白克制——你要的"扁平简约" |
| **Material 3 Expressive** `m3.material.io/expressive` | Google 2025 主推：更大圆角、更活泼的排版与动效——"现代感"的官方答案，但偏张扬 |

**已定：以 Material 3 简约扁平为底，少量吸收 Expressive 的圆角与排版。**

理由是 VRCX 是信息密集的社交客户端（好友列表、动态 feed、通知），
过度装饰会挤压内容密度。

### 3.2 布局骨架（最关键）

**`android/compose-samples` → Reply**
https://github.com/android/compose-samples

Reply 是 Google 官方专门演示**自适应布局**的样本，正中"平板横屏 / 手机竖屏互相切换"：

- `NavigationSuiteScaffold` —— 导航在**底部栏 / 侧边 Rail / 抽屉**之间按窗口尺寸自动切换
- `ListDetailPaneScaffold` —— 列表+详情：窄屏分屏跳转，宽屏**左右双栏并排**

这套组合直接就是本项目导航的答案，建议照抄结构。

### 3.3 视觉风格

- **ReadYou**（7.5k⭐）`https://github.com/ReadYouApp/ReadYou`
  RSS 阅读器，Material You + Compose。扁平、列表流沉浸，
  形态最接近 VRCX 的「好友列表 / 动态 feed / 详情页」。**首选风格参考**。
- **Seal** `https://github.com/JunkFood02/Seal`
  MD3 高颜值，单 Activity + Compose Navigation，交互简洁。

**组合建议**：骨架学 Reply，视觉学 ReadYou，圆角/排版点缀 Expressive。

---

## 4. 平板（横屏）/ 手机（竖屏）双模式

服务端**完全不感知布局**，模式切换纯客户端实现。

| 维度 | 手机竖屏 | 平板横屏 |
|---|---|---|
| 导航 | 底部导航栏 | 侧边 Navigation Rail / 抽屉 |
| 列表 + 详情 | 分屏跳转（详情占满屏） | 双栏并排（左列表右详情） |
| 内容密度 | 单列 | 多列网格 / 更宽卡片 |

四条要点：

1. **用 `WindowSizeClass` 判定形态**，不要靠屏幕英寸或固定 dp 阈值。
   宽度等级 Compact / Medium / Expanded 是官方口径，折叠屏、分屏、外接屏都能正确工作。
2. **模式必须能手动切换并持久化**（用户偏好优先于自动判定），存 `DataStore`。
   你明确要求两种模式可互相切换——所以自动判定只是默认值，不是唯一值。
3. **旋转 / 切换时保留状态**：列表滚动位置与选中项用
   `rememberSaveable` + ViewModel 持有选中 id，避免重建丢状态。
4. **列表分页懒加载**：feed、好友列表、通知返回量可能很大，
   配合 `Paging3`，或手动传分页参数。

---

## 5. 屏幕矩阵（源自桌面端 31 条路由）

从 `src/src/app/routes.tsx` 提取全部路由，按服务端命令覆盖度判定 Android 可行性。
命令覆盖度来自服务端实际支持的 278 条命令，不是估计。

### ✅ 可直接实现（服务端命令已就绪）

| 屏幕 | 路由 | 支撑命令 |
|---|---|---|
| 登录 | `/login` | `/v1/auth/*`（accounts → login → 2fa） |
| 动态 Feed | `/feed` | `app__feed_latest_query` / `rows_query` / `search_query`（4） |
| 好友位置 | `/friends-locations` | `app__vrchat_friend_status_get` + `realtimeFriendProjection` |
| 好友列表 | `/social/friend-list` | 同上 + `app__backend_runtime_combined_snapshot_get` |
| 游戏日志 | `/game-log` | `app__game_log_*`（8） |
| 房间历史 | `/instance-history` | `app__instance_history_query` + `instance_activity_*` |
| 通知 | `/notification` | `app__notification_*`（17） |
| 收藏（好友/世界/头像/群组） | `/favorites/*` | `app__favorite_*`（10） |
| 我的头像 | `/my-avatars` | `app__my_avatars_get` + `app__avatar_*`（20） |
| 好友动态记录 | `/social/friend-log` | `app__friend_log_*`（5） |
| 活动 / 统计 | `/activity` | `app__activity_*`（3）+ `app__avatar_usage_ranking` + `app__world_summaries_get` |
| 共同好友图 | `/charts/mutual` | `app__mutual_graph_*`（4，含 `snapshot_get`） |
| 浏览历史 | `/browse-history` | `app__browse_history_*`（6） |
| 搜索（分类型） | `/search` | `app__vrchat_search_users/worlds/groups`（5） |
| 群组 | `/tools/my-groups` 等 | `app__vrchat_group_*`（31）+ `app__group_*`（6） |
| 库存 / 相册 / Prints | `/tools/inventory`、`/tools/gallery` | `app__vrchat_media_*`（21） |
| 世界 / 实例 | （用户/世界详情页） | `app__vrchat_world_*`（7）+ `app__vrchat_instance_*`（5） |
| 备忘 | （用户详情内） | `app__memo_*`（8） |
| 外部 API | （头像搜索 / 版本） | `app__external_api_*`（5） |
| 设置 | `/settings` | `app__config_*`（3）+ 本地偏好 |

### ⚠️ 部分可用（需服务端补齐才能完整）

| 屏幕 | 缺失 | 影响 |
|---|---|---|
| `/search` 全局搜索 | `app__quick_search_query` | 只能分类型搜（用户/世界/群组），没有统一搜索框 |
| `/social/moderation` | `app__get_/set_vrchat_user_moderation` | 用户审核读不到也改不了 |
| 通知偏好 | `notification_activity_filters_set` / `do_not_disturb_mode_set` | 过滤与免打扰设置无法保存 |
| 群组排序 | `vrchat_group_order_get/set` | 自定义排序丢失 |

> `/dashboard/:id` 是**客户端配置驱动**的面板看板（布局存本地 config，
> 面板内容由上述命令填充），因此不缺服务端接口，但属次级功能，排 P4。

### ❌ 桌面专属，Android 不做

- `/player-list`（当前房间实时玩家）—— 依赖**本机游戏进程与日志**，手机上无意义
- `/tools/vrchat-log`、`/tools/screenshot-metadata` —— 读本机 VRChat 日志与截图元数据
- 窗口 / 托盘 / 截图 / 进程监控 / 启动游戏 / 本地文件与注册表 / VR 覆盖层 / 助手与 MCP / TTS / 应用自更新
- `/themes`、`/community-themes` —— **不是不做，是纯客户端**：主题只是本地 UI 皮肤，无需服务端

---

## 6. 架构设计

```
UI (Compose)
  └─ ViewModel  (StateFlow<UiState>)
       └─ Repository  (领域语义，缓存 + 合并)
            ├─ TenantClient          POST /v1/tenants（免鉴权：领凭据 / 探活）
            ├─ CommandClient         POST /v1/command
            ├─ AuthClient            /v1/auth/*（需凭据）
            └─ EventStreamClient     WS /v1/stream
                 └─ 事件 → Flow → Repository 增量合并
```

分层职责：

| 层 | 职责 |
|---|---|
| `TenantClient` | 唯一免鉴权入口：`GET /v1/health` 探活 + 读 `tenants.registered` 判断是否需要邀请码；`POST /v1/tenants` 领取本机凭据；`POST /v1/tenants/token` 轮换 |
| `CommandClient` | 唯一命令出口：`invoke(command, args)`。内部查启动时拉的 `supported` 表，不在表内直接抛错（不静默降级） |
| `AuthClient` | 登录三步流。**每个请求都带租户凭据**——`/v1/auth/*` 全在保护面内；凭据存 `ServerConfigStore` |
| `EventStreamClient` | WebSocket；解析 `hello/event/lagged` 三态帧；退避重连 1s→30s |
| Repository | 首屏全量查询 → 缓存 → 事件增量合并 → 对外暴露 `Flow` |
| ViewModel | 只做状态编排，不含网络细节 |

### 6.1 四个最容易踩的坑

1. **`/v1/health` 不能用来验证凭据**。它免鉴权，错 token 照样返回 200，所以"能连上"和"凭据有效"是两件事。验证凭据只能靠 `GET /v1/auth/status`（401 = 凭据失效/被吊销）。
2. **认领必须排在登录之前**。服务端 `/v1/auth/*` 全部在保护面内，凭据来自 `POST /v1/tenants`，而不是登录成功的返回值。顺序是**地址 → 认领 → 账号列表 → 登录**；账号列表本身也需要凭据，所以 label 不可能取自 VRChat 用户名（取设备名 `Build.MODEL`）。
3. **参数形态有两种**，按命令而定：166 条走 `args.input` 整体对象，
   75 条是扁平命名参数，37 条无参。传错会得到 `missing \`input\` argument`。
   **务必照 `ANDROID_CLIENT_API.md` 第 5 节的清单区分**——这是最高频的错误来源。
4. **流帧是扁平的**：`{"kind":"event","event":"...","payload":...,"seq":N}`（`tag="kind"`）。
   按嵌套结构解析会导致所有事件静默丢弃（桌面客户端曾踩过，现象是"连上了但没反应"）。

> 重连后必须全量对账：服务端不重放历史事件。收到 `lagged`，
> 或重连成功，都要主动调一次 `app__backend_runtime_combined_snapshot_get` 重新对齐。
> 事件里也有 `realtimeProjectionSync` 可作为对账信号。

### 6.2 启动流程

```
0. 读本地 serverAddress / tenantToken / certificatePin
1. GET /v1/health          → 探活 + 拉 supported 表 + 读 tenants.registered
2. 无 tenantToken          → Phase.NEED_CLAIM
                              POST /v1/tenants {label, inviteCode?}
                                ├─ 首租户免邀请码；已有租户则 403 admissionClosed
                                ├─ 409 duplicateLabel / 400 invalidLabel
                                └─ 成功 → 存 token
   有 tenantToken          → GET /v1/auth/status 校验（401 → 清 token 回到 NEED_CLAIM）
3. 已认证?  READY : NEED_AUTH
                           NEED_AUTH → GET /v1/auth/accounts（需凭据）
                                     → 选账号 / 密码登录 → 2FA
4. 连 /v1/stream?token=...（同样带证书固定）
5. 全量水合：combined_snapshot + 各屏首屏查询
```

> ⚠️ **登出 ≠ 放弃凭据**。`AUTH_LOGOUT` 只丢 VRChat 会话，租户与凭据保留（回到 `NEED_AUTH`）；
> 只有"忘记服务器"才清地址+凭据。否则每次登出都会在运维的 registry 里留一个死租户。
>
> 注：`supported` 表**只在启动时拉一次**。将来服务端加了命令，
> Android 端应提供"重新拉取能力"的入口，避免重启才能用上新命令。

#### 6.2.1 证书指纹（服务器用自签证书时）

只有把服务端放在 TLS 后面（nginx / stunnel）时才需要。局域网明文直连**留空即可**。

- 来源：`vps-tls-setup.sh` 打印的 **SPKI SHA-256（Base64）**。
  ⚠️ 它同时打印另一枚"证书指纹"（`openssl -fingerprint`，十六进制带冒号）——那**不是**用来固定的，
  `TlsPinning.parse` 认得这个形状并会明确报错，不会让你一直连不上还不知道为什么。
- 落地：`TlsPinning.apply()` 装 OkHttp `CertificatePinner`，`CommandClient` / `AuthClient` /
  `TenantClient` / `EventStreamClient`（含 **wss**）四个都带，事件流不能漏。
- 两条硬规则（都有单测）：
  1. **指纹只在 `https://` 地址下生效。** 填在 http 地址上会直接报错——否则用户以为加密了，
     而实际什么都没校验。
  2. **解析不出来就拒绝连接**，不做"静默不固定"。安静地降级到不固定，比一开始就不固定更坏。
- 地址写法：`https://主机[:外部端口]`。`normalizeBaseUrl` 对 https **不补 `:8790`**（那是明文口，
  且 nginx 听的是 443）；只有裸主机 / `http://` 才补默认端口。
  ⇒ NAT 映射应当是 **外部端口 → 容器 443**，`8790` 永不对外。

---

## 7. 分阶段路线

**本轮只做手机竖屏**，平板横屏不在此次范围（见 UI 规格第 8 节）。

> **P0 已完成并编译出 APK**（`android/app/build/outputs/apk/debug/app-debug.apk`）。
> 构建：`./gradlew assembleDebug`（或 `.workbuddy/scripts/build-android.sh`）。
> 需要 JDK 17 与 `ANDROID_HOME=C:\Android\Sdk`，详见 `android/README.md`。

| 阶段 | 目标 | 产出 |
|---|---|---|
| **P0 骨架** | 传输层 + 认证 + 空壳导航 | `CommandClient` / `TenantClient` / `AuthClient` / `EventStreamClient`；登录页能领到租户凭据并登录；底部 5 Tab 空壳；顶栏头像 + 连接色点 + IP + 在线人数 |
| **P1 动态 + 好友** | 两个最高频 Tab | 动态四类筛选卡片流 + 实时插入；好友分组（同房间/在线/活跃/离线）；两 Tab 各自搜索框；**关系图**（抓取状态机 + 力导向图画布 + 缩放平移 + 列表视图） |
| **P2 主页 + 收藏** | 概览与收藏 | 主页好友聚集地（按人数排序）+ 群组活动 + 最近访问世界；收藏三类分段 + 分组管理 |
| **P3 个人页** | 资料 + 编辑 + 设置 | 资料展示；**编辑个人资料**（简介/状态/标签/徽章/装饰，含保存态反馈）；设置（连接/账号/外观/数据） |
| **P4 通知 + 详情** | 顶栏铃铛与通用详情页 | 通知列表 + 未读角标；用户/世界/群组三个通用详情页 |
| **P5（后置）** | 平板横屏 | 侧边 Rail；列表详情双栏。**竖屏做完再排** |

**P0 就要注意的一件事**：导航组件直接用 `NavigationSuiteScaffold` 写，
别硬编码 `NavigationBar`。虽然本轮只出竖屏底部栏，但用这个组件写，
将来 P5 改侧边 Rail 是改配置而非重写。

每阶段的验收都用**真实服务端**跑，不 mock——服务端已在路由器上常驻，
`http://192.168.1.1:8790/v1/health` 可直接验证。

---

## 8. 服务端待补清单（按优先级）

这些是 Android 想做完整就必须在服务端补的，独立于客户端排期：

| 优先级 | 命令 | 说明 |
|---|---|---|
| 高 | `app__quick_search_query` | 全局搜索，手机端高频 |
| 高 | `app__get_/set_vrchat_user_moderation` | 用户审核读写 |
| 中 | `app__notification_activity_filters_set`、`do_not_disturb_mode_set` | 通知偏好持久化 |
| 中 | `app__vrchat_group_order_get/set` | 群组排序 |
| 中 | `app__ancillary_runtime_snapshot_get` | 登录水合；缺它界面可能卡加载态 |
| 低 | 媒体字节上传（emoji / gallery / icon） | 需要新增 multipart / 二进制通道，是独立一块工作 |
| 不做 | `app__player_list_current_snapshot`、`app__vrchat_instance_join` | 依赖本机游戏，手机上无意义 |

---

## 9. 决策记录

| 项 | 决定 | 依据 |
|---|---|---|
| 技术栈 | **原生 Kotlin + Jetpack Compose** | 效果最好；自适应布局有官方现成答案 |
| UI 风格 | **简约扁平为主 + 少量 Material 3 Expressive** | 信息密集，不被装饰挤压 |
| UI 复用策略 | **用 Material 3 组件库，不套成品 App 模板** | 见 3.0 节 |
| 当前状态 | **停在文档，不开始编码** | 等 UI 想清楚再动手 |

---

## 10. 维护约定

- 接口文档**不要手改**：服务端每次迁移命令后重跑
  `python3 .workbuddy/scripts/gen_android_api_doc.py`
- 服务端改流帧格式时，必须同步检查客户端的帧解析（历史上出过一次静默丢事件）
- 第 2、3、9 节已落定；**界面细节见 `docs/ANDROID_UI_SPEC.md`**
