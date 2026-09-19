use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;

use serde_json::Value;
use tokio::sync::broadcast;
use vrcx_0_application_core::{
    format_runtime_output_event, RuntimeEventSink, RuntimeOutputLevel, RuntimeOutputMode,
    RuntimeOutputLine,
};
use vrcx_0_remote_protocol::StreamFrame;

/// How many frames a slow client may fall behind before it is told it lagged.
/// Realtime social events are worthless once stale, so this stays small: it is
/// better to drop a burst and resync than to buffer minutes of history.
const BROADCAST_CAPACITY: usize = 512;

/// Fan-out point for realtime events.
///
/// `RuntimeEventBus` exposes exactly one sink slot, which is what the headless
/// binary already installs. Claiming that slot with a sink that forwards into a
/// broadcast channel gives every connected client the same event stream without
/// touching a single upstream crate.
#[derive(Clone)]
pub struct EventHub {
    sender: broadcast::Sender<Arc<StreamFrame>>,
    sequence: Arc<AtomicU64>,
}

impl Default for EventHub {
    fn default() -> Self {
        Self::new()
    }
}

impl EventHub {
    pub fn new() -> Self {
        let (sender, _receiver) = broadcast::channel(BROADCAST_CAPACITY);
        Self {
            sender,
            sequence: Arc::new(AtomicU64::new(0)),
        }
    }

    pub fn subscribe(&self) -> broadcast::Receiver<Arc<StreamFrame>> {
        self.sender.subscribe()
    }

    /// Number of clients currently attached, for logging only.
    pub fn receiver_count(&self) -> usize {
        self.sender.receiver_count()
    }

    fn next_sequence(&self) -> u64 {
        self.sequence.fetch_add(1, Ordering::Relaxed) + 1
    }

    fn publish(&self, frame: StreamFrame) {
        // A send error only means nobody is listening yet, which is normal
        // before the first client connects.
        let _ = self.sender.send(Arc::new(frame));
    }
}

/// `RuntimeEventSink` implementation handed to `RuntimeHostState`.
///
/// Cloneable so it can be wrapped by another sink that also fans out, which is
/// how the console echo is layered in front of the broadcast.
#[derive(Clone)]
pub struct BroadcastEventSink {
    hub: EventHub,
}

impl BroadcastEventSink {
    pub fn new(hub: EventHub) -> Self {
        Self { hub }
    }
}

impl RuntimeEventSink for BroadcastEventSink {
    fn emit(&self, event: &str, payload: Value) {
        let seq = self.hub.next_sequence();
        self.hub.publish(StreamFrame::Event {
            event: event.to_string(),
            payload,
            seq,
        });
    }
}

/// Renders runtime events to the console so an operator tailing the server
/// still sees sign-in progress and fatal errors.
///
/// It wraps the broadcast sink rather than replacing it, so the console gets
/// everything the stream does. With several tenants sharing one stdout the
/// label is what keeps an operator able to tell whose sign-in just failed.
#[derive(Clone)]
pub struct ConsoleEchoSink {
    label: Arc<str>,
    inner: BroadcastEventSink,
}

impl ConsoleEchoSink {
    pub fn new(label: impl Into<Arc<str>>, inner: BroadcastEventSink) -> Self {
        Self {
            label: label.into(),
            inner,
        }
    }
}

impl RuntimeEventSink for ConsoleEchoSink {
    fn emit(&self, event: &str, payload: Value) {
        self.inner.emit(event, payload.clone());
        if let Some(output) =
            format_runtime_output_event(RuntimeOutputMode::Headless, event, &payload)
        {
            print_output(&self.label, output);
        }
    }
}

fn print_output(label: &str, output: RuntimeOutputLine) {
    match output.level {
        RuntimeOutputLevel::Info => println!("[{label}] {}", output.message),
        RuntimeOutputLevel::Warn | RuntimeOutputLevel::Error => {
            eprintln!("[{label}] {}", output.message)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use vrcx_0_remote_protocol::PROTOCOL_VERSION;

    #[test]
    fn emitted_events_carry_a_monotonic_sequence() {
        let hub = EventHub::new();
        let mut receiver = hub.subscribe();
        let sink = BroadcastEventSink::new(hub.clone());

        sink.emit("realtimeFeedProjection", serde_json::json!({ "rows": 1 }));
        sink.emit("realtimeFeedProjection", serde_json::json!({ "rows": 2 }));

        let first = receiver.try_recv().unwrap();
        let second = receiver.try_recv().unwrap();
        let mut sequences = Vec::new();
        for frame in [first, second] {
            match frame.as_ref() {
                StreamFrame::Event { seq, .. } => sequences.push(*seq),
                other => panic!("expected an event frame, got {other:?}"),
            }
        }
        assert_eq!(sequences, vec![1, 2]);
    }

    #[test]
    fn hello_frame_carries_the_protocol_version() {
        let encoded = serde_json::to_value(StreamFrame::Hello {
            protocol_version: PROTOCOL_VERSION,
            app_version: "0.1.0".into(),
        })
        .unwrap();

        assert_eq!(encoded["kind"], "hello");
        assert_eq!(encoded["protocolVersion"], PROTOCOL_VERSION);
    }

    #[test]
    fn publishing_without_subscribers_is_not_an_error() {
        let hub = EventHub::new();
        hub.publish(StreamFrame::Lagged { skipped: 3 });
        assert_eq!(hub.receiver_count(), 0);
    }
}
