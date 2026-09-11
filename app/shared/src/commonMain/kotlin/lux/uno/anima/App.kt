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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import lux.uno.anima.dev.AppEnvironment
import lux.uno.anima.dev.EnvironmentStore
import lux.uno.anima.dev.createShakeDetector
import lux.uno.anima.finance.FinanceRepository
import lux.uno.anima.finance.model.buildOverview
import lux.uno.anima.finance.model.cardsNeedingAttention
import lux.uno.anima.finance.model.format
import lux.uno.anima.finance.model.trialNeedsAttention
import lux.uno.anima.finance.reminders.JsonFileHandledReminderStore
import lux.uno.anima.finance.reminders.ReminderCoordinator
import lux.uno.anima.finance.reminders.createReminderNotifier
import lux.uno.anima.finance.store.IconStore
import lux.uno.anima.finance.store.JsonFileFinanceStore
import lux.uno.anima.finance.sync.recordsToPush
import lux.uno.anima.finance.today
import lux.uno.anima.platform.createSecretStore
import lux.uno.anima.platform.newId
import lux.uno.anima.resources.*
import lux.uno.anima.sync.Account
import lux.uno.anima.sync.BlobSync
import lux.uno.anima.sync.Hlc
import lux.uno.anima.sync.HybridClock
import lux.uno.anima.sync.JsonFileSyncStateStore
import lux.uno.anima.sync.KtorSyncApi
import lux.uno.anima.sync.KtorSyncEvents
import lux.uno.anima.sync.LiveSync
import lux.uno.anima.sync.SyncEngine
import lux.uno.anima.sync.SyncState
import lux.uno.anima.sync.SyncStatus
import lux.uno.anima.sync.animaHttpClient
import lux.uno.anima.sync.loadOrCreate
import lux.uno.anima.ui.account.AccountScreen
import lux.uno.anima.ui.account.AccountState
import lux.uno.anima.ui.account.message
import lux.uno.anima.ui.components.ContentMaxWidth
import lux.uno.anima.ui.components.EmptyState
import lux.uno.anima.ui.components.ScreenSurface
import lux.uno.anima.ui.components.VerticalHairline
import lux.uno.anima.ui.dashboard.ComingSoonScreen
import lux.uno.anima.ui.dashboard.DashboardScreen
import lux.uno.anima.ui.dashboard.FinanceGlance
import lux.uno.anima.ui.dashboard.LifeArea
import lux.uno.anima.ui.dev.DeveloperScreen
import lux.uno.anima.ui.dev.EnvironmentBanner
import lux.uno.anima.ui.dev.showsBanner
import lux.uno.anima.ui.finance.CategoriesScreen
import lux.uno.anima.ui.finance.FinanceHomeScreen
import lux.uno.anima.ui.finance.PaymentMethodsScreen
import lux.uno.anima.ui.finance.SubscriptionDetailScreen
import lux.uno.anima.ui.finance.SubscriptionEditorScreen
import lux.uno.anima.ui.icons.IconImages
import lux.uno.anima.ui.icons.LocalIconImages
import lux.uno.anima.ui.nav.AnimaNavRail
import lux.uno.anima.ui.nav.Move
import lux.uno.anima.ui.nav.Navigator
import lux.uno.anima.ui.nav.Screen
import lux.uno.anima.ui.nav.SystemBackHandler
import lux.uno.anima.ui.nav.WindowClass
import lux.uno.anima.ui.nav.listPane
import lux.uno.anima.ui.nav.rememberNavigator
import lux.uno.anima.ui.nav.stateKey
import lux.uno.anima.ui.nav.subscriptionId
import lux.uno.anima.ui.text.ResourceReminderStrings
import lux.uno.anima.ui.text.relativeDayLabel
import lux.uno.anima.ui.text.seededCategories
import lux.uno.anima.ui.theme.AnimaTheme
import org.jetbrains.compose.resources.stringResource

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
            onSetJoinPassword = { id, password ->
                scope.launch { environments.setJoinPassword(id, password) }
            },
        )
    }
}

@Composable
private fun Anima(
    environment: AppEnvironment,
    onSelectEnvironment: (String) -> Unit,
    onSetServerUrl: (id: String, url: String) -> Unit,
    onSetJoinPassword: (id: String, password: String) -> Unit,
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
    // Read here rather than inside the repository: the starting categories are strings, and
    // strings are only reachable from composition.
    val seedCategories = seededCategories()
    val repository = remember(environment.id, nodeId) {
        FinanceRepository(
            store = JsonFileFinanceStore(fileName = environment.dataFileName),
            scope = scope,
            seedCategories = seedCategories,
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
            strings = ResourceReminderStrings,
            // Device state, so it lives beside the sync cursor rather than in the dataset.
            handledStore = JsonFileHandledReminderStore(
                fileName = environment.handledRemindersFileName,
            ),
        )
    }

    // One client for every call this environment makes, live channel included. Two would
    // mean two connection pools and two sets of sockets to the same host.
    val http = remember(environment.id) { animaHttpClient() }

    // A null api is the sandbox, which has no server by design — the engine answers
    // NotConfigured rather than treating it as a failure.
    val api = remember(http, environment.serverUrl) {
        environment.serverUrl?.let { KtorSyncApi(http, it) }
    }
    val events = remember(http, environment.serverUrl) {
        environment.serverUrl?.let { KtorSyncEvents(http, it) }
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
    // Keeps what a screen holds — half-typed forms, scroll position — alive while it sits
    // buried under another one. Leaving a screen for good drops its state below, so opening
    // the same screen again starts clean.
    val screenState = rememberSaveableStateHolder()
    val stackKeys = navigator.entries.map { it.stateKey }
    var liveKeys by remember { mutableStateOf(stackKeys) }
    LaunchedEffect(stackKeys) {
        (liveKeys - stackKeys.toSet()).forEach(screenState::removeState)
        liveKeys = stackKeys
    }
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

    // The other half of that: edits made on another device. Without this, a device that is
    // only being read from stays on whatever it last pulled, because nothing local ever
    // asks. Runs only while signed in, and is torn down with the session — there is nothing
    // to listen to without a token, and the server would refuse the connection anyway.
    val live = remember(engine, events) {
        LiveSync(engine, events, secrets, environment.sessionTokenKey)
    }
    LaunchedEffect(live, signedIn) {
        if (signedIn) live.run()
    }

    // Coming back to the app is the one moment a device is most likely to be stale and
    // least likely to be told: a phone's connection does not survive being backgrounded,
    // and the live channel may not have noticed yet. Skipping the first one because the
    // launch effect above already syncs — and does it after the dataset has loaded, which
    // this cannot promise.
    var syncedOnStart by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (syncedOnStart) scope.launch { engine.syncNow() } else syncedOnStart = true
    }

    // Closed with the environment it belongs to. A switch rebuilds everything below it, and
    // a client left open would keep its connections — including a live one — to the server
    // the app just stopped using.
    DisposableEffect(http) {
        onDispose { http.close() }
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

    val overview = remember(data, today) {
        buildOverview(data.subscriptions, data.categories, today, data.settings.preferredCurrency)
    }
    // Built outside the remember below because it reads strings, which only composition can do.
    val nextCharge = overview.upcoming.firstOrNull()?.let {
        stringResource(
            Res.string.dashboard_next_charge,
            it.subscription.name,
            relativeDayLabel(it.daysAway),
        )
    }
    val glance = remember(data, today, overview, nextCharge) {
        FinanceGlance(
            monthlyTotal = overview.primaryTotal?.monthly?.format(),
            subscriptionCount = overview.activeCount,
            nextChargeLabel = nextCharge,
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
        BoxWithConstraints(
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
            if (!loaded) return@BoxWithConstraints

            // One place decides how much room there is, and the layout below reads it.
            // Measured from the window rather than asked of the platform: a desktop window
            // dragged narrow is a phone-shaped space and is laid out like one.
            val windowClass = WindowClass.of(maxWidth)

            // Where the rail is showing it is the way back to the dashboard, so the screens
            // it reaches drop their own arrow rather than offering a second one.
            val sectionBack: (() -> Unit)? =
                if (windowClass.showsRail) null else ({ navigator.back(); Unit })

            // A lambda rather than a function because it closes over the whole data layer:
            // as a top-level composable this would be thirty parameters threaded through one
            // call site. Held in one place because the panes below both draw screens with it,
            // and two copies of this `when` is how two panes start disagreeing about what a
            // screen is.
            val render: @Composable (Screen) -> Unit = { screen ->
                screenState.SaveableStateProvider(screen.stateKey) {
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
                            onBack = sectionBack,
                            selectedId = navigator.current.subscriptionId,
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
                                    // The join password belongs to the server, not the
                                    // person, so it comes from the environment rather than
                                    // from anything typed on this screen.
                                    accountMessage = account
                                        .register(email, password, environment.joinPassword)
                                        .message()
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
                            onBack = sectionBack,
                        )

                        is Screen.ComingSoon -> ComingSoonScreen(
                            area = screen.area,
                            onBack = sectionBack,
                        )
                    }
                }
            }

            Column(Modifier.fillMaxSize()) {
                EnvironmentBanner(environment, onOpen = { showDeveloper = true })

                Row(
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
                ) {
                    if (windowClass.showsRail) {
                        AnimaNavRail(
                            current = navigator.current,
                            onHome = { navigator.switchTo(Screen.Dashboard) },
                            onOpenArea = { area ->
                                navigator.switchTo(
                                    if (area == LifeArea.FINANCE) {
                                        Screen.Finance
                                    } else {
                                        Screen.ComingSoon(area)
                                    },
                                )
                            },
                            onOpenAccount = { navigator.switchTo(Screen.Account) },
                            syncDot = syncDotFor(environment, signedIn, syncStatus, pendingCount),
                        )
                    }

                    // The section list that stays put while the pane beside it shows whatever
                    // the list opened. Finance sitting next to the placeholder is the resting
                    // state, which is why it counts as a list pane while it is the current
                    // screen rather than only while something is open on top of it.
                    val listPane = when {
                        !windowClass.showsTwoPanes -> null
                        navigator.current == Screen.Finance -> Screen.Finance
                        else -> navigator.current.listPane
                    }

                    Box(
                        Modifier
                            .weight(1f)
                            // The rail has taken the left inset; left here it would pad the
                            // content away from an edge the rail is already covering.
                            .then(
                                if (windowClass.showsRail) {
                                    Modifier.consumeWindowInsets(
                                        WindowInsets.safeDrawing.only(WindowInsetsSides.Start),
                                    )
                                } else {
                                    Modifier
                                },
                            ),
                    ) {
                        if (listPane == null) {
                            ScreenTransition(navigator) { render(it) }
                        } else {
                            // The pair is capped and centred together rather than each pane
                            // being centred in its own half. Left to spread, the detail pane
                            // centres its own column in whatever is left over, and the two
                            // panes drift apart across the middle of a wide screen with the
                            // seam between them stranded on the far left.
                            Row(
                                Modifier
                                    .widthIn(max = ListPaneWidth + ContentMaxWidth)
                                    .fillMaxSize()
                                    .align(Alignment.TopCenter),
                            ) {
                                Box(Modifier.width(ListPaneWidth).fillMaxHeight()) { render(listPane) }
                                VerticalHairline()
                                Box(Modifier.weight(1f)) {
                                    // Only the pane that changed animates. Sliding the list
                                    // out and back every time a row is opened would be
                                    // movement saying something happened to the list, which
                                    // is the one thing that did not.
                                    ScreenTransition(navigator) { screen ->
                                        if (screen == listPane) DetailPlaceholder() else render(screen)
                                    }
                                }
                            }
                        }
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
                    onSetJoinPassword = onSetJoinPassword,
                    onClose = { showDeveloper = false },
                )
            }
        }
    }
}

/** How wide the section list stays while a pane beside it shows what the list opened. */
private val ListPaneWidth = 380.dp

/**
 * One screen giving way to the next, in whichever pane is doing the changing.
 *
 * The direction comes from [Navigator.lastMove] rather than from comparing screens, so a
 * rail jump that shortens the stack reads as going back the same way a Back press does.
 */
@Composable
private fun ScreenTransition(
    navigator: Navigator,
    modifier: Modifier = Modifier,
    content: @Composable (Screen) -> Unit,
) {
    AnimatedContent(
        targetState = navigator.current,
        modifier = modifier,
        transitionSpec = {
            val forward = navigator.lastMove == Move.Forward
            val transform = if (forward) {
                (fadeIn(tween(200)) + slideInHorizontally(tween(240)) { it / 14 })
                    .togetherWith(fadeOut(tween(140)))
            } else {
                // The same movement run backwards in time: the screen being left slides out
                // the way it came in, and the one underneath fades up where it already sits
                // rather than arriving from anywhere.
                fadeIn(tween(140))
                    .togetherWith(
                        fadeOut(tween(200)) +
                            slideOutHorizontally(tween(240)) { it / 14 },
                    )
            }
            // Going forward the new screen arrives on top; going back the old one has to
            // stay on top, because it is the thing moving out of the way.
            transform.apply { targetContentZIndex = if (forward) 1f else -1f }
        },
        label = "screen",
    ) { screen ->
        content(screen)
    }
}

/**
 * What the detail pane says before anything has been opened in it.
 *
 * The pane keeps its width rather than letting the list spread into it: a list that changes
 * width the first time a row is opened moves every other row out from under the pointer.
 */
@Composable
private fun DetailPlaceholder() {
    ScreenSurface { contentModifier ->
        Box(
            contentModifier.safeDrawingPadding().fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            EmptyState(
                glyph = "🗂️",
                title = stringResource(Res.string.finance_select_title),
                body = stringResource(Res.string.finance_select_body),
            )
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
