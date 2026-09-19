#!/bin/sh
# Put a TLS terminator in front of the VRCX-0 data plane.
#
# Runs ON the server as root, after provision.sh.
#
#   sh tls-setup.sh <public-ip>
#
# The public IP is a required argument (not a hardcoded constant) because the
# certificate's SAN must name it -- re-running this on a different host without
# changing the IP hands clients a certificate that does not match what they
# typed.
#
# Why nginx and not stunnel: /v1/stream is a WebSocket, so the terminator has
# to understand the HTTP Upgrade handshake; nginx also owns port 80 for a
# future ACME challenge if a domain is ever attached.
#
# Why a self-signed certificate: a bare IP cannot get a Let's Encrypt cert.
# Clients pin the SPKI fingerprint instead of trusting a CA chain -- the same
# trust model this project already uses for SSH host keys. If a domain is added
# later, swap in certbot (HTTP-01 needs port 80 reachable) and drop the pin.

set -e

PUBLIC_IP="${1:?usage: sh tls-setup.sh <public-ip>}"
TLS_DIR=/etc/vrcx-0/tls
CERT=$TLS_DIR/server.crt
KEY=$TLS_DIR/server.key
SITE=/etc/nginx/sites-available/vrcx-0

echo "=== 1. install nginx ==="
if ! command -v nginx >/dev/null 2>&1; then
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq nginx >/tmp/nginx-install.log 2>&1
    echo "installed: $(nginx -v 2>&1)"
else
    echo "already installed: $(nginx -v 2>&1)"
fi

# No HTTP/2 anywhere, by design: the data plane is REST plus one WebSocket,
# and a WebSocket is an HTTP/1.1 Upgrade connection, so h2 buys nothing here.
# Leaving it out also sidesteps a real divergence between distro builds --
# Ubuntu 24.04's nginx 1.24 answers "unknown directive" to both spellings of
# the http2 switch, and a version probe is complexity with no payoff.
HTTP2_LISTEN="listen 443 ssl default_server;"
HTTP2_STANZA=""

echo "=== 2. self-signed certificate (idempotent) ==="
mkdir -p "$TLS_DIR"
chmod 700 "$TLS_DIR"
if [ -f "$CERT" ] && [ -f "$KEY" ]; then
    # An existing cert is kept only if it still names this host's IP; a cert
    # minted for a previous address would fail every client pin check silently.
    if openssl x509 -in "$CERT" -noout -ext subjectAltName 2>/dev/null | grep -q "IP Address:$PUBLIC_IP"; then
        echo "certificate already present and names $PUBLIC_IP, keeping it"
    else
        echo "existing certificate does not name $PUBLIC_IP - regenerating"
        rm -f "$CERT" "$KEY"
    fi
fi
if [ ! -f "$CERT" ]; then
    openssl req -x509 -newkey rsa:2048 -sha256 -days 3650 -nodes \
        -keyout "$KEY" -out "$CERT" \
        -subj "/CN=vrcx-0" \
        -addext "subjectAltName=IP:$PUBLIC_IP,IP:127.0.0.1,DNS:localhost" \
        >/tmp/vrcx-cert.log 2>&1
    chmod 600 "$KEY"
    chmod 644 "$CERT"
    echo "generated a new self-signed certificate for $PUBLIC_IP"
fi

echo "   notBefore/notAfter:"
openssl x509 -in "$CERT" -noout -dates | sed 's/^/     /'
echo "   SAN:"
openssl x509 -in "$CERT" -noout -ext subjectAltName 2>/dev/null | tail -n +2 | sed 's/^/     /'

echo "=== 3. nginx site ==="
cat > "$SITE" <<NGINXEOF
map \$http_upgrade \$connection_upgrade {
    default upgrade;
    ''      close;
}

server {
    listen 80 default_server;
    listen [::]:80 default_server;
    server_name _;
    # Reserved for a future ACME HTTP-01 challenge; right now nothing is served
    # in the clear, so everything is pushed to TLS.
    return 301 https://\$host\$request_uri;
}

server {
    $HTTP2_LISTEN
    listen [::]:443 ssl default_server;
    $HTTP2_STANZA
    server_name _;

    ssl_certificate     /etc/vrcx-0/tls/server.crt;
    ssl_certificate_key /etc/vrcx-0/tls/server.key;
    ssl_protocols       TLSv1.2 TLSv1.3;
    ssl_ciphers         HIGH:!aNULL:!MD5;
    ssl_session_cache   shared:SSL:10m;
    ssl_session_timeout 1h;

    # Media uploads can be large, and /v1/stream is a long-lived WebSocket.
    client_max_body_size 128m;
    proxy_http_version 1.1;
    proxy_read_timeout  3600s;
    proxy_send_timeout  3600s;
    proxy_buffering     off;

    location / {
        proxy_pass http://127.0.0.1:8790;
        proxy_set_header Upgrade           \$http_upgrade;
        proxy_set_header Connection        \$connection_upgrade;
        proxy_set_header Host              \$host;
        proxy_set_header X-Real-IP         \$remote_addr;
        proxy_set_header X-Forwarded-For   \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
    }
}
NGINXEOF

rm -f /etc/nginx/sites-enabled/default
ln -sf "$SITE" /etc/nginx/sites-enabled/vrcx-0

echo "=== 4. validate + reload ==="
nginx -t
systemctl enable nginx >/dev/null 2>&1
systemctl reload nginx 2>/dev/null || systemctl restart nginx
sleep 2
systemctl is-active nginx

echo "=== 5. local TLS check (through nginx, ignoring the self-signed chain) ==="
curl -sk -m 8 --noproxy '*' https://127.0.0.1/v1/health -o /tmp/vrcx-health-tls.json \
    && jq -c '{status, protocolVersion, commands: (.commands.supported|length)}' /tmp/vrcx-health-tls.json 2>/dev/null \
    || echo "TLS front end did not answer - is the backend on 127.0.0.1:8790 up?"

echo "=== 6. certificate fingerprint for client pinning ==="
echo "   SHA-256 (cert DER):"
openssl x509 -in "$CERT" -noout -fingerprint -sha256 | sed 's/^/     /'
echo "   SPKI SHA-256 (what Android OkHttp pins):"
openssl x509 -in "$CERT" -pubkey -noout \
    | openssl pkey -pubin -outform der \
    | openssl dgst -sha256 -binary \
    | openssl enc -base64 | sed 's/^/     /'

echo "=== done ==="
echo ""
echo "NOTE: the firewall is NOT touched by this script on purpose."
echo "      Run firewall.sh separately, AFTER nginx is verified, so that if"
echo "      SSH is ever cut off it is unambiguous which step did it."
echo "      The cloud provider's own security group must also allow TCP 443;"
echo "      this script cannot reach that layer."
