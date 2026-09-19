//! GENERATED FILE - do not edit by hand.
//!
//! Commands that map straight onto a `vrcx_0_vrchat_client` request builder.
//! Derived from the desktop layer so the two cannot drift:
//!   facade:  crates/runtime-host-desktop/src/vrchat_remote.rs
//!   service: src-tauri/src/commands/vrchat/users/service.rs
//!          src-tauri/src/commands/vrchat/friends/service.rs
//!          src-tauri/src/commands/vrchat/instances/service.rs
//!          src-tauri/src/commands/vrchat/favorites/service.rs
//!          src-tauri/src/commands/vrchat/search/service.rs
//!          src-tauri/src/commands/vrchat/notifications/service.rs
//!          src-tauri/src/commands/vrchat/tools/service.rs
//!          src-tauri/src/commands/vrchat/avatars/service.rs
//!          src-tauri/src/commands/vrchat/media/service.rs
//!          src-tauri/src/commands/vrchat/auth/service.rs
//!          src-tauri/src/commands/vrchat/worlds/service.rs

#![allow(unused_imports)]
// The facade's client imports come across wholesale: an arm may need a helper
// the facade used when shaping the request (`media_file_list_params`, say), and
// which of them survive depends on which commands resolved.

use serde_json::Value as JsonValue;

use vrcx_0_composition::RuntimeHostState;
use vrcx_0_core::vrchat_endpoints::VRCHAT_API_DEFAULT_ENDPOINT;
use vrcx_0_vrchat_client::{auth,avatars,favorites,friends,instances,media,notifications,search,tools,users};
use vrcx_0_vrchat_client::auth::{ current_user_get_input, file_analysis_get_input, visits_get_input, };
use vrcx_0_vrchat_client::avatars::{ avatar_file_get_input, avatar_gallery_get_input, avatar_list_by_user_get_input, avatar_styles_get_input, AvatarListByUserGetInput, };
use vrcx_0_vrchat_client::favorites::{favorite_groups_get_input, favorite_worlds_get_input};
use vrcx_0_vrchat_client::friends::friend_status_get_input;
use vrcx_0_vrchat_client::instances::{ instance_close_input, instance_create_input, instance_get_input, instance_self_invite_input, instance_short_name_get_input, InstanceCreateGroupAccessType, InstanceCreateMinimumAvatarPerformance, InstanceCreateRegion, InstanceCreateRequest, InstanceCreateType, };
use vrcx_0_vrchat_client::media::{ asset_upload_input, avatar_gallery_image_upload_input, file_delete_input, files_get_input, image_upload_input, inventory_bundle_consume_input, inventory_item_equip_input, inventory_item_update_input, inventory_items_get_input, inventory_slot_unequip_input, inventory_template_get_input, print_delete_input, print_get_input, print_upload_input, prints_get_input, reward_redeem_input, sticker_upload_input, tagged_image_upload_input, user_inventory_item_get_input, EmojiLoopStyle, EmojiUploadParams, ImageAnimationStyle, ImageMaskTag, InventoryItemUpdateRequest, InventoryListParams, InventoryOrder, MediaAssetUploadRequest, MediaFileListParams, MediaFileTag, PrintUploadParams, ProfileDecorationEquipSlot, };
use vrcx_0_vrchat_client::notifications::{ boop_send_input, request_invite_photo_input, request_invite_send_input, RequestInviteRequest, };
use vrcx_0_vrchat_client::query::{ AvatarListSort, QueryOrder, ReleaseStatusFilter, UserSearchCustomField, UserSearchSort, WorldSearchSort, };
use vrcx_0_vrchat_client::search::{ search_groups_get_input, search_groups_strict_get_input, search_instance_short_name_get_input, search_users_get_input, search_worlds_get_input, GroupSearchParams, UserSearchParams, WorldSearchParams, };
use vrcx_0_vrchat_client::tools::{ following_calendars_get_input, group_calendar_get_input, group_calendar_ics_get_input, group_event_follow_input, invite_message_edit_input, invite_messages_get_input, user_note_save_input, user_report_input, CalendarListParams, InviteMessageType, };
use vrcx_0_vrchat_client::users::{profile_get_input, user_represented_group_get_input};
use super::vrchat_remote_inputs::{VrchatAuthFileAnalysisInput,VrchatAvatarFileInput,VrchatAvatarIdInput,VrchatBoopInput,VrchatFavoriteGroupsInput,VrchatFavoriteWorldsInput,VrchatFriendUserInput,VrchatInstanceCloseInput,VrchatInstanceCreateInput,VrchatInstanceIdentityInput,VrchatInstanceSelfInviteInput,VrchatInstanceShortNameInput,VrchatMediaAvatarGalleryImageUploadInput,VrchatMediaFileIdInput,VrchatMediaFilesInput,VrchatMediaImageUploadInput,VrchatMediaInventoryItemInput,VrchatMediaInventoryItemUpdateInput,VrchatMediaInventoryItemsInput,VrchatMediaInventoryTemplateInput,VrchatMediaPrintIdInput,VrchatMediaPrintUploadInput,VrchatMediaPrintsInput,VrchatMediaProfileDecorationEquipInput,VrchatMediaRewardRedeemInput,VrchatMediaUserInventoryItemInput,VrchatRequestInvitePhotoSendInput,VrchatRequestInviteSendInput,VrchatSearchGroupsInput,VrchatSearchShortNameInput,VrchatSearchUsersInput,VrchatSearchWorldsInput,VrchatToolsCalendarEventInput,VrchatToolsCalendarGroupInput,VrchatToolsCalendarListInput,VrchatToolsFollowGroupEventInput,VrchatToolsUserNoteSaveInput,VrchatToolsUserReportInput,VrchatUserInput,VrchatUserProfileInput};
use super::vrchat_remote_conversions::*;
use super::{parse_required, send, CommandResult, IntoApiRequest};

/// Runs one of the direct-endpoint commands, or `None` if it is not one.
pub async fn dispatch(
    runtime: &RuntimeHostState,
    command: &str,
    args: &JsonValue,
) -> Option<CommandResult> {
    let web = runtime.web_client();
    let endpoint = VRCHAT_API_DEFAULT_ENDPOINT.to_string();

    match command {
        "app__vrchat_user_profile_get" => {
            let input: VrchatUserProfileInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    users::profile_get_input(endpoint.clone(), input.user_id, input.as_self).into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_user_represented_group_get" => {
            let input: VrchatUserInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    users::user_represented_group_get_input(endpoint.clone(), input.user_id).into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_friend_status_get" => {
            let input: VrchatFriendUserInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    friends::friend_status_get_input(endpoint.clone(), input.user_id).into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_instance_get" => {
            let input: VrchatInstanceIdentityInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    instances::instance_get_input(endpoint.clone(), input.world_id, input.instance_id).into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_instance_short_name_get" => {
            let input: VrchatInstanceShortNameInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    instances::instance_short_name_get_input(endpoint.clone(), input.world_id, input.instance_id, input.short_name).into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_instance_create" => {
            let input: VrchatInstanceCreateInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    instances::instance_create_input(endpoint.clone(), instance_create_request(input.params)).into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_instance_self_invite" => {
            let input: VrchatInstanceSelfInviteInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    instances::instance_self_invite_input(endpoint.clone(), input.world_id, input.instance_id, input.short_name).into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_instance_close" => {
            let input: VrchatInstanceCloseInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    instances::instance_close_input(endpoint.clone(), input.location, input.hard_close).into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_favorite_worlds_get" => {
            let input: VrchatFavoriteWorldsInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    favorites::favorite_worlds_get_input(endpoint.clone(), input.n, input.offset, input.owner_id, input.user_id, input.tag).into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_favorite_groups_get" => {
            let input: VrchatFavoriteGroupsInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    favorites::favorite_groups_get_input(endpoint.clone(), input.n, input.offset, input.owner_id).into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_search_worlds_get" => {
            let input: VrchatSearchWorldsInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    search::search_worlds_get_input(endpoint.clone(), world_search_params(input.params), input.option).into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_search_users_get" => {
            let input: VrchatSearchUsersInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    search::search_users_get_input(endpoint.clone(), user_search_params(input.params)).into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_search_groups_get" => {
            let input: VrchatSearchGroupsInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    search::search_groups_get_input(endpoint.clone(), group_search_params(input.params)).into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_search_groups_strict_get" => {
            let input: VrchatSearchGroupsInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    search::search_groups_strict_get_input(endpoint.clone(), group_search_params(input.params)).into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_search_instance_short_name_get" => {
            let input: VrchatSearchShortNameInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    search::search_instance_short_name_get_input(endpoint.clone(), input.short_name).into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_request_invite_send" => {
            let input: VrchatRequestInviteSendInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    notifications::request_invite_send_input(endpoint.clone(), input.receiver_user_id, request_invite_request(input.params)).into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_request_invite_photo_send" => {
            let input: VrchatRequestInvitePhotoSendInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    notifications::request_invite_photo_input(endpoint.clone(), input.receiver_user_id, request_invite_request(input.params), input.image_data).into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_boop_send" => {
            let input: VrchatBoopInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    notifications::boop_send_input(endpoint.clone(), input.user_id, input.emoji_id, input.inventory_item_id).into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_tools_group_calendar_get" => {
            let input: VrchatToolsCalendarGroupInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    tools::group_calendar_get_input(endpoint.clone(), input.group_id).into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_tools_following_calendars_get" => {
            let input: VrchatToolsCalendarListInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    tools::following_calendars_get_input(endpoint.clone(), calendar_list_params(input.params)).into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_tools_group_event_follow" => {
            let input: VrchatToolsFollowGroupEventInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    tools::group_event_follow_input(endpoint.clone(), input.group_id, input.event_id, input.is_following).into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_tools_group_calendar_ics_get" => {
            let input: VrchatToolsCalendarEventInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    tools::group_calendar_ics_get_input(endpoint.clone(), input.group_id, input.event_id).into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_tools_user_note_save" => {
            let input: VrchatToolsUserNoteSaveInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    tools::user_note_save_input(endpoint.clone(), input.target_user_id, input.note).into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_tools_user_report" => {
            let input: VrchatToolsUserReportInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    tools::user_report_input(endpoint.clone(), input.user_id, input.reason).into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_avatar_gallery_get" => {
            let input: VrchatAvatarIdInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    avatars::avatar_gallery_get_input(endpoint.clone(), input.avatar_id).into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_avatar_file_get" => {
            let input: VrchatAvatarFileInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    avatars::avatar_file_get_input(endpoint.clone(), input.file_id).into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_media_files_get" => {
            let input: VrchatMediaFilesInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    media::files_get_input(endpoint.clone(), media_file_list_params(input.params)).into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_media_file_delete" => {
            let input: VrchatMediaFileIdInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    media::file_delete_input(endpoint.clone(), input.file_id).into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_media_avatar_gallery_image_upload" => {
            let input: VrchatMediaAvatarGalleryImageUploadInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    media::avatar_gallery_image_upload_input(endpoint.clone(), input.image_data, input.avatar_id).into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_media_sticker_upload" => {
            let input: VrchatMediaImageUploadInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    media::sticker_upload_input(endpoint.clone(), input.image_data).into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_media_print_upload" => {
            let input: VrchatMediaPrintUploadInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    media::print_upload_input(endpoint.clone(), input.image_data, input.crop_white_border, print_upload_params(input.params)).into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_media_asset_upload" => {
            let input: vrcx_0_application::remote::MediaAssetUploadRequest = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    media::asset_upload_input(endpoint.clone(), media_asset_upload_request(input)).into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_media_prints_get" => {
            let input: VrchatMediaPrintsInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    media::prints_get_input(endpoint.clone(), input.user_id, input.n).into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_media_print_get" => {
            let input: VrchatMediaPrintIdInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    media::print_get_input(endpoint.clone(), input.print_id).into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_media_print_delete" => {
            let input: VrchatMediaPrintIdInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    media::print_delete_input(endpoint.clone(), input.print_id).into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_media_inventory_items_get" => {
            let input: VrchatMediaInventoryItemsInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    media::inventory_items_get_input(endpoint.clone(), inventory_list_params(input.params)).into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_media_inventory_template_get" => {
            let input: VrchatMediaInventoryTemplateInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    media::inventory_template_get_input(endpoint.clone(), input.inventory_template_id).into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_media_profile_decoration_equip" => {
            let input: VrchatMediaProfileDecorationEquipInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    media::inventory_item_equip_input(endpoint.clone(), input.inventory_id, profile_decoration_equip_slot(input.equip_slot)).into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_media_user_inventory_item_get" => {
            let input: VrchatMediaUserInventoryItemInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    media::user_inventory_item_get_input(endpoint.clone(), input.user_id, input.inventory_id).into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_media_inventory_item_update" => {
            let input: VrchatMediaInventoryItemUpdateInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    media::inventory_item_update_input(endpoint.clone(), input.inventory_id, InventoryItemUpdateRequest {
                is_archived: input.params.is_archived,
            }).into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_media_inventory_bundle_consume" => {
            let input: VrchatMediaInventoryItemInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    media::inventory_bundle_consume_input(endpoint.clone(), input.inventory_id).into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_media_reward_redeem" => {
            let input: VrchatMediaRewardRedeemInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    media::reward_redeem_input(endpoint.clone(), input.code).into_api_request(),
                )
                .await,
            )
        }
        "app__vrchat_auth_file_analysis_get" => {
            let input: VrchatAuthFileAnalysisInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                send(
                    web,
                    auth::file_analysis_get_input(endpoint.clone(), input.file_id, input.version, input.variant).into_api_request(),
                )
                .await,
            )
        }

        _ => None,
    }
}

/// Every command answered here, for the capability list on `/v1/health`.
pub const COMMANDS: &[&str] = &[
    "app__vrchat_user_profile_get",
    "app__vrchat_user_represented_group_get",
    "app__vrchat_friend_status_get",
    "app__vrchat_instance_get",
    "app__vrchat_instance_short_name_get",
    "app__vrchat_instance_create",
    "app__vrchat_instance_self_invite",
    "app__vrchat_instance_close",
    "app__vrchat_favorite_worlds_get",
    "app__vrchat_favorite_groups_get",
    "app__vrchat_search_worlds_get",
    "app__vrchat_search_users_get",
    "app__vrchat_search_groups_get",
    "app__vrchat_search_groups_strict_get",
    "app__vrchat_search_instance_short_name_get",
    "app__vrchat_request_invite_send",
    "app__vrchat_request_invite_photo_send",
    "app__vrchat_boop_send",
    "app__vrchat_tools_group_calendar_get",
    "app__vrchat_tools_following_calendars_get",
    "app__vrchat_tools_group_event_follow",
    "app__vrchat_tools_group_calendar_ics_get",
    "app__vrchat_tools_user_note_save",
    "app__vrchat_tools_user_report",
    "app__vrchat_avatar_gallery_get",
    "app__vrchat_avatar_file_get",
    "app__vrchat_media_files_get",
    "app__vrchat_media_file_delete",
    "app__vrchat_media_avatar_gallery_image_upload",
    "app__vrchat_media_sticker_upload",
    "app__vrchat_media_print_upload",
    "app__vrchat_media_asset_upload",
    "app__vrchat_media_prints_get",
    "app__vrchat_media_print_get",
    "app__vrchat_media_print_delete",
    "app__vrchat_media_inventory_items_get",
    "app__vrchat_media_inventory_template_get",
    "app__vrchat_media_profile_decoration_equip",
    "app__vrchat_media_user_inventory_item_get",
    "app__vrchat_media_inventory_item_update",
    "app__vrchat_media_inventory_bundle_consume",
    "app__vrchat_media_reward_redeem",
    "app__vrchat_auth_file_analysis_get",

];
