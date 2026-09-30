"""com.vrcx0.tablet.net 的宿主机验收入口。

设计取向：这一层（PinnedTls / RemoteTransport）刻意只依赖 JDK，所以它能在
**没有 Android 设备**的情况下被真正验证 —— 编译 + 对着真服务器跑 TLS 行为
断言。这就是"没有 adb 也能证明接口是通的"的那条路。

用法：
    python tools/run_bridge_test.py                # 默认打现役 VPS
    python tools/run_bridge_test.py https://host   # 指定目标

退出码非 0 表示有断言失败，可以直接接 CI。
"""

import os
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
APP = HERE.parent
NET_SRC = APP / "app" / "src" / "main" / "java" / "com" / "vrcx0" / "tablet" / "net"
OUT = HERE / "out"
LOG = HERE / "bridge_test.log"

JAVA_CANDIDATES = [
    os.environ.get("JAVA_HOME", ""),
    r"C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot",
    r"C:\Program Files\Java\jdk-17",
]


def find_jdk():
    for root in JAVA_CANDIDATES:
        if not root:
            continue
        exe = "javac.exe" if os.name == "nt" else "javac"
        if (Path(root) / "bin" / exe).exists():
            return Path(root) / "bin"
    raise SystemExit("找不到 JDK 17；设置 JAVA_HOME 或改 JAVA_CANDIDATES")


def main():
    jdk = find_jdk()
    print(f"JDK: {jdk}")

    sources = sorted(str(p) for p in NET_SRC.glob("*.java"))
    sources.append(str(HERE / "BridgeTest.java"))
    OUT.mkdir(parents=True, exist_ok=True)

    compile_cmd = [
        str(jdk / "javac.exe"),
        # 中文 Windows 上 javac 默认按平台编码（GBK）读源码，
        # 不钉 UTF-8 会报一屏"编码 GBK 的不可映射字符"。
        "-encoding", "UTF-8",
        "-Xlint:-options",
        "-d", str(OUT),
        *sources,
    ]
    r = subprocess.run(compile_cmd, capture_output=True, text=True, errors="replace")
    if r.returncode != 0:
        print("编译失败：")
        print(r.stdout)
        print(r.stderr)
        return r.returncode
    print(f"编译通过（{len(sources)} 个源文件）")

    target = sys.argv[1] if len(sys.argv) > 1 else "https://<server-ip>"
    run_cmd = [str(jdk / "java.exe"), "-cp", str(OUT), "BridgeTest", target]
    r = subprocess.run(run_cmd, capture_output=True, text=True, errors="replace",
                       encoding="utf-8")
    text = (r.stdout or "") + (r.stderr or "")
    # 再落一份 UTF-8 日志：终端编码不可控，文件才是可读的。
    LOG.write_text(text, encoding="utf-8")
    print(text)
    print(f"（完整日志: {LOG}）")
    return r.returncode


if __name__ == "__main__":
    sys.exit(main())
