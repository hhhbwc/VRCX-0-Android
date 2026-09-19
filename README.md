# VRCX-0 Android — Android 客户端 + 远程数据面

VRCX-0 主仓库见 **[Map1en/VRCX-0](https://github.com/Map1en/VRCX-0)**（桌面端，Tauri + React + Rust）。
本仓库是它的 **Android 瘦客户端**，外加客户端要连的那个服务端：

| 目录 | 是什么 |
|---|---|
| `android/` | **Android 瘦客户端**（Kotlin + Compose）：不持有任何 VRChat 凭据，所有数据来自用户自部署的服务端 |
| `crates/remote-server/` | **headless 服务端**：自己持有 VRChat 会话与 SQLite，把数据面暴露成 HTTP + WebSocket，供瘦客户端连接 |
| `docs/` | 这两条线的设计文档与安全模型 |
| `tools/` | 交叉编译、代码生成、端到端测试脚本 |

> 服务端放在这里只是因为**这个客户端现在只有它需要**；它是平台无关的，将来的 PC 客户端
> 会连同一个服务端（那是另一个项目）。

> 少部分生成物（`ArgForms.kt`、命令表、API 文档）的头部注释里还写着
> `.workbuddy/scripts/...` —— 那是旧路径，脚本现在在 `tools/`。改注释会触发重新生成，
> 这里保持原样。

典型形态（服务端部署在一台 VPS 上，手机从外面连）：

```
                        ┌────────────────── VPS ──────────────────┐
 Android ── HTTPS 443 ─▶│ nginx（自签证书，客户端按 SPKI pin 信任）│
                        │     └─▶ 127.0.0.1:8790  remote-server   │
                        └─────────────────────────────────────────┘
```

## 状态：进行中，别当成品用

这是**开发中的代码**，不是发布件：

- 协议、命令名、参数形状都可能变；服务端和客户端**要一起升级**。
- 服务端目前是**多租户模型**（一租户 = 一个 data dir = 独立 DB / 会话 / 事件流 / token），
  首个租户可直接认领，之后的租户需要邀请码。
- Android 客户端处于 P0/P1 之间，部分屏幕仍是占位。

## 一条红线

**数据面只绑 `127.0.0.1`。** 公网只暴露 nginx 的 443；把 `8790` 绑到 `0.0.0.0`
就等于把别人的 VRChat 社交数据直接挂到公网上。`crates/remote-server/deploy/vps/verify.sh`
做的第一件事就是验这条。

## 服务端

`crates/remote-server/` 按**主仓库 workspace 的相对路径**摆放，拷回主仓库
`crates/` 下即可参与构建（它依赖同 workspace 的 `application`、`composition`、
`persistence` 等 crate，单独 `cargo build` 是不行的）。

```bash
# 在主仓库里
cargo build -p vrcx-0-remote-server --release
```

跨平台静态二进制（x86_64 musl）：

```bash
# Windows 宿主
.\src\crates\remote-server\deploy\vps\deploy-vps.ps1 -Server <server-ip> -Password '***'
```

一键脚本会依次做：交叉编译 musl 二进制 → 上传 → `provision.sh`（systemd unit、
`vrcx` 用户、`/var/lib/vrcx-0`）→ 生成邀请码 → `tls-setup.sh`（nginx + 自签证书 + 打印
SPKI pin）→ `firewall.sh`（ufw 只放行 22/80/443）→ `verify.sh` 自检 → 打印 Android 连接参数。

也可以分步手工跑，详见 [`crates/remote-server/deploy/vps/README.md`](crates/remote-server/deploy/vps/README.md)。
其他平台（OpenWrt / Termux / Debian）见 `DEPLOY-platforms.md`、`DEPLOY-openwrt.md`。

### 几个必须知道的事实

- `/v1/health` **免鉴权**，错的 token 也返回 200 → **不能拿它验凭据**，用 `/v1/auth/status`
  的 401。
- 命令转发走 `POST /v1/command`，body 是 `{command, args}`，未知命令返 `501`。
  （`POST /v1/rpc` 是更早的 `RemoteRequest` 枚举协议，`tag="method"`。）
- **动态 / 好友日志是增量事件流，不是回源查询**：条目由服务端登录后的 VRChat WebSocket
  好友事件 diff 累积而来。没登录 = 必然全空；首次同步还**故意不写历史**，所以要等好友
  真的发生变化才有内容。游戏日志类事件（谁进出房间）需要游戏与读日志的进程同机，
  纯 VPS 上永远没有。

## Android 客户端

需要 **JDK 17** 与 **Android SDK**（`ANDROID_HOME`）：

```bash
./gradlew assembleDebug     # 或 assembleRelease
```

产物在 `app/build/outputs/apk/`。构建环境细节（为什么仓库用镜像、release 的 R8 配置）
见 [`android/README.md`](android/README.md)。

客户端只存**服务器地址与 token**，不存任何 VRChat 凭据（`ServerConfigStore`，DataStore）。
连接自签证书的服务器时填 SPKI pin —— 与 SSH 首次信任 host key 是同一个模型。

## License

GPL-3.0，与主仓库一致。见 [LICENSE](LICENSE) 与 [NOTICE](NOTICE)。
