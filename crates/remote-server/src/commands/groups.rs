//! GENERATED FILE - do not edit by hand.
//!
//! Derived from the desktop command layer so this server runs the same
//! application functions the desktop build does:
//!   commands: src-tauri/src/commands/vrchat/groups/service.rs
//!   facade:   crates/runtime-host-desktop/src/group.rs
//! Regenerate:
//!   .workbuddy/scripts/extract_vrchat_commands.py --facade crates/runtime-host-desktop/src/group.rs \
//!       --commands src-tauri/src/commands/vrchat/groups/service.rs --deps-fn group --module vrcx_0_application::social --out D:/vrcx-1/src/crates/remote-server/src/commands/groups.rs

use serde_json::Value as JsonValue;

use vrcx_0_application::social::{VrchatGroupGalleryInput,VrchatGroupIdInput,VrchatGroupJoinRequestRespondInput,VrchatGroupJoinRequestsInput,VrchatGroupLogsInput,VrchatGroupMemberPropsInput,VrchatGroupMemberRoleInput,VrchatGroupMembersInput,VrchatGroupMembersSearchInput,VrchatGroupPagedInput,VrchatGroupPostCreateInput,VrchatGroupPostDeleteInput,VrchatGroupPostEditInput,VrchatGroupProfileInput,VrchatGroupRepresentationInput,VrchatGroupUserGroupsInput,VrchatGroupUserInput,add_member_role,ban_member,block_group,cancel_request,create_post,delete_invite,delete_post,edit_post,get_audit_log_types,get_bans,get_gallery,get_group,get_invites,get_join_requests,get_logs,get_member,get_members,get_posts,get_user_groups,get_user_instances,join_group,kick_member,leave_group,remove_member_role,respond_join_request,search_members,send_invite,set_member_props,set_representation,unban_member,unblock_group};
use vrcx_0_composition::RuntimeHostState;

use super::{encode, parse_required, CommandResult};
use crate::deps;

/// Runs one of these commands, or `None` when the name belongs to no one here.
///
/// `None` is the signal to keep looking - and ultimately to answer `501`, which
/// is what tells the client to run the command against its own runtime.
pub async fn dispatch(
    runtime: &RuntimeHostState,
    command: &str,
    args: &JsonValue,
) -> Option<CommandResult> {
    match command {
        "app__vrchat_group_get" => {
            let input: VrchatGroupProfileInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(get_group(deps::group(runtime), input).await))
        }
        "app__vrchat_group_user_groups_get" => {
            let input: VrchatGroupUserGroupsInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(get_user_groups(deps::group(runtime), input).await))
        }
        "app__vrchat_group_posts_get" => {
            let input: VrchatGroupPagedInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(get_posts(deps::group(runtime), input).await))
        }
        "app__vrchat_group_member_get" => {
            let input: VrchatGroupUserInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(get_member(deps::group(runtime), input).await))
        }
        "app__vrchat_group_members_get" => {
            let input: VrchatGroupMembersInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(get_members(deps::group(runtime), input).await))
        }
        "app__vrchat_group_members_search" => {
            let input: VrchatGroupMembersSearchInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(search_members(deps::group(runtime), input).await))
        }
        "app__vrchat_group_gallery_get" => {
            let input: VrchatGroupGalleryInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(get_gallery(deps::group(runtime), input).await))
        }
        "app__vrchat_group_bans_get" => {
            let input: VrchatGroupPagedInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(get_bans(deps::group(runtime), input).await))
        }
        "app__vrchat_group_invites_get" => {
            let input: VrchatGroupPagedInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(get_invites(deps::group(runtime), input).await))
        }
        "app__vrchat_group_join_requests_get" => {
            let input: VrchatGroupJoinRequestsInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(get_join_requests(deps::group(runtime), input).await))
        }
        "app__vrchat_group_audit_log_types_get" => {
            let input: VrchatGroupIdInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(get_audit_log_types(deps::group(runtime), input).await))
        }
        "app__vrchat_group_logs_get" => {
            let input: VrchatGroupLogsInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(get_logs(deps::group(runtime), input).await))
        }
        "app__vrchat_group_user_instances_get" => {
            let input: VrchatGroupUserGroupsInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(get_user_instances(deps::group(runtime), input).await))
        }
        "app__vrchat_group_post_create" => {
            let input: VrchatGroupPostCreateInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(create_post(deps::group(runtime), input).await))
        }
        "app__vrchat_group_post_edit" => {
            let input: VrchatGroupPostEditInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(edit_post(deps::group(runtime), input).await))
        }
        "app__vrchat_group_post_delete" => {
            let input: VrchatGroupPostDeleteInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(delete_post(deps::group(runtime), input).await))
        }
        "app__vrchat_group_join" => {
            let input: VrchatGroupIdInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(join_group(deps::group(runtime), input).await))
        }
        "app__vrchat_group_leave" => {
            let input: VrchatGroupIdInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(leave_group(deps::group(runtime), input).await))
        }
        "app__vrchat_group_request_cancel" => {
            let input: VrchatGroupIdInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(cancel_request(deps::group(runtime), input).await))
        }
        "app__vrchat_group_invite_send" => {
            let input: VrchatGroupUserInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(send_invite(deps::group(runtime), input).await))
        }
        "app__vrchat_group_member_kick" => {
            let input: VrchatGroupUserInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(kick_member(deps::group(runtime), input).await))
        }
        "app__vrchat_group_member_ban" => {
            let input: VrchatGroupUserInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(ban_member(deps::group(runtime), input).await))
        }
        "app__vrchat_group_member_unban" => {
            let input: VrchatGroupUserInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(unban_member(deps::group(runtime), input).await))
        }
        "app__vrchat_group_member_role_add" => {
            let input: VrchatGroupMemberRoleInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(add_member_role(deps::group(runtime), input).await))
        }
        "app__vrchat_group_member_role_remove" => {
            let input: VrchatGroupMemberRoleInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(remove_member_role(deps::group(runtime), input).await))
        }
        "app__vrchat_group_invite_delete" => {
            let input: VrchatGroupUserInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(delete_invite(deps::group(runtime), input).await))
        }
        "app__vrchat_group_join_request_respond" => {
            let input: VrchatGroupJoinRequestRespondInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(respond_join_request(deps::group(runtime), input).await))
        }
        "app__vrchat_group_representation_set" => {
            let input: VrchatGroupRepresentationInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(set_representation(deps::group(runtime), input).await))
        }
        "app__vrchat_group_member_props_set" => {
            let input: VrchatGroupMemberPropsInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(set_member_props(deps::group(runtime), input).await))
        }
        "app__vrchat_group_block" => {
            let input: VrchatGroupIdInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(block_group(deps::group(runtime), input).await))
        }
        "app__vrchat_group_unblock" => {
            let input: VrchatGroupUserInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(unblock_group(deps::group(runtime), input).await))
        }

        _ => None,
    }
}

/// Every command answered here, for the capability list on `/v1/health`.
///
/// The client asks once which commands exist and then only forwards those,
/// so adding a command here is enough for the client to start using it.
pub const COMMANDS: &[&str] = &[
    "app__vrchat_group_get",
    "app__vrchat_group_user_groups_get",
    "app__vrchat_group_posts_get",
    "app__vrchat_group_member_get",
    "app__vrchat_group_members_get",
    "app__vrchat_group_members_search",
    "app__vrchat_group_gallery_get",
    "app__vrchat_group_bans_get",
    "app__vrchat_group_invites_get",
    "app__vrchat_group_join_requests_get",
    "app__vrchat_group_audit_log_types_get",
    "app__vrchat_group_logs_get",
    "app__vrchat_group_user_instances_get",
    "app__vrchat_group_post_create",
    "app__vrchat_group_post_edit",
    "app__vrchat_group_post_delete",
    "app__vrchat_group_join",
    "app__vrchat_group_leave",
    "app__vrchat_group_request_cancel",
    "app__vrchat_group_invite_send",
    "app__vrchat_group_member_kick",
    "app__vrchat_group_member_ban",
    "app__vrchat_group_member_unban",
    "app__vrchat_group_member_role_add",
    "app__vrchat_group_member_role_remove",
    "app__vrchat_group_invite_delete",
    "app__vrchat_group_join_request_respond",
    "app__vrchat_group_representation_set",
    "app__vrchat_group_member_props_set",
    "app__vrchat_group_block",
    "app__vrchat_group_unblock",

];
