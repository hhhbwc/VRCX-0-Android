use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, RwLock};

/// Tracks whether the headless VRChat backend runtime actually came up.
///
/// The server deliberately keeps serving when the runtime cannot start. The
/// common case is a router that rebooted after the auth cookie expired, and a
/// process that just exits in that situation is unoperable:
///
/// - `/v1/health` becomes unreachable, so nobody can tell "the process died"
///   from "the session expired" — the two need completely different fixes.
/// - procd's `respawn 3600 5 5` burns all five retries within a minute and then
///   gives up permanently, so the service never comes back on its own.
///
/// Binding anyway and reporting the failure here turns an invisible death into
/// a diagnosable state: `GET /v1/health` answers `status: "degraded"` with the
/// reason, and the process stays alive for the supervisor.
///
/// This is deliberately three states, not two. "No failure recorded" and "the
/// runtime is up" are not the same thing: a tenant that has not been started yet
/// also has no failure recorded, and counting it as running made `/v1/health`
/// answer `ok` for a server that had not brought anything up. Tenants are now
/// started after the listener is bound, so that window is observable and has to
/// be reported honestly.
#[derive(Clone, Debug, Default)]
pub struct RuntimeStatus {
    /// Set once `start` has reported a result, whichever way it went.
    started: Arc<AtomicBool>,
    /// `None` while nothing has gone wrong; otherwise why it is not running.
    failure: Arc<RwLock<Option<String>>>,
}

impl RuntimeStatus {
    pub fn new() -> Self {
        Self::default()
    }

    /// The runtime started (or a later retry succeeded).
    pub fn mark_started(&self) {
        self.started.store(true, Ordering::SeqCst);
        self.set(None);
    }

    /// The runtime could not start; `reason` is surfaced through `/v1/health`.
    pub fn mark_failed(&self, reason: impl Into<String>) {
        self.started.store(true, Ordering::SeqCst);
        self.set(Some(reason.into()));
    }

    /// Why the runtime is not running, or `None` when it is.
    pub fn failure(&self) -> Option<String> {
        self.failure
            .read()
            .ok()
            .and_then(|guard| guard.clone())
    }

    /// Whether a start attempt has produced an answer yet.
    pub fn has_started(&self) -> bool {
        self.started.load(Ordering::SeqCst)
    }

    /// The runtime is up and serving.
    pub fn is_running(&self) -> bool {
        self.has_started() && self.failure().is_none()
    }

    /// Neither up nor failed: a start attempt is still outstanding.
    pub fn is_pending(&self) -> bool {
        !self.has_started()
    }

    fn set(&self, value: Option<String>) {
        // A poisoned lock must not take the health endpoint down with it; the
        // worst case is a stale status, which is still better than no answer.
        if let Ok(mut guard) = self.failure.write() {
            *guard = value;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_fresh_status_is_pending_not_running() {
        // The distinction that matters: nothing has been attempted yet, so
        // reporting "running" would make a server that has not started anything
        // answer `ok`.
        let status = RuntimeStatus::new();
        assert!(status.is_pending());
        assert!(!status.is_running());
        assert_eq!(status.failure(), None);
    }

    #[test]
    fn a_failure_is_reported_until_it_is_cleared() {
        let status = RuntimeStatus::new();
        status.mark_failed("No saved account is available for headless login.");
        assert!(!status.is_running());
        assert!(!status.is_pending());
        assert_eq!(
            status.failure().as_deref(),
            Some("No saved account is available for headless login.")
        );

        status.mark_started();
        assert!(status.is_running());
        assert_eq!(status.failure(), None);
    }

    #[test]
    fn a_retry_can_succeed_after_a_failure() {
        let status = RuntimeStatus::new();
        status.mark_failed("boom");
        assert!(!status.is_running());
        status.mark_started();
        assert!(status.is_running());
        assert!(!status.is_pending());
    }

    #[test]
    fn clones_observe_the_same_state() {
        let status = RuntimeStatus::new();
        let observer = status.clone();
        assert!(observer.is_pending());
        status.mark_failed("boom");
        assert_eq!(observer.failure().as_deref(), Some("boom"));
        assert!(!observer.is_pending());
    }
}
