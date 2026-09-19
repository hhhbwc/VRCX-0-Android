//! Hand-written command table for the remote favorite-mutation commands.
//!
//! Each command forwards a typed `FavoriteRemote*Input` to the same
//! `FavoriteMutationCoordinator` method the desktop facade calls
//! (`crates/runtime-host-desktop/src/state.rs`'s `favorite_*_remote`). The
//! coordinator performs its own authenticated-mutation capture, so the only
//! server-side work is assembling the coordinator from `RuntimeHostState` and
//! mapping the incoming JSON into the application input — there is no separate
//! `detail`/mutation-enum shape to reproduce as with avatars.
//!
//! Derived from `crates/runtime-host-desktop/src/state.rs`:
//!   app__vrchat_favorite_add
//!   app__vrchat_favorite_delete
//!   app__vrchat_favorite_group_save
//!   app__vrchat_favorite_group_clear

use serde::Deserialize;
use serde_json::Value as JsonValue;

use vrcx_0_application::favorites::{
    FavoriteRemoteAddInput, FavoriteRemoteDeleteInput, FavoriteRemoteGroupClearInput,
    FavoriteRemoteGroupSaveInput,
};
use vrcx_0_application_core::{FavoriteEntityKind, FavoriteGroupVisibility, VrchatFavoriteType};
use vrcx_0_composition::RuntimeHostState;

use super::{argument, encode, parse_required, CommandResult};
use crate::deps;

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct FavoriteAddInput {
    #[serde(rename = "type")]
    type_name: VrchatFavoriteType,
    #[serde(default)]
    favorite_id: String,
    #[serde(default)]
    tags: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct FavoriteDeleteInput {
    #[serde(default)]
    object_id: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct FavoriteGroupSaveInput {
    #[serde(rename = "type")]
    type_name: VrchatFavoriteType,
    #[serde(default)]
    group: String,
    display_name: Option<String>,
    visibility: Option<FavoriteGroupVisibility>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct FavoriteGroupClearInput {
    #[serde(rename = "type")]
    type_name: VrchatFavoriteType,
    #[serde(default)]
    group: String,
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
    // The coordinator owns its store/remote/runtime handles, so building it
    // once per call is cheap and keeps every arm a single `encode(...)` line.
    let coordinator = deps::favorite_mutation(runtime);
    const LABEL: &str = "Remote favorite mutation";

    match command {
        "app__vrchat_favorite_add" => {
            let input: FavoriteAddInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(
                coordinator
                    .add_remote(
                        LABEL,
                        FavoriteRemoteAddInput {
                            kind: input.type_name,
                            entity_id: input.favorite_id,
                            tags: input.tags,
                        },
                    )
                    .await,
            ))
        }
        "app__vrchat_favorite_delete" => {
            let input: FavoriteDeleteInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(
                coordinator
                    .delete_remote(LABEL, FavoriteRemoteDeleteInput { object_id: input.object_id })
                    .await,
            ))
        }
        "app__vrchat_favorite_group_save" => {
            let input: FavoriteGroupSaveInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(
                coordinator
                    .save_remote_group(
                        LABEL,
                        FavoriteRemoteGroupSaveInput {
                            kind: input.type_name,
                            group: input.group,
                            display_name: input.display_name,
                            visibility: input.visibility,
                        },
                    )
                    .await,
            ))
        }
        "app__vrchat_favorite_group_clear" => {
            let input: FavoriteGroupClearInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(
                coordinator
                    .clear_remote_group(
                        LABEL,
                        FavoriteRemoteGroupClearInput {
                            kind: input.type_name,
                            group: input.group,
                        },
                    )
                    .await,
            ))
        }
        // Local favorite *groups* are written into the server's SQLite through
        // `FavoriteMutationCoordinator` (the same coordinator the remote-mutation
        // arms use). They are reached via `desktop_assembly().favorite_mutations()`
        // because the `DesktopRuntimeHostState` wrapper method is desktop-only.
        "app__local_favorite_group_create" => {
            let kind: FavoriteEntityKind = match argument(args, "kind") {
                Ok(value) => value,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            let group_name = match argument(args, "groupName") {
                Ok(value) => value,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            let assembly = runtime.desktop_assembly();
            Some(encode(assembly.favorite_mutations().create_local_group(kind, group_name)))
        }
        "app__local_favorite_group_rename" => {
            let kind: FavoriteEntityKind = match argument(args, "kind") {
                Ok(value) => value,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            let group_name = match argument(args, "groupName") {
                Ok(value) => value,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            let new_group_name = match argument(args, "newGroupName") {
                Ok(value) => value,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            let assembly = runtime.desktop_assembly();
            Some(encode(assembly.favorite_mutations().rename_local_group(
                kind,
                group_name,
                new_group_name,
            )))
        }
        "app__local_favorite_group_delete" => {
            let kind: FavoriteEntityKind = match argument(args, "kind") {
                Ok(value) => value,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            let group_name = match argument(args, "groupName") {
                Ok(value) => value,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            let assembly = runtime.desktop_assembly();
            Some(encode(assembly.favorite_mutations().delete_local_group(kind, group_name)))
        }
        _ => None,
    }
}

/// Every command answered here, for the capability list on `/v1/health`.
///
/// The client asks once which commands exist and then only forwards those,
/// so adding a command here is enough for the client to start using it.
pub const COMMANDS: &[&str] = &[
    "app__vrchat_favorite_add",
    "app__vrchat_favorite_delete",
    "app__vrchat_favorite_group_save",
    "app__vrchat_favorite_group_clear",
    "app__local_favorite_group_create",
    "app__local_favorite_group_rename",
    "app__local_favorite_group_delete",
];
