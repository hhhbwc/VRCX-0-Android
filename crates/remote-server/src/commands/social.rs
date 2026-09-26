//! Social-runtime commands executed server-side.
//!
//! The desktop build reaches these through `DesktopSocialRuntime` (in
//! `runtime-host-desktop`, which the server cannot depend on). That runtime is
//! a thin shell over `application::social` pure functions plus a handful of
//! outbound adapters, every one of which is constructible from the pieces
//! `composition` already shares — so this module re-assembles the same deps
//! bundles and calls the same functions. Diagnostics/sync bookkeeping from
//! the desktop shell is skipped (observability only).
//!
//! Covered:
//!   * notification chains ×7 — `LocalNotificationChainActions` bundle
//!   * social mutations ×5 (unfriend / friend requests) — `SocialMutationDeps`
//!   * moderation sync ×2 — `ModerationSyncDeps`
//!   * social baselines ×3 — `SocialBaselineDeps` (+ the favorites baseline
//!     result is recorded into the shared authenticated orchestrator, exactly
//!     like the desktop does)

use std::sync::Arc;

use serde_json::Value as JsonValue;

use vrcx_0_application::social::{
    self as application, AuthenticatedRuntimeOrchestrator, ModerationSyncDeps,
    ModerationSyncMutationInput, ModerationSyncRefreshInput, ModerationSyncRuntime,
    NotificationBoopDismissInput, NotificationBoopReplyInput, NotificationHideExpireInput,
    NotificationInstanceInviteInput, NotificationInviteResponseInput,
    NotificationRequestInviteAcceptInput, NotificationRespondInput, QuickSearchQueryInput,
    QuickSearchRuntime, QuickSearchSources, SocialFriendMutationInput,
    SocialFriendRequestAcceptInput, SocialFriendRequestCancelInput, SocialMutationDeps,
    SocialUnfriendBatchInput,
};
use vrcx_0_application_core::{
    RemoteMutationGate, RuntimeAuthScope, RuntimeEventBus, Result, WebClient, WorldCache,
};
use vrcx_0_application_realtime::{
    build_favorites_baseline, build_synced_friend_roster_baseline, RealtimeHostRuntime,
    RealtimeRemoteRequests, RealtimeStore, SocialBaselineDeps, SocialFavoritesBaselineInput,
    SocialFriendRosterBaselineInput,
};
use vrcx_0_composition::RuntimeHostState;
use vrcx_0_outbound_adapters::{
    LocalAvatarApplicationAdapter, LocalMediaUploadAdapter, LocalModerationSyncStore,
    LocalNotificationChainActions, LocalQuickSearchDetailStore, PersistenceRealtimeStore,
    VrchatAvatarRemote, VrchatModerationSyncRemoteRequests, VrchatQuickSearchRemoteRequests,
    VrchatRealtimeRemoteRequests, VrchatRequestAdapter, VrchatSocialMutationRemoteRequests,
};
use vrcx_0_persistence::DatabaseService;

use super::{encode, parse_required, CommandResult};

pub const COMMANDS: &[&str] = &[
    // notification chains
    "app__notification_hide_and_expire",
    "app__notification_request_invite_accept",
    "app__notification_instance_invite_send",
    "app__notification_invite_response_send",
    "app__notification_boop_dismiss",
    "app__notification_boop_reply",
    "app__notification_respond_and_expire",
    // social mutations
    "app__social_unfriend",
    "app__social_unfriend_selection",
    "app__social_friend_request_send",
    "app__social_friend_request_cancel",
    "app__social_friend_request_notification_accept",
    // moderation sync
    "app__moderation_sync_refresh",
    "app__moderation_sync_update",
    // social baselines
    "app__social_baseline_refresh",
    "app__social_favorites_baseline_get",
    "app__social_friend_roster_baseline_get",
    // quick search
    "app__quick_search_query",
];

/// Server-side counterpart of `DesktopSocialRuntime`: the same deps bundles,
/// assembled from `RuntimeHostState` instead of desktop state fields.
struct SocialRuntime {
    db: Arc<DatabaseService>,
    web: Arc<WebClient>,
    auth_scope: RuntimeAuthScope,
    remote_mutations: Arc<RemoteMutationGate>,
    realtime: Arc<RealtimeHostRuntime>,
    event_bus: RuntimeEventBus,
    world_cache: Arc<WorldCache>,
    authenticated: AuthenticatedRuntimeOrchestrator,
    realtime_store: Arc<PersistenceRealtimeStore>,
    moderation_store: LocalModerationSyncStore,
    moderation_sync: ModerationSyncRuntime,
    remote: VrchatRequestAdapter,
    media_upload: LocalMediaUploadAdapter,
}

impl SocialRuntime {
    fn from_runtime(runtime: &RuntimeHostState) -> Self {
        let assembly = runtime.desktop_assembly();
        let db = Arc::clone(runtime.database());
        let web = Arc::clone(runtime.web_client());
        Self {
            realtime_store: Arc::new(PersistenceRealtimeStore::new(Arc::clone(&db))),
            moderation_store: LocalModerationSyncStore::new(Arc::clone(&db)),
            moderation_sync: assembly.moderation_sync().clone(),
            remote: VrchatRequestAdapter::new(Arc::clone(&web)),
            media_upload: LocalMediaUploadAdapter::new(Arc::clone(&web)),
            db,
            web,
            auth_scope: assembly.auth_scope().clone(),
            remote_mutations: Arc::clone(assembly.remote_mutations()),
            realtime: Arc::clone(runtime.realtime_runtime()),
            event_bus: assembly.event_bus().clone(),
            world_cache: Arc::clone(assembly.world_cache()),
            authenticated: runtime.authenticated_runtime().clone(),
        }
    }

    fn mutation_deps(&self) -> SocialMutationDeps<'_> {
        SocialMutationDeps::new(
            self.realtime_store.as_ref(),
            &VrchatSocialMutationRemoteRequests,
            &self.remote,
            &self.auth_scope,
            self.remote_mutations.as_ref(),
            &self.realtime,
        )
    }

    fn moderation_deps(&self) -> ModerationSyncDeps<'_> {
        ModerationSyncDeps::new(
            &self.moderation_store,
            &VrchatModerationSyncRemoteRequests,
            &self.remote,
            &self.auth_scope,
            self.remote_mutations.as_ref(),
        )
    }

    fn baseline_deps(&self) -> SocialBaselineDeps {
        SocialBaselineDeps::new(
            Arc::clone(&self.realtime_store) as Arc<dyn RealtimeStore>,
            Arc::new(VrchatRealtimeRemoteRequests) as Arc<dyn RealtimeRemoteRequests>,
            Arc::clone(&self.web),
            self.auth_scope.clone(),
        )
    }

    /// Mirrors `DesktopSocialRuntime::notification_actions`, including the
    /// authenticated-session guard.
    fn notification_actions(&self) -> Result<LocalNotificationChainActions<'_>> {
        let expected_scope = self.auth_scope.snapshot();
        if !expected_scope.active || expected_scope.current_user_id.trim().is_empty() {
            return Err(vrcx_0_application_core::Error::Custom(
                "Notification action requires an authenticated session.".into(),
            ));
        }
        Ok(LocalNotificationChainActions::new(
            self.db.as_ref(),
            self.web.as_ref(),
            &self.auth_scope,
            expected_scope,
            &self.event_bus,
            self.world_cache.as_ref(),
            self.remote_mutations.as_ref(),
            &self.media_upload,
        ))
    }
}

/// Runs one notification-chain action through the shared bundle.
async fn notification_action<T, F>(social: &SocialRuntime, run: F) -> Result<T>
where
    F: for<'a> FnOnce(&'a LocalNotificationChainActions<'a>) -> std::pin::Pin<
        Box<dyn std::future::Future<Output = Result<T>> + Send + 'a>,
    >,
{
    let actions = social.notification_actions()?;
    run(&actions).await
}

/// Builds the quick-search runtime the same way the desktop shell does
/// (`runtime-host-desktop::DesktopRuntime::quick_search_runtime`), from the
/// pieces `composition` already shares.
///
/// The runtime caches its remote working set (own/favorite avatars, worlds,
/// groups) for a minute, so the caller holds one per tenant instead of
/// rebuilding per request — a rebuilt one would re-pull every page on each
/// keystroke.
pub(crate) fn quick_search_runtime(runtime: &RuntimeHostState) -> QuickSearchRuntime {
    let assembly = runtime.desktop_assembly();
    let db = Arc::clone(runtime.database());
    let web = Arc::clone(runtime.web_client());
    let avatar_adapter = Arc::new(LocalAvatarApplicationAdapter::new(Arc::clone(&db)));
    QuickSearchRuntime::new(
        QuickSearchSources::new(
            Arc::new(LocalQuickSearchDetailStore::new(db)),
            Arc::new(VrchatQuickSearchRemoteRequests),
            avatar_adapter,
            Arc::new(VrchatAvatarRemote::new(
                web,
                assembly.diagnostics().clone(),
                assembly.sync().clone(),
            )),
            Arc::clone(assembly.world_cache()),
        ),
        Arc::new(VrchatRequestAdapter::new(Arc::clone(runtime.web_client()))),
        assembly.auth_scope().clone(),
        assembly.diagnostics().clone(),
        assembly.sync().clone(),
    )
}

pub async fn dispatch(
    runtime: &RuntimeHostState,
    quick_search: &QuickSearchRuntime,
    command: &str,
    args: &JsonValue,
) -> Option<CommandResult> {
    if !COMMANDS.contains(&command) {
        return None;
    }
    let social = SocialRuntime::from_runtime(runtime);
    match command {
        // -- notification chains ----------------------------------------
        "app__notification_hide_and_expire" => {
            let input: NotificationHideExpireInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(
                notification_action(&social, |actions| {
                    Box::pin(application::hide_and_expire_notification(actions, input))
                })
                .await,
            ))
        }
        "app__notification_request_invite_accept" => {
            let input: NotificationRequestInviteAcceptInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(
                notification_action(&social, |actions| {
                    Box::pin(application::accept_request_invite_notification(actions, input))
                })
                .await,
            ))
        }
        "app__notification_instance_invite_send" => {
            let input: NotificationInstanceInviteInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(
                notification_action(&social, |actions| {
                    Box::pin(application::send_instance_invite_notification(actions, input))
                })
                .await,
            ))
        }
        "app__notification_invite_response_send" => {
            let input: NotificationInviteResponseInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(
                notification_action(&social, |actions| {
                    Box::pin(application::send_invite_response_notification(actions, input))
                })
                .await,
            ))
        }
        "app__notification_boop_dismiss" => {
            let input: NotificationBoopDismissInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(
                notification_action(&social, |actions| {
                    Box::pin(application::dismiss_boop_notifications(actions, input))
                })
                .await,
            ))
        }
        "app__notification_boop_reply" => {
            let input: NotificationBoopReplyInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(
                notification_action(&social, |actions| {
                    Box::pin(application::send_boop_reply_notification(actions, input))
                })
                .await,
            ))
        }
        "app__notification_respond_and_expire" => {
            let input: NotificationRespondInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(
                notification_action(&social, |actions| {
                    Box::pin(application::respond_and_expire_notification(actions, input))
                })
                .await,
            ))
        }
        // -- social mutations --------------------------------------------
        "app__social_unfriend" => {
            let input: SocialFriendMutationInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(application::unfriend(social.mutation_deps(), input).await))
        }
        "app__social_unfriend_selection" => {
            let input: SocialUnfriendBatchInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(
                application::unfriend_selection(social.mutation_deps(), input).await,
            ))
        }
        "app__social_friend_request_send" => {
            let input: SocialFriendMutationInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(
                application::send_friend_request(social.mutation_deps(), input).await,
            ))
        }
        "app__social_friend_request_cancel" => {
            let input: SocialFriendRequestCancelInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(
                application::cancel_friend_request(social.mutation_deps(), input).await,
            ))
        }
        "app__social_friend_request_notification_accept" => {
            let input: SocialFriendRequestAcceptInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(
                application::accept_friend_request_notification(social.mutation_deps(), input)
                    .await,
            ))
        }
        // -- moderation sync ----------------------------------------------
        "app__moderation_sync_refresh" => {
            let input: ModerationSyncRefreshInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(
                application::refresh_player_moderations(
                    &social.moderation_sync,
                    social.moderation_deps(),
                    input,
                )
                .await,
            ))
        }
        "app__moderation_sync_update" => {
            let input: ModerationSyncMutationInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(
                application::update_player_moderation(
                    &social.moderation_sync,
                    social.moderation_deps(),
                    input,
                )
                .await,
            ))
        }
        // -- social baselines ---------------------------------------------
        "app__social_baseline_refresh" => {
            Some(encode(runtime.refresh_social_baseline_now().await))
        }
        "app__social_favorites_baseline_get" => {
            let input: SocialFavoritesBaselineInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            let result = build_favorites_baseline(social.baseline_deps(), input).await;
            match result {
                Ok(output) => {
                    social.authenticated.update_favorites_baseline(output.clone());
                    Some(encode(Ok(output)))
                }
                Err(error) => Some(CommandResult::Failed(error.to_string())),
            }
        }
        "app__social_friend_roster_baseline_get" => {
            let input: SocialFriendRosterBaselineInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            let result = build_synced_friend_roster_baseline(
                social.baseline_deps(),
                &social.realtime,
                input,
            )
            .await
            .map(|baseline| baseline.output);
            match result {
                Ok(output) => Some(encode(Ok(output))),
                Err(error) => Some(CommandResult::Failed(error.to_string())),
            }
        }
        // -- quick search ---------------------------------------------------
        "app__quick_search_query" => {
            let input: QuickSearchQueryInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            // Same shape as the desktop binding: the friend half searches the
            // live realtime snapshot, not the database.
            let snapshot = social.realtime.friend_snapshot();
            Some(encode(quick_search.query(input, snapshot).await))
        }
        _ => None,
    }
}
