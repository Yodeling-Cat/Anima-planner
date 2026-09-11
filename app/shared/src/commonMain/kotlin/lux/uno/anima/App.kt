package lux.uno.anima

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import lux.uno.anima.dev.AppEnvironment
import lux.uno.anima.dev.EnvironmentStore
import lux.uno.anima.dev.createShakeDetector
import lux.uno.anima.ui.dev.DeveloperScreen
import lux.uno.anima.ui.dev.EnvironmentBanner
import lux.uno.anima.ui.dev.showsBanner
import lux.uno.anima.finance.FinanceRepository
import lux.uno.anima.finance.model.buildOverview
import lux.uno.anima.finance.model.cardsNeedingAttention
import lux.uno.anima.finance.model.format
import lux.uno.anima.finance.model.trialNeedsAttention
import lux.uno.anima.finance.relativeDayLabel
import lux.uno.anima.finance.reminders.JsonFileHandledReminderStore
import lux.uno.anima.finance.reminders.ReminderCoordinator
import lux.uno.anima.finance.reminders.createReminderNotifier
import lux.uno.anima.finance.store.IconStore
import lux.uno.anima.finance.store.JsonFileFinanceStore
import lux.uno.anima.sync.Hlc
import lux.uno.anima.sync.HybridClock
import lux.uno.anima.platform.createSecretStore
import lux.uno.anima.sync.JsonFileSyncStateStore
import lux.uno.anima.sync.KtorSyncApi
import lux.uno.anima.finance.sync.recordsToPush
import lux.uno.anima.platform.newId
import lux.uno.anima.sync.Account
import lux.uno.anima.sync.BlobSync
import lux.uno.anima.sync.SyncEngine
import lux.uno.anima.ui.account.AccountScreen
import lux.uno.anima.ui.account.AccountState
import lux.uno.anima.ui.account.message
import lux.uno.anima.sync.animaHttpClient
import lux.uno.anima.sync.SyncState
import lux.uno.anima.sync.SyncStatus
import lux.uno.anima.sync.loadOrCreate
import lux.uno.anima.finance.today
import lux.uno.anima.ui.dashboard.ComingSoonScreen
import lux.uno.anima.ui.dashboard.DashboardScreen
import lux.uno.anima.ui.dashboard.FinanceGlance
import lux.uno.anima.ui.dashboard.LifeArea
import lux.uno.anima.ui.finance.CategoriesScreen
import lux.uno.anima.ui.finance.FinanceHomeScreen
import lux.uno.anima.ui.finance.PaymentMethodsScreen
import lux.uno.anima.ui.finance.SubscriptionDetailScreen
import lux.uno.anima.ui.finance.SubscriptionEditorScreen
import lux.uno.anima.ui.icons.IconImages
import lux.uno.anima.ui.icons.LocalIconImages
import lux.uno.anima.ui.nav.Move
import lux.uno.anima.ui.nav.Screen
import lux.uno.anima.ui.nav.SystemBackHandler
import lux.uno.anima.ui.nav.rememberNavigator
import lux.uno.anima.ui.theme.AnimaTheme

@Composable
fun App() {
    AnimaTheme {
        val scope = rememberCoroutineScope()
        val environments = remember { EnvironmentStore() }
        val environment by environments.active.collectAsStateWithLifecycle()
        val environmentReady by environments.isReady.collectAsStateWithLifecycle()
        LaunchedEffect(environments) { environments.load() }

        // Nothing below is built until the environment is known. Starting on the default and
        // correcting a moment later would open the production dataset — and stamp it — every
        // time the app launched anywhere else, which is the one thing environments exist to
        // prevent. It happens on every rotation too, not just at launch.
        if (!environmentReady) return@AnimaTheme

        Anima(
            environment = environment,
            onSelectEnvironment = { scope.launch { environments.select(it) } },
            onSetServerUrl = { id, url -> scope.launch { environments.setServerUrl(id, url) } },
        )
    }
}

@Composable
private fun Anima(
    environment: AppEnvironment,
    onSelectEnvironment: (String) -> Unit,
    onSetServerUrl: (id: String, url: String) -> Unit,
) {
    val scope = rememberCoroutineScope()

    // The node id has to be read before anything can stamp a change with it, so the data
    // layer waits for it the same way it waits for the environment.
    val syncStateStore = remember(environment.id) {
        JsonFileSyncStateStore(fileName = environment.syncStateFileName)
    }
    var startingState by remember(environment.id) { mutableStateOf<SyncState?>(null) }
    LaunchedEffect(syncStateStore) { startingState = syncStateStore.loadOrCreate() }
    val nodeId = startingState?.nodeId ?: return

    // Keyed on the environment, so selecting a different one rebuilds the whole data
    // layer against that environment's files. That is what makes the switch take effect
    // without a restart — and why nothing needs to be told to reload.
    val repository = remember(environment.id, nodeId) {
        FinanceRepository(
            store = JsonFileFinanceStore(fileName = environment.dataFileName),
            scope = scope,
            clock = HybridClock(nodeId, last = startingState?.lastHlc ?: Hlc.Zero),
        )
    }
    val iconImages = remember(environment.id) {
        IconImages(IconStore(folderName = environment.iconFolderName))
    }
    val reminders = remember(repository, environment.id) {
        ReminderCoordinator(
            notifier = createReminderNotifier(),
            repository = repository,
            // Device state, so it lives beside the sync cursor rather than in the dataset.
            handledStore = JsonFileHandledReminderStore(
                fileName = environment.handledRemindersFileName,
            ),
        )
    }

    // A null api is the sandbox, which has no server by design — the engine answers
    // NotConfigured rather than treating it as a failure.
    val api = remember(environment.serverUrl) {
        environment.serverUrl?.let { KtorSyncApi(animaHttpClient(), it) }
    }
    val engine = remember(repository, api, iconImages) {
        SyncEngine(
            repository = repository,
            stateStore = syncStateStore,
            secrets = createSecretStore(),
            api = api,
            blobs = BlobSync(iconImages.asBlobStore()) {
                // Only icons a live subscription still points at. An orphan is the pruning
                // pass's problem, not something to hand the server.
                repository.data.value.subscriptions.mapNotNull { it.icon.imageId }.toSet()
            },
            tokenKey = environment.sessionTokenKey,
        )
    }
    val syncStatus by engine.status.collectAsStateWithLifecycle()
    val syncState by engine.syncState.collectAsStateWithLifecycle()

    val data by repository.data.collectAsStateWithLifecycle()
    val loaded by repository.isLoaded.collectAsStateWithLifecycle()

    val secrets = remember { createSecretStore() }
    val account = remember(api) { Account(secrets, api, environment.sessionTokenKey) }
    val signedIn by account.signedIn.collectAsStateWithLifecycle()
    val accountEmail by account.email.collectAsStateWithLifecycle()
    LaunchedEffect(account) { account.refresh() }

    var accountBusy by remember { mutableStateOf(false) }
    var accountMessage by remember(environment.id) { mutableStateOf<String?>(null) }

    // What this device has stamped and not yet sent. Derived from the data rather than
    // counted alongside it, so the number cannot drift from what it describes.
    val pendingCount = remember(data, syncState) {
        syncState?.let { data.recordsToPush(it.nodeId, it.lastPushed).size } ?: 0
    }
    val navigator = rememberNavigator()
    val today = remember { today() }

    val notificationsEnabled by reminders.permissionGranted.collectAsStateWithLifecycle()

    LaunchedEffect(repository, iconImages) {
        repository.load()
        // Icons predating content addressing are renamed to the hash of their contents, and
        // the records pointing at them corrected. A no-op once it has run.
        repository.applyIconMigration(iconImages.migrateToContentAddressing())

        // Icons left behind by deleted or abandoned edits are cleaned up once per launch.
        iconImages.pruneExcept(
            repository.data.value.subscriptions.mapNotNull { it.icon.imageId }.toSet(),
        )
        // Catch up before anything else happens. Nothing waits on it: the local dataset is
        // already on screen, and a sync that cannot reach anything changes nothing.
        engine.syncNow()
    }

    // Every edit made here asks for a sync. The engine debounces, so typing a name is one
    // round trip rather than one per keystroke.
    val localEdits by repository.localEdits.collectAsStateWithLifecycle()
    LaunchedEffect(localEdits) {
        if (localEdits > 0) engine.requestSync(scope)
    }

    // A second of deliberate shaking, or Ctrl+Shift+D where there is no accelerometer.
    var showDeveloper by rememberSaveable { mutableStateOf(false) }
    val shake = remember { createShakeDetector() }
    DisposableEffect(shake) {
        shake.start { showDeveloper = true }
        onDispose { shake.stop() }
    }

    // One definition of going back, shared by the Escape key and by Android's gesture
    // and button. Keeping them as two implementations is what let the system back quit
    // the app from halfway down the stack.
    val canGoBack = showDeveloper || navigator.canGoBack
    val goBack = {
        if (showDeveloper) {
            showDeveloper = false
            true
        } else {
            navigator.back()
        }
    }

    // Disabled at the root of the stack on purpose: the last back press belongs to
    // Android, which closes the app. Swallowing it would leave no way out.
    SystemBackHandler(enabled = canGoBack) { goBack() }

    // Runs for the life of the app: shows anything that fell due while it was closed,
    // hands future reminders to the OS, and re-checks hourly so a desktop window left
    // open overnight still reports today's card expiry.
    LaunchedEffect(reminders, loaded) {
        if (!loaded) return@LaunchedEffect
        reminders.requestPermission()
        reminders.run()
    }

    val glance = remember(data, today) {
        val overview =
            buildOverview(data.subscriptions, data.categories, today, data.settings.preferredCurrency)
        FinanceGlance(
            monthlyTotal = overview.primaryTotal?.monthly?.format(),
            subscriptionCount = overview.activeCount,
            nextChargeLabel = overview.upcoming.firstOrNull()?.let {
                "${it.subscription.name} ${relativeDayLabel(it.daysAway)}"
            },
            alertCount = data.subscriptions.count { it.isActive && it.trialNeedsAttention(today) } +
                data.paymentMethods.cardsNeedingAttention(today).size,
        )
    }

    // The root holds focus so a key press with nothing else selected still lands here.
    // Re-requested on every screen change, because focus dies with the screen that held it.
    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(loaded, navigator.current) {
        if (loaded) runCatching { rootFocus.requestFocus() }
    }

    CompositionLocalProvider(LocalIconImages provides iconImages) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .focusRequester(rootFocus)
                .focusable()
                // onKeyEvent, not onPreviewKeyEvent: dialogs and text fields get first refusal,
                // so Escape closes what is open before it starts unwinding the back stack.
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when {
                        // The desktop stand-in for shaking, which no laptop can do.
                        event.isCtrlPressed && event.isShiftPressed && event.key == Key.D -> {
                            showDeveloper = !showDeveloper
                            true
                        }
                        event.key == Key.Escape -> goBack()
                        else -> false
                    }
                },
        ) {
            if (!loaded) return@CompositionLocalProvider

            Column(Modifier.fillMaxSize()) {
                EnvironmentBanner(environment, onOpen = { showDeveloper = true })

                AnimatedContent(
                    targetState = navigator.current,
                    modifier = Modifier
                        .weight(1f)
                        // Every screen pads itself for the status bar. When the banner is
                        // showing it has already taken that inset, so it has to be marked
                        // spent here or the screen below would leave a second gap.
                        .then(
                            if (environment.showsBanner()) {
                                Modifier.consumeWindowInsets(
                                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top),
                                )
                            } else {
                                Modifier
                            },
                        ),
                    transitionSpec = {
                        val forward = navigator.lastMove == Move.Forward
                        val transform = if (forward) {
                            (fadeIn(tween(200)) + slideInHorizontally(tween(240)) { it / 14 })
                                .togetherWith(fadeOut(tween(140)))
                        } else {
                            // The same movement run backwards in time: the screen being left
                            // slides out the way it came in, and the one underneath fades up
                            // where it already sits rather than arriving from anywhere.
                            fadeIn(tween(140))
                                .togetherWith(
                                    fadeOut(tween(200)) +
                                        slideOutHorizontally(tween(240)) { it / 14 },
                                )
                        }
                        // Going forward the new screen arrives on top; going back the old one
                        // has to stay on top, because it is the thing moving out of the way.
                        transform.apply { targetContentZIndex = if (forward) 1f else -1f }
                    },
                    label = "screen",
                ) { screen ->
                    when (screen) {
                        Screen.Dashboard -> DashboardScreen(
                            glance = glance,
                            onOpenAccount = { navigator.go(Screen.Account) },
                            syncDot = syncDotFor(environment, signedIn, syncStatus, pendingCount),
                            onOpen = { area ->
                                navigator.go(
                                    if (area == LifeArea.FINANCE) Screen.Finance else Screen.ComingSoon(area),
                                )
                            },
                        )

                        Screen.Finance -> FinanceHomeScreen(
                            data = data,
                            today = today,
                            onBack = { navigator.back() },
                            onOpenSubscription = { navigator.go(Screen.SubscriptionDetail(it)) },
                            onAddSubscription = { navigator.go(Screen.SubscriptionEditor()) },
                            onOpenPaymentMethods = { navigator.go(Screen.PaymentMethods) },
                            onOpenCategories = { navigator.go(Screen.Categories) },
                        )

                        is Screen.SubscriptionDetail -> {
                            val subscription = data.subscriptions.firstOrNull { it.id == screen.id }
                            if (subscription == null) {
                                // The record was deleted from underneath this screen.
                                LaunchedEffect(screen.id) { navigator.popTo(Screen.Finance) }
                            } else {
                                SubscriptionDetailScreen(
                                    subscription = subscription,
                                    data = data,
                                    today = today,
                                    onBack = { navigator.back() },
                                    onEdit = { navigator.go(Screen.SubscriptionEditor(subscription.id)) },
                                    onDelete = {
                                        repository.deleteSubscription(subscription.id)
                                        navigator.popTo(Screen.Finance)
                                    },
                                    onTogglePaused = {
                                        repository.setSubscriptionActive(subscription.id, !subscription.isActive)
                                    },
                                    onEndTrial = { repository.endTrial(subscription.id) },
                                    onRecordPrice = { price, date, note ->
                                        repository.recordPriceChange(subscription.id, price, date, note)
                                    },
                                    onDeletePricePoint = { date ->
                                        repository.deletePricePoint(subscription.id, date)
                                    },
                                )
                            }
                        }

                        is Screen.SubscriptionEditor -> SubscriptionEditorScreen(
                            existing = screen.id?.let { id -> data.subscriptions.firstOrNull { it.id == id } },
                            data = data,
                            today = today,
                            onBack = { navigator.back() },
                            onSave = { subscription ->
                                repository.upsertSubscription(subscription)
                                // Editing came from the detail screen, so returning to it is enough.
                                if (screen.id != null) navigator.back()
                                else navigator.replace(Screen.SubscriptionDetail(subscription.id))
                            },
                            onManagePaymentMethods = { navigator.go(Screen.PaymentMethods) },
                            onManageCategories = { navigator.go(Screen.Categories) },
                            onCreateCategory = { repository.upsertCategory(it) },
                        )

                        Screen.PaymentMethods -> PaymentMethodsScreen(
                            data = data,
                            today = today,
                            notificationsEnabled = notificationsEnabled,
                            onBack = { navigator.back() },
                            onSave = { repository.upsertPaymentMethod(it) },
                            onDelete = { repository.deletePaymentMethod(it) },
                        )

                        Screen.Categories -> CategoriesScreen(
                            data = data,
                            onBack = { navigator.back() },
                            onSave = { repository.upsertCategory(it) },
                            onDelete = { repository.deleteCategory(it) },
                        )

                        Screen.Account -> AccountScreen(
                            state = AccountState(
                                environment = environment,
                                signedIn = signedIn,
                                email = accountEmail,
                                syncStatus = syncStatus,
                                pendingCount = pendingCount,
                                busy = accountBusy,
                                message = accountMessage,
                            ),
                            onSignIn = { email, password ->
                                scope.launch {
                                    accountBusy = true
                                    accountMessage = account.signIn(email, password).message()
                                    accountBusy = false
                                    // Signing in is the moment there is finally something to talk to.
                                    if (accountMessage == null) engine.syncNow()
                                }
                            },
                            onRegister = { email, password ->
                                scope.launch {
                                    accountBusy = true
                                    accountMessage = account.register(email, password).message()
                                    accountBusy = false
                                    if (accountMessage == null) engine.syncNow()
                                }
                            },
                            onSignOut = { alsoDeleteLocalData ->
                                scope.launch {
                                    account.signOut()
                                    accountMessage = null
                                    if (alsoDeleteLocalData) {
                                        repository.resetLocalData()
                                        // A new node id too: after a wipe this is a new device as far as
                                        // the server is concerned, and the old push mark would describe
                                        // records that are no longer here.
                                        syncStateStore.save(SyncState(nodeId = newId("node")))
                                    }
                                    navigator.back()
                                }
                            },
                            onSyncNow = { scope.launch { engine.syncNow() } },
                            onBack = { navigator.back() },
                        )

                        is Screen.ComingSoon -> ComingSoonScreen(
                            area = screen.area,
                            onBack = { navigator.back() },
                        )
                    }
                }
            }

            // Drawn over the app rather than pushed onto the back stack: it is reachable
            // from any screen, and dismissing it must leave the stack exactly as it was.
            if (showDeveloper) {
                DeveloperScreen(
                    active = environment,
                    data = data,
                    shakeSupported = shake.isSupported,
                    syncStatus = syncStatus,
                    onSyncNow = { scope.launch { engine.syncNow() } },
                    onSelectEnvironment = onSelectEnvironment,
                    onSetServerUrl = onSetServerUrl,
                    onClose = { showDeveloper = false },
                )
            }
        }
    }
}

/**
 * The single dot on the dashboard.
 *
 * Null when there is nothing to say — the sandbox, or everything already up to date. Amber
 * means something is waiting rather than wrong; red means an attempt was refused. Being
 * offline with nothing pending earns no mark at all: that is the normal state of a phone,
 * and marking it would teach people to ignore the dot.
 */
private fun syncDotFor(
    environment: AppEnvironment,
    signedIn: Boolean,
    status: SyncStatus,
    pendingCount: Int,
): Color? = when {
    !environment.syncs -> null
    status == SyncStatus.Failed -> Color(0xFFE0504F)
    !signedIn -> Color(0xFFE0A458)
    pendingCount > 0 -> Color(0xFFE0A458)
    else -> null
}
