package com.mykungfu.ledger

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
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
 * A shell around the Ledger page. The page itself is unchanged and lives in
 * assets, so it behaves exactly as it does in the browser; this class supplies
 * only the three things a bare WebView will not do:
 *
 *   - keep localStorage between launches, which is where all the data lives
 *   - turn the CSV and JSON "downloads" into real files in Downloads
 *   - open a file picker for restoring a backup
 *
 * Deliberately smaller than the deck log's shell: no camera, no location, no
 * widgets, no print. This app has no photographs and no map, and an app
 * holding somebody's salary and holdings should ask for nothing it does not
 * need.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooser =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val cb = filePathCallback ?: return@registerForActivityResult
            filePathCallback = null
            if (result.resultCode != Activity.RESULT_OK) {
                cb.onReceiveValue(null)
                return@registerForActivityResult
            }
            val uri = result.data?.data
            cb.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        web = WebView(this)
        setContentView(web)

        WebView.setWebContentsDebuggingEnabled(false)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true          // localStorage — the whole data store
            allowFileAccess = true
            allowContentAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
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
                    // Deliberately unfiltered. The only picker in this app is
                    // "restore a backup", and file managers report .csv and
                    // .json inconsistently — often as text/plain or
                    // application/octet-stream. A picker filtered on the page's
                    // own accept string greys out the very file the user opened
                    // it to select. The deck log learned this the hard way and
                    // it is written down in DECISIONS.md.
                    fileChooser.launch(
                        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            type = "*/*"
                            addCategory(Intent.CATEGORY_OPENABLE)
                        }
                    )
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
                if (web.canGoBack()) {
                    web.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        web.loadUrl("file:///android_asset/index.html")
    }

    /** Writes a data: URL handed over from the page into the Downloads folder. */
    private fun saveDownload(dataUrl: String, filename: String) {
        try {
            val comma = dataUrl.indexOf(',')
            if (comma < 0) throw IllegalArgumentException("malformed data url")
            val header = dataUrl.substring(0, comma)
            val bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
            val mime = header.removePrefix("data:").substringBefore(';')
                .ifBlank { "application/octet-stream" }

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
                val dir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )
                dir.mkdirs()
                FileOutputStream(File(dir, filename)).use { it.write(bytes) }
            }
            toast("Saved to Downloads: $filename")
        } catch (e: Exception) {
            // The export IS the backup in this app, so a silent failure here is
            // the worst kind. Say so on the screen.
            toast("Could not save $filename: ${e.message}")
        }
    }

    private fun toast(msg: String) = runOnUiThread {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun saveFile(dataUrl: String, filename: String) = saveDownload(dataUrl, filename)
    }

    companion object {
        /**
         * The page builds a Blob and clicks an <a download>, which a WebView
         * ignores completely — no error, no file, nothing. This catches that
         * click in the capture phase, reads the blob back as a data: URL and
         * hands it to Kotlin, so both exports keep working with no change to
         * the page itself.
         *
         * The page revokes its blob URL on a timer after the click; the fetch
         * here starts immediately, so it is always well inside that window.
         */
        private const val BRIDGE_JS = """
        (function(){
          if (window.__ledgerBridge) return;
          window.__ledgerBridge = true;

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
        })();
        """
    }
}
