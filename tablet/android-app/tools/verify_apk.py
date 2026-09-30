"""平板 App 的 APK 交付校验。

为什么要有这个脚本：这台机器上**没有 adb 设备**，所以"装上去看一眼"这条路走不通。
剩下能做的就是把「装了会不会出问题」拆成可静态证明的几条，逐条断言：

  1. 包名与手机端**必须不同** —— 两个界面是同设备并存的两个应用，
     同包名 + 不同证书会互相覆盖（用户明确提过这一点）；
  2. INTERNET 权限在 —— 没有它桥一发请求就是 SecurityException；
  3. assets 里真的带上了闸门与桥（gate 标记 / bridge.js / conn.js / argforms.js）；
  4. dex 里真的有桥的类与回调名 —— 这是**新鲜度**检查：
     assets 是拷进去的、dex 是编出来的，两者都对才算这个包是新的；
  5. 签名有效（debug keystore，可覆盖安装）；
  6. 明文 http 只在局域网场景被放行，生产仍要求 https + 指纹；
  7. **包内 assets 与源文件逐字节一致** —— 这条是"改了网页代码却打出旧包"的克星。
     实测踩过：`sync_assets.py` 的整目录删除被宿主的删除守卫拦掉、脚本死掉，
     而调用方把它接进了管道（**管道的退出码取自最后一段**），失败被整个吞掉，
     打包照旧"成功"，包里却是上一版 assets。只查"文件在不在"是查不出来的。
  8. **安全区是接在父容器上的**（读 MainActivity.java 源码断言）—— dex 里
     "类名在不在"分不出 `root.setOnApplyWindowInsetsListener` 与
     `web.setOnApplyWindowInsetsListener`，而后者在真机上完全不生效：
     网页内容由合成层铺满整个 View，View 的 padding 只影响背景，于是顶栏整条
     压在状态栏下面，连接状态点（闸门的唯一入口）落进状态栏触摸区点不到；
  9. **五屏数据层在包里**（2026-09-22「接数据」轮新增）—— assets 必须带 screens.js、
     index.html 必须加载它、五屏取数命令的关键词必须在、假名（真 VRChat 用户名）
    在任何网页资产里都不得残留。渲染行为与"无裸 id"红线由 _shots/make_screens_shot.py
    在运行时断言，这里只做静态存在性检查。
 10. **dist/ 里那个包就是刚构建出来的这个**（2026-09-29 补）—— 用户拿的是 dist/ 里的
     APK，而它由 `_shots/_stage_dist.py` 从 app-debug.apk 拷过去。此前没有任何断言
     把两者绑在一起：verify 只验 app-debug.apk，闸门只看 dist 里有不有 *.apk。
     "闸门全绿但发布目录是旧包"就是这么发生的。缺 dist 里的当前版本包直接判 FAIL。

用法：python tools/verify_apk.py
"""

import os
import re
import subprocess
import sys
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
APP = HERE.parent
APK = APP / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"

PHONE_PACKAGE = "com.vrcx0.android"   # 手机端，必须不同
EXPECT_PACKAGE = "com.vrcx0.tablet"

SDK = Path(r"C:\Android\Sdk")

pass_n = 0
fail_n = 0


def check(name, ok, detail=None):
    global pass_n, fail_n
    if ok:
        pass_n += 1
        print(f"  PASS  {name}")
    else:
        fail_n += 1
        print(f"  FAIL  {name}" + (f"  <{detail}>" if detail else ""))


def find_tool(name):
    """在 build-tools 里找一个工具。

    ⚠️ 扩展名不统一：aapt2 是 .exe，apksigner 是 .bat。按名字硬拼扩展名
    会得到一个"工具不存在"的假故障（第一次就是这么踩的），所以逐个试。
    """
    suffixes = [".exe", ".bat", ""] if os.name == "nt" else [""]
    for ver in ("34.0.0", "36.0.0", "35.0.0"):
        for suffix in suffixes:
            p = SDK / "build-tools" / ver / (name + suffix)
            if p.exists():
                return p
    raise SystemExit(f"找不到 build-tools 里的 {name}（找过 {SDK / 'build-tools'}）")


def main():
    if not APK.exists():
        raise SystemExit(f"APK 不存在：{APK}\n先跑 tools/build_apk.py")

    print("=== 平板 App APK 交付校验 ===")
    print(f"目标: {APK}")
    print(f"大小: {APK.stat().st_size / 1048576:.2f} MB")
    print()

    # ---------------------------------------------------------- badging
    aapt2 = find_tool("aapt2")
    r = subprocess.run([str(aapt2), "dump", "badging", str(APK)],
                       capture_output=True, text=True, errors="replace")
    badging = r.stdout or ""

    m = re.search(r"package: name='([^']+)'", badging)
    pkg = m.group(1) if m else ""
    check(f"包名是 {EXPECT_PACKAGE}", pkg == EXPECT_PACKAGE, pkg or "解析不出")
    check(f"包名与手机端 {PHONE_PACKAGE} 不同（否则会互相覆盖）", pkg != PHONE_PACKAGE, pkg)

    label = re.search(r"application-label:'([^']*)'", badging)
    check("应用名存在", bool(label), label.group(1) if label else "")
    print(f"        应用名 = {label.group(1) if label else '?'}")

    # 版本号与 build.gradle.kts 的声明必须一致 —— 这条能抓到"改了版本号却没重新构建"，
    # 那种情况下 APK 里的旧版本字符串会一直骗人。
    gradle = (APP / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    declared = re.search(r'versionName\s*=\s*"([^"]+)"', gradle)
    declared = declared.group(1) if declared else ""
    in_apk = re.search(r"versionName='([^']+)'", badging)
    in_apk = in_apk.group(1) if in_apk else ""
    check(f"APK 版本号与 build.gradle.kts 一致（{declared}）",
          bool(declared) and declared == in_apk, f"apk={in_apk} gradle={declared}")

    # 版本号一致**还不够** —— 用户真正拿到的是 dist/ 里那个包，而 dist 是
    # `_shots/_stage_dist.py` 从 app-debug.apk 拷过去（同时归档旧包、改下载页）的。
    # 这条补的正是历史故障「闸门全绿，但发布目录里躺着一个旧包」：
    # 在补它之前，verify_apk 只验 app-debug.apk，而闸门是按 `dist/*.apk` **是否存在**
    # 来决定跑不跑 verify_apk —— 两个目录之间一条断言都没有，各绿各的。
    dist = APP.parent / "dist"
    want = dist / f"VRCX0-tablet-{in_apk}.apk"
    if not dist.is_dir():
        check("dist/ 发布目录存在", False, "没有 dist 目录")
    elif not want.exists():
        got = sorted(p.name for p in dist.glob("*.apk")) or ["(空的)"]
        check(f"dist/ 里有当前版本的包（{want.name}）", False,
              "dist 里是 %s —— 先跑 _shots/_stage_dist.py" % got)
    else:
        import hashlib
        ha = hashlib.sha256(want.read_bytes()).hexdigest()
        hb = hashlib.sha256(APK.read_bytes()).hexdigest()
        check("dist/ 里的包与刚构建的 app-debug.apk 逐字节相同",
              ha == hb, "dist=%s built=%s" % (ha[:12], hb[:12]))

    check("声明了 INTERNET 权限（桥没有它会直接抛 SecurityException）",
          "uses-permission: name='android.permission.INTERNET'" in badging)

    # ⚠️ `dump badging` 不打印 usesCleartextTraffic（它只列权限/特性/标签）。
    # 要读它必须去合并后的清单里找，否则会得到一个"明明有却报没有"的假失败。
    r = subprocess.run([str(aapt2), "dump", "xmltree", "--file", "AndroidManifest.xml",
                        str(APK)], capture_output=True, text=True, errors="replace")
    tree = r.stdout or ""
    check("usesCleartextTraffic=true 在合并后的清单里（局域网自建服务器场景）",
          "usesCleartextTraffic" in tree and "=true" in tree)

    # ---------------------------------------------------------- assets
    z = zipfile.ZipFile(str(APK))
    names = z.namelist()

    def asset(path):
        try:
            return z.read("assets/" + path).decode("utf-8", errors="replace")
        except KeyError:
            return ""

    html = asset("index.html")
    check("assets/index.html 在包内", bool(html))
    check("index.html 带上了连接闸门（id=\"gate\"）", 'id="gate"' in html)
    check("index.html 加载了 bridge.js", "scripts/bridge.js" in html)
    check("index.html 加载了 conn.js", "scripts/conn.js" in html)
    check("index.html 加载了 argforms.js", "scripts/argforms.js" in html)
    check("index.html 加载了 gate.css", "styles/gate.css" in html)
    check("main.js 的 #device 真机全屏模式仍在", "var device = false" in asset("scripts/main.js"))

    bridge = asset("scripts/bridge.js")
    check("assets/scripts/bridge.js 在包内且定义了回调入口",
          "__vrcxResolve" in bridge)
    check("bridge.js 声明了 window.VRCX 门面", "window.VRCX = VRCX" in bridge)

    argforms = asset("scripts/argforms.js")
    check("assets/scripts/argforms.js 在包内（参数形态表）",
          "INPUT_COMMANDS" in argforms and "usesInput" in argforms)

    # 五屏真实数据层（2026-09-22 接数据轮）：screens.js 由脚本生成订阅 conn.js 的
    # onChange，登录后拉服务端数据渲染五屏。静态只查存在性与接线关键词；
    # 渲染正确性与"DOM 无裸 id"红线在 _shots/make_screens_shot.py 里运行时断言。
    screens = asset("scripts/screens.js")
    check("index.html 加载了 screens.js（五屏真实数据层）",
          "scripts/screens.js" in html)
    check("assets/scripts/screens.js 在包内且接上了五屏取数命令",
          "app__feed_latest_query" in screens
          and "app__social_friend_roster_baseline_get" in screens
          and "app__world_summaries_get" in screens
          and "app__browse_history_query" in screens
          and "app__vrchat_auth_current_user_get" in screens
          and "app__my_avatars_get" in screens)
    check("screens.js 有「未知」兜底（世界名解析不出来时不回显 id）",
          "未知" in screens)
    check("screens.js 接管演示横幅（登录后撤横幅/写明取数状态，所有权不在 conn.js）",
          "$('demobar-text')" in screens and "演示数据" in screens)

    conn = asset("scripts/conn.js")
    check("conn.js 用服务端的 status 判别键处理登录结果",
          "'authenticated'" in conn and "'challenge'" in conn)
    # 个人页那两颗账号按钮曾经**没有 id、也没有监听器**，点了毫无反应 ——
    # 断言「id 被接线 + 走的是 authLogout（保留租户）而不是 forgetAll」，
    # 免得下次重构又把它们退回成摆设。逐字节一致那条只能保证"是新写的"，
    # 保证不了"接线还在"。
    check("conn.js 接线了个人页「退出登录」（me-signout → authLogout）",
          "me-signout" in conn and "authLogout" in conn)
    check("conn.js 接线了个人页「忘记此服务器」（me-forget → forgetAll + 重开闸门）",
          "me-forget" in conn and "forgetAll" in conn)
    check("index.html 里两颗账号按钮带上了 id（否则 JS 无从接线）",
          'id="me-signout"' in html and 'id="me-forget"' in html)
    check("index.html 带上了二次确认模态（在 #gate 外面，个人页要用）",
          'id="confirm"' in html and 'id="confirm-ok"' in html)

    # ---------------------------------------------------------- 闸门拦截规则（源码级）
    #
    # 「新用户装完应该先填地址 + 登录」这件事此前是坏的：判据只看"配没配地址"，
    # 且"先看看界面"写进了 localStorage —— 于是地址配好之后冷启动再也不弹闸门，
    # 用户直接进主界面、个人页写着"未登录"。这几条把修法钉在包里。
    # 旧版把"先看看界面"写进 localStorage 当跳过标记 —— 于是它永久生效，
    # 地址配好之后冷启动再也不弹闸门。修法是**把这个标记整个删掉**：
    # 冷启动要不要拦，只看登录态，不看任何"看过了"的记号。
    check("⚠️ 闸门不再有任何「看过了」的持久标记（写进 localStorage 会让它永久生效）",
          "gateSeen" not in conn and "SEEN_KEY" not in conn)
    check("boot 的拦截判据是登录态（没登录就把闸门推回来），不再只看配没配地址",
          "else if (!peeked) open();" in conn
          and "if (state.authenticated) close();" in conn
          and "if (!state.hasToken) open();" in conn)
    # configured = 原生 isComplete() = 地址 + 凭据齐备，会漏掉「地址已存、人还没加入」
    # 那一档 —— 而那一档恰恰最需要探活（不探活就问不出要不要邀请码）。
    check("boot 探活的判据是「有没有地址」，不是 configured",
          "var hasAddr = !!((el.addr" in conn)
    check("凭据失效（401）也把闸门推回来（此时必须重新领名额）",
          "window.VRCX.clearToken();" in conn and conn.count("open();") >= 3)
    check("顶栏那颗「我」的头像由登录态驱动（不写死第三方昵称首字）",
          'id="topAvatar"' in html and "el.topAvatar" in conn)

    # ---------------------------------------------------------- 样例数据不许冒充真实数据
    #
    # 样例里写死的名字看起来就是真 VRChat 用户名，而且同一个人同时出现在动态流、
    # 好友列表、主页与详情面板 —— 用户会以为"我登录的是别人的账号"。
    # 2026-09-22 起：登录后五屏被 screens.js 的真实数据替换、横幅被它撤掉；
    # 样例只服务浏览器预览模式，横幅在那里仍是身份声明（未登录时 conn.js 也会写明）。
    check("样例带「演示数据」标识（浏览器预览模式下样例不许冒充真实数据）",
          'id="demobar"' in html and "演示数据" in html)
    _FAKE = ["神不吃豆腐", "tianmeng113", "天晴雨停", "枫叶树下的清醒梦",
             "星野たけし", "Kaito", "Aoi"]
    _left = [n for n in _FAKE if n in html or n in conn or n in screens]
    check("包内网页资产不带写死的第三方昵称（身份只能来自登录态）",
          not _left, ",".join(_left))

    check("assets 里没有误带演示产物（README / showcase / _shots）",
          not any(n.startswith(("assets/showcase", "assets/_shots")) or
                  n.endswith("assets/README.md") for n in names))

    # ---------------------------------------------------------- assets 新鲜度
    #
    # ⚠️ 这一节是必须的，不是锦上添花。上面那几条只查"关键字在不在"，
    #    而关键字在**上一版**里通常也在 —— 于是"改了 CSS/HTML 却打出旧包"能全绿通过。
    #    只有逐字节比对源文件才能证明这个包装的是**刚才写的那份代码**。
    import hashlib

    DEMO = APP.parent                       # tablet-mode/
    src_files = ["index.html", "manifest.webmanifest"]
    for d in ("styles", "scripts", "icons"):
        for r, _, fs in os.walk(DEMO / d):
            for f in fs:
                src_files.append(str(Path(r, f).relative_to(DEMO)).replace("\\", "/"))

    def dig(p):
        return hashlib.sha256(p.read_bytes()).hexdigest()

    zipped = {n[len("assets/"):]: hashlib.sha256(z.read(n)).hexdigest()
              for n in names if n.startswith("assets/")}
    src_map = {p: dig(DEMO / p) for p in src_files}

    missing = sorted(p for p in src_map if p not in zipped)
    extra = sorted(p for p in zipped if p not in src_map)
    differing = sorted(p for p in src_map if p in zipped and src_map[p] != zipped[p])

    check(f"包内 assets 与源逐字节一致（{len(src_map)} 个文件）",
          not missing and not extra and not differing,
          "; ".join(filter(None, [
              f"缺 {missing}" if missing else "",
              f"多 {extra}" if extra else "",
              f"内容不同 {differing}" if differing else "",
          ])) or None)
    if differing or missing:
        print("        ⚠️ 先跑 tools/sync_assets.py 再重新构建；"
              "sync 失败时**别把它接进管道**（管道的退出码只看最后一段，会吞掉失败）。")

    # ---------------------------------------------------------- dex（新鲜度）
    dex = b"".join(z.read(n) for n in names if n.endswith(".dex"))
    check("dex 含桥的注册名 VRCXNative（证明本次新增代码进了包）",
          b"VRCXNative" in dex)
    check("dex 含原生回调名 __vrcxResolve", b"__vrcxResolve" in dex)
    check("dex 含 PinnedTls（指纹钉的实现类）", b"PinnedTls" in dex)
    check("dex 含 spkiSha256Base64（SPKI 计算入口）", b"spkiSha256Base64" in dex)
    check("dex 含 RemoteTransport", b"RemoteTransport" in dex)
    check("dex 含 ServerConfig", b"ServerConfig" in dex)
    # 「选择账号」那条通道（GET /v1/auth/accounts）—— 与手机端 LoginScreen 对齐后新增的
    check("dex 含 authAccounts（选账号通道进了包）", b"authAccounts" in dex)
    check("dex 含 PATH_AUTH_ACCOUNTS 常量", b"PATH_AUTH_ACCOUNTS" in dex)
    # targetSdk 35 强制 edge-to-edge → 由**父容器**吃 insets 让位系统栏
    # （⚠️ 曾经写在 WebView 自己身上，真机实测无效：内容由合成层铺满整个 View，
    #   View 的 padding 只影响背景。断言容器与 insets 两个类名都在包里。）
    check("dex 含 onApplyWindowInsets（安全区让位进了包）",
          b"onApplyWindowInsets" in dex)
    check("dex 含 FrameLayout 容器（insets 是加在父容器上的，不是 WebView 自己）",
          b"FrameLayout" in dex)
    check("dex 含 requestApplyInsets（首帧就主动要一次 insets）",
          b"requestApplyInsets" in dex)
    # 反向：浏览器侧的调试串不该出现在 dex 里（说明没把演示脚本混进原生）
    check("dex 不含演示者的 __LAYOUT__ 快照名（原生侧无演示残留）",
          b"__LAYOUT__" not in dex)

    # ---------------------------------------------------------- 安全区接法（源码级）
    #
    # ⚠️ 这一节钉的是一条**真机修过的 bug**：targetSdk 35 强制 edge-to-edge 时，
    #    insets 必须加在 **WebView 的父容器**上。写在 WebView 自己身上是无效的
    #    （内容由合成层铺满整个 View，View 的 padding 只影响背景）—— 实测顶栏
    #    整条被状态栏压住，那颗连接状态点落进状态栏触摸区，点它只会拉下通知栏，
    #    等于连接闸门没有入口。dex 里"类名在不在"查不出这类回归（两种写法都含
    #    onApplyWindowInsets），所以这里直接读源码断言**接在哪一层**。
    activity = (APP / "app" / "src" / "main" / "java" / "com" / "vrcx0" / "tablet"
                / "MainActivity.java").read_text(encoding="utf-8")
    check("insets 加在父容器 FrameLayout 上（root.setOnApplyWindowInsetsListener）",
          "root.setOnApplyWindowInsetsListener" in activity)
    check("⚠️ insets 没有再写回 WebView 自己身上（那样真机上完全不生效）",
          "web.setOnApplyWindowInsetsListener" not in activity,
          "web.setOnApplyWindowInsetsListener 又出现了 —— 见 MainActivity 里那段注释")
    check("WebView 被加进容器且容器是 content view",
          "root.addView(web" in activity and "setContentView(root)" in activity)
    check("键盘高度（ime）并进了底部内边距（targetSdk 35 下 adjustResize 已失效）",
          "Type.ime()" in activity, "没并 ime：闸门里的密码/验证码框会被键盘盖住")

    # ---------------------------------------------------------- 签名
    apksigner = find_tool("apksigner")
    r = subprocess.run([str(apksigner), "verify", "--print-certs", str(APK)],
                       capture_output=True, text=True, errors="replace", shell=True)
    out = (r.stdout or "") + (r.stderr or "")
    check("APK 签名有效（可 install -r 覆盖同包名旧版）", r.returncode == 0,
          f"rc={r.returncode}")
    dn = re.search(r"Signer #1 certificate DN: (.+)", out)
    if dn:
        print(f"        签名者 = {dn.group(1).strip()}")
    check("用的是 Android Debug 证书（侧载够用）",
          "Android Debug" in out or "CN=Android Debug" in out)

    print()
    print(f"合计 {pass_n + fail_n} 项，PASS {pass_n}，FAIL {fail_n}")
    return 1 if fail_n else 0


if __name__ == "__main__":
    sys.exit(main())
