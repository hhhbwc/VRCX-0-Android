plugins {
    id("com.android.application")
}

android {
    namespace = "com.vrcx0.tablet"
    compileSdk = 35

    defaultConfig {
        // 开发期：预览壳与手机端 com.vrcx0.android **不同包名**，
        // 否则 install -r 会覆盖手机上真在用的那个 App（连带登录态一起踩掉）。
        // 但这不是「两个产品」—— 合并后平板只是手机端 App 的一个尺寸档，
        // 届时这个预览壳退役，包名的差异也随之消失。
        applicationId = "com.vrcx0.tablet"
        minSdk = 26
        targetSdk = 35
        // 0.2.0-t1：相对 t0（纯 UI 壳）接上了数据面 —— 原生指纹钉传输层
        // （net/PinnedTls + net/RemoteTransport）、DataBridge、连接闸门。
        // 0.2.1-t1：真机两个 bug —— ①安全区改由**父容器**吃 insets（原先写在
        //   WebView 自己身上，真机上完全不生效：顶栏整条被状态栏压住，连接状态点
        //   落进状态栏触摸区，"打开连接闸门"这件事直接没了）；②个人页
        //   「退出登录 / 忘记此服务器」此前没有 id 也没有监听器，点了毫无反应。
        // 0.2.2-t1：首启拦截 + 样例身份
        //   ① 闸门判据从"配没配地址"改成"有没有登录"，且跳过标记只进 sessionStorage
        //      —— 旧版地址配好就再也不弹闸门，装完直接进主界面而个人页写着"未登录"；
        //   ② 样例里的第三方昵称（曾同时出现在动态/好友/主页/详情）换成明确占位名，
        //      并加了可见的「演示数据」标识；顶栏头像改由登录态驱动。
        // 0.2.3-t1（另 AI）：五屏接真实数据 + 详情页动作 + 事件流 M2 骨架。
        // 0.2.4-t1：B1 加固（baseline 带 wsid，重连对账不再被 Superseded）、B6 本地搜索、
        //   清理 demobar/通知假数据；真机验收通过。
        // 0.2.5-t1：把事件流传输从 JS new WebSocket 下沉到原生（EventStreamClient）：
        //   ① B3 token 走 Authorization 头、不再拼进 query（不进服务器访问日志）；
        //   ② B2 TLS 复用 PinnedTls 证书钉，自签 HTTPS 下可连（WebView 直连会被系统库拒）；
        //   删 DataBridge.streamToken()，conn.js 改用 window.VRCXNative.openStream/closeStream
        //   + __vrcxStreamFrame/Open/Close 回调，帧处理逻辑不变。
        // 0.2.6-t1：修「点动态里的好友后底部抽屉与右栏同时出现」——根因是
        //   screens.js 的 floatDetail() 把 #detail 从 .app__content 挪到 body 并加
        //   position:fixed，于是它出不了 840dp+ 的主从右栏，只能当整屏底部抽屉。
        //   删掉 floatDetail()（#detail 留在网格里 → 平板横/大屏渲染成常驻右栏）；
        //   并修掉随之暴露的一条 CSS 特异度冲突：600+ 档
        //   `.app.detail-open .detail{transform:translate(calc(-50% - var(--rail-w)/2),0)}`
        //   ((0,3,0)) 压过 840+ 档 `.detail{transform:none}` ((0,1,0))，
        //   会把右栏整体左移 50%+rail/2（实测 x 从 592 变 227，压住列表）。
        // 0.2.7-t1：修「切 tab 时详情跨屏残留」——USB 真机实测：动态里点开好友 ->
        //   切到「好友」页，抽屉仍悬在屏上（连切 5 个 tab 都不关）。根因是切页由
        //   main.js 的 goto() 处理，它不管 #detail；而 #detail 的显隐归 screens.js
        //   （applyDetailVis/detailOpen）。改 screens.js wire() 里加 document 级
        //   委托：点带 data-goto 的元素即 closeDetail()（boot 的 hash 驱动 goto
        //   不经 click，出图路径 tabp_open 的详情断言不受影响）。
        // 0.2.8-t1：服务端补 app__quick_search_query（oss 侧 social.rs，e2e 80/80）后，
        //   平板接真实数据的三件套：
        //   ① 顶栏全局搜索面板（服务端 quick_search_query 分组结果，命令不在能力表
        //      时降级为已加载好友的本地过滤，conn.js 现在转交 commands.supported 原表）；
        //   ② 通知中心（app__notification_list_query 服务端本就实现，此前是占位
        //      toast）—— 未读角标 + 全部标为已读（location=local，纯本地 DB 写）；
        //   ③ 骨架屏 —— 桥模式下真实数据到位前用骨架条顶掉静态样例，冷启动不再
        //      闪"示例好友 A…G"（浏览器演示无桥不受影响，样例仍是它的内容）。
        //   M4：net/PinnedTls.java 从「命中即信任、否则默认校验」收紧为「配了指纹
        //      就只认那把钥匙」（对齐手机端 TlsPinning.kt 2026-09-22 语义），
        //      25/25 run_bridge_test 对真服务器通过。
        // 0.2.9-t1：搬原版功能第一批（用户指令「原版的功能全都给搬过来」）。
        //   新增导轨/底栏第 6 项「更多」→ 工具 hub，下挂四屏（screens_tools.js，
        //   数据面命令全部由服务端实现，无需服务端改动）：
        //   ① 游戏日志 app__game_log_sessions_query（桌面 /game-log）
        //   ② 房间历史 app__instance_history_query（桌面 /instance-history）
        //   ③ 浏览历史 app__browse_history_query（桌面 /browse-history，带服务端 search）
        //   ④ 我的群组 app__vrchat_group_user_groups_get（桌面 /tools/my-groups）
        //   详情抽屉新增「记录」段：app__friend_log_history_query（桌面
        //   /social/friend-log），screens.js 的 fillDetail 派发 vrcx:detail 事件。
        //   共同好友图 / 库存 / 设置 / 审核仍欠，见 ROADMAP。
        // 0.2.10-t1：搬原版功能第二批（数据面命令全部由服务端实现）：
        //   ① 详情抽屉「备注」：app__memo_get_user / memo_save_user（桌面 /memo，私人备注）
        //   ② 活动与统计页（更多 → 记录组）：app__activity_view（168 桶按小时求和画
        //      时段柱图 + 峰值窗口）+ app__avatar_usage_ranking（常用模型排行）
        //   ③ 搜索面板「全网」档：app__vrchat_search_users/worlds/groups（VRChat 实时搜，
        //      三条都支持才显示档位；用户行可点开详情；世界/群组只展示）
        // 0.2.11-t1：修 bug 找茬轮（screens.js / screens_tools.js）：
        //   ① 搜索面板竞态：全网档用独立 liveSeq，「全网→我的」切档/清空输入/重开面板
        //      三种时序下在飞的全网响应会把过期分组 append 进新结果 —— 非全网路径与
        //      openSearchPanel 现在都会作废 liveSeq（openSearchPanel 同时作废 searchSeq，
        //      关面板前的旧查询不再回填新面板）。
        //   ② 深链直达工具页不加载：#device,t-activity 由 main.js goto() 切页但没人触发
        //      懒加载 → 永远空白。wire() 解析 hash + onState 认证翻转后补查。
        //   ③ 本地四屏重进不刷新：loaded 一次性标记 → 改为每次进入重拉（本地 SQLite 便宜），
        //      群组（VRChat 远程）保留会话缓存。
        //   ④ loadActivity 的 acEmpty/peak textContent 判空（崩溃面）。
        // 0.2.12-t1：修「所有好友都是离线」+ 两处 UI 整改（用户真机反馈）：
        //   ① 全员离线的根因 = roster 基线对账需要 realtime 会话 id（websocket），
        //      而它只随自身 user-update 事件下发 —— 安静会话里客户端永远学不到，
        //      于是每次基线都被服务端拒成 Superseded → 落到没有状态字段的
        //      friend_log 兜底 → 全员 offline。修复分两层：
        //      服务端 hello 帧新增 websocket 字段（协议 v2 增量，旧客户端忽略；
        //      手机端 wireJson ignoreUnknownKeys=true 已核）；客户端 hello 到手
        //      即存 streamWebsocket 并 notify，screens.js 检测到 wsid 变化就
        //      定向重拉名单重画（旧服务端在跑时事件来源照旧兜底）。
        //   ② 去重：游戏日志唯一入口 = 动态页「日志」chip，更多页删掉重复卡；
        //      日志页返回键回动态。
        //   ③ 底栏 6 项排不开 → 回到 5 项，「更多」窄屏走顶栏工具按钮
        //      （容器查询只在无导轨档显示），宽屏仍走侧栏第六项。
        // 0.2.13-t1：修三个死交互 + 搬原版共同好友图（/charts/mutual）：
        //   ① 好友筛选 chips（全部/同房间/在线/活跃/离线）此前是死的 —— 接线生效；
        //   ② 好友/详情头像改从名单基线的缩略图取（FriendRecord 自带），
        //      不再只靠 feed 行喂 —— 242 人全员有头像；
        //   ③ 关系图死按钮 → 共同好友图页：app__mutual_graph_snapshot_get 建图
        //      （canvas 力导向，节点色随在线状态，点节点开详情），空数据给
        //      fetch_start 抓取入口 + 5s 轮询（meta 覆盖数稳定即停）。
        // 0.2.14-t1：事件对账节流 —— 事件触发的全量重拉最少间隔 15s
        //        （原来 1.2s 防抖，好友上下线成串到达的晚上横幅常驻、路由器被打爆），
        //        且后台对账静默（不闪「正在从服务器读取」横幅）。
        // 0.2.15-t1：收藏好友置顶 + chips 滚动 + 发布前全量闸门：
        //   ① 好友列表里收藏（特别关心）的排组内最前，名字带「★ 特别关心」标
        //      （用户：我关注的好友排序要靠前）；
        //   ② chipstrip 横向滚动 —— 窄屏第 5 颗筛选 chip（离线）不再被裁；
        //   ③ 新增 _shots/predeploy_check.py：发布前一次跑完所有套件的闸门
        //      （screens/layout/gate/contract/verify-apk，--live 加真服务器传输测试）。
        // 0.2.16-t1：修共同好友图两个问题（用户实测反馈）：
        //   ① 抓取根本没启动 —— 服务端硬校验 friendIds 非空，之前传了空数组被拒。
        //      现在传全量名单 id；另加「取消抓取」（fetch_cancel）。
        //   ② 图不显示东西 —— 两层：a) 力导向没有向心力，节点被斥力推出画布；
        //      b) 无缩放适配。加向心重力 + bbox 缩放适配（绘制与点击反算同一变换），
        //      无论布局漂到哪都缩进画布。
        //   ③ 抓取进度：进度条（covered/total %）+ 状态文字实时更新，3s 轮询
        //      snapshot_get，图随抓取进度逐渐长出来。
        // 0.2.17-t1：搬原版三件（服务端命令全部就绪）：
        //   ① 收藏的群组档（/favorites/groups = saved_group_favorites 本地集合，
        //      集合名做组头、群组名走 vrchat_group_get 解析，裸 id 不上屏）；
        //   ② 我的模型全量页（/my-avatars：个人页只显示 12 个，这里全量 + 累计使用时长，
        //      缩略图顺序走服务器代理避免并发打爆）；
        //   ③ 个人页「服务器配置」只读区（config_list_values 键值对）。
        // 0.2.18-t1：bug 找茬第二轮（真数据边界路径）：
        //   ① 好友/动态筛选空结果误报「这里还没有好友/动态」——区分
        //      「确实没有」与「筛选没匹配到」（后者给换筛选提示）；
        //   ② VRChat 会话失效（502 + 消息含 401）误报「凭据已失效」——
        //      独立文案指向重新登录（抓共同好友图触发会话吊销的实测场景）；
        //   ③ 群组收藏夹读取失败误报「还没有收藏夹」——失败如实报原因。
        //      契约测试 33→35（describeError 两分支 + 本机 401 不误判）。
        // 0.2.19-t1：详细信息显示（世界详情面板）+ 二级菜单触发完善：
        //   ① 世界详情面板：好友分组头 / 浏览历史世界行 / 收藏世界格 / 全网搜索
        //      世界行，四处都可点开 world_get 的完整详情（作者/游览/收藏/容量/
        //      发布状态/描述，缩略图头图走服务器代理），裸 id 不上屏；
        //   ② 好友分组头变成可点按钮（有世界 id 时）；
        //   ③ 顺带修：搜索结果好友行的 id 传递（重构时 kind 标记错位导致
        //      好友结果不可点——套件当场抓住）。
        // 0.2.20-t1：修「命令参数形态」—— 一批功能其实从来没取到过数据（重大）：
        //   调用点自己包了一层 {input: ...}，而 VRCX.command 内部的 ArgForms.encode
        //   还会再包一层，实际发出去是 {input:{input:{...}}}。服务端只回一句
        //   502 invalid arguments，前端一律表现为"没数据"，所以探针全绿也照坏。
        //   对真实进程实测（.workbuddy/scripts/probe_arg_shape.py）：
        //     game_log_sessions_query  → HTTP 200 []（静默空查询！最难发现）
        //     instance_history_query   → missing field `userId`
        //     browse_history_query     → missing field `ownerUserId`
        //     activity_view            → missing field `ownerUserId`
        //     world_get                → "World id is required."
        //     mutual_graph_fetch_start → missing field `ownerUserId`
        //     group_user_groups_get    → unknown field `input`, expected `userId`
        //   翻历史 APK 逐版核对：游戏日志/房间历史/浏览历史/我的群组自 0.2.9
        //   上线起就是坏的（活动统计自 0.2.10 起，世界详情自 0.2.19 起）——
        //   它们全都只在探针里"验收"过，真机上没人打开看过。
        //   本轮修 8 处调用点为平铺；并新增静态闸门 _shots/lint_callsites.py
        //   （已接进 predeploy_check.py），这一类错误以后跑闸门就能拦下。
        // 0.2.23-t1：修用户一次性报的 8 条真机问题（其中 6 条是**真缺陷**）
        //   ① 双指缩放关不掉 —— `setSupportZoom(false)` 三兄弟只关"缩放控件"，
        //      拦不住手势。真正拦得住的是 viewport meta 的
        //      `maximum-scale=1, user-scalable=no` + CSS `touch-action: pan-x pan-y`
        //      兜底（`auto` 里含 pinch-zoom）。两处补齐，原生那三行留给旧内核。
        //      ⚠️ 无头验证不了：两种合成手势连"故意允许缩放"的对照档都捏不动，
        //      `_shots/audit_zoom.py` 因此自报 SKIP（闸门里也是 SKIP，不是 PASS）。
        //   ② 关系图小点点点不中 —— `drawGraph` 自 0.2.16 起改成等比 bbox 适配，
        //      而 `graphTap` 只改了反算公式的一半、仍按 900x500 线性拉伸算
        //      （横向差 6%、纵向差 19%）→ 靠上靠下的节点永远点不中。现在画/点/标签
        //      摆位共用唯一的 `graphTransform()`，命中半径按**屏幕像素**（26px）。
        //   ③ 关系图不显示用户名、无高亮 —— 新增标签层（默认标我 + 度数最高的
        //      十几个，选中时只标"相关的人"，带碰撞跳过）+ 选中高亮（相关线加粗、
        //      无关节点压灰、选中环白垫底蓝外圈）。两步式：先高亮看清关系，
        //      再点同一点打开资料。
        //      ⚠️ 修的过程中发现：详情抽屉**在图页上永远显示不出来**
        //      （`.app__content.is-wide .detail{display:none}`，而图页恰好 is-wide；
        //      CDP 实测 hidden=false 而 display=none）→ 文案承诺的"再点一次看详情"
        //      是死路。现在第二次点会先切回好友页再开详情。
        //   ④ 收藏页永远空白 —— `app__favorite_list` 读的本地三张表
        //      （favorite_world/avatar/friend）从未被写过：路由器租户库与本机桌面库
        //      直接开库查过，都是 0 行。改用真正在跑的
        //      `app__social_favorites_baseline_get`（会话级缓存一次）。
        //   ⑤ 好友都没有头像 —— 服务端实测诊断（241 个 `/auth/user/friends` 样本）
        //      里 `currentAvatarThumbnailImageUrl` **241/241 missing**，而
        //      `currentAvatarImageUrl` 241/241 非空。只认 thumbnail 就人人退化成
        //      首字母圆。改为 thumbnail → currentAvatarImageUrl → iconUrl 三层兜底，
        //      并给 VRChat 图片 URL 追加 `/64` 取缩略（242 人走原图会打爆路由器）。
        //   ⑥ 个人页模型不显示封面 —— 除了同一个字段问题，还有一个 `var` 闭包 bug：
        //      循环里 `loadImage(url).then(function(data){ … thumb … })` 拿到的
        //      永远是**最后一轮**的 thumb（每张图都往最后一格写），且兜底那句会把
        //      最后一格的图抹成名字。改用 IIFE 绑当轮元素。
        //   ⑦ 详情面板随列表滚动一起上移 —— `#detail` 是 `.app__content`
        //      （position:relative）的 absolute 子元素，包含块就是滚动容器。
        //      改成外层 `overflow:hidden`、滚动交给 `.pane`。
        //   ⑧ 「服务器配置」太乱 + 「状态文字」后面有箭头却点不动 ——
        //      配置改为白名单中文标签直出 + 其余折叠 + 凭据类（credential/token/
        //      password/secret/cookie/apikey）**永不渲染**；带 chevron 的三行
        //      （当前模型 / 人称代词 / 状态文字）现在真的能点：模型行进模型页，
        //      另两行就地编辑（`app__vrchat_current_user_update`）。
        //      ⚠️ 顺带修：`safe()` 是"整串含裸 id 就整条丢掉"的中央闸门，
        //      而 `vrcx_lastuserloggedin` 的值恰好就是一个 usr_ id → 该行只能显示
        //      「（空）」。掩码要发生在 `safe()` **之前**（`cfgText()`）。
        //   闸门：screens 123→148 项（新增"交互档"：真实坐标派发点击、真滚动、
        //   真点保存按钮并核对入参形态）。另外 `lint_state_fields.py` 收窄了两类
        //   误报（同名不同物 / 宿主字段），并加了 `--selftest` 三例。
        // 0.2.24-t1：真机复核发现的两处（会话失效态的实际表现）：
        //   ① 个人页冻结在开机渲染 —— VRChat 会话 401 后 state.loaded 一直 false，
        //      renderAll 提前返回，"服务器地址"一直显示"未配置"（顶栏明明已连上）。
        //      修：未加载分支也刷新个人页（地址来自本地 configJson，与会话无关）。
        //   ② 通知/服务器配置是本地库读取，不依赖 VRChat 会话 —— 却挂在 loadAll
        //      成功尾部，会话一死就永远不跑（configs_n=0、角标永不亮）。
        //      修：抽成 __SCREENS__.loadLocal，认证翻转即跑。
        // 0.2.25-t1：bug 找茬第三轮（失败路径状态机）：
        //   ① favError 成功后不清理 —— 会话恢复、收藏回来了，错误文案还挂着；
        //   ② 取数失败后骨架条永远闪烁 —— 横幅说"失败"、列表一闪一闪说"在加载"。
        //      且失败可能发生在 wire() 之前（极速失败：catch 先于 DOMContentLoaded），
        //      wire 末尾的 paintSkeleton 会把已清除的骨架重新画上去 ——
        //      画完骨架后检查"是否已失败"，失败则立即换成诚实错误提示；
        //   ③ 失败路径 feedEmpty 不亮（renderFeed 没跑）—— paintLoadFailed 补亮。
        //   新增"取数失败"探针档（fail_all 桥：全部 command 回 502+401）。
        // 0.2.26-t1：独立审计抓出的两个坏功能。**两个都是上一版刚加就坏的**，而且
        //   都从未进过任何包 —— 源在 03:08 改完，APK 在 03:02 就打完了（见 HANDOFF 的
        //   "改源未重打包"账）。复现方式与判据都留在 `_shots/audit_feedfilter.py` /
        //   `audit_detailph.py` 里：
        //   ① 动态类型筛选恒筛出 0 条 —— chip 的 `data-feed-kind` 是**键**（"GPS"），
        //      而判据写成 `feedKindText(row.type) === state.feedFilter`：左边是给人看的
        //      **文案**（"位置"）→ 恒 false，只有「全部」看着正常。改成直接比键。
        //      另：「全部」chip 补 `is-on` 初始选中态（原先一进页面七个 chip 全没选中）。
        //   ② 详情列占位永远不可见 —— 占位块住在 `<aside id="detail">` 内部，而
        //      「没选中好友」时 #detail 会被 `hidden` + `[hidden]{display:none!important}`
        //      藏掉 → 该露出它的那一刻连祖先一起被藏（实测：自身 display:flex、
        //      可见像素 0）。修法：占位块移出 #detail、成为 .app__content 的直接子级，
        //      并在 840+ 档与 #detail 共占第 2 列栅格位，显隐交给 .app.detail-open。
        // 0.2.27-t1：真机（手机 PLZ110）反馈的两个"看得见"的观感缺陷：
        //   ① 顶栏右侧「地址 + 在线」溢出，压到铃铛 / 搜索图标上（"太挤了，都互相覆盖了"）——
        //      两条文字都是 `white-space:nowrap` 且既无 `overflow:hidden` 也无省略号，
        //      父级 `min-width:0` 只让它们"可以被压窄"，压窄后文字并不收敛而是**原地外溢**。
        //      修：块级 + `text-overflow:ellipsis`（只对块级生效）+ 父级 `overflow:hidden`。
        //   ② 点任意按钮留一块**蓝色小方块** —— 那是 `:focus-visible` 的
        //      `outline: 2px solid var(--md-primary)`（`#0054d6` = 蓝，还 `outline-offset:2px`）。
        //      桌面浏览器里指针点击**不**匹配 `:focus-visible`（只匹配键盘 Tab），
        //      但 **Android WebView 把触摸点击也判成"应当显示焦点"** → 每点一次留一圈蓝框。
        //      修：`@media (hover:none)` 下关掉；键盘导航（外接键盘）仍保留焦点环。
        //      顺带把 WebView 自带的触摸高亮设为透明（原先是半透明方块，ROM 配色不一）。
        //      ⚠️ 事后更正（2026-09-29，在 MuMu 模拟器上做了 A/B）：用户说的"蓝色小方块"
        //      **其实是后者**——`-webkit-tap-highlight`，不是 `:focus-visible` 的焦点环。
        //      证据：方块只在**按住时**存在（0.12~0.32s 都拍得到、0.45s 后没了），
        //      位置正好是整行；且 Chromium 只对**文本输入**在任意聚焦方式下判 focus-visible，
        //      按钮要键盘交互才算 → `.app` 里的 button 从不匹配它。
        //      0.2.26 上量到 37044 px / 100% 偏蓝；0.2.27 上同样手势 0 px。
        //      闸门里那条 `audit_taphighlight_emu.py` 真机跑，且**先核对设备上包的
        //      CSS 与源码逐字节一致**才跑（否则等于给旧包发 PASS）。
        //   两条都带**对照组**验过：`_shots/audit_topbar.py` 会断言"桌面下 outline 必须还在"，
        //   防止把无障碍一起删掉；触摸环境用 `Emulation.setTouchEmulationEnabled` 模拟
        //   （`setEmulatedMedia(features=[hover])` 不生效，见 `_shots/_dbg_hover.py`）。
        // 0.2.28-t1：补 0.2.27 审计挑出的"禁用态没有任何视觉反馈"。
        //   全项目 6 处 `el.x.disabled = …`（连接/加入/登录/详情保存/详情取消），
        //   而 `grep disabled styles/` **一条样式都没有** → 禁用按钮和可用按钮一模一样。
        //   用户实测点「加入」零反应（真因 labelOk 为假，而"平板"只是 placeholder）
        //   → 只能以为 App 坏了。修法：M3 规范灰化（onSurface 12%/38%）+ not-allowed
        //   + 说明文案点名"还要填：设备名称、邀请码"。闸门 `audit_disabled.py` 覆盖，
        //   并已用反向对照验过鉴别力（删掉样式会红 4 处）。
        // 0.2.29-t1：修「动态行的头像永远是首字母」——用户报的"好友都没头像"里剩下的那半边。
        //   病根：`app__feed_latest_query` 返回的行**一个头像字段都没有**
        //   （对真服务器实测，行字段只有 created_at / displayName / location /
        //    ownerUserId / type / userId），于是 screens.js 里那三层兜底是**死代码**，
        //   恒为 '' ⇒ 动态行永远只画首字母圆；而**同一批人**在好友页是有图的
        //   （好友列表走名册，字段齐全）。
        //   修法：行里本来就已查出 `rec`（名册记录），直接 `friendAvatarUrl(rec)`；
        //   自己发的动态不在名册里，用 `state.me` 兜住；feed 自带的三字段留在最后，
        //   万一哪天服务端补上就自动生效。
        //   ⚠️ 这个 bug 能活这么久，是因为**没有任何断言在看动态行头像** ——
        //   夹具 FEED_ROWS 如实照抄了"没有头像字段"的形态（不是夹具撒谎），
        //   纯粹是断言缺失。screens 套件因此补了 2 条（出图 3/4、3 张全带 /64），
        //   并已用反向对照验过鉴别力（去掉修复即红 2 条）。
        // 0.2.31-t1 ——「状态语义 + 可点通知 + 戳表情」（2026-09-29）
        //   ① 状态灯补齐 VRChat 五档（join me 蓝 / active 绿 / ask me 橙 / busy 红 / offline 灰）
        //      并区分**实心**（在游戏实例里）与**空心**（只在网页/APP 挂着），
        //      判定链逐条照搬桌面版 friendsSidebarModel.ts:264-371。以前只有三档全实心，
        //      join me / ask me / busy 被挤成一档 → "一部分人的灯没规律地不一样"。
        //   ② 好友排序按 Sort by Status → Sort Alphabetically（桌面版默认三级排序）：
        //      组内 收藏 > 状态(join me 先) > 字母序(zh-Hans-CN) > 稳定；
        //      分组 "我所在的实例" 优先 → 标题 → 人数，offline 沉底。
        //   ③ 通知**可以点开看详情**了（以前的行既没 click 也没 href），
        //      并按 type 给操作（加入 / 接受好友 / 戳回去 / 忽略 / 清除）。
        //   ④ 戳一戳可以选表情（VRChat 内置 65 个，桌面端常量表脚本生成）。
        //   ⑤ 「关于」那四个开关真接线（以前是纯静态 HTML，点了零反应）。
        //   ⑥ 加了克制的过渡（列表入场 / 按压反馈 / 面板弹入），受
        //      prefers-reduced-motion 与「降低动效」开关双重管。
        // 0.2.31-t2 ——「更多资料」对齐原版 + 状态文案修错（2026-09-30）
        //   ① 状态中文文案修正：原版 ask me=「忙碌」、busy=「请勿打扰」
        //      （反直觉，曾接反），join me=「欢迎加入」、active=「活跃中」；
        //      旧版把英文 status 原文（join me / ask me / busy）直接打到界面上。
        //   ② 详情面板补「更多资料」区块，对齐桌面版 UserDialog 的 info / mutual 两 Tab：
        //      曾用名、主页世界（只认 wrld_ 开头，private/offline/hidden(usr_) 不印）、
        //      简介链接（只显主机名、点一下复制整条）、徽章（只摆 showcased && !hidden）；
        //      共同好友「按需」拉取（app__user_mutual_friends_list_get 是裸 {rows} 信封，
        //      套 unwrap 会静默失效）。服务端字段较 roster 更全，名单 bio 为空时用
        //      资料接口回填主面板。白名单渲染，夹具塞满 email/discordId/friendKey/
        //      presence.groups 等敏感字段做负向断言，确认不泄漏。
        //   ③ audit_statusdot 由 35 项扩到 75 项（状态文案 + 白名单 + 共同好友入参形态）。
        // 0.2.31-t3 —— 死控件转正 + 主题真做（2026-09-30）
        //   ① 个人页「外观」3 档主题（跟随系统 / 浅色 / 深色）从死 chip 变成真功能：
        //      tokens.css 加 [data-theme="dark"] + prefers-color-scheme 两套 M3 深色槽位，
        //      删掉原先写在 .app 上的 --md-surface 覆盖（特异性 0,2,1 会反吃深色 token）。
        //      选「跟随系统」不设 data-theme，交 CSS 媒体查询；浅/深显式写属性盖过系统。
        //   ② 主页「查看全部」从纯摆设接成真行为：好友聚集地默认只露前 4 个，
        //      点了展开全部并切「收起」；不足 4 个时不显示该按钮。
        //   ③ 顺手把上一轮漏网的硬约束补进审计：共同好友入参必须是裸 {userId}、
        //      深色主题下不应再有任何 .app 级 surface 覆盖。
        // t4（vc36）收藏增删改（2026-09-30）：
        //   ① 删：收藏格「移除」→ vrchat_favorite_delete。objectId 必须是 fvrt_ 对象 id，
        //      从基线 remoteFavoritesById（fvrt→favoriteId/$groupKey）客户端自翻，零额外请求。
        //   ② 增：好友详情「收藏」(dFav) + 世界详情「收藏这个世界」(wFav) → favPickPanel 选夹
        //      → vrchat_favorite_add {type, favoriteId, tags=夹键}；容量满禁用。
        //   ③ 改：收藏页「新建收藏夹」+ 组头「编辑」→ vrchat_favorite_group_save
        //      {type, group, displayName, visibility}；新建键 = 同型最大序号+1，超上限拒绝。
        //   写后统一 refreshFavorites()：基线全失效回源，不做乐观删除。
        //   conn.js 导出 __ASK_CONFIRM__ 给「移除」复用确认框。
        versionCode = 36
        versionName = "0.2.31-t4"
    }

    buildTypes {
        release {
            // 纯 WebView 壳，没有需要 R8 压的字节码；保持不混淆，
            // 让 WebView 里的 JS 与资源路径维持原样。
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // 源码里有中文注释；不显式钉住 UTF-8 的话，在中文 Windows 上 AGP 可能
        // 按 GBK 读源码（javac 的默认值是平台编码），报一堆"不可映射字符"。
        encoding = "UTF-8"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}
