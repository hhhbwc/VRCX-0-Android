# VRCX-0 Android UI 规格

> 配套：`ANDROID_CLIENT_PLAN.md`（架构/排期）、`ANDROID_CLIENT_API.md`（接口）
> 本文描述**界面长什么样**。每个模块的支撑命令都对着服务端 278 条命令表核实过，
> ✅ = 服务端已支持，⚠️ = 需服务端补，❌ = 桌面专属不做。
>
> **当前范围：只做手机竖屏。平板横屏暂缓**（见第 8 节）。

---

## 1. 总览

### 1.1 骨架

```
┌──────────────────────────────────────┐
│ [头像]                   192.168.1.1 │  ← 顶栏
│                          在线 12/87  │
├──────────────────────────────────────┤
│                                      │
│           当前 Tab 内容               │
│                                      │
├──────────────────────────────────────┤
│  动态   好友   主页   收藏   个人     │  ← 底部 5 Tab
└──────────────────────────────────────┘
```

### 1.2 顶栏（你说的 + 我补的）

| 位置 | 内容 | 说明 |
|---|---|---|
| 左上 | **连接状态色点** + **当前用户头像**（圆形） | 绿=已连接 / 黄=重连中 / 灰=已断开。**瘦客户端必须显示**，否则"没数据"和"断线了"分不清。点头像进"个人"Tab |
| 右上（内侧） | **通知铃铛 + 未读角标** | 位置定在这里：紧邻 IP 左侧，形成"操作 + 状态"一组 |
| 右上（外侧） | **服务器 IP** | 长按可复制 / 更换服务器 |
| IP 下方 | **好友在线人数** | 形如 `在线 12 / 87`，来自好友状态实时统计 |

**铃铛放右上、IP 左侧的理由**：右上那块是"状态区"（IP + 在线人数），
把通知入口并进去语义一致；若放左上会跟头像抢主导航的位置，
放中间则两边都不靠。通知有 17 条服务端命令，必须有入口。

> 顶栏**不放**搜索框 —— 搜索改由各 Tab 自带，见 1.4。

### 1.3 底部 5 Tab

| # | Tab | 内容 |
|---|---|---|
| 1 | **动态** | 好友的各种变动流 |
| 2 | **好友** | 好友位置与状态 |
| 3 | **主页** | 概览：群组活动、最近访问、好友聚集地 |
| 4 | **收藏** | 世界 / 模型 / 好友 |
| 5 | **个人** | 个人资料 + 设置 |

### 1.4 搜索：每个 Tab 自带（个人页除外）

顶栏不设搜索框，改在**每个内容 Tab 的顶部常驻一个搜索框**。
"个人"页不显示（那里是自己的资料和设置，没有搜索需求）。

各 Tab 的搜索对象不同，实现也不同：

| Tab | 搜什么 | 怎么实现 |
|---|---|---|
| **动态** | 动态条目内容 | `app__feed_search_query` ✅ 服务端搜 |
| **好友** | 好友名 / 签名 | **本地过滤**——好友列表已在内存，零延迟，不用打服务端 ✅ |
| **主页** | 世界 / 群组 | `app__vrchat_search_worlds_get` / `vrchat_search_groups_get` ✅ 服务端搜 |
| **收藏** | 收藏项名字 | **本地过滤**——收藏列表已在内存 ✅ |
| **个人** | — | 不显示搜索框 |

**为什么好友和收藏用本地过滤**：这两份数据本来就要全量拉下来渲染列表，
再打一次服务端搜索纯属浪费，而且本地过滤是即时的，体验更好。
动态和主页的数据量大且可翻页，才需要服务端搜。

⚠️ 唯一的遗憾：目前**没有跨类型的统一搜索**（一个框同时搜好友+世界+群组）。
服务端 `app__quick_search_query` 还没实现，已列入服务端待补清单第一项。
补上之后可以把这四个框合成一个。

---

## 2. Tab 1 — 动态（Feed）

你说的五类**正好对应**服务端已有的 `FeedFilter` 枚举，一字不差：

| 你说的 | 对应枚举 | 显示内容 |
|---|---|---|
| 位置变动 | `GPS` | 谁从哪去了哪（含前后地点） |
| 上线 | `Online` | 上线时间 + 所在世界 |
| 下线 | `Offline` | 下线时间 |
| 状态签名变化 | `Status` | 状态（join me / ask me / busy / active）+ 签名前后对比 |
| 模型变动 | `Avatar` | 换了的模型名 + 缩略图前后对比 |
| 简介更改 | `Bio` | 简介前后对比 |

**帮你补的三类**（服务端数据里本来就有，你没提到但值得放）：

| 补充 | 枚举 | 价值 |
|---|---|---|
| 信任等级变化 | `TrustLevel` | 好友关系变亲密/变陌生，很有信息量 |
| 加好友 / 删好友 | `Friend` / `Unfriend` | 社交关系变动 |
| 正在加入 | `OnPlayerJoining` | 好友正在进哪个房间，可快速跟过去 |

**界面结构**

- 顶部：横向 Filter Chips（全部 / 位置 / 上下线 / 状态 / 模型 / 简介 …），可多选
- 主体：卡片流，按时间倒序；同一人连续变动可折叠
- 每张卡：头像 + 名字 + 变动类型图标 + 前后对比 + 相对时间
- 支持下拉刷新、上拉加载更多、点击头像进用户详情
- 实时：订阅 `realtimeFeedProjection` 事件增量插入新卡片

**命令**：`app__feed_latest_query`（首屏）/ `app__feed_rows_query`（翻页）/
`app__feed_search_query`（搜索）　✅ 全部已支持

---

## 3. Tab 2 — 好友

你说的七项分组，全部可由好友状态数据算出：

| 你说的 | 数据来源 | 备注 |
|---|---|---|
| 好友位置 | 好友记录的 `worldName` / `locationName` | ✅ |
| 在线 | `status` 字段 | ✅ |
| 具有分组的好友 | 收藏分组标签 | ✅ |
| **同一房间** | 按 `instanceId` 聚合 | ✅ 同一实例的人聚成一组 |
| 活跃中（网页端之类） | `platform` 字段 | ✅ 可区分 Web / Desktop / VR |
| 离线 | `status = offline` | ✅ |
| 关系网 | `app__mutual_graph_*` | ✅ 独立入口，点进去看共同好友图 |

**界面结构**

- 顶部：分组切换 Chips —— **同一房间 / 在线 / 活跃中 / 分组 / 离线 / 关系网**
- 主体：好友列表行 —— 头像 + 名字 + 状态色点 + 平台图标 + 当前位置
- 补充筛选：按分组筛选、按名字搜索、按状态排序
- 补充状态：**旅行中**（`isTraveling`，正在传送）、**私密**（`isPrivate`，位置不可见）
- 点击进用户详情；长按可快捷操作（收藏 / 邀请 / 查看资料）

**"同一房间"是这个 Tab 的亮点**：把当前好友按 `instanceId` 聚合，
每个房间显示"谁在那 + 几个人"，比平铺列表有用得多。

**命令**：`app__vrchat_friend_status_get`（全量）+ `realtimeFriendProjection`（增量）+
关系图系列命令　✅ 全部已支持

### 3.4 关系图（点"关系网"进入的子页面）

> 上一版我把这项写成"独立入口"就带过了，等于没设计。这里补全。

**它不是一个普通列表，是一个需要"先跑一次抓取"的功能**，这是最容易做漏的地方。

#### 数据模型

```
MutualGraphSnapshot {
  friendIds: string[]                              // 我的好友
  links:     { friendId, mutualId }[]              // 边：某好友 ↔ 某个共同好友
  meta:      { friendId, lastFetchedAt, optedOut, totalCount }[]   // 每个好友的抓取情况
}
```

注意语义：它不是"好友之间互相认识"的图，而是**「我的每个好友 ↔ 我和他之间的共同好友」**。
每个好友能算出"我和他有 N 个共同好友"。

#### 状态机（必须实现）

首次进入**数据是空的**，因为要逐个好友请求 VRChat API 才能拿到。共 5 态：

| 状态 | 界面 |
|---|---|
| `idle`（从未抓过） | 空态插图 + 说明 + **「开始分析」按钮** |
| `running` | **进度条 + 已处理 x/总数** + 「取消」按钮 |
| `cancelling` / `cancelled` | 保留已有部分数据，可重新开始 |
| `completed` | 正式展示关系图 |
| `error` | 错误原因 + 重试 |

进度字段齐全：`totalFriends` / `processedFriends` / `fetchedFriends` /
`optedOutFriends` / `failedFriends`。
实时进度订阅 `mutualGraphFetchStatus` 事件。

⚠️ 两个必须告诉用户的现实：
- **抓取很慢**——好友越多越慢（逐个请求），且 VRChat API 有频率限制。
  所以进度条和可取消不是可选项，没有的话用户会以为 App 卡死。
- **部分好友会失败**（`optedOut` = 对方隐私设置关闭了共同好友可见）。
  要如实显示"N 位好友不可分析"，别假装数据是完整的。

#### 展示形态：**图视图为主 + 列表视图为辅**（两个都要）

> 修正：我最初以"手机屏读不出来"为由砍掉了力导向图，这是错的——
> **缩放 + 平移正是为此存在的**。做回来。

顶部一个两选一的分段控件：**「图」「列表」**，默认进「图」。

---

##### 图视图（主）

**图的结构**（先理清语义，别画错）：

```
              我
        ┌─────┼─────┐
      好友A  好友B  好友C      ← 我的好友，节点大小 ∝ 共同好友数
       ╱ ╲    ╱ ╲     │
     m1  m2  m3  m4   m5      ← 共同好友（可能本身也是我的好友，要去重）
```

- 节点三类：**我**（中心，最大）、**我的好友**（大小 ∝ `meta.totalCount`）、
  **共同好友**（最小）
- 边两类：**我 ↔ 好友**（实线）、**好友 ↔ 共同好友**（细线、半透明）
- ⚠️ 一个坑：`links[].mutualId` 里的人**可能本身也是我的好友**，
  建图时要用同一个 id 去重，否则会出现两个同名节点

**交互（决定可用性的部分）**

| 手势 | 行为 |
|---|---|
| 单指拖动 | 平移画布 |
| 双指捏合 | 缩放（建议 0.3x – 5x，超出夹紧） |
| 双击 | 适应屏幕（fit） |
| 点节点 | 选中 → 底部 Sheet 显示详情 + 跳用户详情 |
| 拖节点 | 可选：手动拨开重叠的簇 |

实现用 Compose 的 `pointerInput` + `detectTransformGestures`，
缩放平移状态存 `rememberSaveable`，旋转不丢。

**两个必须做的性能措施**（几百节点逃不掉）：

1. **标签分级显示（LOD）** —— 缩放小于阈值只画圆点不画文字，
   放大后才显示名字。否则小比例下全部标签糊成一团，既难看又拖慢。
2. **视口裁剪** —— 只绘制落在可见区域内的节点和边。

**布局算法**：桌面端用 `graphology` + `forceAtlas2` + `noverlap`（JS 生态，
Android 无直接对等物），需要自己实现：

- 在**后台协程**跑迭代，每帧把位置发出来，主线程只管画
- 节点数 ≤ 500：简化 ForceAtlas2 或 Fruchterman-Reingold 的 O(n²) 就够
- 超过 500：上 Barnes-Hut 四叉树近似降到 O(n log n)
- 布局收敛后**停止迭代**（不要一直烧 CPU）；抓取过程中新节点进来再唤醒

**渐进式呈现（很自然的效果）**：因为抓取是逐个好友进行的，
可以让已抓到的好友**逐个浮现进图里**，配合进度条——
比"转圈等完再一次性显示"体验好得多。

---

##### 列表视图（辅）

图看"形状"，列表看"排名"，两者互补，且列表几乎零成本：

```
┌────────────────────────────┐
│ [图]  列表                  │  ← 分段控件
├────────────────────────────┤
│ [头像] Ren         12 位 > │  ← 点开看具体是哪 12 人
│ [头像] Mika         8 位 > │
│ [头像] Yuki         5 位 > │
│ ...                        │
│ 3 位好友不可分析（隐私设置） │
└────────────────────────────┘
```

- 每个好友一行：头像 + 名字 + 共同好友数，按数量降序
- 点行展开看具体是哪些共同好友，可继续点进详情
- 复用该 Tab 的搜索框（本地过滤）

---

## 4. Tab 3 — 主页（概览）

这是"一眼看完"的页面，三段式卡片流：

### 4.1 好友聚集地（你要的重点）

> "我的好友都聚在同一个图的，从人多到人少排序"

- 把当前在线好友按**所在世界**聚合 → 世界卡片
- 每张卡：世界名 + 缩略图 + **当前有几个好友** + 好友头像堆叠
- **按人数从多到少排序**
- 点卡片 → 看该世界详情 / 好友列表

⚠️ 说明：服务端 `app__world_friend_visits(worldId)` 是**反方向**的
（给定世界 → 哪些好友来过，是历史访问）。
你要的"现在哪些世界聚着人"需要**客户端按好友状态的世界 ID 聚合**，
数据都在，排序在客户端做，不缺接口。

### 4.2 群组活动

- 群组帖子流（`app__vrchat_group_posts_get`）✅
- 群组公告 / 事件 / 成员变动
- 卡片：群组头像 + 名 + 活动摘要 + 时间

你说"如果可能的话"——**可能**，命令已支持。

### 4.3 最近访问世界

- 我最近去过的世界，按时间倒序
- 卡片：世界缩略图 + 名 + 上次访问时间 + 去过几次
- 命令：`app__vrchat_auth_visits_get`（我的访问记录）✅ +
  `app__world_summaries_get`（批量取世界缩略图/名）✅

### 4.4 补充

- **房间历史**入口（我自己的进出记录）—— `app__instance_history_query` ✅
- **游戏日志**入口 —— `app__game_log_*`（8 条）✅
  （提醒：这类数据由**与 VRChat 同机的桌面客户端**上传，手机只读）
- 补充：页面整体下拉刷新

---

## 5. Tab 4 — 收藏

你说的三类**正好是** `FavoriteEntityKind = "avatar" | "world" | "friend"`，完全对齐。

- 顶部：三个横向分段（世界 / 模型 / 好友）
- 主体：网格（世界、模型用卡片网格）或列表（好友用列表行）
- 补充：**分组管理** —— 创建 / 重命名 / 删除收藏分组
  （`app__local_favorite_group_create` / `rename` / `delete`）✅
- 补充：新增/移除收藏（`app__vrchat_favorite_add` / `delete`）✅
- 补充：收藏导入（从别的账号或文件导入）✅

**命令**：`app__favorite_list(kind)` + `app__favorite_details_hydrate`（补全名字缩略图）
+ 分组系列命令　✅ 全部已支持

> ⚠️ **一个不做就会翻车的坑**：`app__favorite_list` 返回的 `FavoriteRow`
> 只有 `{ createdAt, groupName, userId? , avatarId?, worldId? }`——
> **只有 ID 和分组名，没有名字也没有缩略图**。
> 必须再调 `app__favorite_details_hydrate` 补全，否则列表会是一排空白行。
> 建议：拉取后立刻批量 hydrate 一次并缓存，不要等滚动到可视区才补。

---

## 6. Tab 5 — 个人（资料 + 设置）

### 6.1 资料展示

头像大图 + 名字 + 状态 + 签名 + 简介 + 信任等级 + 标签 + 徽章 + 代表群组
（`app__vrchat_user_get` / `app__vrchat_user_profile_get` ✅）

### 6.2 编辑个人资料（你特别要求的功能）

**服务端命令齐全，不需要补。** 而且可编辑的比你我能想到的都多——
以下是从 `CurrentUserUpdateRequest` 读到的**完整字段清单**，不是猜的：

| 分组 | 可编辑项 | 字段 |
|---|---|---|
| **状态** | 在线状态 | `status`（join me / ask me / busy / active / offline） |
| | 状态签名 | `statusDescription` |
| **简介** | 个人简介 | `bio` |
| | 简介链接 | `bioLinks[]` |
| |  pronouns | `pronouns` |
| **形象** | 头像 | `userIcon` |
| | 资料图覆盖 | `profilePicOverride` |
| | 资料背景 | `backgroundType`：纯色 / 渐变（上下色）/ 纹理 |
| **主页** | 主页世界 | `homeLocation` |
| **开关** | 允许他人复制我的模型 | `allowAvatarCopying` |
| | 允许 Boop | `isBoopingEnabled` |
| | **关闭"共同好友"对我可见** | `hasSharedConnectionsOptOut` |
| | Discord 好友可见 | `hasDiscordFriendsOptOut` |
| **其他** | 内容过滤 | `contentFilters[]` |
| | 标签增删 | `vrchat_current_user_tags_add` / `_remove` ✅ |
| | 徽章 | `vrchat_current_user_badge_update` ✅ |
| | 个人装饰 | `vrchat_media_profile_decoration_equip` / `_unequip` ✅ |

**一个值得指出的闭环**：`hasSharedConnectionsOptOut`（关掉共同好友对我可见）
正是关系图里 `optedOut` 的另一面——**你在这里关掉自己，别人在关系图里就看不到你**。
这两个功能在界面上应该互相说清楚，否则用户会困惑"为什么分析不到这位好友"。

> ⚠️ 重要提醒：这些改动全部走 VRChat 官方 API，**有频率限制**（短时间内反复改会被限流）。
> 编辑页必须有明确的保存态反馈："保存中 / 已保存 / 失败可重试"，
> 且**不要做实时保存**（每敲一个字就发一次请求必然被限流）——
> 应当是显式点"保存"才提交。

### 6.3 设置（你要求放这里）

**设置没丢**，就在个人页里（往下滚）。以下是**真实存在的配置项**——
从 `configKeys.ts` 的实际键名读出，不是编的；能存是因为服务端有
`app__config_set_values` / `app__config_list_values` ✅。

| 分组 | 项目 | 配置键 |
|---|---|---|
| **连接**（最重要） | 服务器地址、Token、测试连接、重拉能力表 | `serverAddress` `serverToken` `remoteModeEnabled` |
| **账号** | 当前账号、切换账号、登出 | — |
| **外观** | 深浅色、主题色 / 动态取色、紧凑度、降低动效、隐藏昵称、显示实例 ID、显示年龄限制房间、12 小时制、字体 | `ThemeMode` `themeColor` `tableDensity` `reducedMotionAndBlur` `hideNicknames` `showInstanceIdInLocation` `isAgeGatedInstancesVisible` `dtHour12` `fontFamily` `cjkFontPack` `ZoomLevel` |
| **动态 Feed** | 隐藏私密、隐藏设备、隐藏在线时长、隐藏删好友、极简模式、时间显示方式 | `hidePrivateFromFeed` `hideDevicesFromFeed` `hideUptimeFromFeed` `hideUnfriends` `minimalFeed` `feedTimeDisplayMode` `feedViewMode` |
| **收藏** | 三类各自的排序与密度 | `FavoritesFriendSort` `FavoritesWorldSort` `FavoritesAvatarSort` `Favorites*Density` |
| **数据** | 游戏日志采集、Feed 持久化、头像历史自动清理、**匿名遥测开关** | `gameLogDisabled` `feedPersistenceDisabled` `avatarFeedPersistenceDisabled` `avatarAutoCleanup` `anonymousUsageTelemetry` |
| **备份与恢复** | 备份设置、手动备份、恢复 | `profile_backup_*` / `profile_restore_*`（13 条）✅ |
| **关于** | 版本、协议版本、开源许可 | — |

> **这里必须如实说明一个现实**：设置页里「通知」这一组**几乎没东西可做**。
> 桌面端的通知设置项大多是桌面专属的——桌面 Toast、TTS 朗读、
> VR 头显通知（HMD）、webhook、任务栏角标、Overlay——手机上全都不存在。
>
> 更关键的是：**通知过滤与免打扰的保存命令服务端还没实现** ⚠️
> （`app__notification_activity_filters_set`、`app__notification_do_not_disturb_mode_set`
> 均不在 278 条内）。所以本轮通知设置只能放"通知显示样式"
> （`notificationLayout` / `notificationOpacity` / `notificationTimeout`），
> 过滤与免打扰要等服务端补齐。我不会画一堆存不住的开关充数。

**桌面专属、Android 不做**：启动项、托盘、截图助手、VRChat 缓存清理、
注册表备份、进程监控、Overlay、TTS、webhook、VR 头显通知。

### 6.4 补充

- 个人页顶部可放**我的头像库**入口（`app__my_avatars_get`）✅
- 补充：**自己的房间历史 / 游戏日志**入口
- 补充：备份与恢复（服务端 13 条 `profile_backup_*` / `profile_restore_*` 命令已支持）

---

## 7. 通用页面（你没提但必须有）

| 页面 | 说明 |
|---|---|
| **用户详情** | 从任何列表点头像进入：资料、共同好友（`app__user_mutual_friends_list_get` ✅）、备忘（`app__memo_*` ✅）、收藏、邀请、审核 |
| **世界详情** | 世界信息、好友访问记录（`app__world_friend_visits` ✅）、收藏 |
| **群组详情** | 群组信息、成员、帖子、审核 |
| **连接/登录页** | 填服务器地址（可选填**证书指纹**，折叠在"服务器用了自签证书"里）→ 领取凭据（设备名 + 邀请码）→ 选账号/密码 → 2FA |
| **空状态 / 错误态 / 骨架屏** | 三态都要有，尤其"服务端无数据"和"断线"要区分开 |

---

## 8. 平板横屏 —— 暂缓

**本轮只做手机竖屏**，横屏不在此次范围。以下差异先记下来，等竖屏做完再排：

| 维度 | 手机竖屏（本次做） | 平板横屏（暂缓） |
|---|---|---|
| 导航 | **底部 5 Tab** | 侧边 Navigation Rail（Material 3 规范） |
| 列表 + 详情 | 点击跳转，详情占满屏 | 左列表 + 右详情双栏并排 |
| 动态流 | 单列卡片 | 双列卡片 |
| 收藏 | 2 列网格 | 3–4 列网格 |

**预先提醒一个实现约束**：横屏时底部 5 个大 Tab 会拉得很宽，
所以将来横屏多半要改成侧边 Rail。这意味着导航组件应当
**一开始就写成可切换的**（用 `NavigationSuiteScaffold`），
而不是硬编码一个 `NavigationBar`——否则横屏时得重写。
这是唯一需要现在就为横屏留的口子，其余等竖屏完成后再说。

---

## 9. 我帮你补的清单（汇总）

你没提到但建议加的：

1. **连接状态指示**（顶栏色点）—— 瘦客户端刚需
2. **通知入口**（顶栏铃铛 + 角标）—— 否则 17 条通知命令无处安放
3. **搜索框**（顶栏下方）—— 手机端刚需
4. **动态的另三类**：信任等级变化、加/删好友、正在加入
5. **好友的另两态**：旅行中、私密位置
6. **"同一房间"聚合** —— 按 `instanceId` 分组，不只是按世界
7. **收藏分组管理**（创建/重命名/删除）
8. **通用详情页**（用户 / 世界 / 群组）
9. **空状态 / 错误态 / 骨架屏**
10. **编辑资料的操作反馈**（因 VRChat API 有限流）

---

## 10. 命令映射速查

| Tab | 主要命令 | 状态 |
|---|---|---|
| 顶栏 | `backend_runtime_combined_snapshot_get`（在线人数/水合） | ✅ |
| 动态 | `feed_latest_query` / `feed_rows_query` / `feed_search_query`，事件 `realtimeFeedProjection` | ✅ |
| 好友 | `vrchat_friend_status_get`，事件 `realtimeFriendProjection`，`mutual_graph_*` | ✅ |
| 主页 | `vrchat_auth_visits_get`、`world_summaries_get`、`vrchat_group_posts_get`、`instance_history_query` | ✅ |
| 收藏 | `favorite_list` / `favorite_details_hydrate` / 分组系列 / `vrchat_favorite_add`·`delete` | ✅ |
| 个人 | `vrchat_user_get` / `vrchat_user_profile_get` / `current_user_*_update` / `profile_backup_*` | ✅ |
| 搜索 | `vrchat_search_users/worlds/groups` | ✅（分类型） |
| 搜索 | `quick_search_query`（统一搜索） | ⚠️ 服务端待补 |
| 通知 | `notification_*`（17 条） | ✅（界面入口待定） |
| 通知偏好 | `notification_activity_filters_set` / `do_not_disturb_mode_set` | ⚠️ 服务端待补 |

---

## 11. 决策记录

| 项 | 决定 |
|---|---|
| 通知入口 | **顶栏右上、IP 左侧**，铃铛 + 未读角标。不占 Tab |
| 搜索 | **每个内容 Tab 自带搜索框**（动态/好友/主页/收藏），个人页不显示 |
| 平板横屏 | **本轮不做**，只做手机竖屏 |
| 竖屏导航 | 底部 5 Tab，照你说的 |

**唯一为横屏预留的口子**：导航组件现在就用 `NavigationSuiteScaffold`
写，别硬编码 `NavigationBar`——否则将来横屏改侧边 Rail 要重写。
