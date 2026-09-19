//! `integrations/external_api` commands: the five pure-HTTP lookups against
//! third-party services (external avatar search, YouTube metadata, GitHub
//! releases/contributors, remote image fetch).
//!
//! The desktop facade (`runtime-host-desktop/src/external_api.rs`) is a thin
//! wrapper that validates its parameters, builds an
//! [`ExternalHttpRequestInput`] from `vrcx_0_contracts::external_api` and runs
//! it through `WebClient::execute_external_api` — the exact entry point the
//! server already holds on `runtime.web_client()`. Each arm below therefore
//! calls the *same* input constructor and the *same* `execute_external_api`
//! the client would use, so requests and responses are byte-identical to the
//! local path.
//!
//! The facade additionally records diagnostics/sync entries for these calls;
//! those are host side-channels rather than the command's data contract and
//! are intentionally not reproduced (consistent with every other migrated
//! module here).
//!
//! Derived from: src-tauri/src/commands/integrations/external_api/service.rs

use std::collections::HashMap;

use serde::Deserialize;
use serde_json::Value as JsonValue;

use vrcx_0_contracts::external_api::{
    avatar_search_get_input, github_contributors_get_input, github_releases_get_input,
    image_data_url_get_input, youtube_video_metadata_get_input, ExternalApiScope,
    ExternalHttpRequestInput,
};
use vrcx_0_composition::RuntimeHostState;

use super::{parse_required, CommandResult};

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AvatarSearchInput {
    #[serde(default)]
    url: String,
    #[serde(default)]
    vrcx_id: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct YoutubeVideoInput {
    #[serde(default)]
    video_id: String,
    #[serde(default)]
    api_key: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct UrlInput {
    #[serde(default)]
    url: String,
    #[serde(default)]
    headers: HashMap<String, String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ImageInput {
    #[serde(default)]
    url: String,
}

/// Same trimming/non-empty validation the facade applies before building the
/// request; an empty parameter is a command failure, not a network one.
fn require_text(value: String, message: &str) -> Result<String, String> {
    let value = value.trim().to_string();
    if value.is_empty() {
        return Err(message.to_string());
    }
    Ok(value)
}

async fn run_external(
    web: &super::WebClient,
    built: Result<(ExternalHttpRequestInput, ExternalApiScope), String>,
) -> CommandResult {
    let (input, scope) = match built {
        Ok(pair) => pair,
        Err(error) => return CommandResult::Failed(error),
    };
    match web.execute_external_api(input, scope).await {
        Ok(response) => match serde_json::to_value(response) {
            Ok(value) => CommandResult::Ok(value),
            Err(error) => CommandResult::Failed(format!("failed to encode response: {error}")),
        },
        Err(error) => CommandResult::Failed(error.to_string()),
    }
}

pub async fn dispatch(
    runtime: &RuntimeHostState,
    command: &str,
    args: &JsonValue,
) -> Option<CommandResult> {
    let web = runtime.web_client();

    match command {
        "app__external_api_avatar_search_get" => {
            let input: AvatarSearchInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            let url = match require_text(input.url, "ExternalApiAvatarSearchGet requires url.") {
                Ok(value) => value,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            let vrcx_id = match require_text(
                input.vrcx_id,
                "ExternalApiAvatarSearchGet requires vrcxId.",
            ) {
                Ok(value) => value,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                run_external(
                    web,
                    Ok((
                        avatar_search_get_input(&url, &vrcx_id),
                        ExternalApiScope::AvatarSearch,
                    )),
                )
                .await,
            )
        }
        "app__external_api_youtube_video_metadata_get" => {
            let input: YoutubeVideoInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            let video_id = match require_text(
                input.video_id,
                "ExternalApiYoutubeVideoMetadataGet requires videoId.",
            ) {
                Ok(value) => value,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            let api_key = match require_text(
                input.api_key,
                "ExternalApiYoutubeVideoMetadataGet requires apiKey.",
            ) {
                Ok(value) => value,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                run_external(
                    web,
                    Ok((
                        youtube_video_metadata_get_input(&video_id, &api_key),
                        ExternalApiScope::Youtube,
                    )),
                )
                .await,
            )
        }
        "app__external_api_github_releases_get" => {
            let input: UrlInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            let url =
                match require_text(input.url, "ExternalApiGithubReleasesGet requires url.") {
                    Ok(value) => value,
                    Err(error) => return Some(CommandResult::Failed(error)),
                };
            Some(
                run_external(
                    web,
                    Ok((
                        github_releases_get_input(&url, input.headers),
                        ExternalApiScope::UpdateRelease,
                    )),
                )
                .await,
            )
        }
        "app__external_api_github_contributors_get" => {
            let input: UrlInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            let url = match require_text(
                input.url,
                "ExternalApiGithubContributorsGet requires url.",
            ) {
                Ok(value) => value,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(
                run_external(
                    web,
                    Ok((
                        github_contributors_get_input(&url, input.headers),
                        ExternalApiScope::GithubContributors,
                    )),
                )
                .await,
            )
        }
        "app__external_api_image_data_url_get" => {
            let input: ImageInput = match parse_required(args) {
                Ok(input) => input,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            let url =
                match require_text(input.url, "ExternalApiImageDataUrlGet requires url.") {
                    Ok(value) => value,
                    Err(error) => return Some(CommandResult::Failed(error)),
                };
            Some(
                run_external(
                    web,
                    Ok((
                        image_data_url_get_input(&url),
                        ExternalApiScope::Image,
                    )),
                )
                .await,
            )
        }
        _ => None,
    }
}

/// Every command answered here, for the capability list on `/v1/health`.
pub const COMMANDS: &[&str] = &[
    "app__external_api_avatar_search_get",
    "app__external_api_youtube_video_metadata_get",
    "app__external_api_github_releases_get",
    "app__external_api_github_contributors_get",
    "app__external_api_image_data_url_get",
];
