# Cross-compile vrcx-0-remote-server for Linux musl targets (static ELF) and Windows.
#
# Default target is x86_64-unknown-linux-musl: the reference deployment is a KVM VPS
# (Ubuntu 24.04 / x86_64). Pass -Target <triple> for anything else; run -List to see
# every target this script knows how to build and validate.
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
#   .\build-musl.ps1                                            # x86_64 (default, for the VPS)
#   .\build-musl.ps1 -Target aarch64-unknown-linux-musl         # ARM64 NAS / router / phone
#   .\build-musl.ps1 -Target armv7-unknown-linux-musleabihf     # 32-bit ARM (old NAS, Pi)
#   .\build-musl.ps1 -Target riscv64gc-unknown-linux-musl       # RISC-V board
#   .\build-musl.ps1 -Target x86_64-pc-windows-msvc             # Windows (no zig needed)
#   .\build-musl.ps1 -List

param(
    # Point these at your own VRCX-0 workspace root; the defaults assume the
    # layout of the machine this script was written on.
    [string] $Repo      = "D:\vrcx-1\src",
    [string] $TargetDir = "D:\vrcx-1\src\target-musl",
    [string] $ZigRoot   = "$env:USERPROFILE\.cargo\tools\zig-0.15.2\ziglang",
    [string] $LldShim   = "$env:USERPROFILE\.cargo\lld-shim",
    [string] $Target    = "x86_64-unknown-linux-musl",
    [int]    $Jobs      = 8,
    [int]    $Attempts  = 3,
    [switch] $List
)

$ErrorActionPreference = "Continue"

# ---------------------------------------------------------------------------
# Every target this script knows how to build, and how to validate the result.
#
#   Kind     elf = check ELF e_machine + make sure no musl loader is referenced
#            pe  = check the DOS/PE header machine field
#   UseZig   $true  -> `cargo zigbuild` (zig supplies the cross libc: musl / mingw)
#            $false -> plain `cargo build` (host toolchain links it, e.g. MSVC)
#
# ELF e_machine values: 0x3E x86-64, 0xB7 AArch64, 0x03 i386, 0x28 ARM, 0xF3 RISC-V.
# PE machine values:    0x8664 x86-64, 0xAA64 ARM64.
# ---------------------------------------------------------------------------
$Targets = [ordered]@{
    "x86_64-unknown-linux-musl"      = @{ Kind = "elf"; Machine = 0x3E;   Name = "x86-64";       Loader = "/lib/ld-musl-x86_64.so.1";    UseZig = $true;  Note = "云 VPS / 普通服务器（默认）" }
    "aarch64-unknown-linux-musl"     = @{ Kind = "elf"; Machine = 0xB7;   Name = "AArch64";     Loader = "/lib/ld-musl-aarch64.so.1";   UseZig = $true;  Note = "ARM64 NAS / 路由器 / Termux" }
    "i686-unknown-linux-musl"        = @{ Kind = "elf"; Machine = 0x03;   Name = "i386";        Loader = "/lib/ld-musl-i386.so.1";      UseZig = $true;  Note = "32 位 x86 老机器" }
    "armv7-unknown-linux-musleabihf" = @{ Kind = "elf"; Machine = 0x28;   Name = "ARM";         Loader = "/lib/ld-musl-armhf.so.1";     UseZig = $true;  Note = "32 位 ARM：老 NAS / Pi / OpenWrt" }
    "riscv64gc-unknown-linux-musl"   = @{ Kind = "elf"; Machine = 0xF3;   Name = "RISC-V";      Loader = "/lib/ld-musl-riscv64.so.1";   UseZig = $true;  Note = "RISC-V 开发板" }
    "x86_64-pc-windows-msvc"         = @{ Kind = "pe";  Machine = 0x8664; Name = "x86-64 (PE)"; Loader = "";                            UseZig = $false; Note = "Windows（本机 MSVC，不用 zig）" }
    "aarch64-pc-windows-msvc"        = @{ Kind = "pe";  Machine = 0xAA64; Name = "ARM64 (PE)";  Loader = "";                            UseZig = $false; Note = "Windows on ARM（需 MSVC ARM64 工具链）" }
    "x86_64-pc-windows-gnu"          = @{ Kind = "pe";  Machine = 0x8664; Name = "x86-64 (PE)"; Loader = "";                            UseZig = $true;  Note = "Windows MinGW（zig 提供 libc）" }
}

if ($List) {
    Write-Host "supported -Target values:"
    foreach ($k in $Targets.Keys) {
        $t = $Targets[$k]
        Write-Host ("  {0,-32} {1,-14} {2}" -f $k, $t.Name, $t.Note)
    }
    exit 0
}

if (-not $Targets.Contains($Target)) {
    Write-Host "==> unsupported -Target '$Target'; run with -List to see the supported ones"
    exit 1
}
$spec        = $Targets[$Target]
$wantMachine = $spec.Machine
$wantName    = $spec.Name
$muslLoader  = $spec.Loader
$useZig      = $spec.UseZig
$isWindows   = $Target -like "*windows*"

$binRel  = "target-musl\$Target\release\vrcx-0-remote-server" + $(if ($isWindows) { ".exe" } else { "" })
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

$buildCmd = if ($useZig) { "cargo zigbuild" } else { "cargo build" }
Write-Host "==> cross-compiling to $Target via $buildCmd (jobs=$Jobs, up to $Attempts attempts)"
Write-Host "==> zig cache: $CacheRoot"

for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $log   = Join-Path $logDir "attempt-$attempt-$stamp.log"

    Write-Host "==> attempt $attempt/$Attempts"
    Push-Location $Repo
    if ($useZig) {
        (& cargo zigbuild -p vrcx-0-remote-server --release --target $Target -j $Jobs) *>&1 |
            Out-File $log -Encoding utf8
    } else {
        (& cargo build -p vrcx-0-remote-server --release --target $Target -j $Jobs) *>&1 |
            Out-File $log -Encoding utf8
    }
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

# ---- report + validate the executable ----
$f = Get-Item $binPath
$b = [IO.File]::ReadAllBytes($binPath)

Write-Host ""
Write-Host "==> binary: $binPath"
Write-Host "    size     : $([math]::Round($f.Length/1MB,2)) MB"
Write-Host "    mtime    : $($f.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss'))"

if ($spec.Kind -eq "pe") {
    # 'MZ' + e_lfanew -> 'PE\0\0' -> COFF header -> machine (2 bytes LE)
    $isPe = ($b[0] -eq 0x4D) -and ($b[1] -eq 0x5A)
    $machine = $null
    if ($isPe) {
        $lfanew = [BitConverter]::ToInt32($b, 0x3C)
        if ($b[$lfanew] -eq 0x50 -and $b[$lfanew + 1] -eq 0x45 -and $b[$lfanew + 2] -eq 0 -and $b[$lfanew + 3] -eq 0) {
            $machine = [BitConverter]::ToUInt16($b, $lfanew + 4)
        }
    }
    Write-Host "    pe       : $isPe  machine=0x$(if ($machine -ne $null) { '{0:X}' -f $machine } else { '??' })  $(if ($machine -eq $wantMachine) { "$wantName OK" } else { "UNEXPECTED - expected $wantName" })"
    if (-not $isPe -or $machine -ne $wantMachine) { Write-Host "==> VALIDATION FAILED"; exit 1 }
} else {
    $machine = $b[18] + ($b[19] -shl 8)
    $etype   = $b[16] + ($b[17] -shl 8)
    $isElf   = (($b[0] -eq 0x7F) -and ($b[1] -eq 0x45) -and ($b[2] -eq 0x4C) -and ($b[3] -eq 0x46))

    Write-Host "    elf      : $isElf  class=$(if($b[4] -eq 2){'ELF64'}else{$b[4]})  endian=$(if($b[5] -eq 1){'LE'}else{'BE'})"
    Write-Host "    machine  : 0x$('{0:X}' -f $machine)  $(if($machine -eq $wantMachine){"$wantName OK"}else{"UNEXPECTED - expected $wantName"})"
    Write-Host "    type     : $(if($etype -eq 2){'EXEC (static, no PIE)'}elseif($etype -eq 3){'DYN (PIE)'}else{$etype})"

    # A dynamically linked musl binary dies on a bare host with a cryptic "not found".
    $needsLoader = [Text.Encoding]::ASCII.GetString($b).Contains($muslLoader)
    Write-Host "    linkage  : $(if($needsLoader){"DYNAMIC - needs $muslLoader at runtime!"}else{'static (no musl loader referenced)'})"

    if (-not $isElf -or $machine -ne $wantMachine) { Write-Host "==> VALIDATION FAILED"; exit 1 }
    if ($needsLoader) { Write-Host "==> WARNING: not statically linked; deployment may fail" }
}

Write-Host "==> done"
