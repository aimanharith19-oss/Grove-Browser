package app.grove.browser.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.grove.browser.BrowserVM
import app.grove.browser.Note
import app.grove.browser.Tab
import app.grove.browser.Urls

/** Page actions (Vivaldi style): switches that change how the page looks, for this tab. */
private val PAGE_ACTIONS = listOf(
    "dark" to "Dark page", "grayscale" to "Grayscale", "sepia" to "Sepia", "contrast" to "High contrast",
    "bigtext" to "Bigger text", "serif" to "Reading font", "mono" to "Monospace font",
    "noimages" to "Hide images", "noanim" to "No animations",
)

@Composable
fun PageFxSheet(vm: BrowserVM, t: Tab, visible: Boolean, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose))
        }
        AnimatedVisibility(visible, modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(androidx.compose.animation.core.spring(dampingRatio = 0.86f, stiffness = 500f)) { it },
            exit = slideOutVertically(androidx.compose.animation.core.tween(200)) { it }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(cs.surface).navigationBarsPadding().padding(bottom = 8.dp)) {
                Box(Modifier.padding(top = 8.dp).align(Alignment.CenterHorizontally).width(36.dp).height(4.dp)
                    .clip(RoundedCornerShape(2.dp)).background(cs.onSurface.copy(alpha = 0.25f)))
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Page actions", fontSize = 18.sp, color = cs.onSurface, modifier = Modifier.weight(1f))
                    if (t.fx.isNotEmpty()) TextButton({ vm.clearFx(t) }) { Text("Reset") }
                }
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    PAGE_ACTIONS.forEach { (k, label) ->
                        Row(Modifier.fillMaxWidth().clickable { vm.toggleFx(t, k) }.padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(label, fontSize = 16.sp, color = cs.onSurface, modifier = Modifier.weight(1f))
                            Switch(k in t.fx, { vm.toggleFx(t, k) })
                        }
                    }
                    Text("Stays on for this tab until you switch it off.", fontSize = 12.sp, color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }
        }
    }
}

/** Notes (Vivaldi style): a searchable list; tap to edit, the link opens the page the note came from. */
@Composable
fun NotesScreen(vm: BrowserVM, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var q by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(cs.background)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Header("notes") }
            Box(Modifier.padding(end = 12.dp).size(44.dp).clip(CircleShape).background(cs.primary)
                .clickable { vm.noteDraft = Note(System.currentTimeMillis(), "", "", System.currentTimeMillis()) }, contentAlignment = Alignment.Center) {
                Icon(androidx.compose.material.icons.Icons.Default.Add, "new note", tint = Color.White)
            }
        }
        BasicTextField(q, { q = it }, singleLine = true, textStyle = TextStyle(color = cs.onSurface, fontSize = 15.sp), cursorBrush = SolidColor(cs.primary),
            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(40.dp),
            decorationBox = { inner ->
                Row(Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(cs.surfaceVariant).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(androidx.compose.material.icons.Icons.Default.Search, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Box(Modifier.weight(1f).padding(start = 8.dp)) { if (q.isEmpty()) Text("Search notes", color = cs.onSurfaceVariant, fontSize = 15.sp); inner() }
                }
            })
        val list = vm.notes.items.filter { q.isBlank() || it.text.contains(q, true) || it.url.contains(q, true) }
        if (vm.notes.items.isEmpty()) Text("No notes yet. Select text on a page and choose Add note in the menu, or tap +.",
            color = cs.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.id }) { n ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(cs.surface).clickable { vm.noteDraft = n }.padding(14.dp)) {
                    Text(n.text, fontSize = 15.sp, color = cs.onSurface, maxLines = 6, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(n.time)), fontSize = 12.sp, color = cs.onSurfaceVariant)
                        if (n.url.isNotBlank()) Text(" · " + Urls.host(n.url), fontSize = 12.sp, color = cs.primary, maxLines = 1,
                            modifier = Modifier.clickable { onOpen(n.url) })
                    }
                }
            }
        }
    }
}

@Composable
fun NoteDialog(vm: BrowserVM) {
    val d = vm.noteDraft ?: return
    val ctx = LocalContext.current
    var text by remember(d.id, d.text) { mutableStateOf(d.text) }
    val isNew = vm.notes.items.none { it.id == d.id }
    AlertDialog(
        onDismissRequest = { vm.noteDraft = null },
        title = { Text(if (isNew) "New note" else "Edit note") },
        text = {
            Column {
                OutlinedTextField(text, { text = it }, label = { Text("Note") }, minLines = 4, maxLines = 10, modifier = Modifier.fillMaxWidth())
                if (d.url.isNotBlank()) Text(Urls.host(d.url), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = { TextButton({ vm.saveNote(d.copy(text = text)); vm.noteDraft = null }) { Text("Save") } },
        dismissButton = {
            Row {
                if (!isNew) {
                    TextButton({ share(ctx, text + if (d.url.isNotBlank()) "\n" + d.url else "") }) { Text("Share") }
                    TextButton({ vm.deleteNote(d); vm.noteDraft = null }) { Text("Delete") }
                } else TextButton({ vm.noteDraft = null }) { Text("Cancel") }
            }
        },
    )
}
