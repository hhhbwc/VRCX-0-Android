# VRCX-0 远程服务端：配置文件与各平台部署指南

> 面向"拿到源码/二进制想自己跑起来"的用户。OpenWrt 路由器的详细部署见
> `DEPLOY-openwrt.md`；本文讲通用的部分。

## 一、配置文件（vrcx-server.toml）

从 `deploy/vrcx-server.toml.example` 复制一份改名 `vrcx-server.toml`，放在
**可执行文件同目录**或**启动时的工作目录**，改完直接启动即可，不需要记命令行参数：

```toml
bind = "0.0.0.0:8790"   # 监听地址；前面有反向代理/内网穿透时改 127.0.0.1
token = ""              # 仅第一个租户的迁移用 token，全新部署留空
invite = ""             # 第二个及之后租户认领所需的邀请码，暴露公网前务必设置
```

查找顺序（找到第一个为止）：`--config <路径>` 显式指定 → 可执行文件同目录 →
当前工作目录。**优先级**：命令行标志 > 环境变量（`VRCX_SERVER_BIND/TOKEN/INVITE`）>
配置文件 > 默认值。文件是 TOML 子集：`key = "value"` 行 + `#` 注释，未知键忽略。

`--data-dir`（数据根目录，每租户一个子目录）不在配置文件里：它由平台数据目录
逻辑处理，且路由器上务必放在外置存储（见 DEPLOY-openwrt.md 的 overlay 说明）。

## 二、各平台部署

### 0. 公网 VPS（systemd + nginx TLS，推荐）

有公网 IP 的云主机（腾讯云/阿里云/任意 KVM）走 **`deploy/vps/`**：一键脚本
`deploy-vps.ps1`（Windows 侧）完成构建、上传、systemd 常驻、nginx TLS 前置、
防火墙与验收，并打印 Android 连接参数（地址 + SPKI pin）。详见
`deploy/vps/README.md`。

数据平面只绑 `127.0.0.1:8790`，公网只暴露 nginx 443；客户端按 SPKI 指纹信任
自签证书。**不要把 8790 直接绑到公网网卡。**

### 1. OpenWrt 路由器（aarch64 / armv7）

见 `DEPLOY-openwrt.md`。要点：**必须静态 musl 二进制**（交叉编译，见下），
数据目录放外置存储，procd init 脚本开机自启。

### 2. Debian / Ubuntu / Armbian（x86_64 / aarch64）

```bash
# aarch64（树莓派、N1 盒子等）：直接用 musl 静态二进制
mkdir -p ~/vrcx-server && cd ~/vrcx-server
cp vrcx-0-remote-server . && cp vrcx-server.toml.example vrcx-server.toml
./vrcx-0-remote-server            # 读取同目录 vrcx-server.toml

# x86_64：cargo build --release -p vrcx-0-remote-server 即可
```

用 systemd 常驻（`/etc/systemd/system/vrcx-server.service`）：

```ini
[Unit]
Description=VRCX-0 remote server
After=network.target

[Service]
WorkingDirectory=/home/USER/vrcx-server
ExecStart=/home/USER/vrcx-server/vrcx-0-remote-server
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

### 3. Termux（Android，免 root）

服务端是**完全静态的 musl 二进制**，不依赖任何系统库，Termux 可以直接跑：

```bash
pkg install proot  # 可选；真正必需的只有 Termux 本体
mkdir -p ~/vrcx-server && cd ~/vrcx-server
# 把 aarch64 musl 二进制和 vrcx-server.toml 拷进来（adb/USB/网络皆可）
chmod +x vrcx-0-remote-server
./vrcx-0-remote-server --data-dir ~/vrcx-data
```

注意事项：
- Android 杀后台：在系统设置里给 Termux 关电池优化；Termux 通知栏
  「Acquire wakelock」。
- `--data-dir` 指到 Termux 私有目录（~/ 下即可），卸载 App 会丢数据。
- 手机与客户端同一局域网时直接用手机 IP；出门在外建议 Tailscale 组网。

### 4. Windows

本机编译（需要 Rust + VS Build Tools）或直接使用发布物：

```powershell
cargo build --release -p vrcx-0-remote-server
.\target\release\vrcx-0-remote-server.exe --data-dir D:\vrcx-data
```

`vrcx-server.toml` 放在 exe 同目录即可被自动发现。

## 三、从源码编译

| 目标平台 | 命令 | 产物 |
|---|---|---|
| OpenWrt/嵌入式 aarch64 | `cargo zigbuild -p vrcx-0-remote-server --release --target aarch64-unknown-linux-musl` | 静态二进制，任何 Linux aarch64 可跑（含 Termux） |
| x86_64 Linux | `cargo build --release -p vrcx-0-remote-server` | 动态链接 glibc |
| Windows | `cargo build --release -p vrcx-0-remote-server` | exe |

依赖只有 Rust 工具链本身（rusqlite bundled 自带 SQLite 源码，rustls 自带
crypto provider；无 OpenSSL、无系统依赖）。交叉编译 aarch64 musl 需要
[cargo-zigbuild](https://github.com/rust-cross/cargo-zigbuild) + zig 作为
C 交叉工具链——zig 顺带把 musl libc 编出来，主机上不需要装任何 C 工具链。

## 四、升级

服务端的数据布局是向前兼容的：换新二进制 + 重启即可，租户数据/会话保留。
升级前备份 `--data-dir` 下的 `tenants.json` 和各租户目录更稳妥。
