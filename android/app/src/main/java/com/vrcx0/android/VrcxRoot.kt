package com.vrcx0.android

import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import com.vrcx0.android.ui.i18n.tr
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vrcx0.android.data.AppSettings
import com.vrcx0.android.ui.screens.LoginScreen
import com.vrcx0.android.ui.screens.MainScreen
import com.vrcx0.android.ui.theme.VrcxTheme

@Composable
fun VrcxRoot(viewModel: SessionViewModel = viewModel()) {
    val appContext = LocalContext.current.applicationContext
    val settings = remember { AppSettings(appContext) }
    // Collected here, at the root, so both the theme (read before any screen
    // exists) and any screen below can see the same values.
    val settingsValues by settings.values.collectAsStateWithLifecycle(
        initialValue = AppSettings.Values()
    )

    VrcxTheme(
        darkTheme = settingsValues.themeMode.isDark(),
        dynamicColor = settingsValues.dynamicColor
    ) {
        val state by viewModel.ui.collectAsStateWithLifecycle()
        val services by viewModel.services.collectAsStateWithLifecycle()
        val snackbarHostState = remember { SnackbarHostState() }
        val updateScope = rememberCoroutineScope()
        val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

        androidx.compose.runtime.CompositionLocalProvider(
            com.vrcx0.android.data.LocalAppSettings provides settingsValues,
            // Provided here rather than read per-screen so every motion primitive
            // resolves the flag once, at the root. A screen that reads the
            // preference directly would keep animating if the value changed
            // mid-composition (the provider forces the whole subtree to recompose).
            com.vrcx0.android.ui.components.LocalReducedMotion provides settingsValues.reducedMotion
        ) {

        // Surface a one-shot "connected" notice, then clear it so it shows once.
        LaunchedEffect(state.connectionNotice) {
            state.connectionNotice?.let { notice ->
                snackbarHostState.showSnackbar(notice)
                viewModel.clearConnectionNotice()
            }
        }

        // Update check: once per app start, after a server is attached (the
        // lookup rides the server's GitHub proxy). "Never remind me" sticks
        // per release tag; anything else re-prompts on the next launch.
        var pendingUpdate by androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf<com.vrcx0.android.update.UpdateChecker.Release?>(null)
        }
        val appCtx = androidx.compose.ui.platform.LocalContext.current.applicationContext
        val appSettings = androidx.compose.runtime.remember {
            com.vrcx0.android.data.AppSettings(appCtx)
        }
        val currentVersionName = remember {
            appCtx.packageManager.getPackageInfo(appCtx.packageName, 0).versionName.orEmpty()
        }
        LaunchedEffect(services) {
            val runner = services?.runner ?: return@LaunchedEffect
            val values = appSettings.values.first()
            val release = com.vrcx0.android.update.UpdateChecker.fetchLatest(
                runner, values.updateRepo
            ) ?: return@LaunchedEffect
            val current = currentVersionName
            if (com.vrcx0.android.update.UpdateChecker.isNewer(release.tagName, current) &&
                release.tagName != values.ignoredUpdateTag
            ) {
                pendingUpdate = release
            }
        }
        pendingUpdate?.let { release ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { pendingUpdate = null },
                title = { Text("发现新版本".tr() + " " + release.tagName) },
                text = {
                    Column {
                        Text(
                            release.body.take(600),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 10,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "更新包在该 release 页面下载".tr(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        uriHandler.openUri(release.htmlUrl)
                        pendingUpdate = null
                    }) { Text("立即更新".tr()) }
                },
                dismissButton = {
                    Row {
                        androidx.compose.material3.TextButton(onClick = {
                            pendingUpdate = null
                        }) { Text("以后再说".tr()) }
                        androidx.compose.material3.TextButton(onClick = {
                            updateScope.launch {
                                appSettings.setIgnoredUpdateTag(release.tagName)
                            }
                            pendingUpdate = null
                        }) { Text("不再提示".tr()) }
                    }
                }
            )
        }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { padding ->
            Surface(
                Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                AnimatedContent(
                    targetState = state.phase,
                    transitionSpec = {
                        val forward = targetState == Phase.READY
                        (fadeIn() + slideInHorizontally(
                            initialOffsetX = { if (forward) it / 4 else -it / 4 }
                        )) togetherWith (fadeOut() + slideOutHorizontally(
                            targetOffsetX = { if (forward) -it / 4 else it / 4 }
                        ))
                    },
                    label = "rootPhase",
                    modifier = Modifier.fillMaxSize()
                ) { phase ->
                    when (phase) {
                        Phase.CHECKING -> Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) { CircularProgressIndicator() }

                        Phase.READY -> MainScreen(
                            state = state,
                            commandRunner = viewModel.commandRunner,
                            services = services,
                            settings = settings,
                            streamFrames = viewModel.streamFrames,
                            onSignOut = viewModel::signOut,
                            onForgetServer = viewModel::forgetServer
                        )

                        Phase.NEED_SERVER, Phase.NEED_CLAIM, Phase.NEED_AUTH -> LoginScreen(
                            state = state,
                            onUseServer = viewModel::useServer,
                            onClaimTenant = viewModel::claimTenant,
                            onPickAccount = viewModel::loginWithSavedAccount,
                            onPasswordLogin = viewModel::loginWithPassword,
                            onSubmitTwoFactor = viewModel::submitTwoFactor
                        )
                    }
                }
            }
        }
        }
    }
}
