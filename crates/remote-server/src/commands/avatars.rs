//! Hand-written command table for the avatar remote-mutation commands.
//!
//! These are mutations: each builds an `AvatarRemoteMutation` enum and a
//! human-readable `detail` from its input, then calls the same `application`
//! function the desktop facade calls. They cannot go through the generated
//! endpoint table because the application function takes the command name, a
//! detail string and a mutation enum rather than a single `input` value, so the
//! request shape is assembled here from the facade source rather than derived.
//!
//! Derived from `crates/runtime-host-desktop/src/avatar.rs`:
//!   app__vrchat_avatar_delete
//!   app__vrchat_avatar_impostor_create
//!   app__vrchat_avatar_impostor_delete
//!   app__vrchat_avatar_moderation_delete
//!   app__vrchat_avatar_moderation_send
//!   app__vrchat_avatar_save
//!   app__vrchat_avatar_select
//!   app__vrchat_avatar_select_fallback
//!   app__vrchat_avatar_moderations_get

use serde::Deserialize;
use serde_json::Value as JsonValue;
use std::sync::Arc;

use vrcx_0_application::avatars::{
    self as application, AvatarModerationDeps, AvatarRemote, AvatarRemoteMutation,
    AvatarRemoteMutationDeps,
};
use vrcx_0_application::remote::AvatarUpdateRequest as ApplicationAvatarUpdateRequest;
use vrcx_0_application_realtime::{
    CURRENT_USER_AVATAR_RESPONSE_AUTHORITY_FIELDS, CURRENT_USER_FALLBACK_AVATAR_RESPONSE_AUTHORITY_FIELDS,
};
use vrcx_0_composition::RuntimeHostState;
use vrcx_0_outbound_adapters::{LocalAvatarApplicationAdapter, VrchatAvatarRemote};

use super::{encode, parse_required, CommandResult};
use crate::deps;

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AvatarIdInput {
    avatar_id: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AvatarSaveInput {
    avatar_id: String,
    params: ApplicationAvatarUpdateRequest,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AvatarModerationInput {
    avatar_id: String,
}

/// Runs one of these commands, or `None` when the name belongs to no one here.
///
/// `None` is the signal to keep looking — and ultimately to answer `501`, which
/// is what tells the client to run the command against its own runtime.
pub async fn dispatch(
    runtime: &RuntimeHostState,
    command: &str,
    args: &JsonValue,
) -> Option<CommandResult> {
    // Assembled once per call. The mutation arms borrow these; the moderation
    // getter reuses the remote stub. The cost is trivial next to the network
    // round trip the application function makes.
    let assembly = runtime.desktop_assembly();
    let adapter = LocalAvatarApplicationAdapter::new(Arc::clone(runtime.database()));
    let remote: Arc<dyn AvatarRemote> = Arc::new(VrchatAvatarRemote::new(
        Arc::clone(runtime.web_client()),
        assembly.diagnostics().clone(),
        assembly.sync().clone(),
    ));

    match command {
        "app__vrchat_avatar_delete" => {
            let input: AvatarIdInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            let avatar_id = input.avatar_id.trim().to_string();
            let deps = match mutation_deps(&adapter, &remote, runtime) {
                Ok(deps) => deps,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(
                application::delete_avatar(
                    &deps,
                    avatar_id.clone(),
                    "app__vrchat_avatar_delete",
                    format!("Deleting avatar {avatar_id}."),
                    AvatarRemoteMutation::Delete { avatar_id },
                )
                .await,
            ))
        }
        "app__vrchat_avatar_impostor_create" => {
            let input: AvatarIdInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            let avatar_id = input.avatar_id.trim().to_string();
            let deps = match mutation_deps(&adapter, &remote, runtime) {
                Ok(deps) => deps,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(
                application::execute_avatar_remote_mutation(
                    &deps,
                    "app__vrchat_avatar_impostor_create",
                    format!("Creating avatar impostor for {avatar_id}."),
                    AvatarRemoteMutation::CreateImpostor { avatar_id },
                )
                .await,
            ))
        }
        "app__vrchat_avatar_impostor_delete" => {
            let input: AvatarIdInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            let avatar_id = input.avatar_id.trim().to_string();
            let deps = match mutation_deps(&adapter, &remote, runtime) {
                Ok(deps) => deps,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(
                application::execute_avatar_remote_mutation(
                    &deps,
                    "app__vrchat_avatar_impostor_delete",
                    format!("Deleting avatar impostor for {avatar_id}."),
                    AvatarRemoteMutation::DeleteImpostor { avatar_id },
                )
                .await,
            ))
        }
        "app__vrchat_avatar_moderation_delete" => {
            let input: AvatarModerationInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            let avatar_id = input.avatar_id.trim().to_string();
            let deps = match mutation_deps(&adapter, &remote, runtime) {
                Ok(deps) => deps,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(
                application::execute_avatar_moderation_mutation(
                    &deps,
                    "app__vrchat_avatar_moderation_delete",
                    format!("Deleting avatar moderation block for {avatar_id}."),
                    AvatarRemoteMutation::DeleteModeration { avatar_id },
                )
                .await,
            ))
        }
        "app__vrchat_avatar_moderation_send" => {
            let input: AvatarModerationInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            let avatar_id = input.avatar_id.trim().to_string();
            let deps = match mutation_deps(&adapter, &remote, runtime) {
                Ok(deps) => deps,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(
                application::execute_avatar_moderation_mutation(
                    &deps,
                    "app__vrchat_avatar_moderation_send",
                    format!("Sending avatar moderation block for {avatar_id}."),
                    AvatarRemoteMutation::SendModeration { avatar_id },
                )
                .await,
            ))
        }
        "app__vrchat_avatar_save" => {
            let input: AvatarSaveInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            let avatar_id = input.avatar_id.trim().to_string();
            let deps = match mutation_deps(&adapter, &remote, runtime) {
                Ok(deps) => deps,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(
                application::save_avatar(
                    &deps,
                    "app__vrchat_avatar_save",
                    format!("Saving avatar {avatar_id}."),
                    AvatarRemoteMutation::Save {
                        avatar_id,
                        params: input.params,
                    },
                )
                .await,
            ))
        }
        "app__vrchat_avatar_select" => {
            let input: AvatarIdInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            let avatar_id = input.avatar_id.trim().to_string();
            let deps = match mutation_deps(&adapter, &remote, runtime) {
                Ok(deps) => deps,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(
                application::select_avatar(
                    &deps,
                    "app__vrchat_avatar_select",
                    format!("Selecting avatar {avatar_id}."),
                    AvatarRemoteMutation::Select {
                        avatar_id,
                        fallback: false,
                    },
                    CURRENT_USER_AVATAR_RESPONSE_AUTHORITY_FIELDS,
                )
                .await,
            ))
        }
        "app__vrchat_avatar_select_fallback" => {
            let input: AvatarIdInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            let avatar_id = input.avatar_id.trim().to_string();
            let deps = match mutation_deps(&adapter, &remote, runtime) {
                Ok(deps) => deps,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(
                application::select_avatar(
                    &deps,
                    "app__vrchat_avatar_select_fallback",
                    format!("Selecting fallback avatar {avatar_id}."),
                    AvatarRemoteMutation::Select {
                        avatar_id,
                        fallback: true,
                    },
                    CURRENT_USER_FALLBACK_AVATAR_RESPONSE_AUTHORITY_FIELDS,
                )
                .await,
            ))
        }
        "app__vrchat_avatar_moderations_get" => {
            let moderation = assembly.avatar_moderation();
            let deps: AvatarModerationDeps<'_> = deps::avatar_moderation(&remote, assembly.auth_scope());
            Some(encode(
                application::get_avatar_moderations(
                    moderation,
                    deps,
                    "app__vrchat_avatar_moderations_get",
                    "Getting avatar moderations.",
                )
                .await,
            ))
        }
        _ => None,
    }
}

/// Builds the mutation deps, threading the per-call adapter/remote through the
/// shared [`deps::avatar_mutation`] helper. Lives here rather than as a closure
/// so the borrow lifetimes stay explicit and the arm bodies stay readable.
fn mutation_deps<'a>(
    adapter: &'a LocalAvatarApplicationAdapter,
    remote: &'a Arc<dyn AvatarRemote>,
    runtime: &'a RuntimeHostState,
) -> Result<AvatarRemoteMutationDeps<'a>, String> {
    deps::avatar_mutation(
        adapter,
        remote,
        runtime.desktop_assembly().auth_scope(),
        runtime.desktop_assembly().remote_mutations(),
        runtime.realtime_runtime(),
        runtime.desktop_assembly().avatar_cache(),
        runtime.desktop_assembly().avatar_moderation(),
    )
}

/// Every command answered here, for the capability list on `/v1/health`.
pub const COMMANDS: &[&str] = &[
    "app__vrchat_avatar_delete",
    "app__vrchat_avatar_impostor_create",
    "app__vrchat_avatar_impostor_delete",
    "app__vrchat_avatar_moderation_delete",
    "app__vrchat_avatar_moderation_send",
    "app__vrchat_avatar_save",
    "app__vrchat_avatar_select",
    "app__vrchat_avatar_select_fallback",
    "app__vrchat_avatar_moderations_get",
];
