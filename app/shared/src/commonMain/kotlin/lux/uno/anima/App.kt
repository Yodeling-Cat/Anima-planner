package lux.uno.anima

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lux.uno.anima.finance.FinanceRepository
import lux.uno.anima.finance.SampleData
import lux.uno.anima.finance.model.buildOverview
import lux.uno.anima.finance.model.format
import lux.uno.anima.finance.model.trialNeedsAttention
import lux.uno.anima.finance.relativeDayLabel
import lux.uno.anima.finance.store.IconStore
import lux.uno.anima.finance.store.JsonFileFinanceStore
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
import lux.uno.anima.ui.nav.Screen
import lux.uno.anima.ui.nav.rememberNavigator
import lux.uno.anima.ui.theme.AnimaTheme

@Composable
fun App() {
    AnimaTheme {
        val scope = rememberCoroutineScope()
        val repository = remember { FinanceRepository(JsonFileFinanceStore(), scope) }
        val iconImages = remember { IconImages(IconStore()) }
        val data by repository.data.collectAsStateWithLifecycle()
        val loaded by repository.isLoaded.collectAsStateWithLifecycle()
        val navigator = rememberNavigator()
        val today = remember { today() }

        LaunchedEffect(repository) {
            repository.load()
            // Icons left behind by deleted or abandoned edits are cleaned up once per launch.
            iconImages.pruneExcept(
                repository.data.value.subscriptions.mapNotNull { it.icon.imageId }.toSet(),
            )
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
                alertCount = data.subscriptions.count {
                    it.isActive && it.trialNeedsAttention(today)
                },
            )
        }

        CompositionLocalProvider(LocalIconImages provides iconImages) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (!loaded) return@CompositionLocalProvider

            AnimatedContent(
                targetState = navigator.current,
                transitionSpec = {
                    (fadeIn(tween(200)) + slideInHorizontally(tween(240)) { it / 14 })
                        .togetherWith(fadeOut(tween(140)))
                },
                label = "screen",
            ) { screen ->
                when (screen) {
                    Screen.Dashboard -> DashboardScreen(
                        glance = glance,
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
                        onLoadSample = {
                            repository.replaceAll(
                                SampleData.build(today, data.settings.preferredCurrency),
                            )
                        },
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

                    is Screen.ComingSoon -> ComingSoonScreen(
                        area = screen.area,
                        onBack = { navigator.back() },
                    )
                }
            }
        }
    }
}
}
