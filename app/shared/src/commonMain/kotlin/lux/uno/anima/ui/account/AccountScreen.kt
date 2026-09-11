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
import lux.uno.anima.sync.AccountResult
import lux.uno.anima.sync.SyncStatus
import lux.uno.anima.ui.components.AnimaTextField
import lux.uno.anima.ui.components.AnimaTopBar
import lux.uno.anima.ui.components.FormSection
import lux.uno.anima.ui.components.GhostButton
import lux.uno.anima.ui.components.PrimaryButton
import lux.uno.anima.ui.components.ScreenSurface
import lux.uno.anima.ui.theme.AnimaTheme

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
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenSurface(modifier) { contentModifier ->
        Column(contentModifier.safeDrawingPadding().fillMaxSize()) {
            AnimaTopBar(title = "Account", onBack = onBack)

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
    FormSection(environment.label) {
        Text(
            "This environment keeps everything on this device and never syncs, so there is " +
                "no account to sign in to.",
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

    FormSection("Sign in") {
        Text(
            "Your subscriptions are on this device already. Signing in is what keeps them " +
                "the same on your other ones.",
            style = MaterialTheme.typography.bodySmall,
            color = AnimaTheme.palette.muted,
        )
        AnimaTextField(
            value = email,
            onValueChange = { email = it },
            label = "Email",
            placeholder = "you@example.com",
            keyboardType = KeyboardType.Email,
        )
        AnimaTextField(
            value = password,
            onValueChange = { password = it },
            label = "Password",
            keyboardType = KeyboardType.Password,
            visualTransformation = PasswordVisualTransformation(),
        )

        state.message?.let { Message(it) }

        PrimaryButton(
            label = if (state.busy) "Working…" else "Sign in",
            onClick = { onSignIn(email, password) },
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth(),
        )
        GhostButton(
            label = "Create an account",
            onClick = { onRegister(email, password) },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Signing in to ${state.environment.serverUrl}",
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

    FormSection("Signed in") {
        Text(state.email ?: "Signed in", style = MaterialTheme.typography.bodyMedium)
        Text(
            state.environment.serverUrl.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = AnimaTheme.palette.faint,
        )
        state.message?.let { Message(it) }
    }

    SyncSection(state, onSyncNow)

    FormSection("Sign out") {
        Text(
            "Ends the session on this device. Your subscriptions stay here unless you ask " +
                "for them to be removed.",
            style = MaterialTheme.typography.bodySmall,
            color = AnimaTheme.palette.muted,
        )
        GhostButton("Sign out", { confirming = true }, modifier = Modifier.fillMaxWidth())
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
    FormSection("Sync") {
        Text(state.headline(), style = MaterialTheme.typography.titleSmall)
        Text(
            state.detail(),
            style = MaterialTheme.typography.bodySmall,
            color = AnimaTheme.palette.muted,
        )
        GhostButton("Sync now", onSyncNow, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * Keeping something back and failing to send it are different situations, and a person has
 * to be able to tell which one they are in. Collapsing them into one "not synced" is how a
 * sync app stops being believed.
 */
private fun AccountState.headline(): String = when {
    syncStatus == SyncStatus.Syncing -> "Syncing"
    syncStatus == SyncStatus.Failed -> "Last attempt failed"
    syncStatus == SyncStatus.Offline && pendingCount > 0 -> "$pendingCount waiting to go up"
    syncStatus == SyncStatus.Offline -> "Cannot reach the server"
    pendingCount > 0 -> "$pendingCount waiting to go up"
    else -> "Up to date"
}

private fun AccountState.detail(): String = when {
    syncStatus == SyncStatus.Syncing -> "Sending and collecting changes."
    syncStatus == SyncStatus.Failed ->
        "The server was reached and refused the exchange. Nothing here has been lost."
    syncStatus == SyncStatus.Offline ->
        "Everything still works. Changes are kept here and go up when the server can be " +
            "reached again."
    pendingCount > 0 -> "Changes made here have not reached the server yet."
    else -> "Everything here has reached the server."
}

@Composable
private fun SignOutDialog(
    pendingCount: Int,
    onDismiss: () -> Unit,
    onSignOut: (alsoDeleteLocalData: Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sign out?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Your subscriptions stay on this device, and your email address is " +
                        "remembered so signing back in only asks for a password.",
                )
                if (pendingCount > 0) {
                    Text(
                        "$pendingCount change${if (pendingCount == 1) "" else "s"} here " +
                            "hasn't reached the server yet. Removing them now would lose them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        // Keeping the data is the confirm button, because it is the one that cannot go
        // wrong. Removing it is offered as the quieter option, never the default.
        confirmButton = { TextButton(onClick = { onSignOut(false) }) { Text("Sign out, keep data") } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(onClick = { onSignOut(true) }) {
                    Text("Remove data", color = MaterialTheme.colorScheme.error)
                }
            }
        },
    )
}

@Composable
private fun Message(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

/** Turns a result into something worth reading. Null means it worked. */
fun AccountResult.message(): String? = when (this) {
    AccountResult.Success -> null
    AccountResult.Rejected -> "That email and password do not match an account."
    AccountResult.RegistrationClosed ->
        "This server is not accepting new accounts. Ask whoever runs it for a join " +
            "password, or sign in with an account that already exists."
    AccountResult.WrongJoinPassword ->
        "That join password is wrong. It is set in the developer screen under the server " +
            "address, and whoever runs the server can tell you what it should be."
    AccountResult.Unreachable ->
        "Could not reach the server. Check the address in the developer screen, or try again " +
            "when you are back online."
    is AccountResult.Refused -> reason
}
