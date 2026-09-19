//! Dependency bundles for the forwarded commands.
//!
//! The desktop command layer is only a shell: it asks a facade for a bundle of
//! dependencies and hands it to a pure function in the `application` crate.
//! Everything that actually matters — auth-scope checks, remote mutation
//! pacing, diagnostics, request building — lives in that pure function.
//!
//! Assembling the same bundles here is what lets this server run the *same*
//! code instead of a reimplementation of it. The parts are all reached through
//! [`RuntimeHostState`], which owns the running session the commands need.

use std::sync::Arc;

use vrcx_0_application::avatars::{AvatarModerationDeps, AvatarRemote, AvatarRemoteMutationDeps};
use vrcx_0_application::favorites::{
    FavoriteMutationCoordinator, FavoriteMutationRuntimeDeps, FavoriteRemote, FavoriteStore,
};
use vrcx_0_application::social::GroupApiDeps;
use vrcx_0_application_core::{AuthenticatedMutationContext, AvatarCache, RemoteMutationGate, RuntimeAuthScope};
use vrcx_0_application_realtime::RealtimeHostRuntime;
use vrcx_0_composition::RuntimeHostState;
use vrcx_0_outbound_adapters::{
    LocalAvatarApplicationAdapter, LocalFavoriteStore, VrchatFavoriteRemote,
    VrchatGroupRemoteRequests, VrchatRequestAdapter,
};

/// The bundle the group application functions expect.
///
/// Rebuilt per call rather than cached: it is four cheap handles around state
/// the runtime already owns, and a cached copy would pin an `Arc` to a web
/// client that can be swapped when the session is re-established.
pub fn group(runtime: &RuntimeHostState) -> GroupApiDeps {
    let assembly = runtime.desktop_assembly();
    GroupApiDeps::new(
        Arc::new(VrchatRequestAdapter::new(Arc::clone(
            runtime.web_client(),
        ))),
        Arc::new(VrchatGroupRemoteRequests),
        assembly.diagnostics().clone(),
        assembly.sync().clone(),
        assembly.auth_scope().clone(),
        Arc::clone(assembly.remote_mutations()),
    )
}

/// The bundle the avatar remote-mutation commands expect.
///
/// Mirrors `DesktopAvatarRuntime::mutation_deps` exactly, but assembled from
/// [`RuntimeHostState`] instead of the desktop facade's cached fields. The parts
/// are all reachable: the adapter is a thin wrapper over the database, and the
/// remote stub is the same `VrchatAvatarRemote` the desktop build uses. The
/// returned deps borrows `adapter`/`remote` (owned by the caller) and the
/// runtime handles, so it stays valid only for the calling command's scope.
pub fn avatar_mutation<'a>(
    adapter: &'a LocalAvatarApplicationAdapter,
    remote: &'a Arc<dyn AvatarRemote>,
    auth_scope: &'a RuntimeAuthScope,
    remote_mutations: &'a Arc<RemoteMutationGate>,
    realtime: &'a Arc<RealtimeHostRuntime>,
    avatar_cache: &'a Arc<AvatarCache>,
    avatar_moderation: &'a vrcx_0_application::avatars::AvatarModerationRuntime,
) -> Result<AvatarRemoteMutationDeps<'a>, String> {
    let context = AuthenticatedMutationContext::capture(auth_scope, remote_mutations, "Avatar mutation")
        .map_err(|error| error.to_string())?;
    Ok(AvatarRemoteMutationDeps::new(
        adapter,
        remote.as_ref(),
        realtime,
        avatar_cache,
        avatar_moderation,
        context,
    ))
}

/// The bundle `app__vrchat_avatar_moderations_get` expects.
///
/// Unlike the mutations it needs no authenticated-mutation context — it only
/// reads the moderation runtime through the shared remote stub.
pub fn avatar_moderation<'a>(
    remote: &'a Arc<dyn AvatarRemote>,
    auth_scope: &'a RuntimeAuthScope,
) -> AvatarModerationDeps<'a> {
    AvatarModerationDeps::new(remote.as_ref(), auth_scope)
}

/// The `FavoriteMutationCoordinator` the remote favorite commands expect.
///
/// Built straight from [`RuntimeHostState`]: the store is the database-backed
/// local favorite store, the remote stub is the same `VrchatFavoriteRemote`
/// the desktop build uses, and the runtime deps are the cheap handles the
/// coordinator's own `capture` borrows. It is owned and `'static`, so it is
/// rebuilt per call without pinning any runtime handle the session might swap.
pub fn favorite_mutation(runtime: &RuntimeHostState) -> FavoriteMutationCoordinator {
    let assembly = runtime.desktop_assembly();
    let store: Arc<dyn FavoriteStore> =
        Arc::new(LocalFavoriteStore::new(Arc::clone(runtime.database())));
    let remote: Arc<dyn FavoriteRemote> = Arc::new(VrchatFavoriteRemote::new(
        Arc::clone(runtime.web_client()),
        assembly.diagnostics().clone(),
        assembly.sync().clone(),
    ));
    FavoriteMutationCoordinator::new(
        store,
        remote,
        FavoriteMutationRuntimeDeps::new(
            assembly.diagnostics().clone(),
            assembly.sync().clone(),
            assembly.event_bus().clone(),
            assembly.auth_scope().clone(),
            Arc::clone(assembly.remote_mutations()),
        ),
    )
}
