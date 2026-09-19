//! One isolated runtime per user.
//!
//! The server used to hold exactly one signed-in VRChat session and hand the
//! same bearer token to every client that signed in. That made "the token" a
//! server-wide secret rather than a per-user credential, so any user could act
//! as any other - transport encryption alone cannot fix that, because the
//! problem is on the wrong side of the connection.
//!
//! A *tenant* is the unit that fixes it. Each one owns:
//!
//!   * its own data directory, and therefore its own SQLite database;
//!   * its own `RuntimeHostState`, and therefore its own VRChat session;
//!   * its own event bus, so `/v1/stream` never leaks another user's events;
//!   * its own bearer token, which can be rotated or revoked on its own.
//!
//! This is possible without touching the composition layer because
//! `RuntimeHostState` keeps no process-global state at all - every field is an
//! instance field, and its profile lock is taken per data directory.
//!
//! Tokens are stored as SHA-256 digests. The previous design kept the single
//! server token in the database XOR-obfuscated, which is recoverable by anyone
//! who reads the file; a digest is not.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::{Arc, RwLock};
use std::time::{SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use vrcx_0_composition::{RuntimeHostOptions, RuntimeHostProfile, RuntimeHostState};
use vrcx_0_local_server::{generate_token, tokens_match};
use vrcx_0_platform::app_paths::{AppDataDirResolution, AppDataDirSource};

use crate::auth::AuthCoordinator;
use crate::events::{BroadcastEventSink, ConsoleEchoSink, EventHub};
use crate::relay::LogRelay;
use crate::rpc::RpcContext;
use crate::runtime_status::RuntimeStatus;
use crate::runtime_task::TokioRuntimeTaskExecutor;

/// Name of the registry file, relative to the server's data root.
pub const REGISTRY_FILE: &str = "tenants.json";
/// Subdirectory of the data root that holds one directory per tenant.
pub const TENANTS_DIR: &str = "tenants";
/// Bumped if the on-disk shape ever changes incompatibly.
const REGISTRY_VERSION: u32 = 1;

#[derive(Debug)]
pub enum TenantError {
    Io(String),
    Parse(String),
    Runtime(String),
    NotFound(String),
    /// A tenant already uses this label or id.
    Duplicate(String),
    /// The label was empty once trimmed.
    InvalidLabel,
    /// The supplied invite code did not match, or admission is closed.
    AdmissionDenied,
}

impl std::fmt::Display for TenantError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Io(message) => write!(f, "io error: {message}"),
            Self::Parse(message) => write!(f, "registry file is unreadable: {message}"),
            Self::Runtime(message) => write!(f, "runtime could not be created: {message}"),
            Self::NotFound(id) => write!(f, "no tenant with id {id}"),
            Self::Duplicate(what) => write!(f, "a tenant already uses {what}"),
            Self::InvalidLabel => write!(f, "a tenant label cannot be empty"),
            Self::AdmissionDenied => write!(f, "admission denied"),
        }
    }
}

impl std::error::Error for TenantError {}

fn io_error(error: std::io::Error) -> TenantError {
    TenantError::Io(error.to_string())
}

fn unix_now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

/// A tenant as persisted in `tenants.json`. Deliberately holds no secret: the
/// token is only ever stored as a digest, and the plaintext is shown to the
/// operator exactly once, when the tenant is created.
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TenantRecord {
    pub id: String,
    pub label: String,
    /// Hex SHA-256 of the bearer token.
    pub token_sha256: String,
    /// Unix seconds.
    pub created_at: u64,
    /// Set when an operator suspends a tenant; login and commands are refused
    /// while true, without deleting anything.
    #[serde(default)]
    pub disabled: bool,
    /// Absolute path this tenant's profile lives in, when it is not the default
    /// `tenants/<id>`.
    ///
    /// Exists for exactly one reason: adopting a server that was deployed
    /// before tenants did. That deployment kept its whole profile in the data
    /// root, and moving a live SQLite database plus its WAL into a subdirectory
    /// is the kind of copy that fails halfway and takes the signed-in session
    /// with it. Recording the old location instead keeps the session, the
    /// history and the credentials exactly where they are.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub data_dir: Option<String>,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RegistryFile {
    version: u32,
    #[serde(default)]
    tenants: Vec<TenantRecord>,
}

/// Aggregate tenant state, flattened into `/v1/health`.
#[derive(Clone, Debug, Default)]
pub struct TenantHealth {
    /// Rows in the registry file, including any that failed to load.
    pub registered: usize,
    /// Tenants with a live runtime in this process.
    pub live: usize,
    /// Live tenants whose VRChat backend came up.
    pub running: usize,
    /// Live tenants whose start attempt has not finished yet.
    ///
    /// A tenant is exactly one of running / starting / failed, and this is the
    /// one that has to be named separately: without it, "nothing has been
    /// attempted" and "everything is up" look identical from the outside.
    pub starting: usize,
    /// The first reason a tenant is degraded, prefixed with its label.
    pub first_failure: Option<String>,
}

/// Everything a request needs once its tenant has been resolved.
///
/// Mirrors what `ApiState` used to hold for the whole process, which is exactly
/// the point: these were server-wide singletons and are now per tenant.
pub struct TenantRuntime {
    /// Behind a lock only because the token digest has to be replaceable at
    /// runtime: rotating a token must take effect on the live tenant without
    /// rebuilding a runtime that currently holds a signed-in session.
    record: RwLock<TenantRecord>,
    pub data_dir: PathBuf,
    pub state: Arc<RuntimeHostState>,
    pub rpc: Arc<RpcContext>,
    pub events: EventHub,
    pub status: RuntimeStatus,
    pub relay: LogRelay,
    pub auth: AuthCoordinator,
}

impl TenantRuntime {
    pub fn id(&self) -> String {
        self.read_record().id.clone()
    }

    pub fn label(&self) -> String {
        self.read_record().label.clone()
    }

    pub fn record(&self) -> TenantRecord {
        self.read_record().clone()
    }

    fn read_record(&self) -> std::sync::RwLockReadGuard<'_, TenantRecord> {
        // A poisoned lock means some other request panicked. Carrying on with a
        // stale record beats refusing every request for the rest of the
        // process's life, which is the same trade `AuthCoordinator` makes.
        self.record
            .read()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    /// Swaps in a new token digest after a rotation.
    fn set_token_digest(&self, digest: String) {
        let mut record = self
            .record
            .write()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        record.token_sha256 = digest;
    }

    fn token_digest(&self) -> String {
        self.read_record().token_sha256.clone()
    }

    /// Builds the runtime without starting the VRChat backend. Kept synchronous
    /// so a caller can build every tenant first and start them concurrently.
    pub fn build(
        record: TenantRecord,
        data_root: &Path,
        app_version: &str,
    ) -> Result<Self, TenantError> {
        let data_dir = record
            .data_dir
            .as_deref()
            .map(PathBuf::from)
            .map(|path| {
                if path.is_absolute() {
                    path
                } else {
                    data_root.join(path)
                }
            })
            .unwrap_or_else(|| tenant_data_dir(data_root, &record.id));
        std::fs::create_dir_all(&data_dir).map_err(io_error)?;

        let state = Arc::new(
            RuntimeHostState::new(RuntimeHostOptions {
                // Matches the desktop shell's origin so any code path that
                // compares it behaves identically in headless mode.
                realtime_origin: "http://localhost:9000".into(),
                launched_from_autostart: false,
                app_data_dir: AppDataDirResolution {
                    current_dir: data_dir.clone(),
                    default_dir: data_dir.clone(),
                    persisted_dir: None,
                    cli_dir: Some(data_dir.clone()),
                    source: AppDataDirSource::Cli,
                },
                app_version: app_version.to_string(),
                profile: RuntimeHostProfile::HeadlessData,
                database_maintenance_cache_dir: None,
            })
            .map_err(|error| TenantError::Runtime(error.to_string()))?,
        );

        let events = EventHub::new();
        // Each tenant gets its own event bus; wiring the sink here is what
        // keeps one user's realtime traffic out of another's stream.
        // The console echo sits in front of it so an operator tailing the
        // server still sees sign-in progress, now labelled with whose it is.
        state.set_event_sink(ConsoleEchoSink::new(
            record.label.clone(),
            BroadcastEventSink::new(events.clone()),
        ));
        state.set_task_executor(TokioRuntimeTaskExecutor);

        let rpc = Arc::new(RpcContext::new(Arc::clone(&state), app_version));
        let relay = LogRelay::new(&data_dir);
        let status = RuntimeStatus::new();

        Ok(Self {
            record: RwLock::new(record),
            data_dir,
            state,
            rpc,
            events,
            status,
            relay,
            auth: AuthCoordinator::new(),
        })
    }

    /// Starts the backend runtime. A tenant that cannot start is recorded as
    /// degraded rather than taking the process down, matching the single-tenant
    /// behaviour: `/v1/health` is how an operator finds out why.
    pub async fn start(&self, force_login: bool) -> Result<(), String> {
        // The directory is the thing an operator has to find when a user asks
        // where their data went, so it is worth one line in the log rather than
        // making them reconstruct it from the tenant id.
        tracing::info!(
            tenant = %self.id(),
            label = %self.label(),
            data_dir = %self.data_dir.display(),
            "starting tenant"
        );
        let prompt = force_login.then(|| {
            std::sync::Arc::new(crate::StdinLoginPrompt) as std::sync::Arc<dyn vrcx_0_composition::CliLoginPrompt>
        });
        match self.state.start_headless_backend_runtime(prompt).await {
            Ok(_) => {
                self.status.mark_started();
                Ok(())
            }
            Err(error) => {
                let reason = format!("failed to start the VRChat backend runtime: {error}");
                self.status.mark_failed(reason.clone());
                Err(reason)
            }
        }
    }

    pub fn stop(&self, reason: &str) {
        self.state.stop_backend_runtime(reason);
        self.state.stop_runtime_tasks();
    }
}

/// Owns the registry file and the live runtimes it describes.
///
/// Reads are behind an `RwLock` rather than an `ArcSwap` because tenants are
/// created rarely and looked up on every request; a read lock is cheap and the
/// write path is a once-per-user event.
pub struct TenantRegistry {
    data_root: PathBuf,
    app_version: String,
    /// Required to create any tenant beyond the very first one. `None` means
    /// admission is closed after bootstrap.
    invite_code: Option<String>,
    file: RwLock<RegistryFile>,
    live: RwLock<HashMap<String, Arc<TenantRuntime>>>,
    /// Digest -> tenant id, so an unauthenticated lookup never compares against
    /// every tenant in turn.
    by_token: RwLock<HashMap<String, String>>,
}

impl TenantRegistry {
    /// Loads the registry from disk and builds a runtime for every record.
    ///
    /// A tenant whose runtime cannot be built is logged and skipped rather than
    /// aborting startup: one corrupt profile must not lock every other user out
    /// of the server.
    pub fn load(
        data_root: &Path,
        app_version: &str,
        invite_code: Option<String>,
    ) -> Result<Self, TenantError> {
        std::fs::create_dir_all(data_root).map_err(io_error)?;
        std::fs::create_dir_all(data_root.join(TENANTS_DIR)).map_err(io_error)?;

        let path = data_root.join(REGISTRY_FILE);
        let file = if path.exists() {
            let raw = std::fs::read_to_string(&path).map_err(io_error)?;
            let parsed: RegistryFile =
                serde_json::from_str(&raw).map_err(|e| TenantError::Parse(e.to_string()))?;
            parsed
        } else {
            RegistryFile {
                version: REGISTRY_VERSION,
                tenants: Vec::new(),
            }
        };

        let registry = Self {
            data_root: data_root.to_path_buf(),
            app_version: app_version.to_string(),
            invite_code,
            file: RwLock::new(file),
            live: RwLock::new(HashMap::new()),
            by_token: RwLock::new(HashMap::new()),
        };

        let records: Vec<TenantRecord> = registry.file.read().unwrap().tenants.clone();
        for record in records {
            if record.disabled {
                continue;
            }
            match TenantRuntime::build(record.clone(), data_root, app_version) {
                Ok(runtime) => {
                    registry
                        .by_token
                        .write()
                        .unwrap()
                        .insert(record.token_sha256.clone(), record.id.clone());
                    registry.live.write().unwrap().insert(record.id.clone(), Arc::new(runtime));
                }
                Err(error) => {
                    eprintln!(
                        "tenant {} could not be loaded and is being skipped: {error}",
                        record.id
                    );
                }
            }
        }

        // Starting the backend is async and happens in `start_all`, outside
        // this constructor, so building several tenants never serialises.
        Ok(registry)
    }

    /// Starts every loaded tenant's VRChat backend concurrently.
    ///
    /// Concurrent rather than sequential on purpose: each start is dominated by
    /// network waits, so serialising them would make startup time grow linearly
    /// with the number of users.
    pub async fn start_all(&self, force_login: bool) {
        let runtimes: Vec<Arc<TenantRuntime>> =
            self.live.read().unwrap().values().cloned().collect();
        let mut handles = Vec::with_capacity(runtimes.len());
        for runtime in runtimes {
            let id = runtime.id();
            handles.push(tokio::spawn(async move {
                if let Err(reason) = runtime.start(force_login).await {
                    eprintln!("tenant {id} is degraded: {reason}");
                }
            }));
        }
        for handle in handles {
            let _ = handle.await;
        }
    }

    pub fn is_empty(&self) -> bool {
        self.file.read().unwrap().tenants.is_empty()
    }

    /// Whether the next claim on this server needs no invitation.
    ///
    /// Startup uses this to decide how loudly to warn: a server sitting with no
    /// tenants and no invite code is one that anybody who can reach the port
    /// can claim, which is fine on a LAN and not fine on the internet.
    pub fn is_unclaimed(&self) -> bool {
        self.is_empty()
    }

    /// Aggregate state for `/v1/health`.
    ///
    /// A single process now holds several accounts, so "is the server healthy"
    /// has no single answer: it is healthy when it is serving, and the useful
    /// question is how many of its tenants actually came up.
    pub fn health(&self) -> TenantHealth {
        let live = self.live.read().unwrap();
        let mut running = 0usize;
        let mut starting = 0usize;
        let mut first_failure: Option<String> = None;
        for runtime in live.values() {
            let status = &runtime.status;
            if status.is_running() {
                running += 1;
            } else if status.is_pending() {
                // Not up, and nothing has been attempted yet: still being
                // brought up. Counting these as "running" is what let a server
                // that had started nothing answer `ok`.
                starting += 1;
            } else if let Some(reason) = status.failure() {
                if first_failure.is_none() {
                    first_failure = Some(format!("{}: {reason}", runtime.label()));
                }
            }
        }
        TenantHealth {
            registered: self.file.read().unwrap().tenants.len(),
            live: live.len(),
            running,
            starting,
            first_failure,
        }
    }

    pub fn authenticate(&self, token: &str) -> Option<Arc<TenantRuntime>> {
        if token.is_empty() {
            return None;
        }
        let digest = token_digest(token);
        let id = self.by_token.read().unwrap().get(&digest).cloned()?;
        // Look the digest up first (cheap), then compare in constant time so a
        // timing signal cannot be used to walk the digest space.
        let live = self.live.read().unwrap();
        let runtime = live.get(&id)?;
        if tokens_match(&runtime.token_digest(), &digest) {
            Some(Arc::clone(runtime))
        } else {
            None
        }
    }

    /// Every live tenant, for shutdown and diagnostics.
    pub fn runtimes(&self) -> Vec<Arc<TenantRuntime>> {
        self.live.read().unwrap().values().cloned().collect()
    }

    /// Creates a tenant and returns it together with the plaintext token, which
    /// is the only time that value ever exists outside the client.
    ///
    /// `token` forces a specific bearer token instead of generating one. The
    /// only caller that needs it is bootstrap migration: a server deployed
    /// before tenants existed has clients holding the old server-wide token in
    /// their config, and adopting that token as the first tenant's own keeps
    /// every one of them working across the upgrade without being reconfigured.
    ///
    /// Admission is enforced here rather than by a separate check so there is
    /// no way to reach the create path without passing it: the first tenant on
    /// a fresh server needs no code, so an operator can bring the server up
    /// alone, and every later one must present the code the operator started
    /// with. Without that, anyone who could reach the port could provision an
    /// account on somebody else's server.
    pub fn create(
        &self,
        label: &str,
        invite: Option<&str>,
        token: Option<&str>,
    ) -> Result<(Arc<TenantRuntime>, String), TenantError> {
        // Bootstrap: allow the first tenant without a code, require one after.
        if !self.is_empty() {
            match (self.invite_code.as_deref(), invite) {
                (Some(expected), Some(supplied)) if tokens_match(supplied, expected) => {}
                _ => return Err(TenantError::AdmissionDenied),
            }
        }

        let label = label.trim();
        if label.is_empty() {
            return Err(TenantError::InvalidLabel);
        }
        if self
            .file
            .read()
            .unwrap()
            .tenants
            .iter()
            .any(|existing| existing.label.eq_ignore_ascii_case(label))
        {
            return Err(TenantError::Duplicate(format!("the label {label}")));
        }

        let token = match token.map(str::trim).filter(|value| !value.is_empty()) {
            Some(explicit) => explicit.to_string(),
            None => generate_token().map_err(|e| TenantError::Runtime(e.to_string()))?,
        };
        let digest = token_digest(&token);
        if self.by_token.read().unwrap().contains_key(&digest) {
            return Err(TenantError::Duplicate(
                "that bearer token".to_string(),
            ));
        }

        let record = TenantRecord {
            id: short_id()?,
            label: label.to_string(),
            token_sha256: digest.clone(),
            created_at: unix_now(),
            disabled: false,
            data_dir: None,
        };

        let runtime = Arc::new(TenantRuntime::build(
            record.clone(),
            &self.data_root,
            &self.app_version,
        )?);

        {
            let mut file = self.file.write().unwrap();
            file.tenants.push(record.clone());
            self.persist_locked(&file)?;
        }
        self.by_token
            .write()
            .unwrap()
            .insert(digest, record.id.clone());
        self.live
            .write()
            .unwrap()
            .insert(record.id.clone(), Arc::clone(&runtime));

        Ok((runtime, token))
    }

    /// Adopts a data directory that predates tenants as the first tenant's own.
    ///
    /// Nothing is moved. The old deployment kept its profile directly in the
    /// data root, WAL and all, and relocating a live SQLite database is exactly
    /// the kind of half-finished copy that costs an operator their session.
    /// Recording the existing path in `data_dir` leaves the session, the saved
    /// credentials and the history exactly where they are.
    ///
    /// `data_root` is passed explicitly rather than assumed to be the tenant
    /// directory because the whole point is that this one tenant is a special
    /// case.
    pub fn adopt_legacy(
        &self,
        label: &str,
        data_dir: &Path,
        token: Option<&str>,
    ) -> Result<(Arc<TenantRuntime>, String), TenantError> {
        if !self.is_empty() {
            return Err(TenantError::Duplicate(
                "this server already has a tenant".to_string(),
            ));
        }

        let token = match token.map(str::trim).filter(|value| !value.is_empty()) {
            Some(explicit) => explicit.to_string(),
            None => generate_token().map_err(|e| TenantError::Runtime(e.to_string()))?,
        };
        let record = TenantRecord {
            id: short_id()?,
            label: label.to_string(),
            token_sha256: token_digest(&token),
            created_at: unix_now(),
            disabled: false,
            data_dir: Some(data_dir.to_string_lossy().into_owned()),
        };

        let runtime = Arc::new(TenantRuntime::build(
            record.clone(),
            &self.data_root,
            &self.app_version,
        )?);
        {
            let mut file = self.file.write().unwrap();
            file.tenants.push(record.clone());
            self.persist_locked(&file)?;
        }
        self.by_token
            .write()
            .unwrap()
            .insert(record.token_sha256.clone(), record.id.clone());
        self.live
            .write()
            .unwrap()
            .insert(record.id.clone(), Arc::clone(&runtime));

        Ok((runtime, token))
    }

    /// Issues a fresh bearer token for a tenant and forgets the old digest.
    ///
    /// This is the only way back from a lost token: the registry stores a
    /// digest, so the original cannot be looked up and has to be replaced. Any
    /// client still holding the previous token starts getting 401s, which is
    /// the intended effect rather than a side effect.
    pub fn rotate_token(&self, id: &str) -> Result<String, TenantError> {
        let token = generate_token().map_err(|e| TenantError::Runtime(e.to_string()))?;
        let digest = token_digest(&token);

        let previous = {
            let mut file = self.file.write().unwrap();
            let Some(record) = file.tenants.iter_mut().find(|tenant| tenant.id == id) else {
                return Err(TenantError::NotFound(id.to_string()));
            };
            let previous = std::mem::replace(&mut record.token_sha256, digest.clone());
            self.persist_locked(&file)?;
            previous
        };

        {
            let mut by_token = self.by_token.write().unwrap();
            by_token.remove(&previous);
            by_token.insert(digest.clone(), id.to_string());
        }
        // The live runtime caches its own digest for the constant-time compare,
        // so it has to be brought in step or every request would start failing
        // while the registry file says otherwise.
        if let Some(runtime) = self.live.read().unwrap().get(id) {
            runtime.set_token_digest(digest);
        }
        Ok(token)
    }

    fn persist_locked(&self, file: &RegistryFile) -> Result<(), TenantError> {
        let path = self.data_root.join(REGISTRY_FILE);
        let json = serde_json::to_string_pretty(file)
            .map_err(|e| TenantError::Parse(e.to_string()))?;
        // Write-then-rename so a crash mid-write cannot leave a truncated
        // registry that would lock every user out on the next boot.
        let temporary = path.with_extension("json.tmp");
        std::fs::write(&temporary, json).map_err(io_error)?;
        std::fs::rename(&temporary, &path).map_err(io_error)?;
        Ok(())
    }
}

pub fn tenant_data_dir(data_root: &Path, id: &str) -> PathBuf {
    data_root.join(TENANTS_DIR).join(id)
}

/// Reads the registry without building any runtime.
///
/// The operator commands (`--list-tenants`, `--revoke-tenant`) work on a server
/// that is already running under a supervisor, so they must not open a second
/// copy of every tenant's database and fight the live process for its profile
/// lock. Reading the file is enough for what they do.
pub fn read_records(data_root: &Path) -> Result<Vec<TenantRecord>, TenantError> {
    let path = data_root.join(REGISTRY_FILE);
    if !path.exists() {
        return Ok(Vec::new());
    }
    let raw = std::fs::read_to_string(&path).map_err(io_error)?;
    let file: RegistryFile =
        serde_json::from_str(&raw).map_err(|e| TenantError::Parse(e.to_string()))?;
    Ok(file.tenants)
}

/// Removes a tenant from the registry and returns the record it removed.
///
/// The data directory is deliberately left on disk. Revocation should not
/// silently destroy a user's history, and an operator who is sure can delete the
/// directory afterwards; an operator who is not sure cannot undelete it.
///
/// Note this only edits the file. A server that is already running still holds
/// the tenant in memory and will keep honouring its token until it is
/// restarted, so the revocation takes effect on the next start.
pub fn revoke_tenant(data_root: &Path, id: &str) -> Result<TenantRecord, TenantError> {
    let mut records = read_records(data_root)?;
    let index = records
        .iter()
        .position(|tenant| tenant.id == id)
        .ok_or_else(|| TenantError::NotFound(id.to_string()))?;
    let removed = records.remove(index);

    let file = RegistryFile {
        version: REGISTRY_VERSION,
        tenants: records,
    };
    let path = data_root.join(REGISTRY_FILE);
    let json =
        serde_json::to_string_pretty(&file).map_err(|e| TenantError::Parse(e.to_string()))?;
    let temporary = path.with_extension("json.tmp");
    std::fs::write(&temporary, json).map_err(io_error)?;
    std::fs::rename(&temporary, &path).map_err(io_error)?;
    Ok(removed)
}

/// Hex SHA-256. The registry never stores the token itself.
fn token_digest(token: &str) -> String {
    let digest = Sha256::digest(token.as_bytes());
    let mut out = String::with_capacity(digest.len() * 2);
    for byte in digest {
        out.push_str(&format!("{byte:02x}"));
    }
    out
}

/// A short, filesystem-safe identifier. Derived from the same CSPRNG as the
/// token rather than a counter, so tenant directories do not reveal how many
/// users a server has or in what order they joined.
fn short_id() -> Result<String, TenantError> {
    let raw = generate_token().map_err(|e| TenantError::Runtime(e.to_string()))?;
    Ok(raw.chars().take(16).collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn digest_is_stable_and_not_the_token() {
        let digest = token_digest("abc");
        assert_eq!(digest.len(), 64);
        assert_eq!(digest, token_digest("abc"));
        assert_ne!(digest, token_digest("abd"));
        assert!(!digest.contains("abc"));
    }

    #[test]
    fn short_id_is_filesystem_safe() {
        let id = short_id().expect("token generation");
        assert_eq!(id.len(), 16);
        assert!(id
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_'));
    }
}
