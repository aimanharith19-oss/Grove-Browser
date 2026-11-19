package app.grove.browser.ui

import androidx.compose.material.icons.filled.*
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import app.grove.browser.Gecko
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.grove.browser.Bookmark
import app.grove.browser.BrowserVM
import app.grove.browser.MainActivity
import app.grove.browser.R
import app.grove.browser.Tab
import app.grove.browser.Urls
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.navigationBarsPadding

enum class Overlay { NONE, TABS, FAVORITES, RECENT, SETTINGS, ADDONS, SAVED, NOTES }

/** A menu entry: an icon and label in the Edge-style menu grid. */
class MenuItem(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector = EI.More, val action: () -> Unit)

@Composable
fun BrowserScreen(vm: BrowserVM) {
    val t = vm.cur ?: return
    val ctx = LocalContext.current
    val activity = ctx as Activity
    val focus = LocalFocusManager.current
    var overlay by rememberSaveable { mutableStateOf(Overlay.NONE) }
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var find by remember { mutableStateOf(false) }
    var editBm by remember { mutableStateOf<Bookmark?>(null) }
    var pageFx by remember { mutableStateOf(false) }

    val standalone = vm.webApp != null
    val immersive = vm.fullscreen || vm.pip
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(vm.needNotifPermission) {
        if (vm.needNotifPermission) { vm.needNotifPermission = false; runCatching { notif.launch(Manifest.permission.POST_NOTIFICATIONS) } }
    }

    BackHandler {
        when {
            vm.fullscreen -> t.session.exitFullScreen()
            editing -> focus.clearFocus()
            menu -> menu = false
            pageFx -> pageFx = false
            find -> { find = false; t.session.finder.clear() }
            overlay != Overlay.NONE -> overlay = Overlay.NONE
            t.canBack -> t.session.goBack()
            standalone -> activity.finish()
            t.parent != null || (t.home && vm.tabs.size > 1) -> vm.closeTab(t)
            else -> activity.moveTaskToBack(true)
        }
    }

    // Full screen: Android's status bar and navigation bar are hidden (swipe from the edge to peek at them).
    val hideBars = vm.fullscreen || (vm.prefs.immersive == 1 && !standalone)
    LaunchedEffect(hideBars) {
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            if (hideBars) {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            } else show(WindowInsetsCompat.Type.systemBars())
        }
    }

    val bg = MaterialTheme.colorScheme.background
    val tint = barColor(t)
    val edge by animateColorAsState(if ((overlay == Overlay.NONE || overlay == Overlay.TABS) && !t.home) tint else bg, wp(300), label = "edge")
    val view = LocalView.current
    SideEffect {
        WindowCompat.getInsetsController(activity.window, view).apply {
            val light = edge.luminance() > 0.5f
            isAppearanceLightStatusBars = light; isAppearanceLightNavigationBars = light
        }
    }
    val web = !t.home && Urls.isWeb(t.url)
    // Edge-style menu: one grid of icons (4 per row)
    val items = buildList {
        add(MenuItem("Favorites", androidx.compose.material.icons.Icons.Default.Star) { overlay = Overlay.FAVORITES })
        add(MenuItem("History", EI.History) { overlay = Overlay.RECENT })
        add(MenuItem("Downloads", EI.Download) { vm.openDownloads() })
        add(MenuItem("Notes", EI.Note) { overlay = Overlay.NOTES })
        add(MenuItem("InPrivate", androidx.compose.material.icons.Icons.Default.Lock) { vm.newTab(private = true) })
        if (!t.home) add(MenuItem("Share", androidx.compose.material.icons.Icons.Default.Share) { share(ctx, t.url) })
        if (!t.home) add(MenuItem("Find on page", androidx.compose.material.icons.Icons.Default.Search) { find = true })
        if (web) add(MenuItem("Add note", androidx.compose.material.icons.Icons.Default.Create) { vm.noteFromPage(t) })
        if (web) add(MenuItem("Page actions", EI.Palette) { pageFx = true })
        if (web) add(MenuItem("Reader", EI.Reader) { vm.reader(t, "view") })
        if (web) add(MenuItem("Picture in picture", EI.Pip) { vm.pipNow(t) })
        if (vm.closed.isNotEmpty()) add(MenuItem("Reopen tab", EI.Reopen) { vm.reopenClosed() })
        if (!t.home) add(MenuItem(if (t.desktop) "Mobile site" else "Desktop site", EI.Desktop) { vm.toggleDesktop(t) })
        if (vm.saved.items.isNotEmpty()) add(MenuItem("Saved pages", EI.Saved) { overlay = Overlay.SAVED })
        if (web) {
            add(MenuItem("Save offline", EI.Saved) { vm.reader(t, "save") })
            add(MenuItem("Save as PDF", EI.Download) { vm.savePdf(t) })
            add(MenuItem("Print", EI.Print) { vm.print(t) })
            if (t.manifest != null) add(MenuItem("Install app", EI.Install) { vm.installWebApp(t) })
            add(MenuItem("Pin to start", EI.Pin) { pin(ctx, t) })
            if (t.trackers > 0) add(MenuItem("${t.trackers} trackers", EI.Shield) { vm.toast("grove's tracking protection stopped ${t.trackers} trackers on this page") })
        }
        add(MenuItem("Extensions", EI.Puzzle) { overlay = Overlay.ADDONS })
        add(MenuItem("Settings", androidx.compose.material.icons.Icons.Default.Settings) { overlay = Overlay.SETTINGS })
        if (vm.prefs.clearOnQuit == 1) add(MenuItem("Quit", EI.Exit) { vm.clearData(); activity.finishAndRemoveTask() })
    }
    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize().background(edge)
            .then(when {
                immersive -> Modifier
                hideBars -> Modifier.windowInsetsPadding(WindowInsets.ime) // edge to edge; only the keyboard pushes things up
                else -> Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
            })
    ) {
        if (find && !immersive && overlay == Overlay.NONE) FindBar(t.session) { find = false }
        if (standalone && !immersive && vm.webAppHost != null && !t.home && Urls.host(t.url) != vm.webAppHost) {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(Urls.host(t.url), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f), maxLines = 1)
                Text("✕", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.clickable { if (t.canBack) t.session.goBack() else activity.finish() }.padding(4.dp))
            }
        }
        val barTop = vm.prefs.barTop == 1
        val showBars = !immersive && !standalone && (overlay == Overlay.NONE || overlay == Overlay.TABS)
        val favorite: (() -> Unit)? = if (t.home) null else {
            { editBm = vm.bookmarks.items.firstOrNull { it.url == t.url } ?: Bookmark(System.currentTimeMillis(), t.title.ifBlank { Urls.host(t.url) }, t.url) }
        }
        // the new tab page has its own search box (like Edge), so the address bar steps aside there
        val bar: @Composable () -> Unit = { if (showBars && !(t.home && !editing && overlay == Overlay.NONE)) {
            AddressBar(vm, t, editing,
                onEditing = { editing = it; if (it) menu = false else query = "" },
                onQuery = { query = it },
                onGo = { vm.go(it); focus.clearFocus() },
                onFavorite = favorite, isFavorite = vm.bookmarks.items.any { it.url == t.url })
        } }
        if (barTop) bar()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            GeckoHost(vm, t.session)
            PullIndicator(vm)
            // Home fades in and out; screens slide up a little as they appear (Windows Phone motion).
            androidx.compose.animation.AnimatedVisibility(visible = t.home && !standalone, enter = fadeIn(wp(260)), exit = fadeOut(wp(140))) {
                HomeScreen(vm, onOpen = { vm.go(it) }, onEdit = { editBm = it },
                    onAdd = { g -> editBm = Bookmark(System.currentTimeMillis(), "", "", g) })
            }
            if (editing && query.isNotBlank()) Suggestions(vm, query) { vm.go(it); focus.clearFocus() }
            AnimatedContent(overlay, transitionSpec = {
                (fadeIn(wp(240)) + slideInVertically(wp(380)) { it / 12 }) togetherWith fadeOut(wp(140))
            }, label = "overlay") { o ->
                when (o) {
                    Overlay.FAVORITES -> LinksScreen("favorites", vm.bookmarks.items.map { it.title to it.url },
                        onOpen = { vm.go(it); overlay = Overlay.NONE }, onLong = { editBm = vm.bookmarks.items[it] })
                    Overlay.RECENT -> LinksScreen("recent", vm.history.items.asReversed().map { it.title to it.url },
                        onOpen = { vm.go(it); overlay = Overlay.NONE }, onClear = { vm.clearHistory() })
                    Overlay.SETTINGS -> SettingsScreen(vm, onAddons = { overlay = Overlay.ADDONS }) { overlay = Overlay.NONE }
                    Overlay.ADDONS -> AddonsScreen(vm) { overlay = Overlay.NONE }
                    Overlay.NOTES -> NotesScreen(vm, onOpen = { vm.go(it); overlay = Overlay.NONE })
                    Overlay.SAVED -> LinksScreen("saved pages", vm.saved.items.map { it.title to it.url },
                        onOpen = { u -> vm.saved.items.firstOrNull { it.url == u }?.let { vm.openSaved(it) }; overlay = Overlay.NONE },
                        onLong = { i -> vm.saved.items.getOrNull(i)?.let { vm.deleteSaved(it); vm.toast("removed") } })
                    Overlay.NONE, Overlay.TABS -> Box(Modifier.fillMaxSize())
                }
            }
        }
        if (!barTop) bar()
        if (showBars && !editing) NavBar(vm, t, onTabs = { vm.snapshot(); overlay = Overlay.TABS }, onMenu = { menu = true })
        if (standalone && !immersive && t.progress in 1..99) LinearProgressIndicator(
            progress = { t.progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp),
            color = MaterialTheme.colorScheme.primary, trackColor = Color.Transparent, drawStopIndicator = {},
        )
    }
    // open tabs slide up over the page, like Firefox for Android
    if (!standalone) TabsSheet(vm, overlay == Overlay.TABS && !immersive) { overlay = Overlay.NONE }
    if (!standalone) MenuSheet(items, menu && !immersive) { menu = false }
    if (!standalone) PageFxSheet(vm, t, pageFx && !immersive) { pageFx = false }
    }

    PromptDialog(vm)
    PermissionDialog(vm)
    ContextMenuDialog(vm)
    InstallAddonDialog()
    AppLinkDialog(vm)
    NoteDialog(vm)
    editBm?.let { b ->
        BookmarkDialog(b, isNew = vm.bookmarks.items.none { it.id == b.id },
            groups = (vm.bookmarks.items.map { it.group } + vm.extraGroups).filter { it.isNotBlank() }.distinct(),
            onSave = { vm.saveBookmark(it); editBm = null },
            onDelete = { vm.deleteBookmark(b); editBm = null },
            onDismiss = { editBm = null })
    }
}

/** Pull to refresh: a line grows across the top as you pull; let go at full width to reload. (Its own function, so pulling redraws only this.) */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.PullIndicator(vm: BrowserVM) {
    val p = vm.pull
    if (p < 0f) return
    Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(4.dp)
        .graphicsLayer { scaleX = p.coerceIn(0f, 1f); alpha = 0.5f + p.coerceIn(0f, 1f) / 2 }
        .background(MaterialTheme.colorScheme.primary))
    if (p >= 1f) Text("release to refresh", color = Color.White, fontSize = 13.sp,
        modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp).background(MaterialTheme.colorScheme.primary).padding(horizontal = 10.dp, vertical = 4.dp))
}

@Composable
private fun GeckoHost(vm: BrowserVM, session: GeckoSession) {
    AndroidView(
        // TextureView rendering: no black flashes or misplaced pages under Grove's menus and overlays.
        factory = { c ->
            // The plain page view, as in 1.2 (a custom page view in 1.3 drew pages black on some phones).
            GeckoView(c).apply {
                // Texture rendering: no flashes under Grove's menus, and surface rendering drew pages black on some phones.
                setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW)
                // Android autofill (password managers such as Grove Vault) can see and fill web forms.
                setAutofillEnabled(true)
                importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_YES
                val v = this
                vm.capture = { runCatching { v.capturePixels() }.getOrNull() } // screenshots for tab previews and home tiles
            }
        },
        update = { v ->
            // Only show a session Gecko has opened (an unopened one would make GeckoView open a second engine).
            if (v.session !== session && session.isOpen) { if (v.session != null) v.releaseSession(); v.setSession(session) }
        },
        onRelease = { it.releaseSession() },
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * Edge-style address bar: a rounded grey pill with the site's lock (or a search glass), the address, and refresh / favorite
 * at the end. Swipe it sideways to go to the next or previous tab.
 */
@Composable
private fun AddressBar(
    vm: BrowserVM, t: Tab, editing: Boolean,
    onEditing: (Boolean) -> Unit, onQuery: (String) -> Unit, onGo: (String) -> Unit,
    onFavorite: (() -> Unit)? = null, isFavorite: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val bar = barColor(t)
    val fg = if (bar.luminance() > 0.5f) Color(0xFF1B1B1B) else Color.White
    val pill = if (t.private) Color(0xFF3A3A3A) else cs.surfaceVariant
    val progress by animateFloatAsState(t.progress / 100f, wp(260), label = "progress")
    val loading = t.progress in 1..99
    Column(Modifier.fillMaxWidth().background(bar)) {
        Row(Modifier.fillMaxWidth().height(56.dp)
            .pointerInput(vm.tabs.size, vm.cur) {
                var drag = 0f
                detectHorizontalDragGestures(onDragStart = { drag = 0f }, onDragEnd = {
                    val i = vm.tabs.indexOf(vm.cur)
                    val next = if (drag < -120f) vm.tabs.getOrNull(i + 1) else if (drag > 120f) vm.tabs.getOrNull(i - 1) else null
                    if (next != null) { vm.snapshot(); vm.select(next) }
                }) { _, d -> drag += d }
            }
            .padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(20.dp)).background(pill).padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                val lead = when {
                    editing || t.home -> androidx.compose.material.icons.Icons.Default.Search
                    t.url.startsWith("https:") -> androidx.compose.material.icons.Icons.Default.Lock
                    else -> androidx.compose.material.icons.Icons.Default.Info
                }
                androidx.compose.material3.Icon(lead, null, tint = fg.copy(alpha = 0.75f), modifier = Modifier.size(18.dp))
                if (t.private && !editing) Text("InPrivate", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF0078D4)).padding(horizontal = 6.dp, vertical = 2.dp))
                AddressBox(t, editing, onEditing, onQuery, onGo, fg, Modifier.weight(1f).padding(start = 8.dp))
                if (!editing && !t.home) {
                    PillBtn(if (onFavorite != null && isFavorite) androidx.compose.material.icons.Icons.Default.Star else androidx.compose.material.icons.Icons.Default.Star,
                        "favorite", if (isFavorite) cs.primary else fg.copy(alpha = 0.45f)) { onFavorite?.invoke() }
                    PillBtn(if (loading) androidx.compose.material.icons.Icons.Default.Close else androidx.compose.material.icons.Icons.Default.Refresh,
                        if (loading) "stop" else "refresh", fg.copy(alpha = 0.8f)) { if (loading) t.session.stop() else t.session.reload() }
                }
            }
        }
        if (loading) LinearProgressIndicator(
            progress = { progress }, modifier = Modifier.fillMaxWidth().height(2.dp),
            color = cs.primary, trackColor = Color.Transparent, drawStopIndicator = {},
        ) else Spacer(Modifier.height(2.dp))
    }
}

@Composable
private fun PillBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color, onClick: () -> Unit) =
    Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        androidx.compose.material3.Icon(icon, label, tint = tint, modifier = Modifier.size(20.dp))
    }

/** Edge's bottom bar: back, forward, new tab, tabs, menu — five equal slots, icons centred. */
@Composable
private fun NavBar(vm: BrowserVM, t: Tab, onTabs: () -> Unit, onMenu: () -> Unit) {
    val bar = barColor(t)
    val fg = if (bar.luminance() > 0.5f) Color(0xFF1B1B1B) else Color.White
    Row(Modifier.fillMaxWidth().height(52.dp).background(bar), verticalAlignment = Alignment.CenterVertically) {
        NavBtn("back", t.canBack, { t.session.goBack() }) {
            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.ArrowBack, null, tint = fg, modifier = Modifier.size(22.dp))
        }
        NavBtn("forward", t.canFwd, { t.session.goForward() }) {
            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.ArrowForward, null, tint = fg, modifier = Modifier.size(22.dp))
        }
        NavBtn("new tab", true, { vm.newTab(private = t.private) }) {
            Box(Modifier.size(34.dp).border(1.5.dp, fg, CircleShape), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.Add, null, tint = fg, modifier = Modifier.size(22.dp))
            }
        }
        NavBtn("tabs", true, onTabs) {
            Box(Modifier.size(24.dp).border(1.5.dp, fg, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                Text(vm.tabs.count { it.private == t.private }.let { if (it > 99) ":)" else it.toString() }, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        NavBtn("menu", true, onMenu) {
            androidx.compose.material3.Icon(EI.More, null, tint = fg, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun RowScope.NavBtn(label: String, enabled: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) =
    Box(Modifier.weight(1f).fillMaxHeight().alpha(if (enabled) 1f else 0.35f).clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center) { content() }

/** Edge's menu: a sheet that slides up with a grid of icons. */
@Composable
private fun MenuSheet(items: List<MenuItem>, visible: Boolean, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose))
        }
        AnimatedVisibility(visible, modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(androidx.compose.animation.core.spring(dampingRatio = 0.86f, stiffness = 500f)) { it },
            exit = slideOutVertically(androidx.compose.animation.core.tween(200)) { it }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(cs.surface).navigationBarsPadding().padding(bottom = 8.dp)) {
                Box(Modifier.padding(top = 8.dp, bottom = 4.dp).align(Alignment.CenterHorizontally).width(36.dp).height(4.dp)
                    .clip(RoundedCornerShape(2.dp)).background(cs.onSurface.copy(alpha = 0.25f)))
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    items.chunked(4).forEach { row ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                            row.forEach { item ->
                                Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable { onClose(); item.action() }.padding(vertical = 12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally) {
                                    androidx.compose.material3.Icon(item.icon, null, tint = cs.onSurface, modifier = Modifier.size(24.dp))
                                    Text(item.label, color = cs.onSurface, fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp, start = 2.dp, end = 2.dp))
                                }
                            }
                            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

/** Bar colour: Edge's light (or dark) grey; dark grey for InPrivate. */
@Composable
fun barColor(t: Tab): Color = if (t.private) Color(0xFF242424) else MaterialTheme.colorScheme.surface

@Composable
private fun AddressBox(
    t: Tab, editing: Boolean, onEditing: (Boolean) -> Unit, onQuery: (String) -> Unit,
    onGo: (String) -> Unit, fg: Color, modifier: Modifier,
) {
    var tf by remember { mutableStateOf(TextFieldValue("")) }
    val shown = if (editing) tf else TextFieldValue(if (t.home) "" else Urls.label(t.url))
    BasicTextField(
        value = shown,
        onValueChange = { if (editing) { tf = it; onQuery(it.text) } },
        modifier = modifier.fillMaxHeight().onFocusChanged { f ->
            if (f.isFocused && !editing) {
                val s = if (t.home) "" else t.url
                tf = TextFieldValue(s, TextRange(0, s.length))
                onEditing(true)
            } else if (!f.isFocused && editing) onEditing(false)
        },
        singleLine = true,
        textStyle = TextStyle(color = fg, fontSize = 16.sp),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onGo = { onGo(tf.text) }),
        decorationBox = { inner ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                if (shown.text.isEmpty()) Text("Search or enter web address", color = fg.copy(alpha = 0.55f), fontSize = 16.sp, maxLines = 1)
                inner()
            }
        },
    )
}

/** Local-only suggestions from favorites and recent pages: nothing typed is sent anywhere. */
@Composable
private fun Suggestions(vm: BrowserVM, q: String, onPick: (String) -> Unit) {
    val list = remember(q) {
        (vm.bookmarks.items.map { it.title to it.url } + vm.history.items.asReversed().map { it.title to it.url })
            .filter { (title, url) -> title.contains(q, true) || url.contains(q, true) }
            .distinctBy { it.second }.take(8)
    }
    if (list.isEmpty()) return
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        LazyColumn(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            items(list) { (title, url) ->
                Column(Modifier.fillMaxWidth().clickable { onPick(url) }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(title.ifBlank { Urls.host(url) }, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(url, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun FindBar(session: GeckoSession, onClose: () -> Unit) {
    var q by remember { mutableStateOf("") }
    var res by remember { mutableStateOf("") }
    fun run(text: String?, back: Boolean = false) {
        session.finder.find(text, if (back) GeckoSession.FINDER_FIND_BACKWARDS else 0)
            .accept { r -> res = if (r == null || r.total == 0) "0/0" else "${r.current}/${r.total}" }
    }
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().background(cs.surfaceVariant).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(q, { q = it; if (it.isEmpty()) { session.finder.clear(); res = "" } else run(it) },
            singleLine = true, textStyle = TextStyle(color = Color.Black, fontSize = 16.sp),
            modifier = Modifier.weight(1f).height(36.dp).background(Color.White).padding(horizontal = 8.dp, vertical = 8.dp))
        Text(res, color = cs.onSurface, modifier = Modifier.padding(horizontal = 8.dp))
        Text("▲", color = cs.onSurface, modifier = Modifier.clickable { if (q.isNotEmpty()) run(null, true) }.padding(8.dp))
        Text("▼", color = cs.onSurface, modifier = Modifier.clickable { if (q.isNotEmpty()) run(null) }.padding(8.dp))
        Text("✕", color = cs.onSurface, modifier = Modifier.clickable { session.finder.clear(); onClose() }.padding(8.dp))
    }
}

fun share(ctx: Context, url: String) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
    ctx.startActivity(Intent.createChooser(i, null))
}

fun copy(ctx: Context, text: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("link", text))
}

private fun pin(ctx: Context, t: Tab) {
    if (!ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)) return
    val info = ShortcutInfoCompat.Builder(ctx, "pin_" + t.url.hashCode())
        .setShortLabel(t.title.ifBlank { Urls.host(t.url) }.take(24).ifBlank { "Grove" })
        .setIcon(IconCompat.createWithResource(ctx, R.mipmap.ic_launcher))
        .setIntent(Intent(Intent.ACTION_VIEW, Uri.parse(t.url), ctx, MainActivity::class.java))
        .build()
    ShortcutManagerCompat.requestPinShortcut(ctx, info, null)
}
