package app.grove.browser.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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

enum class Overlay { NONE, TABS, FAVORITES, RECENT, SETTINGS }

class MenuItem(val label: String, val action: () -> Unit)

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

    BackHandler {
        when {
            vm.fullscreen -> t.session.exitFullScreen()
            editing -> focus.clearFocus()
            menu -> menu = false
            find -> { find = false; t.session.finder.clear() }
            overlay != Overlay.NONE -> overlay = Overlay.NONE
            t.canBack -> t.session.goBack()
            t.parent != null || (t.home && vm.tabs.size > 1) -> vm.closeTab(t)
            else -> activity.moveTaskToBack(true)
        }
    }

    LaunchedEffect(vm.fullscreen) {
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            if (vm.fullscreen) {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            } else show(WindowInsetsCompat.Type.systemBars())
        }
    }

    val bg = MaterialTheme.colorScheme.background
    Column(
        Modifier.fillMaxSize().background(bg)
            .then(if (vm.fullscreen) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing))
    ) {
        if (find && !vm.fullscreen && overlay == Overlay.NONE) FindBar(t.session) { find = false }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            GeckoHost(t.session)
            if (t.home) HomeScreen(vm, onOpen = { vm.go(it) }, onEdit = { editBm = it },
                onAdd = { editBm = Bookmark(System.currentTimeMillis(), "", "") })
            if (editing && query.isNotBlank()) Suggestions(vm, query) { vm.go(it); focus.clearFocus() }
            when (overlay) {
                Overlay.TABS -> TabsScreen(vm) { overlay = Overlay.NONE }
                Overlay.FAVORITES -> LinksScreen("favorites", vm.bookmarks.items.map { it.title to it.url },
                    onOpen = { vm.go(it); overlay = Overlay.NONE }, onLong = { editBm = vm.bookmarks.items[it] })
                Overlay.RECENT -> LinksScreen("recent", vm.history.items.asReversed().map { it.title to it.url },
                    onOpen = { vm.go(it); overlay = Overlay.NONE }, onClear = { vm.clearHistory() })
                Overlay.SETTINGS -> SettingsScreen(vm) { overlay = Overlay.NONE }
                Overlay.NONE -> {}
            }
        }
        if (!vm.fullscreen && overlay == Overlay.NONE) {
            val items = buildList {
                add(MenuItem("tabs") { overlay = Overlay.TABS })
                add(MenuItem("new inprivate tab") { vm.newTab(private = true) })
                add(MenuItem("recent") { overlay = Overlay.RECENT })
                add(MenuItem("favorites") { overlay = Overlay.FAVORITES })
                if (!t.home) {
                    val existing = vm.bookmarks.items.firstOrNull { it.url == t.url }
                    add(MenuItem(if (existing != null) "edit favorite" else "add to favorites") {
                        editBm = existing ?: Bookmark(System.currentTimeMillis(), t.title.ifBlank { Urls.host(t.url) }, t.url)
                    })
                    add(MenuItem("share page") { share(ctx, t.url) })
                    add(MenuItem("find on page") { find = true })
                    add(MenuItem(if (t.desktop) "mobile version" else "desktop version") { vm.toggleDesktop(t) })
                    if (Urls.isWeb(t.url)) add(MenuItem("pin to start") { pin(ctx, t) })
                }
                add(MenuItem("ublock origin") { vm.openUblock() })
                add(MenuItem("settings") { overlay = Overlay.SETTINGS })
            }
            AppBar(vm, t, editing, menu, items,
                onEditing = { editing = it; if (it) menu = false else query = "" },
                onQuery = { query = it },
                onGo = { vm.go(it); focus.clearFocus() },
                onMenu = { menu = it },
                onTabs = { overlay = Overlay.TABS })
        }
    }

    PromptDialog(vm)
    ContextMenuDialog(vm)
    editBm?.let { b ->
        BookmarkDialog(b, isNew = vm.bookmarks.items.none { it.id == b.id },
            onSave = { vm.saveBookmark(it); editBm = null },
            onDelete = { vm.deleteBookmark(b); editBm = null },
            onDismiss = { editBm = null })
    }
}

@Composable
private fun GeckoHost(session: GeckoSession) {
    AndroidView(
        factory = { GeckoView(it) },
        update = { v -> if (v.session !== session) { if (v.session != null) v.releaseSession(); v.setSession(session) } },
        onRelease = { it.releaseSession() },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun AppBar(
    vm: BrowserVM, t: Tab, editing: Boolean, menu: Boolean, items: List<MenuItem>,
    onEditing: (Boolean) -> Unit, onQuery: (String) -> Unit, onGo: (String) -> Unit,
    onMenu: (Boolean) -> Unit, onTabs: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val bar = if (t.private) Color(0xFF2B1A45) else cs.surfaceVariant
    val fg = if (t.private) Color.White else cs.onSurface
    Column(Modifier.fillMaxWidth().background(bar)) {
        if (t.progress in 1..99) LinearProgressIndicator(
            progress = { t.progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp),
            color = cs.primary, trackColor = Color.Transparent, drawStopIndicator = {},
        ) else Spacer(Modifier.height(2.dp))
        Row(Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            AddressBox(t, editing, onEditing, onQuery, onGo, Modifier.weight(1f))
            if (!editing) {
                Spacer(Modifier.width(10.dp))
                Box(Modifier.size(34.dp).border(2.dp, fg).clickable(onClick = onTabs), contentAlignment = Alignment.Center) {
                    Text(vm.tabs.size.toString(), color = fg, fontSize = 15.sp)
                }
                Text("•••", color = fg, fontSize = 18.sp,
                    modifier = Modifier.clickable { onMenu(!menu) }.padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp))
            }
        }
        AnimatedVisibility(menu && !editing) {
            Column(Modifier.fillMaxWidth().heightIn(max = 470.dp).verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
                val loading = t.progress in 1..99
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RoundBtn("←", "back", fg, t.canBack) { t.session.goBack(); onMenu(false) }
                    RoundBtn("→", "forward", fg, t.canFwd) { t.session.goForward(); onMenu(false) }
                    RoundBtn(if (loading) "✕" else "↻", if (loading) "stop" else "refresh", fg, !t.home) {
                        if (loading) t.session.stop() else t.session.reload(); onMenu(false)
                    }
                    RoundBtn("+", "new tab", fg, true) { vm.newTab(); onMenu(false) }
                }
                items.forEach { item ->
                    Text(item.label, color = fg, fontSize = 20.sp, fontWeight = FontWeight.Light,
                        modifier = Modifier.fillMaxWidth().clickable { onMenu(false); item.action() }
                            .padding(horizontal = 20.dp, vertical = 9.dp))
                }
            }
        }
    }
}

@Composable
private fun RoundBtn(glyph: String, label: String, fg: Color, enabled: Boolean, onClick: () -> Unit) {
    Column(Modifier.alpha(if (enabled) 1f else 0.35f).clickable(enabled = enabled, onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(42.dp).border(2.dp, fg, CircleShape), contentAlignment = Alignment.Center) {
            Text(glyph, color = fg, fontSize = 18.sp)
        }
        Text(label, color = fg, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun AddressBox(
    t: Tab, editing: Boolean, onEditing: (Boolean) -> Unit, onQuery: (String) -> Unit,
    onGo: (String) -> Unit, modifier: Modifier,
) {
    var tf by remember { mutableStateOf(TextFieldValue("")) }
    val shown = if (editing) tf else TextFieldValue(if (t.home) "" else Urls.host(t.url))
    BasicTextField(
        value = shown,
        onValueChange = { if (editing) { tf = it; onQuery(it.text) } },
        modifier = modifier.height(38.dp).onFocusChanged { f ->
            if (f.isFocused && !editing) {
                val s = if (t.home) "" else t.url
                tf = TextFieldValue(s, TextRange(0, s.length))
                onEditing(true)
            } else if (!f.isFocused && editing) onEditing(false)
        },
        singleLine = true,
        textStyle = TextStyle(color = Color.Black, fontSize = 16.sp),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onGo = { onGo(tf.text) }),
        decorationBox = { inner ->
            Box(Modifier.fillMaxSize().background(Color.White).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
                if (shown.text.isEmpty()) Text("search or enter address", color = Color.Gray, fontSize = 16.sp)
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
