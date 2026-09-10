package lux.uno.anima.ui.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import lux.uno.anima.finance.FinanceData
import lux.uno.anima.finance.model.Category
import lux.uno.anima.platform.newId
import lux.uno.anima.ui.components.AccentSwatches
import lux.uno.anima.ui.components.AnimaCard
import lux.uno.anima.ui.components.AnimaTextField
import lux.uno.anima.ui.components.AnimaTopBar
import lux.uno.anima.ui.components.ColorSwatchRow
import lux.uno.anima.ui.components.EmojiStrip
import lux.uno.anima.ui.components.EmptyState
import lux.uno.anima.ui.components.GlyphTile
import lux.uno.anima.ui.components.IconAction
import lux.uno.anima.ui.components.PrimaryButton
import lux.uno.anima.ui.components.ScreenSurface
import lux.uno.anima.ui.theme.AnimaIcons
import lux.uno.anima.ui.theme.AnimaTheme

/** Emoji offered when naming a category. Anything else can be typed into the field. */
internal val CategoryGlyphChoices = listOf(
    "✦", "🎬", "🎧", "📺", "🎮", "🛠", "☁️", "📚", "🍳", "🧗",
    "🌿", "🏠", "📈", "🚗", "🐾", "☕️", "🎨", "🧠", "💼", "🎁",
)

@Composable
fun CategoriesScreen(
    data: FinanceData,
    onBack: () -> Unit,
    onSave: (Category) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<Category?>(null) }
    var creating by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Category?>(null) }

    val usageCount = remember(data) {
        data.subscriptions.groupingBy { it.categoryId }.eachCount()
    }

    ScreenSurface(modifier) { contentModifier ->
        Column(contentModifier.safeDrawingPadding().fillMaxSize()) {
            AnimaTopBar(
                title = "Categories",
                onBack = onBack,
                actions = { IconAction(AnimaIcons.Plus, "New category", { creating = true }) },
            )

            if (data.categories.isEmpty()) {
                EmptyState(
                    glyph = "🏷",
                    title = "No categories",
                    body = "Categories are what the \"where it goes\" breakdown is built from. "
                        + "Add the ones that match how you actually think about spending.",
                    action = { PrimaryButton("Add one", { creating = true }, icon = AnimaIcons.Plus) },
                )
                return@Column
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                data.categories.forEach { category ->
                    val used = usageCount[category.id] ?: 0
                    AnimaCard(onClick = { editing = category }, contentPadding = PaddingValues(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GlyphTile(category.glyph, Color(category.colorArgb), size = 40.dp)
                            Spacer(Modifier.width(13.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(category.label, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = when (used) {
                                        0 -> "Nothing in it yet"
                                        1 -> "1 subscription"
                                        else -> "$used subscriptions"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AnimaTheme.palette.muted,
                                )
                            }
                            IconAction(
                                icon = AnimaIcons.Trash,
                                contentDescription = "Delete ${category.label}",
                                onClick = { pendingDelete = category },
                                tint = AnimaTheme.palette.faint,
                            )
                        }
                    }
                }
            }
        }
    }

    if (creating || editing != null) {
        CategoryDialog(
            existing = editing,
            onDismiss = { creating = false; editing = null },
            onConfirm = {
                onSave(it)
                creating = false
                editing = null
            },
        )
    }

    pendingDelete?.let { category ->
        val used = usageCount[category.id] ?: 0
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${category.label}?") },
            text = {
                Text(
                    if (used == 0) "Nothing is filed under it."
                    else "$used subscription${if (used == 1) "" else "s"} will become uncategorised. "
                        + "Nothing else about them changes.",
                )
            },
            confirmButton = {
                TextButton(onClick = { onDelete(category.id); pendingDelete = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Keep") } },
        )
    }
}

/** Shared by this screen and the "new category" shortcut inside the subscription editor. */
@Composable
fun CategoryDialog(
    existing: Category?,
    onDismiss: () -> Unit,
    onConfirm: (Category) -> Unit,
) {
    var label by remember { mutableStateOf(existing?.label.orEmpty()) }
    var glyph by remember { mutableStateOf(existing?.glyph ?: "✦") }
    var colorArgb by remember { mutableStateOf(existing?.colorArgb ?: AccentSwatches.first()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New category" else "Edit ${existing.label}") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                AnimaTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = "Name",
                    placeholder = "Coffee",
                )
                EmojiStrip(
                    selected = glyph,
                    choices = CategoryGlyphChoices,
                    accent = Color(colorArgb),
                    onSelect = { glyph = it },
                    onTyped = { glyph = it },
                )
                ColorSwatchRow(
                    colors = AccentSwatches,
                    selected = colorArgb,
                    onSelect = { colorArgb = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = label.isNotBlank(),
                onClick = {
                    onConfirm(
                        Category(
                            id = existing?.id ?: newId("cat"),
                            label = label.trim(),
                            glyph = glyph.ifBlank { "✦" },
                            colorArgb = colorArgb,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
