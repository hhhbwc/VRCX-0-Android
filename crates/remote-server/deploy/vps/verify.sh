#!/usr/bin/env bash
# 服务端自检：证明"数据面只绑 loopback、公网只暴露 nginx 443"这条红线成立。
#
# 用法（服务器上 root 执行）：
#     ./verify.sh
#
# 退出码 0 = 全部通过；非 0 = 有 FAIL。只做只读检查，不改任何东西。
set -u

PORT=8790
UNIT=vrcx-0-remote-server
PASSED=0
FAILED=0

section() { printf '\n\033[1m%s\033[0m\n' "$1"; }
info()    { printf '  %s\n' "$1"; }

# assert <说明> <实际值> <期望值>
assert() {
    local label="$1" actual="$2" expected="$3"
    if [ "$actual" = "$expected" ]; then
        PASSED=$((PASSED + 1))
        printf '  \033[32mPASS\033[0m  %-46s %s\n' "$label" "$actual"
    else
        FAILED=$((FAILED + 1))
        printf '  \033[31mFAIL\033[0m  %-46s got=%s want=%s\n' "$label" "$actual" "$expected"
    fi
}

http_code() {
    curl -s -o /dev/null -m 10 -w '%{http_code}' "$@" 2>/dev/null || echo 000
}

section "1. 服务"
if systemctl is-active --quiet "$UNIT"; then assert "systemd unit 运行中" "active" "active"; else assert "systemd unit 运行中" "$(systemctl is-active "$UNIT" 2>/dev/null)" "active"; fi
assert "开机自启" "$(systemctl is-enabled "$UNIT" 2>/dev/null)" "enabled"

section "2. 监听地址（关键：必须是 127.0.0.1，不能是 0.0.0.0）"
LISTEN="$(ss -ltnH "sport = :$PORT" 2>/dev/null | awk '{print $4}')"
info "ss: ${LISTEN:-（没在监听）}"
case "$LISTEN" in
    127.0.0.1:$PORT|127.0.0.1:*:[0-9]*|\[::1\]:$PORT) assert "数据面绑定" "loopback" "loopback" ;;
    "") assert "数据面绑定" "not-listening" "loopback" ;;
    *)  assert "数据面绑定" "$LISTEN（公网可达！）" "loopback" ;;
esac
assert "监听里没有 0.0.0.0:$PORT" "$(printf '%s' "$LISTEN" | grep -c "^0\.0\.0\.0:$PORT$\|^\*:$PORT$")" "0"

section "3. 数据面能用（服务器内部直连）"
assert "http://127.0.0.1:$PORT/v1/health" "$(http_code "http://127.0.0.1:$PORT/v1/health")" "200"

section "4. nginx 反代能用（客户端真正走的路）"
assert "https://127.0.0.1/v1/health (nginx)" "$(http_code -k "https://127.0.0.1/v1/health")" "200"
# find -L 跟着 sites-enabled 里的符号链接走，否则 grep -r 会跳过它、误报 FAIL
PROXY="$(grep -hoE 'proxy_pass[[:space:]]+http://127\.0\.0\.1:[0-9]+' \
    $(find -L /etc/nginx -maxdepth 2 -type f 2>/dev/null) 2>/dev/null | head -1)"
assert "nginx proxy_pass 指向 loopback" "$(printf '%s' "$PROXY" | grep -c "127.0.0.1:$PORT")" "1"

section "5. 鉴权真的在拦（/v1/health 免鉴权，不能拿它验凭据）"
assert "坏 token -> /v1/auth/status" "$(http_code -k -H 'Authorization: Bearer definitely-not-a-token' https://127.0.0.1/v1/auth/status)" "401"
assert "坏 token -> /v1/stream" "$(http_code -k -H 'Authorization: Bearer definitely-not-a-token' https://127.0.0.1/v1/stream)" "401"

section "6. 租户"
REGISTRY=/var/lib/vrcx-0/tenants.json
if [ -f "$REGISTRY" ]; then
    info "$(printf '%s' "$(cat "$REGISTRY")" | head -c 400)"
else
    # 全新服务器本来就没有租户，这不是部署问题 —— 但它是"App 里什么都没有"的
    # 头号原因，所以单独提示而不是计入 FAIL。
    info "还没有任何客户端认领过租户（tenants.json 不存在）。"
    info "→ 没有 VRChat 会话 = 没有人去订阅好友事件 = 动态/日志/好友列表必然全空。"
    info "  App 连上并完成登录后这里才会出现内容。"
fi

section "7. 最近的服务日志"
journalctl -u "$UNIT" --since '-10 min' --no-pager 2>/dev/null | tail -8 | sed 's/^/  /'

printf '\n\033[1m结果：%s 项通过，%s 项失败\033[0m\n' "$PASSED" "$FAILED"
[ "$FAILED" -eq 0 ] || exit 1
