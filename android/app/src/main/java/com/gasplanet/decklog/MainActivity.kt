package com.gasplanet.decklog

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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

        web.loadUrl("file:///android_asset/index.html")
    }

    /**
     * Builds the picker the page actually asked for.
     *
     * This used to hardcode "image/*" and ignore the accept types altogether,
     * so every file input in the app opened a photo gallery — which meant none
     * of the four data imports (three CSV and the instrument JSON) could ever
     * pick their file on Android. Photos worked, so nothing looked broken.
     */
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

    /** Writes a data: URL handed over from the page into the Downloads folder. */
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
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                FileOutputStream(File(dir, filename)).use { it.write(bytes) }
            }
            toast("Saved to Downloads: $filename")
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
