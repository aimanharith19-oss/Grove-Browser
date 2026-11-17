package app.grove.browser.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.grove.browser.BrowserVM
import app.grove.browser.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoView
import kotlin.math.roundToInt

/**
 * Grove's floating video window: the playing tab's video in a small window over Grove, so it keeps playing while you
 * use other tabs and websites. Same look and moves as Android's picture-in-picture and the in-page mini player:
 * rounded, tap for dimmed controls (audio only, back to the video, play/pause, close), drag anywhere, snaps to a side.
 */
@Composable
fun FloatingPlayer(vm: BrowserVM) {
    val t = vm.floating
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val d = LocalDensity.current
        val maxW = constraints.maxWidth.toFloat(); val maxH = constraints.maxHeight.toFloat()
        val wPx = (maxW * 0.62f).coerceAtMost(with(d) { 340.dp.toPx() })
        val ratio = ((t?.videoH ?: 9).toFloat() / (t?.videoW ?: 16).coerceAtLeast(1)).coerceIn(0.4f, 1.8f)
        val hPx = wPx * ratio
        val margin = with(d) { 12.dp.toPx() }
        val x = remember { Animatable(maxW - wPx - margin) }
        val y = remember { Animatable(maxH - hPx - with(d) { 96.dp.toPx() }) }
        val scope = rememberCoroutineScope()
        var shown by remember { mutableStateOf(false) }
        LaunchedEffect(shown) { if (shown) { delay(3000); shown = false } }
        AnimatedVisibility(t != null && !vm.pip && !vm.fullscreen,
            enter = scaleIn(spring(dampingRatio = 0.8f, stiffness = 500f), initialScale = 0.6f) + fadeIn(),
            exit = scaleOut(targetScale = 0.85f) + fadeOut()) {
            val tab = t ?: return@AnimatedVisibility
            val shape = RoundedCornerShape(14.dp)
            Box(Modifier
                .offset { IntOffset(x.value.roundToInt(), y.value.roundToInt()) }
                .size(with(d) { wPx.toDp() }, with(d) { hPx.toDp() })
                .shadow(10.dp, shape).clip(shape).background(Color.Black)) {
                AndroidView(
                    factory = { c -> GeckoView(c).apply { setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW) } },
                    update = { v ->
                        val s = tab.session
                        // after the main page view has let go of this tab
                        v.post { if (v.session !== s && s.isOpen && vm.floating === tab) runCatching { if (v.session != null) v.releaseSession(); v.setSession(s) } }
                    },
                    onRelease = { runCatching { it.releaseSession() } },
                    modifier = Modifier.fillMaxSize(),
                )
                // a clear layer on top so taps and drags reach Grove, not the page
                Box(Modifier.fillMaxSize()
                .pointerInput(tab) {
                        detectDragGestures(
                            onDragStart = { shown = false },
                            onDragEnd = {
                                scope.launch {
                                    // fling off a side closes it, otherwise it snaps to the nearest side
                                    if (x.value + wPx < 60f || x.value > maxW - 60f) { vm.stopFloating(keepPlaying = false); return@launch }
                                    launch { x.animateTo(if (x.value + wPx / 2 < maxW / 2) margin else maxW - wPx - margin, spring(0.85f, 400f)) }
                                    y.animateTo(y.value.coerceIn(margin, maxH - hPx - margin), spring(0.85f, 400f))
                                }
                            },
                        ) { c, drag -> c.consume(); scope.launch { x.snapTo(x.value + drag.x); y.snapTo((y.value + drag.y).coerceIn(0f, maxH - hPx)) } }
                    }
                    .pointerInput(tab) { detectTapGestures(onTap = { shown = !shown }) })
                AnimatedVisibility(shown, enter = fadeIn(), exit = fadeOut()) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))) {
                        Btn(R.drawable.ic_pip_close, "close", Modifier.align(Alignment.TopEnd)) { vm.stopFloating(keepPlaying = false) }
                        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Btn(R.drawable.ic_pip_audio, "audio only") { vm.stopFloating(keepPlaying = true) }
                            Btn(R.drawable.ic_pip_expand, "back to the video", big = true) { vm.stopFloating(keepPlaying = true, back = true) }
                            Btn(if (tab.playing || tab.videoPlaying) R.drawable.ic_pip_pause else R.drawable.ic_pip_play, "play or pause") {
                                tab.media?.let { if (tab.playing) it.pause() else it.play() }; shown = true
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Btn(icon: Int, label: String, modifier: Modifier = Modifier, big: Boolean = false, click: () -> Unit) =
    Box(modifier.size(if (big) 46.dp else 40.dp).clip(CircleShape).clickable(onClick = click), contentAlignment = Alignment.Center) {
        Icon(androidx.compose.ui.res.painterResource(icon), label, tint = Color.White, modifier = Modifier.size(if (big) 28.dp else 24.dp))
    }
