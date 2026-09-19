# vrcx-0-remote-server

A headless VRCX-0 that owns the VRChat session and the database, and serves the
data over the network to any number of users.

This is the server half of the client/server split. It reuses the same
`RuntimeHostState` the desktop app builds, in `HeadlessData` profile: login,
the realtime pipeline, the REST polling schedule and every database write are
the upstream code paths, unchanged. What this binary adds is a multi-tenant data
plane.

## How the split works

```
tenant A ─ app_data_dir ─ RuntimeHostState ─ SQLite (single writer) ─ event sink ─┐
tenant B ─ app_data_dir ─ RuntimeHostState ─ SQLite (single writer) ─ event sink ─┼─> /v1/stream
tenant C ─ app_data_dir ─ RuntimeHostState ─ SQLite (single writer) ─ event sink ─┘
                                                                     ▲
VRChat cloud ──pipeline WS + REST──> per-tenant RuntimeHostState ─────┘
```

`RuntimeEventBus` exposes exactly one sink slot, per runtime. Each tenant claims
its own with a sink that forwards into a broadcast channel, which is what makes
every client of *that* tenant see the same realtime stream and no other tenant's,
without modifying any upstream crate.

Queries do not go through the desktop data layer. `LocalDataRuntime` lives in
`runtime-host-desktop` and drags in screenshots, the Windows registry and the
VR overlay — none of which exist on a router. Its query methods are thin
wrappers over free functions in `vrcx-0-persistence`, which has no desktop
dependency at all, so this server calls those directly.

## Tenancy

A *tenant* is one user's slice of this server:

| | |
|---|---|
| data directory | `<data-root>/tenants/<id>` |
| database | its own, with its own write-ahead log |
| VRChat session | its own, including its own saved credentials |
| event stream | its own bus, so `/v1/stream` cannot leak across users |
| bearer token | its own, stored only as a SHA-256 digest |

This works without touching the composition layer because `RuntimeHostState`
keeps no process-global state at all — every field is an instance field, and its
profile lock is taken per data directory.

**Why it exists.** The first design held one session and handed the same bearer
token to every client that signed in, which made "the token" a server-wide
secret rather than a per-user credential: any user could act as any other.
Transport encryption cannot fix that, because the problem sits on the wrong side
of the connection.

Tokens are issued exactly once, by `POST /v1/tenants`. The server cannot
reproduce one afterwards, so a lost token is replaced rather than looked up.

## Endpoints

Two routes are open, and that is the whole open surface:

| Route | Auth | Purpose |
|---|---|---|
| `GET /v1/health` | no | Liveness, protocol version, and how many tenants are up. Reports nothing about any account. |
| `POST /v1/tenants` | no | Claims a tenant and returns its token. The only way a client with nothing but an address can become something the server will talk to. |

Everything else needs `Authorization: Bearer <token>`, compared in constant
time, and is scoped to the tenant that token identifies:

| Route | Purpose |
|---|---|
| `POST /v1/rpc` | Typed query gateway, see `vrcx-0-remote-protocol`. |
| `POST /v1/command` | Generic command forwarder. Answers `501` for commands the server has not migrated, which the client turns into a local call. |
| `GET /v1/stream` | WebSocket. `Hello`, then one frame per runtime event. |
| `POST /v1/game-log/events` | Raw VRChat log lines relayed from the machine running the game. |
| `GET /v1/auth/status` | Who this tenant is signed in as. |
| `GET /v1/auth/accounts` | Accounts this tenant can sign in without a password. |
| `POST /v1/auth/login` | VRChat username and password, answered with a session or a 2FA challenge. |
| `POST /v1/auth/2fa` | Answers a challenge. |
| `POST /v1/auth/logout` | Signs out and forgets the saved credentials. |
| `POST /v1/tenants/token` | Replaces the calling tenant's token. |

The sign-in routes being protected is the point, not an oversight. They used to
be open because the token was what a successful sign-in handed back; a client now
holds its own token *before* it signs in, so these can require it and each one can
only ever drive one account.

### Reading `/v1/health`

```json
{ "status": "degraded", "appVersion": "2.29.0", "protocolVersion": 2,
  "runtime": { "started": false, "error": "alice: failed to start the VRChat backend runtime: ..." },
  "tenants": { "registered": 2, "live": 2, "running": 1 } }
```

`tenants.running` counts tenants whose VRChat backend actually came up, and a
tenant is exactly one of running / starting / failed. That third state matters:
"no failure recorded yet" is **not** the same as "up", and treating them as one
made a server that had brought nothing up answer `status: "ok"`. `registered` is
the registry file, `live` is what this process loaded.

Two invariants are worth knowing when you consume this:

- `status` is `ok` exactly when `live > 0` and `running == live`. Anything else
  is `degraded`, and `runtime.error` always says why.
- A tenant that has never been signed in reports itself as **degraded**, not as
  broken. There is no VRChat session to restore, so there is nothing running --
  and that is the expected state after a claim or a reboot with an expired
  cookie, not a fault to restart the service over.

## Startup order

The listener is bound **before** any tenant is started, and tenant start-up is
not awaited.

Restoring a VRChat session is a network operation. On a host that cannot reach
the API, each attempt blocks until it times out, and awaiting that first would
hold the port closed for as long as the slowest tenant takes. During that window
nothing outside could tell this process from a dead one -- which is exactly the
confusion `/v1/health` exists to remove, and enough for `procd` (or systemd) to
start killing a server that is fine.

So the order is: bind, announce, start the tenants in the background, serve. A
tenant that is still coming up is reported as starting, and one that fails is
reported with its reason.

## Running

```
cargo run -p vrcx-0-remote-server -- [--bind <address>] [--data-dir <path>]
                                      [--invite <code>] [--token <token>] [--login]
```

| Flag | Meaning |
|---|---|
| `--bind <address>` | Listener (default `0.0.0.0:8790`, env `VRCX_SERVER_BIND`). Bind `127.0.0.1` when a proxy or tunnel sits in front. |
| `--data-dir <path>` | Root directory. One subdirectory per tenant under `tenants/`, registry in `tenants.json`. |
| `--invite <code>` | Required to claim a tenant once one exists (env `VRCX_SERVER_INVITE`). **Set this before the port is reachable from the internet**, or the first caller to reach `/v1/tenants` claims the server. |
| `--token <token>` | Forces the first tenant's token (env `VRCX_SERVER_TOKEN`). A migration aid: pass the token an existing deployment's clients already hold and they keep working across the upgrade. Ignored once a tenant exists. |
| `--login`, `-l` | Interactive VRChat login before serving. Only useful at a terminal; a client can otherwise sign in through `/v1/auth/login`. |

Operator commands, which read the registry and exit without binding anything:

```
vrcx-0-remote-server --list-tenants [--data-dir <path>]
vrcx-0-remote-server --revoke-tenant <tenantId> [--data-dir <path>]
```

`--revoke-tenant` leaves the data directory on disk and takes effect on the next
start, because a running process still holds that tenant in memory.

Omit `--login` for unattended runs: each tenant reuses its persisted auth cookie,
which is what lets the server survive a reboot.

Point `--data-dir` at persistent storage. On a router the default
`~/.config/VRCX-0` usually lands on the overlay filesystem, and a database plus
years of social history will not fit there.

### Upgrading from a single-tenant deployment

If the data root holds a profile written before tenants existed, startup adopts
it as the first tenant and points that tenant at the existing directory. Nothing
is copied, so nothing can be copied halfway: the session, the history and the
saved credentials stay where they are. Pass the old shared token as `--token`
and existing clients keep working without being reconfigured.

## Cross-compiling

The server has no desktop dependencies, so a static musl build works:

```
rustup target add x86_64-unknown-linux-musl
cargo build -p vrcx-0-remote-server --release --target x86_64-unknown-linux-musl
```

Swap the target for `aarch64-unknown-linux-musl` on 64-bit ARM routers,
`armv7-unknown-linux-musleabihf` on 32-bit ARM, or `mips*el-unknown-linux-musl`
on MIPS. `cargo zigbuild` is what the project's `build-musl.ps1` uses, because it
ships the C toolchain without a separate cross compiler.

`rusqlite` is built with `bundled`, so no system SQLite is required.

## Security notes

- **Traffic is plain HTTP.** Terminate TLS in front of it (nginx, Caddy) or keep
  it on a private network such as Tailscale or WireGuard. Do not expose port 8790
  to the internet as-is. Encryption is also only one of the three things that
  make a shared server defensible: see
  `docs/REMOTE_SERVER_MULTITENANT_SECURITY.md` for the identity and authorization
  halves, which are what tenants provide.
- **An unclaimed server with no `--invite` is anybody's.** The first caller to
  reach `/v1/tenants` becomes the first user, by design, so that a fresh server
  can be brought up by one person. Set the invite code before the port is public.
- **A bearer token grants full read access to one tenant's social data.** Treat
  it like a password. It is stored as a digest on the server, so it cannot be
  recovered, only replaced.
- **`POST /v1/rpc` with `{"method":"configList"}` returns the tenant's whole
  config table**, including deobfuscated secrets. Worth narrowing to an allowlist
  before this is reachable by anything but a trusted client.

## Status

Implemented: multi-tenant registry and routing, login, pipeline, polling,
database, health, typed read queries, realtime streaming, the log relay landing
area, and the command forwarder (277 of the desktop's 504 commands answer here,
the rest return `501` and run on the client).

Not yet wired:

- **Log parsing.** Relayed lines are appended to `<data-dir>/remote-relay/` and
  acknowledged with a resumable offset, but nothing consumes the file yet.
- **Media uploads.** Five media commands stay on the client because the bytes do.
- **Config reads.** `config_get` is not migrated; `configList` is the only config
  read available.
