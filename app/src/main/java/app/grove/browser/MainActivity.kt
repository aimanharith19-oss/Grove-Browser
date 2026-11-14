package app.grove.browser

import android.app.PictureInPictureParams
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
        ready = true
        if (savedInstanceState == null && webApp == null) handleIntent(intent)
        setContent {
            GroveTheme(vm.prefs) {
                BrowserScreen(vm)
                // Android 12+: swiping home while a video plays goes straight to picture-in-picture.
                val t = vm.cur
                LaunchedEffect(t?.videoPlaying, vm.prefs.pip) { updatePip() }
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

    private fun pipWanted() = ready && vm.prefs.pip == 1 && vm.cur?.videoPlaying == true

    private fun pipParams(): PictureInPictureParams {
        val t = vm.cur
        val r = (t?.videoW ?: 16).toFloat() / (t?.videoH ?: 9).coerceAtLeast(1)
        val ratio = when { r > 2.39f -> Rational(239, 100); r < 0.42f -> Rational(42, 100); else -> Rational((r * 1000).toInt(), 1000) }
        val b = PictureInPictureParams.Builder().setAspectRatio(ratio)
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(pipWanted()).setSeamlessResizeEnabled(false)
        return b.build()
    }

    private fun updatePip() { if (ready) runCatching { setPictureInPictureParams(pipParams()) } }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (pipWanted() && !isInPictureInPictureMode && Build.VERSION.SDK_INT < 31) runCatching { enterPictureInPictureMode(pipParams()) }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (ready) vm.setPip(isInPictureInPictureMode)
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
        pendingFile?.let { (p, r) -> r.complete(p.dismiss()) }
        pendingFile = null
        if (ready) { vm.filePicker = null; vm.external = null }
        super.onDestroy()
    }
}
