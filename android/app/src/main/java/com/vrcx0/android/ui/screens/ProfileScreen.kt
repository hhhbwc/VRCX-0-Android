package com.vrcx0.android.ui.screens

import androidx.compose.runtime.LaunchedEffect
import com.vrcx0.android.ui.sheets.AvatarDetailSheet
import kotlinx.serialization.json.decodeFromJsonElement
import com.vrcx0.android.data.remote.wireJson
import androidx.compose.ui.draw.clip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.clickable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import com.vrcx0.android.ui.i18n.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vrcx0.android.data.AppLanguage
import com.vrcx0.android.data.AppSettings
import com.vrcx0.android.data.remote.trustRankLabel
import com.vrcx0.android.data.SessionServices
import com.vrcx0.android.data.ThemeMode
import com.vrcx0.android.data.displayNameFor
import com.vrcx0.android.ui.components.RemoteAvatar
import com.vrcx0.android.ui.components.RemoteImage
import com.vrcx0.android.ui.components.rememberAvatarNames
import com.vrcx0.android.ui.components.rememberMe
import com.vrcx0.android.ui.components.rememberWorldNames
import kotlinx.coroutines.launch

/**
 * Tab 5 -- the signed-in account.
 *
 * Top to bottom:
 *  1. **资料卡** -- avatar, name, status, pronouns, trust tags. Everything here
 *     reads from the shared user snapshot, so it needs no command of its own
 *     except the avatar/name lookups the rest of the app already runs.
 *  2. **设置** -- the settings that actually have an effect on this device
 *     (see [AppSettings]). The server-side appearance keys are deliberately
 *     *not* offered: those are read by the desktop, so writing them from here
 *     would set a value nothing honours.
 *  3. **账号** -- sign out (drop the VRChat session, keep the tenant) and
 *     forget server (drop everything).
 *
 * Editing the profile is intentionally **read-only** here: every field maps to
 * a VRChat API call that rate-limits hard on repeated writes, and an edit screen
 * that fires on every keystroke would get the account throttled. It is a
 * deliberate P1/P2 split, not an omission.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(
    state: com.vrcx0.android.SessionUiState,
    services: SessionServices?,
    settings: AppSettings?,
    onSignOut: () -> Unit,
    onForgetServer: () -> Unit
) {
    val me = rememberMe(services)
    val worldNames = rememberWorldNames(services)
    val avatarNames = rememberAvatarNames(services)
    val settingsValues = settings?.values?.collectAsState(initial = AppSettings.Values())?.value
        ?: AppSettings.Values()
    val scope = rememberCoroutineScope()

    var confirmSignOut by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    // (avatarId, fallbackName) of the avatar detail sheet to show.
    var avatarDetail by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var editPresence by remember { mutableStateOf(false) }
    var myAvatars by remember { mutableStateOf<List<com.vrcx0.android.data.remote.AvatarSummary>?>(null) }
    var myAvatarsError by remember { mutableStateOf<String?>(null) }

    // "My uploaded avatars" -- a bare JSON array of 40-50 summaries, one call.
    // `avatar_history_list` exists too but answers [] on this deployment (the
    // history is built from desktop game-log data), so a "recent avatars"
    // section would be an empty box; it is left out rather than faked.
    val loadFailed = "加载失败".tr()
    LaunchedEffect(services) {
        val runner = services?.runner ?: return@LaunchedEffect
        runCatching {
            wireJson.decodeFromJsonElement<List<com.vrcx0.android.data.remote.AvatarSummary>>(
                runner("app__my_avatars_get", emptyMap())
            )
        }.onSuccess { myAvatars = it }
            .onFailure { myAvatarsError = it.message ?: loadFailed }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // ---- profile card ----
        val myAvatarName = me?.currentAvatar?.let { avatarNames[it]?.name }
        val homeWorldName = me?.homeLocation?.let { worldNames[it]?.name }

        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RemoteAvatar(
                        services = services,
                        rawUrl = me?.currentAvatarThumbnailImageUrl
                            ?.ifBlank { me?.currentAvatarImageUrl }
                            ?: me?.iconUrl,
                        name = me?.displayName,
                        size = 56.dp,
                        thumbnailSize = com.vrcx0.android.data.repository.ImageProxyRepository.DETAIL_SIZE
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = me?.displayName?.takeIf { it.isNotBlank() } ?: state.displayName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (!me?.username.isNullOrBlank()) {
                            Text(
                                text = me!!.username,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        val statusLine = buildString {
                            append(statusLabel(me?.status))
                            if (!me?.statusDescription.isNullOrBlank()) {
                                append(" · ")
                                append(me?.statusDescription)
                            }
                        }
                        Text(
                            text = statusLine,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = me?.currentAvatar?.isNotBlank() == true) {
                            avatarDetail = me!!.currentAvatar to myAvatarName
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "当前模型".tr(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = myAvatarName ?: "加载中…".tr(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (me?.currentAvatar?.isNotBlank() == true) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                    Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (homeWorldName != null) InfoRow("主页世界".tr(), homeWorldName)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { editPresence = true }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "人称代词".tr(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = me?.pronouns?.takeIf { it.isNotBlank() } ?: "编辑".tr(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { editPresence = true }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "状态文字".tr(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = me?.statusDescription?.takeIf { it.isNotBlank() } ?: "编辑".tr(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                InfoRow("信任等级".tr(), trustLevel(me?.tags.orEmpty()))
            }
        }

        // ---- my uploaded avatars ----
        MyAvatarsSection(
            services = services,
            avatars = myAvatars,
            error = myAvatarsError,
            onOpen = { avatarDetail = it.id to it.name }
        )

        // ---- appearance ----
        AppearanceSection(
            values = settingsValues,
            onTheme = { scope.launch { settings?.setThemeMode(it) } },
            onDynamicColor = { enabled ->
                scope.launch { settings?.setDynamicColor(enabled) }
            }
        )

        // ---- locale (language + time zone) ----
        LocaleSection(
            values = settingsValues,
            onLanguage = { scope.launch { settings?.setLanguage(it) } },
            onTimeZone = { scope.launch { settings?.setTimeZoneId(it) } }
        )

        // ---- settings ----
        SettingsSection(
            settings = settings,
            values = settingsValues,
            onSet = { key, enabled -> scope.launch { settings?.let { apply(it, key, enabled) } } }
        )

        // ---- account ----
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            Column(Modifier.padding(16.dp)) {
                SectionTitle("账号".tr())
                OutlinedButton(
                    onClick = { confirmSignOut = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("退出登录".tr())
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { confirmForget = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("忘记此服务器".tr(), color = MaterialTheme.colorScheme.error)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("退出登录？".tr()) },
            text = { Text("会断开当前 VRChat 账号，但保留这台设备在此服务器上的租户。".tr()) },
            confirmButton = {
                TextButton(onClick = { confirmSignOut = false; onSignOut() }) { Text("退出".tr()) }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) { Text("取消".tr()) }
            }
        )
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("忘记此服务器？".tr()) },
            text = { Text("会清除服务器地址、租户凭据和当前会话，需要重新连接和登录。".tr()) },
            confirmButton = {
                TextButton(onClick = { confirmForget = false; onForgetServer() }) { Text("忘记".tr()) }
            },
            dismissButton = {
                TextButton(onClick = { confirmForget = false }) { Text("取消".tr()) }
            }
        )
    }

    avatarDetail?.let { (avatarId, fallbackName) ->
        AvatarDetailSheet(
            services = services,
            avatarId = avatarId,
            fallbackName = fallbackName,
            onDismiss = { avatarDetail = null }
        )
    }

    if (editPresence) {
        EditPresenceDialog(
            currentStatus = me?.status,
            currentStatusDescription = me?.statusDescription.orEmpty(),
            currentPronouns = me?.pronouns.orEmpty(),
            services = services,
            userId = state.userId,
            onDismiss = { editPresence = false }
        )
    }
}

/**
 * Edits the presence fields VRChat exposes to this account: status, status
 * message and pronouns.
 *
 * The write goes through `app__vrchat_current_user_update`
 * (`{input:{params:{...}}}`, camelCase, `deny_unknown_fields`), so only fields
 * the user actually set are sent -- sending an empty `status` would clear it,
 * which is not what an "edit pronouns" dialog should do.
 *
 * On success the change is applied to the shared snapshot **locally**
 * ([SessionServices.applyPresenceEdit]) rather than by re-reading it: the
 * server treats `status`/`statusDescription` as local-authority fields and
 * strips them from the REST refresh patch, so a refresh would keep rendering
 * the old text. A real refresh only runs when there is no snapshot to patch.
 */
@Composable
private fun EditPresenceDialog(
    currentStatus: String?,
    currentStatusDescription: String,
    currentPronouns: String,
    services: SessionServices?,
    userId: String,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(currentStatus.orEmpty()) }
    var statusText by remember { mutableStateOf(currentStatusDescription) }
    var pronouns by remember { mutableStateOf(currentPronouns) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val loadFailed = "加载失败".tr()
    val saveLabel = "保存".tr()

    // Same order, and the same four values, as the official client's picker.
    // `join me` leads deliberately: it is the most inviting state and the one
    // users reach for most, matching `dialog.user.status.*` ordering upstream.
    val statusOptions = remember {
        listOf("join me", "active", "ask me", "busy")
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("编辑".tr()) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("状态".tr(), style = MaterialTheme.typography.labelSmall)
                // Two per row, not one Row of four: the official labels are up
                // to four characters (请勿打扰), and squeezing four onto one
                // line wraps the longest one onto several lines, which both
                // looks broken and steals height from the text fields below.
                statusOptions.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        pair.forEach { option ->
                            FilterChip(
                                selected = status == option,
                                onClick = {
                                    status = if (status == option) "" else option
                                },
                                label = {
                                    Text(statusLabel(option), maxLines = 1)
                                }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = statusText,
                    onValueChange = { statusText = it },
                    label = { Text("状态文字".tr()) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = pronouns,
                    onValueChange = { pronouns = it },
                    label = { Text("人称代词".tr()) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    scope.launch {
                        saving = true
                        error = null
                        val params = kotlinx.serialization.json.buildJsonObject {
                            if (status.isNotBlank()) put("status", kotlinx.serialization.json.JsonPrimitive(status))
                            if (statusText.isNotBlank() || currentStatusDescription.isNotBlank()) {
                                put("statusDescription", kotlinx.serialization.json.JsonPrimitive(statusText))
                            }
                            put("pronouns", kotlinx.serialization.json.JsonPrimitive(pronouns))
                        }
                        val ok = runCatching {
                            services?.runner?.invoke(
                                "app__vrchat_current_user_update",
                                mapOf("params" to params)
                            )
                        }.isSuccess
                        saving = false
                        if (ok) {
                            // Write the change into the shared snapshot ourselves
                            // instead of waiting for a refresh to reveal it: the
                            // server strips `status`/`statusDescription` out of the
                            // REST refresh patch (they are declared local-authority
                            // fields), so re-reading would keep showing the old
                            // text even though the save succeeded.
                            //
                            // Only fields the user actually touched are forwarded --
                            // a blank status text is a real edit (clearing it), but
                            // an option the user never picked must not be invented
                            // here. Same shape as the request body above, on purpose.
                            services?.applyPresenceEdit(
                                status = status.takeIf { it.isNotBlank() },
                                statusDescription = statusText.takeIf {
                                    statusText.isNotBlank() || currentStatusDescription.isNotBlank()
                                },
                                pronouns = pronouns,
                                // Drops my cached card so the profile sheet
                                // cannot serve the pre-edit status for TTL.
                                selfUserId = userId
                            )
                            onDismiss()
                            // A refresh still runs when the snapshot has not
                            // arrived yet (nothing to patch locally), and is
                            // harmless otherwise: it re-fetches the roster and
                            // leaves the overlay above untouched.
                            if (services?.hasSelfSnapshot != true) {
                                services?.load(userId, force = true)
                            }
                        } else {
                            error = loadFailed
                        }
                    }
                }
            ) { Text(if (saving) "…" else saveLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("取消".tr()) }
        }
    )
}

/**
 * "My uploaded avatars" -- a horizontal strip of this account's own avatar
 * uploads (`app__my_avatars_get`, one call, a bare JSON array). Tapping a cell
 * opens the avatar detail sheet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MyAvatarsSection(
    services: SessionServices?,
    avatars: List<com.vrcx0.android.data.remote.AvatarSummary>?,
    error: String?,
    onOpen: (com.vrcx0.android.data.remote.AvatarSummary) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(16.dp)) {
            SectionTitle("我上传的模型".tr())
            when {
                error != null -> Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                avatars == null -> Text(
                    "加载中…".tr(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                avatars.isEmpty() -> Text(
                    "加入的群组会显示在这里".tr().let { "—" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                else -> LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    items(avatars, key = { it.id }) { avatar ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.width(96.dp)
                        ) {
                            RemoteImage(
                                services = services,
                                rawUrl = avatar.thumbnailImageUrl.ifBlank { avatar.imageUrl },
                                label = avatar.name,
                                size = 88.dp,
                                modifier = Modifier.fillMaxWidth(),
                                cornerRadius = 10.dp
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = avatar.name,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The settings that are real on this device, each with its storage key. */
private enum class SettingKey { REDUCED_MOTION, SHOW_INSTANCE_ID, HIDE_NICKNAMES, HIDE_PRIVATE, HIDE_DEVICES, RELATIVE_TIME }

/**
 * Theme choice and Material You colour, stored on this device.
 *
 * The segmented row reads the live value out of [values], so switching theme
 * repaints the whole app behind the sheet -- the setting is the source, the UI
 * just shows it.
 */
@Composable
private fun AppearanceSection(
    values: AppSettings.Values,
    onTheme: (ThemeMode) -> Unit,
    onDynamicColor: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(16.dp)) {
            SectionTitle("外观".tr())
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = values.themeMode == mode,
                        onClick = { onTheme(mode) },
                        label = { Text(mode.label) }
                    )
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                Spacer(Modifier.height(4.dp))
                SettingRow("动态取色".tr(), "跟随系统壁纸配色（Material You）".tr(), values.dynamicColor) {
                    onDynamicColor(it)
                }
            }
        }
    }
}

/**
 * Language and time zone. Both are device-local (they describe how **this**
 * phone renders), so they persist in [AppSettings], not the server config.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun LocaleSection(
    values: AppSettings.Values,
    onLanguage: (AppLanguage) -> Unit,
    onTimeZone: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(16.dp)) {
            SectionTitle("语言".tr() + " / " + "时区".tr())

            Text(
                text = "语言".tr(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppLanguage.entries.forEach { lang ->
                    FilterChip(
                        selected = values.language == lang,
                        onClick = { onLanguage(lang) },
                        label = { Text(lang.label) }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "时区".tr(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "跟随系统时区".tr(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // A fixed shortlist rather than a picker over all 600+ IANA zones:
            // the common cases are one tap away and "follow system" covers the
            // rest. The system zone is also what an empty value means.
            val zones = remember {
                listOf(
                    "", "Asia/Shanghai", "Asia/Hong_Kong", "Asia/Taipei", "Asia/Singapore",
                    "Asia/Tokyo", "Asia/Seoul", "Asia/Bangkok", "Asia/Vladivostok",
                    "Europe/Moscow", "Europe/Samara", "Asia/Yekaterinburg",
                    "Europe/London", "Europe/Berlin", "Europe/Paris",
                    "America/New_York", "America/Chicago", "America/Los_Angeles", "UTC"
                )
            }
            FlowRow(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                zones.forEach { zone ->
                    val label = if (zone.isBlank()) "跟随系统".tr() else zone.substringAfter('/')
                    FilterChip(
                        selected = values.timeZoneId == zone,
                        onClick = { onTimeZone(zone) },
                        label = { Text(label) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    settings: AppSettings?,
    values: AppSettings.Values,
    onSet: (SettingKey, Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(16.dp)) {
            SectionTitle("设置".tr())
            // Only switches that actually change something are listed here --
            // a toggle that does nothing reads as a broken app faster than a
            // missing option.
            //
            // Reduced motion leads because it is the only switch here that
            // governs how the rest of the app behaves rather than what it shows.
            SettingRow(
                "减少动画".tr(),
                "关闭列表淡入、状态点脉冲等过渡效果".tr(),
                values.reducedMotion
            ) {
                onSet(SettingKey.REDUCED_MOTION, it)
            }
            SettingRow("显示实例 ID".tr(), "在好友所在世界旁显示实例号".tr(), values.showInstanceId) {
                onSet(SettingKey.SHOW_INSTANCE_ID, it)
            }
            SettingRow("隐藏私密动态".tr(), "不在动态里显示私密房间的事件".tr(), values.hidePrivateFromFeed) {
                onSet(SettingKey.HIDE_PRIVATE, it)
            }
            SettingRow("隐藏设备".tr(), "不在好友列表里显示 PC / Android 标签".tr(), values.hideDevicesFromFeed) {
                onSet(SettingKey.HIDE_DEVICES, it)
            }
            SettingRow("相对时间".tr(), "显示“3 分钟前”而不是具体时间".tr(), values.relativeFeedTime) {
                onSet(SettingKey.RELATIVE_TIME, it)
            }
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * VRChat `status` -> the official client's Chinese label.
 *
 * These four are the *official* VRChat social-status names, and their Chinese
 * translations must come from the desktop localization table
 * (`dialog.user.status.*` in `src/localization/zh-CN.json`):
 *
 *   active -> 在线 / join me -> 欢迎加入 / ask me -> 忙碌 / busy -> 请勿打扰
 *
 * Do not "improve" them into descriptive phrases. An earlier revision rendered
 * `join me` as 想找人一起玩 and `ask me` as 想找人说话, which both invented
 * wording the official client never uses *and* shifted the 忙碌/请勿打扰 pair
 * down by one -- the picker read as four wrong options.
 *
 * (Keep this list out of the `when` body: a comment between branches is fine
 * for the parser, but it hides which arm a stray `->` belongs to.)
 */
@Composable
private fun statusLabel(status: String?): String = when (status?.lowercase()) {
    "active" -> "在线".tr()
    "join me" -> "欢迎加入".tr()
    "ask me" -> "忙碌".tr()
    "busy" -> "请勿打扰".tr()
    "offline" -> "离线".tr()
    else -> "在线".tr()
}

/** The highest `system_trust_*` tag, as the official rank name. */
@Composable
private fun trustLevel(tags: List<String>): String {
    val order = listOf(
        "system_trust_legendary",
        "system_trust_veteran",
        "system_trust_trusted",
        "system_trust_known",
        "system_trust_basic",
        "system_trust_user",
        "system_trust_visitor"
    )
    val t = tags.map { it.lowercase() }
    val best = order.firstOrNull { it in t } ?: return "游客".tr()
    return trustRankLabel(best).tr()
}

private suspend fun apply(settings: AppSettings, key: SettingKey, enabled: Boolean) {
    when (key) {
        SettingKey.REDUCED_MOTION -> settings.setReducedMotion(enabled)
        SettingKey.SHOW_INSTANCE_ID -> settings.setShowInstanceId(enabled)
        SettingKey.HIDE_NICKNAMES -> settings.setHideNicknames(enabled)
        SettingKey.HIDE_PRIVATE -> settings.setHidePrivateFromFeed(enabled)
        SettingKey.HIDE_DEVICES -> settings.setHideDevicesFromFeed(enabled)
        SettingKey.RELATIVE_TIME -> settings.setRelativeFeedTime(enabled)
    }
}
