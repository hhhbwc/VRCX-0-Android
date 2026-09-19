# VRCX-0 Android 客户端接口文档

> 由 `.workbuddy/scripts/gen_android_api_doc.py` 从服务端自身源码生成。
> 服务端每次迁移命令后重跑该脚本即可，**不要手改本文件**。

## 1. 架构概览

Android 客户端是**瘦客户端**：本身不持有 VRChat 会话、不跑业务计算，
所有数据来自用户自部署的 VRCX-0 服务端（常驻设备 / VPS）。

```
Android App ──HTTP──> /v1/tenants  (领本机凭据；唯一的免鉴权写入口)
            ──HTTP──> /v1/command  (请求-响应，业务命令)
            ──HTTP──> /v1/auth/*   (登录；需凭据)
            ──WebSocket──> /v1/stream (服务端推送的实时事件)
```

| 项 | 值 |
|---|---|
| 协议版本 | `2`（`PROTOCOL_VERSION`） |
| 传输 | HTTP/1.1 + WebSocket，JSON |
| 鉴权 | `Authorization: Bearer <token>`；WS 用 `?token=` 查询参数 |
| 命令总数 | 278 条（服务端实现）/ 501 条（桌面全量） |

### 端点

**免鉴权面只有两个端点**，其余全部需要租户凭据（含登录）：

| 端点 | 方法 | 鉴权 | 用途 |
|---|---|---|---|
| `/v1/health` | GET | 否 | 健康检查 + 能力发现（含支持命令全表） |
| `/v1/tenants` | POST | 否 | **领取本机租户凭据**（首次免邀请码） |
| `/v1/tenants/token` | POST | 是 | 轮换本机凭据（旧的立即失效） |
| `/v1/auth/status` | GET | 是 | 本租户当前登录状态 |
| `/v1/auth/accounts` | GET | 是 | 本租户已保存账号列表（免密选择登录） |
| `/v1/auth/login` | POST | 是 | 用户名密码登录 / 按 `userId` 登录 |
| `/v1/auth/2fa` | POST | 是 | 回答二步验证挑战 |
| `/v1/auth/logout` | POST | 是 | 登出（丢会话，保留租户与凭据） |
| `/v1/command` | POST | 是 | **核心**：执行业务命令 |
| `/v1/stream` | GET(WS) | 是 | 实时事件流 |
| `/v1/rpc` | POST | 是 | 早期只读 RPC（新客户端用 `/v1/command` 即可） |
| `/v1/game-log/events` | POST | 是 | 游戏日志原文中继（由与游戏同机的客户端上传） |

> ⚠️ **顺序是强制的：先领凭据，再登录。**
> 登录接口之所以需要凭据，是因为服务端不再把 token 当作「登录成功的返回值」——
> 那样等于全服务器共用一把密钥，任何用户都能冒充他人。
> 凭据由 `POST /v1/tenants` 在登录前发放，服务端只存 SHA-256 摘要，
> 明文仅返回一次，丢失只能轮换。

## 2. 连接与认证流程

### 2.1 发现服务端

```http
GET /v1/health
```

```json
{ "status": "ok", "appVersion": "2.29.0", "protocolVersion": 2,
  "runtime": { "started": true, "error": null },
  "tenants": { "registered": 2, "live": 2, "running": 1 },
  "commands": { "calls": 1204, "ok": 1190, "unimplemented": 0,
                 "failed": 14, "supported": ["app__...", "..."] } }
```

`status` 为 `ok` 或 `degraded`；`degraded` 时 `runtime.error` 说明原因。

`tenants` 是**多租户**新增的：`registered` 是注册表中的用户数，
`live` 是本进程装载的运行时数，`running` 是其中后端起来了的数量。
客户端据此在领取凭据前判断**是否需要邀请码**：
`registered == 0` 说明这台服务器还没有任何用户，此时是第一个，免邀请码。

> ⚠️ **`/v1/health` 不能用来验证凭据**：它免鉴权，错 token 一样返回 200。
> 「能连上」和「凭据有效」是两件事；验证只能靠 `GET /v1/auth/status`，
> 401 即凭据失效或被吊销。

> ⚠️ 以这个 handler 为准，**不要**照 `remote-protocol` 里的 `HealthReport`
> 结构体——那个结构体没被用上，且描述的是另一种形状
> （它有 `currentUserId` / `endpoint` / `authenticated`，实际线上都没有）。

**必须先拉一次 `supported` 表**：客户端只在启动时拉一次，
只对该表中存在的命令走服务端；不在表中的命令服务端会返回 `501`。
服务端新增命令后，客户端需重启才会看到（或主动重拉）。

### 2.2 领取租户凭据（登录之前）

客户端只拿到一个地址时，先领凭据，再谈登录。

```http
POST /v1/tenants
{ "label": "Pixel 7", "inviteCode": "..." }   // inviteCode 可省略
```

`label` 是运维在 `--list-tenants` 里看到的名称，用来区分设备；
**不能为空**，建议取设备名（`Build.MODEL`）。
`inviteCode` 只有服务器已有用户时才需要，可省略；
空白务必**整个字段省掉**而不是传 `""`，服务端区分这两种情况。

成功：

```json
{ "tenantId": "t_ab12", "label": "Pixel 7", "token": "vx1_..." }
```

失败（HTTP 状态码 + 机器可读的 `kind`）：

| HTTP | `kind` | 含义 |
|---|---|---|
| 403 | `admissionClosed` | 已有用户且邀请码缺失/错误 |
| 400 | `invalidLabel` | `label` 去空白后为空 |
| 409 | `duplicateLabel` | `label` 已被占用 |
| 500 | `other` | 其它 |

```json
{ "message": "a tenant label cannot be empty", "kind": "invalidLabel" }
```

丢失凭据只能**轮换**，不能找回（服务端只存摘要）：

```http
POST /v1/tenants/token
Authorization: Bearer <旧 token>
```

### 2.3 登录本租户的 VRChat 账号

**以下请求全部需要 `Authorization: Bearer <凭据>`。**

推荐「选择已保存账号」流程，用户不必输密码：

```http
GET /v1/auth/accounts
Authorization: Bearer <凭据>
```

```json
{ "authenticated": false, "loginAvailable": true,
  "currentUserId": "", "currentDisplayName": null,
  "accounts": [ { "userId": "usr_x", "displayName": "名字",
                  "username": "账号", "iconUrl": "https://..." } ] }
```

（`displayName` / `username` / `iconUrl` 为可选字段，可能缺失。）

`GET /v1/auth/status` 另有独立形状，用于判断服务端当前是否已有会话：

```json
{ "authenticated": true, "userId": "usr_x", "displayName": "名字",
  "endpoint": "https://api.vrchat.cloud/api/1",
  "loginAvailable": true, "runtimeError": null }
```

```http
POST /v1/auth/login
{ "userId": "usr_x" }            // 命中保存的 cookie 则直接登录
```

密码登录：`{ "username": "...", "password": "...",
"saveCredentials": true }`。

四种结果都用 **同一形状**返回（`tag = "status"`），客户端只需一个解析器：

```json
{ "status": "authenticated", "userId": "usr_x", "displayName": "...",
  "endpoint": "https://api.vrchat.cloud/api/1" }
{ "status": "challenge", "attemptId": "att_1", "methods": ["totp"],
  "error": null }
{ "status": "failed", "reason": "...", "kind": "rateLimited" }
{ "status": "cancelled" }
```

> ⚠️ `authenticated` 里**不再有 `token` 字段**（旧服务端会返回，新服务端为 `null`/缺失）。
> 租户凭据在你登录之前就已经拿到了，登录响应再发一遍等于把已有密钥又过一遍网。
> 客户端应**保留手上那把凭据**，不要用它覆盖。

`challenge.error` 在上一次验证码被拒时出现；`cancelled` 表示登录被中途取消。
服务端对登录接口有**速率限制**：5 分钟内最多 8 次尝试，
且同一时刻只允许一个登录流程在跑（第二种情况返回 `busy`）。

收到 `challenge` 后：

```http
POST /v1/auth/2fa
{ "attemptId": "att_1", "method": "totp", "code": "123456" }
```

`kind` 取值：`invalidCredentials | missingCredentials |
sessionInvalidated | twoFactorUnavailable | network | other |
rateLimited | busy`。

> VRChat 的凭据只在服务端保存，客户端用完即弃。客户端唯一需要持久化的
> 是**租户 token**——它不是登录产物，而是这台设备在服务器上的身份，
> 生命周期比任何一次登录都长。Android 端应把它存进
> EncryptedSharedPreferences / Keystore。

> ⚠️ **登出 ≠ 放弃凭据**。`/v1/auth/logout` 只丢 VRChat 会话，
> 租户与凭据保留（回到「待登录」）。只有用户明确「忘记服务器」时才清地址+凭据；
> 否则每次登出都会在运维的租户注册表里留下一个死租户。

## 3. 调用业务命令 `POST /v1/command`

请求信封：

```json
{ "command": "app__xxx", "args": { ... } }
```

**两种参数形态**，按命令而定（见第 6 节清单）：

1. **扁平命名参数** —— 参数与 `command` 平级放在 `args` 里，camelCase：

   ```json
   { "command": "app__game_log_entries_add",
     "args": { "kind": "Location", "entries": [ { ... } ] } }
   ```

2. **整体 input 对象** —— 命令体整体放在 `args.input` 下：

   ```json
   { "command": "app__instance_history_query",
     "args": { "input": { "userId": "usr_x", "limit": 50 } } }
   ```

**无参命令**直接传 `"args": {}`。

### 响应

- 成功：命令原本的返回值（类型随命令而异，可能是对象/数组/数字）
- 失败：`{ "message": "..." }`，HTTP 状态码非 2xx
- 未实现：`501` + `{ "message": ... }`（不应发生，请先查 supported 表）

### curl 示例

```bash
curl -X POST http://192.168.1.1:8790/v1/command \
  -H 'Authorization: Bearer <token>' \
  -H 'Content-Type: application/json' \
  -d '{"command":"app__instance_history_query","args":{"input":{"userId":"usr_x"}}}'
```

## 4. 实时事件 `GET /v1/stream?token=<token>`

WebSocket。浏览器/部分客户端无法自定义 header，故 token 走查询参数。

帧格式（`tag = "kind"`，**扁平**，不是嵌套）：

```json
{ "kind": "hello", "protocolVersion": 2, "appVersion": "2.29.0" }
{ "kind": "event", "event": "realtimeFeedProjection",
  "payload": { ... }, "seq": 42 }
{ "kind": "lagged", "skipped": 12 }
```

- `hello`：连接后第一条，先校验 `protocolVersion` 再解释后续帧
- `event`：`seq` 单调递增，可用于检测丢帧
- `lagged`：客户端落后太多被跳过，**必须**用一次全量查询对账

### 已知事件名（54 个）

- `Example`
- `appLauncherSnapshot`
- `appUpdateDownloadProgress`
- `appUpdateInstalled`
- `appUpdateStatus`
- `assistantDelta`
- `assistantDone`
- `assistantError`
- `assistantToolCall`
- `assistantToolResult`
- `assistantTurnEntities`
- `authenticatedRuntimePhase`
- `authenticatedSessionProjection`
- `backendRuntimeTelemetry`
- `backgroundImageState`
- `communityThemeState`
- `dataDirMigration`
- `favoriteImportStatus`
- `favoritesChanged`
- `friendProfileLoadStatus`
- `gameClientEvent`
- `gameLogPersistenceFallback`
- `gameLogProjection`
- `gameLogSideEffect`
- `groupBanImportStatus`
- `groupMembershipBatchProgress`
- `groupModerationBatchProgress`
- `integrationApiStartFailed`
- `mutualGraphFetchStatus`
- `noteExportStatus`
- `notificationDoNotDisturbState`
- `printsAutoCleanup`
- `privacyLockState`
- `profileBackupStatus`
- `profileRestoreProgress`
- `realtimeCurrentUserProjection`
- `realtimeEntryCorrection`
- `realtimeFeedProjection`
- `realtimeFriendProjection`
- `realtimeInstanceClosedProjection`
- `realtimeInstanceQueueProjection`
- `realtimeNotificationProjection`
- `realtimeProjectionSync`
- `realtimeUserProjection`
- `realtimeWsStatus`
- `runtimeGameLogEvent`
- `runtimeGroupInstancesProjection`
- `runtimeVrchatAuthFailure`
- `runtimeWorkerError`
- `screenshotLibraryScanStatus`
- `sharedCollectionImportStatus`
- `testPayload`
- `updateIsGameRunning`
- `vrcStatus`

### 断线重连

退避重连（如 1s → 30s 上限）。重连后**不依赖**事件补发：
服务端不重放历史事件，客户端应在重连成功后主动调一次全量查询
（如 `app__backend_runtime_combined_snapshot_get`）重新对齐状态。

## 5. 命令清单（278 条，按功能分组）

参数列：`input` 表示整体对象形态；否则列出扁平参数名；`-` 表示无参。

### 认证 / 当前用户（9）

| 命令 | 参数 |
|---|---|
| `app__current_user_refresh` | — |
| `app__vrchat_auth_current_user_get` | — |
| `app__vrchat_auth_file_analysis_get` | `input` |
| `app__vrchat_auth_visits_get` | — |
| `app__vrchat_current_user_badge_update` | `input` |
| `app__vrchat_current_user_profile_update` | `input` |
| `app__vrchat_current_user_tags_add` | `input` |
| `app__vrchat_current_user_tags_remove` | `input` |
| `app__vrchat_current_user_update` | `input` |

### 用户 / 好友 / 社交（23）

| 命令 | 参数 |
|---|---|
| `app__friend_log_current_list` | `userId` |
| `app__friend_log_history_delete` | `userId`, `entry` |
| `app__friend_log_history_query` | `query` |
| `app__friend_log_names_cancel` | `requestId` |
| `app__friend_log_names_resolve` | `input` |
| `app__friend_profile_load_cancel` | — |
| `app__friend_profile_load_start` | — |
| `app__mutual_graph_fetch_cancel` | `input` |
| `app__mutual_graph_fetch_start` | `input` |
| `app__mutual_graph_friend_refresh` | `input` |
| `app__mutual_graph_snapshot_get` | `userId` |
| `app__social_baseline_refresh` | — |
| `app__social_favorites_baseline_get` | `input` |
| `app__social_friend_request_cancel` | `input` |
| `app__social_friend_request_notification_accept` | `input` |
| `app__social_friend_request_send` | `input` |
| `app__social_friend_roster_baseline_get` | `input` |
| `app__social_unfriend` | `input` |
| `app__social_unfriend_selection` | `input` |
| `app__user_mutual_friends_list_get` | `input` |
| `app__vrchat_user_get` | `input` |
| `app__vrchat_user_profile_get` | `input` |
| `app__vrchat_user_represented_group_get` | `input` |

### 动态 Feed（4）

| 命令 | 参数 |
|---|---|
| `app__feed_latest_query` | `query` |
| `app__feed_persistence_set_disabled` | `disabled` |
| `app__feed_rows_query` | `query` |
| `app__feed_search_query` | `query` |

### 通知（17）

| 命令 | 参数 |
|---|---|
| `app__notification_add_v1` | `userId`, `notification` |
| `app__notification_add_v2` | `userId`, `notification` |
| `app__notification_boop_dismiss` | `input` |
| `app__notification_boop_reply` | `input` |
| `app__notification_delete` | `userId`, `id` |
| `app__notification_expire` | `userId`, `id` |
| `app__notification_hide_and_expire` | `input` |
| `app__notification_instance_invite_send` | `input` |
| `app__notification_invite_response_send` | `input` |
| `app__notification_list_query` | `query` |
| `app__notification_mark_seen_batch` | `input` |
| `app__notification_request_invite_accept` | `input` |
| `app__notification_respond_and_expire` | `input` |
| `app__notification_sync` | — |
| `app__notification_update_expired` | `userId`, `id`, `expired` |
| `app__notification_v2_expire` | `userId`, `id` |
| `app__notification_v2_mark_seen` | `userId`, `id` |

### 世界 / 实例（19）

| 命令 | 参数 |
|---|---|
| `app__instance_activity_dates_get` | `userId` |
| `app__instance_activity_rows_get` | `startDate`, `endDate` |
| `app__instance_history_query` | `input` |
| `app__vrchat_instance_close` | `input` |
| `app__vrchat_instance_create` | `input` |
| `app__vrchat_instance_get` | `input` |
| `app__vrchat_instance_self_invite` | `input` |
| `app__vrchat_instance_short_name_get` | `input` |
| `app__vrchat_world_delete` | `input` |
| `app__vrchat_world_list_by_user_get` | `input` |
| `app__vrchat_world_persistent_data_delete` | `input` |
| `app__vrchat_world_persistent_data_exists` | `input` |
| `app__vrchat_world_publish` | `input` |
| `app__vrchat_world_save` | `input` |
| `app__vrchat_world_unpublish` | `input` |
| `app__world_friend_visits` | `worldId` |
| `app__world_get` | `input` |
| `app__world_open_register` | `worldId` |
| `app__world_summaries_get` | `worldIds` |

### 游戏日志 / 房间历史（8）

| 命令 | 参数 |
|---|---|
| `app__game_log_entries_add` | `kind`, `entries` |
| `app__game_log_entry_delete` | `kind`, `entry` |
| `app__game_log_instance_delete` | `location`, `eventIds` |
| `app__game_log_instance_delete_by_location` | `location` |
| `app__game_log_previous_instances_by_group_id` | `groupId` |
| `app__game_log_previous_instances_by_world_id` | `worldId` |
| `app__game_log_query` | `query` |
| `app__game_log_sessions_query` | `input` |

### 活动统计（3）

| 命令 | 参数 |
|---|---|
| `app__activity_overlap_view` | `input` |
| `app__activity_page_view` | `input` |
| `app__activity_view` | `input` |

### 头像（35）

| 命令 | 参数 |
|---|---|
| `app__avatar_content_tags_batch` | `input` |
| `app__avatar_feed_history_cleanup` | `cutoffDate` |
| `app__avatar_feed_persistence_set_disabled` | `disabled` |
| `app__avatar_find_by_image_url` | `imageUrl` |
| `app__avatar_get` | `input` |
| `app__avatar_history_clear` | `userId` |
| `app__avatar_history_list` | `userId`, `limit` |
| `app__avatar_tag_add` | `avatarId`, `tag`, `color` |
| `app__avatar_tag_remove` | `avatarId`, `tag` |
| `app__avatar_tag_update_color` | `avatarId`, `tag`, `color` |
| `app__avatar_tags_distinct` | — |
| `app__avatar_tags_get` | `avatarId` |
| `app__avatar_tags_list` | — |
| `app__avatar_tags_patch` | `avatarId`, `patch` |
| `app__avatar_tags_remove_all` | `avatarId` |
| `app__avatar_tags_replace` | `avatarId`, `entries` |
| `app__avatar_time_spent_add` | `userId`, `avatarId`, `timeSpent` |
| `app__avatar_time_spent_get` | `userId`, `avatarId` |
| `app__avatar_time_spent_list` | `userId` |
| `app__avatar_usage_ranking` | `userId`, `limit` |
| `app__my_avatar_by_id_get` | `input` |
| `app__my_avatars_get` | `input` |
| `app__vrchat_avatar_delete` | `input` |
| `app__vrchat_avatar_file_get` | `input` |
| `app__vrchat_avatar_gallery_get` | `input` |
| `app__vrchat_avatar_impostor_create` | `input` |
| `app__vrchat_avatar_impostor_delete` | `input` |
| `app__vrchat_avatar_list_by_user_get` | `input` |
| `app__vrchat_avatar_moderation_delete` | `input` |
| `app__vrchat_avatar_moderation_send` | `input` |
| `app__vrchat_avatar_moderations_get` | — |
| `app__vrchat_avatar_save` | `input` |
| `app__vrchat_avatar_select` | `input` |
| `app__vrchat_avatar_select_fallback` | `input` |
| `app__vrchat_avatar_styles_get` | — |

### 收藏（21）

| 命令 | 参数 |
|---|---|
| `app__favorite_cache_snapshot` | `input` |
| `app__favorite_details_hydrate` | `input` |
| `app__favorite_import_cancel` | — |
| `app__favorite_import_dismiss` | `runId` |
| `app__favorite_import_start` | `input` |
| `app__favorite_import_status` | — |
| `app__favorite_list` | `kind` |
| `app__favorite_local_snapshot` | `kind` |
| `app__favorites_remove_selection` | `input` |
| `app__favorites_transfer_selection` | `input` |
| `app__local_favorite_add` | `kind`, `entityId`, `groupName` |
| `app__local_favorite_group_create` | `kind`, `groupName` |
| `app__local_favorite_group_delete` | `kind` |
| `app__local_favorite_group_rename` | `kind`, `groupName`, `newGroupName` |
| `app__local_favorite_remove` | `kind` |
| `app__vrchat_favorite_add` | `input` |
| `app__vrchat_favorite_delete` | `input` |
| `app__vrchat_favorite_group_clear` | `input` |
| `app__vrchat_favorite_group_save` | `input` |
| `app__vrchat_favorite_groups_get` | `input` |
| `app__vrchat_favorite_worlds_get` | `input` |

### 群组（44）

| 命令 | 参数 |
|---|---|
| `app__group_ban_import_cancel` | — |
| `app__group_ban_import_start` | `input` |
| `app__group_ban_import_status` | — |
| `app__group_calendar_snapshot_get` | `input` |
| `app__group_membership_batch` | `input` |
| `app__group_moderation_batch` | `input` |
| `app__saved_group_collection_create` | `input` |
| `app__saved_group_collection_delete` | `input` |
| `app__saved_group_favorite_add` | `input` |
| `app__saved_group_favorite_remove` | `input` |
| `app__saved_group_favorites_get` | — |
| `app__user_group_quick_moderation_action` | `input` |
| `app__user_group_quick_moderation_get` | `input` |
| `app__vrchat_group_audit_log_types_get` | `input` |
| `app__vrchat_group_bans_get` | `input` |
| `app__vrchat_group_block` | `input` |
| `app__vrchat_group_gallery_get` | `input` |
| `app__vrchat_group_get` | `input` |
| `app__vrchat_group_invite_delete` | `input` |
| `app__vrchat_group_invite_send` | `input` |
| `app__vrchat_group_invites_get` | `input` |
| `app__vrchat_group_join` | `input` |
| `app__vrchat_group_join_request_respond` | `input` |
| `app__vrchat_group_join_requests_get` | `input` |
| `app__vrchat_group_leave` | `input` |
| `app__vrchat_group_logs_get` | `input` |
| `app__vrchat_group_member_ban` | `input` |
| `app__vrchat_group_member_get` | `input` |
| `app__vrchat_group_member_kick` | `input` |
| `app__vrchat_group_member_props_set` | `input` |
| `app__vrchat_group_member_role_add` | `input` |
| `app__vrchat_group_member_role_remove` | `input` |
| `app__vrchat_group_member_unban` | `input` |
| `app__vrchat_group_members_get` | `input` |
| `app__vrchat_group_members_search` | `input` |
| `app__vrchat_group_post_create` | `input` |
| `app__vrchat_group_post_delete` | `input` |
| `app__vrchat_group_post_edit` | `input` |
| `app__vrchat_group_posts_get` | `input` |
| `app__vrchat_group_representation_set` | `input` |
| `app__vrchat_group_request_cancel` | `input` |
| `app__vrchat_group_unblock` | `input` |
| `app__vrchat_group_user_groups_get` | `input` |
| `app__vrchat_group_user_instances_get` | `input` |

### 搜索（5）

| 命令 | 参数 |
|---|---|
| `app__vrchat_search_groups_get` | `input` |
| `app__vrchat_search_groups_strict_get` | `input` |
| `app__vrchat_search_instance_short_name_get` | `input` |
| `app__vrchat_search_users_get` | `input` |
| `app__vrchat_search_worlds_get` | `input` |

### 媒体 / 文件（21）

| 命令 | 参数 |
|---|---|
| `app__vrchat_media_asset_upload` | `input` |
| `app__vrchat_media_avatar_gallery_image_upload` | `input` |
| `app__vrchat_media_file_delete` | `input` |
| `app__vrchat_media_files_get` | `input` |
| `app__vrchat_media_inventory_bundle_consume` | `input` |
| `app__vrchat_media_inventory_item_update` | `input` |
| `app__vrchat_media_inventory_items_collect` | `input` |
| `app__vrchat_media_inventory_items_get` | `input` |
| `app__vrchat_media_inventory_template_get` | `input` |
| `app__vrchat_media_print_delete` | `input` |
| `app__vrchat_media_print_get` | `input` |
| `app__vrchat_media_print_upload` | `input` |
| `app__vrchat_media_prints_get` | `input` |
| `app__vrchat_media_profile_decoration_equip` | `input` |
| `app__vrchat_media_profile_decoration_unequip` | `input` |
| `app__vrchat_media_reward_redeem` | `input` |
| `app__vrchat_media_sticker_upload` | `input` |
| `app__vrchat_media_user_inventory_item_get` | `input` |
| `app__vrchat_prints_favorite_set` | `input` |
| `app__vrchat_prints_favorites_list` | — |
| `app__vrchat_prints_favorites_set` | `input` |

### 工具 / 邀请 / 日历（8）

| 命令 | 参数 |
|---|---|
| `app__vrchat_tools_following_calendars_get` | `input` |
| `app__vrchat_tools_group_calendar_get` | `input` |
| `app__vrchat_tools_group_calendar_ics_get` | `input` |
| `app__vrchat_tools_group_event_follow` | `input` |
| `app__vrchat_tools_invite_message_edit` | `input` |
| `app__vrchat_tools_invite_messages_get` | `input` |
| `app__vrchat_tools_user_note_save` | `input` |
| `app__vrchat_tools_user_report` | `input` |

### 分享合集（4）

| 命令 | 参数 |
|---|---|
| `app__share_collection_create` | `input` |
| `app__share_collection_preview` | `id` |
| `app__shared_collection_import_start` | `input` |
| `app__shared_collection_import_status` | — |

### 备忘 / 笔记（8）

| 命令 | 参数 |
|---|---|
| `app__memo_get_avatar` | `avatarId` |
| `app__memo_get_user` | `userId` |
| `app__memo_get_world` | `worldId` |
| `app__memo_list_user_notes` | `ownerUserId` |
| `app__memo_list_users` | — |
| `app__memo_save_avatar` | `avatarId`, `memo` |
| `app__memo_save_user` | `userId`, `memo` |
| `app__memo_save_world` | `worldId`, `memo` |

### moderation / 审核（4）

| 命令 | 参数 |
|---|---|
| `app__local_moderation_get` | `ownerUserId`, `userId` |
| `app__local_moderation_list` | `ownerUserId` |
| `app__moderation_sync_refresh` | `input` |
| `app__moderation_sync_update` | `input` |

### 配置 / 设置（3）

| 命令 | 参数 |
|---|---|
| `app__config_list_values` | — |
| `app__config_remove_value` | `key` |
| `app__config_set_values` | `entries` |

### 浏览历史（6）

| 命令 | 参数 |
|---|---|
| `app__browse_history_clear` | `ownerUserId`, `entityKind` |
| `app__browse_history_delete` | `ownerUserId`, `entityKind`, `entityId` |
| `app__browse_history_query` | `input` |
| `app__browse_history_record` | `input` |
| `app__browse_history_retention_days_get` | — |
| `app__browse_history_retention_days_set` | `retentionDays` |

### 备份 / 恢复（13）

| 命令 | 参数 |
|---|---|
| `app__profile_backup_current_status` | — |
| `app__profile_backup_discard_pending` | — |
| `app__profile_backup_dismiss_error` | — |
| `app__profile_backup_get_settings` | — |
| `app__profile_backup_retry_delivery` | — |
| `app__profile_backup_run_manual` | `targetPath` |
| `app__profile_backup_set_settings` | `input` |
| `app__profile_restore_clear_rollback` | — |
| `app__profile_restore_discard_staged` | — |
| `app__profile_restore_request` | `expectedSha256` |
| `app__profile_restore_rollback_state` | — |
| `app__profile_restore_take_last_result` | — |
| `app__profile_restore_validate` | `path` |

### 数据库维护（5）

| 命令 | 参数 |
|---|---|
| `app__database_maintenance_broken_game_log_display_names_get` | — |
| `app__database_maintenance_broken_leave_entries_get` | — |
| `app__database_maintenance_max_friend_log_number_get` | `userId` |
| `app__database_maintenance_table_sizes_get` | `userId` |
| `app__user_tables_ensure` | `userId` |

### 外部 API（5）

| 命令 | 参数 |
|---|---|
| `app__external_api_avatar_search_get` | `input` |
| `app__external_api_github_contributors_get` | `input` |
| `app__external_api_github_releases_get` | `input` |
| `app__external_api_image_data_url_get` | `input` |
| `app__external_api_youtube_video_metadata_get` | `input` |

### 运行时 / 杂项（4）

| 命令 | 参数 |
|---|---|
| `app__backend_runtime_combined_snapshot_get` | — |
| `app__ingest_user_facts` | `entries` |
| `app__vrc_status_get` | — |
| `app__vrc_status_refresh` | — |

### 其他（9）

| 命令 | 参数 |
|---|---|
| `app__instance_invite_batch` | `input` |
| `app__note_export_cancel` | — |
| `app__note_export_start` | `input` |
| `app__note_export_status` | — |
| `app__user_groups_overview_get` | `input` |
| `app__vrchat_boop_send` | `input` |
| `app__vrchat_friend_status_get` | `input` |
| `app__vrchat_request_invite_photo_send` | `input` |
| `app__vrchat_request_invite_send` | `input` |

## 6. 未迁移命令与 Android 可用性（223 条）

桌面端共 501 条命令，服务端实现 278 条。剩余 223 条分两类：

### 6.1 桌面专属 —— Android 本就不需要

窗口/托盘/截图/进程监控/启动游戏/本地文件与注册表/VR 覆盖层/
助手与 MCP/TTS/应用自更新/字体/代理 等依赖本机的能力，
手机上不存在对应概念，无需迁移。

### 6.2 真正的功能缺口（Android 需要，但服务端尚未实现）

以下是应在服务端补齐的关键项，按优先级：

| 命令 | 缺失后影响 |
|---|---|
| `app__quick_search_query` | 全局搜索不可用（手机端高频功能） |
| `app__player_list_current_snapshot` | 看不到当前房间的实时玩家列表（依赖本地游戏状态） |
| `app__ancillary_runtime_snapshot_get` | 登录水合失败会导致界面卡在加载态 |
| `app__get_vrchat_user_moderation` | 用户审核状态读不到 |
| `app__set_vrchat_user_moderation` | 无法设置用户审核 |
| `app__vrchat_group_order_get` | 群组自定义排序丢失 |
| `app__vrchat_group_order_set` | 无法保存群组排序 |
| `app__notification_activity_filters_set` | 通知过滤设置无法保存 |
| `app__notification_do_not_disturb_mode_set` | 免打扰设置无法保存 |
| `app__vrchat_media_emoji_upload` | 需要字节上传通道（表情） |
| `app__vrchat_media_gallery_image_upload` | 需要字节上传通道（相册图） |
| `app__vrchat_media_vrc_plus_icon_upload` | 需要字节上传通道（图标） |
| `app__vrchat_instance_join` | 启动本机游戏，手机上无意义 |
| `app__translation_translate` | 富文本翻译（可选） |

> 其余未列出项多为桌面专属或可选功能。字节上传类命令需要服务端
> 新增 multipart/二进制通道，是独立的一块工作。

## 7. Android 端实现要点

### 7.1 传输层

- 启动流程：`/v1/health` 拉 supported 并读 `tenants.registered` →
  无凭据则 `POST /v1/tenants` 领凭据（按需带邀请码）→
  有凭据则 `GET /v1/auth/status` 校验（401 说明被吊销/轮换，清掉重领）→
  已登录直接进主界面，否则走登录 → 成功后连 `/v1/stream`。
- 统一入口：所有命令走一个 `invoke(command, args)`，
  内部查 supported 表决定走服务端还是报错。
- 参数形态按第 5 节清单区分 `input` 与扁平参数——**这是最容易出错的地方**，
  传错形态会得到 `missing \`input\` argument` 之类错误。
- 所有字段 camelCase；时间字段为 ISO-8601 字符串。

### 7.2 平板（横屏）/ 手机（竖屏）双模式

服务端**完全不感知**布局，模式切换纯客户端实现：

| | 手机竖屏 | 平板横屏 |
|---|---|---|
| 导航 | 底部导航栏 | 侧边 Navigation Rail / 抽屉 |
| 列表+详情 | 分屏跳转（点进详情占满屏） | 双栏并排（左列表右详情） |
| 内容密度 | 单列 | 多列网格 / 更宽卡片 |

要点：

1. 用 `WindowSizeClass`（宽度/高度等级）判断形态，**不要用屏幕英寸或固定 dp 阈值**。
2. 模式要能**手动切换并持久化**（用户偏好优先于自动判定），
   存 DataStore。
3. 竖屏↔横屏旋转时保留列表滚动位置与选中项，
   用 `rememberSaveable` + ViewModel 持有选中 id，避免重建丢状态。
4. 列表用分页/懒加载：命令返回量可能很大（如 feed、好友列表），
   配合 `Paging3` 或手动分页参数。

### 7.3 数据一致性

- 首屏用命令全量拉取，之后靠 WS 事件增量更新。
- 收到 `lagged` 或重连后，重新全量查询对账。
- 游戏日志/房间历史的数据由**与 VRChat 同机的桌面客户端**上传，
  手机只是读取方——若服务端没有该数据，说明还没有桌面客户端上报过。

