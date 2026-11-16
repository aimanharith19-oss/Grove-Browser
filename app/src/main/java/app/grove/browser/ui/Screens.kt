package app.grove.browser.ui

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
    Text(title, fontSize = 52.sp, fontWeight = FontWeight.Light, maxLines = 1,
        color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp))
}

/** Home = every favorite as a tile (speed-dial style). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(vm: BrowserVM, onOpen: (String) -> Unit, onEdit: (Bookmark) -> Unit, onAdd: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize().tintBackground(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Text("grove", fontSize = 56.sp, fontWeight = FontWeight.Light, color = cs.onBackground)
                Text("favorites", fontSize = 26.sp, fontWeight = FontWeight.Light, color = cs.primary)
                Spacer(Modifier.height(4.dp))
            }
        }
        items(vm.bookmarks.items, key = { it.id }) { b ->
            // Tile = screenshot of the website, with its icon and name on a strip at the bottom.
            val src = remember { MutableInteractionSource() }
            val icon = Icons.get(ctx, b.url)
            val shot = Thumbs.get(ctx, b.url)
            Box(Modifier.pressTile(src).fillMaxWidth().aspectRatio(1.2f).background(cs.primary)
                .combinedClickable(interactionSource = src, indication = LocalIndication.current, onClick = { onOpen(b.url) }, onLongClick = { onEdit(b) })) {
                if (shot != null) Image(shot, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
                else if (icon != null) Box(Modifier.padding(bottom = 30.dp).size(56.dp).background(Color.White).align(Alignment.Center), contentAlignment = Alignment.Center) {
                    Image(icon, null, Modifier.size(40.dp))
                } else Text(b.title.trim().take(1).uppercase().ifBlank { "•" }, color = Color.White, fontSize = 44.sp,
                    fontWeight = FontWeight.Light, modifier = Modifier.align(Alignment.Center).padding(bottom = 30.dp))
                Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(androidx.compose.ui.graphics.lerp(Color.Black, cs.primary, 0.35f).copy(alpha = 0.86f))
                    .padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (icon != null) Box(Modifier.size(20.dp).background(Color.White), contentAlignment = Alignment.Center) { Image(icon, null, Modifier.size(16.dp)) }
                    else Box(Modifier.size(20.dp).background(cs.primary), contentAlignment = Alignment.Center) {
                        Text(b.title.trim().take(1).uppercase().ifBlank { "•" }, color = Color.White, fontSize = 12.sp)
                    }
                    Text(b.title.ifBlank { Urls.host(b.url) }, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        item {
            Box(Modifier.fillMaxWidth().aspectRatio(1.2f).border(2.dp, cs.onSurfaceVariant).clickable(onClick = onAdd), contentAlignment = Alignment.Center) {
                Text("+", fontSize = 40.sp, color = cs.onSurfaceVariant)
            }
        }
        if (vm.bookmarks.items.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Text("tap + or ••• > add to favorites. long-press a tile to edit.", color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
fun TabsScreen(vm: BrowserVM, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().tintBackground()) {
        Header("tabs")
        LazyVerticalGrid(
            columns = GridCells.Fixed(2), modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(vm.tabs.toList()) { tab ->
                val active = tab === vm.cur
                val src = remember(tab) { MutableInteractionSource() }
                val shot = tab.thumb
                // Swipe a tab left or right to close it.
                var dx by remember(tab) { mutableFloatStateOf(0f) }
                // Each open tab is drawn as a small window: a title bar (site icon, title, ✕) over a picture of the page.
                // The tab you're on has an accent title bar and frame; a new tab shows "new tab" instead of a picture.
                val ctx = LocalContext.current
                val bar = if (active) cs.primary else if (tab.private) Color(0xFF4A2C6E) else cs.surfaceContainerHighest
                val barFg = if (active || tab.private) Color.White else cs.onSurface
                Column(Modifier.pressTile(src).fillMaxWidth().aspectRatio(0.8f)
                    .graphicsLayer { translationX = dx; alpha = 1f - (abs(dx) / size.width.coerceAtLeast(1f)).coerceIn(0f, 0.85f) }
                    .pointerInput(tab) {
                        detectHorizontalDragGestures(
                            onDragEnd = { if (abs(dx) > size.width * 0.38f) vm.closeTab(tab); dx = 0f },
                            onDragCancel = { dx = 0f },
                        ) { change, d -> change.consume(); dx += d }
                    }
                    .border(if (active) 3.dp else 1.dp, if (active) cs.primary else cs.onSurface.copy(alpha = 0.3f))
                    .clickable(interactionSource = src, indication = LocalIndication.current) { vm.select(tab); onClose() }) {
                    // title bar
                    Row(Modifier.fillMaxWidth().height(34.dp).background(bar).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        val icon = if (tab.home) null else Icons.get(ctx, tab.url)
                        if (icon != null) Box(Modifier.size(18.dp).background(Color.White), contentAlignment = Alignment.Center) { Image(icon, null, Modifier.size(14.dp)) }
                        else Text(if (tab.private) "◐" else if (tab.home) "+" else "•", color = barFg, fontSize = 14.sp)
                        Text(if (tab.home) "new tab" else tab.title.ifBlank { Urls.host(tab.url) }, color = barFg, fontSize = 13.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 6.dp))
                        Text("✕", color = barFg, fontSize = 16.sp, modifier = Modifier.clickable { vm.closeTab(tab) }.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                    // the page
                    Box(Modifier.fillMaxWidth().weight(1f).background(cs.background)) {
                        when {
                            shot != null && !tab.home -> Image(shot, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
                            tab.home -> Box(Modifier.fillMaxSize().tintBackground(), contentAlignment = Alignment.Center) {
                                Text("new tab", color = cs.onBackground, fontSize = 18.sp, fontWeight = FontWeight.Light)
                            }
                            else -> Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(Urls.host(tab.url), color = cs.onSurfaceVariant, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (active) Text("now", color = Color.White, fontSize = 11.sp,
                            modifier = Modifier.align(Alignment.BottomEnd).background(cs.primary).padding(horizontal = 6.dp, vertical = 2.dp))
                        if (tab.private) Text("inprivate", color = Color.White, fontSize = 11.sp,
                            modifier = Modifier.align(Alignment.BottomStart).background(Color(0xFF4A2C6E)).padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(cs.surfaceVariant).padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Text("new tab", fontSize = 18.sp, color = cs.onSurface, modifier = Modifier.clickable { vm.newTab(); onClose() }.padding(10.dp))
            Text("new inprivate", fontSize = 18.sp, color = cs.onSurface, modifier = Modifier.clickable { vm.newTab(private = true); onClose() }.padding(10.dp))
            if (vm.closed.isNotEmpty()) Text("reopen closed", fontSize = 18.sp, color = cs.onSurface, modifier = Modifier.clickable { vm.reopenClosed(); onClose() }.padding(10.dp))
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

    @Composable fun section(s: String) =
        Text(s, color = cs.primary, fontSize = 22.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 4.dp))
    @Composable fun option(s: String, on: Boolean, click: () -> Unit) =
        Text(s, fontSize = 19.sp, color = if (on) cs.primary else cs.onBackground,
            fontWeight = if (on) FontWeight.Normal else FontWeight.Light,
            modifier = Modifier.fillMaxWidth().clickable(onClick = click).padding(horizontal = 16.dp, vertical = 8.dp))
    @Composable fun info(s: String) =
        Text(s, fontSize = 14.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))

    // Settings as a short list of categories; each opens its own page (back returns to the list).
    var page by remember { mutableStateOf<String?>(null) }
    androidx.activity.compose.BackHandler(enabled = page != null) { page = null }
    val pages = listOf(
        "general" to "search engine, address bar, links in apps, default browser",
        "appearance" to "theme, accent colour, full screen, text size, zoom",
        "privacy & security" to "tracker blocker, https, secure dns, site data",
        "media & downloads" to "background play, picture in picture, mini player, downloads",
        "favorites" to "back up, restore, automatic backup",
        "extensions" to "groveguard, ublock origin, firefox add-ons",
        "troubleshooting & about" to "reset web engine, version",
    )
    Column(Modifier.fillMaxSize().tintBackground().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Header(page ?: "settings")
        when (page) {
            null -> pages.forEach { (name, sub) ->
                Column(Modifier.fillMaxWidth().clickable { if (name == "extensions") onAddons() else page = name }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(name, fontSize = 24.sp, fontWeight = FontWeight.Light, color = cs.onBackground)
                    Text(sub, fontSize = 13.sp, color = cs.onSurfaceVariant)
                }
            }
            "general" -> {
                section("search engine")
                ENGINES.forEachIndexed { i, (name, _) -> option(name, p.engine == i) { p.engine = i } }
                section("address bar")
                listOf("at the bottom", "at the top").forEachIndexed { i, s -> option(s, p.barTop == i) { p.barTop = i } }
                info("swipe left or right on the address bar to switch tabs")
                section("open links in apps")
                listOf("ask", "always", "never").forEachIndexed { i, s -> option(s, p.appLinks == i) { p.appLinks = i } }
                info("e.g. youtube.com links can open the youtube app")
                section("default browser")
                option("make grove your default browser", false) {
                    runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
                }
            }
            "appearance" -> {
                section("theme")
                listOf("system", "dark", "light").forEachIndexed { i, s -> option(s, p.theme == i) { p.theme = i } }
                section("accent colour")
                ACCENTS.indices.chunked(5).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { i ->
                            Box(Modifier.weight(1f).aspectRatio(1f).background(Color(ACCENTS[i]))
                                .then(if (p.accent == i) Modifier.border(3.dp, cs.onBackground) else Modifier)
                                .clickable { p.accent = i })
                        }
                    }
                }
                section("screen")
                option("full screen: " + if (p.immersive == 1) "on" else "off", false) { p.immersive = 1 - p.immersive }
                info("hides android's status bar and navigation bar so pages use the whole screen. swipe from the top or bottom edge to see them")
                section("text size on websites")
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(80, 100, 120, 150, 200).forEach { v ->
                        Box(Modifier.weight(1f).height(44.dp).background(if (p.textSize == v) cs.primary else cs.surfaceVariant)
                            .clickable { p.textSize = v; vm.applyPrefs() }, contentAlignment = Alignment.Center) {
                            Text("$v%", color = if (p.textSize == v) Color.White else cs.onSurface, fontSize = 15.sp)
                        }
                    }
                }
                option("zoom on every website: " + if (p.forceZoom == 1) "on" else "off", false) { p.forceZoom = 1 - p.forceZoom; vm.applyPrefs() }
                info("lets you pinch to zoom even on sites that try to stop it")
            }
            "privacy & security" -> {
                section("website tracker blocker")
                listOf("strict: trackers, fingerprinters, crypto-miners, social and cross-site cookies", "standard", "off").forEachIndexed { i, s ->
                    option(s, p.etp == i) { p.etp = i; Gecko.setEtp(i) }
                }
                info("the ••• menu (more…) shows how many trackers were blocked on the page · cookies are isolated per site")
                section("secure connections (https)")
                listOf("https first (recommended)", "https only: warn before any http page", "allow http").forEachIndexed { i, s ->
                    option(s, p.https == i) { p.https = i; Gecko.setHttps(i) }
                }
                info("switching between \"first\" and \"allow http\" applies after grove restarts")
                section("secure dns (dns over https)")
                option("off: use your network's dns", p.doh == 0) { p.doh = 0; vm.applyPrefs() }
                DOH.forEachIndexed { i, (name, _) -> option(name, p.doh == i + 1) { p.doh = i + 1; vm.applyPrefs() } }
                info("hides which sites you look up from your network provider. falls back to normal dns if the provider can't be reached")
                section("site permissions")
                info("location, camera, microphone and notifications: each site asks first, and grove remembers your answer")
                section("site data")
                option("delete history and site data when you quit: " + if (p.clearOnQuit == 1) "on" else "off", false) { p.clearOnQuit = 1 - p.clearOnQuit }
                info("adds \"quit\" to the ••• menu; quitting closes every tab and deletes cookies, cache and recent pages")
                option("delete history and site data now", false) { confirmClear = true }
                info("ads, trackers, scam, phishing, 18+ and crypto-mining sites are blocked by groveguard and ublock origin · no telemetry, no studies, no ai, no vpn, no sponsored content")
            }
            "media & downloads" -> {
                section("background play")
                option("background play: " + if (p.bgPlay == 1) "on" else "off", false) { vm.setBgPlay(p.bgPlay == 0) }
                info("keeps any audio or video playing when you leave grove or turn off the screen")
                section("picture in picture")
                listOf("any playing video, when you go home", "only full-screen video", "off (use ••• > pop out)").forEachIndexed { i, s ->
                    val mode = if (i == 2) 0 else i + 1
                    option(s, p.pip == mode) { p.pip = mode }
                }
                section("mini player")
                option("mini player button on videos: " + if (p.miniBtn == 1) "on" else "off", false) { p.miniBtn = 1 - p.miniBtn; vm.toast("applies to pages you open from now") }
                info("tap the small button on a video and it floats in a corner while you keep reading; drag it anywhere, ✕ puts it back")
                section("downloads")
                listOf("grove downloads files", "use another app (a download manager)").forEachIndexed { i, s -> option(s, p.downloader == i) { p.downloader = i } }
            }
            "favorites" -> {
                section("backup")
                option("back up favorites", false) { backup.launch("grove-favorites.json") }
                option("restore favorites", false) { restore.launch(arrayOf("*/*")) }
                info("restore also reads bookmark files exported from firefox, chrome, vivaldi and others (.html or .json)")
                section("automatic backup")
                val folder = vm.backupFolder
                option("automatic backup: " + (folder?.let { "on, in \"$it\"" } ?: "off"), false) { if (folder == null) autoDir.launch(null) else vm.setBackupFolder(null) }
                info("pick a folder once; grove then keeps grove-favorites.json there up to date every time your favorites change")
            }
            "troubleshooting & about" -> {
                section("troubleshooting")
                option("reset web engine", false) {
                    p.sp.edit().putBoolean("resetEngine", true).commit()
                    val ctx2 = ctx.applicationContext
                    val i = ctx2.packageManager.getLaunchIntentForPackage(ctx2.packageName)!!.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    ctx2.startActivity(i)
                    Runtime.getRuntime().exit(0)
                }
                info("if websites show black or won't load: starts the web engine fresh. you'll be signed out of websites; favorites, history, saved pages and settings stay")
                section("about")
                info("grove browser $version · gecko engine")
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
                    val builtIn = e.id == Gecko.GUARD_ID
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
