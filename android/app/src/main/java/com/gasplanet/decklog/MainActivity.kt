package com.gasplanet.decklog

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.Manifest
import android.content.pm.PackageManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * A shell around the deck log page. The page itself is unchanged and lives in
 * assets, so it behaves exactly as it does in the browser; this class only
 * supplies the four things a bare WebView will not do:
 *   - keep localStorage between launches
 *   - open the camera / gallery for the photo buttons
 *   - turn the CSV "downloads" into real files in the Downloads folder
 *   - print the weekly report
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraOutputUri: Uri? = null

    // The page asks for a position; Android has to agree first. The callback is
    // held while the system dialog is up and answered either way afterwards, so
    // a refused permission fails the page's request rather than hanging it.
    private var geoOrigin: String? = null
    private var geoCallback: GeolocationPermissions.Callback? = null

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            val ok = granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                     granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            geoCallback?.invoke(geoOrigin ?: "", ok, false)
            geoCallback = null; geoOrigin = null
            if (!ok) toast("Location is off for this app, so the map cannot show your position.")
        }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // Answered either way and never referenced again -- checkAndNotify already
    // catches a refusal (SecurityException) and just stays quiet, the same way
    // a "no" on location leaves the map working without a position dot.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    // The system never reports whether the user actually granted this from the
    // screen it opens -- its own result code is always "cancelled" regardless
    // -- so there is nothing meaningful to register a callback for. Settings
    // re-checks isIgnoringBatteryOptimizations() fresh the next time it opens.
    private fun requestIgnoreBatteryOptimizations() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
        } catch (e: Exception) {
            // Some OEMs block this screen outright; nothing more to do here.
        }
    }

    private val fileChooser =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val cb = filePathCallback ?: return@registerForActivityResult
            filePathCallback = null
            if (result.resultCode != Activity.RESULT_OK) { cb.onReceiveValue(null); return@registerForActivityResult }

            val data = result.data
            val uris: Array<Uri>? = when {
                // camera wrote straight to the uri we handed it
                data == null || (data.data == null && data.clipData == null) ->
                    cameraOutputUri?.let { arrayOf(it) }
                data.clipData != null -> {
                    val clip = data.clipData!!
                    Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                }
                else -> data.data?.let { arrayOf(it) }
            }
            cb.onReceiveValue(uris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        web = WebView(this)
        setContentView(web)

        WebView.setWebContentsDebuggingEnabled(false)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true          // localStorage — the whole data store
            setGeolocationEnabled(true)       // the map's own-position marker
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mediaPlaybackRequiresUserGesture = false
        }

        web.addJavascriptInterface(AndroidBridge(), "AndroidBridge")

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript(BRIDGE_JS, null)
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                if (callback == null) return
                if (hasLocationPermission()) { callback.invoke(origin ?: "", true, false); return }
                geoOrigin = origin; geoCallback = callback
                locationPermission.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }

            override fun onShowFileChooser(
                view: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                return try {
                    fileChooser.launch(buildChooserIntent(params))
                    true
                } catch (e: Exception) {
                    filePathCallback = null
                    callback?.onReceiveValue(null)
                    false
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })

        NotificationHelper.ensureChannel(this)
        // Respects a reminder the user already turned off in Settings -- the
        // default (enabled, 05:30) only applies until the page has published
        // its first agenda, the same as any other setting read before that.
        if (NotificationHelper.isEnabled(this)) DailyCheckReceiver.schedule(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Asked once ever, not on every launch -- a system dialog on every
        // cold start would be worse than the problem it is trying to catch.
        // Settings carries a manual "Allow" for anyone who dismissed this or
        // whose phone was not yet set up to ask for it.
        val batteryPrefs = getSharedPreferences("decklog_battery", MODE_PRIVATE)
        if (NotificationHelper.isEnabled(this) && !isIgnoringBatteryOptimizations() &&
            !batteryPrefs.getBoolean("asked", false)) {
            batteryPrefs.edit().putBoolean("asked", true).apply()
            requestIgnoreBatteryOptimizations()
        }

        web.loadUrl("file:///android_asset/index.html")
    }

    // Builds the picker the page actually asked for.
    //
    // This used to hardcode an image-only MIME type and ignore the accept types
    // altogether, so every file input in the app opened a photo gallery — which
    // meant none of the four data imports (three CSV and the instrument JSON)
    // could ever pick their file on Android. Photos worked, so nothing looked
    // broken until someone tried to import on the phone.
    //
    // Line comments, not a block comment: an image MIME type written out in a
    // block comment contains the characters that end one, which silently turned
    // the rest of this file into garbage and failed the build.
    private fun buildChooserIntent(params: WebChromeClient.FileChooserParams?): Intent {
        val accept = params?.acceptTypes?.filter { it.isNotBlank() } ?: emptyList()
        val wantsImage = accept.isEmpty() || accept.any { it.startsWith("image/") }
        val multiple = params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE

        if (!wantsImage) {
            // Deliberately unfiltered. File managers report .csv and .json
            // inconsistently — often as text/plain or application/octet-stream —
            // and a picker filtered on the page's own accept string greys out
            // the very file the user opened it to select.
            return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
            }
        }

        val pick = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }

        val camera = try {
            val dir = File(cacheDir, "camera").apply { mkdirs() }
            val photo = File(dir, "shot_${System.currentTimeMillis()}.jpg")
            cameraOutputUri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", photo
            )
            Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, cameraOutputUri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        } catch (e: Exception) {
            cameraOutputUri = null
            null
        }

        return Intent.createChooser(pick, "Photo").apply {
            if (camera != null) putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(camera))
        }
    }

    private fun printPage() {
        val manager = getSystemService(PRINT_SERVICE) as PrintManager
        val adapter = web.createPrintDocumentAdapter("DeckLogReport")
        manager.print(
            "Deck Log Report",
            adapter,
            PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .build()
        )
    }

    /**
     * Everything this app saves goes into one folder of its own rather than
     * being scattered through Downloads among everything else the phone has
     * ever fetched. On Android 10 and later that is a RELATIVE_PATH on the
     * MediaStore entry; before that it is a real directory.
     */
    private val SAVE_DIR = "GAS PLANET Deck Log"

    /** Writes a data: URL handed over from the page into that folder. */
    private fun saveDownload(dataUrl: String, filename: String) {
        try {
            val comma = dataUrl.indexOf(',')
            if (comma < 0) throw IllegalArgumentException("malformed data url")
            val header = dataUrl.substring(0, comma)
            val bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
            val mime = header.removePrefix("data:").substringBefore(';').ifBlank { "application/octet-stream" }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/" + SAVE_DIR)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("could not create the file")
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    SAVE_DIR
                )
                dir.mkdirs()
                FileOutputStream(File(dir, filename)).use { it.write(bytes) }
            }
            toast("Saved to Downloads/$SAVE_DIR: $filename")
        } catch (e: Exception) {
            toast("Could not save $filename: ${e.message}")
        }
    }

    private fun toast(msg: String) = runOnUiThread {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun saveFile(dataUrl: String, filename: String) = saveDownload(dataUrl, filename)

        @JavascriptInterface
        fun print() = runOnUiThread { printPage() }

        /**
         * The page hands over the agenda whenever anything changes. This is the
         * only route the home-screen widgets have to the data, so it is stored
         * and both widgets are redrawn. The agenda JSON also carries the
         * reminder's own settings (Settings screen -> "notify") -- there is no
         * separate bridge call for it, for the same reason: this is the one
         * channel from the page to here, so everything rides on it.
         *
         * Called on a WebView JavaScript thread, not the UI thread.
         */
        @JavascriptInterface
        fun publishAgenda(json: String) {
            AgendaStore.save(applicationContext, json)
            runOnUiThread {
                TodayWidget.refreshAll(applicationContext)
                MonthWidget.refreshAll(applicationContext)
            }
            try {
                val notify = JSONObject(json).optJSONObject("notify")
                if (notify != null) {
                    NotificationHelper.applySettings(
                        applicationContext,
                        notify.optBoolean("enabled", true),
                        notify.optString("time", "05:30")
                    )
                }
            } catch (e: Exception) {
                // Malformed or missing -- leave whatever was set before alone.
            }
            // A phone that was off at the configured time still gets today's
            // reminder once it is back on and the app happens to be opened,
            // rather than waiting for tomorrow's alarm.
            NotificationHelper.catchUpIfDue(applicationContext)
        }

        /** Ticks made on the widget while the app was closed. */
        @JavascriptInterface
        fun pendingTicks(): String = AgendaStore.pendingTicks(applicationContext)

        @JavascriptInterface
        fun clearTicks() = AgendaStore.clearTicks(applicationContext)

        /** Settings' battery-optimization note reads this to decide whether
            to show itself at all -- nothing to do once the phone has already
            excused the app. */
        @JavascriptInterface
        fun isBatteryOptimizationIgnored(): Boolean = isIgnoringBatteryOptimizations()

        @JavascriptInterface
        fun requestIgnoreBatteryOptimizations() = runOnUiThread { this@MainActivity.requestIgnoreBatteryOptimizations() }

        @JavascriptInterface
        fun sendTestNotification() = runOnUiThread { NotificationHelper.sendTest(applicationContext) }
    }

    companion object {
        /**
         * The page builds a Blob and clicks an <a download>, which a WebView
         * ignores. This catches that click in the capture phase, reads the blob
         * back as a data: URL and hands it to Kotlin — so every CSV keeps
         * working with no change to the page itself. window.print() is likewise
         * routed to Android's print service.
         */
        private const val BRIDGE_JS = """
        (function(){
          if (window.__deckLogBridge) return;
          window.__deckLogBridge = true;

          document.addEventListener('click', function(e){
            var a = e.target && e.target.closest ? e.target.closest('a[download]') : null;
            if (!a || !a.href) return;
            e.preventDefault();
            e.stopPropagation();
            var name = a.getAttribute('download') || 'download';
            fetch(a.href).then(function(r){ return r.blob(); }).then(function(b){
              var fr = new FileReader();
              fr.onloadend = function(){ AndroidBridge.saveFile(fr.result, name); };
              fr.readAsDataURL(b);
            }).catch(function(err){ alert('Could not save the file: ' + err); });
          }, true);

          window.print = function(){ AndroidBridge.print(); };
        })();
        """
    }
}
