package lux.uno.anima.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import lux.uno.anima.dev.AppEnvironment
import lux.uno.anima.resources.*
import lux.uno.anima.sync.AccountResult
import lux.uno.anima.sync.SyncStatus
import lux.uno.anima.ui.components.AnimaTextField
import lux.uno.anima.ui.components.AnimaTopBar
import lux.uno.anima.ui.components.FormSection
import lux.uno.anima.ui.components.GhostButton
import lux.uno.anima.ui.components.PrimaryButton
import lux.uno.anima.ui.components.ScreenSurface
import lux.uno.anima.ui.text.label
import lux.uno.anima.ui.theme.AnimaTheme
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Everything the account screen needs to know, so it stays free of wiring. */
data class AccountState(
    val environment: AppEnvironment,
    val signedIn: Boolean,
    /** Remembered from the last sign-in, so nobody has to type it twice. */
    val email: String?,
    val syncStatus: SyncStatus,
    val pendingCount: Int,
    val busy: Boolean,
    val message: String?,
)

@Composable
fun AccountScreen(
    state: AccountState,
    onSignIn: (email: String, password: String) -> Unit,
    onRegister: (email: String, password: String) -> Unit,
    onSignOut: (alsoDeleteLocalData: Boolean) -> Unit,
    onSyncNow: () -> Unit,
    /** Null where a rail is showing, which is the way back to the dashboard instead. */
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    ScreenSurface(modifier) { contentModifier ->
        Column(contentModifier.safeDrawingPadding().fillMaxSize()) {
            AnimaTopBar(title = stringResource(Res.string.account_title), onBack = onBack)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(26.dp),
            ) {
                when {
                    !state.environment.syncs -> NoServerSection(state.environment)
                    state.signedIn -> SignedInSection(state, onSignOut, onSyncNow)
                    else -> SignInSection(state, onSignIn, onRegister)
                }
            }
        }
    }
}

@Composable
private fun NoServerSection(environment: AppEnvironment) {
    FormSection(environment.label()) {
        Text(
            stringResource(Res.string.account_no_server_body),
            style = MaterialTheme.typography.bodyMedium,
            color = AnimaTheme.palette.muted,
        )
    }
}

@Composable
private fun SignInSection(
    state: AccountState,
    onSignIn: (String, String) -> Unit,
    onRegister: (String, String) -> Unit,
) {
    // Seeded from what was remembered, and keyed on it so it fills in the moment the
    // stored address arrives rather than only on the next visit.
    var email by rememberSaveable(state.email) { mutableStateOf(state.email.orEmpty()) }
    var password by rememberSaveable { mutableStateOf("") }
    val canSubmit = email.isNotBlank() && password.isNotBlank() && !state.busy

    FormSection(stringResource(Res.string.account_sign_in)) {
        Text(
            stringResource(Res.string.account_sign_in_body),
            style = MaterialTheme.typography.bodySmall,
            color = AnimaTheme.palette.muted,
        )
        AnimaTextField(
            value = email,
            onValueChange = { email = it },
            label = stringResource(Res.string.account_email),
            placeholder = stringResource(Res.string.account_email_placeholder),
            keyboardType = KeyboardType.Email,
        )
        AnimaTextField(
            value = password,
            onValueChange = { password = it },
            label = stringResource(Res.string.account_password),
            keyboardType = KeyboardType.Password,
            visualTransformation = PasswordVisualTransformation(),
        )

        state.message?.let { Message(it) }

        PrimaryButton(
            label = stringResource(if (state.busy) Res.string.account_working else Res.string.account_sign_in),
            onClick = { onSignIn(email, password) },
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth(),
        )
        GhostButton(
            label = stringResource(Res.string.account_create),
            onClick = { onRegister(email, password) },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(Res.string.account_signing_in_to, state.environment.serverUrl.orEmpty()),
            style = MaterialTheme.typography.bodySmall,
            color = AnimaTheme.palette.faint,
        )
    }
}

@Composable
private fun SignedInSection(
    state: AccountState,
    onSignOut: (Boolean) -> Unit,
    onSyncNow: () -> Unit,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }

    FormSection(stringResource(Res.string.account_signed_in)) {
        Text(
            state.email ?: stringResource(Res.string.account_signed_in),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            state.environment.serverUrl.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = AnimaTheme.palette.faint,
        )
        state.message?.let { Message(it) }
    }

    SyncSection(state, onSyncNow)

    val signOut = stringResource(Res.string.account_sign_out)
    FormSection(signOut) {
        Text(
            stringResource(Res.string.account_sign_out_body),
            style = MaterialTheme.typography.bodySmall,
            color = AnimaTheme.palette.muted,
        )
        GhostButton(signOut, { confirming = true }, modifier = Modifier.fillMaxWidth())
    }

    if (confirming) {
        SignOutDialog(
            pendingCount = state.pendingCount,
            onDismiss = { confirming = false },
            onSignOut = { deleteLocal ->
                confirming = false
                onSignOut(deleteLocal)
            },
        )
    }
}

@Composable
private fun SyncSection(state: AccountState, onSyncNow: () -> Unit) {
    FormSection(stringResource(Res.string.account_sync)) {
        Text(state.headline(), style = MaterialTheme.typography.titleSmall)
        Text(
            state.detail(),
            style = MaterialTheme.typography.bodySmall,
            color = AnimaTheme.palette.muted,
        )
        GhostButton(
            stringResource(Res.string.account_sync_now),
            onSyncNow,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Keeping something back and failing to send it are different situations, and a person has
 * to be able to tell which one they are in. Collapsing them into one "not synced" is how a
 * sync app stops being believed.
 */
@Composable
private fun AccountState.headline(): String = when {
    syncStatus == SyncStatus.Syncing -> stringResource(Res.string.sync_headline_syncing)
    syncStatus == SyncStatus.Failed -> stringResource(Res.string.sync_headline_failed)
    syncStatus == SyncStatus.Offline && pendingCount > 0 ->
        pluralStringResource(Res.plurals.sync_headline_pending, pendingCount, pendingCount)
    syncStatus == SyncStatus.Offline -> stringResource(Res.string.sync_headline_offline)
    pendingCount > 0 ->
        pluralStringResource(Res.plurals.sync_headline_pending, pendingCount, pendingCount)
    else -> stringResource(Res.string.sync_headline_up_to_date)
}

@Composable
private fun AccountState.detail(): String = stringResource(
    when {
        syncStatus == SyncStatus.Syncing -> Res.string.sync_detail_syncing
        syncStatus == SyncStatus.Failed -> Res.string.sync_detail_failed
        syncStatus == SyncStatus.Offline -> Res.string.sync_detail_offline
        pendingCount > 0 -> Res.string.sync_detail_pending
        else -> Res.string.sync_detail_up_to_date
    },
)

@Composable
private fun SignOutDialog(
    pendingCount: Int,
    onDismiss: () -> Unit,
    onSignOut: (alsoDeleteLocalData: Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.account_sign_out_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(Res.string.account_sign_out_dialog_body))
                if (pendingCount > 0) {
                    Text(
                        pluralStringResource(
                            Res.plurals.account_sign_out_pending,
                            pendingCount,
                            pendingCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        // Keeping the data is the confirm button, because it is the one that cannot go
        // wrong. Removing it is offered as the quieter option, never the default.
        confirmButton = {
            TextButton(onClick = { onSignOut(false) }) {
                Text(stringResource(Res.string.account_sign_out_keep_data))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
                TextButton(onClick = { onSignOut(true) }) {
                    Text(stringResource(Res.string.account_remove_data), color = MaterialTheme.colorScheme.error)
                }
            }
        },
    )
}

@Composable
private fun Message(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

/**
 * Turns a result into something worth reading. Null means it worked.
 *
 * Suspending rather than composable because it is called from the coroutine that made the
 * request: the message is resolved once, there and then, and kept as state.
 */
suspend fun AccountResult.message(): String? = when (this) {
    AccountResult.Success -> null
    AccountResult.Rejected -> getString(Res.string.account_error_rejected)
    AccountResult.RegistrationClosed -> getString(Res.string.account_error_registration_closed)
    AccountResult.WrongJoinPassword -> getString(Res.string.account_error_wrong_join_password)
    AccountResult.Unreachable -> getString(Res.string.account_error_unreachable)
    // Whatever the server said, which is the one string in the app no translation reaches.
    is AccountResult.Refused -> reason
}
