mod api;
mod auth;
mod commands;
mod deps;
mod events;
mod invites;
mod relay;
mod rpc;
mod runtime_status;
mod runtime_task;
mod settings;
mod tenants;

use std::path::Path;
use std::process::ExitCode;
use std::sync::Arc;

use serde_json::Value;
use tokio::net::TcpListener;
use tracing_subscriber::filter::LevelFilter;
use tracing_subscriber::layer::SubscriberExt;
use tracing_subscriber::util::SubscriberInitExt;
use tracing_subscriber::Layer;
use vrcx_0_application_core::{
    recommended_tokio_max_blocking_threads, recommended_tokio_worker_threads,
};
use vrcx_0_composition::{CliLoginPrompt, CliTwoFactorChoice};
use vrcx_0_platform::app_paths::resolve_app_data_dir;
use vrcx_0_platform::error_log::{default_app_data_dir, ErrorLogWriter};

use crate::api::ApiState;
use crate::settings::{OperatorCommand, ServerOptions, HELP};
use crate::tenants::TenantRegistry;

const ERROR_LOG_FILE: &str = "vrcx-0-remote-server.err.log";

/// Label given to a profile that predates tenants.
const ADOPTED_TENANT_LABEL: &str = "default";

fn main() -> ExitCode {
    build_adaptive_tokio_runtime().block_on(async_main())
}

fn build_adaptive_tokio_runtime() -> tokio::runtime::Runtime {
    tokio::runtime::Builder::new_multi_thread()
        .worker_threads(recommended_tokio_worker_threads())
        .max_blocking_threads(recommended_tokio_max_blocking_threads())
        .thread_name("vrcx-0-remote-server")
        .enable_all()
        .build()
        .expect("failed to build the server async runtime")
}

async fn async_main() -> ExitCode {
    init_tls_crypto_provider();

    let args: Vec<String> = std::env::args().collect();
    if args.iter().any(|arg| arg == "--help" || arg == "-h") {
        print!("{HELP}");
        return ExitCode::SUCCESS;
    }
    let options = ServerOptions::from_args(&args);

    // The data directory is now a *root*: it holds the tenant registry and one
    // directory per tenant, rather than one profile.
    let data_root = match resolve_app_data_dir() {
        Ok(resolution) => resolution.current_dir,
        Err(error) => {
            // Fall back to the default location so the failure is still logged
            // somewhere an operator can find it.
            init_tracing(default_app_data_dir().as_deref());
            eprintln!("remote server data directory setup failed: {error}");
            return ExitCode::from(1);
        }
    };
    init_tracing(Some(data_root.as_path()));

    // Operator commands exist to be run against a server that is already
    // running under a supervisor, so they touch the registry file and nothing
    // else: binding the port would fight the live process, and building every
    // tenant would make it fight for each profile lock.
    if let Some(command) = options.operator_command() {
        return run_operator_command(command, &data_root, options.invite_code.as_deref());
    }

    let app_version = product_app_version();

    let registry = match TenantRegistry::load(
        &data_root,
        &app_version,
        options.invite_code.clone(),
    ) {
        Ok(registry) => registry,
        Err(error) => {
            report_error(
                &data_root,
                "server:registry",
                format!("the tenant registry could not be loaded: {error}"),
            );
            return ExitCode::from(1);
        }
    };

    // A server deployed before tenants existed kept its whole profile directly
    // in the data root. Adopting it, rather than converting it, is what keeps
    // that deployment's session, history and saved credentials alive across the
    // upgrade: nothing is copied, so nothing can be copied halfway.
    if registry.is_unclaimed() && looks_like_pre_tenant_profile(&data_root) {
        match registry.adopt_legacy(
            ADOPTED_TENANT_LABEL,
            &data_root,
            options.token.as_deref(),
        ) {
            Ok((runtime, token)) => {
                let record = runtime.record();
                println!(
                    "Adopted the existing profile in {} as tenant {} ({}).",
                    data_root.display(),
                    record.id,
                    record.label
                );
                println!("  Its session, history and saved credentials are untouched.");
                println!("  bearer token   : {token}");
            }
            Err(error) => {
                report_error(
                    &data_root,
                    "server:adopt",
                    format!("the existing profile could not be adopted: {error}"),
                );
                return ExitCode::from(1);
            }
        }
    }

    // Each tenant keeps serving when its own account is signed out. That is the
    // expected state after a reboot with an expired cookie, and exiting here
    // would make "this user needs to sign in" indistinguishable from "the
    // process died" - and a supervisor would give up restarting it for good. So
    // failures are recorded per tenant and reported from `/v1/health`.
    let listener = match TcpListener::bind(&options.bind_address).await {
        Ok(listener) => listener,
        Err(error) => {
            report_error(
                &data_root,
                "server:bind",
                format!("failed to bind {}: {error}", options.bind_address),
            );
            stop_all(&registry, "bind-failed");
            return ExitCode::from(1);
        }
    };

    let api_state = ApiState {
        tenants: Arc::new(registry),
        app_version: Arc::from(app_version.as_str()),
    };

    announce(&options, &data_root, &api_state.tenants);

    // Starting the tenants is deliberately neither awaited nor allowed to delay
    // the bind above. Restoring a VRChat session is a network operation, and on
    // a host that cannot reach the API every attempt blocks until it times out.
    // Awaiting that first would hold the port closed for as long as the slowest
    // tenant takes, and during that window an outside observer cannot tell this
    // process from a dead one -- which is exactly the confusion `/v1/health`
    // exists to remove, and enough for a supervisor to start killing a server
    // that is fine. Binding first means health always answers and names the
    // tenants that are still settling.
    //
    // This also makes start-up agree with a fresh claim, which has always
    // started its tenant in the background rather than blocking the response.
    let startup = Arc::clone(&api_state.tenants);
    let force_login = options.force_login;
    tokio::spawn(async move {
        startup.start_all(force_login).await;
        let health = startup.health();
        if health.running < health.live {
            println!(
                "{} of {} tenants are degraded; /v1/health has the reason for each.",
                health.live - health.running,
                health.live
            );
        }
    });

    let registry = Arc::clone(&api_state.tenants);
    let server = axum::serve(listener, api::router(api_state))
        .with_graceful_shutdown(shutdown_signal());
    if let Err(error) = server.await {
        report_error(
            &data_root,
            "server:serve",
            format!("http server stopped with an error: {error}"),
        );
        stop_all(&registry, "serve-error");
        return ExitCode::from(1);
    }

    stop_all(&registry, "shutdown");
    ExitCode::SUCCESS
}

/// Whether the data root holds a profile written before tenants existed.
///
/// The old single-tenant server used the data root itself as its profile, so
/// any of these names appearing there means this upgrade has something to
/// preserve. A directory that has none of them is either fresh or already
/// converted, and adopting an empty directory would just create a tenant that
/// nobody asked for.
fn looks_like_pre_tenant_profile(data_root: &Path) -> bool {
    let Ok(entries) = std::fs::read_dir(data_root) else {
        return false;
    };
    entries.flatten().any(|entry| {
        let name = entry.file_name();
        let name = name.to_string_lossy();
        name.ends_with(".db") || name == "remote-relay" || name == "storage"
    })
}

fn stop_all(registry: &TenantRegistry, reason: &str) {
    for runtime in registry.runtimes() {
        runtime.stop(reason);
    }
}

/// Runs one of the operator commands (list/revoke tenants, invite codes) and
/// exits. `legacy_invite` is the server-wide `--invite` value, if the
/// invocation was given one; generated codes avoid colliding with it.
fn run_operator_command(
    command: OperatorCommand,
    data_root: &Path,
    legacy_invite: Option<&str>,
) -> ExitCode {
    match command {
        OperatorCommand::Ambiguous => {
            eprintln!(
                "only one operator command can run per invocation: --list-tenants, \
                 --revoke-tenant, --list-invites, --generate-invite, --revoke-invite"
            );
            ExitCode::from(2)
        }
        OperatorCommand::ListTenants => match tenants::read_records(data_root) {
            Ok(records) if records.is_empty() => {
                println!("No tenant has claimed this server yet.");
                println!("  data root: {}", data_root.display());
                ExitCode::SUCCESS
            }
            Ok(records) => {
                println!(
                    "{} tenant(s) in {}",
                    records.len(),
                    data_root.join(tenants::REGISTRY_FILE).display()
                );
                for record in records {
                    println!();
                    println!("  id       : {}", record.id);
                    println!("  label    : {}", record.label);
                    println!("  created  : {} (unix seconds)", record.created_at);
                    println!("  disabled : {}", record.disabled);
                    println!("  data dir : {}", tenant_dir(data_root, &record));
                }
                ExitCode::SUCCESS
            }
            Err(error) => {
                report_error(
                    data_root,
                    "server:tenants",
                    format!("the tenant registry could not be read: {error}"),
                );
                ExitCode::from(1)
            }
        },
        OperatorCommand::RevokeTenant(id) => {
            if id.is_empty() {
                eprintln!("--revoke-tenant needs a tenant id; run --list-tenants to find one");
                return ExitCode::from(2);
            }
            match tenants::revoke_tenant(data_root, &id) {
                Ok(removed) => {
                    println!("Revoked tenant {} ({}).", removed.id, removed.label);
                    println!("  its data is still at {}", tenant_dir(data_root, &removed));
                    println!("  restart the server for the revocation to take effect");
                    ExitCode::SUCCESS
                }
                Err(error) => {
                    report_error(
                        data_root,
                        "server:tenants",
                        format!("tenant {id} could not be revoked: {error}"),
                    );
                    ExitCode::from(1)
                }
            }
        }
        OperatorCommand::ListInvites => match invites::read_invites(data_root) {
            Ok(invites) if invites.is_empty() => {
                println!("No invite codes. (`--generate-invite` creates one.)");
                println!("  the legacy --invite code, if configured, is not stored here");
                ExitCode::SUCCESS
            }
            Ok(invites) => {
                let records = tenants::read_records(data_root).unwrap_or_default();
                println!(
                    "{} invite code(s) in {}",
                    invites.len(),
                    data_root.join(invites::INVITES_FILE).display()
                );
                for invite in invites {
                    println!();
                    println!("  code    : {}", invite.code);
                    match invite.kind {
                        invites::InviteKind::New => {
                            println!("  admits  : a new account");
                        }
                        invites::InviteKind::Join => {
                            let label = invite
                                .tenant_id
                                .as_deref()
                                .and_then(|id| records.iter().find(|record| record.id == id))
                                .map(|record| record.label.clone());
                            println!(
                                "  admits  : joins account {} ({})",
                                label.unwrap_or_else(|| "MISSING".into()),
                                invite.tenant_id.as_deref().unwrap_or("?")
                            );
                        }
                    }
                    match invite.remaining() {
                        Some(0) => println!(
                            "  uses    : {} of {} (used up)",
                            invite.used,
                            invite.max_uses.unwrap_or(0)
                        ),
                        Some(left) => println!(
                            "  uses    : {} used, {} left of {}",
                            invite.used,
                            left,
                            invite.max_uses.unwrap_or(0)
                        ),
                        None => println!("  uses    : {} used, unlimited", invite.used),
                    }
                    if invite.disabled {
                        println!("  state   : revoked");
                    }
                    if let Some(note) = invite.note.as_deref() {
                        println!("  note    : {note}");
                    }
                }
                ExitCode::SUCCESS
            }
            Err(error) => {
                report_error(
                    data_root,
                    "server:invites",
                    format!("the invite store could not be read: {error}"),
                );
                ExitCode::from(1)
            }
        },
        OperatorCommand::GenerateInvite { join, note, uses } => {
            let max_uses = match uses.as_deref().map(str::trim).filter(|value| !value.is_empty()) {
                None => Some(1),
                Some("unlimited") => None,
                Some(value) => match value.parse::<u32>() {
                    Ok(count) if count > 0 => Some(count),
                    _ => {
                        eprintln!("--uses expects a positive number or `unlimited`");
                        return ExitCode::from(2);
                    }
                },
            };
            let records = match tenants::read_records(data_root) {
                Ok(records) => records,
                Err(error) => {
                    report_error(
                        data_root,
                        "server:invites",
                        format!("the tenant registry could not be read: {error}"),
                    );
                    return ExitCode::from(1);
                }
            };
            let (kind, tenant_id) = match join.as_deref().map(str::trim).filter(|value| !value.is_empty()) {
                None => (invites::InviteKind::New, None),
                Some(target) => {
                    let Some(record) = records.iter().find(|record| {
                        record.id == target || record.label.eq_ignore_ascii_case(target)
                    }) else {
                        eprintln!("no account matches `{target}`; run --list-tenants to see them");
                        return ExitCode::from(2);
                    };
                    (invites::InviteKind::Join, Some(record.id.clone()))
                }
            };
            let created = invites::create_invite(
                data_root,
                kind,
                tenant_id.clone(),
                note,
                max_uses,
                legacy_invite,
            );
            match created {
                Ok(record) => {
                    println!("Invite code created.");
                    println!("  code    : {}", record.code);
                    match record.kind {
                        invites::InviteKind::New => {
                            println!("  admits  : a new account (own data and sign-in)");
                        }
                        invites::InviteKind::Join => {
                            let label = records
                                .iter()
                                .find(|tenant| Some(&tenant.id) == tenant_id.as_ref())
                                .map(|tenant| tenant.label.clone())
                                .unwrap_or_default();
                            println!(
                                "  admits  : joins the existing account \"{label}\" (same data and session)"
                            );
                            println!("  warning : whoever claims it gets this account's data;");
                            println!("            hand it out only to people you trust");
                        }
                    }
                    println!(
                        "  uses    : {}",
                        record
                            .max_uses
                            .map(|max| format!("{max}"))
                            .unwrap_or_else(|| "unlimited".into())
                    );
                    if let Some(note) = record.note.as_deref() {
                        println!("  note    : {note}");
                    }
                    println!("  stored  : {}", data_root.join(invites::INVITES_FILE).display());
                    println!();
                    println!("A client claims with POST /v1/tenants presenting this code;");
                    println!("the running server reads the file on every claim — no restart needed.");
                    ExitCode::SUCCESS
                }
                Err(error) => {
                    report_error(
                        data_root,
                        "server:invites",
                        format!("the invite code could not be created: {error}"),
                    );
                    ExitCode::from(1)
                }
            }
        }
        OperatorCommand::RevokeInvite(code) => {
            if code.is_empty() {
                eprintln!("--revoke-invite needs a code; run --list-invites to see them");
                return ExitCode::from(2);
            }
            match invites::revoke_invite(data_root, &code) {
                Ok(removed) => {
                    println!("Revoked invite {}.", removed.code);
                    println!("  a claim presenting it from now on is refused");
                    ExitCode::SUCCESS
                }
                Err(error) => {
                    report_error(
                        data_root,
                        "server:invites",
                        format!("invite {code} could not be revoked: {error}"),
                    );
                    ExitCode::from(1)
                }
            }
        }
    }
}

fn tenant_dir(data_root: &Path, record: &tenants::TenantRecord) -> String {
    record
        .data_dir
        .clone()
        .unwrap_or_else(|| tenants::tenant_data_dir(data_root, &record.id).display().to_string())
}

fn announce(options: &ServerOptions, data_root: &Path, registry: &TenantRegistry) {
    let health = registry.health();
    println!(
        "VRCX-0 remote server is listening on {}",
        options.bind_address
    );
    println!("  data root      : {}", data_root.display());
    println!(
        "  registry       : {}",
        data_root.join(crate::tenants::REGISTRY_FILE).display()
    );
    if let Ok(invites) = crate::invites::read_invites(data_root) {
        if !invites.is_empty() {
            println!(
                "  invite codes   : {} in {}",
                invites.len(),
                data_root.join(crate::invites::INVITES_FILE).display()
            );
        }
    }
    println!(
        "  tenants        : {} registered, {} loaded, {} running{}",
        health.registered,
        health.live,
        health.running,
        // Worth naming separately: "loaded but not running" is the normal state
        // for the second or so after boot, and it is not the same as broken.
        if health.starting > 0 {
            format!(", {} starting", health.starting)
        } else {
            String::new()
        }
    );
    println!(
        "  invite code    : {}",
        if options.invite_code.is_some() {
            "required for new tenants"
        } else if registry.is_unclaimed() {
            "NOT SET"
        } else {
            "not set - only per-code invites can admit (see --list-invites)"
        }
    );

    if registry.is_unclaimed() {
        // The whole reason this is loud: an unclaimed server with no invite
        // code is one that anybody who can reach the port can take.
        println!();
        if options.invite_code.is_some() {
            println!("No tenant has claimed this server yet. A client claims one with");
            println!("POST /v1/tenants and must present the invite code to do it.");
        } else {
            println!("No tenant has claimed this server yet and no invite code is set,");
            println!("so the first client that can reach this port will claim it.");
            println!("Set VRCX_SERVER_INVITE (or pass --invite) before exposing the port.");
        }
        return;
    }

    // Nothing has been started yet at this point: tenants are brought up after
    // the socket exists, and in the background, so that the port accepts while
    // they settle. Reporting their run state here would therefore always read
    // "0 running" and look like a failure, so it is left to `/v1/health`, and to
    // the summary the startup task prints once they have settled.
    if health.live > 0 {
        println!();
        println!(
            "Starting {} tenant(s) in the background; /v1/health reports its state.",
            health.live
        );
    }
}

async fn shutdown_signal() {
    let _ = tokio::signal::ctrl_c().await;
}

fn product_app_version() -> String {
    const TAURI_CONFIG: &str = include_str!("../../../src-tauri/tauri.conf.json");
    serde_json::from_str::<Value>(TAURI_CONFIG)
        .ok()
        .and_then(|value| {
            value
                .get("version")
                .and_then(Value::as_str)
                .map(str::trim)
                .filter(|version| !version.is_empty())
                .map(ToOwned::to_owned)
        })
        .unwrap_or_else(|| env!("CARGO_PKG_VERSION").into())
}

fn init_tls_crypto_provider() {
    let _ = rustls::crypto::aws_lc_rs::default_provider().install_default();
}

fn init_tracing(app_data: Option<&Path>) {
    let filter = tracing_subscriber::EnvFilter::try_from_default_env()
        .unwrap_or_else(|_| "vrcx_0=info".into());
    let Some(app_data) = app_data else {
        tracing_subscriber::fmt()
            .with_env_filter(filter)
            .with_target(false)
            .init();
        return;
    };

    let error_log_dir = app_data.to_path_buf();
    tracing_subscriber::registry()
        .with(
            tracing_subscriber::fmt::layer()
                .with_target(false)
                .with_filter(filter),
        )
        .with(
            tracing_subscriber::fmt::layer()
                .with_ansi(false)
                .with_writer(move || {
                    ErrorLogWriter::with_file_name(error_log_dir.clone(), ERROR_LOG_FILE)
                })
                .with_filter(LevelFilter::ERROR),
        )
        .init();
}

fn report_error(app_data: &Path, source: &str, message: impl AsRef<str>) {
    let message = message.as_ref();
    eprintln!("{message}");
    append_error_log(app_data, source, message);
}

fn append_error_log(app_data: &Path, source: &str, message: &str) {
    let line = format!(
        "{}\t{}\t{}\n",
        vrcx_0_core::time::now_iso(),
        source,
        message.replace('\n', " ")
    );
    if std::fs::create_dir_all(app_data).is_err() {
        return;
    }
    use std::io::Write;
    if let Ok(mut file) = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(app_data.join(ERROR_LOG_FILE))
    {
        let _ = file.write_all(line.as_bytes());
    }
}

/// Interactive fallback for `--login`, used when an operator is at a terminal.
///
/// A client signing in over the network is the normal path; this exists for the
/// case where the machine is reachable over SSH and nobody wants to configure a
/// client to recover one account.
pub(crate) struct StdinLoginPrompt;
impl CliLoginPrompt for StdinLoginPrompt {
    fn prompt_username(&self) -> std::io::Result<String> {
        print!("Username/Email: ");
        std::io::Write::flush(&mut std::io::stdout())?;
        let mut username = String::new();
        std::io::stdin().read_line(&mut username)?;
        Ok(username.trim().to_string())
    }

    fn prompt_password(&self) -> std::io::Result<String> {
        rpassword::prompt_password("Password: ")
    }

    fn prompt_two_factor(&self, methods: &[String]) -> std::io::Result<CliTwoFactorChoice> {
        println!("2FA is required. Select an authentication method:");
        for (index, method) in methods.iter().enumerate() {
            println!("{}: {}", index + 1, method);
        }
        print!("Selection [1]: ");
        std::io::Write::flush(&mut std::io::stdout())?;

        let mut selection = String::new();
        std::io::stdin().read_line(&mut selection)?;
        let selection = selection.trim();
        let method_index = if selection.is_empty() {
            0
        } else {
            selection.parse::<usize>().unwrap_or(1).saturating_sub(1)
        };

        let method = methods
            .get(method_index)
            .or_else(|| methods.first())
            .cloned()
            .unwrap_or_default();
        let code = rpassword::prompt_password(format!("Enter {method} code: "))?;
        Ok(CliTwoFactorChoice { method, code })
    }
}
