# 平板客户端（tablet）

同一个服务端的**另一副面孔**：跑在 Android 平板上的独立 App，与 `android/`（手机瘦客户端）
共用同一个 remote-server 与 token，但 UI 是完全独立的一套 ——
本地网页（Material 3 设计 token、深色主题、宽屏双栏布局）跑在原生 WebView 壳里。

## 形态

```
tablet/
├── index.html / styles/ / scripts/ / icons/   # 本地网页（离线可用，无构建步骤）
├── sw.js / manifest.webmanifest               # PWA 外壳（浏览器调试时用）
└── android-app/                               # Android 工程（WebView 壳）
    ├── app/src/main/java/com/vrcx0/tablet/
    │   ├── MainActivity.java                  # WebView 装配 + JS 桥
    │   └── net/                               # 自签 TLS（SPKI 钉扎）
    └── tools/                                 # 构建 / 验收脚本（见下）
```

- **不持有任何 VRChat 凭据**：和手机端一样，只存服务器地址 + token。
- **数据面契约**：网页通过 JS 桥把 `{command, args}` 透传给服务端
  `POST /v1/command`；命令入参一律**平铺**，由生成的 `argforms.js`
  统一决定包不包 `{input: ...}` —— 弄错形态的表现是"界面没数据"而非报错，
  这是本项目踩过最深的坑。
- **自签证书**：`net/` 里按 SPKI 指纹钉扎（配了指纹就只认那把钥匙），
  与手机端同一枚指纹模型。

## 构建

需要 **JDK 17** 与 **Android SDK**（`ANDROID_HOME`，或用环境变量覆盖默认值）：

```bash
cd tablet/android-app
python tools/build_apk.py        # GradleWrapperMain 直调，产物在 app/build/outputs/apk/debug/
```

⚠️ **改完网页资产必须先同步再构建**：Gradle 打包的是 `android-app/app/src/main/assets/`
这份**副本**，不是仓库根的网页源码。流程固定为：

```bash
python tools/sync_assets.py      # 网页源 → assets 副本（逐字节拷贝）
python tools/build_apk.py        # 打 APK
python tools/verify_apk.py       # 验收：包完整性 / 版本 / assets 与源逐字节一致
```

跳过 `sync_assets.py` 会打出**字节相同的旧包**（assets 没变 → Gradle 复用缓存），
装上去"改了却没生效"。

## 生成物（单一事实来源）

- `scripts/argforms.js` — 从手机端工程生成的参数形态表，**不要手改**：

  ```bash
  python tools/gen_argforms.py --src <手机端工程>/android/app/src/main/java/com/vrcx0/android/data/remote/ArgForms.kt
  ```

- `scripts/emojis.js` — 从 VRCX-0 桌面端仓库的常量表生成：

  ```bash
  python tools/gen_boop_emojis.py <VRCX-0仓库>/src/shared/constants/vrchatDefaultEmojis.ts
  ```

## 联测

`tools/BridgeTest.java` + `tools/run_bridge_test.py`：对**真实服务器**跑桥协议验收
（编译 app 源码为独立 JVM 程序、直连、断言鉴权/命令转发/事件流）：

```bash
python tools/run_bridge_test.py https://<server-ip>
```

## 版本

`versionCode` / `versionName` 在 `android-app/app/build.gradle.kts` 底部的注释块里，
每次交付递增并注明改了什么。
