package net.mildhope.nxtes.sync

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.mildhope.nxtes.R
import net.mildhope.nxtes.nxtes
import java.io.IOException

/** The settings row for sync: who's signed in, and a way into [SyncDialog]. */
@Composable
fun SyncSettings() {
    val sync = LocalContext.current.nxtes.sync
    val account by sync.account.state.collectAsState()
    val status by sync.status.collectAsState()
    var open by remember { mutableStateOf(false) }
    val session = account.session
    Text(stringResource(R.string.sync), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    Text(
        if (session == null) stringResource(R.string.sync_intro) else stringResource(R.string.sync_signed_in_as, session.email) + "\n" + statusText(status),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
    TextButton(onClick = { open = true }) { Text(stringResource(if (session == null) R.string.sync_sign_in else R.string.sync_manage)) }
    if (open) SyncDialog(onDismiss = { open = false })
}

/** Signing in or creating an account, or once signed in, the account, its storage and devices. */
@Composable
fun SyncDialog(onDismiss: () -> Unit) {
    val sync = LocalContext.current.nxtes.sync
    val account by sync.account.state.collectAsState()
    val session = account.session
    if (session == null) SignInDialog(account, onDismiss) else AccountDialog(session, onDismiss)
}

private class Message(@param:StringRes val text: Int, val arg: String? = null)

@Composable
private fun Message.show() = if (arg == null) stringResource(text) else stringResource(text, arg)

@Composable
private fun SignInDialog(saved: AccountFile, onDismiss: () -> Unit) {
    val sync = LocalContext.current.nxtes.sync
    val scope = rememberCoroutineScope()
    var creating by rememberSaveable { mutableStateOf(false) }
    var server by rememberSaveable { mutableStateOf(saved.server) }
    var email by rememberSaveable { mutableStateOf(saved.email) }
    var password by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf(if (saved.expired) Message(R.string.sync_expired) else null) }
    var offerResend by remember { mutableStateOf(false) }

    fun run(action: suspend () -> Unit) {
        busy = true
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = Message(errorMessage(e))
                offerResend = (e as? ApiException)?.code == "email_not_verified"
            } finally {
                busy = false
            }
        }
    }

    val ready = !busy && email.isNotBlank() && password.isNotEmpty() && server.isNotBlank()
    fun submit() = run {
        if (creating && sync.client.register(server, email.trim(), password)) {
            creating = false
            message = Message(R.string.sync_check_email, email.trim())
            offerResend = true
            return@run
        }
        sync.signIn(server, email.trim(), password)
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(if (creating) R.string.sync_create_account else R.string.sync_sign_in)) },
        text = {
            Column(Modifier.width(400.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.sync_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(stringResource(R.string.sync_email)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.sync_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it },
                    label = { Text(stringResource(R.string.sync_server)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                message?.let { Text(it.show(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp)) }
                if (offerResend) {
                    TextButton(enabled = !busy && email.isNotBlank(), onClick = {
                        run {
                            sync.client.resendVerification(server, email.trim())
                            message = Message(R.string.sync_resent)
                        }
                    }) { Text(stringResource(R.string.sync_resend)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                    TextButton(enabled = !busy, onClick = {
                        creating = !creating
                        message = null
                    }) { Text(stringResource(if (creating) R.string.sync_have_account else R.string.sync_new_account)) }
                    if (!creating) {
                        TextButton(enabled = !busy && email.isNotBlank(), onClick = {
                            run {
                                sync.client.requestPasswordReset(server, email.trim())
                                message = Message(R.string.sync_reset_sent, email.trim())
                            }
                        }) { Text(stringResource(R.string.sync_forgot_password)) }
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = ready, onClick = ::submit) {
                Text(stringResource(if (creating) R.string.sync_create_account else R.string.sync_sign_in))
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private enum class AccountAction { SIGN_OUT, DELETE }

@Composable
private fun AccountDialog(session: Session, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sync = context.nxtes.sync
    val scope = rememberCoroutineScope()
    val status by sync.status.collectAsState()
    var usage by remember { mutableStateOf<Usage?>(null) }
    var devices by remember { mutableStateOf(emptyList<DeviceInfo>()) }
    var shares by remember { mutableStateOf(emptyList<ShareInfo>()) }
    var reload by remember { mutableIntStateOf(0) }
    var asking by remember { mutableStateOf<AccountAction?>(null) }

    LaunchedEffect(reload, status.lastSynced) {
        runCatching {
            usage = sync.client.usage()
            devices = sync.client.devices()
            shares = sync.client.shares()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sync)) },
        text = {
            Column(Modifier.width(400.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.sync_signed_in_as, session.email), style = MaterialTheme.typography.bodyLarge)
                Text(session.server.substringAfter("://"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Text(statusText(status), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(enabled = !status.running, onClick = { scope.launch { sync.syncNow(userAsked = true) } }) {
                        Text(stringResource(R.string.sync_now))
                    }
                }
                if (status.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { sync.wifiOnly = !sync.wifiOnly }.padding(vertical = 4.dp),
                ) {
                    Text(stringResource(R.string.sync_wifi_only), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = sync.wifiOnly, onCheckedChange = { sync.wifiOnly = it })
                }
                usage?.let { used ->
                    Section(R.string.sync_storage)
                    LinearProgressIndicator(
                        progress = { if (used.quota > 0) (used.used.toFloat() / used.quota).coerceIn(0f, 1f) else 0f },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                    Text(
                        stringResource(R.string.sync_storage_used, Formatter.formatShortFileSize(context, used.used), Formatter.formatShortFileSize(context, used.quota)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (devices.isNotEmpty()) {
                    Section(R.string.sync_devices)
                    devices.forEach { device ->
                        DeviceRow(device) {
                            scope.launch {
                                runCatching { sync.client.removeDevice(device.id) }
                                reload++
                            }
                        }
                    }
                }
                if (shares.isNotEmpty()) {
                    SharedLinks(shares) { share ->
                        scope.launch {
                            runCatching { sync.client.removeShare(share.id) }
                            reload++
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Row {
                    TextButton(onClick = { asking = AccountAction.SIGN_OUT }) { Text(stringResource(R.string.sync_sign_out)) }
                    TextButton(onClick = { asking = AccountAction.DELETE }) {
                        Text(stringResource(R.string.sync_delete_account), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )

    when (asking) {
        AccountAction.SIGN_OUT -> AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text(stringResource(R.string.sync_sign_out)) },
            text = { Text(stringResource(R.string.sync_sign_out_text)) },
            confirmButton = {
                TextButton(onClick = {
                    asking = null
                    scope.launch { sync.signOut() }
                }) { Text(stringResource(R.string.sync_sign_out)) }
            },
            dismissButton = { TextButton(onClick = { asking = null }) { Text(stringResource(R.string.cancel)) } },
        )
        AccountAction.DELETE -> DeleteAccountDialog(onDismiss = { asking = null })
        null -> Unit
    }
}

@Composable
private fun Section(@StringRes title: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun DeviceRow(device: DeviceInfo, onRemove: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(device.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (device.current) stringResource(R.string.sync_this_device) else stringResource(R.string.sync_last_seen, relative(device.lastSeen)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!device.current) {
            IconButton(onClick = onRemove) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.sync_remove_device, device.name)) }
        }
    }
}

@Composable
private fun DeleteAccountDialog(onDismiss: () -> Unit) {
    val sync = LocalContext.current.nxtes.sync
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.sync_delete_account)) },
        text = {
            Column(Modifier.width(400.dp)) {
                Text(stringResource(R.string.sync_delete_account_text))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.sync_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && password.isNotEmpty(), onClick = {
                busy = true
                scope.launch {
                    try {
                        sync.deleteAccount(password)
                        onDismiss()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = errorMessage(e)
                    } finally {
                        busy = false
                    }
                }
            }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun statusText(status: SyncStatus): String = when {
    status.running -> stringResource(R.string.sync_running)
    status.problem == SyncProblem.OFFLINE -> stringResource(R.string.sync_problem_offline)
    status.problem == SyncProblem.SIGNED_OUT -> stringResource(R.string.sync_problem_signed_out)
    status.problem == SyncProblem.STORAGE_FULL -> stringResource(R.string.sync_problem_storage)
    status.problem == SyncProblem.SERVER -> stringResource(R.string.sync_problem_server)
    status.lastSynced != null && System.currentTimeMillis() - status.lastSynced < DateUtils.MINUTE_IN_MILLIS -> stringResource(R.string.sync_just_now)
    status.lastSynced != null -> stringResource(R.string.sync_last, relative(status.lastSynced))
    else -> stringResource(R.string.sync_never)
}

private fun relative(time: Long): String =
    DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

@StringRes
internal fun errorMessage(e: Throwable): Int = when ((e as? ApiException)?.code) {
    "bad_credentials" -> R.string.error_bad_credentials
    "email_not_verified" -> R.string.error_email_not_verified
    "email_taken" -> R.string.error_email_taken
    "weak_password" -> R.string.error_weak_password
    "bad_email" -> R.string.error_bad_email
    "registration_closed" -> R.string.error_registration_closed
    "device_limit" -> R.string.error_device_limit
    "slow_down" -> R.string.error_slow_down
    "quota_exceeded" -> R.string.sync_problem_storage
    "too_large" -> R.string.error_too_large
    null -> if (e is IOException) R.string.error_offline else R.string.error_server
    else -> R.string.error_server
}
