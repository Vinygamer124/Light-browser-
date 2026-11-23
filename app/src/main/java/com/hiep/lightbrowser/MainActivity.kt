package com.hiep.lightbrowser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Patterns
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val HOME = "https://www.google.com"
    }

    private lateinit var web: WebView
    private lateinit var urlBar: EditText
    private lateinit var pill: View
    private lateinit var progress: ProgressBar
    private lateinit var btnReload: ImageView
    private lateinit var btnBack: ImageView
    private lateinit var btnForward: ImageView
    private lateinit var btnHome: ImageView
    private lateinit var btnShare: ImageView

    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        web = findViewById(R.id.webView)
        urlBar = findViewById(R.id.urlBar)
        pill = findViewById(R.id.addressPill)
        progress = findViewById(R.id.progress)
        btnReload = findViewById(R.id.btnReload)
        btnBack = findViewById(R.id.btnBack)
        btnForward = findViewById(R.id.btnForward)
        btnHome = findViewById(R.id.btnHome)
        btnShare = findViewById(R.id.btnShare)

        setupWebView()
        setupUi()

        web.loadUrl(intent?.data?.toString() ?: HOME)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (urlBar.hasFocus()) {
                    urlBar.clearFocus()
                    hideKeyboard()
                } else if (web.canGoBack()) {
                    web.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { web.loadUrl(it.toString()) }
    }

    // ---------------- UI ----------------

    private fun setupUi() {
        btnReload.pressable {
            if (loading) web.stopLoading() else web.reload()
        }
        btnBack.pressable { if (web.canGoBack()) web.goBack() }
        btnForward.pressable { if (web.canGoForward()) web.goForward() }
        btnHome.pressable { web.loadUrl(HOME) }
        btnShare.pressable {
            val link = web.url ?: return@pressable
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, link)
            }
            startActivity(Intent.createChooser(send, null))
        }

        urlBar.setOnFocusChangeListener { _, hasFocus ->
            pill.isActivated = hasFocus
            if (!hasFocus) urlBar.setText(web.url ?: "")
        }
        urlBar.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                go()
                true
            } else false
        }
        updateNav()
    }

    /** Hiệu ứng ấn nhẹ: thu nhỏ một chút khi chạm, trả về khi nhả. */
    @SuppressLint("ClickableViewAccessibility")
    private fun View.pressable(onClick: () -> Unit) {
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            false
        }
        setOnClickListener { onClick() }
    }

    private fun updateNav() {
        btnBack.alpha = if (web.canGoBack()) 1f else 0.3f
        btnForward.alpha = if (web.canGoForward()) 1f else 0.3f
    }

    private fun setLoading(value: Boolean) {
        if (loading == value) return
        loading = value
        btnReload.setImageResource(if (value) R.drawable.ic_close else R.drawable.ic_reload)
    }

    private fun go() {
        val url = normalize(urlBar.text.toString())
        urlBar.clearFocus()
        hideKeyboard()
        web.loadUrl(url)
    }

    private fun normalize(input: String): String {
        val t = input.trim()
        if (t.isEmpty()) return HOME
        return if (Patterns.WEB_URL.matcher(t).matches()) {
            if (t.contains("://")) t else "https://$t"
        } else {
            "https://www.google.com/search?q=" + Uri.encode(t)
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlBar.windowToken, 0)
    }

    // ---------------- WebView ----------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                setLoading(true)
                if (!urlBar.hasFocus()) urlBar.setText(url ?: "")
                updateNav()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                setLoading(false)
                updateNav()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                if (!urlBar.hasFocus()) urlBar.setText(url ?: "")
                updateNav()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val uri = request.url
                return when (uri.scheme) {
                    "http", "https", "about", "data", "javascript", "blob" -> false
                    else -> {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, uri))
                        } catch (_: ActivityNotFoundException) {
                        }
                        true
                    }
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (!request.isForMainFrame) return
                val base = request.url.toString()
                val html = """
                    <html><head><meta name="viewport" content="width=device-width,initial-scale=1">
                    <style>
                    body{font-family:sans-serif;background:#fff;color:#111827;display:flex;
                    align-items:center;justify-content:center;height:100vh;margin:0;text-align:center}
                    h2{margin:0 0 8px;font-size:20px} p{margin:0;color:#6b7280;font-size:14px;padding:0 24px}
                    </style></head><body><div>
                    <h2>Không thể mở trang này</h2>
                    <p>Kiểm tra kết nối mạng rồi bấm nút tải lại.</p>
                    </div></body></html>
                """.trimIndent()
                view.loadDataWithBaseURL(base, html, "text/html", "utf-8", base)
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress < 100) View.VISIBLE else View.INVISIBLE
                setLoading(newProgress < 100)
            }
        }

        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val req = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                addRequestHeader("User-Agent", userAgent)
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    URLUtil.guessFileName(url, contentDisposition, mimeType)
                )
            }
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            Toast.makeText(this, R.string.downloading, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
