# Android UI 精修 — 动效与排版

> 对应桌面版 [UI_REFINEMENT_PLAN.md](./UI_REFINEMENT_PLAN.md)。
> 目标：把「生硬的硬切」换成有节制的动效，同时不牺牲这台设备最要紧的东西—— 242 行好友列表的滚动帧率。

## 一、诊断：先量，再改

改之前先扫了 53 个 Kotlin 文件，结论和桌面版**完全不同**：

| 项目 | 实测 | 判断 |
|---|---|---|
| `MaterialTheme.typography.*` 使用 | 135 处 | 健康 |
| 硬编码 `sp` | 1 处 | 健康 |
| 字体字重（`SemiBold`/`Medium`） | 13 / 7 处 | 健康 |
| `VrcxMotion` 引用 | **9 处，仅 3 个文件** | 严重不足 |
| `screenEnter`/`screenExit`/`tabTransitionSpec` | **0 处引用（死代码）** | 需要处理 |
| `animateColorAsState` | **0** | 缺失 |
| `Crossfade` | **0** | 缺失 |
| `animate*AsState` | 2 | 缺失 |
| `AnimatedVisibility` | 2 | 缺失 |

**关键结论：Android 端的问题不是排版，是动效。**

排版层（`VrcxTypography`）已经写得很扎实——字号、行高、负字距、字重都成体系，`sp` 硬编码几乎为零。真正的问题在于：`Motion.kt` 里那套设计得很好的 spring token 对象，**几乎没人用**；四个 `screenEnter`/`tabExit` 之类的辅助函数定义了却从没被调用过。

所以这次不去动排版（它没问题），专攻动效落地。

## 二、红线：不能碰的东西

`MainScreen.kt` 里有一段长注释，解释了 tab 内容**故意**不做 `AnimatedContent`：用 `SizeTransform` 包住一个 242 行的 `LazyColumn`，会导致每帧重新测量全部子项。这是一个已经想清楚的性能决策，**不能撤销**。

这条约束直接决定了新动效原语的形状：

- `StateCrossfade` 用 `AnimatedContent` 但**不带** `SizeTransform`
- `StaggeredReveal` 只动 alpha 和 scale，不动尺寸/位置
- `AnimatedStatusDot` 只动颜色和一个 12dp 圆点的 scale

一句话：**只动 alpha / scale / color，绝不动会触发列表重新测量的属性。**

## 三、改动清单

### 3.1 `ui/theme/Theme.kt` — 排版落地

`typography = Typography()`（Material 默认值）换成真实的 `VrcxTypography`，配套 `VrcxLineHeightStyle`，标题加负字距：

- `headlineSmall` 22sp/28sp/-0.2sp SemiBold
- `titleLarge` 20sp/26sp/-0.2sp SemiBold
- `titleMedium` 16sp/22sp SemiBold
- `titleSmall` 14sp/20sp SemiBold
- `bodyLarge` 16sp/22sp、`bodyMedium` 14sp/19sp、`bodySmall` 12sp/16sp/+0.1sp
- `labelLarge` 14sp/19sp Medium、`labelMedium` 12sp/16sp/+0.3sp、`labelSmall` 11sp/15sp/+0.4sp

**没有下调任何正文字号**——数据密度是这个 app 的核心价值，动它会伤到用户真正在看的东西。

### 3.2 `ui/theme/Motion.kt` — 补齐 token

在原有 spring token 基础上新增：

```kotlin
const val ColorDurationMs = 180   // 状态点换色
const val StateDurationMs = 140   // 状态切换
const val StaggerMs = 30          // 错开步长
fun staggerDelayMs(index: Int, maxDelayMs: Int = 240): Int   // 上限 240ms
const val PulseDurationMs = 1400  // 呼吸脉冲（原有）
```

上限 240ms 是刻意的：第 9 行之后的元素同时入场，而不是让用户等一串 30ms 的累积延迟。

同时**删掉了从没被引用的** `screenEnter`/`screenExit`/`tabEnterTransition`/`tabExitTransition`/`tabTransitionSpec`——留着死代码会诱使后来者重新加回被明确否决的 scale 动画。

### 3.3 `ui/components/VrcxMotion.kt` — 新建，六个复用原语

所有原语统一读 `LocalReducedMotion`，开启「减少动画」时退化为瞬时切换。

| 原语 | 用途 | 关键取舍 |
|---|---|---|
| `AnimatedStatusDot` | 好友在线状态点 | 换色 180ms + 瞬时 scale 下沉到 0.7 再弹回，由颜色变化本身驱动 |
| `AnimatedCountBadge` | 未读角标 | `scaleIn(0.5f)` + fadeIn，出现时抓眼睛 |
| `StaggeredReveal` | 列表/区块入场 | 只动 alpha + scale(1-lift*0.02)，容器级调用一次 |
| `StateCrossfade` | 加载 → 完成 / 空 → 有 | `AnimatedContent` **不带** `SizeTransform` |
| `ExpandableSection` | 折叠区、筛选行 | 用 `expandVertically`，因为这里是固定几个 chip 而非列表 |

`AnimatedStatusDot` 的 scale 由 `LaunchedEffect(color)` 驱动，而不是再传一个标志位——调用方只传一次状态，任何变化都得到同样的视觉确认。

### 3.4 接线

| 文件 | 改动 |
|---|---|
| `VrcxRoot.kt` | 在既有的 `CompositionLocalProvider` 里补 `LocalReducedMotion provides settingsValues.reducedMotion` |
| `ui/screens/ProfileScreen.kt` | 补上**缺失的** `SettingKey.REDUCED_MOTION` 开关行（枚举和 `apply()` 分支早就有，只有 UI 行没写——即这个设置此前在界面上**根本够不着**） |
| `ui/screens/FriendsScreen.kt` | `FriendRow` 的硬切状态点 → `AnimatedStatusDot` |
| `ui/components/TopBar.kt` | `NotificationBell` 的硬切角标 → `AnimatedCountBadge`（保留 `99+` 封顶） |
| `ui/screens/FavoritesScreen.kt` | 加载/内容硬切 → `StateCrossfade`，键为 snapshot 本身 |
| `ui/screens/FeedScreen.kt` | 失败/空/有内容三态硬切 → `StateCrossfade`，键为三值枚举 `FeedViewState` |

**关于 `StateCrossfade` 的键**：两个页面都刻意**不**用行列表本身作键。列表每次加载都会被替换成新对象，用它作键会导致滚动中途重播淡入。`FavoritesScreen` 用 snapshot 对象（只有 null → 非 null 时才变），`FeedScreen` 用三值枚举。

## 四、验证

### 全链路

| 步骤 | 结果 |
|---|---|
| `:app:compileDebugKotlin` | **BUILD SUCCESSFUL**（分 7 次增量，逐个修正类型错误） |
| `:app:testDebugUnitTest` | **83 例 / 0 失败 / 0 错误**（基线完全保持，11 个测试类） |
| `:app:assembleRelease` | **BUILD SUCCESSFUL** in 1m43s → `app-release.apk` 1.99 MB |
| 真机安装 `adb -s a6943611 install -r` | **Success**（debug keystore 签名，覆盖 debug 包且保留 token） |
| 冷启动 | **0 FATAL**（logcat 仅 OEM/ROM 噪声） |
| 真实数据渲染 | 在线 32 / 242，动态、好友列表（242 行）正常 |
| 「减少动画」开关 | UI 可达，点击后 `checked=true`（其余 4 个开关保持 false，证明命中正确行） |

### ⚠️ APK 新鲜度自检（重要）

`grep -a` 类名会**误判**：R8 把私有的 `VrcxMotion`/`AnimatedStatusDot`/`StateCrossfade` 全部内联剥离，
所以这几个符号在 dex 里**搜不到是正常的**。正确做法是**搜用户可见字符串 + 偏好键**：

```
减少动画                      → 命中 ✓
关闭列表淡入、状态点脉冲等过渡效果  → 命中 ✓
reduced_motion                → 命中 ✓
reducedMotion=                → 命中 ✓
```

### 性能实测（真机 `gfxinfo`，独立滚动 12 次翻页）

| 指标 | 本次 | 之前 release 基线 |
|---|---|---|
| Janky frames | **3 / 586 = 0.51%** | 0.63% |
| 50th percentile | 9ms | 9ms |
| 90th percentile | 13ms | — |
| 95th percentile | 15ms | — |
| 99th percentile | 19ms | — |
| Missed Vsync | **0** | — |
| Slow UI thread | 3 | — |

**结论：242 行好友列表加动画状态点后帧率无回退**，中位帧耗时与基线一致（9ms）。

### 过程中真正抓到的编译错误

写代码时「看起来对」的几处，都是编译器抓出来的，值得记下来：

1. **尾部 lambda 绑定到了 `modifier` 而不是 `content`** — `content` 是最后一个参数但 `modifier` 有默认值，Kotlin 把 `{ }` 解析给了 `Modifier`。必须显式写 `content = { ... }`。
2. **`size: Int = 12` 配 `12.dp` 传参** — 一个叫 `size` 却表示 dp 数量的 `Int` 参数是个陷阱。改成 `Dp` 类型，让类型系统自己挡住。
3. **`when` 分支里 smart cast 失效** — 把 `when` 包进 `StateCrossfade` 的 lambda 后，`snapshotValue` 的可空收窄丢失。改成让 `AnimatedContent` 把解包后的值交给 lambda，分支里不再需要 smart cast。

第 3 条特别值得一提：`AnimatedContent` 的 `content: @Composable (T) -> Unit` 收到的就是解包后的值，用它比自己在外层再判一次更干净。

## 五、世界详情：从「六字段」到「完整档案」

> 用户反馈：「动态里点开好友信息 → 点世界，世界显示的不够详细」；
> 「主页的好友聚集地也一样，点开后详情要显示里面都要谁，要头像和名字」。

### 5.1 根因：不是排版薄，是数据薄

上一版 `WorldDetailSheet` 渲染得很干净，问题是**它手里只有六个字段**。追下去发现服务端是**两套世界数据**：

| 路径 | 数据源 | 返回 |
|---|---|---|
| `app__world_get`（`full=false`，默认） | 服务端**本地 SQLite 缓存** | 12 列：`id`/`name`/`author_*`/`description`/`image_url`/`thumbnail_image_url`/`release_status`/`version`/`created_at`/`updated_at`/`added_at` |
| `app__world_get`（**`full=true`**） | **直连 VRChat 实时 API** | 完整文档 ~30 字段 |

`world_cache/runtime.rs::get` 里那句早返回是关键：

```rust
if !force && !full { return cached_summary; }   // 缓存表根本没存 capacity / visits / tags
```

所以「不够详细」是**数据层的天花板**，不是布局问题。修法是从源头换数据：`full = true`。

### 5.2 四路并发，各管一摊

新增 `data/repository/WorldDetailRepository.kt`，`coroutineScope { async }` 并发四路：

| 命令 | 参数形态 | 拿到什么 |
|---|---|---|
| `app__world_get` | `input`（`worldId`/`force`/**`full=true`**） | 容量、推荐容量、浏览数、收藏数、人气、热度、占用、tags、platforms、unityPackages、publicationDate/labsPublicationDate/created/updated、isLabs、featured、hasPersistData、previewYoutubeId、organization |
| `app__world_friend_visits` | 平铺 `worldId` | **曾来过的好友**：`display_name`/`visit_count`/`last_visited_at` |
| `app__vrchat_instance_get` | `input`（`worldId`+`instanceId`） | **该实例实时人数** `userCount`/`capacity` |
| `app__game_log_previous_instances_by_world_id` | 平铺 `worldId` | 我的游玩记录，`time` 单位 **毫秒** |

第四个命令名字一开始写错了（臆造成 `app__world_previous_instances_get`），查 `docs/ANDROID_API-commands.json` 才定名。

**刻意不与 `WorldNameRepository` 合并**：那个是「几百个 `wrld_` 各自叫什么」的廉价缓存，在列表渲染路径上；这个是单个世界的几次实时往返，只在 sheet 打开时调用。共用缓存会让富记录挤掉列表依赖的廉价记录。

### 5.3 「里面都有谁」——两个问题，两个答案

用户这句话有歧义，而**混淆两者就是在说谎**，所以分两节渲染：

| 区块 | 含义 | 来源 | 代价 |
|---|---|---|---|
| **房间里的人** | 此刻就在**这个实例**里的好友 | 客户端本地：`roster.friends.filter { it.location == location }`，即主页聚集地用的同一份 | **零**（已有数据，即时） |
| **曾来过的好友** | 历史上来过的好友 | `app__world_friend_visits` | 一次往返 |

两条路径给同一答案：`WorldTarget.roster` 有值就用（来自 `WorldGathering.friends`），没有就现从共享快照按 `location` 过滤。后者的好处是从**动态**或**资料页**绕进来时也正确——那两条路径根本没有 gathering 对象。

标题用「曾来过」而不是「在这里」是刻意的：两节上下相邻、含义完全相反，措辞一松就会让人以为这些人现在都在里面。

### 5.4 `location` 必须一路上传

人数是**按实例**算的，不是按世界。同一世界的两个实例是两拨人，绝不该合并。但 `MainScreen` 原来只存 `worldId: String?`，一个世界 id 根本答不出占位率。

引入 `WorldTarget(worldId, location?, roster?)`：

- 收藏页 → `WorldTarget(id)`（只知道世界）
- 建议房间卡 → `WorldTarget(id, room.location)`
- 聚集地卡 → `WorldTarget(id, gathering.location, gathering.friends)`
- 资料页的「所在世界」→ `WorldTarget(card.worldId, card.location)`

`onOpenWorld` 的签名从 `(String) -> Unit` 升级为 `(WorldTarget) -> Unit`，四处调用点（`HomeScreen`/`FavoritesScreen`/`UserProfileSheet`）同步改。

### 5.5 渲染结构

```
┌ 168dp 大图（DETAIL_SIZE=256）
├ 世界名 / 作者 / 访问类型 / 原 location（受「显示实例 ID」开关控制）
├ 【房间里的人】 ← 有好友时显示
│   ├ 容量条：3 / 16 + 区域 + 实例类型   ← app__vrchat_instance_get
│   └ 好友行（头像 38dp + 名字 + 在线状态）← 点击 → 资料页
├ 【详情】描述
├ 【数据统计】容量/推荐容量/浏览/收藏/人气/热度/占用 ← 只显示非零项，两列卡片
├ 【世界标签】chips（最多 24 个 + "+N"）
├ 【支持平台】PC / Android / iOS + 每个 build 的 Unity 版本与性能等级
├ 【实例】版本/发布状态/发布时间/实验室发布/创建/更新/精选/实验室世界/持久化数据/所属组织/宣传片/作者
├ 【曾来过的好友】按 visit_count 降序，头像 + 名字 + "来过 N 次 · 日期" ← 点击 → 资料页
└ 【我的游玩记录】总时长 + 最近 8 次（世界名 / 群组 / 日期 / 时长）
```

**每个区块独立可空**：看不见的世界、已关闭的实例、空的访客表，各自塌缩成「少一行」而不是「报错页」。只有主世界拉取有可见失败态，且失败时**保留已渲染的内容**。

### 5.6 这一轮踩到的坑

1. **`by` 委托用在 `Flow.collectAsState()` 的可空链上** — `services?.x?.states?.collectAsState()` 返回 `State<Map<..>>?`，`by` 拿不到 `getValue`。改成不用 `by`，显式 `states?.value?.get(id)`。顺带修掉真正的隐患：原来写的是 `flow.value` 一次性读取，**永远不会触发重组**，sheet 会一直停在 spinner 上。
2. **`AppSettings.Values` 字段是 `timeZoneId` 不是 `timeZone`** — 猜字段名的成本是 5 个编译错误。
3. **`@SerialName` 只改线上格式，不改 Kotlin 属性名** — `WorldFriendVisitRow` 的属性是 `userId`/`visitCount`，我按 wire 格式写了 `user_id`/`visit_count`，13 个错误一起报。
4. **`WorldDetail.releaseStatus` 是 `String?`** — `when` 里 `""` 分支需要写 `null, "" ->` 才穷尽。

### 5.7 验证

| 步骤 | 结果 |
|---|---|
| `:app:compileDebugKotlin` | **BUILD SUCCESSFUL**（三轮增量：首轮 35 错全在新文件，二轮清剩余） |
| `:app:testDebugUnitTest` | **96 例 / 0 失败**（基线 83 → 96，新增 13 例协议解析测试） |
| `:app:assembleRelease` | 见下方 |

## 六、待办

- [ ] `StaggeredReveal` 已接入世界详情的名册行；其余页面（聚集地列表）尚可推广
- [ ] `ExpandableSection` 可用于 `HomeScreen.showAllGatherings` 与 `FavoritesScreen` 的分组折叠
- [ ] 好友状态**实时切换**时的动画观感（本次只验证了静态渲染与滚动帧率）
- [ ] 世界详情里的「宣传片」目前只显示 `youtu.be/<id>` 文本，未做缩略图/跳转
- [ ] `app__world_friend_visits` 在好友数为 0 与请求失败时都返回空——两者目前**无法区分**，
      所以「还没有好友来过这里」只在确实拿到对象时才显示，否则整节隐藏

## 七、共同好友关系图改造（四个问题）

用户的四条原始诉求，逐条对应：

> 「列表每个名字前面都要加上它的头像；点击展开后为什么名字显示的是 usr 什么什么的，要显示正常的名字；
> 关系图你看着改吧，现在非常非常怪，一个光点都比得上一个人名大了，看着特别特别拥挤；
> 点开后请不要必须拉取才能看信息，以前又不是没拉取过，临时看以前的又不是不行」

### 7.1 B4 —— 打开即显示上次结果（不再强制拉取）

**根因**：`LaunchedEffect(started) { if (!started) return@LaunchedEffect … }` 把整条恢复路径都挂
在「本次会话点过开始拉取」这个条件上。快照只在内存里，进程一死就没了。

**修法**：新增 `MutualGraphCache`，把 `snapshot` + `status` 落盘成 JSON。

- 位置用 **`filesDir` 而不是 `cacheDir`**：快照是**几分钟的服务端遍历**换来的，是全 app
  唯一不能按需重算的数据。`cacheDir` 会被系统在低存储时清掉，等于把用户几分钟的等待丢了。
- `write()` **拒绝空快照** —— 否则一次取消/失败的拉取会把上一份好数据覆盖成空。
- `.part` 写完后 rename，避免半截文件。
- 读取用 `remember(userId, services) { repo.cached(userId) }` **同步**完成，不是
  `LaunchedEffect`：后者会先渲染一帧「还没拉取过关系图」再切走，正是要消除的空态闪烁。
- `LaunchedEffect` 只做「补空」不做「覆盖」：本次会话内已经拉到的新数据优先于文件。
- 头部如实标注来源与年龄：`以下为本地缓存（8 分钟前）`。

### 7.2 B2 —— 展开行不再出现 usr_ 开头的 id

**根因**：`name = friendNames[id] ?: shortId(id)`，兜底直接把 id 截断打出来。

**修法**：**删掉 `shortId`**，换成常量 `PENDING_NAME = "…"`。同时在
`buildGraphGeometry` 里收集 `unresolvedIds`，屏幕对**所有**无名节点（朋友 + 共同好友，
不只是共同好友）发起 `ensure()`，答不上来的显示 `…` 而不是 id。
现在展开任意一行，看到的全是 `GoldenDollar` / `幽蘭冷月` / `雪风 Yukikaze` 这样的真名。

### 7.3 B3 —— 关系图不拥挤、点在标签前不再喧宾夺主

**根因（真正的那个）**：节点 `radius` 是**世界坐标**单位（朋友 `6f + 8f * count / maxCount`，
共同好友恒定 `4f`），绘制时再乘 zoom。旧默认 `scale = 1` 时，一个朋友只有 **6–14 物理像素**，
而旁边的标签是 11 sp ≈ 29 px —— 点比字还大。更糟的是**一个只有 1 个共同好友的朋友**和一个
连接很多的朋友拿到几乎一样的半径，共同好友更是恒定 4f。

**修法**：把半径计算整体搬进**屏幕空间**，并针对标签像素高度硬夹紧。

- 新增纯函数 `nodeScreenRadius(isFriend, mutualCount, maxMutualCount, scale, textPx, …)`，
  上限 `MAX_RADIUS_TO_TEXT = 0.5f`（半径不超过标签高的一半），下限 `ABS_MIN_RADIUS_PX = 1.1f`。
- 世界坐标（`0..1000` 的方阵）与屏幕坐标从此严格分开：**描述位置的用世界单位，
  描述外观的用设备像素**。把这两者混在一起就是整个缺陷的来源。
- 默认缩放 `1f` → `DEFAULT_SCALE = 2.4f`，范围 `MIN_SCALE = 0.5f` / `MAX_SCALE = 12f`。
- 标签预算改为**按视口面积推导**（`LABEL_AREA_PX = 1400f`，夹在 6–42 之间）而不是
  `8 * scale * scale`；标签先画一遍**描边光晕**（surface 色）再填字，名字穿过连线也可读。
  标签字号**不**乘 scale。
- 强调手段从「放大半径」（在 3px 的圆上根本看不出来）改成**画环**：
  选中 `+7px 半透明填充 + 2px 描边`，邻居 `1.4px` 描边。
- 边的宽度改成屏幕像素常量（2.4 / 1.3 / 0.7），不再乘 scale。
- 命中测试用屏幕 slop：`d < node.radius * WORLD_SIZE / 100f + TOUCH_SLOP_PX / scale`。

实测：192 人 / 1360 条连接，标签清晰可读，点不再压过字，社交圈按社区着色自然分簇。

### 7.4 B1 —— 列表每行都有头像

**根因**：`MutualGraphList` 里一个图片都没有。

**修法**：新增 `MutualPeopleRepository`（取代只取名字的 `MutualNamesRepository`），
一次 `app__vrchat_user_get` 同时拿到 **displayName + iconUrl + avatarThumbnailUrl**。
朋友行 40 dp 圆头像，展开的共同好友行 28 dp 圆头像（`PersonRow`，`heightIn(min = 44.dp)`
满足触摸目标下限）。头像全部走 `RemoteAvatar`，取不到时退化成名字哈希色的占位头像，
**绝不出现空白洞**。

### 7.5 本轮附带发现并修掉的问题

共三个，都不是用户点名的四项，但都挡在四项前面。

**(a) 关系图里的搜索框会抢走画布的所有点击。**
一旦点过搜索框，软键盘弹出并**覆盖**（不是压缩）画布下半部，此后每一次点击都进了键盘窗口，
画布表现为「完全点不动」。`clearFocus()` 单独不够 —— IME 是独立窗口，必须同时
`LocalSoftwareKeyboardController.hide()`。

**(b) 画布点击（选节点）在整个屏幕上都不会触发。** 这一条查了很久，结论值得记下来：

- 现象：`pointerInput` 挂在**父 `Box`** 上时，协程体**第一行代码从不执行**。
  在 `Log.e` 级别加了探针，跨越 13 个点击位置、覆盖画布全高，零输出；
  同时 `Box` 确实组合了、也确实量到了 `1080 x 1444`，兄弟节点（图例行）的点击**完全正常**。
- 排除项：`transformable` 存在与否、修饰符顺序、`HorizontalPager`、可滚动祖先、Box 尺寸 —— 全排除。
- 修法：把**同一个**修饰符从 `Box` 下移到 `Canvas` 叶子节点。`Canvas` 才是真正绘制关系图、
  拥有真实 draw surface 的那个节点，移下去之后每一次点击立刻被投递。
- **教训**：`pointerInput` 要挂在**拥有绘制表面的叶子**上，不要挂在纯布局容器上。
  `Box` 本身不绘制，某些设备/Compose 版本下它不会进入 pointer 协商。
- **并存**：`Box` 仍保留 `transformable(state)` 做双指缩放/平移；两者不冲突 ——
  它们是树里两个不同节点，`Box` 收多指流，`Canvas` 收落到它身上的单指点击。
- **推论**：以后这个 `Box` 里任何新的点击目标都要挂在叶子上，且命中测试必须
  用与绘制完全相同的公式做 screen → world 反变换。

**(c) `sp` 字号的标签在 `Paint` 里按像素量。**
标签用 `android.graphics.Paint` 画（为了描边光晕），字号必须 `.sp.toPx()` 现算，
不能沿用 `sp` 数值本身 —— 否则 480 dpi 的机器上标签只有设计尺寸的 1/2.625。

### 7.6 验证

| 步骤 | 结果 |
|---|---|
| `:app:testDebugUnitTest` | **102 例 / 0 失败**（96 → 102，新增 `MutualGraphRadiusTest` 6 例） |
| `:app:assembleRelease` | **BUILD SUCCESSFUL**，2,025,523 字节 |
| APK 新鲜度自检 | `classes.dex` 内含 `mutual-graph` / `本地缓存` / `未命名` / `正在解析名字` |
| 冷启动 | 0 FATAL |
| 首次进入（无缓存） | 正确显示「还没拉取过关系图」+ 开始拉取按钮 |
| 拉取完成后 | 192 人 / 1360 连接，标签可读、点大小合适 |
| 列表视图 | 每行都有真头像 ✅ |
| 展开行 | 全部真实 displayName，**无 `usr_`** ✅ |
| **杀进程重进** | **直接显示列表，无开始拉取按钮，头部标注「本地缓存 59 分钟前」** ✅ |
| **画布点击选节点** | 点中 `樱乃露露` → 蓝点 + 光晕 + 全部 23 条邻居边高亮成辐射状、无关节点淡出 + 卡片显示头像与「共 23 位共同好友 · 更新于 09-19 01:17」 ✅ |
| 点中朋友节点 | `feng枫` → 红点 + 光晕 + 邻居高亮 + 「共 29 位共同好友」 ✅ |
| 缩放/平移共存 | `Box` 上 `transformable` 与 `Canvas` 上 `pointerInput` 并存：swipe 平移生效且选中态保留，互不吞事件 ✅ |
| 崩溃缓冲 | 空（`logcat -b crash` 无记录）✅ |

> 以上点击类验收均在 **release APK**（R8 混淆）上完成，不是 debug 包。

### 7.7 如何在真机上可靠地点中一个节点

节点标签是 `android.graphics.Paint` 直接画进 `Canvas` 的，**不出现在 `uiautomator dump` 里**
（dump 只有页头、按钮、图例、页脚）。所以「按 dump 坐标点节点」必然落空。

可靠做法是从截图的像素里找圆点：节点是浅底上的高饱和实心圆，
对画布区域（y 430–1850）做 `max-min >= 55` 的饱和度筛选，
按 8 px 网格分桶、取每桶质心，命中的就是节点中心。
`tmp_dotfind.py` 是这份逻辑的参考实现（含纯 Python PNG 解码）。


`MutualGraphRadiusTest` 钉住的不变量（跨 scale 0.5–12 × count 0–242 × 朋友/共同好友扫掠）：
半径永不超过自身标签高的一半；任何缩放下节点都 ≥ 1 px；朋友画得比共同好友大；
半径随连接数单调；选中节点允许突破上限（≥ 5 px）；淡化节点缩小但仍 ≥ 1.5 px。
