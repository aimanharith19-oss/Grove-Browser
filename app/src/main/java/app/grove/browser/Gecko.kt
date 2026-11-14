package app.grove.browser

import android.content.Context
import android.util.Log
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.WebExtension
import java.io.File

/** Single Gecko engine for the whole app, locked down for privacy. */
object Gecko {
    private const val UBO_ID = "uBlock0@raymondhill.net"
    private const val UBO_URI = "resource://android/assets/extensions/ublock/"

    @Volatile private var rt: GeckoRuntime? = null
    @Volatile var ublock: WebExtension? = null

    fun runtime(ctx: Context): GeckoRuntime =
        rt ?: synchronized(this) { rt ?: create(ctx.applicationContext).also { rt = it } }

    private fun create(ctx: Context): GeckoRuntime {
        val cfg = File(ctx.filesDir, "gecko.yaml").apply { writeText(CONFIG) }
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
            .build()
        // No telemetry delegate and no crash handler are set: nothing is collected or sent.
        val runtime = GeckoRuntime.create(ctx, settings)
        if (ctx.assets.list("extensions/ublock")?.contains("manifest.json") == true) {
            val ctl = runtime.webExtensionController
            ctl.ensureBuiltIn(UBO_URI, UBO_ID).accept({ ext ->
                ublock = ext
                if (ext != null) ctl.setAllowedInPrivateBrowsing(ext, true).accept({ ublock = it ?: ext }, {})
            }, { Log.w("Grove", "uBlock Origin failed to load", it) })
        }
        return runtime
    }

    private val CONFIG = """
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
  dom.security.https_first: true
  media.peerconnection.ice.default_address_only: true
  media.autoplay.default: 5
  browser.cache.memory.capacity: 65536
"""
}
