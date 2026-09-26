//! Generic command forwarding, so a thin client can run the same command
//! names it would have invoked locally.
//!
//! This is the migration path away from the desktop runtime. Every command
//! implemented here is one the client no longer needs its own runtime for;
//! everything else answers [`CommandResult::NotImplemented`] and the client
//! silently falls back to invoking locally. That is what makes a partial
//! migration safe — the two modes coexist until the last command lands.
//!
//! Responses are serialised from the same types the local bindings return, so
//! the TypeScript side sees byte-identical shapes and needs no changes.
//!
//! Most commands live in a submodule per area, generated from the desktop
//! command layer (`groups.rs` and friends). Those call the same `application`
//! functions the desktop build calls, so behaviour cannot drift between the
//! two. Anything not covered by a submodule falls through to the hand-written
//! [`parse_command`] table below.

mod groups;
mod local;
mod vrchat_remote;
mod vrchat_remote_conversions;
mod vrchat_remote_inputs;
mod avatars;
mod current_user;
mod favorites;
mod social;
mod vrchat_extra;
mod profile;
mod app_extra;
mod external_api;

use std::sync::atomic::{AtomicU64, Ordering};

use serde::de::DeserializeOwned;
use serde_json::Value as JsonValue;

use vrcx_0_application_core::vrchat_api::{VrchatApiRequest, VrchatScope};
use vrcx_0_application_core::WebClient;
use vrcx_0_contracts::game_log::GameLogWriteKind;
use vrcx_0_composition::local_data::LocalDataRuntime;
use vrcx_0_composition::RuntimeHostState;
use vrcx_0_core::vrchat_endpoints::VRCHAT_API_DEFAULT_ENDPOINT;
use vrcx_0_remote_protocol::CommandRequest;
use vrcx_0_vrchat_client::auth::{current_user_get_input, visits_get_input};
use vrcx_0_vrchat_client::avatars::avatar_gallery_get_input;
use vrcx_0_vrchat_client::favorites::favorite_groups_get_input;
use vrcx_0_vrchat_client::friends::friend_status_get_input;

/// How forwarded commands have fared in this process.
///
/// Exposed on `/v1/health` because "is the client actually talking to me" is
/// otherwise unanswerable: a client that silently fell back to its local
/// runtime looks exactly like one that never tried. Splitting the counters by
/// outcome is what distinguishes a working migration from one where every
/// command answers `501` and the client quietly does all the work itself.
#[derive(Debug, Default)]
pub struct CommandStats {
    pub calls: AtomicU64,
    pub ok: AtomicU64,
    pub unimplemented: AtomicU64,
    pub failed: AtomicU64,
}

pub static STATS: CommandStats = CommandStats {
    calls: AtomicU64::new(0),
    ok: AtomicU64::new(0),
    unimplemented: AtomicU64::new(0),
    failed: AtomicU64::new(0),
};

/// What the server decided to do with a forwarded command.
pub enum CommandResult {
    /// Ran successfully; carries the value the local binding would return.
    Ok(JsonValue),
    /// Not migrated yet — the client should invoke locally instead.
    NotImplemented,
    /// Recognised but failed.
    Failed(String),
}

/// Every command this server answers, for the client's capability check.
///
/// The client fetches this once and forwards only what appears here, which is
/// what keeps a half-migrated server fast: an unimplemented command costs no
/// round trip instead of a `501` on every call.
pub fn supported_commands() -> Vec<&'static str> {
    let mut commands: Vec<&'static str> = Vec::new();
    commands.extend_from_slice(groups::COMMANDS);
    commands.extend_from_slice(local::COMMANDS);
    commands.extend_from_slice(vrchat_remote::COMMANDS);
    commands.extend_from_slice(avatars::COMMANDS);
    commands.extend_from_slice(favorites::COMMANDS);
    commands.extend_from_slice(vrchat_extra::COMMANDS);
    commands.extend_from_slice(current_user::COMMANDS);
    commands.extend_from_slice(social::COMMANDS);
    commands.extend_from_slice(profile::COMMANDS);
    commands.extend_from_slice(app_extra::COMMANDS);
    commands.extend_from_slice(external_api::COMMANDS);
    commands.extend_from_slice(HAND_WRITTEN_COMMANDS);
    // The tables overlap — the hand-written list predates the generated ones —
    // so collapse them rather than advertising the same command twice.
    commands.sort_unstable();
    commands.dedup();
    commands
}

/// Commands still written by hand rather than generated per area.
const HAND_WRITTEN_COMMANDS: &[&str] = &[
    "app__vrchat_auth_current_user_get",
    "app__vrchat_auth_visits_get",
    "app__vrchat_friend_status_get",
    "app__vrchat_favorite_groups_get",
    "app__vrchat_avatar_gallery_get",
    "app__game_log_entries_add",
];

/// Builds the per-tenant quick-search runtime (see `social::quick_search_runtime`).
pub(crate) fn quick_search_runtime(
    runtime: &RuntimeHostState,
) -> vrcx_0_application::social::QuickSearchRuntime {
    social::quick_search_runtime(runtime)
}

/// The commands this server can run.
///
/// Split out from [`dispatch`] so membership is testable without standing up a
/// runtime, and so the set of migrated commands is written down in one place.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum CommandKind {
    CurrentUser,
    Visits,
    FriendStatus,
    FavoriteGroups,
    AvatarGallery,
    GameLogEntriesAdd,
}

fn parse_command(name: &str) -> Option<CommandKind> {
    match name {
        "app__vrchat_auth_current_user_get" => Some(CommandKind::CurrentUser),
        "app__vrchat_auth_visits_get" => Some(CommandKind::Visits),
        "app__vrchat_friend_status_get" => Some(CommandKind::FriendStatus),
        "app__vrchat_favorite_groups_get" => Some(CommandKind::FavoriteGroups),
        "app__vrchat_avatar_gallery_get" => Some(CommandKind::AvatarGallery),
        "app__game_log_entries_add" => Some(CommandKind::GameLogEntriesAdd),
        _ => None,
    }
}

/// Runs one command against the server's own VRChat session and database.
///
/// Two hosts are passed because the two halves of the migration need different
/// things: the `vrchat_*` commands run against the live session, while the
/// `local_*` commands are pure database work that only the `local_data` facade
/// knows how to assemble.
pub async fn dispatch(
    runtime: &RuntimeHostState,
    local: &LocalDataRuntime,
    quick_search: &vrcx_0_application::social::QuickSearchRuntime,
    request: CommandRequest,
) -> CommandResult {
    STATS.calls.fetch_add(1, Ordering::Relaxed);
    let result = run(runtime, local, quick_search, request).await;
    match &result {
        CommandResult::Ok(_) => STATS.ok.fetch_add(1, Ordering::Relaxed),
        CommandResult::NotImplemented => STATS.unimplemented.fetch_add(1, Ordering::Relaxed),
        CommandResult::Failed(_) => STATS.failed.fetch_add(1, Ordering::Relaxed),
    };
    result
}

async fn run(
    runtime: &RuntimeHostState,
    local: &LocalDataRuntime,
    quick_search: &vrcx_0_application::social::QuickSearchRuntime,
    request: CommandRequest,
) -> CommandResult {
    // Generated per-area tables first: they cover far more commands than the
    // hand-written table and are the path new migrations should take.
    if let Some(result) = groups::dispatch(runtime, &request.command, &request.args).await {
        return result;
    }
    if let Some(result) = local::dispatch(local, &request.command, &request.args).await {
        return result;
    }
    if let Some(result) = vrchat_remote::dispatch(runtime, &request.command, &request.args).await {
        return result;
    }
    if let Some(result) = avatars::dispatch(runtime, &request.command, &request.args).await {
        return result;
    }
    if let Some(result) = favorites::dispatch(runtime, &request.command, &request.args).await {
        return result;
    }
    if let Some(result) = vrchat_extra::dispatch(runtime, &request.command, &request.args).await {
        return result;
    }
    if let Some(result) = current_user::dispatch(runtime, &request.command, &request.args).await {
        return result;
    }
    if let Some(result) = social::dispatch(runtime, quick_search, &request.command, &request.args)
        .await
    {
        return result;
    }
    if let Some(result) = profile::dispatch(runtime, &request.command, &request.args).await {
        return result;
    }
    if let Some(result) = app_extra::dispatch(runtime, &request.command, &request.args).await {
        return result;
    }
    if let Some(result) = external_api::dispatch(runtime, &request.command, &request.args).await {
        return result;
    }

    let Some(kind) = parse_command(&request.command) else {
        return CommandResult::NotImplemented;
    };

    let web = runtime.web_client();
    let endpoint = VRCHAT_API_DEFAULT_ENDPOINT.to_string();

    match kind {
        CommandKind::CurrentUser => {
            execute(web, current_user_get_input(endpoint)).await
        }
        CommandKind::Visits => execute(web, visits_get_input(endpoint)).await,
        CommandKind::FriendStatus => {
            let input: FriendUserInput = match parse(&request.args) {
                Ok(input) => input,
                Err(error) => return CommandResult::Failed(error),
            };
            match friend_status_get_input(endpoint, input.user_id) {
                Ok((_, request)) => execute(web, request).await,
                Err(error) => CommandResult::Failed(error.to_string()),
            }
        }
        CommandKind::FavoriteGroups => {
            let input: FavoriteGroupsInput = match parse(&request.args) {
                Ok(input) => input,
                Err(error) => return CommandResult::Failed(error),
            };
            execute(
                web,
                favorite_groups_get_input(endpoint, input.n, input.offset, input.owner_id),
            )
            .await
        }
        CommandKind::AvatarGallery => {
            let input: AvatarGalleryInput = match parse(&request.args) {
                Ok(input) => input,
                Err(error) => return CommandResult::Failed(error),
            };
            match avatar_gallery_get_input(endpoint, input.avatar_id) {
                Ok((_, request)) => execute(web, request).await,
                Err(error) => CommandResult::Failed(error.to_string()),
            }
        }
        CommandKind::GameLogEntriesAdd => {
            let kind: GameLogWriteKind = match argument(&request.args, "kind") {
                Ok(kind) => kind,
                Err(error) => return CommandResult::Failed(error),
            };
            let entries: Vec<JsonValue> = match argument(&request.args, "entries") {
                Ok(entries) => entries,
                Err(error) => return CommandResult::Failed(error),
            };
            encode(local.game_log_entries_add(kind, entries))
        }
    }
}

/// Sends a prepared request and serialises the outcome.
///
/// Mirrors what the desktop facade does around the same call: the web client
/// owns the session cookies, so a command needs nothing but its request.
pub(crate) async fn execute(web: &WebClient, request: VrchatApiRequest) -> CommandResult {
    match web.execute_api(request, VrchatScope::Vrchat).await {
        Ok(response) => match serde_json::to_value(response) {
            Ok(value) => CommandResult::Ok(value),
            Err(error) => CommandResult::Failed(format!("failed to encode response: {error}")),
        },
        Err(error) => CommandResult::Failed(error.to_string()),
    }
}

/// Sends a request that a builder may have refused to build.
///
/// The shared request builders do not agree on a shape: some return the request
/// outright, some a `Result`, some a `(id, request)` pair. [`IntoApiRequest`]
/// flattens that, so a generated arm is one line regardless of which it got.
pub(crate) async fn send(
    web: &WebClient,
    built: Result<VrchatApiRequest, String>,
) -> CommandResult {
    match built {
        Ok(request) => execute(web, request).await,
        Err(error) => CommandResult::Failed(error),
    }
}

/// Unifies what the shared request builders return.
pub(crate) trait IntoApiRequest {
    fn into_api_request(self) -> Result<VrchatApiRequest, String>;
}

impl IntoApiRequest for VrchatApiRequest {
    fn into_api_request(self) -> Result<VrchatApiRequest, String> {
        Ok(self)
    }
}

/// The builders return the request alongside whatever identifies the resource,
/// and how many identifiers vary per endpoint — a group id, then a world and
/// instance id. Only the trailing request matters here, so these are generic
/// over the leading elements rather than enumerating every arity.
impl<A> IntoApiRequest for (A, VrchatApiRequest) {
    fn into_api_request(self) -> Result<VrchatApiRequest, String> {
        Ok(self.1)
    }
}

impl<A, B> IntoApiRequest for (A, B, VrchatApiRequest) {
    fn into_api_request(self) -> Result<VrchatApiRequest, String> {
        Ok(self.2)
    }
}

impl<A, B, C> IntoApiRequest for (A, B, C, VrchatApiRequest) {
    fn into_api_request(self) -> Result<VrchatApiRequest, String> {
        Ok(self.3)
    }
}

impl IntoApiRequest for Result<VrchatApiRequest, vrcx_0_vrchat_client::http_api::HttpApiError> {
    fn into_api_request(self) -> Result<VrchatApiRequest, String> {
        self.map_err(|error| error.to_string())
    }
}

/// The `Result` forms of the same shapes. `A`/`B`/`C` are unconstrained on
/// purpose: the identifier types differ per endpoint and are all discarded.
impl<A> IntoApiRequest
    for Result<(A, VrchatApiRequest), vrcx_0_vrchat_client::http_api::HttpApiError>
{
    fn into_api_request(self) -> Result<VrchatApiRequest, String> {
        self.map(|(_, request)| request)
            .map_err(|error| error.to_string())
    }
}

impl<A, B> IntoApiRequest
    for Result<(A, B, VrchatApiRequest), vrcx_0_vrchat_client::http_api::HttpApiError>
{
    fn into_api_request(self) -> Result<VrchatApiRequest, String> {
        self.map(|(_, _, request)| request)
            .map_err(|error| error.to_string())
    }
}

impl<A, B, C> IntoApiRequest
    for Result<(A, B, C, VrchatApiRequest), vrcx_0_vrchat_client::http_api::HttpApiError>
{
    fn into_api_request(self) -> Result<VrchatApiRequest, String> {
        self.map(|(_, _, _, request)| request)
            .map_err(|error| error.to_string())
    }
}

/// Turns an application-layer outcome into what a command answers.
///
/// The generated tables call `application` functions and the `local_data`
/// facade directly. Between them the return types run from `i64` to a page
/// struct to the same `VrchatApiResponse` the desktop bindings use; all that
/// matters is that the value encodes to the JSON the local binding would have
/// returned.
pub(crate) fn encode<T: serde::Serialize>(
    result: vrcx_0_application_core::Result<T>,
) -> CommandResult {
    match result {
        Ok(value) => match serde_json::to_value(value) {
            Ok(value) => CommandResult::Ok(value),
            Err(error) => CommandResult::Failed(format!("failed to encode response: {error}")),
        },
        Err(error) => CommandResult::Failed(error.to_string()),
    }
}

/// Reads one named argument.
///
/// The bindings pass a flat object keyed by the command's parameter names in
/// camelCase, so the caller supplies the key and the type is inferred from the
/// call the value feeds. That inference is the point: a parameter typed as
/// something only `persistence` or `contracts` knows about needs no import in
/// the generated table.
pub(crate) fn argument<T: DeserializeOwned>(args: &JsonValue, key: &str) -> Result<T, String> {
    let value = args
        .get(key)
        .ok_or_else(|| format!("missing `{key}` argument"))?;
    serde_json::from_value(value.clone())
        .map_err(|error| format!("invalid `{key}` argument: {error}"))
}

/// Runs blocking work without stalling the reactor.
///
/// Most of the `local_data` facade is synchronous SQLite, which is why the
/// desktop build runs it on a blocking pool. Inside a multi-threaded reactor
/// `block_in_place` gives the worker the same treatment; under a
/// single-threaded one (`#[tokio::test]`'s default) there is no other task to
/// starve, so running inline is both safe and correct — and panicking there
/// instead would make the generated tables untestable.
pub(crate) fn blocking<T>(work: impl FnOnce() -> T) -> T {
    match tokio::runtime::Handle::try_current() {
        Ok(handle) if handle.runtime_flavor() == tokio::runtime::RuntimeFlavor::MultiThread => {
            tokio::task::block_in_place(work)
        }
        _ => work(),
    }
}

/// Reads the `input` argument a command cannot run without.
///
/// Unlike [`parse`] this has no `Default` fallback: every command reaching here
/// declares a typed input, so a missing one is a caller bug worth reporting
/// rather than a silently empty request.
pub(crate) fn parse_required<T: DeserializeOwned>(args: &JsonValue) -> Result<T, String> {
    let input = args
        .get("input")
        .ok_or_else(|| "missing `input` argument".to_string())?;
    serde_json::from_value(input.clone()).map_err(|error| format!("invalid arguments: {error}"))
}

/// Reads the `input` argument the generated bindings pass.
///
/// Commands with no arguments arrive with an empty object or nothing at all,
/// so a missing key is not an error — a malformed one is.
pub(crate) fn parse<T: DeserializeOwned + Default>(args: &JsonValue) -> Result<T, String> {
    let input = args.get("input").cloned().unwrap_or_default();
    if input.is_null() {
        return Ok(T::default());
    }
    serde_json::from_value(input).map_err(|error| format!("invalid arguments: {error}"))
}

#[derive(Debug, serde::Deserialize, Default)]
#[serde(rename_all = "camelCase")]
struct FriendUserInput {
    user_id: String,
}

#[derive(Debug, serde::Deserialize, Default)]
#[serde(rename_all = "camelCase")]
struct FavoriteGroupsInput {
    n: i32,
    offset: i32,
    owner_id: String,
}

#[derive(Debug, serde::Deserialize, Default)]
#[serde(rename_all = "camelCase")]
struct AvatarGalleryInput {
    avatar_id: String,
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn missing_input_object_falls_back_to_defaults() {
        let parsed: FriendUserInput = parse(&json!({})).expect("defaults");
        assert!(parsed.user_id.is_empty());

        let parsed: FriendUserInput = parse(&json!({ "input": null })).expect("null defaults");
        assert!(parsed.user_id.is_empty());
    }

    #[test]
    fn camel_case_arguments_are_read() {
        let parsed: FriendUserInput =
            parse(&json!({ "input": { "userId": "usr_1" } })).expect("parsed");
        assert_eq!(parsed.user_id, "usr_1");
    }

    #[test]
    fn a_named_argument_is_read_by_its_binding_key() {
        // The bindings send `imageUrl` for a `image_url` parameter, so reading
        // the Rust name would silently miss every argument.
        let read: String = argument(&json!({ "imageUrl": "https://x/y.png" }), "imageUrl")
            .expect("parsed");
        assert_eq!(read, "https://x/y.png");
    }

    #[test]
    fn a_missing_or_wrongly_typed_argument_is_reported() {
        let missing = argument::<String>(&json!({}), "userId").expect_err("should fail");
        assert!(missing.starts_with("missing `userId`"), "got {missing}");

        let wrong = argument::<i32>(&json!({ "limit": "many" }), "limit").expect_err("should fail");
        assert!(wrong.starts_with("invalid `limit`"), "got {wrong}");
    }

    #[test]
    fn the_capability_list_covers_the_database_commands() {
        // The `local_*` table is generated from a different layer than the
        // others, so its presence in the advertised list is worth asserting:
        // if it were forgotten the client would never forward them and every
        // page backed by the database would quietly stay empty.
        let commands = supported_commands();
        for name in [
            "app__avatar_get",
            "app__favorite_list",
            "app__config_list_values",
        ] {
            assert!(commands.contains(&name), "{name} should be advertised");
        }
        assert!(
            !commands.contains(&"app__player_list_current_snapshot"),
            "a command answered from local game state must not be advertised"
        );
    }

    #[test]
    fn wrong_shape_is_reported_not_swallowed() {
        let error = parse::<FavoriteGroupsInput>(&json!({ "input": { "n": "many" } }))
            .expect_err("should fail");
        assert!(error.starts_with("invalid arguments:"), "got {error}");
    }

    #[test]
    fn migrated_commands_are_recognised() {
        for name in [
            "app__vrchat_auth_current_user_get",
            "app__vrchat_auth_visits_get",
            "app__vrchat_friend_status_get",
            "app__vrchat_favorite_groups_get",
            "app__vrchat_avatar_gallery_get"
        ] {
            assert!(parse_command(name).is_some(), "{name} should be migrated");
        }
    }

    #[test]
    fn unmigrated_commands_fall_back_to_local() {
        // Anything unrecognised has to answer `NotImplemented`: that is the
        // signal the client uses to run the command against its own runtime.
        // A typo here would silently black-hole a command instead.
        assert!(parse_command("app__vrchat_group_get").is_none());
        assert!(parse_command("").is_none());
        assert!(parse_command("app__screenshot_capture").is_none());
    }

    #[test]
    fn a_command_is_advertised_once() {
        // Two tables claim some of the same commands, and the capability list
        // is what the client trusts — a duplicate there is not harmless, it
        // makes the list look stale and hides which table actually owns it.
        let commands = supported_commands();
        let mut unique = commands.clone();
        unique.sort_unstable();
        unique.dedup();
        assert_eq!(
            commands.len(),
            unique.len(),
            "the capability list contains duplicates"
        );
        assert!(
            commands.contains(&"app__vrchat_group_get"),
            "generated tables should be advertised"
        );
    }
}
