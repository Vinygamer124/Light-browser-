package com.hiep.lightbrowser

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.util.Patterns
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

class Tab(val id: Int) {
    var web: WebView? = null
    var title: String = ""
    var url: String = ""
    var pendingUrl: String? = null
    var playing = false
    var loading = false
    var progress = 0
    var closeOnBack = false
}

class MainActivity : AppCompatActivity() {

    companion object {
        private const val HOME = "https://www.google.com"
        private const val PREFS = "lb_tabs"
        private const val KEY_URLS = "urls"
        private const val KEY_INDEX = "index"
        const val ACTION_TOGGLE = "com.hiep.lightbrowser.TOGGLE"
        const val ACTION_CLOSE = "com.hiep.lightbrowser.CLOSE"

        /** Chạy ở mọi trang: giả lập trang luôn hiển thị để video không tự dừng, và báo trạng thái phát. */
        private const val BG_SCRIPT = """
(function(){
if(window.__lbBg){return;}
window.__lbBg=true;
function def(o,k,v){try{Object.defineProperty(o,k,{get:function(){return v;},configurable:true});}catch(e){}}
def(document,'hidden',false);
def(document,'webkitHidden',false);
def(document,'visibilityState','visible');
def(document,'webkitVisibilityState','visible');
function stop(e){e.stopImmediatePropagation();}
document.addEventListener('visibilitychange',stop,true);
document.addEventListener('webkitvisibilitychange',stop,true);
function report(){
var m=document.querySelectorAll('video,audio'),p=false,i;
for(i=0;i<m.length;i++){if(!m[i].paused&&!m[i].ended){p=true;break;}}
try{window.LB.onMedia(p);}catch(e){}
}
['play','playing','pause','ended','emptied','abort'].forEach(function(n){document.addEventListener(n,report,true);});
})();
"""

        private const val TOGGLE_JS = """
(function(){
var m=document.querySelectorAll('video,audio'),p=false,i;
for(i=0;i<m.length;i++){if(!m[i].paused&&!m[i].ended){p=true;}}
if(p){for(i=0;i<m.length;i++){m[i].pause();}}
else{for(i=0;i<m.length;i++){if(m[i].readyState>0){m[i].play();break;}}}
})();
"""

        private const val PAUSE_JS = """
(function(){
var m=document.querySelectorAll('video,audio'),i;
for(i=0;i<m.length;i++){m[i].pause();}
})();
"""
    }

    private val tabs = mutableListOf<Tab>()
    private var current: Tab? = null
    private var nextId = 1

    private lateinit var container: FrameLayout
    private lateinit var urlBar: EditText
    private lateinit var pill: View
    private lateinit var progress: ProgressBar
    private lateinit var btnReload: ImageView
    private lateinit var btnBack: ImageView
    private lateinit var btnForward: ImageView
    private lateinit var btnHome: ImageView
    private lateinit var btnShare: ImageView
    private lateinit var btnTabs: View
    private lateinit var tabCount: TextView
    private lateinit var overlay: View
    private lateinit var tabList: LinearLayout
    private lateinit var btnNewTab: View
    private lateinit var btnDone: View
    private lateinit var fullscreenHost: FrameLayout

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var loadingIcon = false
    private var serviceOn = false
    private var askedNotif = false
    private var lastMediaTitle = ""

    private val curWeb: WebView? get() = current?.web

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val mediaReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_TOGGLE -> toggleMedia()
                ACTION_CLOSE -> closeMedia()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        container = findViewById(R.id.webContainer)
        urlBar = findViewById(R.id.urlBar)
        pill = findViewById(R.id.addressPill)
        progress = findViewById(R.id.progress)
        btnReload = findViewById(R.id.btnReload)
        btnBack = findViewById(R.id.btnBack)
        btnForward = findViewById(R.id.btnForward)
        btnHome = findViewById(R.id.btnHome)
        btnShare = findViewById(R.id.btnShare)
        btnTabs = findViewById(R.id.btnTabs)
        tabCount = findViewById(R.id.tabCount)
        overlay = findViewById(R.id.tabsOverlay)
        tabList = findViewById(R.id.tabList)
        btnNewTab = findViewById(R.id.btnNewTab)
        btnDone = findViewById(R.id.btnDone)
        fullscreenHost = findViewById(R.id.fullscreenHost)

        setupUi()

        val filter = IntentFilter().apply {
            addAction(ACTION_TOGGLE)
            addAction(ACTION_CLOSE)
        }
        ContextCompat.registerReceiver(
            this, mediaReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )

        restoreTabs()
        if (savedInstanceState == null) handleIntent(intent)
        if (tabs.isEmpty()) openTab(HOME)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val t = current
                when {
                    customView != null -> exitFullscreen()
                    overlay.visibility == View.VISIBLE -> hideTabs()
                    urlBar.hasFocus() -> {
                        urlBar.clearFocus()
                        hideKeyboard()
                    }
                    curWeb?.canGoBack() == true -> curWeb?.goBack()
                    t != null && t.closeOnBack && tabs.size > 1 -> closeTab(t)
                    tabs.any { it.playing } -> moveTaskToBack(true)
                    else -> finish()
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (serviceOn && tabs.none { it.playing }) {
            MediaService.stop(this)
            serviceOn = false
        }
    }

    override fun onStop() {
        saveTabs()
        super.onStop()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(mediaReceiver)
        } catch (_: Exception) {
        }
        MediaService.stop(this)
        for (t in tabs) {
            t.web?.let {
                container.removeView(it)
                it.destroy()
            }
        }
        super.onDestroy()
    }

    // ---------------- Intent từ app khác ----------------

    private fun handleIntent(i: Intent?) {
        val url = urlFromIntent(i) ?: return
        openTab(url, true)
    }

    private fun urlFromIntent(i: Intent?): String? {
        if (i == null) return null
        return when (i.action) {
            Intent.ACTION_VIEW -> i.dataString
            Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)?.let { extractUrl(it) }
            Intent.ACTION_WEB_SEARCH -> i.getStringExtra(SearchManager.QUERY)?.let { normalize(it) }
            else -> null
        }
    }

    private fun extractUrl(text: String): String {
        val m = Patterns.WEB_URL.matcher(text)
        if (m.find()) {
            val u = m.group()
            return if (u.contains("://")) u else "https://$u"
        }
        return normalize(text)
    }

    // ---------------- Quản lý thẻ ----------------

    private fun openTab(url: String?, closeOnBack: Boolean = false): Tab {
        val t = Tab(nextId++)
        t.closeOnBack = closeOnBack
        if (url != null) {
            t.pendingUrl = url
            t.url = url
        }
        tabs.add(t)
        switchTo(t)
        return t
    }

    private fun switchTo(t: Tab) {
        if (current !== t) current?.web?.visibility = View.GONE
        current = t
        val w = ensureWeb(t)
        w.visibility = View.VISIBLE
        t.pendingUrl?.let {
            t.pendingUrl = null
            w.loadUrl(it)
        }
        refreshUi()
    }

    private fun closeTab(t: Tab) {
        val idx = tabs.indexOf(t)
        if (idx < 0) return
        t.web?.let {
            it.stopLoading()
            container.removeView(it)
            it.destroy()
        }
        t.web = null
        tabs.remove(t)
        t.playing = false
        if (serviceOn && tabs.none { it.playing }) {
            MediaService.stop(this)
            serviceOn = false
        }
        if (tabs.isEmpty()) {
            current = null
            openTab(HOME)
            return
        }
        if (t === current) {
            current = null
            switchTo(tabs[minOf(idx, tabs.size - 1)])
        } else {
            refreshUi()
        }
    }

    private fun showTabs() {
        hideKeyboard()
        rebuildTabList()
        overlay.visibility = View.VISIBLE
    }

    private fun hideTabs() {
        overlay.visibility = View.GONE
    }

    private fun hostOf(url: String): String =
        Uri.parse(url).host?.removePrefix("www.") ?: url

    private fun rebuildTabList() {
        tabList.removeAllViews()
        for (t in tabs.toList()) {
            val v = layoutInflater.inflate(R.layout.item_tab, tabList, false)
            val link = t.web?.url ?: t.url
            v.findViewById<TextView>(R.id.tabTitle).text =
                t.title.ifBlank { if (link.isBlank()) getString(R.string.blank_tab) else hostOf(link) }
            v.findViewById<TextView>(R.id.tabUrl).text = link
            v.findViewById<View>(R.id.tabPlaying).visibility =
                if (t.playing) View.VISIBLE else View.GONE
            v.isActivated = t === current
            v.pressable {
                switchTo(t)
                hideTabs()
            }
            v.findViewById<View>(R.id.tabClose).pressable {
                closeTab(t)
                rebuildTabList()
            }
            tabList.addView(v)
        }
    }

    private fun saveTabs() {
        val pairs = tabs.map { it to (it.web?.url ?: it.pendingUrl ?: it.url) }
            .filter { it.second.startsWith("http") }
        val idx = pairs.indexOfFirst { it.first === current }.coerceAtLeast(0)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_URLS, pairs.joinToString("\n") { it.second })
            .putInt(KEY_INDEX, idx)
            .apply()
    }

    private fun restoreTabs() {
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        val urls = (p.getString(KEY_URLS, "") ?: "").split("\n").filter { it.isNotBlank() }
        if (urls.isEmpty()) return
        for (u in urls) {
            val t = Tab(nextId++)
            t.pendingUrl = u
            t.url = u
            tabs.add(t)
        }
        switchTo(tabs[p.getInt(KEY_INDEX, 0).coerceIn(0, tabs.size - 1)])
    }

    // ---------------- Giao diện ----------------

    private fun setupUi() {
        btnReload.pressable {
            val t = current ?: return@pressable
            if (t.loading) t.web?.stopLoading() else t.web?.reload()
        }
        btnBack.pressable { if (curWeb?.canGoBack() == true) curWeb?.goBack() }
        btnForward.pressable { if (curWeb?.canGoForward() == true) curWeb?.goForward() }
        btnHome.pressable { curWeb?.loadUrl(HOME) }
        btnTabs.pressable { showTabs() }
        btnShare.pressable {
            val link = curWeb?.url ?: return@pressable
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, link)
            }
            startActivity(Intent.createChooser(send, null))
        }
        btnNewTab.pressable {
            openTab(HOME)
            hideTabs()
        }
        btnDone.pressable { hideTabs() }

        urlBar.setOnFocusChangeListener { _, hasFocus ->
            pill.isActivated = hasFocus
            if (!hasFocus) urlBar.setText(curWeb?.url ?: current?.url ?: "")
        }
        urlBar.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                go()
                true
            } else false
        }
    }

    /** Hiệu ứng ấn nhẹ: thu nhỏ một chút khi chạm, trả về khi nhả. */
    @SuppressLint("ClickableViewAccessibility")
    private fun View.pressable(onClick: () -> Unit) {
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            false
        }
        setOnClickListener { onClick() }
    }

    private fun refreshUi() {
        val t = current ?: return
        if (!urlBar.hasFocus()) urlBar.setText(t.web?.url ?: t.url)
        progress.progress = t.progress
        progress.visibility = if (t.loading) View.VISIBLE else View.INVISIBLE
        setLoadingIcon(t.loading)
        updateNav()
        tabCount.text = tabs.size.toString()
    }

    private fun updateNav() {
        btnBack.alpha = if (curWeb?.canGoBack() == true) 1f else 0.3f
        btnForward.alpha = if (curWeb?.canGoForward() == true) 1f else 0.3f
    }

    private fun setLoadingIcon(value: Boolean) {
        if (loadingIcon == value) return
        loadingIcon = value
        btnReload.setImageResource(if (value) R.drawable.ic_close else R.drawable.ic_reload)
    }

    private fun go() {
        val url = normalize(urlBar.text.toString())
        urlBar.clearFocus()
        hideKeyboard()
        curWeb?.loadUrl(url)
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

    // ---------------- Video toàn màn hình ----------------

    private fun enterFullscreen(view: View, cb: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            cb.onCustomViewHidden()
            return
        }
        customView = view
        customCallback = cb
        fullscreenHost.addView(
            view,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        fullscreenHost.visibility = View.VISIBLE
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun exitFullscreen() {
        val v = customView ?: return
        fullscreenHost.removeView(v)
        fullscreenHost.visibility = View.GONE
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
        WindowCompat.getInsetsController(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    // ---------------- Phát nền ----------------

    inner class MediaBridge(private val tab: Tab) {
        @JavascriptInterface
        fun onMedia(playing: Boolean) {
            runOnUiThread { setPlaying(tab, playing) }
        }
    }

    private fun setPlaying(t: Tab, playing: Boolean) {
        if (t.playing == playing) return
        t.playing = playing
        if (playing) {
            lastMediaTitle = t.title.ifBlank { hostOf(t.web?.url ?: t.url) }
            if (Build.VERSION.SDK_INT >= 33 && !askedNotif &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                askedNotif = true
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            MediaService.update(this, lastMediaTitle, true)
            serviceOn = true
        } else if (serviceOn && tabs.none { it.playing }) {
            MediaService.update(this, lastMediaTitle, false)
        }
        if (overlay.visibility == View.VISIBLE) rebuildTabList()
    }

    private fun toggleMedia() {
        val target = tabs.firstOrNull { it.playing } ?: current
        target?.web?.evaluateJavascript(TOGGLE_JS, null)
    }

    private fun closeMedia() {
        for (t in tabs) {
            t.web?.evaluateJavascript(PAUSE_JS, null)
            t.playing = false
        }
        MediaService.stop(this)
        serviceOn = false
        if (overlay.visibility == View.VISIBLE) rebuildTabList()
    }

    // ---------------- WebView của từng thẻ ----------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWeb(t: Tab): WebView {
        t.web?.let { return it }
        val w = WebView(this)
        w.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        w.visibility = View.GONE
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            allowContentAccess = true
            allowFileAccess = true
            setSupportMultipleWindows(true)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.addJavascriptInterface(MediaBridge(t), "LB")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(w, BG_SCRIPT, setOf("*"))
        }

        w.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                view?.evaluateJavascript(BG_SCRIPT, null)
                t.url = url ?: ""
                t.loading = true
                if (t === current) {
                    if (!urlBar.hasFocus()) urlBar.setText(url ?: "")
                    setLoadingIcon(true)
                    updateNav()
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript(BG_SCRIPT, null)
                t.loading = false
                t.title = view?.title ?: t.title
                if (t === current) {
                    setLoadingIcon(false)
                    updateNav()
                }
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                t.url = url ?: t.url
                if (t === current) {
                    if (!urlBar.hasFocus()) urlBar.setText(url ?: "")
                    updateNav()
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                return when (uri.scheme) {
                    "http", "https", "about", "data", "javascript", "blob", "file", "content" -> false
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
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request == null || !request.isForMainFrame) return
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
                view?.loadDataWithBaseURL(base, html, "text/html", "utf-8", base)
            }
        }

        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                t.progress = newProgress
                t.loading = newProgress < 100
                if (t === current) {
                    progress.progress = newProgress
                    progress.visibility = if (t.loading) View.VISIBLE else View.INVISIBLE
                    setLoadingIcon(t.loading)
                }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                t.title = title ?: ""
                if (t.playing && serviceOn && t.title.isNotBlank()) {
                    lastMediaTitle = t.title
                    MediaService.update(this@MainActivity, lastMediaTitle, true)
                }
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val msg = resultMsg ?: return false
                val transport = msg.obj as? WebView.WebViewTransport ?: return false
                val nt = openTab(null, true)
                transport.webView = nt.web
                msg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView?) {
                tabs.firstOrNull { it.web === window }?.let { closeTab(it) }
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null || callback == null) return
                enterFullscreen(view, callback)
            }

            override fun onHideCustomView() {
                exitFullscreen()
            }
        }

        w.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
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

        container.addView(w)
        t.web = w
        return w
    }
}
