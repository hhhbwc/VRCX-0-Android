use std::sync::Arc;

use vrcx_0_application_activity::OverlayActivityRuntime;
use vrcx_0_composition::local_data::LocalDataRuntime;
use vrcx_0_composition::RuntimeHostState;
use vrcx_0_core::OwnerId;
use vrcx_0_remote_protocol::{
    HealthReport, RemoteReply, RemoteRequest, RemoteResponse, PROTOCOL_VERSION,
};

/// Everything a remote query needs: the already-authenticated runtime and the
/// version string reported to clients on connect.
pub struct RpcContext {
    runtime: Arc<RuntimeHostState>,
    local: LocalDataRuntime,
    app_version: Arc<str>,
}

impl RpcContext {
    pub fn new(runtime: Arc<RuntimeHostState>, app_version: impl Into<Arc<str>>) -> Self {
        // The same facade the desktop shell builds, from the same host runtime,
        // so an `app__local_*` command answered here runs the identical code
        // path it would have run on the desktop. The two inputs the host does
        // not already own are per-process: this server has no overlay and no
        // signed-in profile yet.
        let local = LocalDataRuntime::from_host(
            &runtime,
            Arc::new(vrcx_0_outbound_adapters::LocalProfileConfigStore::new(
                Arc::clone(runtime.database()),
                Arc::clone(runtime.storage()),
            )),
            OverlayActivityRuntime::new(),
        );
        Self {
            runtime,
            local,
            app_version: app_version.into(),
        }
    }

    /// Access to the runtime for callers that need more than `dispatch`, such
    /// as the sign-in routes, which drive the live runtime rather than a query.
    pub fn runtime(&self) -> &Arc<RuntimeHostState> {
        &self.runtime
    }

    /// The database-side facade the `local_*` command table needs.
    pub fn local_data(&self) -> &LocalDataRuntime {
        &self.local
    }

    /// Runs a request, keeping SQLite work off the reactor.
    ///
    /// The runtime is `Send + Sync`, so moving a clone into the blocking pool
    /// is safe. A panic inside a query surfaces as an `internal` reply rather
    /// than taking the connection down.
    pub async fn dispatch(&self, request: RemoteRequest) -> RemoteReply {
        let runtime = Arc::clone(&self.runtime);
        let app_version = Arc::clone(&self.app_version);

        match tokio::task::spawn_blocking(move || handle(&runtime, &app_version, request)).await {
            Ok(reply) => reply,
            Err(error) => RemoteReply::internal(format!("query task failed: {error}")),
        }
    }
}

fn handle(
    runtime: &RuntimeHostState,
    app_version: &str,
    request: RemoteRequest,
) -> RemoteReply {
    match request {
        RemoteRequest::Health => ok(RemoteResponse::Health {
            report: health_report(runtime, app_version),
        }),

        // Feed queries go through the realtime runtime rather than straight to
        // SQLite: it merges not-yet-persisted live entries with persisted rows,
        // which is what the desktop command does and what the UI expects.
        RemoteRequest::FeedLatest { query } => match runtime.realtime_runtime().query_feed_latest(query) {
            Ok(output) => ok(RemoteResponse::FeedLatest { output }),
            Err(error) => failed(error),
        },
        RemoteRequest::FeedSearch { query } => match runtime.realtime_runtime().query_feed_search(query) {
            Ok(rows) => ok(RemoteResponse::FeedSearch { rows }),
            Err(error) => failed(error),
        },
        RemoteRequest::FeedRows { query } => {
            match vrcx_0_persistence::feed::feed_rows_query(runtime.database(), query) {
                Ok(rows) => ok(RemoteResponse::FeedRows { rows }),
                Err(error) => failed(error),
            }
        }

        RemoteRequest::FriendLogCurrent { user_id } => {
            match vrcx_0_persistence::friends::friend_log_current_list(runtime.database(), user_id) {
                Ok(entries) => ok(RemoteResponse::FriendLogCurrent { entries }),
                Err(error) => failed(error),
            }
        }
        RemoteRequest::FriendLogHistory { query } => {
            match vrcx_0_persistence::friends::friend_log_history_query(runtime.database(), query) {
                Ok(entries) => ok(RemoteResponse::FriendLogHistory { entries }),
                Err(error) => failed(error),
            }
        }

        // Game log tables are partitioned per account, so the owner id has to
        // come from the live auth scope rather than the request.
        RemoteRequest::GameLogQuery { query } => {
            let owner = current_owner(runtime);
            match vrcx_0_persistence::game_log::game_log_query(runtime.database(), &owner, query) {
                Ok(output) => ok(RemoteResponse::GameLogQuery { output }),
                Err(error) => failed(error),
            }
        }

        RemoteRequest::FavoriteList { kind } => {
            let owner = current_owner(runtime);
            match vrcx_0_persistence::favorites::favorite_list(
                runtime.database(),
                Some(&owner),
                kind,
            ) {
                Ok(rows) => ok(RemoteResponse::FavoriteList { rows }),
                Err(error) => failed(error),
            }
        }

        RemoteRequest::ConfigList => {
            match vrcx_0_persistence::config::config_list_values(runtime.database()) {
                Ok(entries) => ok(RemoteResponse::ConfigList { entries }),
                Err(error) => failed(error),
            }
        }
    }
}

fn current_owner(runtime: &RuntimeHostState) -> OwnerId {
    OwnerId::new(runtime.auth_scope().snapshot().current_user_id)
}

fn health_report(runtime: &RuntimeHostState, app_version: &str) -> HealthReport {
    let scope = runtime.auth_scope().snapshot();
    let current_user_id = scope.current_user_id;
    let endpoint = scope.endpoint;

    HealthReport {
        app_version: app_version.to_string(),
        protocol_version: PROTOCOL_VERSION,
        authenticated: !current_user_id.trim().is_empty(),
        current_user_id,
        endpoint,
    }
}

fn ok(response: RemoteResponse) -> RemoteReply {
    RemoteReply::Ok { response }
}

fn failed(error: impl std::fmt::Display) -> RemoteReply {
    RemoteReply::database(error.to_string())
}
