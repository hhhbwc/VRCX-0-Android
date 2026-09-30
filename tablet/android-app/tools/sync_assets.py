# -*- coding: utf-8 -*-
"""把平板 demo 的网页资产同步进 Android 工程的 assets/。

只拷「产品页 + 它引用的样式/脚本」，演示者自己的东西（README / showcase /
_shots / 生成的海报）不属于 App，不拷。

⚠️ styles/ 必须整个拷：MainActivity 加载的 index.html#device 会加 .stage.bare，
   而「隐藏调试外壳」的规则就写在 stage.css 里，少了它外壳会露出来。

⚠️⚠️ **本脚本刻意不做任何删除。**
   旧版开头是 `shutil.rmtree(ASSETS)` 再重建 —— 后果有两次都被实测到：
     1. 宿主的"危险删除"守卫会拦掉整目录删除（BULK_CONFIRM_REQUIRED），
        脚本在**还没拷贝任何文件之前**就死了。若调用方把它的输出接了管道，
        **管道的退出码取自最后一段**，失败会被整个吞掉 → 打包照旧"成功"，
        但包里装的是**上一版** assets（症状：改了 CSS/HTML，装上却是旧的）。
     2. 就算删成功，中途失败也会留下半个 assets 目录。
   所以现在改成：**逐个覆盖拷贝**，然后把"源里已不存在、却在 assets 里还在"的
   文件**列出来并让脚本以非 0 退出**（交给人决定删不删）。宁可吵一次，不要静默陈旧。
"""
import os, shutil, sys

HERE = os.path.dirname(os.path.abspath(__file__))
DEMO = os.path.abspath(os.path.join(HERE, "..", ".."))          # tablet-mode/
ASSETS = os.path.abspath(os.path.join(HERE, "..", "app", "src", "main", "assets"))

FILES = ["index.html", "manifest.webmanifest"]
DIRS = ["styles", "scripts", "icons"]


def rel(p):
    return os.path.relpath(p, DEMO).replace("\\", "/")


def expected_set():
    """源里应该出现在 assets 里的全部相对路径。"""
    out = set()
    for f in FILES:
        if not os.path.exists(os.path.join(DEMO, f)):
            sys.exit("missing: " + os.path.join(DEMO, f))
        out.add(f)
    for d in DIRS:
        root = os.path.join(DEMO, d)
        if not os.path.isdir(root):
            sys.exit("missing dir: " + root)
        for r, _, fs in os.walk(root):
            for f in fs:
                out.add(rel(os.path.join(r, f)))
    return out


def main():
    want = expected_set()

    os.makedirs(ASSETS, exist_ok=True)
    for r in want:
        src, dst = os.path.join(DEMO, r), os.path.join(ASSETS, r)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copy2(src, dst)
        print("  ", r)
    print("synced", len(want), "files ->", ASSETS)

    # 陈旧文件只报告、不删除
    stale = []
    for r, _, fs in os.walk(ASSETS):
        for f in fs:
            p = os.path.relpath(os.path.join(r, f), ASSETS).replace("\\", "/")
            if p not in want:
                stale.append(p)

    if stale:
        print("\n⚠️ assets 里有多余文件（源里已无）。本脚本不删，请人工确认后处理：")
        for p in sorted(stale):
            print("   ", p)
        sys.exit(1)


if __name__ == "__main__":
    main()
