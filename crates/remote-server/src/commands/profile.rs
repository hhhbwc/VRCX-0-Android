//! Profile backup/restore and note-export commands.
//!
//! Both families route through accessors that already live on the composition
//! `RuntimeHostState` (`profile_backup()` / `note_export()`), so the server
//! reuses the exact same runtimes the desktop shell uses.
//!
//! Path semantics differ from the desktop: `run_manual(target_path)` and
//! `validate_restore(path)` now refer to paths on the server host (the router's
//! data disk), which matches the thin-client split where the server owns all
//! data. `request_restore`'s desktop-only "restart the app" side effect is
//! intentionally skipped here.

use serde_json::Value as JsonValue;

use vrcx_0_application::social::NoteExportStartInput;
use vrcx_0_composition::RuntimeHostState;

use super::{argument, blocking, encode, parse_required, CommandResult};

pub const COMMANDS: &[&str] = &[
    "app__profile_backup_get_settings",
    "app__profile_backup_set_settings",
    "app__profile_backup_run_manual",
    "app__profile_backup_retry_delivery",
    "app__profile_backup_discard_pending",
    "app__profile_backup_dismiss_error",
    "app__profile_backup_current_status",
    "app__profile_restore_validate",
    "app__profile_restore_request",
    "app__profile_restore_discard_staged",
    "app__profile_restore_take_last_result",
    "app__profile_restore_rollback_state",
    "app__profile_restore_clear_rollback",
    "app__note_export_start",
    "app__note_export_status",
    "app__note_export_cancel",
];

pub async fn dispatch(
    runtime: &RuntimeHostState,
    command: &str,
    args: &JsonValue,
) -> Option<CommandResult> {
    let backup = runtime.profile_backup();
    let note_export = runtime.note_export();

    match command {
        "app__profile_backup_get_settings" => {
            Some(encode(blocking(|| Ok(backup.settings()))))
        }
        "app__profile_backup_set_settings" => {
            let settings = match parse_required(args) {
                Ok(value) => value,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(blocking(move || {
                Ok(backup.set_settings(settings))
            })))
        }
        "app__profile_backup_run_manual" => {
            let target_path: String = match argument(args, "targetPath") {
                Ok(value) => value,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(blocking(move || {
                Ok(backup.run_manual(target_path))
            })))
        }
        "app__profile_backup_retry_delivery" => {
            Some(encode(blocking(|| Ok(backup.retry_delivery()))))
        }
        "app__profile_backup_discard_pending" => {
            Some(encode(blocking(|| Ok(backup.discard_pending()))))
        }
        "app__profile_backup_dismiss_error" => {
            Some(encode(blocking(|| Ok(backup.dismiss_error()))))
        }
        "app__profile_backup_current_status" => {
            Some(encode(blocking(|| Ok(backup.current_status()))))
        }
        "app__profile_restore_validate" => {
            let path: String = match argument(args, "path") {
                Ok(value) => value,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(blocking(move || {
                Ok(backup.validate_restore(std::path::Path::new(&path)))
            })))
        }
        "app__profile_restore_request" => {
            let expected_sha256: String = match argument(args, "expectedSha256") {
                Ok(value) => value,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            // Desktop additionally restarts the app when `restart_required`;
            // the server keeps running and just reports the outcome.
            Some(encode(blocking(move || {
                Ok(backup.request_restore(&expected_sha256))
            })))
        }
        "app__profile_restore_discard_staged" => {
            Some(encode(blocking(|| {
                backup.discard_staged_restore().map(|_| ())
            })))
        }
        "app__profile_restore_take_last_result" => {
            Some(encode(blocking(|| backup.take_last_restore_result())))
        }
        "app__profile_restore_rollback_state" => {
            Some(encode(blocking(|| backup.restore_rollback_state())))
        }
        "app__profile_restore_clear_rollback" => {
            Some(encode(blocking(|| Ok(backup.clear_restore_rollback()))))
        }
        "app__note_export_start" => {
            let input: NoteExportStartInput = match parse_required(args) {
                Ok(value) => value,
                Err(error) => return Some(CommandResult::Failed(error)),
            };
            Some(encode(blocking(move || note_export.start(input))))
        }
        "app__note_export_status" => {
            Some(encode(blocking(|| Ok(note_export.status()))))
        }
        "app__note_export_cancel" => {
            Some(encode(blocking(|| Ok(note_export.cancel()))))
        }
        _ => None,
    }
}
