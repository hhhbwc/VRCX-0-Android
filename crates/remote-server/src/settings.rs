use std::env;
use std::path::PathBuf;

/// Default listener.
///
/// Binding every interface is kept as the default because the common deployment
/// is a box behind a reverse proxy or a NAT mapping, and the process has no way
/// to know which. It is no longer *safe* to do so on a public host, though: the
/// credential the server hands out on `POST /v1/tenants` is the only thing
/// between a caller and a whole account, so a host that is reachable from the
/// internet should bind `127.0.0.1` and let a proxy terminate TLS in front.
pub const DEFAULT_BIND_ADDRESS: &str = "0.0.0.0:8790";

/// The `vrcx-server.toml` subset, for people who would rather edit a file
/// than memorise flags.
///
/// Deliberately a hand-rolled `key = "value"` line parser, not a TOML crate:
/// the file has five keys, and keeping the server's dependency tree static
/// matters more on a router than parser generality. Unknown keys are ignored
/// (with a note on stderr) so the example file can carry documentation.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct FileConfig {
    pub bind: Option<String>,
    pub token: Option<String>,
    pub invite: Option<String>,
}

fn parse_config_file(text: &str) -> FileConfig {
    let mut config = FileConfig::default();
    for (index, raw_line) in text.lines().enumerate() {
        let line = raw_line.trim();
        if line.is_empty() || line.starts_with('#') || line.starts_with(';') {
            continue;
        }
        let Some((key, value)) = line.split_once('=') else {
            eprintln!(
                "config: ignoring malformed line {}: {raw_line}",
                index + 1
            );
            continue;
        };
        let key = key.trim().to_ascii_lowercase();
        // Strip one pair of matching quotes; a TOML value may be quoted.
        let value = value.trim();
        let value = value
            .strip_prefix('"')
            .and_then(|v| v.strip_suffix('"'))
            .unwrap_or(value)
            .to_string();
        match key.as_str() {
            "bind" => config.bind = Some(value),
            "token" => config.token = if value.is_empty() { None } else { Some(value) },
            "invite" | "invite_code" => {
                config.invite = if value.is_empty() { None } else { Some(value) }
            }
            other => {
                eprintln!("config: unknown key `{other}` ignored");
            }
        }
    }
    config
}

/// Where to look for a config file, in order.
///
/// `--config <path>` wins; otherwise `vrcx-server.toml` next to the executable
/// and then in the working directory. The executable's own directory is first
/// among the automatic candidates because the common deployment keeps the
/// binary and its configuration together in one folder.
fn discover_config_file(args: &[String]) -> Option<PathBuf> {
    if let Some(explicit) = arg_value(args, "--config") {
        let path = PathBuf::from(explicit);
        if !path.is_file() {
            eprintln!("config: --config {} does not exist", path.display());
            return None;
        }
        return Some(path);
    }
    let name = PathBuf::from("vrcx-server.toml");
    if let Ok(exe) = env::current_exe() {
        let beside = exe.parent()?.join(&name);
        if beside.is_file() {
            return Some(beside);
        }
    }
    if name.is_file() {
        return Some(name);
    }
    None
}

/// The file config, or `FileConfig::default()` when there is no file. A missing
/// file is the normal case for an operator who prefers flags; an unreadable
/// file that *was* asked for is worth one line on stderr, then a fallthrough.
fn load_file_config(args: &[String]) -> FileConfig {
    let Some(path) = discover_config_file(args) else {
        return FileConfig::default();
    };
    match std::fs::read_to_string(&path) {
        Ok(text) => parse_config_file(&text),
        Err(error) => {
            eprintln!(
                "config: could not read {}: {error}",
                path.display()
            );
            FileConfig::default()
        }
    }
}

#[derive(Clone, Debug)]
pub struct ServerOptions {
    pub bind_address: String,
    /// Forces the first tenant's bearer token.
    ///
    /// Kept because a server deployed before tenants existed has clients
    /// holding one server-wide token in their configuration. Adopting that
    /// value as tenant number one's own token is what lets every one of them
    /// keep working across the upgrade without being reconfigured. Ignored once
    /// a tenant already exists — it is a migration input, not a runtime
    /// credential.
    pub token: Option<String>,
    /// Required for every tenant claimed after the first.
    pub invite_code: Option<String>,
    pub force_login: bool,
    /// Print the registry and exit. Runs against the file only, so it is safe
    /// to run while the server is under a supervisor.
    pub list_tenants: bool,
    /// Remove a tenant from the registry and exit. Takes effect on the next
    /// start, because a running process still holds that tenant in memory.
    pub revoke_tenant: Option<String>,
}

impl ServerOptions {
    /// Reads `--flag value` pairs from the process arguments, falling back to
    /// environment variables and then to the defaults above.
    pub fn from_args(args: &[String]) -> Self {
        // Precedence, loosest to tightest: defaults, then the config file,
        // then the environment, then explicit flags. A flag on the command
        // line always wins over a file the operator may have forgotten about.
        let file = load_file_config(args);
        Self {
            bind_address: arg_value(args, "--bind")
                .or_else(|| env_value("VRCX_SERVER_BIND"))
                .or(file.bind)
                .unwrap_or_else(|| DEFAULT_BIND_ADDRESS.into()),
            token: arg_value(args, "--token")
                .or_else(|| env_value("VRCX_SERVER_TOKEN"))
                .or(file.token),
            invite_code: arg_value(args, "--invite")
                .or_else(|| env_value("VRCX_SERVER_INVITE"))
                .or(file.invite),
            force_login: has_flag(args, "--login") || has_flag(args, "-l"),
            list_tenants: has_flag(args, "--list-tenants"),
            revoke_tenant: arg_value(args, "--revoke-tenant"),
        }
    }

    /// The operator command this invocation is, if it is one.
    ///
    /// These run before anything is built or bound, because the point of both is
    /// to work on a server that is already running elsewhere.
    pub fn operator_command(&self) -> Option<OperatorCommand> {
        match (self.list_tenants, self.revoke_tenant.as_deref()) {
            // One of these reads and the other destroys, so there is no safe
            // precedence to pick: guessing wrong either hides a removal the
            // operator asked for or performs one they did not.
            (true, Some(_)) => Some(OperatorCommand::Ambiguous),
            (true, None) => Some(OperatorCommand::ListTenants),
            (false, Some(id)) => Some(OperatorCommand::RevokeTenant(id.trim().to_string())),
            (false, None) => None,
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum OperatorCommand {
    ListTenants,
    RevokeTenant(String),
    Ambiguous,
}

pub const HELP: &str = "\
vrcx-0-remote-server

Usage:
  vrcx-0-remote-server [--bind <address>] [--data-dir <path>]
                       [--token <token>] [--invite <code>] [--login]

Operator commands (these print and exit):
  vrcx-0-remote-server --list-tenants [--data-dir <path>]
  vrcx-0-remote-server --revoke-tenant <tenantId> [--data-dir <path>]

Options:
  --config <path>    TOML configuration file (default: vrcx-server.toml next to
                     the executable, then in the working directory). Keys:
                     bind, token, invite. Flags override the file, the file
                     overrides nothing else. See vrcx-server.toml.example.
  --bind <address>   Listener address (default 0.0.0.0:8790, env VRCX_SERVER_BIND).
                     Bind 127.0.0.1 when a proxy or tunnel sits in front.
  --data-dir <path>  Root directory. One subdirectory per tenant lives under
                     tenants/, and the registry is tenants.json (default: the
                     platform app-data directory).
  --invite <code>    Required to claim a tenant on a server that already has
                     one (env VRCX_SERVER_INVITE). Set this before exposing the
                     server to the internet, or the first caller to reach
                     /v1/tenants claims it.
  --token <token>    Bearer token for the first tenant
                     (env VRCX_SERVER_TOKEN). Migration aid: pass the token an
                     existing deployment's clients already hold and they keep
                     working across the upgrade. Ignored if a tenant exists.
  --login, -l        Force an interactive VRChat login before serving; only
                     useful over SSH on a machine with a terminal, because a
                     client can otherwise sign in through /v1/auth/login
  --list-tenants     Print the tenant registry and exit
  --revoke-tenant <tenantId>
                     Remove a tenant from the registry and exit. Its data
                     directory is left on disk, and the server must be restarted
                     for the removal to take effect
  -h, --help         Show this message

Tenants
  Each user gets a tenant: its own data directory, its own database, its own
  VRChat session and its own bearer token. A client claims one with
  POST /v1/tenants and receives the only copy of that token that will ever
  exist - the server keeps a SHA-256 digest and cannot reproduce it. Losing it
  is recoverable by rotating it, not by looking it up.

  Every other route, including sign-in, needs that token. There is no longer a
  server-wide secret: the old design handed the same token to every client that
  signed in, which meant any user could act as any other.

  The first tenant needs no invitation, so a fresh server can be brought up by
  one person. Set --invite before the server is reachable from the internet -
  otherwise anyone who can open the port can claim it.

Signing in
  A running server with no usable session reports `status: degraded` from
  /v1/health and keeps listening. A client signs in with a VRChat username,
  password and 2FA code; the server stores that tenant's session, and the
  credentials too when the client asks for unattended restarts. Later starts
  then need nobody present.

Keeping --data-dir off flash matters: a server that runs for months will grow
its database past the ~150 MB overlay on most routers, and filling the overlay
corrupts the firmware's own storage.
";

fn has_flag(args: &[String], flag: &str) -> bool {
    args.iter().any(|arg| arg == flag)
}

fn arg_value(args: &[String], name: &str) -> Option<String> {
    let mut iter = args.iter();
    while let Some(arg) = iter.next() {
        if arg == name {
            return iter.next().cloned();
        }
        if let Some(rest) = arg.strip_prefix(&format!("{name}=")) {
            return Some(rest.to_string());
        }
    }
    None
}

fn env_value(name: &str) -> Option<String> {
    env::var(name).ok().filter(|value| !value.trim().is_empty())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn args(values: &[&str]) -> Vec<String> {
        values.iter().map(|value| (*value).to_string()).collect()
    }

    #[test]
    fn defaults_apply_when_nothing_is_passed() {
        let options = ServerOptions::from_args(&args(&[]));
        assert_eq!(options.bind_address, DEFAULT_BIND_ADDRESS);
        assert!(options.token.is_none());
        assert!(options.invite_code.is_none());
        assert!(!options.force_login);
    }

    #[test]
    fn separated_and_inline_flag_forms_both_parse() {
        let separated = ServerOptions::from_args(&args(&["--bind", "127.0.0.1:9001"]));
        assert_eq!(separated.bind_address, "127.0.0.1:9001");

        let inline = ServerOptions::from_args(&args(&["--bind=127.0.0.1:9002"]));
        assert_eq!(inline.bind_address, "127.0.0.1:9002");
    }

    #[test]
    fn login_flag_is_recognised_in_both_spellings() {
        assert!(ServerOptions::from_args(&args(&["--login"])).force_login);
        assert!(ServerOptions::from_args(&args(&["-l"])).force_login);
    }

    #[test]
    fn a_trailing_flag_without_a_value_does_not_panic() {
        let options = ServerOptions::from_args(&args(&["--token"]));
        assert!(options.token.is_none());
    }

    #[test]
    fn the_invite_code_is_read_in_both_spellings() {
        assert_eq!(
            ServerOptions::from_args(&args(&["--invite", "abc"]))
                .invite_code
                .as_deref(),
            Some("abc")
        );
        assert_eq!(
            ServerOptions::from_args(&args(&["--invite=abc"]))
                .invite_code
                .as_deref(),
            Some("abc")
        );
    }

    #[test]
    fn a_plain_run_is_not_an_operator_command() {
        assert_eq!(ServerOptions::from_args(&args(&[])).operator_command(), None);
    }

    #[test]
    fn operator_commands_are_recognised() {
        assert_eq!(
            ServerOptions::from_args(&args(&["--list-tenants"])).operator_command(),
            Some(OperatorCommand::ListTenants)
        );
        assert_eq!(
            ServerOptions::from_args(&args(&["--revoke-tenant", "t1"])).operator_command(),
            Some(OperatorCommand::RevokeTenant("t1".into()))
        );
    }

    #[test]
    fn asking_to_list_and_revoke_at_once_is_refused() {
        let options = ServerOptions::from_args(&args(&["--list-tenants", "--revoke-tenant", "t1"]));
        assert_eq!(
            options.operator_command(),
            Some(OperatorCommand::Ambiguous)
        );
    }
}

#[cfg(test)]
mod file_config_tests {
    use super::*;

    fn args(values: &[&str]) -> Vec<String> {
        values.iter().map(|value| (*value).to_string()).collect()
    }

    #[test]
    fn a_config_file_supplies_values_without_flags() {
        let dir = std::env::temp_dir().join("vrcx-cfg-test-1");
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("vrcx-server.toml");
        std::fs::write(
            &path,
            "# comment\nbind = \"127.0.0.1:9001\"\ntoken = \"abc\"\ninvite = \"inv-1\"\n",
        )
        .unwrap();

        let file = load_file_config(&args(&["--config", path.to_str().unwrap()]));
        assert_eq!(file.bind.as_deref(), Some("127.0.0.1:9001"));
        assert_eq!(file.token.as_deref(), Some("abc"));
        assert_eq!(file.invite.as_deref(), Some("inv-1"));

        let options = ServerOptions::from_args(&args(&["--config", path.to_str().unwrap()]));
        assert_eq!(options.bind_address, "127.0.0.1:9001");
        assert_eq!(options.invite_code.as_deref(), Some("inv-1"));
    }

    #[test]
    fn a_flag_beats_the_config_file() {
        let dir = std::env::temp_dir().join("vrcx-cfg-test-2");
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("vrcx-server.toml");
        std::fs::write(&path, "bind = \"127.0.0.1:9001\"\n").unwrap();

        let options = ServerOptions::from_args(&args(&[
            "--config",
            path.to_str().unwrap(),
            "--bind",
            "127.0.0.1:9002",
        ]));
        assert_eq!(options.bind_address, "127.0.0.1:9002");
    }

    #[test]
    fn comments_and_unknown_keys_are_tolerated() {
        let config = parse_config_file(
            "# only a comment\n; also a comment\nbind = \"1.2.3.4:1\"\nmystery = 5\n",
        );
        assert_eq!(config.bind.as_deref(), Some("1.2.3.4:1"));
        assert!(config.token.is_none());
    }

    #[test]
    fn an_empty_token_value_means_unset() {
        let config = parse_config_file("token = \"\"\n");
        assert!(config.token.is_none());
    }
}
