package lux.uno.anima.ui.dev

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import lux.uno.anima.dev.AppEnvironment
import lux.uno.anima.dev.Environments
import lux.uno.anima.finance.FinanceData
import lux.uno.anima.resources.*
import lux.uno.anima.sync.SyncStatus
import lux.uno.anima.ui.components.AnimaTopBar
import lux.uno.anima.ui.components.AnimaTextField
import lux.uno.anima.ui.components.FormSection
import lux.uno.anima.ui.components.GhostButton
import lux.uno.anima.ui.components.IconAction
import lux.uno.anima.ui.components.ScreenSurface
import lux.uno.anima.ui.text.description
import lux.uno.anima.ui.text.label
import lux.uno.anima.ui.theme.AnimaIcons
import lux.uno.anima.ui.theme.AnimaTheme
import lux.uno.anima.ui.theme.EyebrowStyle
import org.jetbrains.compose.resources.stringResource

/** Amber for anything that is not production, so the warning colour is consistent. */
internal val NonProductionAccent = Color(0xFFE0A458)

/**
 * Whether [EnvironmentBanner] will draw anything.
 *
 * Callers need to know, because a visible banner takes the top window inset for itself and
 * whatever sits beneath it must not pad for the status bar a second time.
 */
fun AppEnvironment.showsBanner(): Boolean = id != Environments.Production.id

/**
 * A thin strip that stays on screen whenever the app is pointed somewhere other than
 * production.
 *
 * Worth the vertical space: the failure this prevents is typing a real subscription into
 * the sandbox, noticing a week later, and having no way to move it across.
 */
@Composable
fun EnvironmentBanner(environment: AppEnvironment, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    if (!environment.showsBanner()) return
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(NonProductionAccent)
            // Background before padding, so the amber runs up behind the status bar while
            // the label sits below it. Colouring that strip is also the clearest possible
            // signal that this is not the real app.
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .clickable(onClick = onOpen)
            .padding(vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = environment.label().uppercase(),
            style = EyebrowStyle,
            color = Color.Black.copy(alpha = 0.75f),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Settings that exist for whoever is building the app, not for whoever is using it.
 *
 * Reached by shaking the device for a second, or Ctrl+Shift+D on desktop. Switching
 * environment takes effect as soon as it is tapped — the data layer is rebuilt around the
 * new one rather than the app being restarted.
 */
@Composable
fun DeveloperScreen(
    active: AppEnvironment,
    data: FinanceData,
    shakeSupported: Boolean,
    syncStatus: SyncStatus,
    onSyncNow: () -> Unit,
    onSelectEnvironment: (String) -> Unit,
    onSetServerUrl: (id: String, url: String) -> Unit,
    onSetJoinPassword: (id: String, password: String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenSurface(modifier) { contentModifier ->
        Column(contentModifier.safeDrawingPadding().fillMaxSize()) {
            AnimaTopBar(
                title = stringResource(Res.string.developer_title),
                actions = {
                    IconAction(AnimaIcons.Close, stringResource(Res.string.common_close), onClose)
                },
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(26.dp),
            ) {
                FormSection(stringResource(Res.string.developer_environment)) {
                    Environments.all.forEach { environment ->
                        EnvironmentRow(
                            environment = environment,
                            selected = environment.id == active.id,
                            onClick = { onSelectEnvironment(environment.id) },
                        )
                    }
                    Text(
                        stringResource(Res.string.developer_environment_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = AnimaTheme.palette.muted,
                    )
                }

                if (active.syncs) {
                    ServerSection(active, onSetServerUrl, onSetJoinPassword)
                } else {
                    FormSection(stringResource(Res.string.developer_server)) {
                        Text(
                            stringResource(Res.string.developer_no_server_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = AnimaTheme.palette.muted,
                        )
                    }
                }

                FormSection(stringResource(Res.string.developer_this_environment)) {
                    DetailRow(stringResource(Res.string.developer_data_file), active.dataFileName)
                    DetailRow(stringResource(Res.string.developer_icons), active.iconFolderName)
                    DetailRow(
                        stringResource(Res.string.developer_subscriptions),
                        data.subscriptions.size.toString(),
                    )
                    DetailRow(
                        stringResource(Res.string.developer_payment_methods),
                        data.paymentMethods.size.toString(),
                    )
                    DetailRow(
                        stringResource(Res.string.developer_categories),
                        data.categories.size.toString(),
                    )
                }

                FormSection(stringResource(Res.string.developer_sync)) {
                    DetailRow(stringResource(Res.string.developer_status), syncStatus.describe())
                    Text(
                        syncStatus.explain(active.syncs),
                        style = MaterialTheme.typography.bodySmall,
                        color = AnimaTheme.palette.muted,
                    )
                    if (active.syncs) {
                        GhostButton(stringResource(Res.string.account_sync_now), onSyncNow)
                    }
                }

                if (!shakeSupported) {
                    FormSection(stringResource(Res.string.developer_shake_title)) {
                        Text(
                            stringResource(Res.string.developer_shake_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = AnimaTheme.palette.muted,
                        )
                    }
                }

                GhostButton(
                    stringResource(Res.string.common_close),
                    onClose,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun SyncStatus.describe(): String = stringResource(
    when (this) {
        SyncStatus.Idle -> Res.string.sync_headline_up_to_date
        SyncStatus.Syncing -> Res.string.sync_headline_syncing
        SyncStatus.Offline -> Res.string.sync_headline_offline
        SyncStatus.SignedOut -> Res.string.sync_headline_signed_out
        SyncStatus.Failed -> Res.string.sync_headline_failed
    },
)

/** Says what the state means rather than restating it, since these are easy to misread. */
@Composable
private fun SyncStatus.explain(syncs: Boolean): String = stringResource(
    when {
        !syncs -> Res.string.sync_detail_never_syncs
        this == SyncStatus.Offline -> Res.string.sync_detail_offline_short
        this == SyncStatus.SignedOut -> Res.string.sync_detail_signed_out
        this == SyncStatus.Failed -> Res.string.sync_detail_failed_short
        else -> Res.string.sync_detail_idle
    },
)

@Composable
private fun ServerSection(
    active: AppEnvironment,
    onSetServerUrl: (String, String) -> Unit,
    onSetJoinPassword: (String, String) -> Unit,
) {
    // Seeded per environment so switching while the screen is open reloads the fields
    // rather than leaving the previous environment's in them.
    var draft by remember(active.id) { mutableStateOf(active.serverUrl.orEmpty()) }
    var join by remember(active.id) { mutableStateOf(active.joinPassword.orEmpty()) }

    FormSection(stringResource(Res.string.developer_server)) {
        AnimaTextField(
            value = draft,
            onValueChange = { draft = it },
            label = stringResource(Res.string.developer_address),
            placeholder = "http://192.168.1.20:8080",
        )
        Text(
            stringResource(Res.string.developer_address_body),
            style = MaterialTheme.typography.bodySmall,
            color = AnimaTheme.palette.muted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton(stringResource(Res.string.common_apply), { onSetServerUrl(active.id, draft) })
            GhostButton(
                stringResource(Res.string.common_reset),
                { draft = ""; onSetServerUrl(active.id, "") },
            )
        }

        AnimaTextField(
            value = join,
            onValueChange = { join = it },
            label = stringResource(Res.string.developer_join_password),
            placeholder = stringResource(Res.string.developer_join_password_placeholder),
        )
        Text(
            stringResource(Res.string.developer_join_password_body),
            style = MaterialTheme.typography.bodySmall,
            color = AnimaTheme.palette.muted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton(stringResource(Res.string.common_apply), { onSetJoinPassword(active.id, join) })
            GhostButton(
                stringResource(Res.string.common_clear),
                { join = ""; onSetJoinPassword(active.id, "") },
            )
        }
    }
}

@Composable
private fun EnvironmentRow(
    environment: AppEnvironment,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accent = if (environment.id == Environments.Production.id) {
        MaterialTheme.colorScheme.primary
    } else {
        NonProductionAccent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AnimaTheme.palette.elevated)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) accent else MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                shape = RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(environment.label(), style = MaterialTheme.typography.titleSmall)
            Text(
                environment.description(),
                style = MaterialTheme.typography.bodySmall,
                color = AnimaTheme.palette.muted,
            )
        }
        if (selected) {
            androidx.compose.material3.Icon(
                imageVector = AnimaIcons.Check,
                contentDescription = stringResource(Res.string.common_active),
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = AnimaTheme.palette.muted,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
