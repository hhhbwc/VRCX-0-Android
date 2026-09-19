//! In-band VRChat sign-in.
//!
//! The server is expected to run on a box with no keyboard — a router, a NAS, a
//! VPS — so the interactive `--login` prompt is unusable in practice. Without a
//! way to sign in over the network, the only recovery from an expired auth
//! cookie is to carry the device somewhere with a terminal, which is not a
//! deployment.
//!
//! These endpoints move that flow onto the client: type a VRChat username,
//! password and 2FA code, and the server persists the session (and, when asked,
//! the credentials) so it can come back up unattended afterwards.
//!
//! Every route here is **tenant-scoped and token-protected**. That is the
//! change from the single-tenant design, where they had to be open: the bearer
//! token used to be what a successful sign-in handed back, so the routes could
//! not require it, which left them reachable by anything that could reach the
//! port and made one shared secret stand in for every user's identity. A client
//! now gets its own token from `POST /v1/tenants` before it signs in, so these
//! handlers receive a [`TenantRuntime`] and can only ever drive that one
//! account's session.

use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use axum::extract::Extension;
use axum::response::{IntoResponse, Response};
use axum::Json;
use vrcx_0_composition::{
    LoginFailureKind, LoginSessionEnd, LoginSessionRespondInput, LoginSessionStartInput,
    LoginSessionState,
};
use vrcx_0_remote_protocol::{
    AuthAccountEntry, AuthAccountsStatus, AuthFailureKind, AuthOutcome, AuthStatus, LoginRequest,
    TwoFactorRequest,
};

use crate::tenants::TenantRuntime;

/// Sign-in attempts accepted inside [`ATTEMPT_WINDOW`] before the server
/// refuses new ones. Generous enough for a mistyped password and a couple of
/// expired 2FA codes, small enough that guessing is hopeless.
const MAX_ATTEMPTS: usize = 8;
const ATTEMPT_WINDOW: Duration = Duration::from_secs(300);

const BUSY_REASON: &str = "another sign-in is already in progress";
const RATE_LIMITED_REASON: &str =
    "too many sign-in attempts in a row; wait a few minutes and try again";

/// Serialises sign-in attempts and remembers recent ones.
///
/// One attempt at a time is not just politeness: the login session inside the
/// runtime holds a single in-flight attempt, so two clients signing in at once
/// would interleave challenges and leave one of them holding an `attemptId`
/// that no longer exists.
#[derive(Clone)]
pub struct AuthCoordinator {
    attempts: Arc<Mutex<VecDeque<Instant>>>,
    in_flight: Arc<tokio::sync::Mutex<()>>,
    busy: Arc<AtomicBool>,
}

impl Default for AuthCoordinator {
    fn default() -> Self {
        Self::new()
    }
}

impl AuthCoordinator {
    pub fn new() -> Self {
        Self {
            attempts: Arc::new(Mutex::new(VecDeque::new())),
            in_flight: Arc::new(tokio::sync::Mutex::new(())),
            busy: Arc::new(AtomicBool::new(false)),
        }
    }

    /// Whether a client may start a sign-in right now, so a UI can grey out its
    /// button instead of submitting and being told no.
    pub fn login_available(&self) -> bool {
        !self.busy.load(Ordering::Relaxed) && !self.rate_limited()
    }

    /// True once too many attempts landed inside [`ATTEMPT_WINDOW`].
    fn rate_limited(&self) -> bool {
        self.attempts_within(ATTEMPT_WINDOW) >= MAX_ATTEMPTS
    }

    /// Counts the attempts still inside `window`, dropping the rest.
    ///
    /// Pruning as it measures is what keeps the deque from growing without
    /// bound after a failed burst.
    fn attempts_within(&self, window: Duration) -> usize {
        let now = Instant::now();
        let mut attempts = self.lock_attempts();
        attempts.retain(|attempted_at| now.duration_since(*attempted_at) < window);
        attempts.len()
    }

    fn note_attempt(&self) {
        self.lock_attempts().push_back(Instant::now());
    }

    /// A successful sign-in clears the window: the operator has proven who they
    /// are, and leaving them throttled afterwards would only punish them for
    /// having mistyped a password on the way in.
    fn note_success(&self) {
        self.lock_attempts().clear();
    }

    /// Takes the single sign-in slot, or `None` when one is already held.
    fn try_begin(&self) -> Option<SignInGuard> {
        let guard = Arc::clone(&self.in_flight).try_lock_owned().ok()?;
        self.busy.store(true, Ordering::Relaxed);
        Some(SignInGuard {
            _guard: guard,
            busy: Arc::clone(&self.busy),
        })
    }

    fn lock_attempts(&self) -> std::sync::MutexGuard<'_, VecDeque<Instant>> {
        // A poisoned lock means another thread panicked mid-attempt. The worst
        // case of carrying on is a stale counter, which beats refusing to serve
        // `/v1/auth/status` for the rest of the process's life.
        self.attempts
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }
}

/// Releases the sign-in slot when the handler finishes, however it finishes.
struct SignInGuard {
    _guard: tokio::sync::OwnedMutexGuard<()>,
    busy: Arc<AtomicBool>,
}

impl Drop for SignInGuard {
    fn drop(&mut self) {
        self.busy.store(false, Ordering::Relaxed);
    }
}

/// `GET /v1/auth/status` — who is signed in, and whether signing in is possible.
pub async fn status(Extension(tenant): Extension<Arc<TenantRuntime>>) -> Response {
    Json(status_report(&tenant)).into_response()
}

/// `GET /v1/auth/accounts` — the saved-account picker.
///
/// Answers "which accounts can this server sign in without a password". It is
/// scoped to the calling tenant, which is what stops it from being what it used
/// to be: a list of every account on the server, handed to anyone who could
/// reach the port. Each entry still only says the server can sign that account
/// in on its own; nothing here is a credential.
pub async fn accounts(Extension(tenant): Extension<Arc<TenantRuntime>>) -> Response {
    let identity = tenant.state.auth_scope().identity();

    let store = vrcx_0_outbound_adapters::LocalAuthCredentialStore::new(Arc::clone(
        tenant.state.database(),
    ));
    let accounts = match vrcx_0_application::auth::saved_snapshot(&store) {
        Ok(snapshot) => snapshot
            .saved_credentials_list
            .into_iter()
            .map(|record| AuthAccountEntry {
                user_id: record.user.id,
                display_name: record.user.display_name,
                username: record.user.username,
                icon_url: record.user.icon_url,
            })
            .collect(),
        // A broken credential store must not take the whole login page down:
        // the operator can still fall back to typing username and password.
        Err(error) => {
            tracing::warn!(error = %error, "failed to read saved credentials for the account list");
            Vec::new()
        }
    };

    Json(AuthAccountsStatus {
        authenticated: !identity.user_id.trim().is_empty(),
        login_available: tenant.auth.login_available(),
        current_user_id: identity.user_id,
        current_display_name: Some(identity.display_name).filter(|name| !name.is_empty()),
        accounts,
    })
    .into_response()
}

/// `POST /v1/auth/login` — username and password, then maybe a 2FA challenge.
pub async fn login(
    Extension(tenant): Extension<Arc<TenantRuntime>>,
    Json(request): Json<LoginRequest>,
) -> Response {
    let Some(_guard) = tenant.auth.try_begin() else {
        return Json(AuthOutcome::failed(BUSY_REASON, AuthFailureKind::Busy)).into_response();
    };
    if tenant.auth.rate_limited() {
        return Json(AuthOutcome::failed(
            RATE_LIMITED_REASON,
            AuthFailureKind::RateLimited,
        ))
        .into_response();
    }
    tenant.auth.note_attempt();

    let username = request.username.trim().to_string();
    let saved_account_selected = !request.user_id.trim().is_empty();
    if !saved_account_selected && (username.is_empty() || request.password.is_empty()) {
        return Json(AuthOutcome::failed(
            "a username and password are required",
            AuthFailureKind::MissingCredentials,
        ))
        .into_response();
    }

    let input = if saved_account_selected {
        LoginSessionStartInput::SavedCredential {
            user_id: request.user_id.trim().to_string(),
        }
    } else {
        LoginSessionStartInput::Basic {
            username,
            password: request.password,
            save_credentials: request.save_credentials,
        }
    };

    let session = tenant.state.start_login_session(input).await;

    Json(finish(&tenant, session).await).into_response()
}

/// `POST /v1/auth/2fa` — answers a challenge raised by `/v1/auth/login`.
pub async fn two_factor(
    Extension(tenant): Extension<Arc<TenantRuntime>>,
    Json(request): Json<TwoFactorRequest>,
) -> Response {
    let Some(_guard) = tenant.auth.try_begin() else {
        return Json(AuthOutcome::failed(BUSY_REASON, AuthFailureKind::Busy)).into_response();
    };
    if tenant.auth.rate_limited() {
        return Json(AuthOutcome::failed(
            RATE_LIMITED_REASON,
            AuthFailureKind::RateLimited,
        ))
        .into_response();
    }
    tenant.auth.note_attempt();

    let session = tenant
        .state
        .respond_login_session(LoginSessionRespondInput {
            attempt_id: request.attempt_id,
            method: request.method.into(),
            code: request.code,
        })
        .await;

    Json(finish(&tenant, session).await).into_response()
}

/// `POST /v1/auth/logout` — signs out and forgets the saved credentials.
///
/// Forgetting them is the whole point on a server: they exist so the process can
/// sign itself back in unattended, so leaving them behind would silently undo
/// the logout on the next restart. Only this tenant's credentials are touched.
pub async fn logout(Extension(tenant): Extension<Arc<TenantRuntime>>) -> Response {
    let host = &tenant.state;
    let user_id = host.auth_scope().identity().user_id;

    if let Err(error) = host.end_login_session(LoginSessionEnd::Logout).await {
        return Json(ApiMessage::error(format!(
            "failed to end the login session: {error}"
        )))
        .into_response();
    }

    if !user_id.trim().is_empty() {
        let store =
            vrcx_0_outbound_adapters::LocalAuthCredentialStore::new(Arc::clone(host.database()));
        if let Err(error) =
            vrcx_0_application::auth::delete_saved_credential(&store, user_id.clone())
        {
            return Json(ApiMessage::error(format!(
                "signed out, but the saved credentials could not be removed: {error}"
            )))
            .into_response();
        }
    }

    // This tenant's data plane has nothing to serve until someone signs in
    // again, and saying so through `/v1/health` is what keeps the state
    // diagnosable — for the operator, and for this user's other devices.
    host.stop_backend_runtime("signed-out");
    tenant
        .status
        .mark_failed("the account was signed out; sign in again to restore account data");

    Json(ApiMessage::ok(user_id)).into_response()
}

#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct ApiMessage {
    #[serde(skip_serializing_if = "Option::is_none")]
    user_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    message: Option<String>,
}

impl ApiMessage {
    fn ok(user_id: String) -> Self {
        Self {
            user_id: Some(user_id),
            message: None,
        }
    }

    fn error(message: String) -> Self {
        Self {
            user_id: None,
            message: Some(message),
        }
    }
}

fn status_report(tenant: &TenantRuntime) -> AuthStatus {
    let host = &tenant.state;
    let identity = host.auth_scope().identity();

    AuthStatus {
        authenticated: !identity.user_id.trim().is_empty(),
        user_id: identity.user_id,
        display_name: identity.display_name,
        endpoint: host.auth_scope().snapshot().endpoint,
        login_available: tenant.auth.login_available(),
        runtime_error: tenant.status.failure(),
    }
}

/// Translates a login-session state into a wire outcome, and does the follow-up
/// work a successful sign-in implies.
async fn finish(tenant: &TenantRuntime, session: LoginSessionState) -> AuthOutcome {
    match session {
        LoginSessionState::Authenticated { session, .. } => {
            tenant.auth.note_success();
            settle_runtime(tenant).await;
            AuthOutcome::Authenticated {
                userId: session.user_id,
                displayName: session.display_name,
                endpoint: session.endpoint,
                // Deliberately absent. This route is only reachable by a client
                // that already presented the tenant's token, so returning it
                // would be handing back a secret the caller is currently using.
                // It also cannot be returned: the registry keeps only a digest.
                token: None,
            }
        }
        LoginSessionState::Challenge {
            attempt_id,
            methods,
            error,
            ..
        } => AuthOutcome::Challenge {
            attemptId: attempt_id,
            methods: methods
                .iter()
                .map(|method| method.as_str().to_string())
                .collect(),
            error,
        },
        LoginSessionState::Failed { reason, kind, .. } => AuthOutcome::Failed {
            reason,
            kind: failure_kind(kind),
        },
        LoginSessionState::Cancelled => AuthOutcome::Cancelled,
    }
}

/// Clears the degraded flag a boot-time failure left behind.
///
/// Normally there is nothing to start: the login transition already ran the
/// authenticated runtime start, which is the same code path a clean boot takes.
/// The fallback covers the case where that transition did not take — a runtime
/// whose data services never came up — by running the ordinary headless start,
/// which now finds a session and completes.
async fn settle_runtime(tenant: &TenantRuntime) {
    if tenant.status.is_running() {
        return;
    }
    let host = &tenant.state;
    if !host.auth_scope().identity().user_id.trim().is_empty() {
        tenant.status.mark_started();
        return;
    }
    match host.start_headless_backend_runtime(None).await {
        Ok(_) => tenant.status.mark_started(),
        Err(error) => tenant.status.mark_failed(format!(
            "the account signed in, but the runtime did not start: {error}"
        )),
    }
}

fn failure_kind(kind: LoginFailureKind) -> AuthFailureKind {
    match kind {
        LoginFailureKind::InvalidCredentials => AuthFailureKind::InvalidCredentials,
        LoginFailureKind::MissingCredentials => AuthFailureKind::MissingCredentials,
        LoginFailureKind::SessionInvalidated => AuthFailureKind::SessionInvalidated,
        LoginFailureKind::TwoFactorUnavailable => AuthFailureKind::TwoFactorUnavailable,
        LoginFailureKind::Network => AuthFailureKind::Network,
        LoginFailureKind::Other => AuthFailureKind::Other,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn coordinator() -> AuthCoordinator {
        AuthCoordinator::new()
    }

    #[test]
    fn a_fresh_coordinator_accepts_a_sign_in() {
        let auth = coordinator();
        assert!(auth.login_available());
        assert!(!auth.rate_limited());
    }

    #[test]
    fn the_window_refuses_after_the_attempt_budget_is_spent() {
        let auth = coordinator();
        for _ in 0..MAX_ATTEMPTS {
            assert!(!auth.rate_limited());
            auth.note_attempt();
        }
        assert!(auth.rate_limited());
        assert!(!auth.login_available());
    }

    #[test]
    fn a_successful_sign_in_clears_the_window() {
        let auth = coordinator();
        for _ in 0..MAX_ATTEMPTS {
            auth.note_attempt();
        }
        assert!(auth.rate_limited());

        auth.note_success();
        assert!(!auth.rate_limited());
        assert!(auth.login_available());
    }

    #[test]
    fn holding_the_slot_hides_the_login_surface_and_blocks_a_second_attempt() {
        let auth = coordinator();
        let guard = auth.try_begin().expect("the slot starts free");
        assert!(!auth.login_available());
        assert!(
            auth.try_begin().is_none(),
            "a second sign-in must be refused"
        );

        drop(guard);
        assert!(auth.login_available());
        assert!(auth.try_begin().is_some(), "the slot is released on drop");
    }

    #[test]
    fn attempts_older_than_the_window_stop_counting() {
        let auth = coordinator();
        for _ in 0..MAX_ATTEMPTS {
            auth.note_attempt();
        }
        assert!(auth.rate_limited(), "the budget is spent");

        // A window narrower than the age of those attempts prunes them, which is
        // exactly what the real window does once the burst is old enough. Using
        // a zero window avoids sleeping and cannot underflow an `Instant`.
        assert_eq!(auth.attempts_within(Duration::ZERO), 0);
        assert!(!auth.rate_limited(), "a pruned burst stops counting");
    }

    #[test]
    fn failure_kinds_are_mapped_rather_than_dropped() {
        assert_eq!(
            failure_kind(LoginFailureKind::InvalidCredentials),
            AuthFailureKind::InvalidCredentials
        );
        assert_eq!(
            failure_kind(LoginFailureKind::Network),
            AuthFailureKind::Network
        );
    }
}
