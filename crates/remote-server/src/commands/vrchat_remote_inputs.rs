//! GENERATED FILE - do not edit by hand.
//!
//! Input types for the direct-endpoint commands. The desktop build declares
//! these inside `src-tauri`; they are plain `Deserialize` descriptions, so they
//! are re-emitted here from the same source rather than retyped.
//!
//! Sources: src-tauri/src/commands/vrchat/auth/types.rs
//!         src-tauri/src/commands/vrchat/avatars/types.rs
//!         src-tauri/src/commands/vrchat/favorites/types.rs
//!         src-tauri/src/commands/vrchat/friends/types.rs
//!         src-tauri/src/commands/vrchat/instances/types.rs
//!         src-tauri/src/commands/vrchat/media/types.rs
//!         src-tauri/src/commands/vrchat/notifications/types.rs
//!         src-tauri/src/commands/vrchat/search/types.rs
//!         src-tauri/src/commands/vrchat/tools/types.rs
//!         src-tauri/src/commands/vrchat/users/types.rs

#![allow(dead_code)]
// Skipped commands leave parameters and their `use` lines behind, and the
// imports are copied wholesale from the desktop decls rather than tracked.
#![allow(unused_imports)]

use serde::Deserialize;
use vrcx_0_application::remote::AvatarListSort;
use vrcx_0_application::remote::AvatarUpdateRequest;
use vrcx_0_application::remote::CalendarListParams;
use vrcx_0_application::remote::EmojiUploadParams;
use vrcx_0_application_core::FavoriteEntityKind;
use vrcx_0_application_core::FavoriteGroupVisibility;
use vrcx_0_application::remote::GroupSearchParams;
use vrcx_0_application::remote::InstanceCreateRequest;
use vrcx_0_application::remote::InventoryItemUpdateRequest;
use vrcx_0_application::remote::InventoryListParams;
use vrcx_0_application::remote::InviteMessageType;
use vrcx_0_application::remote::MediaFileListParams;
use vrcx_0_application::remote::PrintUploadParams;
use vrcx_0_application::remote::ProfileDecorationEquipSlot;
use vrcx_0_application::remote::QueryOrder;
use vrcx_0_application::remote::ReleaseStatusFilter;
use vrcx_0_application::remote::RequestInviteRequest;
use vrcx_0_application::remote::UserSearchParams;
use vrcx_0_application_core::VrchatFavoriteType;
use vrcx_0_application::remote::WorldSearchParams;
use vrcx_0_application::remote::deserialize_nonnegative_i32;

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VrchatAuthFileAnalysisInput {
    #[serde(default)]
    pub file_id: String,
    #[serde(default)]
    pub version: i64,
    #[serde(default)]
    pub variant: String,
}
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VrchatAvatarIdInput {
    #[serde(default)]
    pub avatar_id: String,
}
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VrchatAvatarFileInput {
    #[serde(default)]
    pub file_id: String,
}
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VrchatFavoriteWorldsInput {
    #[serde(default, deserialize_with = "deserialize_nonnegative_i32")]
    pub n: i32,
    #[serde(default, deserialize_with = "deserialize_nonnegative_i32")]
    pub offset: i32,
    #[serde(default)]
    pub owner_id: String,
    #[serde(default)]
    pub user_id: String,
    #[serde(default)]
    pub tag: String,
}
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VrchatFavoriteGroupsInput {
    #[serde(default, deserialize_with = "deserialize_nonnegative_i32")]
    pub n: i32,
    #[serde(default, deserialize_with = "deserialize_nonnegative_i32")]
    pub offset: i32,
    #[serde(default)]
    pub owner_id: String,
}
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VrchatFriendUserInput {
    #[serde(default)]
    pub user_id: String,
}
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VrchatInstanceIdentityInput {
    #[serde(default)]
    pub world_id: String,
    #[serde(default)]
    pub instance_id: String,
}
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VrchatInstanceShortNameInput {
    #[serde(default)]
    pub world_id: String,
    #[serde(default)]
    pub instance_id: String,
    #[serde(default)]
    pub short_name: String,
}
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VrchatInstanceCreateInput {
    pub params: InstanceCreateRequest,
}
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VrchatInstanceSelfInviteInput {
    #[serde(default)]
    pub world_id: String,
    #[serde(default)]
    pub instance_id: String,
    #[serde(default)]
    pub short_name: String,
}
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VrchatInstanceCloseInput {
    #[serde(default)]
    pub location: String,
    #[serde(default)]
    pub hard_close: bool,
}
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VrchatMediaFilesInput {
    #[serde(default)]
    pub params: MediaFileListParams,
}
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VrchatMediaInventoryItemsInput {
    #[serde(default)]
    pub params: InventoryListParams,
}
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VrchatMediaFileIdInput {
    #[serde(default)]
    pub file_id: String,
}
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VrchatMediaImageUploadInput {
    #[serde(default)]
    pub image_data: String,
}
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VrchatMediaAvatarGalleryImageUploadInput {
    #[serde(default)]
    pub image_data: String,
    pub avatar_id: String,
}
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VrchatMediaPrintUploadInput {
    #[serde(default)]
    pub image_data: String,
    #[serde(default)]
    pub crop_white_border: bool,
    pub params: PrintUploadParams,
}
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VrchatMediaPrintsInput {
    #[serde(default)]
    pub user_id: String,
    #[serde(default, deserialize_with = "deserialize_nonnegative_i32")]
    pub n: i32,
}
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VrchatMediaPrintIdInput {
    #[serde(default)]
    pub print_id: String,
}
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VrchatMediaUserInventoryItemInput {
    #[serde(default)]
    pub user_id: String,
    #[serde(default)]
    pub inventory_id: String,
}
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VrchatMediaInventoryItemInput {
    #[serde(default)]
    pub inventory_id: String,
}
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VrchatMediaInventoryItemUpdateInput {
    #[serde(default)]
    pub inventory_id: String,
    pub params: InventoryItemUpdateRequest,
}
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VrchatMediaInventoryTemplateInput {
    #[serde(default)]
    pub inventory_template_id: String,
}
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VrchatMediaProfileDecorationEquipInput {
    #[serde(default)]
    pub inventory_id: String,
    pub equip_slot: ProfileDecorationEquipSlot,
}
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VrchatMediaRewardRedeemInput {
    #[serde(default)]
    pub code: String,
}
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VrchatRequestInviteSendInput {
    #[serde(default)]
    pub receiver_user_id: String,
    pub params: RequestInviteRequest,
}
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VrchatRequestInvitePhotoSendInput {
    #[serde(default)]
    pub receiver_user_id: String,
    pub params: RequestInviteRequest,
    #[serde(default)]
    pub image_data: String,
}
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VrchatBoopInput {
    #[serde(default)]
    pub user_id: String,
    #[serde(default)]
    pub emoji_id: String,
    #[serde(default)]
    pub inventory_item_id: String,
}
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VrchatSearchUsersInput {
    #[serde(default)]
    pub params: UserSearchParams,
}
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VrchatSearchGroupsInput {
    #[serde(default)]
    pub params: GroupSearchParams,
}
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VrchatSearchWorldsInput {
    #[serde(default)]
    pub params: WorldSearchParams,
    pub option: Option<String>,
}
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VrchatSearchShortNameInput {
    #[serde(default)]
    pub short_name: String,
}
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VrchatToolsCalendarListInput {
    #[serde(default)]
    pub params: CalendarListParams,
}
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VrchatToolsCalendarGroupInput {
    #[serde(default)]
    pub group_id: String,
}
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VrchatToolsCalendarEventInput {
    #[serde(default)]
    pub group_id: String,
    #[serde(default)]
    pub event_id: String,
}
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VrchatToolsFollowGroupEventInput {
    #[serde(default)]
    pub group_id: String,
    #[serde(default)]
    pub event_id: String,
    #[serde(default)]
    pub is_following: bool,
}
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VrchatToolsUserNoteSaveInput {
    #[serde(default)]
    pub target_user_id: String,
    #[serde(default)]
    pub note: String,
}
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VrchatToolsUserReportInput {
    #[serde(default)]
    pub user_id: String,
    #[serde(default)]
    pub reason: String,
}
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VrchatUserInput {
    #[serde(default)]
    pub user_id: String,
    #[serde(default)]
    pub force: bool,
    #[serde(default)]
    pub dialog: bool,
    #[serde(default)]
    pub is_friend: Option<bool>,
}
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VrchatUserProfileInput {
    #[serde(default)]
    pub user_id: String,
    #[serde(default)]
    pub as_self: bool,
}
