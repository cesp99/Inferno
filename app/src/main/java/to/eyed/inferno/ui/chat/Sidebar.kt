package to.eyed.inferno.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Archive
import com.composables.icons.lucide.ArchiveRestore
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Gauge
import com.composables.icons.lucide.HardDrive
import com.composables.icons.lucide.Images
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Pin
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.SquarePen
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Wand
import com.composables.icons.lucide.X
import to.eyed.inferno.data.Conversation
import to.eyed.inferno.ui.S
import to.eyed.inferno.ui.components.GhostIconButton
import to.eyed.inferno.ui.components.GlassMenu
import to.eyed.inferno.ui.components.Hairline
import to.eyed.inferno.ui.components.MenuActionRow
import to.eyed.inferno.ui.components.MenuConfirmRow
import to.eyed.inferno.ui.components.OwlMark
import to.eyed.inferno.ui.components.surfaceLow
import to.eyed.inferno.ui.theme.Ink
import to.eyed.inferno.ui.theme.Radii
import to.eyed.inferno.ui.theme.RowMeta
import to.eyed.inferno.ui.theme.Typography
import to.eyed.inferno.ui.theme.fastSpatialSpec
import to.eyed.inferno.ui.theme.rememberHaptics
import java.util.Calendar

/** Sidebar width, both as a drawer and as the permanent pane. */
val SidebarWidth = 300.dp

/**
 * Drawer content: wordmark, search, the four destinations, the chat list grouped by date and the
 * Benchmark / Settings footer. Long-press on a chat opens the anchored menu (rename / pin / archive / delete).
 */
@Composable
fun Sidebar(
    conversations: List<Conversation>,
    activeId: String?,
    busyIds: Set<String>,
    loadedModelName: String?,
    deviceSummary: String,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    onCreateImage: () -> Unit,
    onGallery: () -> Unit,
    onRename: (Conversation) -> Unit,
    onPin: (String, Boolean) -> Unit,
    onArchive: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBench: () -> Unit,
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** Developer mode: the Benchmark row in the footer. */
    showBenchmark: Boolean = false,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var archivedOpen by rememberSaveable { mutableStateOf(false) }
    val filtered = remember(conversations, query) {
        if (query.isBlank()) conversations else conversations.filter { it.displayTitle.contains(query.trim(), ignoreCase = true) }
    }
    val groups = remember(filtered) { groupConversations(filtered) }

    Column(modifier.width(SidebarWidth).fillMaxHeight().background(Ink.I900)) {
        // In touch mode text fields are the only focusable nodes, so the drawer's initial focus would land in
        // the search field and pop the keyboard on every open. This 1 dp sink takes it instead.
        Box(Modifier.size(1.dp).focusable())
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            OwlMark(20.dp)
            Spacer(Modifier.width(10.dp))
            Text(S.appName, style = Typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = (-0.2).sp), color = Ink.White, modifier = Modifier.weight(1f))
            if (onClose != null) GhostIconButton(Lucide.X, S.closeSidebar, onClose, size = 40.dp, iconSize = 18.dp, tint = Ink.I300)
        }
        SearchPill(query, { query = it }, Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
        // The destinations scroll with the chat list so a short window (phone landscape) still reaches every chat.
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
            item(key = "nav", contentType = "nav") {
                Column {
                    NavRow(Lucide.SquarePen, S.newChat, onClick = onNew)
                    NavRow(Lucide.Wand, S.createImage, onClick = onCreateImage)
                    NavRow(Lucide.Images, S.gallery, onClick = onGallery)
                    NavRow(Lucide.HardDrive, S.models, subtitle = loadedModelName ?: S.noModelLoaded, onClick = onOpenModels)
                }
            }
            if (conversations.isEmpty()) item(key = "empty") {
                Text(S.noChatsYet, style = Typography.bodyMedium, color = Ink.I500, modifier = Modifier.padding(start = 8.dp, top = 14.dp))
            } else if (filtered.isEmpty()) item(key = "nomatch") {
                Text(S.noChatsMatch(query.trim()), style = Typography.bodyMedium, color = Ink.I500, modifier = Modifier.padding(start = 8.dp, top = 14.dp))
            }
            groups.forEach { (label, list) ->
                if (list.isEmpty()) return@forEach
                item(key = "h-$label", contentType = "header") { GroupHeader(label, Modifier.animateItem()) }
                list.forEach { c ->
                    item(key = "c-${c.id}", contentType = "row") {
                        ThreadRow(c, c.id == activeId, c.id in busyIds, onSelect, onRename, onPin, onArchive, onDelete, Modifier.animateItem())
                    }
                }
            }
            val archived = filtered.filter { it.archived }
            if (archived.isNotEmpty()) {
                item(key = "h-archived", contentType = "header") {
                    val haptics = rememberHaptics()
                    val rotation by animateFloatAsState(if (archivedOpen) 180f else 0f, fastSpatialSpec(), label = "arch")
                    Row(
                        Modifier
                            .animateItem()
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radii.control))
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) { haptics.tap(); archivedOpen = !archivedOpen }
                            .padding(start = 8.dp, end = 8.dp, top = 18.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${S.archived} · ${archived.size}", style = Typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = Ink.I500, modifier = Modifier.weight(1f).semantics { heading() })
                        Icon(Lucide.ChevronDown, null, Modifier.size(14.dp).graphicsLayer { rotationZ = rotation }, tint = Ink.I500)
                    }
                }
                if (archivedOpen) archived.forEach { c ->
                    item(key = "a-${c.id}", contentType = "row") {
                        ThreadRow(c, c.id == activeId, c.id in busyIds, onSelect, onRename, onPin, onArchive, onDelete, Modifier.animateItem())
                    }
                }
            }
            item(key = "bottom") { Spacer(Modifier.height(12.dp)) }
        }
        Hairline()
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (showBenchmark) NavRow(Lucide.Gauge, S.benchmark, onClick = onOpenBench)
            NavRow(Lucide.Settings, S.settings, onClick = onOpenSettings)
            Text(deviceSummary, style = RowMeta, color = Ink.I500, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp))
        }
    }
}

@Composable
private fun SearchPill(query: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().surfaceLow(CircleShape).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Lucide.Search, null, Modifier.size(16.dp), tint = Ink.I500)
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = query,
            onValueChange = onChange,
            singleLine = true,
            textStyle = Typography.labelLarge.copy(color = Ink.White),
            cursorBrush = SolidColor(Ink.White),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            decorationBox = { inner ->
                Box(Modifier.padding(vertical = 6.dp)) {
                    if (query.isEmpty()) Text(S.searchChats, style = Typography.labelLarge, color = Ink.I500)
                    inner()
                }
            },
            modifier = Modifier.weight(1f),
        )
        if (query.isNotEmpty()) GhostIconButton(Lucide.X, S.cancel, onClick = { onChange("") }, size = 28.dp, iconSize = 13.dp, tint = Ink.I500)
        else Spacer(Modifier.width(28.dp))
    }
}

/** Destination row: 19 dp icon + 15 sp label, optional 12 sp subtitle. */
@Composable
private fun NavRow(icon: ImageVector, title: String, onClick: () -> Unit, subtitle: String? = null) {
    val haptics = rememberHaptics()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.row))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) { haptics.tap(); onClick() }
            .padding(horizontal = 8.dp, vertical = if (subtitle == null) 11.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, Modifier.size(19.dp), tint = Ink.I100)
        Column {
            Text(title, style = Typography.titleSmall, color = Ink.I100)
            if (subtitle != null) Text(subtitle, style = Typography.labelMedium.copy(fontWeight = FontWeight.Normal), color = Ink.I500, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun GroupHeader(label: String, modifier: Modifier = Modifier) {
    Text(
        label,
        style = Typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        color = Ink.I500,
        modifier = modifier.padding(start = 8.dp, end = 8.dp, top = 18.dp, bottom = 6.dp).semantics { heading() },
    )
}

@Composable
private fun ThreadRow(
    conv: Conversation,
    active: Boolean,
    busy: Boolean,
    onSelect: (String) -> Unit,
    onRename: (Conversation) -> Unit,
    onPin: (String, Boolean) -> Unit,
    onArchive: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 1.dp)
                .clip(RoundedCornerShape(Radii.row))
                .background(if (active) Ink.Surface else Color.Transparent)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = { onSelect(conv.id) },
                    onLongClick = { haptics.longPress(); menu = true },
                )
                .padding(horizontal = 8.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (conv.pinned) {
                Icon(Lucide.Pin, null, Modifier.size(13.dp), tint = Ink.I500)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                conv.displayTitle,
                style = Typography.bodyLarge.copy(lineHeight = 20.sp),
                color = if (active) Ink.White else Ink.I100,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (busy) {
                Spacer(Modifier.width(8.dp))
                LoadingIndicator(Modifier.size(16.dp), color = Ink.I300)
            }
        }
        GlassMenu(expanded = menu, onDismiss = { menu = false }) {
            MenuActionRow(Lucide.Pencil, S.rename) { menu = false; onRename(conv) }
            MenuActionRow(Lucide.Pin, if (conv.pinned) S.unpin else S.pin) { menu = false; onPin(conv.id, !conv.pinned) }
            MenuActionRow(if (conv.archived) Lucide.ArchiveRestore else Lucide.Archive, if (conv.archived) S.unarchive else S.archive) { menu = false; onArchive(conv.id, !conv.archived) }
            MenuConfirmRow(Lucide.Trash2, S.delete, onConfirm = { menu = false; onDelete(conv.id) })
        }
    }
}

/** Pinned first, then Today / Yesterday / Previous 7 days / Older; archived chats are excluded here. */
internal fun groupConversations(list: List<Conversation>, now: Long = System.currentTimeMillis()): List<Pair<String, List<Conversation>>> {
    val live = list.filter { !it.archived }
    val pinned = live.filter { it.pinned }
    val rest = live.filter { !it.pinned }
    val order = listOf(S.today, S.yesterday, S.previous7Days, S.older)
    val byGroup = rest.groupBy { groupLabel(it.updatedAt, now) }
    return listOf(S.pinned to pinned) + order.map { it to byGroup[it].orEmpty() }
}

internal fun groupLabel(updatedAt: Long, now: Long = System.currentTimeMillis()): String {
    val startOfToday = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val yesterday = (startOfToday.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    val week = (startOfToday.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -7) }
    return when {
        updatedAt >= startOfToday.timeInMillis -> S.today
        updatedAt >= yesterday.timeInMillis -> S.yesterday
        updatedAt >= week.timeInMillis -> S.previous7Days
        else -> S.older
    }
}
