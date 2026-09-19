package com.vrcx0.android.ui.screens

import com.vrcx0.android.ui.i18n.tr
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.vrcx0.android.Phase
import com.vrcx0.android.SessionUiState
import com.vrcx0.android.data.remote.AuthAccountEntry
import com.vrcx0.android.data.remote.TlsPinning
import com.vrcx0.android.data.remote.normalizeBaseUrl
import com.vrcx0.android.ui.theme.VrcxMotion

/**
 * Sign-in, in the order the server actually allows:
 *
 *   1. type a server address
 *   2. claim this device's slot on it (a label, plus an invite code if the
 *      server already carries someone) -- this yields the credential every
 *      later step needs, so it cannot be skipped or reordered
 *   3. pick an account the server already holds (no password), or
 *   4. fall back to username + password, then 2FA if it raises a challenge
 *
 * The account picker comes before the password form on purpose: the server
 * holds the session, so the common case is choosing a name, not typing
 * credentials.
 */
@Composable
fun LoginScreen(
    state: SessionUiState,
    onUseServer: (String, String) -> Unit,
    onClaimTenant: (String, String) -> Unit,
    onPickAccount: (String) -> Unit,
    onPasswordLogin: (String, String, Boolean) -> Unit,
    onSubmitTwoFactor: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var address by rememberSaveable { mutableStateOf(state.address) }
    var pin by rememberSaveable { mutableStateOf(state.certificatePin) }
    var showPin by rememberSaveable { mutableStateOf(state.certificatePin.isNotBlank()) }
    var label by rememberSaveable { mutableStateOf(state.suggestedLabel) }
    var inviteCode by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPasswordForm by rememberSaveable { mutableStateOf(false) }

    // Checked against what will actually be dialled, so the message follows the
    // address as it is typed. Two of the three cases are advisory; a fingerprint
    // that cannot become a pin is not, and blocks the button -- connecting with
    // an unusable pin would look identical to connecting without one.
    val pinWarning = TlsPinning.warning(normalizeBaseUrl(address), pin)
    val pinUnusable = TlsPinning.parse(pin) is TlsPinning.Spki.Invalid

    // Nothing past this point is reachable without a credential: every
    // /v1/auth/* route is protected, so showing the password form earlier would
    // offer the user a button that can only 401.
    val hasTenant = state.token.isNotBlank()
    val canClaim = !hasTenant &&
        state.baseUrl.isNotBlank() &&
        state.phase == Phase.NEED_CLAIM

    // Once connected with no saved accounts, the VRChat sign-in form is the
    // natural next step, so it opens on its own instead of behind a toggle.
    val autoShowPassword = state.phase == Phase.NEED_AUTH && state.accounts.isEmpty()

    Surface(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Spacer(Modifier.height(48.dp))
                Text(
                    text = "连接到你的服务器".tr(),
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "VRCX-0 服务端保存会话，手机只是显示端".tr(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(28.dp))
            }

            item {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("服务器地址".tr()) },
                    placeholder = { Text("192.168.1.1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Optional, and folded away by default: a LAN address is plain
                // HTTP and needs nothing here. It only appears once the server
                // sits behind TLS with a self-signed certificate, which the
                // phone would otherwise refuse -- so the operator pastes the
                // fingerprint the setup script prints, and nothing else has to
                // be trusted.
                if (showPin) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it },
                        label = { Text("证书指纹（SPKI SHA-256）".tr()) },
                        placeholder = { Text("Base64，脚本会打印") },
                        isError = pinUnusable,
                        supportingText = {
                            Text("只在使用 https 地址时需要。留空即以明文连接局域网服务器。".tr())
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                pinWarning?.let { warning ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = warning,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { showPin = !showPin }) {
                        Text(if (showPin) "隐藏证书指纹".tr() else "服务器用了自签证书".tr())
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { onUseServer(address, pin) },
                        enabled = address.isNotBlank() && !state.busy && !pinUnusable
                    ) {
                        Text("连接".tr())
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(16.dp))
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            if (state.busy) {
                item {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Spacer(Modifier.height(16.dp))
                }
            }

            state.error?.let { error ->
                item {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }

            if (state.supportedCommands > 0) {
                item {
                    Text(
                        text = "已发现 ${state.supportedCommands} 条命令",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }

            // Step 2: this device's slot on the server. Everything after it --
            // account list, login, 2FA -- needs the credential this produces, so
            // it is shown before any of them rather than as an error path.
            if (canClaim) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                text = if (state.needsInvite) "加入这台服务器".tr() else "创建第一个用户".tr(),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = if (state.needsInvite) {
                                    "这台服务器已经有用户了，需要运维提供的邀请码".tr()
                                } else {
                                    "这台服务器还没有任何用户，你现在就是第一个".tr()
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = label,
                                onValueChange = { label = it },
                                label = { Text("设备名称".tr()) },
                                supportingText = { Text("服务器上用这个名字区分设备".tr()) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (state.needsInvite) {
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = inviteCode,
                                    onValueChange = { inviteCode = it },
                                    label = { Text("邀请码".tr()) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { onClaimTenant(label, inviteCode) },
                                enabled = label.isNotBlank() &&
                                    (!state.needsInvite || inviteCode.isNotBlank()) &&
                                    !state.busy,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (state.needsInvite) "加入".tr() else "创建".tr())
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }

            if (state.accounts.isNotEmpty()) {
                item {
                    Text(
                        text = "选择账号".tr(),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                }
                items(state.accounts, key = { it.userId }) { account ->
                    AccountRow(account = account, onClick = { onPickAccount(account.userId) })
                    Spacer(Modifier.height(8.dp))
                }
                item { Spacer(Modifier.height(8.dp)) }
            }

            // Password fallback. Shown automatically once connected with no
            // saved accounts (the usual first-run path); otherwise behind a
            // toggle. The 2FA challenge is a separate modal -- see below.
            if (hasTenant && (state.supportedCommands > 0 || state.accounts.isNotEmpty())) {
                item {
                    if (state.phase == Phase.NEED_AUTH && state.accounts.isEmpty()) {
                        Text(
                            text = "登录 VRChat 账号".tr(),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    if (!autoShowPassword && !showPasswordForm) {
                        TextButton(onClick = { showPasswordForm = true }) {
                            Text("用用户名密码登录".tr())
                        }
                    }
                    AnimatedVisibility(
                        visible = showPasswordForm || autoShowPassword,
                        enter = fadeIn() + expandVertically(
                            animationSpec = VrcxMotion.SizeExpressive
                        ),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = username,
                                onValueChange = { username = it },
                                label = { Text("用户名".tr()) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text("密码".tr()) },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = {
                                    onPasswordLogin(username, password, true)
                                },
                                enabled = username.isNotBlank() &&
                                    password.isNotBlank() && !state.busy,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("登录并保存凭据".tr()) }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    // 2FA challenge: a modal that pops over everything until answered.
    if (state.challengeAttemptId != null) {
        TwoFactorDialog(
            methods = state.challengeMethods,
            error = state.challengeError,
            busy = state.busy,
            onVerify = onSubmitTwoFactor
        )
    }
}

@Composable
private fun TwoFactorDialog(
    methods: List<String>,
    error: String?,
    busy: Boolean,
    onVerify: (String) -> Unit
) {
    var code by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        // Forced modal: the sign-in cannot proceed until the code is answered,
        // so tapping outside must not dismiss it.
        onDismissRequest = { },
        confirmButton = {
            Button(
                onClick = { onVerify(code) },
                enabled = code.isNotBlank() && !busy
            ) { Text("验证".tr()) }
        },
        title = { Text("需要二步验证".tr()) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                if (methods.isNotEmpty()) {
                    Text(
                        text = "验证方式：${methods.joinToString()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                }
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("验证码".tr()) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    )
}

@Composable
private fun AccountRow(account: AuthAccountEntry, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .padding(0.dp),
                contentAlignment = Alignment.Center
            ) {
                if (account.iconUrl.isNullOrBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = CircleShape,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = (account.displayName ?: account.username ?: "?")
                                    .firstOrNull()?.uppercaseChar()?.toString().orEmpty(),
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                } else {
                    AsyncImage(
                        model = account.iconUrl,
                        contentDescription = account.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = account.displayName ?: account.username ?: account.userId,
                    style = MaterialTheme.typography.bodyLarge
                )
                if (account.username != null) {
                    Text(
                        text = account.username!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
