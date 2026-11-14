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
import app.grove.browser.Urls

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
    LazyVerticalGrid(
        columns = GridCells.Adaptive(100.dp),
        modifier = Modifier.fillMaxSize().background(cs.background),
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
            Column(Modifier.combinedClickable(onClick = { onOpen(b.url) }, onLongClick = { onEdit(b) })) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f).background(cs.primary).padding(8.dp)) {
                    Text(b.title.trim().take(1).uppercase().ifBlank { "•" }, color = Color.White, fontSize = 40.sp,
                        fontWeight = FontWeight.Light, modifier = Modifier.align(Alignment.Center))
                    Text(Urls.host(b.url), color = Color.White, fontSize = 10.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart))
                }
                Text(b.title, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = cs.onBackground, modifier = Modifier.padding(top = 4.dp))
            }
        }
        item {
            Column(Modifier.clickable(onClick = onAdd)) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f).border(2.dp, cs.onSurfaceVariant), contentAlignment = Alignment.Center) {
                    Text("+", fontSize = 40.sp, color = cs.onSurfaceVariant)
                }
                Text("add", fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
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
    Column(Modifier.fillMaxSize().background(cs.background)) {
        Header("tabs")
        LazyVerticalGrid(
            columns = GridCells.Fixed(2), modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(vm.tabs.toList()) { tab ->
                val active = tab === vm.cur
                Box(Modifier.fillMaxWidth().aspectRatio(0.85f)
                    .background(if (active) cs.primary else cs.surfaceVariant)
                    .clickable { vm.select(tab); onClose() }.padding(10.dp)) {
                    val fg = if (active) Color.White else cs.onSurface
                    Column(Modifier.padding(end = 20.dp)) {
                        if (tab.private) Text("inprivate", fontSize = 11.sp, color = fg)
                        Text(tab.title.ifBlank { if (tab.home) "new tab" else Urls.host(tab.url) }, color = fg,
                            fontSize = 17.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                    Text(if (tab.home) "" else Urls.host(tab.url), color = fg, fontSize = 11.sp, maxLines = 1,
                        modifier = Modifier.align(Alignment.BottomStart))
                    Text("✕", color = fg, fontSize = 18.sp,
                        modifier = Modifier.align(Alignment.TopEnd).clickable { vm.closeTab(tab) }.padding(4.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(cs.surfaceVariant).padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Text("new tab", fontSize = 18.sp, color = cs.onSurface, modifier = Modifier.clickable { vm.newTab(); onClose() }.padding(10.dp))
            Text("new inprivate", fontSize = 18.sp, color = cs.onSurface, modifier = Modifier.clickable { vm.newTab(private = true); onClose() }.padding(10.dp))
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
    Column(Modifier.fillMaxSize().background(cs.background)) {
        Header(title)
        if (links.isEmpty()) Text("nothing here yet", color = cs.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(links) { i, (name, url) ->
                Column(Modifier.fillMaxWidth().combinedClickable(onClick = { onOpen(url) }, onLongClick = { onLong?.invoke(i) })
                    .padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(name.ifBlank { Urls.host(url) }, fontSize = 20.sp, fontWeight = FontWeight.Light,
                        color = cs.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(url, fontSize = 12.sp, color = cs.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }

    @Composable fun section(s: String) =
        Text(s, color = cs.primary, fontSize = 22.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 4.dp))
    @Composable fun option(s: String, on: Boolean, click: () -> Unit) =
        Text(s, fontSize = 19.sp, color = if (on) cs.primary else cs.onBackground,
            fontWeight = if (on) FontWeight.Normal else FontWeight.Light,
            modifier = Modifier.fillMaxWidth().clickable(onClick = click).padding(horizontal = 16.dp, vertical = 8.dp))
    @Composable fun info(s: String) =
        Text(s, fontSize = 14.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))

    Column(Modifier.fillMaxSize().background(cs.background).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Header("settings")
        section("search engine")
        ENGINES.forEachIndexed { i, (name, _) -> option(name, p.engine == i) { p.engine = i } }
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
        section("secure connections (https)")
        listOf("https first (recommended)", "https only: warn before any http page", "allow http").forEachIndexed { i, s ->
            option(s, p.https == i) { p.https = i; Gecko.setHttps(i) }
        }
        info("switching between \"first\" and \"allow http\" applies after grove restarts")
        section("open links in apps")
        listOf("ask", "always", "never").forEachIndexed { i, s -> option(s, p.appLinks == i) { p.appLinks = i } }
        info("e.g. youtube.com links can open the youtube app")
        section("media")
        option("background play: " + if (p.bgPlay == 1) "on" else "off", false) { vm.setBgPlay(p.bgPlay == 0) }
        info("keeps any audio or video playing when you leave grove or turn off the screen")
        option("picture in picture: " + if (p.pip == 1) "on" else "off", false) { p.pip = 1 - p.pip }
        info("any playing video pops out into a small window when you go home")
        section("favorites")
        option("back up favorites", false) { backup.launch("grove-favorites.json") }
        option("restore favorites", false) { restore.launch(arrayOf("*/*")) }
        info("restore also reads bookmark files exported from firefox, chrome, vivaldi and others (.html or .json)")
        section("privacy")
        info("tracking protection: strict · cookies isolated per site")
        info("ads, trackers, scam, phishing and crypto-mining sites blocked by ublock origin")
        info("no telemetry, no studies or remote experiments, no ai, no vpn, no sponsored content")
        option("extensions (ublock origin, firefox add-ons)", false) { onAddons() }
        option("delete history and site data", false) { confirmClear = true }
        section("about")
        info("grove browser $version · gecko engine")
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
    Column(Modifier.fillMaxSize().background(cs.background)) {
        Header("extensions")
        if (Gecko.addons.isEmpty()) Text("no extensions yet", color = cs.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        LazyColumn(Modifier.weight(1f)) {
            listItems(Gecko.addons.toList(), key = { it.id }) { e ->
                val m = e.metaData
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text((m.name ?: e.id).lowercase(), fontSize = 22.sp, fontWeight = FontWeight.Light, color = cs.onBackground)
                    Text("version ${m.version} · " + if (m.enabled) "on" else "off", fontSize = 12.sp, color = cs.primary)
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(top = 4.dp)) {
                        Text(if (m.enabled) "turn off" else "turn on", color = cs.onBackground,
                            modifier = Modifier.clickable { Gecko.setEnabled(e, !m.enabled) }.padding(vertical = 6.dp))
                        if (m.enabled && m.optionsPageUrl != null) Text("settings", color = cs.onBackground,
                            modifier = Modifier.clickable { vm.openOptions(e); onClose() }.padding(vertical = 6.dp))
                        Text("remove", color = cs.onBackground, modifier = Modifier.clickable { Gecko.remove(e) }.padding(vertical = 6.dp))
                    }
                }
            }
        }
        Text("get firefox add-ons", fontSize = 18.sp, color = cs.onSurface, modifier = Modifier.fillMaxWidth()
            .background(cs.surfaceVariant).clickable { vm.newTab("https://addons.mozilla.org/android/"); onClose() }.padding(16.dp))
    }
}
