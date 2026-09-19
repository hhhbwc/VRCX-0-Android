#!/bin/bash
# 交叉编译 vrcx-0-remote-server 到 aarch64-unknown-linux-musl（OpenWrt 路由器）。
# 这是 build-musl.ps1 的 bash 版本：PowerShell 后台任务在本机会被提前掐断，bash 的不会。
#
# 用法：
#   bash "D:/vrcx-1/.workbuddy/scripts/build-musl.sh"            # 默认 -j 6
#   JOBS=8 bash "D:/vrcx-1/.workbuddy/scripts/build-musl.sh"
#
# 关键点（漏了必失败）：
#   - zig 的缓存必须在 %TEMP% 下：zig 当链接器时会把 musl 的 ~1000 个 C 文件编成
#     libc.a，过程中不停写 .o.tmp 再删。本环境**只有 %TEMP% 允许删除**，
#     其他位置一律 AccessDenied，表现为 `error: sub-compilation of musl libc.a failed`。
#   - 传给原生 Windows 程序（zig）的路径一律用 C:/ 正斜杠形式。
#   - 并行度别用默认 32，会更快触发上面的拒写。

set -u

REPO="D:/vrcx-1/src"
TARGET="aarch64-unknown-linux-musl"
JOBS="${JOBS:-6}"
# 放在 PATH 里的必须是 POSIX 形式（/c/...）：MSYS 只转换这种写法，
# 写 C:/... 原生程序的 PATH 里就还是原样，cargo-zigbuild 会报
# "Failed to find zig ... cannot find binary path"。
ZIG_ROOT="/c/Users/wzy/.cargo/tools/zig-0.15.2/ziglang"
WIN_TEMP="C:/Users/wzy/AppData/Local/Temp"

export PATH="/c/Users/wzy/.cargo/lld-shim:/c/Users/wzy/.cargo/bin:$ZIG_ROOT:$PATH"
export CARGO_TARGET_DIR="D:/vrcx-1/src/target-musl"
export CARGO_TERM_COLOR=never
export ZIG_GLOBAL_CACHE_DIR="$WIN_TEMP/zig-cache-musl/global"
export ZIG_LOCAL_CACHE_DIR="$WIN_TEMP/zig-cache-musl/local"

mkdir -p "$ZIG_GLOBAL_CACHE_DIR" "$ZIG_LOCAL_CACHE_DIR" "$WIN_TEMP/vrcx-musl-build"

LOG="$WIN_TEMP/vrcx-musl-build/$(date +%Y%m%d-%H%M%S).log"
echo "==> target=$TARGET jobs=$JOBS"
echo "==> zig cache: $WIN_TEMP/zig-cache-musl"
echo "==> log: $LOG"

cd "$REPO" || exit 1

{ echo "=== started $(date) ==="
  cargo zigbuild -p vrcx-0-remote-server --release --target "$TARGET" -j "$JOBS"
  echo "=== exit=$? $(date) ===" ; } > "$LOG" 2>&1

tail -5 "$LOG"
echo "==> full log: $LOG"

BIN="$REPO/target-musl/$TARGET/release/vrcx-0-remote-server"
if [ -f "$BIN" ]; then
    echo "==> binary: $BIN ($(stat -c%s "$BIN") bytes, $(date -r "$BIN" '+%Y-%m-%d %H:%M:%S'))"
else
    echo "==> NO BINARY"
    exit 1
fi
