# Cross-compile vrcx-0-remote-server for a Linux musl target (static ELF).
#
# Default target is now x86_64-unknown-linux-musl: the deployment moved off the
# OpenWrt router onto a KVM VPS (Ubuntu 22.04 / x86_64, 2 vCPU / 2 GB). Pass
# -Target aarch64-unknown-linux-musl to still build for the router.
#
# Why this script exists instead of a bare `cargo zigbuild` call:
#
#   Zig uses a scratch `tmp/` directory inside its global cache. It DELETES files there
#   constantly (zig writes `<name>.o.tmp` then renames/removes it). This environment only
#   permits file deletion under the temp directory; everywhere else deletes come back as
#   `AccessDenied`. Zig tolerates failed deletes for ordinary compiles (they surface only as
#   warnings), but the musl `libc.a` sub-compilation that runs at LINK time treats them as
#   fatal:
#
#       warning(compilation): failed to delete '<cache>\tmp\<hash>-thrd_sleep.o.d': AccessDenied
#       error: sub-compilation of musl libc.a failed
#           <zig>\lib\libc\musl\src\network\dn_skipname.c:1:1: note: AccessDenied
#
#   Measured delete permission (2026-09, this box):
#       D:\vrcx-1\...                          denied
#       D:\vrcx-1\src\target-musl\...          denied
#       C:\Users\...\.cargo\tools\...          denied
#       C:\Users\...\AppData\Local\zig\...     denied
#       C:\Users\...\AppData\Local\Temp\...    ALLOWED   <-- cache must live here
#
#   Note the sandbox-bypass flag does NOT help: the denial comes from a hook that applies
#   regardless, and it is invisible in the child's own stderr (zig only reports the symptom).
#   That is why the same build reports `error: Unexpected`, `AccessDenied`, or
#   `[safe-delete] trash-failed` on different runs - all three are this one denial.
#
#   Also keep parallelism low: at the default (32 cores) the failures hit much earlier.
#   `-j 8` got all four C crates (aws-lc-sys, libwebp-sys, zstd-sys, libsqlite3-sys) compiled.
#
# Usage:
#   .\build-musl.ps1                                        # x86_64 (default, for the VPS)
#   .\build-musl.ps1 -Target aarch64-unknown-linux-musl     # for the OpenWrt router
#   .\build-musl.ps1 -Jobs 4

param(
    # Point these at your own VRCX-0 workspace root; the defaults assume the
    # layout of the machine this script was written on.
    [string] $Repo      = "D:\vrcx-1\src",
    [string] $TargetDir = "D:\vrcx-1\src\target-musl",
    [string] $ZigRoot   = "$env:USERPROFILE\.cargo\tools\zig-0.15.2\ziglang",
    [string] $LldShim   = "$env:USERPROFILE\.cargo\lld-shim",
    [string] $Target    = "x86_64-unknown-linux-musl",
    [int]    $Jobs      = 8,
    [int]    $Attempts  = 3
)

$ErrorActionPreference = "Continue"

# Which ELF machine / interp string to expect for each supported target.
switch ($Target) {
    "x86_64-unknown-linux-musl"  { $wantMachine = 0x3E; $wantMachineName = "x86-64";  $muslLoader = "/lib/ld-musl-x86_64.so.1" }
    "aarch64-unknown-linux-musl" { $wantMachine = 0xB7; $wantMachineName = "AArch64"; $muslLoader = "/lib/ld-musl-aarch64.so.1" }
    default { Write-Host "==> unsupported -Target '$Target'"; exit 1 }
}

$binRel  = "target-musl\$Target\release\vrcx-0-remote-server"
$binPath = Join-Path $Repo $binRel
$logDir  = Join-Path $env:TEMP "vrcx-musl-build"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

$env:Path = "$LldShim;$env:USERPROFILE\.cargo\bin;$ZigRoot;$env:Path"
$env:CARGO_TARGET_DIR = $TargetDir
$env:CARGO_TERM_COLOR = "never"

# The one thing that actually matters: zig's scratch cache must sit where deletes work.
$CacheRoot = Join-Path $env:TEMP "zig-cache-musl"
$env:ZIG_GLOBAL_CACHE_DIR = Join-Path $CacheRoot "global"
$env:ZIG_LOCAL_CACHE_DIR  = Join-Path $CacheRoot "local"
New-Item -ItemType Directory -Force -Path $env:ZIG_GLOBAL_CACHE_DIR, $env:ZIG_LOCAL_CACHE_DIR | Out-Null

Write-Host "==> cross-compiling to $Target (jobs=$Jobs, up to $Attempts attempts)"
Write-Host "==> zig cache: $CacheRoot"

for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $log   = Join-Path $logDir "attempt-$attempt-$stamp.log"

    Write-Host "==> attempt $attempt/$Attempts"
    Push-Location $Repo
    (& cargo zigbuild -p vrcx-0-remote-server --release --target $Target -j $Jobs) *>&1 |
        Out-File $log -Encoding utf8
    $code = $LASTEXITCODE
    Pop-Location

    if (Test-Path $binPath) {
        Write-Host "==> attempt $attempt produced a binary (cargo exit=$code)"
        break
    }

    $hint = ""
    if (Select-String -Path $log -Pattern "sub-compilation of musl libc\.a failed" -Quiet -ErrorAction SilentlyContinue) {
        $hint = "  (musl libc.a sub-compilation was denied a delete - check that ZIG_GLOBAL_CACHE_DIR is under the temp dir)"
    }
    Write-Host "==> attempt $attempt failed (cargo exit=$code)$hint"
    Write-Host "    log: $log"
    if ($attempt -eq $Attempts) {
        Write-Host "==> all $Attempts attempts failed; last log: $log"
        exit 1
    }
}

if (-not (Test-Path $binPath)) { Write-Host "==> no binary at $binPath"; exit 1 }

# ---- report + validate the ELF ----
$f = Get-Item $binPath
$b = [IO.File]::ReadAllBytes($binPath)
$machine = $b[18] + ($b[19] -shl 8)
$etype   = $b[16] + ($b[17] -shl 8)
$isElf   = (($b[0] -eq 0x7F) -and ($b[1] -eq 0x45) -and ($b[2] -eq 0x4C) -and ($b[3] -eq 0x46))

Write-Host ""
Write-Host "==> binary: $binPath"
Write-Host "    size     : $([math]::Round($f.Length/1MB,2)) MB"
Write-Host "    mtime    : $($f.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss'))"
Write-Host "    elf      : $isElf  class=$(if($b[4] -eq 2){'ELF64'}else{$b[4]})  endian=$(if($b[5] -eq 1){'LE'}else{'BE'})"
Write-Host "    machine  : 0x$('{0:X}' -f $machine)  $(if($machine -eq $wantMachine){"$wantMachineName OK"}else{"UNEXPECTED - expected $wantMachineName"})"
Write-Host "    type     : $(if($etype -eq 2){'EXEC (static, no PIE)'}elseif($etype -eq 3){'DYN (PIE)'}else{$etype})"

# A dynamically linked musl binary dies on a bare host with a cryptic "not found".
$needsLoader = [Text.Encoding]::ASCII.GetString($b).Contains($muslLoader)
Write-Host "    linkage  : $(if($needsLoader){"DYNAMIC - needs $muslLoader at runtime!"}else{'static (no musl loader referenced)'})"

if (-not $isElf -or $machine -ne $wantMachine) { Write-Host "==> VALIDATION FAILED"; exit 1 }
if ($needsLoader) { Write-Host "==> WARNING: not statically linked; deployment may fail" }
Write-Host "==> done"
