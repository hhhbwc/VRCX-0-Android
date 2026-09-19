use std::sync::Arc;
use std::time::Duration;

use axum::extract::ws::{Message, WebSocket, WebSocketUpgrade};
use axum::extract::{Extension, Request, State};
use axum::http::{header, Method, StatusCode};
use axum::middleware::{self, Next};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use futures_util::stream::SplitSink;
use futures_util::{SinkExt, StreamExt};
use serde_json::json;
use tokio::sync::broadcast;
use tower_http::cors::{Any, CorsLayer};
use vrcx_0_local_server::header_to_str;
use vrcx_0_remote_protocol::{
    paths, CommandError, CommandRequest, LogIngestBatch, RemoteReply, RemoteRequest, StreamFrame,
    TenantCredential, TenantFailure, TenantFailureKind, TenantRegistrationRequest,
    AUTHORIZATION_HEADER, COMMAND_NOT_IMPLEMENTED, PROTOCOL_VERSION,
};

use crate::auth;
use crate::commands::{self, CommandResult};
use crate::tenants::{TenantError, TenantRegistry, TenantRuntime};

/// How often an otherwise idle stream is poked. Reverse proxies and NAT tables
/// drop silent sockets, and a dropped stream looks like a stalled client.
const KEEPALIVE_INTERVAL: Duration = Duration::from_secs(30);

/// Process-wide state. Deliberately tiny.
///
/// It used to hold the runtime, the event bus, the relay and *the* token, all
/// singletons, because there was exactly one account on the server. Every one
/// of those is now a property of a tenant and is reached through
/// [`TenantRegistry`], so nothing here can leak one user's data into another's
/// request: a handler cannot touch a runtime it did not authenticate for.
#[derive(Clone)]
pub struct ApiState {
    pub tenants: Arc<TenantRegistry>,
    pub app_version: Arc<str>,
}

/// Builds the data plane.
///
/// The open surface is two routes and nothing else:
///
/// - `/v1/health`, which answers liveness for a supervisor without disclosing
///   anything about any account;
/// - `/v1/tenants`, which is how a client that has only an address turns itself
///   into something the rest of the server will talk to.
///
/// Everything that touches account data sits behind [`require_tenant`],
/// **including the sign-in routes**. That is the change that makes a shared
/// server defensible: in the single-tenant design `/v1/auth/*` had to be open,
/// because the token was what a successful sign-in handed back. One server-wide
/// secret stood in for every user's identity, so any caller could act as any
/// other. A tenant now receives its own token *before* it signs in, which lets
/// those routes require it and scopes every one of them to a single user.
pub fn router(state: ApiState) -> Router {
    let protected = Router::new()
        .route(paths::RPC, post(handle_rpc))
        .route(paths::COMMAND, post(handle_command))
        .route(paths::STREAM, get(handle_stream))
        .route(paths::LOG_INGEST, post(handle_log_ingest))
        .route(paths::AUTH_STATUS, get(auth::status))
        .route(paths::AUTH_ACCOUNTS, get(auth::accounts))
        .route(paths::AUTH_LOGIN, post(auth::login))
        .route(paths::AUTH_TWO_FACTOR, post(auth::two_factor))
        .route(paths::AUTH_LOGOUT, post(auth::logout))
        .route(paths::TENANT_TOKEN, post(handle_tenant_token_rotate))
        .layer(middleware::from_fn_with_state(
            state.clone(),
            require_tenant,
        ));

    // CORS has to sit outside the token check: a browser sends the preflight
    // with no credentials at all, so an auth layer in front of it would answer
    // 401 and the real request would never be sent. The webview's origin is
    // `tauri://localhost`, which no allow-list would match by name.
    let cors = CorsLayer::new()
        .allow_origin(Any)
        .allow_methods([Method::GET, Method::POST, Method::OPTIONS])
        .allow_headers([header::AUTHORIZATION, header::CONTENT_TYPE])
        .expose_headers(Any);

    Router::new()
        .route(paths::HEALTH, get(handle_health))
        .route(paths::TENANTS, post(handle_tenant_register))
        .merge(protected)
        .layer(cors)
        .with_state(state)
}

/// `POST /v1/tenants` — claims a user slot on this server.
///
/// The first claim needs no invitation, so an operator can bring a fresh
/// server up alone. Every later one must present the code the operator started
/// the server with, which is what keeps "anyone who can reach the port" from
/// turning into "anyone can create an account on somebody else's server".
async fn handle_tenant_register(
    State(state): State<ApiState>,
    Json(request): Json<TenantRegistrationRequest>,
) -> Response {
    // Building a tenant opens a SQLite database, so it must not run on the
    // reactor.
    let registry = Arc::clone(&state.tenants);
    let label = request.label.clone();
    let invite = request.invite_code.clone();
    let created =
        tokio::task::spawn_blocking(move || registry.create(&label, invite.as_deref(), None))
            .await;

    match created {
        Ok(Ok((runtime, token))) => {
            let record = runtime.record();
            // A tenant that has just been created has no VRChat session yet, so
            // the backend is expected to report itself degraded until someone
            // signs in. Starting it anyway is what makes that state visible on
            // `/v1/health` and `/v1/auth/status`, instead of the client seeing
            // a server that looks idle and wondering whether it is broken.
            tokio::spawn(async move {
                if let Err(reason) = runtime.start(false).await {
                    tracing::info!(
                        tenant = %runtime.id(),
                        reason = %reason,
                        "a new tenant is waiting for a sign-in"
                    );
                }
            });
            Json(TenantCredential {
                tenant_id: record.id,
                label: record.label,
                token,
            })
            .into_response()
        }
        Ok(Err(error)) => tenant_failure(error),
        Err(error) => tenant_failure(TenantError::Runtime(format!(
            "tenant registration task failed: {error}"
        ))),
    }
}

/// `POST /v1/tenants/token` — replaces the caller's bearer token.
///
/// Registered here rather than in [`crate::auth`] because it is a tenancy
/// concern: it has nothing to do with the VRChat account and works before one
/// is signed in.
async fn handle_tenant_token_rotate(
    State(state): State<ApiState>,
    Extension(tenant): Extension<Arc<TenantRuntime>>,
) -> Response {
    let id = tenant.id();
    let registry = Arc::clone(&state.tenants);
    let rotated = tokio::task::spawn_blocking(move || registry.rotate_token(&id)).await;

    match rotated {
        Ok(Ok(token)) => Json(TenantCredential {
            tenant_id: tenant.id(),
            label: tenant.label(),
            token,
        })
        .into_response(),
        Ok(Err(error)) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(TenantFailure::new(error.to_string(), TenantFailureKind::Other)),
        )
            .into_response(),
        Err(error) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(TenantFailure::new(
                format!("token rotation task failed: {error}"),
                TenantFailureKind::Other,
            )),
        )
            .into_response(),
    }
}

/// Runs a forwarded command against the calling tenant's own session.
///
/// `501` is the signal the client watches for: it means "not migrated yet",
/// which the client turns into a local invocation so a half-finished migration
/// still works.
async fn handle_command(
    Extension(tenant): Extension<Arc<TenantRuntime>>,
    Json(request): Json<CommandRequest>,
) -> Response {
    let runtime = tenant.rpc.runtime();
    let local = tenant.rpc.local_data();
    match commands::dispatch(runtime.as_ref(), local, request).await {
        CommandResult::Ok(value) => Json(value).into_response(),
        CommandResult::NotImplemented => (
            StatusCode::from_u16(COMMAND_NOT_IMPLEMENTED)
                .unwrap_or(StatusCode::NOT_IMPLEMENTED),
            Json(json!({ "message": "command is not implemented by this server" })),
        )
            .into_response(),
        CommandResult::Failed(message) => (
            StatusCode::BAD_GATEWAY,
            Json(CommandError { message }),
        )
            .into_response(),
    }
}

/// `GET /v1/health` — is this process serving, and how many users came up.
///
/// The shape stays close to the single-tenant version on purpose: the command
/// stats and `commands.supported` are what a client reads on startup to decide
/// what to forward, and that contract is unchanged. What is new is that "the
/// runtime" is now one runtime per tenant, so the answer is a count rather than
/// a flag.
async fn handle_health(State(state): State<ApiState>) -> impl IntoResponse {
    let health = state.tenants.health();
    let started = health.live > 0 && health.running == health.live;
    let error = if started {
        None
    } else if health.live == 0 {
        Some(if health.registered == 0 {
            "no tenant has claimed this server yet".to_string()
        } else {
            "no tenant runtime could be loaded".to_string()
        })
    } else if let Some(failure) = health.first_failure.clone() {
        Some(failure)
    } else if health.starting > 0 {
        // Degraded with no reason would read as a fault in the server. Tenants
        // are started after the socket is bound, so this is a normal transient
        // state right after boot, and it has to say so.
        Some(format!("{} tenant(s) are still starting", health.starting))
    } else {
        None
    };

    Json(json!({
        "status": if started { "ok" } else { "degraded" },
        "appVersion": state.app_version.as_ref(),
        "protocolVersion": PROTOCOL_VERSION,
        "runtime": {
            "started": started,
            "error": error,
        },
        // The whole reason the process stays up when an account is signed out:
        // an operator can tell "this user needs to sign in again" from "the
        // server is broken" without reading a log.
        "tenants": {
            "registered": health.registered,
            "live": health.live,
            "running": health.running,
        },
        // Lets you tell "the client is using me" from "the client fell back to
        // its own runtime", which are otherwise indistinguishable from here.
        // `unimplemented` is the one to watch: it counts commands the client
        // asked for and ended up running itself.
        "commands": {
            "calls": commands::STATS.calls.load(std::sync::atomic::Ordering::Relaxed),
            "ok": commands::STATS.ok.load(std::sync::atomic::Ordering::Relaxed),
            "unimplemented": commands::STATS
                .unimplemented
                .load(std::sync::atomic::Ordering::Relaxed),
            "failed": commands::STATS.failed.load(std::sync::atomic::Ordering::Relaxed),
            // What the client should forward. It asks once and then only sends
            // these, so an unimplemented command costs nothing per call.
            "supported": commands::supported_commands(),
        },
    }))
}

async fn handle_rpc(
    Extension(tenant): Extension<Arc<TenantRuntime>>,
    Json(request): Json<RemoteRequest>,
) -> Json<RemoteReply> {
    Json(tenant.rpc.dispatch(request).await)
}

async fn handle_stream(
    State(state): State<ApiState>,
    Extension(tenant): Extension<Arc<TenantRuntime>>,
    upgrade: WebSocketUpgrade,
) -> Response {
    let app_version = Arc::clone(&state.app_version);
    upgrade.on_upgrade(move |socket| stream_events(app_version, tenant, socket))
}

async fn stream_events(app_version: Arc<str>, tenant: Arc<TenantRuntime>, socket: WebSocket) {
    let (mut sender, mut receiver) = socket.split();
    // Subscribed to this tenant's hub, which is the only thing standing between
    // two users' realtime feeds on a shared server.
    let mut events = tenant.events.subscribe();
    tracing::debug!(
        tenant = %tenant.id(),
        clients = tenant.events.receiver_count(),
        "event stream opened"
    );

    let hello = StreamFrame::Hello {
        protocol_version: PROTOCOL_VERSION,
        app_version: app_version.to_string(),
    };
    if write_frame(&mut sender, &hello).await.is_err() {
        return;
    }

    let mut keepalive = tokio::time::interval(KEEPALIVE_INTERVAL);
    // The first tick fires immediately; consume it so the first ping lands on
    // schedule rather than on connect.
    keepalive.tick().await;

    loop {
        tokio::select! {
            event = events.recv() => match event {
                Ok(frame) => {
                    if write_frame(&mut sender, frame.as_ref()).await.is_err() {
                        break;
                    }
                }
                Err(broadcast::error::RecvError::Lagged(skipped)) => {
                    // Tell the client to resync instead of silently skipping.
                    if write_frame(&mut sender, &StreamFrame::Lagged { skipped }).await.is_err() {
                        break;
                    }
                }
                Err(broadcast::error::RecvError::Closed) => break,
            },
            incoming = receiver.next() => match incoming {
                Some(Ok(Message::Close(_))) | Some(Err(_)) | None => break,
                // Clients do not send anything meaningful yet; ignore chatter
                // rather than tear the stream down.
                Some(Ok(_)) => {}
            },
            _ = keepalive.tick() => {
                if sender.send(Message::Ping(Default::default())).await.is_err() {
                    break;
                }
            }
        }
    }
}

async fn handle_log_ingest(
    Extension(tenant): Extension<Arc<TenantRuntime>>,
    Json(batch): Json<LogIngestBatch>,
) -> Response {
    let relay = tenant.relay.clone();
    match tokio::task::spawn_blocking(move || relay.append(batch)).await {
        Ok(Ok(ack)) => Json(ack).into_response(),
        Ok(Err(error)) => ingest_failure(error.to_string()),
        Err(error) => ingest_failure(format!("ingest task failed: {error}")),
    }
}

fn ingest_failure(message: String) -> Response {
    (
        StatusCode::INTERNAL_SERVER_ERROR,
        Json(json!({ "message": message })),
    )
        .into_response()
}

/// Resolves the bearer token into a tenant and hands it to the handler.
///
/// The lookup is the whole authorization model: a token identifies exactly one
/// tenant, and the handler can only reach that tenant's runtime, database,
/// event bus and VRChat session. There is no "which user is this request for"
/// parameter to get wrong, which is the failure mode the previous design had —
/// it compared every request against one shared secret and then served
/// whichever account that secret belonged to.
async fn require_tenant(
    State(state): State<ApiState>,
    mut request: Request,
    next: Next,
) -> Response {
    let supplied = header_to_str(request.headers(), AUTHORIZATION_HEADER)
        .and_then(bearer_token)
        // The event stream is a browser WebSocket: the `WebSocket` API cannot
        // attach an Authorization header, so the upgrade may carry the token as
        // a query parameter instead. Same secret, same check — only the
        // transport differs. Query strings can leak into logs, which is why
        // only this route accepts one.
        .or_else(|| query_token(request.uri()));

    let Some(tenant) = supplied.and_then(|token| state.tenants.authenticate(token)) else {
        return unauthorized();
    };

    // The handler reads the tenant from here rather than from `ApiState`, which
    // is what makes it impossible to serve one user with another's runtime.
    request.extensions_mut().insert(tenant);
    next.run(request).await
}

/// One answer for "no token", "wrong token" and "token of a revoked tenant".
///
/// Distinguishing them would let an unauthenticated caller probe which tokens
/// exist, and none of the three is actionable differently by a client.
fn unauthorized() -> Response {
    (
        StatusCode::UNAUTHORIZED,
        Json(json!({ "message": "a valid bearer token is required" })),
    )
        .into_response()
}

/// Maps a refused claim onto a status code and a machine-readable kind, so a
/// client can say "that label is taken" instead of "registration failed".
fn tenant_failure(error: TenantError) -> Response {
    let (status, kind) = match &error {
        TenantError::AdmissionDenied => (StatusCode::FORBIDDEN, TenantFailureKind::AdmissionClosed),
        TenantError::InvalidLabel => (StatusCode::BAD_REQUEST, TenantFailureKind::InvalidLabel),
        TenantError::Duplicate(what) if what.starts_with("the label") => {
            (StatusCode::CONFLICT, TenantFailureKind::DuplicateLabel)
        }
        _ => (StatusCode::INTERNAL_SERVER_ERROR, TenantFailureKind::Other),
    };
    (
        status,
        Json(TenantFailure::new(error.to_string(), kind)),
    )
        .into_response()
}

/// Pulls the credential out of `Authorization: Bearer <token>`.
fn bearer_token(value: &str) -> Option<&str> {
    let (scheme, token) = value.split_once(' ')?;
    if !scheme.eq_ignore_ascii_case("bearer") {
        return None;
    }
    let token = token.trim();
    (!token.is_empty()).then_some(token)
}

/// Pulls the credential out of `?token=<value>` on the request URI.
fn query_token(uri: &axum::http::Uri) -> Option<&str> {
    let query = uri.query()?;
    for pair in query.split('&') {
        if let Some(value) = pair.strip_prefix("token=") {
            let value = value.trim();
            if !value.is_empty() {
                return Some(value);
            }
        }
    }
    None
}

async fn write_frame(
    sender: &mut SplitSink<WebSocket, Message>,
    frame: &StreamFrame,
) -> Result<(), ()> {
    let text = serde_json::to_string(frame).map_err(|_| ())?;
    sender
        .send(Message::Text(text.into()))
        .await
        .map_err(|_| ())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bearer_scheme_is_matched_case_insensitively() {
        assert_eq!(bearer_token("Bearer abc"), Some("abc"));
        assert_eq!(bearer_token("bearer abc"), Some("abc"));
        assert_eq!(bearer_token("BEARER   abc  "), Some("abc"));
    }

    #[test]
    fn non_bearer_and_empty_credentials_are_rejected() {
        assert_eq!(bearer_token("Basic abc"), None);
        assert_eq!(bearer_token("Bearer"), None);
        assert_eq!(bearer_token("Bearer   "), None);
        assert_eq!(bearer_token(""), None);
    }

    #[test]
    fn query_tokens_are_read_from_the_stream_uri() {
        let uri: axum::http::Uri = "/v1/stream?token=abc".parse().unwrap();
        assert_eq!(query_token(&uri), Some("abc"));

        let other: axum::http::Uri = "/v1/stream?foo=1".parse().unwrap();
        assert_eq!(query_token(&other), None);

        let blank: axum::http::Uri = "/v1/stream?token=".parse().unwrap();
        assert_eq!(query_token(&blank), None);
    }

    #[test]
    fn a_refused_claim_says_which_kind_of_refusal_it_was() {
        assert_eq!(
            tenant_failure(TenantError::AdmissionDenied).status(),
            StatusCode::FORBIDDEN
        );
        assert_eq!(
            tenant_failure(TenantError::Duplicate("the label alice".into())).status(),
            StatusCode::CONFLICT
        );
        assert_eq!(
            tenant_failure(TenantError::InvalidLabel).status(),
            StatusCode::BAD_REQUEST
        );
        assert_eq!(
            tenant_failure(TenantError::Runtime("no disk".into())).status(),
            StatusCode::INTERNAL_SERVER_ERROR
        );
    }
}
