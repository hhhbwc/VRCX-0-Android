# Deploying to the target router

Everything below was measured on the actual device, not assumed. Where a step
depends on something still undecided, it says so.

## The device

| | |
|---|---|
| Model | Cudy TR3000 256MB v1 (MT7981B) |
| Firmware | BleachWrt / OpenWrt 24.10.5, `mediatek/filogic` |
| CPU | dual-core Cortex-A53 @ 1.3 GHz (`CPU part: 0xd03`) |
| libc | **musl 1.2.5, aarch64** |
| RAM | 496 MB total, ~225 MB available, **no swap, no zram** |
| Flash overlay | 160 MB total, **152 MB available** |
| External disk | `/dev/sda`, 58.6 GB, NTFS, label `211` — detected, **not mounted** |
| Filesystem drivers | `ntfs3` (loaded), `ext4`, `f2fs`, `vfat`, `exfat` |
| Network | LAN `192.168.1.0/24`; upstream via `wlan0` to `192.168.31.1` |
| Internet | reachable, DNS resolves `vrchat.com` — the pipeline can connect |
| System SQLite | absent → `rusqlite` **must** be `bundled` (it already is) |

Two consequences worth internalising:

1. The target triple is `aarch64-unknown-linux-musl`. A statically linked
   binary drops in with no packages to install — every filesystem driver and
   the ntfs3 module are already compiled into this kernel.
2. **Nothing can be built on the device.** Dual-core A53 and no toolchain means
   cross-compiling from a real machine is mandatory.

## Storage: why flash is not an option

152 MB has to hold the binary (~20–30 MB stripped) and every future row of the
database. `game_log` and the feed grow without bound — a few months of daily
playing puts SQLite into the hundreds of megabytes. Filling `/overlay` does not
just break the server, it damages the firmware's own writable layer, since that
same UBIFS volume holds your OpenWrt config.

So the data directory has to go on the external disk. That part is **done and
verified** on the reference device:

| | |
|---|---|
| Partition | `/dev/sda1`, MBR, 62.9 GB (the disk had no partition table before) |
| Filesystem | ext4, label `vrcx-data`, UUID `4e1b42e8-ba22-4226-8920-c76fc448ea73` |
| Mount | `/mnt/vrcx-data`, `noatime`, 57.4 GB free |
| Persistence | `fstab.vrcxdata` via uci, matched by **UUID**, not `/dev/sda1` |

Mount by UUID on purpose: a router that boots with a different disk order would
otherwise mount the wrong volume at that path, and the server would happily
start writing into it.

Doing it again from scratch would be:

```
parted -s /dev/sda mklabel msdos
parted -s /dev/sda mkpart primary ext4 1MiB 100%
mkfs.ext4 -F -L vrcx-data -m 0 /dev/sda1
```

Verify the mount survives a reboot without actually rebooting:

```
umount /mnt/vrcx-data && block mount && df -h /mnt/vrcx-data
```

### Warning: the reference USB stick is barely usable

Measured on the actual disk, 64 MB and smaller writes with `conv=fsync`:

| Test | Time | Throughput |
|---|---|---|
| 64 MB | 1 m 56 s | ~0.55 MB/s |
| 8 MB | 1 m 11 s | ~0.11 MB/s |
| 4 MB | 2.53 s | ~1.58 MB/s |
| 4 MB (repeat) | 50.4 s | ~0.08 MB/s |
| 8 MB (repeat) | 4.71 s | ~1.70 MB/s |

Read that spread carefully: **the same 4 MB write took 2.5 s once and 50 s the
next time.** That is not a slow disk, that is an *erratic* one — cheap flash
controller doing long internal garbage collection, and the delays land on
`fsync`. The stick reports as `346d:5678 "Disk 2.0"` on a 480 Mbps USB 2.0 link.

Two things make this survivable but not solved:

- The persistence layer already runs `PRAGMA journal_mode=WAL` with
  `synchronous=NORMAL` (`persistence/src/database/service.rs:598`), which is the
  right configuration for slow storage — writes become sequential appends and
  `fsync` stops being per-transaction. Throughput is adequate for VRCX's actual
  write volume.
- It does **not** fix the latency spikes. A 50-second stall inside a WAL
  checkpoint will block a tokio blocking thread and make the server look hung.

The router's USB controller is capable of far more than this stick delivers —
`/sys/bus/usb/devices/usb2/speed` reports `20000` (USB 3.x), and the stick is
sitting on the 480 Mbps bus. **A USB 3.0 stick or an SSD is the single highest
value upgrade for this deployment.** Budget for one before trusting the server
with real data.

## Building

Adding a `[profile.release]` in `crates/remote-server/Cargo.toml` does nothing —
Cargo only honours profiles from the **workspace root**. Put it there:

```toml
[profile.release]
strip = true
lto = true
codegen-units = 1
panic = "abort"
```

Then build with the zig linker, which supplies musl libc itself and avoids
hunting for an `aarch64-linux-musl-gcc` toolchain:

```
rustup target add aarch64-unknown-linux-musl
cargo install cargo-zigbuild
cargo zigbuild -p vrcx-0-remote-server --release --target aarch64-unknown-linux-musl
```

### Getting zig is the hard part, not the build

The build needs a cross C compiler, and `zig cc` is the one that works without
hunting for a sysroot. Getting hold of it is the step that actually costs time.

**Do not download zig from ziglang.org.** Measured from a mainland China
connection, it delivered ~7 KB/s through the proxy — 88 MB would take about
3.5 hours. Every OpenWrt-adjacent mirror is either worse or does not carry the
Windows build at all:

| Source | Result |
|---|---|
| `ziglang.org/download/...` | 17 KB/s |
| `pkg.machengine.org/zig/...` | 8 KB/s |
| `mirrors.tuna/ustc/nju/zju/cernet/sjtu.edu.cn/zig/...` | 404, or ≤5 KB/s |

The package that actually works is **`ziglang` on PyPI**, which ships the
complete zig distribution as a wheel, and PyPI has fast mirrors in China:

```
pip download ziglang==0.15.2 --no-deps --only-binary=:all: \
    --platform win_amd64 --python-version 3.11 -d ./zigpkg \
    -i https://pypi.tuna.tsinghua.edu.cn/simple
```

That pulled 94 MB at **10.5 MB/s** — nine seconds instead of three and a half
hours. A wheel is just a zip, and the usual extractors are unreliable here:
PowerShell's `Expand-Archive` exits 0 and silently produces an empty directory,
and `tar -xf` fails outright. Use Python's `zipfile`, which handles the ~19,000
entries correctly:

```
python -c "import zipfile,sys; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])" \
    zigpkg/ziglang-0.15.2-py3-none-win_amd64.whl zig-0.15.2
```

The binary lands at `zig-0.15.2/ziglang/zig.exe`. It finds its own `lib/`
relative to itself, so `ziglang/` is what goes on `PATH` — do not copy the
`.exe` out on its own. Verify with `zig version` before blaming cargo.

`cargo-zigbuild` builds from source in about eight minutes (v0.23.4, tested
against zig 0.15.2 and 0.16.0).

### Status: the cross build passes

Measured on the reference Windows machine: the host build succeeds (15m16s, 9.5 MB,
verified working) and so does the `aarch64-unknown-linux-musl` build — a 9.55 MB
static, non-PIE aarch64 ELF, verified executing **on the router** (`--help` prints,
and the runtime starts, opens the database off flash and reaches the VRChat login).

Three things are needed, and only the first is non-obvious.

**1. Put zig's cache where deletions are allowed. This is the entire mystery.**

Zig keeps a scratch `tmp/` inside its global cache and deletes `<name>.o.tmp`
files there constantly. When a delete is refused, ordinary compiles only log a
warning — but the musl `libc.a` sub-compilation that runs at **link** time treats
it as fatal:

```
warning(compilation): failed to delete '<cache>\tmp\<hash>-thrd_sleep.o.d': AccessDenied
error: sub-compilation of musl libc.a failed
    <zig>\lib\libc\musl\src\network\dn_skipname.c:1:1: note: AccessDenied
```

The same refusal surfaces under three different messages depending on how zig
maps the errno — a bare `error: Unexpected`, `failed to delete ...: AccessDenied`,
or `[safe-delete][SAFE_DELETE_FAIL_CLOSED] trash-failed`. That is why the symptom
looks random and why blaming individual crates leads nowhere: `aws-lc-sys` even
compiles several hundred objects successfully before hitting it.

Deletion permission on the reference machine:

| Location | Deleting works? |
| --- | --- |
| `<repo>\...` | no |
| `<repo>\src\target-musl\...` | no |
| `%USERPROFILE%\.cargo\tools\...` | no |
| `%LOCALAPPDATA%\zig\...` | no |
| `%TEMP%\...` | **yes** |

So point the cache at the temp directory:

```
set ZIG_GLOBAL_CACHE_DIR=%TEMP%\zig-cache-musl\global
set ZIG_LOCAL_CACHE_DIR=%TEMP%\zig-cache-musl\local
```

Disabling the sandbox does **not** help: the refusal comes from a hook that
applies either way, and it never shows up in the child process's own stderr.
To test whether a directory is usable, just try deleting a file in it.

**2. Give the cross build its own target directory.**

Cross-compiling relinks the host build scripts, and overwriting the existing
artifacts produces `lld-link: failed to write output ...: permission denied`:

```
set CARGO_TARGET_DIR=<repo>\target-musl
```

**3. Cap the parallelism.**

At the default (one job per core; 32 here) failures land much earlier. With `-j 8`
all four C dependencies compile — `aws-lc-sys` (371 objects), `libwebp-sys` (123),
`zstd-sys` (36), `libsqlite3-sys`. Budget roughly 19 minutes for the C work and
6–13 minutes for the link.

```
cargo zigbuild -p vrcx-0-remote-server --release --target aarch64-unknown-linux-musl -j 8
```

`<repo>\.workbuddy\scripts\build-musl.ps1` wraps all three settings and validates
the resulting ELF.

Dropping `libwebp-sys`/`zstd-sys` from the server's dependency graph is still
worth doing on its own merits — a process that only serialises JSON to a socket
has no business decoding WebP, and it would cut the binary further — but it is a
**size** optimisation now, not a prerequisite for building.

Until one of those is done, the binary in `target/aarch64-unknown-linux-musl/`
will not exist, and the router will keep reporting "binary missing".

Expect 15–40 MB after stripping. Verify the result really is static before
copying it over — a dynamically linked binary will fail on the router with a
confusing "not found":

```
file target/aarch64-unknown-linux-musl/release/vrcx-0-remote-server
```

## First login: do it off the router

This is the part that trips people up. The server needs a VRChat session, and
establishing one may require username, password and a 2FA code — all typed at a
prompt. Under procd there is no terminal, so `--login` cannot work there.

The way around it is that **the session cookie is persisted in the SQLite
file**. `start_headless_backend_runtime` takes an
`Option<CliLoginPrompt>`: when it is `None` and a session already exists, it
calls `current_user_from_cookie()` and resumes unattended. Only a genuinely
expired cookie raises `AuthInteractionRequired`.

So:

1. Run the server once **on a machine with a keyboard**, with `--login`, using
   the same `--data-dir` you will later move over. Complete login and 2FA.
2. Confirm it reaches a running state and has written `VRCX-0.sqlite3`.
3. Copy that data directory to the router's external disk.
4. Start the service **without** `--login`. It resumes from the cookie.

When the cookie eventually expires months later, repeat step 1 and re-copy the
database. That is the whole maintenance loop.

## Installing

Current state on the reference device: the mount, the data directory and the
init script are **already in place**. Only the binary is missing, because
nothing has been compiled yet.

```
/mnt/vrcx-data/VRCX-0              data directory, on the external disk
/opt/vrcx-0/bin                    where the binary goes (on flash, ~20-30 MB)
/etc/init.d/vrcx-remote-server     installed, executable, syntax-checked
```

The service is deliberately **not enabled yet**: with no binary present, every
boot would log a refusal. Once the binary is copied over:

```
scp vrcx-0-remote-server root@192.168.1.1:/opt/vrcx-0/bin/
/etc/init.d/vrcx-remote-server enable
/etc/init.d/vrcx-remote-server start
```

The init script refuses to start if either the data directory or the binary is
missing, rather than creating a fresh database on flash and filling it.

Check that it survived and is talking to VRChat:

```
logread -e vrcx-remote-server
curl http://192.168.1.1:8790/v1/health
```

The health endpoint deliberately reveals nothing about the account. Everything
else needs a per-tenant bearer token, which a client obtains from
`POST /v1/tenants` — see `README.md` for the two open routes and why the sign-in
routes are no longer among them.

## Keeping it stable

- **Swap needs a workaround, not a package install.** With ~221 MB available
  and no swap at all, a transient spike invites the OOM killer. The obvious
  move does not work on the reference device: **`zram-swap` is not in its
  feeds** (`opkg list | grep zram` returns nothing on BleachWrt 24.10.5), so
  `opkg install zram-swap` fails. Run `opkg update` first and re-check — a
  stale package index looks identical to a package that does not exist. If it
  really is absent, the alternatives are a swap *file* on the external disk
  (slow, and the stick in the reference build is erratic enough to make this
  unattractive) or simply not running anything else on the router.
- **Backups.** The disk is on a router that reboots with your wifi. `sqlite3
  .backup` or a periodic copy of the directory elsewhere — do not rely on the
  single copy.
- **Logrotate.** `procd` sends stdout to syslog. Long-running applications are
  chatty; check `/overlay` usage stays low.

## Before any of this goes live

The router answers SSH as root with the password `password`, on a device that
is also your network gateway. Change it:

```
passwd
```

The server itself listens on plain HTTP and a bearer token discloses one
account's entire social graph. Keep port 8790 on the LAN. Do not port-forward
it, and do not expose it to the internet without TLS in front — and set
`VRCX_SERVER_INVITE` first, because an unclaimed server with no invite code can
be claimed by whoever reaches the port first.
