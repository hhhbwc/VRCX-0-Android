# VRCX-0 Android 客户端

瘦客户端：不持有 VRChat 会话，所有数据来自用户自部署的 VRCX-0 服务端。

- 接口参考：`../docs/ANDROID_CLIENT_API.md`（脚本生成，勿手改）
- 界面规格：`../docs/ANDROID_UI_SPEC.md`
- 实施计划：`../docs/ANDROID_CLIENT_PLAN.md`

## 构建

需要 **JDK 17** 与 **Android SDK**（`ANDROID_HOME`）。

```bash
./gradlew assembleDebug          # 或：.workbuddy/scripts/build-android.sh
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

Windows 上若未设置环境变量，脚本会回退到：
- `JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot`
- `ANDROID_HOME=C:\Android\Sdk`

### 为什么仓库配置是镜像

**Maven Central 在本机返回 403**（`repo1.maven.org` 与 `repo.maven.apache.org` 皆然，
直连与系统代理结果一致）。`settings.gradle.kts` 因此使用阿里云镜像：

| 仓库 | 用途 |
|---|---|
| `google()` | androidx 组件。**必须在前**——`material3-adaptive-navigation-suite` 在阿里云镜像上是 404 |
| `maven.aliyun.com/repository/google` | androidx 镜像，加速 |
| `maven.aliyun.com/repository/public` | Maven Central 镜像（OkHttp、kotlinx.serialization、Coil） |

## 当前进度：P0

已完成：

- `CommandClient` —— 唯一命令出口。启动时拉 `supported` 表，不在表内的命令直接报错而非
  静默降级。自动区分服务端**两种参数形态**（166 条走 `args.input`，75 条扁平参数，
  37 条无参）—— 表由 `.workbuddy/scripts/gen_android_arg_forms.py` 生成，**勿手改**。
- `AuthClient` —— accounts / login / 2fa / logout，四种登录结果一个解析器。
- `EventStreamClient` —— WebSocket，token 走查询参数（WS 无法设 header）；
  解析扁平的 `hello / event / lagged` 帧；退避重连 1s→30s。
- `ServerConfigStore` —— DataStore 只存服务器地址与 token，不存任何 VRChat 凭据。
- 登录页（地址 → 选账号 / 密码 → 2FA）、主界面（底部 5 Tab + 顶栏 + 每 Tab 搜索框位）。
- `Motion.kt` —— 统一的弹簧动效层。

**尚未接线**：顶栏的在线人数显示 `—`。好友列表既不来自
`app__vrchat_friend_status_get`（那是查"我与某个用户的朋友关系"），也不在水合快照里
（那是运行时状态），要等 P1 接好友投影后填充——这里刻意不显示假数字。

## 服务端迁移命令后

```bash
python3 .workbuddy/scripts/gen_android_api_doc.py     # 接口文档
python3 .workbuddy/scripts/gen_android_arg_forms.py    # 参数形态表
```
