#!/usr/bin/env python3
"""End-to-end acceptance test for the multi-tenant remote server.

Why this exists
---------------
The multi-tenant change rewrote the auth middleware, the route partition and the
tenant lifecycle. `cargo test` covers the units (registry, settings parsing,
protocol round-trips) but cannot show that the *running* process actually refuses
an unauthenticated caller, issues one credential per tenant, and revokes on
rotation. Those are exactly the properties the design rests on, so they get
tested against a real listener.

No VRChat account is needed. Everything up to sign-in is exercised, and a tenant
that cannot sign in is *supposed* to report itself degraded -- which is itself
worth asserting.

Why Python rather than bash+curl
--------------------------------
It was written in bash first and that was a mistake, three times over:
  * Git Bash did not convert `/c/Users/...` when passing `--data-dir` to a native
    Windows binary, so the server resolved it against the current drive and wrote
    to `D:\\c\\Users\\...` while the script checked `C:\\Users\\...` -- two
    different trees, and every file assertion silently looked in the wrong one;
  * `taskkill //F //IM` passed `//F` through unconverted, so the teardown never
    killed anything and a stale listener poisoned the next run;
  * curl reusing one `-o` file across steps left no output file at all when a
    request failed, which turned one connection error into a cascade of
    "No such file or directory" that hid the real cause.
Python side-steps all three: paths are strings we control, the child is a Popen
handle we own, and a failed request still returns a status.

Usage
-----
    python .workbuddy/scripts/e2e_remote_server.py

Leaves nothing behind: works in a fresh temp directory and terminates only the
process it started.
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
EXE = os.path.join(ROOT, "src", "target", "debug", "vrcx-0-remote-server.exe")
SRC = os.path.join(ROOT, "src")

BIND = "127.0.0.1:8790"
BASE = f"http://{BIND}"

# The system proxy is configured on this machine and urllib honours it, which
# makes a request to 127.0.0.1 leave the machine and come back dead.
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))

GREEN, RED, DIM, RESET = "\033[32m", "\033[31m", "\033[2m", "\033[0m"

_passed = 0
_failed = 0
_children: list[subprocess.Popen] = []


class Resp:
    """A response or a transport failure. `status == 0` means it never connected."""

    __slots__ = ("status", "body")

    def __init__(self, status: int, body: str) -> None:
        self.status = status
        self.body = body

    def json(self, path: str):
        try:
            value = json.loads(self.body)
        except Exception:
            return None
        for key in path.split("."):
            if not key:
                continue
            if isinstance(value, list):
                value = value[int(key)]
            elif isinstance(value, dict):
                value = value.get(key)
            else:
                return None
        return value


def call(method: str, path: str, token: str | None = None,
         payload=None, raw_auth: str | None = None) -> Resp:
    data = None
    headers: dict[str, str] = {}
    if payload is not None:
        data = json.dumps(payload).encode()
        headers["Content-Type"] = "application/json"
    if raw_auth is not None:
        headers["Authorization"] = raw_auth
    elif token is not None:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(BASE + path, data=data, headers=headers,
                                     method=method)
    try:
        with OPENER.open(request, timeout=15) as response:
            return Resp(response.status, response.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as error:
        return Resp(error.code, error.read().decode("utf-8", "replace"))
    except Exception as error:  # connection refused, timeout, ...
        return Resp(0, repr(error))


def check(desc: str, expected, actual) -> None:
    global _passed, _failed
    if expected == actual:
        _passed += 1
        print(f"  {GREEN}PASS{RESET}  {desc}")
    else:
        _failed += 1
        print(f"  {RED}FAIL{RESET}  {desc} {DIM}(expected {expected!r}, got {actual!r}){RESET}")


def section(name: str) -> None:
    print(f"\n[{name}]")


# --------------------------------------------------------------- server control

def port_free() -> bool:
    return call("GET", "/v1/health").status == 0


def start_server(data_dir: str, log_path: str, *extra: str) -> subprocess.Popen:
    os.makedirs(data_dir, exist_ok=True)
    handle = open(log_path, "wb")
    child = subprocess.Popen(
        [EXE, "--bind", BIND, "--data-dir", data_dir, *extra],
        cwd=SRC, stdout=handle, stderr=subprocess.STDOUT,
    )
    _children.append(child)
    for _ in range(80):
        time.sleep(0.25)
        if child.poll() is not None:
            break
        if call("GET", "/v1/health").status == 200:
            return child
    child.terminate()
    child.wait(timeout=10)
    print(f"server did not come up; {log_path} follows:", file=sys.stderr)
    print(open(log_path, encoding="utf-8", errors="replace").read(), file=sys.stderr)
    sys.exit(1)


def stop_server(child: subprocess.Popen) -> None:
    if child.poll() is None:
        child.terminate()
        try:
            child.wait(timeout=10)
        except subprocess.TimeoutExpired:
            child.kill()
            child.wait(timeout=10)
    time.sleep(0.5)


def operator(data_dir: str, *args: str) -> tuple[int, str]:
    done = subprocess.run([EXE, "--data-dir", data_dir, *args],
                          cwd=SRC, capture_output=True, text=True, errors="replace")
    return done.returncode, (done.stdout or "") + (done.stderr or "")


# ---------------------------------------------------------------------- phases

def phase_multi_tenant(work: str) -> None:
    data = os.path.join(work, "data")
    invite = "e2e-invite"
    server = start_server(data, os.path.join(work, "server1.log"), "--invite", invite)

    print()
    print("=== Phase 1: fresh server - claiming, scoping, rotation, revocation ===")

    # ---- the open surface is exactly two routes ---------------------------
    section("open surface")
    health = call("GET", "/v1/health")
    check("GET /v1/health needs no token", 200, health.status)
    check("  health reports zero tenants", 0, health.json("tenants.registered"))
    check("  health is degraded with nobody signed in", "degraded", health.json("status"))

    for path in ("/v1/auth/status", "/v1/auth/accounts"):
        check(f"GET {path} is refused without a token", 401, call("GET", path).status)
    check("POST /v1/auth/login is refused without a token", 401,
          call("POST", "/v1/auth/login",
               payload={"username": "x", "password": "y"}).status)
    check("POST /v1/auth/2fa is refused without a token", 401,
          call("POST", "/v1/auth/2fa", payload={}).status)
    check("POST /v1/auth/logout is refused without a token", 401,
          call("POST", "/v1/auth/logout", payload={}).status)
    check("POST /v1/rpc is refused without a token", 401,
          call("POST", "/v1/rpc", payload={}).status)
    check("POST /v1/command is refused without a token", 401,
          call("POST", "/v1/command",
               payload={"command": "app__x", "args": {}}).status)
    check("POST /v1/game-log/events is refused without a token", 401,
          call("POST", "/v1/game-log/events", payload={}).status)

    # The tenant middleware sits in front of the whole protected router, so an
    # unauthenticated caller gets 401 for a wrong method and for a path that does
    # not exist at all -- it cannot enumerate routes. That is desirable, so it is
    # asserted as-is; the checks below then prove routing itself is intact by
    # repeating them with a credential (once one exists).
    check("an unauthenticated caller cannot probe for routes", 401,
          call("GET", "/v1/nope").status)
    check("  ...nor for methods", 401, call("GET", "/v1/tenants/token").status)
    check("  ...but the open surface is still open", 200, call("GET", "/v1/health").status)

    # ---- claiming ---------------------------------------------------------
    section("claiming a tenant")
    blank = call("POST", "/v1/tenants", payload={"label": "   "})
    check("a blank label is rejected", 400, blank.status)
    check("  ...and says why", "invalidLabel", blank.json("kind"))

    first = call("POST", "/v1/tenants", payload={"label": "alice"})
    check("the first claim needs no invitation", 200, first.status)
    token_a = first.json("credential.token") if first.json("status") == "claimed" else first.json("token")
    check("  ...and returns a credential", True, bool(token_a))
    check("  ...with a tenant id", True, bool(first.json("tenantId") or first.json("credential.tenantId")))
    tenant_a = first.json("tenantId") or first.json("credential.tenantId")

    check("a later claim without a code is refused", 403,
          call("POST", "/v1/tenants", payload={"label": "bob"}).status)
    check("  ...as admissionClosed", "admissionClosed",
          call("POST", "/v1/tenants", payload={"label": "bob"}).json("kind"))
    check("a wrong invitation code is refused", 403,
          call("POST", "/v1/tenants",
               payload={"label": "bob", "inviteCode": "wrong"}).status)

    second = call("POST", "/v1/tenants",
                  payload={"label": "bob", "inviteCode": invite})
    check("a correct invitation code is admitted", 200, second.status)
    token_b = second.json("token") or second.json("credential.token")
    tenant_b = second.json("tenantId") or second.json("credential.tenantId")

    dup = call("POST", "/v1/tenants",
               payload={"label": "alice", "inviteCode": invite})
    check("a duplicate label is refused", 409, dup.status)
    check("  ...as duplicateLabel", "duplicateLabel", dup.json("kind"))

    check("the two tenants got different credentials", True, token_a != token_b)
    check("the two tenants got different ids", True, tenant_a != tenant_b)

    after = call("GET", "/v1/health")
    check("health now reports two tenants", 2, after.json("tenants.registered"))

    # The invariant that has to hold at every instant, because a client uses
    # `runtime.error` to decide between "wait" and "something is wrong". It was
    # violated twice: once by counting a never-started tenant as running (which
    # answered `ok` for a server that had brought nothing up), and once by
    # leaving the reason empty.
    live, running = after.json("tenants.live"), after.json("tenants.running")
    check("status is ok exactly when every loaded tenant is up",
          "ok" if live and running == live else "degraded", after.json("status"))
    check("a degraded health always carries a reason", True,
          after.json("runtime.started") or bool(after.json("runtime.error")))

    # ---- one credential, one tenant ---------------------------------------
    section("per-tenant credentials")
    check("alice's token is accepted", 200, call("GET", "/v1/auth/accounts", token_a).status)
    check("bob's token is accepted", 200, call("GET", "/v1/auth/status", token_b).status)
    check("a fabricated token is rejected", 401,
          call("GET", "/v1/auth/accounts", "not-a-real-token").status)
    check("a missing token is rejected", 401, call("GET", "/v1/auth/accounts").status)
    check("a bare token with no scheme is rejected", 401,
          call("GET", "/v1/auth/accounts", raw_auth=token_a).status)
    check("a scheme with no token is rejected", 401,
          call("GET", "/v1/auth/accounts", raw_auth="Bearer").status)
    check("a non-Bearer scheme is rejected", 401,
          call("GET", "/v1/auth/accounts", raw_auth=f"Basic {token_a}").status)
    check("the scheme is matched case-insensitively", 200,
          call("GET", "/v1/auth/accounts", raw_auth=f"bearer {token_a}").status)
    check("the event stream rejects a bad token", 401, call("GET", "/v1/stream", "nope").status)
    check("the tenant's data directory is its own", True,
          os.path.isdir(os.path.join(data, "tenants", tenant_a)))

    # Now that a credential exists, routing can be checked properly: an
    # authenticated caller must still get 404 and 405 where those are correct.
    check("an authenticated caller gets 404 for an unknown route", 404,
          call("GET", "/v1/nope", token_a).status)
    check("  ...and 405 for a wrong method on a real route", 405,
          call("GET", "/v1/tenants/token", token_a).status)

    # ---- forwarded commands: the data plane the thin clients use ----------
    section("forwarded commands")
    h = call("GET", "/v1/health")
    check("the capability list advertises quick search", True,
          "app__quick_search_query" in (h.json("commands.supported") or []))
    # 502 (not 501) is the contract here: 501 = "not migrated, run it locally",
    # 502 = "recognised but failed". A degraded tenant has no VRChat session,
    # so the scope gate is what must refuse -- with a readable reason.
    qs = call("POST", "/v1/command", token_a, payload={
        "command": "app__quick_search_query",
        "args": {"input": {"query": "test"}},
    })
    check("quick search answers 502 for a signed-out tenant (never 501)", 502, qs.status)
    check("  ...and the reason is the scope gate, not a crash", True,
          "requires an authenticated session" in qs.body)
    malformed = call("POST", "/v1/command", token_a, payload={
        "command": "app__quick_search_query",
        "args": {},
    })
    check("quick search without input reports the argument, not a 404", 502,
          malformed.status)
    check("  ...naming what is missing", True,
          "missing" in malformed.body and "input" in malformed.body)

    # ---- rotation revokes the old credential ------------------------------
    section("rotation")
    rotated = call("POST", "/v1/tenants/token", token_a, payload={})
    check("alice can rotate her own credential", 200, rotated.status)
    token_a2 = rotated.json("token")
    check("  ...and gets a different one", True, bool(token_a2) and token_a2 != token_a)
    check("the rotated-away credential stops working", 401,
          call("GET", "/v1/auth/accounts", token_a).status)
    check("the new credential works", 200, call("GET", "/v1/auth/accounts", token_a2).status)
    check("rotating alice did not disturb bob", 200,
          call("GET", "/v1/auth/accounts", token_b).status)
    check("rotation keeps the tenant id", tenant_a, rotated.json("tenantId"))

    # ---- the registry file holds no recoverable secret --------------------
    section("registry file")
    registry = open(os.path.join(data, "tenants.json"), encoding="utf-8").read()
    check("it does not contain alice's credential", False, token_a2 in registry)
    check("it does not contain bob's credential", False, token_b in registry)
    check("  ...one digest per tenant", 2, registry.count("tokenSha256"))
    check("each tenant got its own data directory", 2,
          len(os.listdir(os.path.join(data, "tenants"))))

    # ---- operator commands: the point is they work on a live server -------
    section("operator commands (against a running server)")
    code, listing = operator(data, "--list-tenants")
    check("--list-tenants succeeds while the server is running", 0, code)
    check("  ...and names alice", True, "alice" in listing)
    check("  ...and names bob", True, "bob" in listing)

    code, out = operator(data, "--list-tenants", "--revoke-tenant", tenant_b)
    check("asking to list and revoke at once is refused", 2, code)
    check("  ...with an explanation", True,
          "only one operator command" in out)

    code, _ = operator(data, "--revoke-tenant", tenant_b)
    check("--revoke-tenant succeeds", 0, code)
    _, listing2 = operator(data, "--list-tenants")
    check("  ...removes bob from the registry", False, "bob" in listing2)
    check("  ...leaves alice alone", True, "alice" in listing2)
    check("  ...but keeps bob's data on disk", True,
          os.path.isdir(os.path.join(data, "tenants", tenant_b)))

    # A revocation takes effect on the next start, because the running process
    # still holds that tenant in memory. That is documented behaviour, so it is
    # asserted rather than assumed.
    check("a revoked tenant keeps working until restart", 200,
          call("GET", "/v1/auth/accounts", token_b).status)

    stop_server(server)
    server = start_server(data, os.path.join(work, "server2.log"), "--invite", invite)
    check("after restart the revoked tenant is gone from health", 1,
          call("GET", "/v1/health").json("tenants.registered"))
    boot = call("GET", "/v1/health")
    boot_live, boot_running = boot.json("tenants.live"), boot.json("tenants.running")
    check("a freshly booted server is not reported as ok while tenants start",
          "ok" if boot_live and boot_running == boot_live else "degraded", boot.json("status"))
    check("  ...and says why when it is not ok", True,
          boot.json("runtime.started") or bool(boot.json("runtime.error")))
    check("  ...and its credential no longer works", 401,
          call("GET", "/v1/auth/accounts", token_b).status)
    check("  ...while the surviving tenant still works", 200,
          call("GET", "/v1/auth/accounts", token_a2).status)

    # ---- a fresh server must not invent a tenant --------------------------
    section("restart without the invitation code")
    stop_server(server)
    server = start_server(data, os.path.join(work, "server3.log"))
    check("an existing registry needs no invitation to load", 1,
          call("GET", "/v1/health").json("tenants.registered"))
    check("existing credentials still work", 200,
          call("GET", "/v1/auth/accounts", token_a2).status)
    stop_server(server)


def phase_legacy_adoption(work: str) -> None:
    print()
    print("=== Phase 2: upgrading a deployment that predates tenants ===")
    data = os.path.join(work, "legacy")
    os.makedirs(data, exist_ok=True)
    # Any of these names is what the old single-tenant server left behind.
    with open(os.path.join(data, "vrcx-0.db"), "wb") as handle:
        handle.write(b"not really a database")

    server = start_server(data, os.path.join(work, "legacy.log"), "--invite", "e2e-invite")
    health = call("GET", "/v1/health")
    check("the existing profile is adopted as a tenant", 1, health.json("tenants.registered"))
    check("  ...and is reported as degraded, not dead", "degraded", health.json("status"))

    _, listing = operator(data, "--list-tenants")
    check("  ...labelled 'default'", True, "label    : default" in listing)
    data_dir_line = next((line for line in listing.splitlines() if "data dir" in line), "")
    check("  ...still pointing at the original directory", True,
          os.path.basename(data) in data_dir_line)
    check("  ...and not relocated under tenants/", False,
          os.path.join("tenants") in data_dir_line.replace("/", os.sep))
    check("the old database is untouched", True,
          os.path.getsize(os.path.join(data, "vrcx-0.db")) == 21)
    stop_server(server)


def main() -> int:
    if not os.path.exists(EXE):
        print(f"missing {EXE}", file=sys.stderr)
        print("build it first: cd src && cargo build -p vrcx-0-remote-server", file=sys.stderr)
        return 1
    if not port_free():
        print(f"something is already listening on {BASE} -- stop it first", file=sys.stderr)
        return 1

    work = tempfile.mkdtemp(prefix="vrcx-e2e-")
    print(f"{DIM}work dir: {work}{RESET}")
    try:
        phase_multi_tenant(work)
        phase_legacy_adoption(work)
    finally:
        for child in _children:
            stop_server(child)
        shutil.rmtree(work, ignore_errors=True)

    print()
    if _failed == 0:
        print(f"=== {GREEN}{_passed} passed, 0 failed{RESET} ===")
    else:
        print(f"=== {_passed} passed, {RED}{_failed} failed{RESET} ===")
    return 1 if _failed else 0


if __name__ == "__main__":
    sys.exit(main())
