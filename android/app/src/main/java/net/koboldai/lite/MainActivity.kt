package net.koboldai.lite

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader

    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermissionRequest: PermissionRequest? = null

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris: Array<Uri>? = if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            when {
                data?.clipData != null -> {
                    val count = data.clipData!!.itemCount
                    Array(count) { i -> data.clipData!!.getItemAt(i).uri }
                }
                data?.data != null -> arrayOf(data.data!!)
                else -> null
            }
        } else {
            null
        }
        fileChooserCallback?.onReceiveValue(uris)
        fileChooserCallback = null
    }

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            pendingPermissionRequest?.let { it.grant(it.resources) }
        } else {
            pendingPermissionRequest?.deny()
        }
        pendingPermissionRequest = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set status bar & window background
        window.statusBarColor = Color.parseColor("#1A232F")
        window.navigationBarColor = Color.parseColor("#1A232F")

        val rootLayout = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#1A232F"))
        }

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#1A232F"))
        }
        rootLayout.addView(webView)
        setContentView(rootLayout)

        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        configureWebView()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(LOCAL_INDEX_URL)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.allowFileAccessFromFileURLs = true
        settings.allowUniversalAccessFromFileURLs = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        webView.addJavascriptInterface(WebAppInterface(this), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url ?: return null
                if (url.host.equals(ASSET_HOST, ignoreCase = true)) {
                    return assetLoader.shouldInterceptRequest(url)
                }
                return null
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url ?: return false
                if (url.host.equals(ASSET_HOST, ignoreCase = true)) {
                    return false
                }
                try {
                    val intent = Intent(Intent.ACTION_VIEW, url)
                    startActivity(intent)
                    return true
                } catch (e: Exception) {
                    Log.e(TAG, "Cannot launch external URL: $url", e)
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectDownloadBridge(view)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                wv: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback

                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                return try {
                    filePickerLauncher.launch(intent)
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to launch file picker", e)
                    fileChooserCallback?.onReceiveValue(null)
                    fileChooserCallback = null
                    false
                }
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.let {
                    if (it.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                        if (ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            it.grant(it.resources)
                        } else {
                            pendingPermissionRequest = it
                            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    } else {
                        it.grant(it.resources)
                    }
                }
            }

            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("KoboldAI Lite")
                    .setMessage(message ?: "")
                    .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("KoboldAI Lite")
                    .setMessage(message ?: "")
                    .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: JsPromptResult?
            ): Boolean {
                val input = EditText(this@MainActivity).apply {
                    setText(defaultValue ?: "")
                }
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("KoboldAI Lite")
                    .setMessage(message ?: "")
                    .setView(input)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        result?.confirm(input.text.toString())
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ ->
                        result?.cancel()
                    }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }
        }
    }

    private fun injectDownloadBridge(view: WebView?) {
        val script = """
            (function() {
                if (window.__koboldAndroidBridgeInstalled) return;
                window.__koboldAndroidBridgeInstalled = true;

                const originalClick = HTMLAnchorElement.prototype.click;
                HTMLAnchorElement.prototype.click = function() {
                    const href = this.href || '';
                    const download = this.download || this.getAttribute('download');
                    if (download && (href.startsWith('blob:') || href.startsWith('data:'))) {
                        try {
                            const filename = download || 'download';
                            fetch(href)
                                .then(r => r.blob())
                                .then(b => {
                                    const reader = new FileReader();
                                    reader.onloadend = function() {
                                        const result = reader.result || '';
                                        const base64Data = result.indexOf(',') >= 0 ? result.split(',')[1] : result;
                                        const mimeType = b.type || 'application/octet-stream';
                                        if (window.AndroidBridge && window.AndroidBridge.saveBlobFile) {
                                            window.AndroidBridge.saveBlobFile(base64Data, filename, mimeType);
                                        }
                                    };
                                    reader.readAsDataURL(b);
                                })
                                .catch(e => {
                                    console.error('Android bridge error', e);
                                    originalClick.apply(this);
                                });
                            return;
                        } catch(e) {
                            console.error('Bridge exception', e);
                        }
                    }
                    originalClick.apply(this);
                };
            })();
        """.trimIndent()
        view?.evaluateJavascript(script, null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "KoboldMainActivity"
        private const val ASSET_HOST = "appassets.androidplatform.net"
        private const val LOCAL_INDEX_URL = "https://$ASSET_HOST/assets/web/index.html"
    }
}
