#!/usr/bin/env python3
"""Generate docs/ANDROID_CLIENT_API.md from the server's own source of truth.

Why a generator instead of a hand-written doc: the command surface is the
contract, and it moves. Every server-side migration adds a command, and a
hand-maintained list would silently drift within a week. This reads the same
dispatch tables the server compiles, so the doc cannot lie about what exists.

Inputs
  remote-server/src/commands/*.rs  -- command arms and their `argument()` keys
  src-tauri/src/**/*.rs            -- the full desktop command surface (for gaps)

Output
  docs/ANDROID_CLIENT_API.md
  docs/ANDROID_API-commands.json   (machine-readable, for code generation)
"""
from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Iterable

ROOT = Path(__file__).resolve().parents[2]
SERVER_CMDS = ROOT / "src" / "crates" / "remote-server" / "src" / "commands"
DESKTOP = ROOT / "src" / "src-tauri" / "src"
CRATES = ROOT / "src" / "crates"
DOCS = ROOT / "docs"

# ---------------------------------------------------------------- extraction

# `"app__a" | "app__b" =>` arms, possibly wrapped over lines by rustfmt.
CMD_ITEM = re.compile(r'"(app__[a-z0-9_]+)"')
ARG_ANY = re.compile(r'argument\(\s*(?:&?\w+\.)?args\s*,\s*"([A-Za-z0-9_]+)"\s*\)')
PARSE_ANY = re.compile(r'\b(?:parse_required|parse)\s*\(\s*args\s*\)')
HAND_LIST = re.compile(
    r'const HAND_WRITTEN_COMMANDS:\s*&\[&str\]\s*=\s*&\[(.*?)\];', re.S
)
EVENT_PAYLOAD = re.compile(
    r'runtime_event_payload!\(\s*[A-Za-z0-9_]+,\s*"([a-zA-Z0-9_]+)"'
)
EVENT_NAME = re.compile(r'EVENT_NAME[^;]*?"([a-zA-Z0-9_]+)"')


def _merge_or_arms(lines: list[str]) -> list[str]:
    """Rejoin `"app__a" | "app__b" =>` that rustfmt split across lines."""
    merged: list[str] = []
    for raw in lines:
        line = raw.rstrip()
        if merged and (merged[-1].rstrip().endswith("|")
                       or line.lstrip().startswith("|")):
            merged[-1] = merged[-1].rstrip() + " " + line.lstrip()
        else:
            merged.append(line)
    return merged


def commands() -> dict[str, dict]:
    out: dict[str, dict] = {}

    def note(name: str, args: Iterable[str], uses_input: bool, src: str) -> None:
        e = out.setdefault(name, {"args": [], "input": False, "sources": []})
        for a in args:
            if a not in e["args"]:
                e["args"].append(a)
        e["input"] = e["input"] or uses_input
        if src not in e["sources"]:
            e["sources"].append(src)

    for path in sorted(SERVER_CMDS.glob("*.rs")):
        lines = _merge_or_arms(path.read_text(encoding="utf-8").splitlines())
        starts = []
        for i, line in enumerate(lines):
            if "app__" not in line or "=>" not in line:
                continue
            names = CMD_ITEM.findall(line)
            if names:
                starts.append((i, names, "{" in line))
        for idx, (start, names, braced) in enumerate(starts):
            nxt = starts[idx + 1][0] if idx + 1 < len(starts) else start + 4
            end = nxt if braced else min(nxt, start + 4)
            block = "\n".join(lines[start:end])
            args = ARG_ANY.findall(block)
            uses_input = bool(PARSE_ANY.search(block)) or "input" in args
            args = [a for a in args if a != "input"]
            for name in names:
                note(name, args, uses_input, path.name)

        text = path.read_text(encoding="utf-8")
        for m in HAND_LIST.finditer(text):
            for name in CMD_ITEM.findall(m.group(1)):
                note(name, [], False, path.name)
        if path.name == "mod.rs":
            for m in re.finditer(
                r'CommandKind::(\w+)\s*=>\s*\{(.*?)\n        \}', text, re.S
            ):
                note("__kind__" + m.group(1), ARG_ANY.findall(m.group(2)),
                     bool(PARSE_ANY.search(m.group(2))), "mod.rs")
    # resolve CommandKind placeholders to their real command names
    mod = (SERVER_CMDS / "mod.rs").read_text(encoding="utf-8")
    aliases = dict(
        (f"__kind__{kind}", name)
        for name, kind in re.findall(
            r'"(app__[a-z0-9_]+)"\s*=>\s*Some\(CommandKind::(\w+)\)', mod
        )
    )
    resolved: dict[str, dict] = {}
    for name, e in out.items():
        real = aliases.get(name, name)
        t = resolved.setdefault(real, {"args": [], "input": False, "sources": []})
        for a in e["args"]:
            if a not in t["args"]:
                t["args"].append(a)
        t["input"] = t["input"] or e["input"]
        for s in e["sources"]:
            if s not in t["sources"]:
                t["sources"].append(s)
    return resolved


def events() -> list[str]:
    found: set[str] = set()
    for path in CRATES.rglob("*.rs"):
        text = path.read_text(encoding="utf-8", errors="ignore")
        found.update(EVENT_PAYLOAD.findall(text))
        found.update(EVENT_NAME.findall(text))
    return sorted(found)


def desktop_commands() -> list[str]:
    pat = re.compile(r'\bapp__[a-z0-9_]+')
    found: set[str] = set()
    for path in DESKTOP.rglob("*.rs"):
        found.update(pat.findall(path.read_text(encoding="utf-8",
                                                errors="ignore")))
    return sorted(found)


# ---------------------------------------------------------------- grouping

GROUPS: list[tuple[str, tuple[str, ...]]] = [
    ("认证 / 当前用户", ("vrchat_auth_", "current_user", "vrchat_current_user_")),
    ("用户 / 好友 / 社交", ("vrchat_user_", "friend_log_", "mutual_graph_",
                            "user_mutual_", "social_", "friend_profile_")),
    ("动态 Feed", ("feed_",)),
    ("通知", ("notification_",)),
    ("世界 / 实例", ("vrchat_world_", "world_", "vrchat_instance_",
                     "instance_activity_", "instance_history_")),
    ("游戏日志 / 房间历史", ("game_log_",)),
    ("活动统计", ("activity_",)),
    ("头像", ("vrchat_avatar_", "avatar_", "my_avatar")),
    ("收藏", ("favorite", "favorites_", "local_favorite", "vrchat_favorite_")),
    ("群组", ("vrchat_group_", "group_", "user_group_", "saved_group_")),
    ("搜索", ("vrchat_search_",)),
    ("媒体 / 文件", ("vrchat_media_", "vrchat_prints_")),
    ("工具 / 邀请 / 日历", ("vrchat_tools_",)),
    ("分享合集", ("share_", "shared_collection_")),
    ("备忘 / 笔记", ("memo_",)),
    (" moderation / 审核", ("moderation_", "local_moderation_")),
    ("配置 / 设置", ("config_",)),
    ("浏览历史", ("browse_history_",)),
    ("备份 / 恢复", ("profile_backup_", "profile_restore_")),
    ("数据库维护", ("database_maintenance_", "user_tables_")),
    ("外部 API", ("external_api_",)),
    ("运行时 / 杂项", ("backend_runtime_", "vrc_status", "world_friend_",
                       "ingest_user_facts", "moderation_sync_")),
]


def group_of(name: str) -> str:
    body = name[len("app__"):]
    for label, prefixes in GROUPS:
        for p in prefixes:
            if body.startswith(p):
                return label
    return "其他"


def render(cmds: dict[str, dict], evts: list[str],
           desk: list[str]) -> str:
    sup = sorted(cmds)
    L: list[str] = []
    w = L.append

    w("# VRCX-0 Android 客户端接口文档")
    w("")
    w("> 由 `.workbuddy/scripts/gen_android_api_doc.py` 从服务端自身源码生成。")
    w("> 服务端每次迁移命令后重跑该脚本即可，**不要手改本文件**。")
    w("")
    w("## 1. 架构概览")
    w("")
    w("Android 客户端是**瘦客户端**：本身不持有 VRChat 会话、不跑业务计算，")
    w("所有数据来自用户自部署的 VRCX-0 服务端（常驻设备 / VPS）。")
    w("")
    w("```")
    w("Android App ──HTTP──> /v1/tenants  (领本机凭据；唯一的免鉴权写入口)")
    w("            ──HTTP──> /v1/command  (请求-响应，业务命令)")
    w("            ──HTTP──> /v1/auth/*   (登录；需凭据)")
    w("            ──WebSocket──> /v1/stream (服务端推送的实时事件)")
    w("```")
    w("")
    w("| 项 | 值 |")
    w("|---|---|")
    w("| 协议版本 | `2`（`PROTOCOL_VERSION`） |")
    w("| 传输 | HTTP/1.1 + WebSocket，JSON |")
    w("| 鉴权 | `Authorization: Bearer <token>`；WS 用 `?token=` 查询参数 |")
    w("| 命令总数 | %d 条（服务端实现）/ %d 条（桌面全量） |" % (len(sup), len(desk)))
    w("")
    w("### 端点")
    w("")
    w("**免鉴权面只有两个端点**，其余全部需要租户凭据（含登录）：")
    w("")
    w("| 端点 | 方法 | 鉴权 | 用途 |")
    w("|---|---|---|---|")
    w("| `/v1/health` | GET | 否 | 健康检查 + 能力发现（含支持命令全表） |")
    w("| `/v1/tenants` | POST | 否 | **领取本机租户凭据**（首次免邀请码） |")
    w("| `/v1/tenants/token` | POST | 是 | 轮换本机凭据（旧的立即失效） |")
    w("| `/v1/auth/status` | GET | 是 | 本租户当前登录状态 |")
    w("| `/v1/auth/accounts` | GET | 是 | 本租户已保存账号列表（免密选择登录） |")
    w("| `/v1/auth/login` | POST | 是 | 用户名密码登录 / 按 `userId` 登录 |")
    w("| `/v1/auth/2fa` | POST | 是 | 回答二步验证挑战 |")
    w("| `/v1/auth/logout` | POST | 是 | 登出（丢会话，保留租户与凭据） |")
    w("| `/v1/command` | POST | 是 | **核心**：执行业务命令 |")
    w("| `/v1/stream` | GET(WS) | 是 | 实时事件流 |")
    w("| `/v1/rpc` | POST | 是 | 早期只读 RPC（新客户端用 `/v1/command` 即可） |")
    w("| `/v1/game-log/events` | POST | 是 | 游戏日志原文中继（由与游戏同机的客户端上传） |")
    w("")
    w("> ⚠️ **顺序是强制的：先领凭据，再登录。**")
    w("> 登录接口之所以需要凭据，是因为服务端不再把 token 当作「登录成功的返回值」——")
    w("> 那样等于全服务器共用一把密钥，任何用户都能冒充他人。")
    w("> 凭据由 `POST /v1/tenants` 在登录前发放，服务端只存 SHA-256 摘要，")
    w("> 明文仅返回一次，丢失只能轮换。")
    w("")
    w("## 2. 连接与认证流程")
    w("")
    w("### 2.1 发现服务端")
    w("")
    w("```http")
    w("GET /v1/health")
    w("```")
    w("")
    w("```json")
    w('{ "status": "ok", "appVersion": "2.29.0", "protocolVersion": 2,')
    w('  "runtime": { "started": true, "error": null },')
    w('  "tenants": { "registered": 2, "live": 2, "running": 1 },')
    w('  "commands": { "calls": 1204, "ok": 1190, "unimplemented": 0,')
    w('                 "failed": 14, "supported": ["app__...", "..."] } }')
    w("```")
    w("")
    w("`status` 为 `ok` 或 `degraded`；`degraded` 时 `runtime.error` 说明原因。")
    w("")
    w("`tenants` 是**多租户**新增的：`registered` 是注册表中的用户数，")
    w("`live` 是本进程装载的运行时数，`running` 是其中后端起来了的数量。")
    w("客户端据此在领取凭据前判断**是否需要邀请码**：")
    w("`registered == 0` 说明这台服务器还没有任何用户，此时是第一个，免邀请码。")
    w("")
    w("> ⚠️ **`/v1/health` 不能用来验证凭据**：它免鉴权，错 token 一样返回 200。")
    w("> 「能连上」和「凭据有效」是两件事；验证只能靠 `GET /v1/auth/status`，")
    w("> 401 即凭据失效或被吊销。")
    w("")
    w("> ⚠️ 以这个 handler 为准，**不要**照 `remote-protocol` 里的 `HealthReport`")
    w("> 结构体——那个结构体没被用上，且描述的是另一种形状")
    w("> （它有 `currentUserId` / `endpoint` / `authenticated`，实际线上都没有）。")
    w("")
    w("**必须先拉一次 `supported` 表**：客户端只在启动时拉一次，")
    w("只对该表中存在的命令走服务端；不在表中的命令服务端会返回 `501`。")
    w("服务端新增命令后，客户端需重启才会看到（或主动重拉）。")
    w("")
    w("### 2.2 领取租户凭据（登录之前）")
    w("")
    w("客户端只拿到一个地址时，先领凭据，再谈登录。")
    w("")
    w("```http")
    w("POST /v1/tenants")
    w('{ "label": "Pixel 7", "inviteCode": "..." }   // inviteCode 可省略')
    w("```")
    w("")
    w("`label` 是运维在 `--list-tenants` 里看到的名称，用来区分设备；")
    w("**不能为空**，建议取设备名（`Build.MODEL`）。")
    w("`inviteCode` 只有服务器已有用户时才需要，可省略；")
    w("空白务必**整个字段省掉**而不是传 `\"\"`，服务端区分这两种情况。")
    w("")
    w("成功：")
    w("")
    w("```json")
    w('{ "tenantId": "t_ab12", "label": "Pixel 7", "token": "vx1_..." }')
    w("```")
    w("")
    w("失败（HTTP 状态码 + 机器可读的 `kind`）：")
    w("")
    w("| HTTP | `kind` | 含义 |")
    w("|---|---|---|")
    w("| 403 | `admissionClosed` | 已有用户且邀请码缺失/错误 |")
    w("| 400 | `invalidLabel` | `label` 去空白后为空 |")
    w("| 409 | `duplicateLabel` | `label` 已被占用 |")
    w("| 500 | `other` | 其它 |")
    w("")
    w("```json")
    w('{ "message": "a tenant label cannot be empty", "kind": "invalidLabel" }')
    w("```")
    w("")
    w("丢失凭据只能**轮换**，不能找回（服务端只存摘要）：")
    w("")
    w("```http")
    w("POST /v1/tenants/token")
    w("Authorization: Bearer <旧 token>")
    w("```")
    w("")
    w("### 2.3 登录本租户的 VRChat 账号")
    w("")
    w("**以下请求全部需要 `Authorization: Bearer <凭据>`。**")
    w("")
    w("推荐「选择已保存账号」流程，用户不必输密码：")
    w("")
    w("```http")
    w("GET /v1/auth/accounts")
    w("Authorization: Bearer <凭据>")
    w("```")
    w("")
    w("```json")
    w('{ "authenticated": false, "loginAvailable": true,')
    w('  "currentUserId": "", "currentDisplayName": null,')
    w('  "accounts": [ { "userId": "usr_x", "displayName": "名字",')
    w('                  "username": "账号", "iconUrl": "https://..." } ] }')
    w("```")
    w("")
    w("（`displayName` / `username` / `iconUrl` 为可选字段，可能缺失。）")
    w("")
    w("`GET /v1/auth/status` 另有独立形状，用于判断服务端当前是否已有会话：")
    w("")
    w("```json")
    w('{ "authenticated": true, "userId": "usr_x", "displayName": "名字",')
    w('  "endpoint": "https://api.vrchat.cloud/api/1",')
    w('  "loginAvailable": true, "runtimeError": null }')
    w("```")
    w("")
    w("```http")
    w("POST /v1/auth/login")
    w('{ "userId": "usr_x" }            // 命中保存的 cookie 则直接登录')
    w("```")
    w("")
    w("密码登录：`{ \"username\": \"...\", \"password\": \"...\",")
    w("\"saveCredentials\": true }`。")
    w("")
    w("四种结果都用 **同一形状**返回（`tag = \"status\"`），客户端只需一个解析器：")
    w("")
    w("```json")
    w('{ "status": "authenticated", "userId": "usr_x", "displayName": "...",')
    w('  "endpoint": "https://api.vrchat.cloud/api/1" }')
    w('{ "status": "challenge", "attemptId": "att_1", "methods": ["totp"],')
    w('  "error": null }')
    w('{ "status": "failed", "reason": "...", "kind": "rateLimited" }')
    w('{ "status": "cancelled" }')
    w("```")
    w("")
    w("> ⚠️ `authenticated` 里**不再有 `token` 字段**（旧服务端会返回，新服务端为 "
      "`null`/缺失）。")
    w("> 租户凭据在你登录之前就已经拿到了，登录响应再发一遍等于把已有密钥又过一遍网。")
    w("> 客户端应**保留手上那把凭据**，不要用它覆盖。")
    w("")
    w("`challenge.error` 在上一次验证码被拒时出现；`cancelled` 表示登录被中途取消。")
    w("服务端对登录接口有**速率限制**：5 分钟内最多 8 次尝试，")
    w("且同一时刻只允许一个登录流程在跑（第二种情况返回 `busy`）。")
    w("")
    w("收到 `challenge` 后：")
    w("")
    w("```http")
    w("POST /v1/auth/2fa")
    w('{ "attemptId": "att_1", "method": "totp", "code": "123456" }')
    w("```")
    w("")
    w("`kind` 取值：`invalidCredentials | missingCredentials |")
    w("sessionInvalidated | twoFactorUnavailable | network | other |")
    w("rateLimited | busy`。")
    w("")
    w("> VRChat 的凭据只在服务端保存，客户端用完即弃。客户端唯一需要持久化的")
    w("> 是**租户 token**——它不是登录产物，而是这台设备在服务器上的身份，")
    w("> 生命周期比任何一次登录都长。Android 端应把它存进")
    w("> EncryptedSharedPreferences / Keystore。")
    w("")
    w("> ⚠️ **登出 ≠ 放弃凭据**。`/v1/auth/logout` 只丢 VRChat 会话，")
    w("> 租户与凭据保留（回到「待登录」）。只有用户明确「忘记服务器」时才清地址+凭据；")
    w("> 否则每次登出都会在运维的租户注册表里留下一个死租户。")
    w("")
    w("## 3. 调用业务命令 `POST /v1/command`")
    w("")
    w("请求信封：")
    w("")
    w("```json")
    w('{ "command": "app__xxx", "args": { ... } }')
    w("```")
    w("")
    w("**两种参数形态**，按命令而定（见第 6 节清单）：")
    w("")
    w("1. **扁平命名参数** —— 参数与 `command` 平级放在 `args` 里，camelCase：")
    w("")
    w("   ```json")
    w('   { "command": "app__game_log_entries_add",')
    w('     "args": { "kind": "Location", "entries": [ { ... } ] } }')
    w("   ```")
    w("")
    w("2. **整体 input 对象** —— 命令体整体放在 `args.input` 下：")
    w("")
    w("   ```json")
    w('   { "command": "app__instance_history_query",')
    w('     "args": { "input": { "userId": "usr_x", "limit": 50 } } }')
    w("   ```")
    w("")
    w("**无参命令**直接传 `\"args\": {}`。")
    w("")
    w("### 响应")
    w("")
    w("- 成功：命令原本的返回值（类型随命令而异，可能是对象/数组/数字）")
    w("- 失败：`{ \"message\": \"...\" }`，HTTP 状态码非 2xx")
    w("- 未实现：`501` + `{ \"message\": ... }`（不应发生，请先查 supported 表）")
    w("")
    w("### curl 示例")
    w("")
    w("```bash")
    w("curl -X POST http://192.168.1.1:8790/v1/command \\")
    w("  -H 'Authorization: Bearer <token>' \\")
    w("  -H 'Content-Type: application/json' \\")
    w("  -d '{\"command\":\"app__instance_history_query\","
      "\"args\":{\"input\":{\"userId\":\"usr_x\"}}}'")
    w("```")
    w("")
    w("## 4. 实时事件 `GET /v1/stream?token=<token>`")
    w("")
    w("WebSocket。浏览器/部分客户端无法自定义 header，故 token 走查询参数。")
    w("")
    w("帧格式（`tag = \"kind\"`，**扁平**，不是嵌套）：")
    w("")
    w("```json")
    w('{ "kind": "hello", "protocolVersion": 2, "appVersion": "2.29.0" }')
    w('{ "kind": "event", "event": "realtimeFeedProjection",')
    w('  "payload": { ... }, "seq": 42 }')
    w('{ "kind": "lagged", "skipped": 12 }')
    w("```")
    w("")
    w("- `hello`：连接后第一条，先校验 `protocolVersion` 再解释后续帧")
    w("- `event`：`seq` 单调递增，可用于检测丢帧")
    w("- `lagged`：客户端落后太多被跳过，**必须**用一次全量查询对账")
    w("")
    w("### 已知事件名（%d 个）" % len(evts))
    w("")
    for e in evts:
        w("- `%s`" % e)
    w("")
    w("### 断线重连")
    w("")
    w("退避重连（如 1s → 30s 上限）。重连后**不依赖**事件补发：")
    w("服务端不重放历史事件，客户端应在重连成功后主动调一次全量查询")
    w("（如 `app__backend_runtime_combined_snapshot_get`）重新对齐状态。")
    w("")

    # ---- command catalogue
    w("## 5. 命令清单（%d 条，按功能分组）" % len(sup))
    w("")
    w('参数列：`input` 表示整体对象形态；否则列出扁平参数名；`-` 表示无参。')
    w("")
    grouped: dict[str, list[str]] = {}
    for c in sup:
        grouped.setdefault(group_of(c), []).append(c)
    for label, _ in GROUPS:
        items = grouped.pop(label, [])
        if not items:
            continue
        w("### %s（%d）" % (label.strip(), len(items)))
        w("")
        w("| 命令 | 参数 |")
        w("|---|---|")
        for c in sorted(items):
            e = cmds[c]
            if e["input"]:
                a = "`input`"
            elif e["args"]:
                a = ", ".join("`%s`" % x for x in e["args"])
            else:
                a = "—"
            w("| `%s` | %s |" % (c, a))
        w("")
    if grouped:
        w("### 其他（%d）" % sum(len(v) for v in grouped.values()))
        w("")
        w("| 命令 | 参数 |")
        w("|---|---|")
        for label in sorted(grouped):
            for c in sorted(grouped[label]):
                e = cmds[c]
                a = "`input`" if e["input"] else (
                    ", ".join("`%s`" % x for x in e["args"]) or "—")
                w("| `%s` | %s |" % (c, a))
        w("")

    # ---- gaps
    gap = [c for c in desk if c not in cmds]
    w("## 6. 未迁移命令与 Android 可用性（%d 条）" % len(gap))
    w("")
    w("桌面端共 %d 条命令，服务端实现 %d 条。剩余 %d 条分两类："
      % (len(desk), len(sup), len(gap)))
    w("")
    w("### 6.1 桌面专属 —— Android 本就不需要")
    w("")
    w("窗口/托盘/截图/进程监控/启动游戏/本地文件与注册表/VR 覆盖层/")
    w("助手与 MCP/TTS/应用自更新/字体/代理 等依赖本机的能力，")
    w("手机上不存在对应概念，无需迁移。")
    w("")
    w("### 6.2 真正的功能缺口（Android 需要，但服务端尚未实现）")
    w("")
    w("以下是应在服务端补齐的关键项，按优先级：")
    w("")
    w("| 命令 | 缺失后影响 |")
    w("|---|---|")
    priorities = [
        ("app__quick_search_query", "全局搜索不可用（手机端高频功能）"),
        ("app__player_list_current_snapshot",
         "看不到当前房间的实时玩家列表（依赖本地游戏状态）"),
        ("app__ancillary_runtime_snapshot_get",
         "登录水合失败会导致界面卡在加载态"),
        ("app__get_vrchat_user_moderation", "用户审核状态读不到"),
        ("app__set_vrchat_user_moderation", "无法设置用户审核"),
        ("app__vrchat_group_order_get", "群组自定义排序丢失"),
        ("app__vrchat_group_order_set", "无法保存群组排序"),
        ("app__notification_activity_filters_set", "通知过滤设置无法保存"),
        ("app__notification_do_not_disturb_mode_set", "免打扰设置无法保存"),
        ("app__vrchat_media_emoji_upload", "需要字节上传通道（表情）"),
        ("app__vrchat_media_gallery_image_upload", "需要字节上传通道（相册图）"),
        ("app__vrchat_media_vrc_plus_icon_upload", "需要字节上传通道（图标）"),
        ("app__vrchat_instance_join", "启动本机游戏，手机上无意义"),
        ("app__translation_translate", "富文本翻译（可选）"),
    ]
    for name, impact in priorities:
        if name in gap:
            w("| `%s` | %s |" % (name, impact))
    w("")
    w("> 其余未列出项多为桌面专属或可选功能。字节上传类命令需要服务端")
    w("> 新增 multipart/二进制通道，是独立的一块工作。")
    w("")
    w("## 7. Android 端实现要点")
    w("")
    w("### 7.1 传输层")
    w("")
    w("- 启动流程：`/v1/health` 拉 supported 并读 `tenants.registered` →")
    w("  无凭据则 `POST /v1/tenants` 领凭据（按需带邀请码）→")
    w("  有凭据则 `GET /v1/auth/status` 校验（401 说明被吊销/轮换，清掉重领）→")
    w("  已登录直接进主界面，否则走登录 → 成功后连 `/v1/stream`。")
    w("- 统一入口：所有命令走一个 `invoke(command, args)`，")
    w("  内部查 supported 表决定走服务端还是报错。")
    w("- 参数形态按第 5 节清单区分 `input` 与扁平参数——**这是最容易出错的地方**，")
    w("  传错形态会得到 `missing \\`input\\` argument` 之类错误。")
    w("- 所有字段 camelCase；时间字段为 ISO-8601 字符串。")
    w("")
    w("### 7.2 平板（横屏）/ 手机（竖屏）双模式")
    w("")
    w("服务端**完全不感知**布局，模式切换纯客户端实现：")
    w("")
    w("| | 手机竖屏 | 平板横屏 |")
    w("|---|---|---|")
    w("| 导航 | 底部导航栏 | 侧边 Navigation Rail / 抽屉 |")
    w("| 列表+详情 | 分屏跳转（点进详情占满屏） | 双栏并排（左列表右详情） |")
    w("| 内容密度 | 单列 | 多列网格 / 更宽卡片 |")
    w("")
    w("要点：")
    w("")
    w("1. 用 `WindowSizeClass`（宽度/高度等级）判断形态，**不要用屏幕英寸或"
      "固定 dp 阈值**。")
    w("2. 模式要能**手动切换并持久化**（用户偏好优先于自动判定），")
    w("   存 DataStore。")
    w("3. 竖屏↔横屏旋转时保留列表滚动位置与选中项，")
    w("   用 `rememberSaveable` + ViewModel 持有选中 id，避免重建丢状态。")
    w("4. 列表用分页/懒加载：命令返回量可能很大（如 feed、好友列表），")
    w("   配合 `Paging3` 或手动分页参数。")
    w("")
    w("### 7.3 数据一致性")
    w("")
    w("- 首屏用命令全量拉取，之后靠 WS 事件增量更新。")
    w("- 收到 `lagged` 或重连后，重新全量查询对账。")
    w("- 游戏日志/房间历史的数据由**与 VRChat 同机的桌面客户端**上传，")
    w("  手机只是读取方——若服务端没有该数据，说明还没有桌面客户端上报过。")
    w("")
    return "\n".join(L) + "\n"


def main() -> None:
    cmds = commands()
    evts = events()
    desk = desktop_commands()
    DOCS.mkdir(parents=True, exist_ok=True)
    (DOCS / "ANDROID_API-commands.json").write_text(
        json.dumps(cmds, indent=1, ensure_ascii=False), encoding="utf-8"
    )
    md = render(cmds, evts, desk)
    (DOCS / "ANDROID_CLIENT_API.md").write_text(md, encoding="utf-8")
    print("commands : %d" % len(cmds))
    print("events   : %d" % len(evts))
    print("desktop  : %d  (gap %d)" % (len(desk),
                                       len([c for c in desk if c not in cmds])))
    print("written  : %s" % (DOCS / "ANDROID_CLIENT_API.md"))


if __name__ == "__main__":
    main()
