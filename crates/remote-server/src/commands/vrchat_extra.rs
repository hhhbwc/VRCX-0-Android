//! Hand-written command table for the `vrchat_remote` / `worlds` / `media`
//! facade commands that the endpoint generator (`generate_endpoint_commands.py`)
//! skips. Two reasons a command lands here rather than in the generated
//! `vrchat_remote.rs`:
//!   * its facade method flattens the parameters into a struct literal the
//!     generator cannot reproduce (`avatar_list_by_user_get` — its
//!     `AvatarListByUserGetInput` turned out to be `pub` in
//!     `vrcx_0_vrchat_client::avatars`, so the arm mirrors the facade literal);
//!   * it routes through a different facade (`runtime_host().worlds()` or
//!     `runtime_host().media()`) that the generator does not scan.
//!
//! Every arm calls the *same* `vrcx_0_vrchat_client` builder the desktop facade
//! ultimately calls, so the network request the server issues is byte-identical
//! to what the client would have sent — and the response re-uses the very
//! `VrchatApiResponse` the local bindings return, so the TypeScript side needs
//! no changes.
//!
//! Derived from:
//!   src-tauri/src/commands/vrchat/avatars/service.rs  (app__vrchat_avatar_styles_get)
//!   src-tauri/src/commands/vrchat/users/service.rs     (app__vrchat_user_get)
//!   src-tauri/src/commands/vrchat/worlds/service.rs    (app__vrchat_world_list_by_user_get,
//!                                                       app__vrchat_world_persistent_data_exists,
//!                                                       app__vrchat_world_save, app__vrchat_world_delete,
//!                                                       app__vrchat_world_publish, app__vrchat_world_unpublish,
//!                                                       app__vrchat_world_persistent_data_delete)
//!   src-tauri/src/commands/vrchat/tools/service.rs     (app__vrchat_tools_invite_messages_get,
//!                                                       app__vrchat_tools_invite_message_edit)

use std::sync::Arc;

use serde::Deserialize;
use serde_json::Value as JsonValue;

use vrcx_0_application::media::{
    collect_inventory_items, InventoryItemsCollectDeps, InventoryItemsCollectInput,
};
use vrcx_0_application::remote::{
    AvatarListSort as ApplicationAvatarListSort, ProfileDecorationEquipSlot,
    QueryOrder as ApplicationQueryOrder, ReleaseStatusFilter as ApplicationReleaseStatusFilter,
};
use vrcx_0_application::social::{favorite_state, set_print_favorite, set_print_favorites};
use vrcx_0_composition::RuntimeHostState;
use vrcx_0_core::vrchat_endpoints::VRCHAT_API_DEFAULT_ENDPOINT;
use vrcx_0_outbound_adapters::{LocalPrintAdapter, VrchatInventoryRemoteRequests};
use vrcx_0_vrchat_client::avatars::{
    avatar_list_by_user_get_input, avatar_styles_get_input, AvatarListByUserGetInput,
};
use vrcx_0_vrchat_client::media::inventory_slot_unequip_input;
use vrcx_0_vrchat_client::query::{QueryOrder, ReleaseStatusFilter, WorldSearchSort};
use vrcx_0_vrchat_client::tools::{
    invite_message_edit_input, invite_messages_get_input, InviteMessageType,
};
use vrcx_0_vrchat_client::users::user_get_input;
use vrcx_0_vrchat_client::worlds::{
    world_delete_input, world_list_by_user_get_input, world_persistent_data_delete_input,
    world_persistent_data_exists_input, world_publish_input, world_save_input,
    world_unpublish_input, WorldUpdateRequest,
};

use super::{blocking, encode, parse_required, send, CommandResult, IntoApiRequest};
use super::vrchat_remote_conversions::{
    profile_decoration_equip_slot, query_order, release_status_filter,
};

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct UserIdInput {
    #[serde(default)]
    user_id: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct WorldIdInput {
    #[serde(default)]
    world_id: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct WorldPersistentDataInput {
    #[serde(default)]
    user_id: String,
    #[serde(default)]
    world_id: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct InviteMessagesInput {
    #[serde(default)]
    current_user_id: String,
    message_type: InviteMessageType,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct InviteMessageEditInput {
    #[serde(default)]
    current_user_id: String,
    message_type: InviteMessageType,
    #[serde(default)]
    slot: i32,
    #[serde(default)]
    message: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct WorldListByUserInput {
    #[serde(default)]
    user_id: String,
    #[serde(default)]
    n: i32,
    #[serde(default)]
    offset: i32,
    sort: WorldSearchSort,
    order: QueryOrder,
    release_status: ReleaseStatusFilter,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct WorldSaveInput {
    #[serde(default)]
    world_id: String,
    params: WorldUpdateRequest,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AvatarListByUserInput {
    #[serde(default)]
    user_id: String,
    #[serde(default)]
    user: String,
    #[serde(default)]
    n: i32,
    #[serde(default)]
    offset: i32,
    sort: ApplicationAvatarListSort,
    order: ApplicationQueryOrder,
    release_status: ApplicationReleaseStatusFilter,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PrintFavoriteSetInput {
    #[serde(default)]
    print_id: String,
    #[serde(default)]
    favorite: bool,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PrintFavoritesSetInput {
    #[serde(default)]
    print_ids: Vec<String>,
    #[serde(default)]
    favorite: bool,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ProfileDecorationUnequipInput {
    equip_slot: ProfileDecorationEquipSlot,
}

/// Mirrors the desktop facade's private converter
/// (`runtime-host-desktop/src/vrchat_remote.rs`), which the generator did not
/// pick up because the generated conversions file only carries the fns the
/// generated arms reference.
fn avatar_list_sort(value: ApplicationAvatarListSort) -> vrcx_0_vrchat_client::query::AvatarListSort {
    match value {
        ApplicationAvatarListSort::Created => vrcx_0_vrchat_client::query::AvatarListSort::Created,
        ApplicationAvatarListSort::Updated => vrcx_0_vrchat_client::query::AvatarListSort::Updated,
        ApplicationAvatarListSort::Order => vrcx_0_vrchat_client::query::AvatarListSort::Order,
        ApplicationAvatarListSort::CreatedAt => {
            vrcx_0_vrchat_client::query::AvatarListSort::CreatedAt
        }
        ApplicationAvatarListSort::UpdatedAt => {
            vrcx_0_vrchat_client::query::AvatarListSort::UpdatedAt
        }
    }
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
    let web = runtime.web_client();
    let endpoint = VRCHAT_API_DEFAULT_ENDPOINT.to_string();

    // The print-favorites store reads/writes the server's own SQLite through
    // the same adapter the desktop media facade builds (`db` + `web`); making
    // it per call is two Arc clones and keeps every arm self-contained.
    let print_adapter = LocalPrintAdapter::new(
        Arc::clone(runtime.database()),
        Arc::clone(runtime.web_client()),
    );

    match command {
        "app__vrchat_avatar_styles_get" => Some(
            send(
                web,
                avatar_styles_get_input(endpoint.clone()).into_api_request(),
            )
            .await,
        ),
        "app__vrchat_user_get" => {
            let input: UserIdInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    user_get_input(endpoint.clone(), input.user_id).into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_world_list_by_user_get" => {
            let input: WorldListByUserInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    world_list_by_user_get_input(
                        endpoint.clone(),
                        input.user_id,
                        input.n,
                        input.offset,
                        input.sort,
                        input.order,
                        input.release_status,
                    )
                    .into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_world_persistent_data_exists" => {
            let input: WorldPersistentDataInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    world_persistent_data_exists_input(
                        endpoint.clone(),
                        input.user_id,
                        input.world_id,
                    )
                    .into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_world_save" => {
            let input: WorldSaveInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    world_save_input(endpoint.clone(), input.world_id, input.params)
                        .into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_world_delete" => {
            let input: WorldIdInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    world_delete_input(endpoint.clone(), input.world_id).into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_world_publish" => {
            let input: WorldIdInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    world_publish_input(endpoint.clone(), input.world_id).into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_world_unpublish" => {
            let input: WorldIdInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    world_unpublish_input(endpoint.clone(), input.world_id).into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_world_persistent_data_delete" => {
            let input: WorldPersistentDataInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    world_persistent_data_delete_input(
                        endpoint.clone(),
                        input.user_id,
                        input.world_id,
                    )
                    .into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_tools_invite_messages_get" => {
            let input: InviteMessagesInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    invite_messages_get_input(endpoint.clone(), input.current_user_id, input.message_type)
                        .into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_tools_invite_message_edit" => {
            let input: InviteMessageEditInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    invite_message_edit_input(
                        endpoint.clone(),
                        input.current_user_id,
                        input.message_type,
                        input.slot,
                        input.message,
                    )
                    .into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_avatar_list_by_user_get" => {
            let input: AvatarListByUserInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            // Mirrors the desktop facade's `avatars_by_user` struct literal.
            Some(
                send(
                    web,
                    avatar_list_by_user_get_input(AvatarListByUserGetInput {
                        endpoint: endpoint.clone(),
                        user_id: input.user_id,
                        user: input.user,
                        n: input.n,
                        offset: input.offset,
                        sort: avatar_list_sort(input.sort),
                        order: query_order(input.order),
                        release_status: release_status_filter(input.release_status),
                    })
                    .into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_prints_favorites_list" => {
            Some(encode(blocking(|| favorite_state(&print_adapter))))
        }
        "app__vrchat_prints_favorite_set" => {
            let input: PrintFavoriteSetInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(blocking(|| {
                set_print_favorite(&print_adapter, &input.print_id, input.favorite)
            })))
        }
        "app__vrchat_prints_favorites_set" => {
            let input: PrintFavoritesSetInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(blocking(|| {
                set_print_favorites(&print_adapter, &input.print_ids, input.favorite)
            })))
        }
        "app__vrchat_media_inventory_items_collect" => {
            let input: InventoryItemsCollectInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            // Same session guard the desktop media facade applies before the
            // paged collect.
            let assembly = runtime.desktop_assembly();
            let auth_scope = assembly.auth_scope().clone();
            let expected_scope = auth_scope.snapshot();
            if !expected_scope.active || expected_scope.current_user_id.trim().is_empty() {
                return Some(CommandResult::Failed(
                    "Inventory collect requires an authenticated session.".to_string(),
                ));
            }
            let remote = VrchatInventoryRemoteRequests::new(Arc::clone(web));
            Some(encode(
                collect_inventory_items(
                    &InventoryItemsCollectDeps::new(&remote, &auth_scope, expected_scope),
                    input,
                )
                .await,
            ))
        }
        "app__vrchat_media_profile_decoration_unequip" => {
            let input: ProfileDecorationUnequipInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    inventory_slot_unequip_input(
                        endpoint.clone(),
                        profile_decoration_equip_slot(input.equip_slot),
                    )
                    .into_api_request(),
                )
                .await,
            )
        }
        _ => None,
    }
}

/// Every command answered here, for the capability list on `/v1/health`.
///
/// The client asks once which commands exist and then only forwards those,
/// so adding a command here is enough for the client to start using it.
pub const COMMANDS: &[&str] = &[
    "app__vrchat_avatar_styles_get",
    "app__vrchat_user_get",
    "app__vrchat_world_list_by_user_get",
    "app__vrchat_world_persistent_data_exists",
    "app__vrchat_world_save",
    "app__vrchat_world_delete",
    "app__vrchat_world_publish",
    "app__vrchat_world_unpublish",
    "app__vrchat_world_persistent_data_delete",
    "app__vrchat_tools_invite_messages_get",
    "app__vrchat_tools_invite_message_edit",
    "app__vrchat_avatar_list_by_user_get",
    "app__vrchat_prints_favorites_list",
    "app__vrchat_prints_favorite_set",
    "app__vrchat_prints_favorites_set",
    "app__vrchat_media_inventory_items_collect",
    "app__vrchat_media_profile_decoration_unequip",
];
