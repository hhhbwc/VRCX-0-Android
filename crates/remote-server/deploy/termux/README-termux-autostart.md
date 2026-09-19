# Termux 自启动指南（VRCX-0 远程服务端）

## 一、无 root（推荐路径，99% 的手机够用）

无 root 的自启动靠 **Termux:Boot 插件**：它是一个独立的 App，开机时执行
`~/.termux/boot/` 下的脚本。

### 步骤

1. **安装 Termux:Boot**（必须和 Termux 本体来自同一来源——都在 F-Droid、
   或都在 GitHub Releases 装的，**混装 Play 商店版会因签名不同失败**）。
2. **打开一次 Termux:Boot**（装完必须手动启动一次，否则它的开机广播不生效）。
3. 在 Termux 里放入脚本：
   ```bash
   mkdir -p ~/.termux/boot
   cp vrcx-boot.sh ~/.termux/boot/
   chmod +x ~/.termux/boot/vrcx-boot.sh
   ```
   （脚本内容：自动 `termux-wake-lock` + 服务器没在跑就启动，日志在
   `~/vrcx-data/server.log`。需要 `pkg install termux-api` 才有 wake-lock。）
4. **保活三件套**——这一步比脚本更重要：
   - 系统设置 → 电池 → 给 **Termux** 和 **Termux:Boot** 都关闭电池优化；
   - ROM 的「自启动管理」里允许这两个 App 自启动（ColorOS/MIUI/HyperOS/
   OriginOS 都有这个开关，不开的话锁屏几分钟进程就被冻结）;
   - Termux 通知栏常驻通知不要划掉（那是 Termux 的保活手段之一）。

### 验证

重启手机 → 打开 Termux → `pgrep -f vrcx-0-remote-server` 有输出即成功；
或看 `~/vrcx-data/server.log` 的启动时间戳。客户端连 `手机IP:8790`。

## 二、有 root（更彻底，开机即系统服务）

root 后不再依赖 Termux:Boot（那个方案仍要"启动 Termux 环境"，且被
Android 的后台管控约束），可以直接挂进系统引导：

### 方式 A：Magisk 模块（最干净）

1. Magisk → 模块 → 从本地安装一个空模块（或手写 `module.prop` +
   `service.sh`）。
2. 在模块的 `service.sh` 里加（**晚于系统启动执行，以 root 运行**）：
   ```sh
   #!/system/bin/sh
   DIR=/data/adb/vrcx-server   # 二进制和 toml 放这里
   sleep 20                    # 等网络起来
   "$DIR/vrcx-0-remote-server" --data-dir "$DIR/data" >> "$DIR/server.log" 2>&1 &
   ```
3. 重启即生效。数据放在 `/data/adb/` 下（重启不丢、不受 Termux 生命周期
   影响、不给 Termux 收尸）。

### 方式 B：init.d 脚本

部分 ROM 支持 `/data/local/userinit.sh` 或 `/system/etc/init.d/`（需要
ROM 开启 init.d 支持 + root）。内容同上，效果一样。

### root 方案与无 root 的实质区别

| | 无 root（Termux:Boot） | root（Magisk/init.d） |
|---|---|---|
| 触发时机 | 解锁后 Termux 环境启动时 | 系统引导阶段，无需打开任何 App |
| 进程归属 | Termux（受 Android 后台管控） | root，管控最松 |
| 保活 | 要手动关电池优化+自启动白名单 | 基本不用管 |
| 数据位置 | Termux 私有目录（卸载即丢） | /data/adb（持久） |
| 端口 | 只能 >1024（8790 没问题） | 可绑任意端口 |

两种方式的 8790 端口都没问题——服务端本来就默认绑高位端口。

## 三、无论哪种方式，两个通用提醒

- 服务端有内建的开机恢复：**只要 VRChat 会话保存过**（客户端登录时勾选
  保存凭据），重启后服务端自己恢复会话，不需要人守着重新登录。
- 手机重启后 IP 可能变；家里路由器给手机 MAC 绑一个静态 DHCP 租约，
  客户端地址就不用改。
