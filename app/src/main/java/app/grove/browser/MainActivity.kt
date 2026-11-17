package app.grove.browser

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.app.SearchManager
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.util.Rational
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import app.grove.browser.ui.BrowserScreen
import app.grove.browser.ui.CrashScreen
import app.grove.browser.ui.GroveTheme
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession.PromptDelegate.FilePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse

open class MainActivity : ComponentActivity() {
    /** Set by [WebAppActivity]: the web app's start page and scope. */
    protected open val webApp: Pair<String, String?>? get() = null
    private val vm: BrowserVM by viewModels { viewModelFactory { initializer { BrowserVM(application, webApp?.first, webApp?.second) } } }
    private var ready = false
    private var pendingFile: Pair<FilePrompt, GeckoResult<PromptResponse>>? = null
    private val pickOne = registerForActivityResult(ActivityResultContracts.OpenDocument()) { finishFile(listOfNotNull(it)) }
    private var permDone: ((Boolean) -> Unit)? = null
    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        permDone?.invoke(res.isNotEmpty() && res.values.all { it }); permDone = null
    }
    private val pickMany = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { finishFile(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val crash = GroveApp.crashFile(application)
        if (crash.exists()) {
            val text = crash.readText()
            crash.delete()
            setContent { CrashScreen(text) { recreate() } }
            return
        }
        if (javaClass != MainActivity::class.java && webApp == null) { finish(); return }
        vm.filePicker = { p, r ->
            pendingFile?.let { (op, or) -> or.complete(op.dismiss()) }
            pendingFile = p to r
            val mimes = p.mimeTypes?.filter { it.contains('/') }?.takeIf { it.isNotEmpty() }?.toTypedArray() ?: arrayOf("*/*")
            runCatching { if (p.type == FilePrompt.Type.MULTIPLE) pickMany.launch(mimes) else pickOne.launch(mimes) }
                .onFailure { finishFile(emptyList()) }
        }
        vm.external = { intent, fallback ->
            runCatching { startActivity(intent) }.onFailure { if (fallback != null) vm.go(fallback) else vm.toast("no app can open this link") }
        }
        vm.androidPerms = { perms, done ->
            permDone?.invoke(false)
            permDone = done
            runCatching { askPerms.launch(perms) }.onFailure { permDone = null; done(false) }
        }
        vm.enterPip = { if (!isInPictureInPictureMode) runCatching { enterPictureInPictureMode(pipParams()) } }
        androidx.core.content.ContextCompat.registerReceiver(this, pipReceiver, IntentFilter(PIP_INTENT), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        ready = true
        if (savedInstanceState == null && webApp == null) handleIntent(intent)
        setContent {
            GroveTheme(vm.prefs) {
                BrowserScreen(vm)
                // Android 12+: swiping home while a video plays goes straight to picture-in-picture.
                val t = vm.cur
                LaunchedEffect(t?.videoPlaying, t?.playing, vm.prefs.pip) { updatePip() }
            }
        }
    }

    private fun finishFile(uris: List<Uri>) {
        val (p, r) = pendingFile ?: return
        pendingFile = null
        r.complete(
            when {
                uris.isEmpty() -> p.dismiss()
                p.type == FilePrompt.Type.MULTIPLE -> p.confirm(this, uris.toTypedArray())
                else -> p.confirm(this, uris[0])
            }
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (webApp == null) handleIntent(intent)
    }

    // ---------- picture-in-picture ----------

    /** Settings: 0 off, 1 any playing video when going home, 2 only a full-screen video. */
    private fun pipWanted(): Boolean {
        val t = vm.cur ?: return false
        val media = t.videoPlaying || t.playing
        return ready && when (vm.prefs.pip) {
            1 -> t.videoPlaying || (vm.fullscreen && t.playing)
            2 -> vm.fullscreen && media
            else -> false
        }
    }

    private fun pipParams(): PictureInPictureParams {
        val t = vm.cur
        val r = (t?.videoW ?: 16).toFloat() / (t?.videoH ?: 9).coerceAtLeast(1)
        val ratio = when { r > 2.39f -> Rational(239, 100); r < 0.42f -> Rational(42, 100); else -> Rational((r * 1000).toInt(), 1000) }
        val b = PictureInPictureParams.Builder().setAspectRatio(ratio)
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(pipWanted()).setSeamlessResizeEnabled(false)
        // same buttons as Grove's own mini player: audio only, play/pause (Android adds expand and close)
        val playing = t?.playing == true || t?.videoPlaying == true
        b.setActions(listOf(
            pipAction(R.drawable.ic_pip_audio, "Audio only", PIP_AUDIO, 1),
            if (playing) pipAction(R.drawable.ic_pip_pause, "Pause", PIP_PLAY, 2) else pipAction(R.drawable.ic_pip_play, "Play", PIP_PLAY, 2),
        ))
        return b.build()
    }

    private fun pipAction(icon: Int, title: String, what: String, code: Int) = RemoteAction(
        Icon.createWithResource(this, icon), title, title,
        PendingIntent.getBroadcast(this, code, Intent(PIP_INTENT).setPackage(packageName).putExtra("a", what), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (!ready) return
            when (i.getStringExtra("a")) {
                PIP_PLAY -> vm.togglePlay()
                // close the video window, keep the sound: Grove goes to the background with the media notification
                PIP_AUDIO -> { vm.startAudioOnly(); moveTaskToBack(true) }
            }
        }
    }

    private fun updatePip() { if (ready) runCatching { setPictureInPictureParams(pipParams()) } }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (pipWanted() && !isInPictureInPictureMode && Build.VERSION.SDK_INT < 31) runCatching { enterPictureInPictureMode(pipParams()) }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (ready) vm.pipMode(isInPictureInPictureMode)
    }

    /** Only http(s) links are accepted from other apps (blocks file:, javascript:, content: tricks). */
    private fun handleIntent(i: Intent?) {
        if (!ready) return
        val url = when (i?.action) {
            Intent.ACTION_VIEW -> i.dataString?.takeIf { Urls.isWeb(it) }?.let { Urls.clean(it) }
            Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)?.let { txt ->
                Urls.fromInput(Regex("https?://\\S+").find(txt)?.value ?: txt, vm.prefs.engineUrl)
            }
            Intent.ACTION_WEB_SEARCH -> i.getStringExtra(SearchManager.QUERY)?.let { Urls.fromInput(it, vm.prefs.engineUrl) }
            else -> null
        }
        if (!url.isNullOrBlank()) vm.openUrl(url)
    }


    override fun onStart() { super.onStart(); if (ready) { vm.bookmarks.reload(); vm.history.reload(); vm.foreground(true) } }

    override fun onStop() {
        super.onStop()
        if (ready) { vm.foreground(false); vm.save() }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(pipReceiver) }
        pendingFile?.let { (p, r) -> r.complete(p.dismiss()) }
        pendingFile = null
        if (ready) { vm.filePicker = null; vm.external = null; vm.enterPip = null; vm.androidPerms = null; vm.capture = null }
        super.onDestroy()
    }
}

private const val PIP_INTENT = "app.grove.browser.PIP"
private const val PIP_PLAY = "play"
private const val PIP_AUDIO = "audio"
