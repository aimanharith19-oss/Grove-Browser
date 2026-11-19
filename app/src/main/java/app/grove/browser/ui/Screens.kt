package app.grove.browser.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.material.icons.filled.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items as listItems
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.grove.browser.Bookmark
import app.grove.browser.BrowserVM
import app.grove.browser.ENGINES
import app.grove.browser.Gecko
import app.grove.browser.Icons
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import app.grove.browser.Urls
import app.grove.browser.Thumbs
import app.grove.browser.DOH
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

/** Windows Phone "tilt": a tile sinks a little while your finger is on it. */
@Composable
fun Modifier.pressTile(source: MutableInteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, wp(180), label = "tile")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

@Composable
fun Header(title: String) {
    Text(title.replaceFirstChar { it.uppercase() }, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 10.dp))
}

/**
 * New tab page, Edge style: the picture of the day behind a search box and your favorites as quick-link tiles.
 * Favorites are sorted into speed dial groups (Vivaldi style): tap a group to see its tiles, long-press it to rename or delete.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(vm: BrowserVM, onOpen: (String) -> Unit, onEdit: (Bookmark) -> Unit, onAdd: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    LaunchedEffect(vm.prefs.ntpImage) { vm.loadNtpPicture() }
    val pic = vm.ntpPicture
    val onPic = pic != null
    val groups = remember(vm.bookmarks.items.toList(), vm.extraGroups.toList()) {
        (listOf("") + vm.bookmarks.items.map { it.group } + vm.extraGroups).distinct()
    }
    var group by rememberSaveable { mutableStateOf("") }
    if (group !in groups) group = ""
    var editGroup by remember { mutableStateOf<String?>(null) }
    var newGroup by remember { mutableStateOf(false) }
    val text = if (onPic) Color.White else cs.onBackground
    Box(Modifier.fillMaxSize().background(cs.background)) {
        if (pic != null) {
            Image(pic, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.15f), 0.5f to Color.Black.copy(alpha = 0.35f), 1f to Color.Black.copy(alpha = 0.6f))))
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(96.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 40.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text("Grove", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = text, modifier = Modifier.padding(bottom = 14.dp))
                    HomeSearch(onPic) { vm.go(it) }
                    // speed dial groups
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 18.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        groups.forEach { g ->
                            val on = g == group
                            Text(g.ifBlank { "Favorites" }, fontSize = 14.sp, color = if (on) Color.White else text,
                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.clip(RoundedCornerShape(16.dp))
                                    .background(if (on) cs.primary else if (onPic) Color.White.copy(alpha = 0.18f) else cs.surfaceVariant)
                                    .combinedClickable(onClick = { group = g }, onLongClick = { if (g.isNotBlank()) editGroup = g })
                                    .padding(horizontal = 14.dp, vertical = 7.dp))
                        }
                        Text("+ Group", fontSize = 14.sp, color = text.copy(alpha = 0.8f),
                            modifier = Modifier.clip(RoundedCornerShape(16.dp)).border(1.dp, text.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                                .clickable { newGroup = true }.padding(horizontal = 14.dp, vertical = 6.dp))
                    }
                }
            }
            val list = vm.bookmarks.items.filter { it.group == group }
            items(list, key = { it.id }) { b ->
                val src = remember { MutableInteractionSource() }
                val icon = Icons.get(ctx, b.url)
                val shot = Thumbs.get(ctx, b.url)
                Column(Modifier.pressTile(src).combinedClickable(interactionSource = src, indication = null, onClick = { onOpen(b.url) }, onLongClick = { onEdit(b) }),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    // quick link: a rounded card with the site's picture (or its icon), the name underneath
                    Box(Modifier.fillMaxWidth().aspectRatio(1.1f).clip(RoundedCornerShape(14.dp))
                        .background(if (onPic) Color.White.copy(alpha = 0.85f) else cs.surface)
                        .border(1.dp, if (onPic) Color.Transparent else cs.outline.copy(alpha = 0.35f), RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center) {
                        if (shot != null) Image(shot, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
                        val badge = Modifier.size(if (shot != null) 26.dp else 40.dp).clip(RoundedCornerShape(8.dp)).background(Color.White)
                        Box(if (shot != null) Modifier.fillMaxSize().padding(6.dp) else Modifier, contentAlignment = if (shot != null) Alignment.BottomStart else Alignment.Center) {
                            if (icon != null) Box(badge, contentAlignment = Alignment.Center) { Image(icon, null, Modifier.fillMaxSize(0.75f)) }
                            else Box(badge.background(cs.primary), contentAlignment = Alignment.Center) {
                                Text(b.title.trim().take(1).uppercase().ifBlank { "•" }, color = Color.White, fontSize = if (shot != null) 13.sp else 20.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    Text(b.title.ifBlank { Urls.host(b.url) }, color = text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp))
                }
            }
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1.1f).clip(RoundedCornerShape(14.dp))
                        .background(if (onPic) Color.White.copy(alpha = 0.18f) else cs.surfaceVariant).clickable { onAdd(group) },
                        contentAlignment = Alignment.Center) {
                        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.Add, "add", tint = text, modifier = Modifier.size(28.dp))
                    }
                    Text("Add", color = text, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
            if (list.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Tap + or the ☆ in the address bar to add a site. Long-press a tile to edit it or move it to another group.",
                    color = text.copy(alpha = 0.8f), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
    if (newGroup) NameDialog("New group", "") { n -> newGroup = false; if (!n.isNullOrBlank()) { vm.extraGroups.add(n.trim()); group = n.trim() } }
    editGroup?.let { g ->
        NameDialog("Rename group", g, delete = { vm.deleteGroup(g); vm.extraGroups.remove(g); editGroup = null; group = "" }) { n ->
            editGroup = null
            if (!n.isNullOrBlank() && n.trim() != g) { vm.renameGroup(g, n); vm.extraGroups.remove(g); group = n.trim() }
        }
    }
}

@Composable
private fun HomeSearch(onPic: Boolean, onGo: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var q by remember { mutableStateOf("") }
    BasicTextField(q, { q = it }, singleLine = true,
        textStyle = TextStyle(color = Color(0xFF1B1B1B), fontSize = 16.sp),
        cursorBrush = SolidColor(cs.primary),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onGo = { if (q.isNotBlank()) { onGo(q); q = "" } }),
        modifier = Modifier.fillMaxWidth().height(48.dp),
        decorationBox = { inner ->
            Row(Modifier.fillMaxSize().shadow(if (onPic) 0.dp else 2.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp))
                .background(Color.White).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.Search, null, tint = Color(0xFF616161), modifier = Modifier.size(20.dp))
                Box(Modifier.weight(1f).padding(start = 10.dp)) {
                    if (q.isEmpty()) Text("Search or enter web address", color = Color(0xFF8A8A8A), fontSize = 16.sp, maxLines = 1)
                    inner()
                }
            }
        })
}

/** Small dialog asking for a name (groups). Returns null when cancelled. */
@Composable
fun NameDialog(title: String, start: String, delete: (() -> Unit)? = null, done: (String?) -> Unit) {
    var name by remember { mutableStateOf(start) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { done(null) },
        title = { Text(title) },
        text = { androidx.compose.material3.OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true) },
        confirmButton = { androidx.compose.material3.TextButton({ done(name) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (delete != null) androidx.compose.material3.TextButton(delete) { Text("Delete group") }
                androidx.compose.material3.TextButton({ done(null) }) { Text("Cancel") }
            }
        },
    )
}

/**
 * Open tabs in a sheet that slides up over the page, like Firefox for Android: rounded top, a handle to drag it down,
 * "tabs" and "inprivate" pages, each tab a card with its picture; the one you're on is outlined. Swipe a card sideways to close it.
 */
@Composable
fun TabsSheet(vm: BrowserVM, visible: Boolean, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val spring = androidx.compose.animation.core.spring<androidx.compose.ui.unit.IntOffset>(dampingRatio = 0.86f, stiffness = 420f)
    Box(Modifier.fillMaxSize()) {
        androidx.compose.animation.AnimatedVisibility(visible, enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose))
        }
        androidx.compose.animation.AnimatedVisibility(visible, modifier = Modifier.align(Alignment.BottomCenter),
            enter = androidx.compose.animation.slideInVertically(spring) { it }, exit = androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(220)) { it }) {
            var private by remember { mutableStateOf(vm.cur?.private == true) }
            var drag by remember { mutableFloatStateOf(0f) }
            val shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.86f)
                .graphicsLayer { translationY = drag.coerceAtLeast(0f) }
                .clip(shape).background(cs.surface).navigationBarsPadding()) {
                // handle + header: drag down to close
                Column(Modifier.fillMaxWidth().pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = { if (drag > size.height * 2.5f) onClose(); drag = 0f },
                        onDragCancel = { drag = 0f },
                    ) { c, d -> c.consume(); drag += d }
                }) {
                    Box(Modifier.padding(top = 10.dp).align(Alignment.CenterHorizontally).width(40.dp).height(4.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp)).background(cs.onSurface.copy(alpha = 0.3f)))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        val normal = vm.tabs.count { !it.private }; val priv = vm.tabs.size - normal
                        SheetTab("Tabs  $normal", !private) { private = false }
                        SheetTab("InPrivate  $priv", private) { private = true }
                        Spacer(Modifier.weight(1f))
                        // tab tricks (Vivaldi style)
                        var tools by remember { mutableStateOf(false) }
                        Box {
                            Box(Modifier.size(40.dp).clip(CircleShape).clickable { tools = true }, contentAlignment = Alignment.Center) {
                                androidx.compose.material3.Icon(EI.More, "tab tools", tint = cs.onSurface, modifier = Modifier.size(22.dp))
                            }
                            androidx.compose.material3.DropdownMenu(tools, { tools = false }) {
                                @Composable fun item(label: String, enabled: Boolean = true, go: () -> Unit) =
                                    androidx.compose.material3.DropdownMenuItem({ Text(label) }, { tools = false; go() }, enabled = enabled)
                                item("Reopen closed tab", vm.closed.isNotEmpty()) { vm.reopenClosed(); onClose() }
                                item("Close duplicate tabs") { vm.closeDuplicates(private) }
                                item("Stack tabs by site") { vm.sortBySite() }
                                item("Close other tabs", vm.cur?.private == private) { vm.cur?.let { vm.closeOthers(it) } }
                                item(if (private) "Close all InPrivate tabs" else "Close all tabs") { vm.closeAll(private); onClose() }
                            }
                        }
                    }
                }
                val list = vm.tabs.filter { it.private == private }
                Box(Modifier.weight(1f)) {
                    if (list.isEmpty()) Text(if (private) "no inprivate tabs" else "no tabs", color = cs.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center))
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2), modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 88.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(list, key = { System.identityHashCode(it) }) { tab -> TabCard(vm, tab, onClose) }
                    }
                    // new tab button, bottom right (like Firefox's)
                    Row(Modifier.align(Alignment.BottomEnd).padding(16.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(28.dp))
                        .background(if (private) Color(0xFF4A2C6E) else cs.primary).clickable { vm.newTab(private = private); onClose() }
                        .padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.Add, null, tint = Color.White, modifier = Modifier.size(22.dp))
                        Text(if (private) "InPrivate tab" else "New tab", color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetTab(label: String, on: Boolean, click: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Text(label, fontSize = 15.sp, color = if (on) Color.White else cs.onSurface,
        modifier = Modifier.padding(end = 8.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
            .background(if (on) cs.primary else cs.surfaceVariant).clickable(onClick = click).padding(horizontal = 14.dp, vertical = 7.dp))
}

@Composable
private fun TabCard(vm: BrowserVM, tab: app.grove.browser.Tab, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val active = tab === vm.cur
    val src = remember(tab) { MutableInteractionSource() }
    var dx by remember(tab) { mutableFloatStateOf(0f) }
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
    Column(Modifier.pressTile(src).fillMaxWidth().aspectRatio(0.78f)
        .graphicsLayer { translationX = dx; alpha = 1f - (abs(dx) / size.width.coerceAtLeast(1f)).coerceIn(0f, 0.85f) }
        .pointerInput(tab) {
            detectHorizontalDragGestures(
                onDragEnd = { if (abs(dx) > size.width * 0.38f) vm.closeTab(tab); dx = 0f },
                onDragCancel = { dx = 0f },
            ) { change, d -> change.consume(); dx += d }
        }
        .clip(shape)
        .border(if (active) 3.dp else 1.dp, if (active) cs.primary else cs.onSurface.copy(alpha = 0.15f), shape)
        .background(cs.surfaceContainerHigh)
        .clickable(interactionSource = src, indication = LocalIndication.current) { vm.select(tab); onClose() }) {
        Box(Modifier.fillMaxWidth().weight(1f).background(cs.background)) {
            val shot = tab.thumb
            when {
                shot != null && !tab.home -> Image(shot, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
                tab.home -> Box(Modifier.fillMaxSize().tintBackground(), contentAlignment = Alignment.Center) {
                    Text("New tab", color = cs.onBackground, fontSize = 15.sp)
                }
                else -> Box(Modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.Center) {
                    Text(Urls.host(tab.url), color = cs.onSurfaceVariant, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(38.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val icon = if (tab.home) null else Icons.get(ctx, tab.url)
            if (icon != null) Image(icon, null, Modifier.size(16.dp))
            Text(if (tab.home) "new tab" else tab.title.ifBlank { Urls.host(tab.url) }, color = cs.onSurface, fontSize = 13.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 6.dp))
            Box(Modifier.size(38.dp).clickable { vm.closeTab(tab) }, contentAlignment = Alignment.Center) {
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.Close, "close", tint = cs.onSurface, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LinksScreen(
    title: String, links: List<Pair<String, String>>, onOpen: (String) -> Unit,
    onLong: ((Int) -> Unit)? = null, onClear: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    val shown = if (q.isBlank()) links.withIndex().toList() else links.withIndex().filter { (_, l) -> l.first.contains(q, true) || l.second.contains(q, true) }
    Column(Modifier.fillMaxSize().tintBackground()) {
        Header(title)
        if (links.size > 6) BasicTextField(q, { q = it }, singleLine = true, textStyle = TextStyle(color = Color.Black, fontSize = 16.sp),
            cursorBrush = SolidColor(cs.primary),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().height(38.dp).background(Color.White).padding(horizontal = 10.dp, vertical = 9.dp),
            decorationBox = { inner -> Box { if (q.isEmpty()) Text("search $title", color = Color.Gray, fontSize = 16.sp); inner() } })
        if (links.isEmpty()) Text("nothing here yet", color = cs.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        LazyColumn(Modifier.weight(1f)) {
            listItems(shown) { (i, link) ->
                val (name, url) = link
                Row(Modifier.fillMaxWidth().combinedClickable(onClick = { onOpen(url) }, onLongClick = { onLong?.invoke(i) })
                    .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val icon = Icons.get(ctx, url)
                Box(Modifier.size(36.dp).background(if (icon != null) Color.White else cs.primary), contentAlignment = Alignment.Center) {
                    if (icon != null) Image(icon, null, Modifier.size(26.dp))
                    else Text(name.ifBlank { Urls.host(url) }.take(1).uppercase(), color = Color.White, fontSize = 18.sp)
                }
                Column(Modifier.padding(start = 12.dp)) {
                    Text(name.ifBlank { Urls.host(url) }, fontSize = 20.sp, fontWeight = FontWeight.Light,
                        color = cs.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(url, fontSize = 12.sp, color = cs.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                }
            }
        }
        if (onClear != null && links.isNotEmpty())
            Text("clear", fontSize = 18.sp, color = cs.onSurface, modifier = Modifier.fillMaxWidth()
                .background(cs.surfaceVariant).clickable(onClick = onClear).padding(16.dp))
    }
}

@Composable
fun SettingsScreen(vm: BrowserVM, onAddons: () -> Unit, onClose: () -> Unit) {
    val p = vm.prefs
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(vm::exportBookmarks) }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importBookmarks) }
    val autoDir = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let { u -> vm.setBackupFolder(u) } }
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }
    val I = androidx.compose.material.icons.Icons.Default
    var open by remember { mutableStateOf<String?>(null) } // the choice row that's expanded

    // One setting per row: icon, short name, and its value (a switch, or the current choice) on the right.
    @Composable fun row(icon: ImageVector, title: String, value: String? = null, sub: String? = null, click: () -> Unit, end: @Composable () -> Unit = {}) =
        Row(Modifier.fillMaxWidth().clickable(onClick = click).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(cs.primary.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Icon(icon, null, tint = cs.primary, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(title, fontSize = 17.sp, color = cs.onBackground)
                if (value != null) Text(value, fontSize = 13.sp, color = cs.primary)
                if (sub != null) Text(sub, fontSize = 12.sp, color = cs.onSurfaceVariant)
            }
            end()
        }
    @Composable fun toggle(icon: ImageVector, title: String, on: Boolean, sub: String? = null, click: () -> Unit) =
        row(icon, title, sub = sub, click = click) { androidx.compose.material3.Switch(on, { click() }) }
    @Composable fun choice(icon: ImageVector, title: String, options: List<String>, sel: Int, pick: (Int) -> Unit) {
        val expanded = open == title
        row(icon, title, value = options.getOrNull(sel) ?: "", click = { open = if (expanded) null else title }) {
            androidx.compose.material3.Icon(if (expanded) I.KeyboardArrowUp else I.KeyboardArrowDown, null, tint = cs.onSurfaceVariant)
        }
        androidx.compose.animation.AnimatedVisibility(expanded) {
            Column(Modifier.fillMaxWidth().padding(start = 50.dp)) {
                options.forEachIndexed { i, o ->
                    Row(Modifier.fillMaxWidth().clickable { pick(i); open = null }.padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(sel == i, { pick(i); open = null })
                        Text(o, fontSize = 15.sp, color = cs.onBackground)
                    }
                }
            }
        }
    }
    @Composable fun group(s: String) =
        Text(s.uppercase(), color = cs.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 2.dp))

    var page by remember { mutableStateOf<String?>(null) }
    androidx.activity.compose.BackHandler(enabled = page != null) { page = null; open = null }
    val pages = listOf(
        Triple("general", I.Home, "search, address bar, links"),
        Triple("appearance", I.Edit, "theme, colour, text size"),
        Triple("privacy", I.Lock, "trackers, https, dns, data"),
        Triple("media", I.PlayArrow, "background, picture in picture"),
        Triple("downloads", I.KeyboardArrowDown, "where files go"),
        Triple("favorites", I.Star, "backup and restore"),
        Triple("extensions", I.Build, "groveguard, add-ons"),
        Triple("about", I.Info, "grove browser $version"),
    )
    Column(Modifier.fillMaxSize().tintBackground().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Header(page ?: "settings")
        when (page) {
            null -> pages.forEach { (name, icon, sub) -> row(icon, name, sub = sub, click = { if (name == "extensions") onAddons() else page = name }) }
            "general" -> {
                choice(I.Search, "search engine", ENGINES.map { it.first }, p.engine) { p.engine = it }
                choice(I.KeyboardArrowDown, "address bar", listOf("bottom", "top (like Edge)"), p.barTop) { p.barTop = it }
                choice(I.ExitToApp, "open links in apps", listOf("ask", "always", "never"), p.appLinks) { p.appLinks = it }
                row(I.CheckCircle, "default browser", sub = "make grove your default", click = {
                    runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
                })
            }
            "appearance" -> {
                choice(I.Edit, "theme", listOf("system", "dark", "light"), p.theme) { p.theme = it }
                group("accent colour")
                ACCENTS.indices.chunked(7).forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        r.forEach { i ->
                            Box(Modifier.weight(1f).aspectRatio(1f).clip(CircleShape).background(Color(ACCENTS[i]))
                                .then(if (p.accent == i) Modifier.border(3.dp, cs.onBackground, CircleShape) else Modifier)
                                .clickable { p.accent = i })
                        }
                    }
                }
                toggle(I.Home, "picture of the day", p.ntpImage == 1, "behind the new tab page (from Bing, once a day)") { p.ntpImage = 1 - p.ntpImage }
                group("screen")
                toggle(I.KeyboardArrowUp, "full screen", p.immersive == 1, "hide status and navigation bars") { p.immersive = 1 - p.immersive }
                choice(I.Create, "text size", listOf(80, 100, 120, 150, 200).map { "$it%" }, listOf(80, 100, 120, 150, 200).indexOf(p.textSize)) {
                    p.textSize = listOf(80, 100, 120, 150, 200)[it]; vm.applyPrefs()
                }
                toggle(I.Search, "always allow zoom", p.forceZoom == 1) { p.forceZoom = 1 - p.forceZoom; vm.applyPrefs() }
            }
            "privacy" -> {
                choice(I.Lock, "tracker blocker", listOf("strict", "standard", "off"), p.etp) { p.etp = it; Gecko.setEtp(it) }
                choice(I.CheckCircle, "https", listOf("https first", "https only", "allow http"), p.https) { p.https = it; Gecko.setHttps(it) }
                choice(I.Place, "secure dns", listOf("off") + DOH.map { it.first }, p.doh) { p.doh = it; vm.applyPrefs() }
                group("site data")
                toggle(I.ExitToApp, "clear when you quit", p.clearOnQuit == 1, "adds quit to the ••• menu") { p.clearOnQuit = 1 - p.clearOnQuit }
                row(I.Delete, "clear history and site data", click = { confirmClear = true })
                Text("groveguard blocks ads, trackers, scams and 18+ sites · no telemetry", fontSize = 12.sp, color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }
            "media" -> {
                toggle(I.PlayArrow, "background play", p.bgPlay == 1, "keep playing when you leave grove") { vm.setBgPlay(p.bgPlay == 0) }
                toggle(I.Home, "picture in picture", p.pip != 0, "full-screen video floats when you leave grove") { p.pip = if (p.pip != 0) 0 else 1 }
                toggle(I.List, "picture in picture button", p.miniBtn == 1, "on videos") { p.miniBtn = 1 - p.miniBtn; vm.toast("applies to pages you open from now") }
            }
            "downloads" -> {
                choice(I.KeyboardArrowDown, "download with", listOf("grove", "another app"), p.downloader) { p.downloader = it }
            }
            "favorites" -> {
                row(I.Send, "back up", click = { backup.launch("grove-favorites.json") })
                row(I.Refresh, "restore", sub = "also firefox, chrome, vivaldi files", click = { restore.launch(arrayOf("*/*")) })
                val folder = vm.backupFolder
                toggle(I.DateRange, "automatic backup", folder != null, folder) { if (folder == null) autoDir.launch(null) else vm.setBackupFolder(null) }
            }
            "about" -> {
                row(I.Info, "grove browser", value = version, sub = "gecko engine", click = {})
            }
        }
    }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("delete history and site data?") },
        text = { Text("cookies, cache, logins on sites and recent pages will be removed. favorites are kept.") },
        confirmButton = { TextButton({ vm.clearData(); confirmClear = false }) { Text("delete") } },
        dismissButton = { TextButton({ confirmClear = false }) { Text("cancel") } },
    )
}

@Composable
fun AddonsScreen(vm: BrowserVM, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().tintBackground()) {
        Header("extensions")
        if (Gecko.addons.isEmpty()) Text("no extensions yet", color = cs.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        LazyColumn(Modifier.weight(1f)) {
            listItems(Gecko.addons.toList(), key = { it.id }) { e ->
                val m = e.metaData
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text((m.name ?: e.id).lowercase(), fontSize = 22.sp, fontWeight = FontWeight.Light, color = cs.onBackground)
                    val builtIn = e.id in Gecko.LOCKED
                    Text("version ${m.version} · " + if (builtIn) "built in · always on" else if (m.enabled) "on" else "off", fontSize = 12.sp, color = cs.primary)
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(top = 4.dp)) {
                        // the add-on's popup (e.g. uBlock Origin's on/off switch for the current site)
                        if (m.enabled && Gecko.actions.containsKey(e.id)) Text("open", color = cs.primary,
                            modifier = Modifier.clickable { onClose(); Gecko.actions[e.id]?.click() }.padding(vertical = 6.dp))
                        if (!builtIn) Text(if (m.enabled) "turn off" else "turn on", color = cs.onBackground,
                            modifier = Modifier.clickable { Gecko.setEnabled(e, !m.enabled) }.padding(vertical = 6.dp))
                        if (m.enabled && m.optionsPageUrl != null) Text("settings", color = cs.onBackground,
                            modifier = Modifier.clickable { vm.openOptions(e); onClose() }.padding(vertical = 6.dp))
                        if (!builtIn) Text("remove", color = cs.onBackground, modifier = Modifier.clickable { Gecko.remove(e) }.padding(vertical = 6.dp))
                    }
                }
            }
        }
        Text("get firefox add-ons", fontSize = 18.sp, color = cs.onSurface, modifier = Modifier.fillMaxWidth()
            .background(cs.surfaceVariant).clickable { vm.newTab("https://addons.mozilla.org/android/"); onClose() }.padding(16.dp))
    }
}
