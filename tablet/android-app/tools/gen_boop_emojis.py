"""从桌面端常量表生成平板端的 VRChat 内置 boop emoji 模块。

用法：python tools/gen_boop_emojis.py <VRCX-0桌面端仓库>/src/shared/constants/vrchatDefaultEmojis.ts

为什么要脚本生成而不是手抄：65 项里含撇号（Can't see）、连字符、
gif 后缀等多种形态，手抄必错；而 emoji id 错了**不会报错**——
服务端照 200 回包的兄弟会当场耸肩，用户就是收不到戳一戳。
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent   # tablet/
# 源表在 VRCX-0 桌面端仓库（Map1en/VRCX-0）
SRC = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else None
DST = ROOT / "scripts" / "emojis.js"


def main():
    if SRC is None:
        raise SystemExit(
            "用法：python tools/gen_boop_emojis.py <桌面端仓库>/src/shared/constants/vrchatDefaultEmojis.ts")
    raw = SRC.read_text(encoding="utf-8")
    # 空格：有的项被 prettier 拆成多行，所以要用 DOTALL 的宽匹配
    pattern = re.compile(
        r"name:\s*(?P<q1>['\"])(?P<name>.*?)(?P=q1)\s*,\s*"
        r"previewFile:\s*(?P<q2>['\"])(?P<file>.*?)(?P=q2)",
        re.S,
    )
    items = [(m.group("name"), m.group("file")) for m in pattern.finditer(raw)]
    if not items:
        raise SystemExit("!! 一个都没解析出来 —— 桌面端常量表的结构变了，先看一眼再改")

    def emoji_id(name):
        return "default_" + name.replace(" ", "_").lower()

    def jstr(s):
        return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'

    rows = [
        "  { id: %s, name: %s, previewFile: %s }"
        % (jstr(emoji_id(n)), jstr(n), jstr(f))
        for n, f in items
    ]

    head = [
        "/* eslint-disable */\n",
        "/* VRChat 内置 boop emoji —— **脚本生成，禁手改**（共 %d 项）。\n" % len(items),
        "   生成脚本：android-app/tools/gen_boop_emojis.py\n",
        "   来源：桌面版 VRCX-0 `src/src/shared/constants/vrchatDefaultEmojis.ts`\n",
        "   id 规则 = `default_` + 名称空格转下划线 + 小写（撇号保留，见 Can't see）；\n",
        "   预览图 = `https://wiki-files.vrchat.com/<previewFile>`。\n",
        "\n",
        "   为什么必须抄这份表：服务端**没有**“列出可用 boop emoji”的端点\n",
        "   （2026-09-29 全仓确认），只有一个 `boop_send(user_id, emoji_id, inventory_item_id)`。\n",
        "   凭空编 emoji id 就是一例新的静默失效：服务端不报错，对方就是收不到。*/\n",
        "(function () {\n",
        "  'use strict';\n\n",
        "  var CATALOG = [\n",
    ]
    tail = [
        "\n  ];\n\n",
        "  var BASE = 'https://wiki-files.vrchat.com/';\n\n",
        "  window.VRCX_DEFAULT_EMOJIS = CATALOG.map(function (it) {\n",
        "    return { id: it.id, name: it.name, previewUrl: BASE + it.previewFile };\n",
        "  });\n",
        "})();\n",
    ]
    DST.write_text("".join(head) + ",\n".join(rows) + "".join(tail),
                   encoding="utf-8", newline="\n")
    print("写出 %s —— %d 项，%d bytes" % (DST, len(items), DST.stat().st_size))
    print("前 2 项:", rows[:2])
    odd = [r for r in rows if "'" in r]
    print("含撇号的项:", odd or "（无）")


if __name__ == "__main__":
    main()
