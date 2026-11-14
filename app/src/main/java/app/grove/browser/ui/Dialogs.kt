package app.grove.browser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.grove.browser.Bookmark
import app.grove.browser.BrowserVM
import org.mozilla.geckoview.GeckoSession.ContentDelegate.ContextElement
import org.mozilla.geckoview.GeckoSession.PromptDelegate.AlertPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ButtonPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ChoicePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse
import org.mozilla.geckoview.GeckoSession.PromptDelegate.TextPrompt

private fun flatten(a: Array<ChoicePrompt.Choice>): List<ChoicePrompt.Choice> =
    a.flatMap { c -> c.items?.let { listOf(c) + flatten(it) } ?: listOf(c) }

/** Web page alert / confirm / prompt / <select> dialogs. */
@Composable
fun PromptDialog(vm: BrowserVM) {
    val pp = vm.prompt ?: return
    val p = pp.p
    fun done(r: PromptResponse) {
        if (vm.prompt !== pp) return
        vm.prompt = null
        if (!p.isComplete) pp.res.complete(r)
    }
    val title: @Composable () -> Unit = { Text(p.title.orEmpty(), maxLines = 2, overflow = TextOverflow.Ellipsis) }
    when (p) {
        is AlertPrompt -> AlertDialog(
            onDismissRequest = { done(p.dismiss()) }, title = title,
            text = { Text(p.message.orEmpty(), Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton({ done(p.dismiss()) }) { Text("ok") } },
        )
        is ButtonPrompt -> AlertDialog(
            onDismissRequest = { done(p.confirm(ButtonPrompt.Type.NEGATIVE)) }, title = title,
            text = { Text(p.message.orEmpty(), Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton({ done(p.confirm(ButtonPrompt.Type.POSITIVE)) }) { Text("ok") } },
            dismissButton = { TextButton({ done(p.confirm(ButtonPrompt.Type.NEGATIVE)) }) { Text("cancel") } },
        )
        is TextPrompt -> {
            var v by remember(p) { mutableStateOf(p.defaultValue.orEmpty()) }
            AlertDialog(
                onDismissRequest = { done(p.dismiss()) }, title = title,
                text = { Column { Text(p.message.orEmpty()); OutlinedTextField(v, { v = it }, singleLine = true) } },
                confirmButton = { TextButton({ done(p.confirm(v)) }) { Text("ok") } },
                dismissButton = { TextButton({ done(p.dismiss()) }) { Text("cancel") } },
            )
        }
        is ChoicePrompt -> {
            val multi = p.type == ChoicePrompt.Type.MULTIPLE
            val all = remember(p) { flatten(p.choices) }
            var picked by remember(p) { mutableStateOf(all.filter { it.selected && it.items == null }.toSet()) }
            AlertDialog(
                onDismissRequest = { done(p.dismiss()) }, title = title,
                text = {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        all.filterNot { it.separator }.forEach { c ->
                            val group = c.items != null
                            val on = c in picked
                            Text(
                                (if (multi && !group) (if (on) "☑ " else "☐ ") else "") + c.label,
                                fontSize = if (group) 14.sp else 18.sp,
                                fontWeight = if (group) FontWeight.Bold else if (on) FontWeight.Normal else FontWeight.Light,
                                color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.fillMaxWidth()
                                    .clickable(enabled = !group && !c.disabled) {
                                        if (multi) picked = if (on) picked - c else picked + c else done(p.confirm(c))
                                    }
                                    .padding(vertical = 10.dp),
                            )
                        }
                    }
                },
                confirmButton = { if (multi) TextButton({ done(p.confirm(picked.toTypedArray())) }) { Text("done") } },
                dismissButton = { TextButton({ done(p.dismiss()) }) { Text("cancel") } },
            )
        }
        else -> LaunchedEffect(p) { done(p.dismiss()) }
    }
}

@Composable
fun ContextMenuDialog(vm: BrowserVM) {
    val (tab, el) = vm.contextMenu ?: return
    val ctx = LocalContext.current
    val close = { vm.contextMenu = null }
    val acts = buildList {
        el.linkUri?.let { l ->
            add(MenuItem("open in new tab") { vm.newTab(l, private = tab.private, parent = tab) })
            add(MenuItem("open in background") { vm.newTab(l, private = tab.private, select = false, parent = tab) })
            add(MenuItem("copy link") { copy(ctx, l) })
            add(MenuItem("share link") { share(ctx, l) })
        }
        if (el.type == ContextElement.TYPE_IMAGE) el.srcUri?.let { s ->
            add(MenuItem("open image in new tab") { vm.newTab(s, private = tab.private, parent = tab) })
            add(MenuItem("save image") { vm.downloadUrl(s) })
            add(MenuItem("copy image address") { copy(ctx, s) })
        }
    }
    AlertDialog(
        onDismissRequest = close,
        confirmButton = {},
        title = { Text(el.linkUri ?: el.srcUri.orEmpty(), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                acts.forEach { a ->
                    Text(a.label, fontSize = 19.sp, fontWeight = FontWeight.Light,
                        modifier = Modifier.fillMaxWidth().clickable { close(); a.action() }.padding(vertical = 10.dp))
                }
            }
        },
    )
}

@Composable
fun BookmarkDialog(b: Bookmark, isNew: Boolean, onSave: (Bookmark) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var title by remember(b) { mutableStateOf(b.title) }
    var url by remember(b) { mutableStateOf(b.url) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "add to favorites" else "edit favorite") },
        text = {
            Column {
                OutlinedTextField(title, { title = it }, label = { Text("name") }, singleLine = true)
                OutlinedTextField(url, { url = it }, label = { Text("address") }, singleLine = true)
            }
        },
        confirmButton = { TextButton({ if (url.isNotBlank()) onSave(b.copy(title = title, url = url)) }) { Text("save") } },
        dismissButton = {
            if (isNew) TextButton(onDismiss) { Text("cancel") } else TextButton(onDelete) { Text("delete") }
        },
    )
}

@Composable
fun CrashScreen(text: String, onContinue: () -> Unit) {
    val ctx = LocalContext.current
    GroveTheme(app.grove.browser.Prefs(ctx)) {
        val cs = MaterialTheme.colorScheme
        Column(Modifier.fillMaxSize().background(cs.background).windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)) {
            Text("grove crashed", fontSize = 40.sp, fontWeight = FontWeight.Light, color = cs.onBackground)
            Text("copy this and send it so it can be fixed. it stays on your phone unless you share it.",
                color = cs.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            Text(text, fontSize = 11.sp, color = cs.onBackground,
                modifier = Modifier.weight(1f).fillMaxWidth().background(cs.surfaceVariant)
                    .verticalScroll(rememberScrollState()).padding(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton({ copy(ctx, text) }) { Text("copy") }
                TextButton(onContinue) { Text("open grove") }
            }
        }
    }
}
