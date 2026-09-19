#!/bin/sh
# Provision / refresh the VRCX-0 remote server on a systemd Linux host.
#
# Runs ON the server as root. The binary must already be at $BIN_DIR/$APP --
# upload it first (deploy-vps.ps1 does this); overwriting a running executable
# fails with ETXTBSY, which is why this script stops the service before
# finalising anything.
#
# Layout it creates:
#   /opt/vrcx-0/bin/vrcx-0-remote-server   static musl binary
#   /var/lib/vrcx-0                        data root: tenants.json + tenants/<id>/
#   /etc/vrcx-0/remote-server.env          operator environment (invite code)
#
# SECURITY: the listener is pinned to 127.0.0.1. A VPS is reachable from the
# public internet, so the plaintext data plane must never sit on a routable
# interface; a TLS terminator (nginx, see tls-setup.sh) owns the exposed port
# and proxies to loopback.

set -e

APP=vrcx-0-remote-server
BIN_DIR=/opt/vrcx-0/bin
DATA_DIR=/var/lib/vrcx-0
UNIT=/etc/systemd/system/$APP.service
SVC_USER=vrcx

echo "=== 1. service account ==="
if ! id -u "$SVC_USER" >/dev/null 2>&1; then
    useradd --system --no-create-home --shell /usr/sbin/nologin "$SVC_USER"
    echo "created system user $SVC_USER"
else
    echo "user $SVC_USER already exists"
fi

echo "=== 2. directories ==="
mkdir -p "$BIN_DIR" "$DATA_DIR"
# The data dir must be owned by the service user; the binary dir is read-only
# to it (ProtectSystem=strict plus the exit codes below depend on this).
chown -R "$SVC_USER:$SVC_USER" "$DATA_DIR"
chmod 750 "$DATA_DIR"

echo "=== 3. binary preflight ==="
if [ ! -f "$BIN_DIR/$APP" ]; then
    echo "FATAL: no binary at $BIN_DIR/$APP - upload it first"
    exit 1
fi
chmod 755 "$BIN_DIR/$APP"
# A dynamically linked musl binary dies with a bare "not found"; check up front
# so a bad upload is reported here rather than as a cryptic systemd failure.
if head -c 4096 "$BIN_DIR/$APP" | grep -q '/lib/ld-musl-'; then
    echo "FATAL: binary is dynamically linked (references a musl loader); expected static"
    exit 1
fi
if ! head -c 4 "$BIN_DIR/$APP" | grep -q 'ELF'; then
    echo "FATAL: $BIN_DIR/$APP is not an ELF file"
    exit 1
fi
echo "binary looks static and is executable"

# systemd refuses to start a binary while an upload write is still in flight,
# and an old process holding the inode makes the swap look like it worked.
echo "=== 4. stop existing service ==="
systemctl stop "$APP" 2>/dev/null || true

echo "=== 5. install unit ==="
cat > "$UNIT" <<'UNITEOF'
[Unit]
Description=VRCX-0 remote server (headless VRChat data plane)
After=network-online.target
Wants=network-online.target
# systemd 255 (Ubuntu 24.04) reads the start-limit keys only in [Unit]; in
# [Service] they are silently ignored with a warning.
StartLimitIntervalSec=3600
StartLimitBurst=10

[Service]
Type=simple
User=vrcx
Group=vrcx
WorkingDirectory=/var/lib/vrcx-0
# The listener is pinned to loopback on purpose; see the SECURITY note at the
# top of provision.sh. Admission control comes from
# /etc/vrcx-0/remote-server.env (VRCX_SERVER_INVITE=...), which this unit reads
# if it exists - a server with no invite code and no tenants yet can be claimed
# by whoever reaches the port.
EnvironmentFile=-/etc/vrcx-0/remote-server.env
ExecStart=/opt/vrcx-0/bin/vrcx-0-remote-server --data-dir /var/lib/vrcx-0 --bind 127.0.0.1:8790

# Give up only after a long window. A short window means one flaky network
# moment exhausts the retry budget and the service stays dead with nobody
# noticing.
Restart=always
RestartSec=5

StandardOutput=journal
StandardError=journal

# Hardening. The process needs write access to exactly one directory.
NoNewPrivileges=true
PrivateTmp=true
PrivateDevices=true
ProtectSystem=strict
ProtectHome=true
ProtectKernelTunables=true
ProtectControlGroups=true
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX
ReadWritePaths=/var/lib/vrcx-0
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
UNITEOF

echo "=== 6. enable + start ==="
mkdir -p /etc/vrcx-0
chmod 700 /etc/vrcx-0
if [ ! -f /etc/vrcx-0/remote-server.env ]; then
    # Created empty and unreadable rather than omitted, so the path to set the
    # invite code is discoverable instead of being a README footnote.
    cat > /etc/vrcx-0/remote-server.env <<'ENVEOF'
# Environment for vrcx-0-remote-server. Read by the systemd unit.
#
# Required before any tenant beyond the first can be claimed. Without it, the
# first caller who can reach POST /v1/tenants claims this server.
# Generate one with: head -c 18 /dev/urandom | base64
# VRCX_SERVER_INVITE=
ENVEOF
    chmod 600 /etc/vrcx-0/remote-server.env
    echo "created /etc/vrcx-0/remote-server.env (set VRCX_SERVER_INVITE there)"
fi

systemctl daemon-reload
systemctl enable "$APP" >/dev/null 2>&1
systemctl restart "$APP"
sleep 4

echo "=== 7. status ==="
systemctl is-active "$APP" || true
systemctl is-enabled "$APP" || true

echo "=== 8. self-check (health endpoint on loopback) ==="
if curl -s -m 8 --noproxy '*' http://127.0.0.1:8790/v1/health -o /tmp/vrcx-health.json; then
    jq -c '{status, protocolVersion, appVersion, tenants, commands: (.commands.supported|length), runtime}' /tmp/vrcx-health.json 2>/dev/null \
        || head -c 400 /tmp/vrcx-health.json
else
    echo "health endpoint did not answer"
fi

echo "=== 9. last log lines ==="
journalctl -u "$APP" -n 12 --no-pager 2>/dev/null | tail -12

echo "=== done ==="
