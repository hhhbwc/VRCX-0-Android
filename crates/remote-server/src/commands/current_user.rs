//! Current-user mutation commands, executed server-side.
//!
//! The desktop build wires `CurrentUserMutationRuntime` to a port that lives
//! in `runtime-host-desktop` (its only desktop-specific dependency is the
//! realtime host runtime for query-cache invalidation). The server
//! re-implements the same port against pieces `composition` already shares:
//! the shared `WebClient`, the assembly's diagnostics/sync pair, and
//! `RuntimeHostState::realtime_runtime()`. Everything else — auth capture,
//! the remote-mutation gate, the 250 ms mutation spacing, the
//! invalidate-on-2xx policy — is the runtime's own logic, reused verbatim.

use std::sync::Arc;

use serde_json::Value as JsonValue;

use vrcx_0_application::social::{
    ContentFilter as ApplicationContentFilter, CurrentUserMutationFuture, CurrentUserMutationPort,
    CurrentUserMutationRequest, CurrentUserMutationRuntime,
    CurrentUserProfileUpdateRequest as ApplicationProfileUpdateRequest,
    CurrentUserQueryInvalidationFuture, CurrentUserUpdateRequest as ApplicationUserUpdateRequest,
    VrchatCurrentUserBadgeInput, VrchatCurrentUserProfileUpdateInput, VrchatCurrentUserTagsInput,
    VrchatCurrentUserUpdateInput,
};
use vrcx_0_application_core::vrchat_api::{execute_api_command, VrchatScope};
use vrcx_0_application_core::{
    RuntimeAuthScopeSnapshot, RuntimeDiagnostics, RuntimeSyncEngine, WebClient,
};
use vrcx_0_application_realtime::RealtimeHostRuntime;
use vrcx_0_composition::RuntimeHostState;
use vrcx_0_vrchat_client::users::{
    current_user_badge_update_input, current_user_tags_add_input, current_user_tags_remove_input,
    current_user_update_input, profile_update_input, ContentFilter as ProtocolContentFilter,
    CurrentUserProfileUpdateRequest as ProtocolProfileUpdateRequest,
    CurrentUserUpdateRequest as ProtocolUserUpdateRequest,
};

use super::{encode, parse_required, CommandResult};

pub const COMMANDS: &[&str] = &[
    "app__vrchat_current_user_profile_update",
    "app__vrchat_current_user_update",
    "app__vrchat_current_user_badge_update",
    "app__vrchat_current_user_tags_add",
    "app__vrchat_current_user_tags_remove",
];

/// Assembles the shared `CurrentUserMutationRuntime` on top of a server-side
/// port. The desktop's `current_user_mutations()` accessor is not reachable
/// from here (`DesktopRuntimeHostState` owns it), so the server builds the
/// same runtime from the same assembly pieces.
pub fn current_user_mutation(runtime: &RuntimeHostState) -> CurrentUserMutationRuntime {
    let assembly = runtime.desktop_assembly();
    CurrentUserMutationRuntime::new(
        assembly.auth_scope().clone(),
        Arc::clone(assembly.remote_mutations()),
        Arc::new(ServerCurrentUserMutationPort {
            web: Arc::clone(runtime.web_client()),
            diagnostics: assembly.diagnostics().clone(),
            sync: assembly.sync().clone(),
            realtime: Arc::clone(runtime.realtime_runtime()),
        }),
    )
}

struct ServerCurrentUserMutationPort {
    web: Arc<WebClient>,
    diagnostics: RuntimeDiagnostics,
    sync: RuntimeSyncEngine,
    realtime: Arc<RealtimeHostRuntime>,
}

impl CurrentUserMutationPort for ServerCurrentUserMutationPort {
    fn execute<'a>(
        &'a self,
        scope: RuntimeAuthScopeSnapshot,
        request: CurrentUserMutationRequest,
    ) -> CurrentUserMutationFuture<'a> {
        Box::pin(async move {
            let (command, detail, request) = match request {
                CurrentUserMutationRequest::Profile(params) => {
                    let (user_id, request) = profile_update_input(
                        scope.endpoint,
                        scope.current_user_id,
                        profile_update_request(params),
                    )?;
                    (
                        "app__vrchat_current_user_profile_update",
                        format!("Updating profile for current user {user_id}."),
                        request,
                    )
                }
                CurrentUserMutationRequest::User(params) => {
                    let (user_id, request) = current_user_update_input(
                        scope.endpoint,
                        scope.current_user_id,
                        user_update_request(params),
                    )?;
                    (
                        "app__vrchat_current_user_update",
                        format!("Updating current user {user_id}."),
                        request,
                    )
                }
                CurrentUserMutationRequest::Badge {
                    badge_id,
                    hidden,
                    showcased,
                } => {
                    let (user_id, badge_id, request) = current_user_badge_update_input(
                        scope.endpoint,
                        scope.current_user_id,
                        badge_id,
                        hidden,
                        showcased,
                    )?;
                    (
                        "app__vrchat_current_user_badge_update",
                        format!("Updating badge {badge_id} for current user {user_id}."),
                        request,
                    )
                }
                CurrentUserMutationRequest::AddTags(tags) => {
                    let (user_id, request) =
                        current_user_tags_add_input(scope.endpoint, scope.current_user_id, tags)?;
                    (
                        "app__vrchat_current_user_tags_add",
                        format!("Adding tags to current user {user_id}."),
                        request,
                    )
                }
                CurrentUserMutationRequest::RemoveTags(tags) => {
                    let (user_id, request) = current_user_tags_remove_input(
                        scope.endpoint,
                        scope.current_user_id,
                        tags,
                    )?;
                    (
                        "app__vrchat_current_user_tags_remove",
                        format!("Removing tags from current user {user_id}."),
                        request,
                    )
                }
            };
            execute_api_command(
                &self.web,
                &self.diagnostics,
                &self.sync,
                (command, detail),
                request,
                VrchatScope::Vrchat,
            )
            .await
        })
    }

    fn invalidate_user_query<'a>(
        &'a self,
        scope: RuntimeAuthScopeSnapshot,
    ) -> CurrentUserQueryInvalidationFuture<'a> {
        Box::pin(async move {
            self.realtime
                .invalidate_user_query_cache(&scope.endpoint, &scope.current_user_id)
                .await;
        })
    }
}

// -- request conversions -------------------------------------------------
// Copied from `runtime-host-desktop/src/current_user_mutation.rs` (private
// there; the server cannot depend on that crate). Keep in sync with the
// facade — the application types and the client types are structurally
// identical shadow copies.

fn profile_update_request(
    request: ApplicationProfileUpdateRequest,
) -> ProtocolProfileUpdateRequest {
    match request {
        ApplicationProfileUpdateRequest::Default => ProtocolProfileUpdateRequest::Default,
        ApplicationProfileUpdateRequest::Gradient {
            background_gradient_bottom,
            background_gradient_top,
        } => ProtocolProfileUpdateRequest::Gradient {
            background_gradient_bottom,
            background_gradient_top,
        },
        ApplicationProfileUpdateRequest::Texture {
            background_texture_id,
        } => ProtocolProfileUpdateRequest::Texture {
            background_texture_id,
        },
    }
}

fn user_update_request(request: ApplicationUserUpdateRequest) -> ProtocolUserUpdateRequest {
    ProtocolUserUpdateRequest {
        home_location: request.home_location,
        status: request.status,
        status_description: request.status_description,
        bio: request.bio,
        bio_links: request.bio_links,
        pronouns: request.pronouns,
        user_icon: request.user_icon,
        profile_pic_override: request.profile_pic_override,
        allow_avatar_copying: request.allow_avatar_copying,
        is_booping_enabled: request.is_booping_enabled,
        has_shared_connections_opt_out: request.has_shared_connections_opt_out,
        has_discord_friends_opt_out: request.has_discord_friends_opt_out,
        content_filters: request
            .content_filters
            .map(|filters| filters.into_iter().map(content_filter).collect()),
    }
}

fn content_filter(filter: ApplicationContentFilter) -> ProtocolContentFilter {
    match filter {
        ApplicationContentFilter::Adult => ProtocolContentFilter::Adult,
        ApplicationContentFilter::Gore => ProtocolContentFilter::Gore,
        ApplicationContentFilter::Horror => ProtocolContentFilter::Horror,
        ApplicationContentFilter::Sex => ProtocolContentFilter::Sex,
        ApplicationContentFilter::Violence => ProtocolContentFilter::Violence,
    }
}

// -- dispatch ------------------------------------------------------------

pub async fn dispatch(
    runtime: &RuntimeHostState,
    command: &str,
    args: &JsonValue,
) -> Option<CommandResult> {
    if !COMMANDS.contains(&command) {
        return None;
    }
    let mutations = current_user_mutation(runtime);
    match command {
        "app__vrchat_current_user_profile_update" => {
            let input: VrchatCurrentUserProfileUpdateInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(mutations.update_profile(input).await))
        }
        "app__vrchat_current_user_update" => {
            let input: VrchatCurrentUserUpdateInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(mutations.update_user(input).await))
        }
        "app__vrchat_current_user_badge_update" => {
            let input: VrchatCurrentUserBadgeInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(mutations.update_badge(input).await))
        }
        "app__vrchat_current_user_tags_add" => {
            let input: VrchatCurrentUserTagsInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(mutations.add_tags(input).await))
        }
        "app__vrchat_current_user_tags_remove" => {
            let input: VrchatCurrentUserTagsInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(mutations.remove_tags(input).await))
        }
        _ => None,
    }
}
