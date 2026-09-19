//! Hand-written command table for the remaining migratable `application/`
//! commands: realtime helpers, friend-log name resolution, my-avatars, vrc
//! status, share-collection (minus the browser-opening manager), the
//! frontend batch/import surface, group overview/calendar/quick-moderation
//! and favorite bulk transfer/removal.
//!
//! Everything here reuses the same application functions the desktop shell
//! calls; the desktop-only side effects (tray refresh, opening a browser
//! window, restarting the app) are intentionally not reproduced.
//!
//! NOT migrated (desktop features, stay local):
//!   * `app__share_collection_open_manage` — opens the share editor in a browser
//!   * `app__ancillary_runtime_snapshot_get` / `app__notification_do_not_disturb_mode_set`
//!     / `app__runtime_group_instances_refresh` / `app__runtime_discord_reconcile_request`
//!     / `app__runtime_background_job_record` — desktop notification/Discord/job layer

use std::sync::{Arc, OnceLock};

use serde::Serialize;
use serde_json::Value as JsonValue;

use vrcx_0_application::avatars::{
    get_my_avatar_by_id, get_my_avatars, MyAvatarByIdInput, MyAvatarsDeps, MyAvatarsInput,
};
use vrcx_0_application::collections::{
    preview_shared_collection, register_world_open_share, share_collection_create,
    ShareCollectionCreateInput, ShareCollectionDeps,
};
use vrcx_0_application::favorites::{
    persist_favorite_cache_snapshot, FavoriteBulkRemoveInput, FavoriteCacheSnapshotInput,
    FavoriteDetailsHydrateInput, FavoriteDetailsRuntime, FavoriteImportStartInput,
    FavoriteStore, FavoriteTransferSelectionInput,
};
use vrcx_0_application::social::{
    self as application, get_group_quick_moderation, get_user_groups_overview,
    load_group_calendar, mark_notifications_seen_batch, resolve_friend_log_names,
    run_group_membership_batch, run_group_quick_moderation_action, run_group_moderation_batch,
    send_instance_invites_batch, sync_notifications, AvatarContentTagsBatchInput,
    FriendLogNameResolutionCoordinator, FriendLogNameResolutionDeps,
    FriendLogNameResolutionInput, GroupCalendarDeps, GroupCalendarInput,
    GroupMembershipBatchInput, GroupBanImportStartInput,
    GroupQuickModerationActionInput, GroupQuickModerationDeps, GroupQuickModerationInput,
    GroupModerationBatchInput, InstanceInviteBatchInput,
    NotificationMarkSeenBatchInput, NotificationSyncDeps,
    UserGroupsOverviewDeps, UserGroupsOverviewInput,
    VrchatBatchMutationActions, VrchatGroupMembershipBatchActions,
    VrchatGroupModerationBatchActions, VrchatInstanceInviteBatchActions,
};
use vrcx_0_application_core::{Error, Result as AppResult};
use vrcx_0_composition::RuntimeHostState;
use vrcx_0_outbound_adapters::{
    CachedWorldNameResolver, LocalAvatarApplicationAdapter, LocalFavoriteStore,
    LocalFriendLogNameStore, LocalNotificationMarkSeenActions, LocalNotificationSyncAdapter,
    LocalWorldCollectionAdapter, VrchatAvatarRemote, VrchatBatchMutationRemoteRequests,
    VrchatGroupMembershipRemoteRequests, VrchatGroupModerationRemoteRequests,
    VrchatGroupRemoteRequests, VrchatInstanceInviteRemoteRequests, VrchatRequestAdapter,
};

use super::{argument, encode, parse_required, CommandResult};
use crate::deps;

pub const COMMANDS: &[&str] = &[
    "app__current_user_refresh",
    "app__ingest_user_facts",
    "app__friend_profile_load_start",
    "app__friend_profile_load_cancel",
    "app__friend_log_names_resolve",
    "app__friend_log_names_cancel",
    "app__my_avatars_get",
    "app__my_avatar_by_id_get",
    "app__vrc_status_get",
    "app__vrc_status_refresh",
    "app__share_collection_create",
    "app__share_collection_preview",
    "app__world_open_register",
    "app__shared_collection_import_start",
    "app__shared_collection_import_status",
    "app__favorite_import_start",
    "app__favorite_import_status",
    "app__favorite_import_cancel",
    "app__favorite_import_dismiss",
    "app__group_ban_import_start",
    "app__group_ban_import_status",
    "app__group_ban_import_cancel",
    "app__favorite_details_hydrate",
    "app__favorite_cache_snapshot",
    "app__avatar_content_tags_batch",
    "app__group_membership_batch",
    "app__group_moderation_batch",
    "app__notification_mark_seen_batch",
    "app__instance_invite_batch",
    "app__notification_sync",
    "app__user_groups_overview_get",
    "app__group_calendar_snapshot_get",
    "app__user_group_quick_moderation_get",
    "app__user_group_quick_moderation_action",
    "app__favorites_transfer_selection",
    "app__favorites_remove_selection",
    "app__backend_runtime_combined_snapshot_get",
];

/// Mirrors the desktop-private `CurrentUserRefreshOutcome` (a plain
/// `{ applied: bool }` camelCase struct), which lives in the desktop-only
/// crate and cannot be imported here.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct CurrentUserRefreshOutcome {
    applied: bool,
}

/// Long-lived friend-log name-resolution coordinator. The desktop shell keeps
/// one on `AppState` so a later `cancel` can find the in-flight request; the
/// server dispatch is otherwise stateless, so a process-wide instance
/// preserves that semantics.
fn friend_log_coordinator() -> &'static FriendLogNameResolutionCoordinator {
    static COORDINATOR: OnceLock<FriendLogNameResolutionCoordinator> = OnceLock::new();
    COORDINATOR.get_or_init(FriendLogNameResolutionCoordinator::default)
}

fn require_active_scope(
    runtime: &RuntimeHostState,
    requirement: &str,
) -> AppResult<vrcx_0_application_core::RuntimeAuthScopeSnapshot> {
    let scope = runtime.desktop_assembly().auth_scope().snapshot();
    if scope.active && !scope.current_user_id.trim().is_empty() {
        Ok(scope)
    } else {
        Err(Error::Custom(format!(
            "{requirement} requires an authenticated session."
        )))
    }
}

fn avatar_remote(
    runtime: &RuntimeHostState,
) -> Arc<dyn vrcx_0_application::avatars::AvatarRemote> {
    let assembly = runtime.desktop_assembly();
    Arc::new(VrchatAvatarRemote::new(
        Arc::clone(runtime.web_client()),
        assembly.diagnostics().clone(),
        assembly.sync().clone(),
    ))
}

pub async fn dispatch(
    runtime: &RuntimeHostState,
    command: &str,
    args: &JsonValue,
) -> Option<CommandResult> {
    let assembly = runtime.desktop_assembly();

    match command {
        // ---------- realtime helpers ----------
        "app__current_user_refresh" => {
            let applied = match runtime
                .realtime_runtime()
                .refresh_current_user_now(JsonValue::Null)
                .await
            {
                Ok(applied) => applied,
                Err(error) => return Some(CommandResult::Failed(error.to_string())),
            };
            Some(encode(Ok(CurrentUserRefreshOutcome { applied })))
        }
        "app__ingest_user_facts" => {
            let entries: Vec<JsonValue> = match argument(args, "entries") {
                Ok(value) => value,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            runtime.realtime_runtime().ingest_user_facts(entries);
            Some(encode(Ok(())))
        }
        "app__friend_profile_load_start" => Some(encode(
            runtime.realtime_runtime().start_friend_profile_bulk_load(),
        )),
        "app__friend_profile_load_cancel" => Some(encode(
            runtime.realtime_runtime().cancel_friend_profile_bulk_load(),
        )),

        // ---------- friend log name resolution ----------
        "app__friend_log_names_resolve" => {
            let input: FriendLogNameResolutionInput = match parse_required(args) {
                Ok(value) => value,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            let store =
                LocalFriendLogNameStore::new(Arc::clone(runtime.database()));
            let deps = FriendLogNameResolutionDeps::new(
                &store,
                assembly.auth_scope(),
                runtime.realtime_runtime(),
            );
            Some(encode(
                resolve_friend_log_names(friend_log_coordinator(), deps, input).await,
            ))
        }
        "app__friend_log_names_cancel" => {
            let request_id: String = match argument(args, "requestId") {
                Ok(value) => value,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(Ok(
                friend_log_coordinator().cancel(&request_id),
            )))
        }

        // ---------- my avatars ----------
        "app__my_avatars_get" | "app__my_avatar_by_id_get" => {
            let expected_scope = match require_active_scope(
                runtime,
                "My avatars query",
            ) {
                Ok(scope) => scope,
                Err(error) => return Some(CommandResult::Failed(error.to_string())),
            };
            let adapter = LocalAvatarApplicationAdapter::new(Arc::clone(runtime.database()));
            let remote = avatar_remote(runtime);
            let auth_scope = assembly.auth_scope().clone();
            if command == "app__my_avatars_get" {
                let input: MyAvatarsInput = match parse_required(args) {
                    Ok(value) => value,
                    Err(error) => return Some(CommandResult::Failed(error)),
                };
                let deps = MyAvatarsDeps::new(&adapter, remote.as_ref(), &auth_scope, expected_scope);
                Some(encode(get_my_avatars(&deps, input).await))
            } else {
                let input: MyAvatarByIdInput = match parse_required(args) {
                    Ok(value) => value,
                    Err(error) => return Some(CommandResult::Failed(error)),
                };
                let deps = MyAvatarsDeps::new(&adapter, remote.as_ref(), &auth_scope, expected_scope);
                Some(encode(get_my_avatar_by_id(&deps, input).await))
            }
        }

        // ---------- vrc status ----------
        "app__vrc_status_get" => {
            Some(encode(Ok(assembly.vrc_status().snapshot())))
        }
        "app__vrc_status_refresh" => {
            Some(encode(assembly.vrc_status().refresh().await))
        }

        // ---------- share collection (minus browser-opening manager) ----------
        "app__share_collection_create" => {
            let input: ShareCollectionCreateInput = match parse_required(args) {
                Ok(value) => value,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            let auth_scope = assembly.auth_scope().snapshot();
            let display_name = runtime.snapshot_backend_runtime().auth_display_name;
            let collections = LocalWorldCollectionAdapter::new(Arc::clone(runtime.database()));
            let deps = ShareCollectionDeps::new(
                &collections,
                &collections,
                &auth_scope.current_user_id,
                &display_name,
            );
            Some(encode(share_collection_create(deps, input).await))
        }
        "app__share_collection_preview" => {
            let id: String = match argument(args, "id") {
                Ok(value) => value,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            let collections = LocalWorldCollectionAdapter::new(Arc::clone(runtime.database()));
            Some(encode(preview_shared_collection(&collections, &id).await))
        }
        "app__world_open_register" => {
            let world_id: String = match argument(args, "worldId") {
                Ok(value) => value,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            let auth_scope = assembly.auth_scope().snapshot();
            let collections = LocalWorldCollectionAdapter::new(Arc::clone(runtime.database()));
            let _ = register_world_open_share(
                &collections,
                &collections,
                &auth_scope.current_user_id,
                &world_id,
            )
            .await;
            Some(encode(Ok(())))
        }
        "app__shared_collection_import_start" => {
            let input = match parse_required(args) {
                Ok(value) => value,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(runtime.shared_collection_import().start(input)))
        }
        "app__shared_collection_import_status" => {
            Some(encode(Ok(runtime.shared_collection_import().status())))
        }

        // ---------- favorite import / group ban import runtimes ----------
        "app__favorite_import_start" => {
            let input: FavoriteImportStartInput = match parse_required(args) {
                Ok(value) => value,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(runtime.favorite_import().start(input)))
        }
        "app__favorite_import_status" => {
            Some(encode(Ok(runtime.favorite_import().status())))
        }
        "app__favorite_import_cancel" => {
            Some(encode(Ok(runtime.favorite_import().cancel())))
        }
        "app__favorite_import_dismiss" => {
            let run_id: String = match argument(args, "runId") {
                Ok(value) => value,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(Ok(runtime.favorite_import().dismiss(&run_id))))
        }
        "app__group_ban_import_start" => {
            let input: GroupBanImportStartInput = match parse_required(args) {
                Ok(value) => value,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(runtime.group_ban_import().start(input)))
        }
        "app__group_ban_import_status" => {
            Some(encode(Ok(runtime.group_ban_import().status())))
        }
        "app__group_ban_import_cancel" => {
            Some(encode(Ok(runtime.group_ban_import().cancel())))
        }

        // ---------- favorite cache / hydrate ----------
        "app__favorite_details_hydrate" => {
            let input: FavoriteDetailsHydrateInput = match parse_required(args) {
                Ok(value) => value,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            let expected_scope =
                match require_active_scope(runtime, "Batch action") {
                    Ok(scope) => scope,
                    Err(error) => {
                        return Some(CommandResult::Failed(error.to_string()))
                    }
                };
            let store: Arc<dyn FavoriteStore> =
                Arc::new(LocalFavoriteStore::new(Arc::clone(runtime.database())));
            let remote: Arc<dyn vrcx_0_application::favorites::FavoriteRemote> = Arc::new(
                vrcx_0_outbound_adapters::VrchatFavoriteRemote::new(
                    Arc::clone(runtime.web_client()),
                    assembly.diagnostics().clone(),
                    assembly.sync().clone(),
                ),
            );
            let runtime_details = FavoriteDetailsRuntime::new(
                store,
                remote,
                assembly.auth_scope().clone(),
                assembly.world_cache().clone(),
            );
            Some(encode(runtime_details.hydrate(input, expected_scope).await))
        }
        "app__favorite_cache_snapshot" => {
            let input: FavoriteCacheSnapshotInput = match parse_required(args) {
                Ok(value) => value,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            let store = LocalFavoriteStore::new(Arc::clone(runtime.database()));
            Some(encode(persist_favorite_cache_snapshot(&store, input)))
        }

        // ---------- frontend batch actions ----------
        "app__avatar_content_tags_batch" => {
            let input: AvatarContentTagsBatchInput = match parse_required(args) {
                Ok(value) => value,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            let expected_scope = match require_active_scope(runtime, "Batch action") {
                Ok(scope) => scope,
                Err(error) => return Some(CommandResult::Failed(error.to_string())),
            };
            let adapter = VrchatRequestAdapter::new(Arc::clone(runtime.web_client()));
            let actions = VrchatBatchMutationActions::new(
                &adapter,
                &VrchatBatchMutationRemoteRequests,
                assembly.auth_scope(),
                expected_scope,
                assembly.remote_mutations(),
            );
            Some(encode(
                application::run_avatar_content_tags_batch(&actions, input).await,
            ))
        }
        "app__group_membership_batch" => {
            let input: GroupMembershipBatchInput = match parse_required(args) {
                Ok(value) => value,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            let expected_scope = match require_active_scope(runtime, "Batch action") {
                Ok(scope) => scope,
                Err(error) => return Some(CommandResult::Failed(error.to_string())),
            };
            let adapter = VrchatRequestAdapter::new(Arc::clone(runtime.web_client()));
            let actions = VrchatGroupMembershipBatchActions::new(
                &adapter,
                &VrchatGroupRemoteRequests,
                assembly.auth_scope(),
                expected_scope,
                assembly.event_bus().clone(),
                assembly.remote_mutations(),
            );
            let coordinator = application::GroupMembershipBatchCoordinator::default();
            Some(encode(
                run_group_membership_batch(&coordinator, &actions, input).await,
            ))
        }
        "app__group_moderation_batch" => {
            let input: GroupModerationBatchInput = match parse_required(args) {
                Ok(value) => value,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            let expected_scope = match require_active_scope(runtime, "Batch action") {
                Ok(scope) => scope,
                Err(error) => return Some(CommandResult::Failed(error.to_string())),
            };
            let adapter = VrchatRequestAdapter::new(Arc::clone(runtime.web_client()));
            let actions = VrchatGroupModerationBatchActions::new(
                &adapter,
                &VrchatGroupModerationRemoteRequests,
                assembly.auth_scope(),
                expected_scope,
                assembly.event_bus().clone(),
                assembly.remote_mutations(),
            );
            let coordinator = application::GroupModerationBatchCoordinator::default();
            Some(encode(
                run_group_moderation_batch(&coordinator, &actions, input).await,
            ))
        }
        "app__notification_mark_seen_batch" => {
            let input: NotificationMarkSeenBatchInput = match parse_required(args) {
                Ok(value) => value,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            let expected_scope = match require_active_scope(runtime, "Batch action") {
                Ok(scope) => scope,
                Err(error) => return Some(CommandResult::Failed(error.to_string())),
            };
            let actions = LocalNotificationMarkSeenActions::new(
                runtime.database().as_ref(),
                runtime.web_client().as_ref(),
                assembly.auth_scope(),
                expected_scope,
                assembly.remote_mutations(),
            );
            Some(encode(mark_notifications_seen_batch(&actions, input).await))
        }
        "app__instance_invite_batch" => {
            let input: InstanceInviteBatchInput = match parse_required(args) {
                Ok(value) => value,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            let expected_scope = match require_active_scope(runtime, "Batch action") {
                Ok(scope) => scope,
                Err(error) => return Some(CommandResult::Failed(error.to_string())),
            };
            let adapter = VrchatRequestAdapter::new(Arc::clone(runtime.web_client()));
            let name_resolver = CachedWorldNameResolver::new(
                assembly.world_cache().clone(),
                Arc::clone(runtime.web_client()),
            );
            let actions = VrchatInstanceInviteBatchActions::new(
                &adapter,
                &VrchatInstanceInviteRemoteRequests,
                assembly.auth_scope(),
                expected_scope,
                assembly.remote_mutations(),
                &name_resolver,
            );
            Some(encode(send_instance_invites_batch(&actions, input).await))
        }
        "app__notification_sync" => {
            let expected_scope = match require_active_scope(runtime, "Batch action") {
                Ok(scope) => scope,
                Err(error) => return Some(CommandResult::Failed(error.to_string())),
            };
            let adapter = LocalNotificationSyncAdapter::new(
                Arc::clone(runtime.database()),
                Arc::clone(runtime.web_client()),
            );
            let deps = NotificationSyncDeps::new(&adapter, assembly.auth_scope(), expected_scope);
            Some(encode(sync_notifications(&deps).await))
        }

        // ---------- group overview / calendar / quick moderation ----------
        "app__user_groups_overview_get" => {
            let input: UserGroupsOverviewInput = match parse_required(args) {
                Ok(value) => value,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            let deps = UserGroupsOverviewDeps {
                groups: deps::group(runtime),
                auth_scope: assembly.auth_scope().clone(),
                remote_requests: Arc::new(VrchatGroupMembershipRemoteRequests),
            };
            Some(encode(get_user_groups_overview(deps, input).await))
        }
        "app__group_calendar_snapshot_get" => {
            let input: GroupCalendarInput = match parse_required(args) {
                Ok(value) => value,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            let deps = GroupCalendarDeps::new(
                Arc::new(vrcx_0_outbound_adapters::VrchatGroupCalendarRemote::new(
                    Arc::clone(runtime.web_client()),
                )),
                assembly.auth_scope().clone(),
                assembly.diagnostics().clone(),
                assembly.sync().clone(),
            );
            Some(encode(load_group_calendar(deps, input).await))
        }
        "app__user_group_quick_moderation_get"
        | "app__user_group_quick_moderation_action" => {
            let deps = GroupQuickModerationDeps {
                groups: deps::group(runtime),
                auth_scope: assembly.auth_scope().clone(),
                remote_requests: Arc::new(VrchatGroupMembershipRemoteRequests),
            };
            if command == "app__user_group_quick_moderation_get" {
                let input: GroupQuickModerationInput = match parse_required(args) {
                    Ok(value) => value,
                    Err(error) => return Some(CommandResult::Failed(error)),
                };
                Some(encode(get_group_quick_moderation(deps, input).await))
            } else {
                let input: GroupQuickModerationActionInput = match parse_required(args) {
                    Ok(value) => value,
                    Err(error) => return Some(CommandResult::Failed(error)),
                };
                Some(encode(run_group_quick_moderation_action(deps, input).await))
            }
        }

        // ---------- favorite bulk transfer / removal ----------
        "app__favorites_transfer_selection" => {
            let input: FavoriteTransferSelectionInput = match parse_required(args) {
                Ok(value) => value,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(
                assembly
                    .favorite_mutations()
                    .transfer_selection(input)
                    .await,
            ))
        }
        "app__favorites_remove_selection" => {
            let input: FavoriteBulkRemoveInput = match parse_required(args) {
                Ok(value) => value,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(
                assembly.favorite_mutations().remove_selection(input).await,
            ))
        }

        // ---------- startup hydration ----------
        // The one-shot snapshot a freshly signed-in client hydrates its
        // friends/favorites/runtime mirrors from. Without it the shell would
        // sit on its loading state forever: the phase events it mirrors were
        // emitted by the server long before this client connected.
        "app__backend_runtime_combined_snapshot_get" => {
            Some(encode(Ok(runtime.backend_runtime_combined_snapshot())))
        }
        _ => None,
    }
}
