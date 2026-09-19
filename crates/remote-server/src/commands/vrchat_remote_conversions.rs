//! GENERATED FILE - do not edit by hand.
//!
//! Conversions the facade applies before calling a request builder, copied
//! from `crates/runtime-host-desktop/src/vrchat_remote.rs` by `.workbuddy/scripts/generate_endpoint_commands.py`.
//!
//! They exist because two crates declare the same parameter struct: the
//! facade takes the application crate's copy and the builder wants the client
//! crate's, so something has to map one to the other. Copied rather than
//! retyped, so the two stay in step; the real fix is to declare the struct
//! once, at which point these disappear.

#![allow(dead_code)]
#![allow(unused_imports)]

use vrcx_0_application::remote::VrchatApiRuntime;
use vrcx_0_application::remote::{ AvatarListSort as ApplicationAvatarListSort, CalendarListParams as ApplicationCalendarListParams, EmojiLoopStyle as ApplicationEmojiLoopStyle, EmojiUploadParams as ApplicationEmojiUploadParams, GroupSearchParams as ApplicationGroupSearchParams, ImageAnimationStyle as ApplicationImageAnimationStyle, ImageMaskTag as ApplicationImageMaskTag, InstanceCreateGroupAccessType as ApplicationInstanceCreateGroupAccessType, InstanceCreateMinimumAvatarPerformance as ApplicationInstanceCreateMinimumAvatarPerformance, InstanceCreateRegion as ApplicationInstanceCreateRegion, InstanceCreateRequest as ApplicationInstanceCreateRequest, InstanceCreateType as ApplicationInstanceCreateType, InventoryItemUpdateRequest as ApplicationInventoryItemUpdateRequest, InventoryListParams as ApplicationInventoryListParams, InventoryOrder as ApplicationInventoryOrder, InviteMessageType as ApplicationInviteMessageType, MediaAssetUploadRequest as ApplicationMediaAssetUploadRequest, MediaFileListParams as ApplicationMediaFileListParams, MediaFileTag as ApplicationMediaFileTag, PrintUploadParams as ApplicationPrintUploadParams, ProfileDecorationEquipSlot as ApplicationProfileDecorationEquipSlot, QueryOrder as ApplicationQueryOrder, ReleaseStatusFilter as ApplicationReleaseStatusFilter, RequestInviteRequest as ApplicationRequestInviteRequest, UserSearchCustomField as ApplicationUserSearchCustomField, UserSearchParams as ApplicationUserSearchParams, UserSearchSort as ApplicationUserSearchSort, WorldSearchParams as ApplicationWorldSearchParams, WorldSearchSort as ApplicationWorldSearchSort, };
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
pub fn calendar_list_params(value: ApplicationCalendarListParams) -> CalendarListParams {
    CalendarListParams {
        n: value.n,
        offset: value.offset,
        date: value.date,
    }
}
pub fn emoji_upload_params(value: ApplicationEmojiUploadParams) -> EmojiUploadParams {
    match value {
        ApplicationEmojiUploadParams::Emoji {
            animation_style,
            mask_tag,
        } => EmojiUploadParams::Emoji {
            animation_style: image_animation_style(animation_style),
            mask_tag: match mask_tag {
                ApplicationImageMaskTag::Square => ImageMaskTag::Square,
            },
        },
        ApplicationEmojiUploadParams::EmojiAnimated {
            animation_style,
            mask_tag,
            frames,
            frames_over_time,
            loop_style,
        } => EmojiUploadParams::EmojiAnimated {
            animation_style: image_animation_style(animation_style),
            mask_tag: match mask_tag {
                ApplicationImageMaskTag::Square => ImageMaskTag::Square,
            },
            frames,
            frames_over_time,
            loop_style: loop_style.map(|value| match value {
                ApplicationEmojiLoopStyle::PingPong => EmojiLoopStyle::PingPong,
            }),
        },
    }
}
pub fn group_search_params(value: ApplicationGroupSearchParams) -> GroupSearchParams {
    GroupSearchParams {
        query: value.query,
        offset: value.offset,
        n: value.n,
    }
}
pub fn image_animation_style(value: ApplicationImageAnimationStyle) -> ImageAnimationStyle {
    match value {
        ApplicationImageAnimationStyle::Aura => ImageAnimationStyle::Aura,
        ApplicationImageAnimationStyle::Bats => ImageAnimationStyle::Bats,
        ApplicationImageAnimationStyle::Bees => ImageAnimationStyle::Bees,
        ApplicationImageAnimationStyle::Bounce => ImageAnimationStyle::Bounce,
        ApplicationImageAnimationStyle::Cloud => ImageAnimationStyle::Cloud,
        ApplicationImageAnimationStyle::Confetti => ImageAnimationStyle::Confetti,
        ApplicationImageAnimationStyle::Crying => ImageAnimationStyle::Crying,
        ApplicationImageAnimationStyle::Dislike => ImageAnimationStyle::Dislike,
        ApplicationImageAnimationStyle::Fire => ImageAnimationStyle::Fire,
        ApplicationImageAnimationStyle::Idea => ImageAnimationStyle::Idea,
        ApplicationImageAnimationStyle::Lasers => ImageAnimationStyle::Lasers,
        ApplicationImageAnimationStyle::Like => ImageAnimationStyle::Like,
        ApplicationImageAnimationStyle::Magnet => ImageAnimationStyle::Magnet,
        ApplicationImageAnimationStyle::Mistletoe => ImageAnimationStyle::Mistletoe,
        ApplicationImageAnimationStyle::Money => ImageAnimationStyle::Money,
        ApplicationImageAnimationStyle::Noise => ImageAnimationStyle::Noise,
        ApplicationImageAnimationStyle::Orbit => ImageAnimationStyle::Orbit,
        ApplicationImageAnimationStyle::Pizza => ImageAnimationStyle::Pizza,
        ApplicationImageAnimationStyle::Rain => ImageAnimationStyle::Rain,
        ApplicationImageAnimationStyle::Rotate => ImageAnimationStyle::Rotate,
        ApplicationImageAnimationStyle::Shake => ImageAnimationStyle::Shake,
        ApplicationImageAnimationStyle::Snow => ImageAnimationStyle::Snow,
        ApplicationImageAnimationStyle::Snowball => ImageAnimationStyle::Snowball,
        ApplicationImageAnimationStyle::Spin => ImageAnimationStyle::Spin,
        ApplicationImageAnimationStyle::Splash => ImageAnimationStyle::Splash,
        ApplicationImageAnimationStyle::Stop => ImageAnimationStyle::Stop,
        ApplicationImageAnimationStyle::Zzz => ImageAnimationStyle::Zzz,
    }
}
pub fn instance_create_request(value: ApplicationInstanceCreateRequest) -> InstanceCreateRequest {
    InstanceCreateRequest {
        r#type: match value.r#type {
            ApplicationInstanceCreateType::Friends => InstanceCreateType::Friends,
            ApplicationInstanceCreateType::Group => InstanceCreateType::Group,
            ApplicationInstanceCreateType::Hidden => InstanceCreateType::Hidden,
            ApplicationInstanceCreateType::Private => InstanceCreateType::Private,
            ApplicationInstanceCreateType::Public => InstanceCreateType::Public,
        },
        can_request_invite: value.can_request_invite,
        world_id: value.world_id,
        owner_id: value.owner_id,
        region: match value.region {
            ApplicationInstanceCreateRegion::Eu => InstanceCreateRegion::Eu,
            ApplicationInstanceCreateRegion::Jp => InstanceCreateRegion::Jp,
            ApplicationInstanceCreateRegion::Us => InstanceCreateRegion::Us,
            ApplicationInstanceCreateRegion::Use => InstanceCreateRegion::Use,
        },
        group_access_type: value.group_access_type.map(|value| match value {
            ApplicationInstanceCreateGroupAccessType::Members => {
                InstanceCreateGroupAccessType::Members
            }
            ApplicationInstanceCreateGroupAccessType::Plus => InstanceCreateGroupAccessType::Plus,
            ApplicationInstanceCreateGroupAccessType::Public => {
                InstanceCreateGroupAccessType::Public
            }
        }),
        queue_enabled: value.queue_enabled,
        role_ids: value.role_ids,
        age_gate: value.age_gate,
        display_name: value.display_name,
        minimum_avatar_performance: value.minimum_avatar_performance.map(|value| match value {
            ApplicationInstanceCreateMinimumAvatarPerformance::Poor => {
                InstanceCreateMinimumAvatarPerformance::Poor
            }
            ApplicationInstanceCreateMinimumAvatarPerformance::Medium => {
                InstanceCreateMinimumAvatarPerformance::Medium
            }
            ApplicationInstanceCreateMinimumAvatarPerformance::Good => {
                InstanceCreateMinimumAvatarPerformance::Good
            }
        }),
    }
}
pub fn inventory_list_params(value: ApplicationInventoryListParams) -> InventoryListParams {
    InventoryListParams {
        n: value.n,
        offset: value.offset,
        holder_id: value.holder_id,
        equip_slot: value.equip_slot,
        order: value.order.map(|value| match value {
            ApplicationInventoryOrder::Newest => InventoryOrder::Newest,
        }),
        tags: value.tags,
        types: value.types,
        flags: value.flags,
        not_types: value.not_types,
        not_flags: value.not_flags,
        archived: value.archived,
    }
}
pub fn media_asset_upload_request(
    value: ApplicationMediaAssetUploadRequest,
) -> MediaAssetUploadRequest {
    match value {
        ApplicationMediaAssetUploadRequest::Gallery { image_data } => {
            MediaAssetUploadRequest::Gallery { image_data }
        }
        ApplicationMediaAssetUploadRequest::Icons { image_data } => {
            MediaAssetUploadRequest::Icons { image_data }
        }
        ApplicationMediaAssetUploadRequest::Emojis { image_data, params } => {
            MediaAssetUploadRequest::Emojis {
                image_data,
                params: emoji_upload_params(params),
            }
        }
        ApplicationMediaAssetUploadRequest::Stickers { image_data } => {
            MediaAssetUploadRequest::Stickers { image_data }
        }
        ApplicationMediaAssetUploadRequest::Prints {
            image_data,
            crop_white_border,
            params,
        } => MediaAssetUploadRequest::Prints {
            image_data,
            crop_white_border,
            params: print_upload_params(params),
        },
    }
}
pub fn media_file_list_params(value: ApplicationMediaFileListParams) -> MediaFileListParams {
    MediaFileListParams {
        n: value.n,
        offset: value.offset,
        tag: value.tag.map(|value| match value {
            ApplicationMediaFileTag::Gallery => MediaFileTag::Gallery,
            ApplicationMediaFileTag::AvatarGallery => MediaFileTag::AvatarGallery,
            ApplicationMediaFileTag::Icon => MediaFileTag::Icon,
            ApplicationMediaFileTag::Emoji => MediaFileTag::Emoji,
            ApplicationMediaFileTag::EmojiAnimated => MediaFileTag::EmojiAnimated,
            ApplicationMediaFileTag::Sticker => MediaFileTag::Sticker,
        }),
    }
}
pub fn print_upload_params(value: ApplicationPrintUploadParams) -> PrintUploadParams {
    PrintUploadParams {
        note: value.note,
        timestamp: value.timestamp,
    }
}
pub fn profile_decoration_equip_slot(
    value: ApplicationProfileDecorationEquipSlot,
) -> ProfileDecorationEquipSlot {
    match value {
        ApplicationProfileDecorationEquipSlot::IconFrame => ProfileDecorationEquipSlot::IconFrame,
        ApplicationProfileDecorationEquipSlot::ProfileEffect => {
            ProfileDecorationEquipSlot::ProfileEffect
        }
        ApplicationProfileDecorationEquipSlot::NameplateEffect => {
            ProfileDecorationEquipSlot::NameplateEffect
        }
    }
}
pub fn query_order(value: ApplicationQueryOrder) -> QueryOrder {
    match value {
        ApplicationQueryOrder::Ascending => QueryOrder::Ascending,
        ApplicationQueryOrder::Descending => QueryOrder::Descending,
    }
}
pub fn release_status_filter(value: ApplicationReleaseStatusFilter) -> ReleaseStatusFilter {
    match value {
        ApplicationReleaseStatusFilter::All => ReleaseStatusFilter::All,
        ApplicationReleaseStatusFilter::Hidden => ReleaseStatusFilter::Hidden,
        ApplicationReleaseStatusFilter::Private => ReleaseStatusFilter::Private,
        ApplicationReleaseStatusFilter::Public => ReleaseStatusFilter::Public,
    }
}
pub fn request_invite_request(value: ApplicationRequestInviteRequest) -> RequestInviteRequest {
    RequestInviteRequest {
        request_slot: value.request_slot,
    }
}
pub fn user_search_custom_field(value: ApplicationUserSearchCustomField) -> UserSearchCustomField {
    match value {
        ApplicationUserSearchCustomField::Bio => UserSearchCustomField::Bio,
        ApplicationUserSearchCustomField::DisplayName => UserSearchCustomField::DisplayName,
    }
}
pub fn user_search_params(value: ApplicationUserSearchParams) -> UserSearchParams {
    UserSearchParams {
        search: value.search,
        developer_type: value.developer_type,
        n: value.n,
        offset: value.offset,
        is_internal_variant: value.is_internal_variant,
        custom_fields: value.custom_fields.map(user_search_custom_field),
        sort: value.sort.map(user_search_sort),
        order: value.order.map(query_order),
    }
}
pub fn user_search_sort(value: ApplicationUserSearchSort) -> UserSearchSort {
    match value {
        ApplicationUserSearchSort::CreatedAt => UserSearchSort::CreatedAt,
        ApplicationUserSearchSort::Created => UserSearchSort::Created,
        ApplicationUserSearchSort::LastLogin => UserSearchSort::LastLogin,
        ApplicationUserSearchSort::NuisanceFactor => UserSearchSort::NuisanceFactor,
        ApplicationUserSearchSort::Relevance => UserSearchSort::Relevance,
    }
}
pub fn world_search_params(value: ApplicationWorldSearchParams) -> WorldSearchParams {
    WorldSearchParams {
        featured: value.featured,
        sort: value.sort.map(world_search_sort),
        user: value.user,
        user_id: value.user_id,
        n: value.n,
        order: value.order.map(query_order),
        offset: value.offset,
        search: value.search,
        tag: value.tag,
        notag: value.notag,
        release_status: value.release_status.map(release_status_filter),
        max_unity_version: value.max_unity_version,
        min_unity_version: value.min_unity_version,
        platform: value.platform,
        noplatform: value.noplatform,
        fuzzy: value.fuzzy,
        avatar_specific: value.avatar_specific,
    }
}
pub fn world_search_sort(value: ApplicationWorldSearchSort) -> WorldSearchSort {
    match value {
        ApplicationWorldSearchSort::CreatedAt => WorldSearchSort::CreatedAt,
        ApplicationWorldSearchSort::UpdatedAt => WorldSearchSort::UpdatedAt,
        ApplicationWorldSearchSort::Created => WorldSearchSort::Created,
        ApplicationWorldSearchSort::Favorites => WorldSearchSort::Favorites,
        ApplicationWorldSearchSort::Heat => WorldSearchSort::Heat,
        ApplicationWorldSearchSort::LabsPublicationDate => WorldSearchSort::LabsPublicationDate,
        ApplicationWorldSearchSort::Magic => WorldSearchSort::Magic,
        ApplicationWorldSearchSort::Name => WorldSearchSort::Name,
        ApplicationWorldSearchSort::Order => WorldSearchSort::Order,
        ApplicationWorldSearchSort::Popularity => WorldSearchSort::Popularity,
        ApplicationWorldSearchSort::PublicationDate => WorldSearchSort::PublicationDate,
        ApplicationWorldSearchSort::Random => WorldSearchSort::Random,
        ApplicationWorldSearchSort::Relevance => WorldSearchSort::Relevance,
        ApplicationWorldSearchSort::ReportCount => WorldSearchSort::ReportCount,
        ApplicationWorldSearchSort::ReportScore => WorldSearchSort::ReportScore,
        ApplicationWorldSearchSort::Shuffle => WorldSearchSort::Shuffle,
        ApplicationWorldSearchSort::Trust => WorldSearchSort::Trust,
        ApplicationWorldSearchSort::Updated => WorldSearchSort::Updated,
    }
}
