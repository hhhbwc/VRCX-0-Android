#!/bin/sh
# Minimal host firewall for the VPS.
#
# Runs ON the server as root, and only AFTER nginx TLS has been verified.
# Kept separate from tls-setup.sh so that if SSH ever gets cut off it is
# unambiguous which change did it.
#
# Note the layer this does NOT cover: the cloud provider's security group
# (configured in their web console) sits in front of this host. TCP 443 must be
# allowed there too, or nothing outside can reach nginx no matter what ufw says.
#
# CRITICAL: allow 22 BEFORE enabling, or this SSH session is the last one.

set -e

echo "=== current state ==="
ufw status verbose 2>/dev/null | head -12 || true

echo "=== allow inbound SSH first ==="
ufw allow 22/tcp

echo "=== allow the TLS front end ==="
ufw allow 443/tcp
# Port 80 is kept open only so a future ACME HTTP-01 challenge can be served;
# right now nginx just 301-redirects it to HTTPS.
ufw allow 80/tcp

echo "=== policy ==="
ufw default deny incoming
ufw default allow outgoing

echo "=== enable ==="
ufw --force enable

echo "=== result ==="
ufw status verbose | head -20

echo "=== verify listeners ==="
ss -tlnp 2>/dev/null | grep -E ':(22|80|443)\b' || true

echo "=== done ==="
echo "If this session drops now, the provider's web console is the way back in."
