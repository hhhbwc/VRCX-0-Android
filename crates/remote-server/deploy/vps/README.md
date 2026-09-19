# 部署到 Linux VPS（systemd + nginx TLS）

面向**有公网 IP 的云主机**（腾讯云/阿里云/任意 KVM VPS）的正式部署路径。
OpenWrt 路由器见 `../DEPLOY-openwrt.md`，Termux 见 `termux/`。

最终形态：

```
                        ┌───────────────────────── VPS ─────────────────────────┐
 Android ── HTTPS 443 ─▶│ nginx (自签证书, 客户端按 SPKI pin 信任)               │
                        │     └─▶ 127.0.0.1:8790  vrcx-0-remote-server (systemd) │
                        └─────────────────────────────────────────────────────────┘
```

数据平面**只绑 loopback**；公网只暴露 nginx 的 443。客户端用
`https://<ip>` + SPKI 指纹（pin）信任自签证书 —— 与 SSH host key 同一信任模型。

## 一键部署（Windows 侧）

在仓库根目录的 PowerShell：

```powershell
.\src\crates\remote-server\deploy\vps\deploy-vps.ps1 `
    -Server <server-ip> -User root -Password '***' `
    -ServerKey 'SHA256:...'           # 首次连接时 plink 报错里会给出
```

`-Password` 只在你自己的 shell 里出现，**不要写进任何文件**；`-ServerKey` 同理
（它是这台机器的身份，泄露等于把中间人机会交出去）。

脚本依次：构建（x86_64 musl 静态二进制，可 `-SkipBuild` 跳过）→ 上传 →
provision → TLS → 防火墙 → 远端与公网验收，最后打印 Android 连接参数。

## 手动分步（脚本都在服务器上以 root 跑）

| 步骤 | 脚本 | 说明 |
|---|---|---|
| 1 | `provision.sh` | 建 `vrcx` 系统用户、`/opt/vrcx-0/bin`、`/var/lib/vrcx-0`、systemd unit（含加固项），启动并自检 `/v1/health` |
| 2 | `tls-setup.sh <公网IP>` | 装 nginx、生成 10 年自签证书（SAN 含该 IP）、反代到 8790，打印 **SPKI pin** |
| 3 | `firewall.sh` | ufw：放行 22/80/443，其余拒绝。**先放 22 再 enable** |
| 4 | `verify.sh` | 只读自检：监听地址 / 数据面 / nginx / 鉴权 / 租户，有 FAIL 就退出 1 |

分开三步是有意的：任何一步失败时，故障域是明确的 —— 尤其是防火墙，
它必须在 nginx 验证通过之后才动，否则 SSH 断了都说不清是谁干的。

## 验收：怎么确认 8790 真生效

「生效」有两层含义，两层都要过：**内部能跑**（数据面活着），**外部碰不到**
（只绑 loopback）。只测其中一层都会漏：只测外部 443 的话，就算服务端哪天被
改成 `0.0.0.0:8790` 你也看不出来。

**服务器上（root）**：

```bash
/opt/vrcx-0/deploy/verify.sh   # deploy-vps.ps1 会把它装到这里；手工传也行
```

期望：数据面绑定 = `loopback` / 监听里没有 `0.0.0.0:8790` /
`127.0.0.1:8790/v1/health` = 200 / `https://127.0.0.1/v1/health` = 200 /
坏 token 打 `/v1/auth/status` 和 `/v1/stream` = 401。

**任意外网机器（Windows / PowerShell）**：

```powershell
# 1) 客户端真正的入口：必须 200
curl.exe -k -o NUL -w "%{http_code}`n" https://<server-ip>/v1/health        # 200

# 2) 数据面本身：必须不通（超时 / 拒绝）。通了就是红线被破了。
curl.exe -m 8 -o NUL -w "%{http_code}`n" http://<server-ip>:8790/v1/health # 000

# 3) 鉴权：错 token 必须 401（/v1/health 免鉴权，拿它验凭据是白验）
curl.exe -k -o NUL -w "%{http_code}`n" -H "Authorization: Bearer x" https://<server-ip>/v1/auth/status  # 401

# 4) 证书指纹（App 里填的 pin，必须和服务器上的一致）
openssl s_client -connect <server-ip>:443 -servername <server-ip> </dev/null 2>$null |
  openssl x509 -pubkey -noout | openssl pkey -pubin -outform der |
  openssl dgst -sha256 -binary | openssl enc -base64
```

| 检查 | 期望 | 说明 |
|---|---|---|
| `https://<ip>/v1/health` | 200 | nginx → 数据面整条链路通 |
| `http://<ip>:8790/v1/health` | 不通（000） | 数据面只绑 loopback |
| 错 token → `/v1/auth/status` | 401 | 鉴权在拦 |
| 错 token → `/v1/stream` | 401 | WS 也走鉴权（顺带验 nginx Upgrade 转发） |

## 运维

```bash
systemctl status vrcx-0-remote-server      # 状态
journalctl -u vrcx-0-remote-server -f      # 日志
/opt/vrcx-0/bin/vrcx-0-remote-server --data-dir /var/lib/vrcx-0 --list-tenants
```

- 邀请码：`/etc/vrcx-0/remote-server.env` 里的 `VRCX_SERVER_INVITE`
  （第二个及以后租户认领时需要；deploy-vps.ps1 首次部署会自动生成一个并打印）
- 升级：重跑 `deploy-vps.ps1 -SkipBuild` 之前的完整命令即可（unit 不变，二进制换完自动重启）
- 云厂商**安全组/防火墙**（控制台那一层）需放行 TCP 443；本目录的 firewall.sh
  只管主机 ufw，管不到云平台那层
