package com.jfdedit3.tete

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = filePathCallback
            filePathCallback = null

            if (callback == null) {
                return@registerForActivityResult
            }

            if (result.resultCode != RESULT_OK) {
                callback.onReceiveValue(null)
                return@registerForActivityResult
            }

            callback.onReceiveValue(extractUris(result.data))
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)

        configureWebView()

        onBackPressedDispatcher.addCallback(this) {
            if (webView.canGoBack()) {
                webView.goBack()
            } else {
                finish()
            }
        }

        if (savedInstanceState == null) {
            webView.loadUrl(HOME_URL)
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    private fun configureWebView() {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.addJavascriptInterface(ClipboardBridge(this), "AndroidClipboard")

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            loadsImagesAutomatically = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = "$userAgentString TeteAndroid/1.0"
        }

        webView.webViewClient =
            object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    val uri = request?.url ?: return false
                    val scheme = uri.scheme?.lowercase().orEmpty()

                    return when (scheme) {
                        "http", "https" -> false
                        else -> {
                            openExternal(uri)
                            true
                        }
                    }
                }

                override fun onPageFinished(
                    view: WebView?,
                    url: String?,
                ) {
                    super.onPageFinished(view, url)
                    injectClipboardBridge()
                    progressBar.isVisible = false
                }
            }

        webView.webChromeClient =
            object : WebChromeClient() {
                override fun onProgressChanged(
                    view: WebView?,
                    newProgress: Int,
                ) {
                    super.onProgressChanged(view, newProgress)
                    progressBar.isVisible = newProgress < 100
                    progressBar.progress = newProgress
                }

                override fun onPermissionRequest(request: PermissionRequest?) {
                    request?.grant(request.resources)
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?,
                ): Boolean {
                    if (filePathCallback == null) {
                        return false
                    }

                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = filePathCallback

                    return try {
                        filePickerLauncher.launch(createFileChooserIntent(fileChooserParams))
                        true
                    } catch (_: ActivityNotFoundException) {
                        this@MainActivity.filePathCallback = null
                        filePathCallback.onReceiveValue(null)
                        false
                    }
                }
            }
    }

    private fun createFileChooserIntent(params: WebChromeClient.FileChooserParams?): Intent {
        val parsedTypes =
            params
                ?.acceptTypes
                ?.flatMap { entry -> entry.split(",") }
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.distinct()
                .orEmpty()

        val pickerIntent =
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(
                    Intent.EXTRA_ALLOW_MULTIPLE,
                    params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE,
                )

                if (parsedTypes.isEmpty() || parsedTypes.contains("*/*")) {
                    type = "*/*"
                } else if (parsedTypes.size == 1) {
                    type = parsedTypes.first()
                } else {
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, parsedTypes.toTypedArray())
                }
            }

        return Intent(Intent.ACTION_CHOOSER).apply {
            putExtra(Intent.EXTRA_INTENT, pickerIntent)
            putExtra(Intent.EXTRA_TITLE, getString(R.string.choose_file))
        }
    }

    private fun extractUris(data: Intent?): Array<Uri>? {
        if (data == null) return null

        val clipData: ClipData? = data.clipData
        if (clipData != null) {
            val uris = ArrayList<Uri>(clipData.itemCount)
            for (index in 0 until clipData.itemCount) {
                clipData.getItemAt(index).uri?.let(uris::add)
            }
            return uris.toTypedArray()
        }

        data.data?.let { singleUri ->
            return arrayOf(singleUri)
        }

        return null
    }

    private fun openExternal(uri: Uri) {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, uri),
            )
        }
    }

    private fun injectClipboardBridge() {
        webView.evaluateJavascript(
            """
            (function () {
              if (window.__teteClipboardInjected) return;
              window.__teteClipboardInjected = true;

              if (!window.AndroidClipboard) return;

              const clipboard = {
                writeText: async function(text) {
                  window.AndroidClipboard.writeText(String(text ?? ""));
                },
                readText: async function() {
                  return window.AndroidClipboard.readText();
                }
              };

              try {
                Object.defineProperty(navigator, "clipboard", {
                  value: clipboard,
                  configurable: true
                });
              } catch (e) {
                try {
                  navigator.clipboard = clipboard;
                } catch (_) {}
              }
            })();
            """.trimIndent(),
            null,
        )
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        webView.removeJavascriptInterface("AndroidClipboard")
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val HOME_URL = "https://x.com"
    }
}
