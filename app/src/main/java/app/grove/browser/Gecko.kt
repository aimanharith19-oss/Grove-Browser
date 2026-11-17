package app.grove.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import java.io.File

/** A Firefox add-on asking to be installed, waiting for the user's answer. */
class InstallAsk(
    val ext: WebExtension, val permissions: List<String>,
    val result: GeckoResult<WebExtension.PermissionPromptResponse>,
)

/** Single Gecko engine for the whole app, locked down for privacy, plus Firefox add-on support. */
object Gecko {
    const val UBO_ID = "uBlock0@raymondhill.net"
    private const val HELPER_ID = "helper@grove.browser"
    private const val HELPER_URI = "resource://android/assets/helper/"
    /** Groveguard web protection (ads, trackers, 18+, dangerous sites), built into the APK: no Mozilla signature needed. */
    const val GUARD_ID = "web-protection@groveguard.org"
    private const val GUARD_URI = "resource://android/assets/groveguard/"
    private const val ENABLE_USER = 1 // WebExtension.EnableSource.USER

    @Volatile private var rt: GeckoRuntime? = null
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())

    /** Installed add-ons (Grove's internal helper is hidden). */
    val addons = mutableStateListOf<WebExtension>()
    /** Toolbar buttons of add-ons, by add-on id. */
    val actions = mutableStateMapOf<String, WebExtension.Action>()
    var installAsk by mutableStateOf<InstallAsk?>(null)
    /** Gives a fresh (not yet opened) tab session for add-on popups and add-on tabs. */
    var popupHost: (() -> GeckoSession?)? = null
    /** Grove's internal helper add-on (picture-in-picture, background play). */
    @Volatile var helper: WebExtension? = null; private set
    val helperListeners = mutableListOf<(WebExtension) -> Unit>()

    fun runtime(ctx: Context): GeckoRuntime =
        rt ?: synchronized(this) { rt ?: create(ctx.applicationContext).also { rt = it } }

    private fun create(ctx: Context): GeckoRuntime {
        app = ctx
        val sp = ctx.getSharedPreferences("grove", Context.MODE_PRIVATE)
        val https = sp.getInt("https", 0)
        val cfg = File(ctx.filesDir, "gecko.yaml").apply { writeText(config(httpsFirst = https == 0)) }
        val blocking = ContentBlocking.Settings.Builder()
            .antiTracking(ContentBlocking.AntiTracking.STRICT)
            .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT)
            .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
            .cookieBehaviorPrivateMode(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
            .safeBrowsing(ContentBlocking.SafeBrowsing.DEFAULT) // phishing + malware pages
            .build()
        val settings = GeckoRuntimeSettings.Builder()
            .configFilePath(cfg.absolutePath)
            .contentBlocking(blocking)
            .aboutConfigEnabled(false)
            .remoteDebuggingEnabled(false)
            .consoleOutput(false)
            .allowInsecureConnections(if (https == 1) GeckoRuntimeSettings.HTTPS_ONLY else GeckoRuntimeSettings.ALLOW_ALL)
            .build()
        // No telemetry delegate and no crash handler are set: nothing is collected or sent.
        val runtime = GeckoRuntime.create(ctx, settings)
        applyPrefs(sp.getInt("textSize", 100), sp.getInt("forceZoom", 1) == 1, sp.getInt("doh", 0), runtime)
        setEtp(sp.getInt("etp", 0), runtime)
        val ctl = runtime.webExtensionController
        ctl.setPromptDelegate(prompts)
        ctl.ensureBuiltIn(HELPER_URI, HELPER_ID).accept({ ext ->
            if (ext != null) { helper = ext; helperListeners.toList().forEach { it(ext) } }
        }, { Log.w("Grove", "helper failed", it) })
        ctl.ensureBuiltIn(GUARD_URI, GUARD_ID).accept({ ext ->
            if (ext != null) {
                Log.i("Grove", "Groveguard ready ${ext.metaData.version}")
                linkGroveguard(ctx, ext)
                ctl.setAllowedInPrivateBrowsing(ext, true).accept({ refresh(runtime) }, { refresh(runtime) })
            }
        }, { Log.w("Grove", "Groveguard failed", it) })
        installUblock(ctx, runtime)
        refresh(runtime)
        return runtime
    }

    /**
     * The built-in Groveguard extension asks for the Groveguard app's pairing code; Grove Browser fetches it from the app
     * (which only gives it to the real Grove Browser) and hands it over, so linking needs no typing.
     */
    private fun linkGroveguard(ctx: Context, ext: WebExtension) {
        ext.setMessageDelegate(object : WebExtension.MessageDelegate {
            override fun onConnect(port: WebExtension.Port) {
                Thread {
                    val code = runCatching {
                        ctx.contentResolver.call(android.net.Uri.parse("content://org.grove.guard.status"), "extLink", null, null)?.getString("code")
                    }.onFailure { Log.w("Grove", "Groveguard link: ${it.javaClass.simpleName}: ${it.message}") }.getOrNull()
                    if (code.isNullOrBlank()) return@Thread // Groveguard isn't installed (or is too old)
                    main.post {
                        runCatching { port.postMessage(org.json.JSONObject().put("code", code).put("name", "Grove Browser")) }
                        Log.i("Grove", "Groveguard linked")
                    }
                }.start()
            }
        }, "groveguard")
    }

    /** Text size, zoom and DNS-over-HTTPS settings (applied at start and whenever you change them). */
    fun applyPrefs(textSize: Int, forceZoom: Boolean, doh: Int, r: GeckoRuntime? = rt) {
        val s = r?.settings ?: return
        runCatching {
            s.setAutomaticFontSizeAdjustment(false)
            s.setFontInflationEnabled(textSize != 100)
            s.setFontSizeFactor(textSize / 100f)
            s.setForceUserScalableEnabled(forceZoom)
            val provider = DOH.getOrNull(doh - 1)
            if (provider == null) s.setTrustedRecursiveResolverMode(GeckoRuntimeSettings.TRR_MODE_OFF)
            else { s.setTrustedRecursiveResolverUri(provider.second); s.setTrustedRecursiveResolverMode(GeckoRuntimeSettings.TRR_MODE_FIRST) }
        }.onFailure { Log.w("Grove", "prefs failed", it) }
    }

    /** Tracking protection level: 0 strict, 1 standard, 2 off. */
    fun setEtp(level: Int, r: GeckoRuntime? = rt) {
        val cb = r?.settings?.contentBlocking ?: return
        runCatching {
            cb.setEnhancedTrackingProtectionLevel(when (level) { 1 -> ContentBlocking.EtpLevel.DEFAULT; 2 -> ContentBlocking.EtpLevel.NONE; else -> ContentBlocking.EtpLevel.STRICT })
            cb.setAntiTracking(when (level) { 1 -> ContentBlocking.AntiTracking.DEFAULT; 2 -> ContentBlocking.AntiTracking.NONE; else -> ContentBlocking.AntiTracking.STRICT })
        }.onFailure { Log.w("Grove", "etp failed", it) }
    }

    fun setHttps(mode: Int) {
        rt?.settings?.setAllowInsecureConnections(if (mode == 1) GeckoRuntimeSettings.HTTPS_ONLY else GeckoRuntimeSettings.ALLOW_ALL)
    }

    // ---------- add-ons ----------

    private val prompts = object : WebExtensionController.PromptDelegate {
        override fun onInstallPromptRequest(
            extension: WebExtension, permissions: Array<String>, origins: Array<String>, dataCollectionPermissions: Array<String>,
        ): GeckoResult<WebExtension.PermissionPromptResponse> {
            // Grove's bundled uBlock Origin is approved automatically. Data collection is never granted.
            if (extension.id == UBO_ID) return GeckoResult.fromValue(WebExtension.PermissionPromptResponse(true, true, false))
            val r = GeckoResult<WebExtension.PermissionPromptResponse>()
            main.post {
                installAsk?.result?.complete(WebExtension.PermissionPromptResponse(false, false, false))
                installAsk = InstallAsk(extension, (permissions + origins).toList(), r)
            }
            return r
        }
    }

    fun answerInstall(allow: Boolean, privateTabs: Boolean) {
        val a = installAsk ?: return
        installAsk = null
        a.result.complete(WebExtension.PermissionPromptResponse(allow, allow && privateTabs, false))
    }

    /** Installs a Firefox add-on (.xpi). Gecko checks it is signed by Mozilla. */
    fun install(url: String) {
        val r = rt ?: return
        toast("getting add-on…")
        r.webExtensionController.install(url).accept({ ext ->
            toast("${ext?.metaData?.name ?: "add-on"} added")
            refresh(r)
        }, { e -> Log.w("Grove", "install failed", e); toast("couldn't add this add-on"); refresh(r) })
    }

    fun setEnabled(e: WebExtension, on: Boolean) {
        val r = rt ?: return
        if (e.id == GUARD_ID && !on) return // built in, always on
        val ctl = r.webExtensionController
        (if (on) ctl.enable(e, ENABLE_USER) else ctl.disable(e, ENABLE_USER)).accept({ refresh(r) }, { refresh(r) })
    }

    fun remove(e: WebExtension) {
        val r = rt ?: return
        if (e.id == GUARD_ID) return // built in
        if (e.id == UBO_ID) app.getSharedPreferences("grove", Context.MODE_PRIVATE).edit().putBoolean("ubo_removed", true).apply()
        actions.remove(e.id)
        r.webExtensionController.uninstall(e).accept({ refresh(r) }, { refresh(r) })
    }

    fun refresh(r: GeckoRuntime? = rt) {
        r ?: return
        r.webExtensionController.list().accept({ list ->
            val l = list.orEmpty().filter { it.id != HELPER_ID }
            l.forEach { bind(it) }
            addons.clear(); addons.addAll(l.sortedBy { it.metaData.name?.lowercase() })
            actions.keys.retainAll(l.filter { it.metaData.enabled }.map { it.id }.toSet())
        }, { Log.w("Grove", "list failed", it) })
    }

    private fun bind(e: WebExtension) {
        e.setActionDelegate(object : WebExtension.ActionDelegate {
            override fun onBrowserAction(extension: WebExtension, session: GeckoSession?, action: WebExtension.Action) {
                if (session == null) actions[extension.id] = action
            }
            override fun onOpenPopup(extension: WebExtension, action: WebExtension.Action): GeckoResult<GeckoSession>? =
                popupHost?.invoke()?.let { GeckoResult.fromValue(it) }
            override fun onTogglePopup(extension: WebExtension, action: WebExtension.Action) = onOpenPopup(extension, action)
        })
        e.setTabDelegate(object : WebExtension.TabDelegate {
            override fun onNewTab(extension: WebExtension, createDetails: WebExtension.CreateTabDetails): GeckoResult<GeckoSession>? =
                popupHost?.invoke()?.let { GeckoResult.fromValue(it) }
        })
    }

    /** Installs the uBlock Origin release bundled in the APK, signed by Mozilla, as a normal add-on. */
    /**
     * Groveguard (built in, always on) already blocks with the same uBlock Origin / EasyList lists, plus element hiding and
     * ad-script blocking. Running uBlock Origin too doubles the work on every request and page, which made pages laggy,
     * so it is no longer added, and it is switched off once on phones that have it (it can be switched back on in extensions).
     */
    private fun installUblock(ctx: Context, r: GeckoRuntime) {
        val sp = ctx.getSharedPreferences("grove", Context.MODE_PRIVATE)
        val ctl = r.webExtensionController
        ctl.list().accept({ list ->
            val have = list.orEmpty().firstOrNull { it.id == UBO_ID } ?: return@accept
            if (have.isBuiltIn) { ctl.uninstall(have).accept({ refresh(r) }, { refresh(r) }); return@accept }
            if (!sp.getBoolean("ubo_off_once", false) && have.metaData.enabled) {
                sp.edit().putBoolean("ubo_off_once", true).apply()
                ctl.disable(have, ENABLE_USER).accept({ refresh(r); toast("uBlock Origin switched off: Groveguard already blocks the same ads, so pages are faster") }, { refresh(r) })
            } else sp.edit().putBoolean("ubo_off_once", true).apply()
        }, { Log.w("Grove", "list failed", it) })
    }

    private fun toast(msg: String) = main.post { Toast.makeText(app, msg, Toast.LENGTH_SHORT).show() }

    private fun config(httpsFirst: Boolean) = """
prefs:
  toolkit.telemetry.enabled: false
  toolkit.telemetry.unified: false
  toolkit.telemetry.archive.enabled: false
  toolkit.telemetry.server: ""
  datareporting.healthreport.uploadEnabled: false
  datareporting.policy.dataSubmissionEnabled: false
  datareporting.usage.uploadEnabled: false
  dom.private-attribution.submission.enabled: false
  app.normandy.enabled: false
  app.shield.optoutstudies.enabled: false
  messaging-system.rsexperimentloader.enabled: false
  nimbus.rollouts.enabled: false
  browser.ml.enable: false
  browser.ml.chat.enabled: false
  browser.ml.linkPreview.enabled: false
  browser.tabs.groups.smart.enabled: false
  extensions.ml.enabled: false
  extensions.getAddons.cache.enabled: false
  extensions.getAddons.showPane: false
  extensions.htmlaboutaddons.recommendations.enabled: false
  extensions.dataCollectionPermissions.enabled: false
  browser.safebrowsing.downloads.remote.enabled: false
  privacy.query_stripping.enabled: true
  privacy.query_stripping.enabled.pbmode: true
  privacy.globalprivacycontrol.enabled: true
  privacy.donottrackheader.enabled: true
  browser.send_pings: false
  network.prefetch-next: false
  network.dns.disablePrefetch: true
  network.predictor.enabled: false
  network.http.speculative-parallel-limit: 0
  dom.security.https_first: $httpsFirst
  dom.security.https_first_pbm: $httpsFirst
  media.peerconnection.ice.default_address_only: true
  media.autoplay.default: 1
  media.videocontrols.picture-in-picture.enabled: false
  browser.cache.memory.capacity: 65536
  general.smoothScroll: true
  general.smoothScroll.msdPhysics.enabled: true
  apz.overscroll.enabled: true
  apz.allow_zooming: true
  layout.css.scroll-behavior.enabled: true
"""
}
