# One-command deployment of the VRCX-0 remote server to a Linux VPS.
#
# Runs on the Windows build host. Drives plink/pscp (PuTTY) to:
#   1. build the static x86_64 musl binary       (-SkipBuild to reuse)
#   2. upload the binary and the server-side scripts
#   3. provision.sh        (systemd unit, service user, data dir, self-check)
#   4. tls-setup.sh <ip>   (nginx + self-signed cert, prints the SPKI pin)
#   5. firewall.sh         (ufw; skipped with -SkipFirewall)
#   6. verify: loopback, TLS through nginx, and finally the public IP from here
#
# The first run on a fresh host also generates the invite code, writes it to
# /etc/vrcx-0/remote-server.env, and prints it. Keep that output: the invite
# code is required to claim any tenant after the first.
#
# Host key handling is TOFU: pass -ServerKey to pin it (recommended on every run
# after the first), or omit it once and the fingerprint the server presents is
# trusted and printed in big letters for you to record.
#
# Example (fill in your own host; never commit real credentials or host keys):
#   .\deploy-vps.ps1 -Server <server-ip> -Password '***' -ServerKey 'SHA256:...'

param(
    [Parameter(Mandatory=$true)]  [string] $Server,
    [string] $User      = "root",
    [Parameter(Mandatory=$true)]  [string] $Password,
    [string] $ServerKey   = "",
    [string] $RepoRoot  = "D:\vrcx-1",
    [string] $Binary    = "D:\vrcx-1\src\target-musl\x86_64-unknown-linux-musl\release\vrcx-0-remote-server",
    [string] $Plink     = "C:\Program Files\PuTTY\plink.exe",
    [string] $Pscp      = "C:\Program Files\PuTTY\pscp.exe",
    [switch] $SkipBuild,
    [switch] $SkipFirewall
)

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

function Say($msg) { Write-Host "==> $msg" }

# ---------------------------------------------------------------- host key --
$hostKeyArgs = @()
if ($ServerKey -ne "") {
    $hostKeyArgs = @("-hostkey", $ServerKey)
} else {
    Say "no -ServerKey given; fetching the server's fingerprint (TOFU)"
    $probe = & $Plink -ssh -batch "$User@$Server" -pw $Password "echo ok" 2>&1 | Out-String
    if ($probe -match "SHA256:[A-Za-z0-9+/=]+") {
        $ServerKey = $Matches[0]
        Write-Host ""
        Write-Host "  ************************************************************"
        Write-Host "  *  Trusting this host key on first use. Record it and pass *"
        Write-Host "  *  -ServerKey '$ServerKey'"
        Write-Host "  *  on every later run, and verify it out-of-band once.     *"
        Write-Host "  ************************************************************"
        Write-Host ""
        $hostKeyArgs = @("-hostkey", $ServerKey)
    } else {
        throw "could not learn the host key: $probe"
    }
}

function Remote($cmd) {
    & $Plink -ssh -batch @hostKeyArgs "$User@$Server" -pw $Password $cmd
    if ($LASTEXITCODE -ne 0) { throw "remote command failed (exit $LASTEXITCODE): $cmd" }
}

function Upload($local, $remotePath) {
    & $Pscp -batch @hostKeyArgs -pw $Password $local "${User}@${Server}:$remotePath"
    if ($LASTEXITCODE -ne 0) { throw "upload failed: $local -> $remotePath" }
}

# ------------------------------------------------------------------- build --
if (-not $SkipBuild) {
    Say "building the static musl binary"
    & "$RepoRoot\.workbuddy\scripts\build-musl.ps1" -Jobs 8
    if ($LASTEXITCODE -ne 0) { throw "build failed" }
}
if (-not (Test-Path $Binary)) { throw "no binary at $Binary (run without -SkipBuild)" }
$binInfo = Get-Item $Binary
Say ("binary: {0} ({1:N1} MB, built {2})" -f $Binary, ($binInfo.Length/1MB), $binInfo.LastWriteTime)

# ------------------------------------------------- normalise script line ends --
# sh chokes on CRLF ("bad interpreter"); rewrite every script we ship as LF.
$stage = Join-Path $env:TEMP "vrcx-vps-stage"
Remove-Item -Recurse -Force $stage -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $stage | Out-Null
foreach ($f in @("provision.sh", "tls-setup.sh", "firewall.sh", "verify.sh")) {
    $text = [IO.File]::ReadAllText((Join-Path $here $f)) -replace "`r`n", "`n"
    [IO.File]::WriteAllText((Join-Path $stage $f), $text, (New-Object Text.UTF8Encoding($false)))
}

# ------------------------------------------------------------------ upload --
Say "preparing directories on $Server"
Remote "mkdir -p /opt/vrcx-0/bin /tmp/vrcx-deploy && echo ready"

Say "uploading the binary (this stops nothing yet)"
Upload $Binary "/tmp/vrcx-deploy/vrcx-0-remote-server"
foreach ($f in @("provision.sh", "tls-setup.sh", "firewall.sh", "verify.sh")) {
    Upload (Join-Path $stage $f) "/tmp/vrcx-deploy/$f"
}
# Keep the read-only self-check around after the deploy (it is how you confirm
# later that 8790 is still loopback-only).
Remote "mkdir -p /opt/vrcx-0/deploy && cp /tmp/vrcx-deploy/verify.sh /opt/vrcx-0/deploy/verify.sh && chmod +x /opt/vrcx-0/deploy/verify.sh"

# --------------------------------------------------------------- provision --
Say "provisioning (systemd unit, service user, data dir)"
# Stop the live service FIRST: overwriting a running executable in place fails
# with ETXTBSY. Only then swap the staged binary in, and only then run
# provision.sh, whose preflight check expects the binary at its final path.
Remote "systemctl stop vrcx-0-remote-server 2>/dev/null || true"
Remote "cp /tmp/vrcx-deploy/vrcx-0-remote-server /opt/vrcx-0/bin/vrcx-0-remote-server"
Remote "sh /tmp/vrcx-deploy/provision.sh 2>&1 | tee /tmp/vrcx-provision.out | tail -22"

# ------------------------------------------------------- invite code (once) --
Say "ensuring an invite code exists"
$invite = Remote "sh -c 'grep -E ^VRCX_SERVER_INVITE= /etc/vrcx-0/remote-server.env 2>/dev/null | cut -d= -f2'"
if ([string]::IsNullOrWhiteSpace($invite)) {
    $invite = -join ((48..57) + (97..122) | Get-Random -Count 24 | ForEach-Object { [char]$_ })
    Remote "sh -c 'echo VRCX_SERVER_INVITE=$invite >> /etc/vrcx-0/remote-server.env && systemctl restart vrcx-0-remote-server'"
    Write-Host ""
    Write-Host "  +----------------------------------------------------------+"
    Write-Host "  |  NEW INVITE CODE (needed to claim any tenant after #1):  |"
    Write-Host "  |    $invite"
    Write-Host "  +----------------------------------------------------------+"
    Write-Host ""
} else {
    Say "invite code already set (not printed; it is a secret)"
}

# ------------------------------------------------------------------ TLS -----
Say "setting up the TLS front end (nginx + self-signed cert for $Server)"
Remote "sh /tmp/vrcx-deploy/tls-setup.sh $Server 2>&1 | tee /tmp/vrcx-tls.out | tail -30"

# --------------------------------------------------------------- firewall ---
if (-not $SkipFirewall) {
    Say "enabling the host firewall (22/80/443 only)"
    Remote "sh /tmp/vrcx-deploy/firewall.sh 2>&1 | tail -12"
}

# ------------------------------------------------------------------ verify --
# Server-side red-line self-check: binding, data plane, nginx, auth, tenants.
Say "verification: server self-check"
Remote "sh /opt/vrcx-0/deploy/verify.sh 2>&1 | tail -34"
Say "verification: health payload through nginx on the loopback interface"
Remote "curl -sk -m 8 --noproxy '*' https://127.0.0.1/v1/health"

Say "verification: wrong token must be refused with 401 (not 200)"
Remote "curl -sk -m 8 --noproxy '*' -o /dev/null -w '%{http_code}' -H 'Authorization: Bearer wrong' https://127.0.0.1/v1/auth/status"

Say "verification: public endpoint from this machine"
$pub = & curl.exe -sk -m 10 --noproxy '*' "https://$Server/v1/health"
Write-Host $pub

# ------------------------------------------------------------- connection ---
$pin = Remote "openssl x509 -in /etc/vrcx-0/tls/server.crt -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | openssl enc -base64"

Write-Host ""
Write-Host "  ============================================================"
Write-Host "   Android client connection"
Write-Host "  ============================================================"
Write-Host "   server address : https://$Server"
Write-Host "   SPKI pin       : $pin"
Write-Host ""
Write-Host "   First launch: the app claims tenant #1 by itself (no invite"
Write-Host "   needed for the first one). The invite code above is for any"
Write-Host "   further devices/people."
Write-Host ""
Write-Host "   If the public check above failed but the loopback one passed,"
Write-Host "   the cloud provider's security group is not allowing TCP 443 -"
Write-Host "   open it in the provider's console; nothing on the host can fix that."
Write-Host ""
