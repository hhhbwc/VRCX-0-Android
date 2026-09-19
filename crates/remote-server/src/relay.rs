use std::collections::HashMap;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

use vrcx_0_remote_protocol::{LogIngestAck, LogIngestBatch};

const RELAY_DIR_NAME: &str = "remote-relay";
const FALLBACK_SOURCE: &str = "relayed.log";

/// Landing area for VRChat log lines relayed from the machine running the game.
///
/// The server cannot read the game's log itself, so a client next to the game
/// forwards raw lines and the server appends them here. Downstream this is just
/// another log file: the existing watcher and parser consume it unchanged,
/// which is why the sender is deliberately dumb and ships no parser.
#[derive(Clone)]
pub struct LogRelay {
    dir: Arc<PathBuf>,
    committed: Arc<Mutex<HashMap<String, u64>>>,
}

impl LogRelay {
    pub fn new(app_data_dir: &Path) -> Self {
        Self {
            dir: Arc::new(app_data_dir.join(RELAY_DIR_NAME)),
            committed: Arc::new(Mutex::new(HashMap::new())),
        }
    }

    /// Directory the relayed lines land in. Not read back yet, but the watcher
    /// that consumes it is the next step and needs the path.
    #[allow(dead_code)]
    pub fn dir(&self) -> &Path {
        self.dir.as_path()
    }

    /// Offset the server has committed for a source file. A sender that lost
    /// its own bookmark can ask for this and resume from it. The resumable
    /// acknowledgement already carries this value, so the query endpoint that
    /// would call it is not wired up yet.
    #[allow(dead_code)]
    pub fn committed_offset(&self, source_file: &str) -> u64 {
        let source = sanitize_source_file(source_file);
        self.lock_committed().get(&source).copied().unwrap_or(0)
    }

    /// Appends a relayed batch and reports where the sender should resume.
    ///
    /// A batch that begins before the committed offset is a retry of data
    /// already on disk, so it is acknowledged without being appended a second
    /// time. Acknowledging the committed offset instead of the batch's own end
    /// tells the sender exactly where to back up to, which makes retries after
    /// a dropped response idempotent rather than duplicating lines.
    pub fn append(&self, batch: LogIngestBatch) -> std::io::Result<LogIngestAck> {
        let source = sanitize_source_file(&batch.source_file);

        let mut committed = self.lock_committed();
        let offset = committed.get(&source).copied().unwrap_or(0);

        if batch.start_offset < offset {
            return Ok(LogIngestAck {
                next_offset: offset,
                accepted: 0,
            });
        }

        if batch.lines.is_empty() {
            committed.insert(source, batch.end_offset);
            return Ok(LogIngestAck {
                next_offset: batch.end_offset,
                accepted: 0,
            });
        }

        std::fs::create_dir_all(self.dir.as_path())?;
        let path = self.dir.join(&source);
        let mut file = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(path)?;

        let mut buffer = String::new();
        for line in &batch.lines {
            buffer.push_str(line.trim_end_matches(['\n', '\r']));
            buffer.push('\n');
        }
        file.write_all(buffer.as_bytes())?;
        file.flush()?;

        let accepted = batch.lines.len() as u32;
        committed.insert(source, batch.end_offset);
        Ok(LogIngestAck {
            next_offset: batch.end_offset,
            accepted,
        })
    }

    fn lock_committed(&self) -> std::sync::MutexGuard<'_, HashMap<String, u64>> {
        self.committed
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }
}

/// Reduces a sender-supplied name to a single safe file name.
///
/// The name crosses a trust boundary, so path separators and traversal are
/// stripped rather than validated; anything unrecognisable collapses to a
/// fixed fallback instead of failing the ingest.
fn sanitize_source_file(source_file: &str) -> String {
    let candidate = source_file
        .rsplit(['/', '\\'])
        .next()
        .unwrap_or_default()
        .trim();

    if candidate.is_empty() {
        return FALLBACK_SOURCE.into();
    }

    let sanitized: String = candidate
        .chars()
        .filter(|ch| ch.is_ascii_alphanumeric() || matches!(ch, '.' | '_' | '-'))
        .collect();

    let sanitized = sanitized.trim_matches('.').to_string();
    let is_usable = !sanitized.is_empty() && sanitized.len() <= 128;
    if is_usable {
        sanitized
    } else {
        FALLBACK_SOURCE.into()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn batch(source: &str, start: u64, end: u64, lines: &[&str]) -> LogIngestBatch {
        LogIngestBatch {
            source_file: source.into(),
            start_offset: start,
            end_offset: end,
            lines: lines.iter().map(|line| (*line).to_string()).collect(),
        }
    }

    fn relay(dir: &Path) -> LogRelay {
        LogRelay::new(dir)
    }

    #[test]
    fn appends_lines_and_advances_the_offset() {
        let temp = tempfile_dir();
        let relay = relay(&temp);

        let ack = relay
            .append(batch("output_log.txt", 0, 12, &["one", "two"]))
            .unwrap();

        assert_eq!(ack.next_offset, 12);
        assert_eq!(ack.accepted, 2);
        let written = std::fs::read_to_string(temp.join("remote-relay/output_log.txt")).unwrap();
        assert_eq!(written, "one\ntwo\n");
    }

    #[test]
    fn retrying_an_acknowledged_batch_does_not_duplicate_lines() {
        let temp = tempfile_dir();
        let relay = relay(&temp);

        relay
            .append(batch("output_log.txt", 0, 12, &["one", "two"]))
            .unwrap();
        let retry = relay
            .append(batch("output_log.txt", 0, 12, &["one", "two"]))
            .unwrap();

        assert_eq!(retry.accepted, 0);
        assert_eq!(retry.next_offset, 12);
        let written = std::fs::read_to_string(temp.join("remote-relay/output_log.txt")).unwrap();
        assert_eq!(written, "one\ntwo\n");
    }

    #[test]
    fn a_sender_behind_the_commit_point_is_told_where_to_resume() {
        let temp = tempfile_dir();
        let relay = relay(&temp);

        relay
            .append(batch("output_log.txt", 0, 20, &["a", "b", "c"]))
            .unwrap();
        let stale = relay
            .append(batch("output_log.txt", 8, 14, &["b"]))
            .unwrap();

        assert_eq!(stale.accepted, 0);
        assert_eq!(stale.next_offset, 20);
        assert_eq!(relay.committed_offset("output_log.txt"), 20);
    }

    #[test]
    fn each_source_file_keeps_its_own_offset() {
        let temp = tempfile_dir();
        let relay = relay(&temp);

        relay.append(batch("a.log", 0, 5, &["a"])).unwrap();
        relay.append(batch("b.log", 0, 5, &["b"])).unwrap();

        assert_eq!(relay.committed_offset("a.log"), 5);
        assert_eq!(relay.committed_offset("b.log"), 5);
        assert_eq!(relay.committed_offset("never-seen.log"), 0);
    }

    #[test]
    fn traversal_in_the_source_name_cannot_escape_the_relay_directory() {
        assert_eq!(sanitize_source_file("../../etc/passwd"), "passwd");
        assert_eq!(sanitize_source_file(r"C:\Windows\evil.log"), "evil.log");
        assert_eq!(sanitize_source_file("   "), FALLBACK_SOURCE);
        assert_eq!(sanitize_source_file(".."), FALLBACK_SOURCE);
    }

    fn tempfile_dir() -> PathBuf {
        let unique = format!(
            "vrcx-remote-server-test-{}-{:?}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|elapsed| elapsed.as_nanos())
                .unwrap_or_default()
        );
        let dir = std::env::temp_dir().join(unique);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }
}
