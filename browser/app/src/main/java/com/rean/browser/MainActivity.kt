package com.rean.browser

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {

    private class Tab(val webView: WebView, val incognito: Boolean) {
        var title: String = "New tab"
        var url: String = ""
    }

    private lateinit var store: BrowserStore
    private val tabs = mutableListOf<Tab>()
    private var current = -1

    private lateinit var root: LinearLayout
    private lateinit var urlBar: EditText
    private lateinit var urlBackground: GradientDrawable
    private lateinit var progress: ProgressBar
    private lateinit var content: FrameLayout
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var btnBack: TextView
    private lateinit var btnForward: TextView
    private lateinit var btnTabs: TextView
    private val chromeTexts = mutableListOf<TextView>()

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = BrowserStore(this)
        buildUi()
        applyTheme()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val t = currentTab()
                when {
                    t != null && t.webView.canGoBack() -> t.webView.goBack()
                    tabs.size > 1 -> closeTab(current)
                    else -> moveTaskToBack(true) // minimise instead of closing, like CallerX
                }
            }
        })

        handleIntent(intent, fromNew = savedInstanceState == null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent, fromNew = false)
    }

    override fun onPause() {
        super.onPause()
        tabs.forEach { it.webView.onPause() }
    }

    override fun onResume() {
        super.onResume()
        tabs.forEach { it.webView.onResume() }
    }

    override fun onDestroy() {
        tabs.forEach { it.webView.destroy() }
        if (tabs.any { it.incognito }) clearPrivateData()
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?, fromNew: Boolean) {
        val viewUrl = if (intent?.action == Intent.ACTION_VIEW) intent.dataString else null
        when {
            viewUrl != null -> newTab(viewUrl)
            fromNew -> newTab(null)
        }
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
        }

        urlBackground = GradientDrawable().apply { cornerRadius = dp(20).toFloat() }
        urlBar = EditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            hint = "Search or type a URL"
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = urlBackground
            setOnEditorActionListener { _, actionId, event ->
                val enterKey = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_GO || enterKey) {
                    navigate(text.toString())
                    hideKeyboard()
                    true
                } else {
                    false
                }
            }
            setOnFocusChangeListener { _, hasFocus -> if (hasFocus) selectAll() }
        }

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            addView(urlBar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(chromeButton("⋮") { showMenu() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        }

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }

        content = FrameLayout(this)

        btnBack = chromeButton("←") { currentTab()?.webView?.goBack() }
        btnForward = chromeButton("→") { currentTab()?.webView?.goForward() }
        btnTabs = chromeButton("1") { showTabs() }
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(btnBack, weighted())
            addView(btnForward, weighted())
            addView(chromeButton("⌂") { navigate(store.homeUrl) }, weighted())
            addView(chromeButton("⟳") { currentTab()?.webView?.reload() }, weighted())
            addView(btnTabs, weighted())
            addView(chromeButton("+") { newTab(null) }, weighted())
        }

        root.addView(topBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)))
        root.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(bottomBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        setContentView(root)
    }

    private fun chromeButton(label: String, action: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
            chromeTexts.add(this)
        }
    }

    private fun weighted() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    private fun applyTheme() {
        val dark = store.darkMode
        val bg = if (dark) 0xFF202124.toInt() else Color.WHITE
        val fg = if (dark) Color.WHITE else 0xFF202124.toInt()
        root.setBackgroundColor(bg)
        topBar.setBackgroundColor(bg)
        bottomBar.setBackgroundColor(bg)
        urlBackground.setColor(if (dark) 0xFF303134.toInt() else 0xFFF1F3F4.toInt())
        urlBar.setTextColor(fg)
        urlBar.setHintTextColor(0x88888888.toInt())
        chromeTexts.forEach { it.setTextColor(fg) }
        tabs.forEach { applyWebSettings(it.webView) }
        updateNav()
    }

    private fun applyWebSettings(wv: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(wv.settings, store.darkMode)
        }
    }

    private fun updateNav() {
        val t = currentTab()
        btnBack.alpha = if (t?.webView?.canGoBack() == true) 1f else 0.3f
        btnForward.alpha = if (t?.webView?.canGoForward() == true) 1f else 0.3f
        btnTabs.text = tabs.size.toString()
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlBar.windowToken, 0)
        urlBar.clearFocus()
    }

    // ------------------------------------------------------------------ tabs

    private fun currentTab(): Tab? = tabs.getOrNull(current)

    private fun currentUrl(): String? =
        currentTab()?.let { it.webView.url ?: it.url }?.takeIf { it.isNotBlank() }

    private fun newTab(url: String?, incognito: Boolean = false) {
        val wv = createWebView()
        addTab(wv, incognito)
        wv.loadUrl(normalize(url ?: store.homeUrl))
        urlBar.requestFocus()
        urlBar.setText("")
    }

    private fun addTab(wv: WebView, incognito: Boolean) {
        tabs.add(Tab(wv, incognito))
        content.addView(wv, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        selectTab(tabs.size - 1)
    }

    private fun selectTab(index: Int) {
        if (index !in tabs.indices) return
        current = index
        tabs.forEachIndexed { i, t -> t.webView.visibility = if (i == index) View.VISIBLE else View.GONE }
        val t = tabs[index]
        progress.visibility = View.GONE
        if (!urlBar.hasFocus()) urlBar.setText(t.webView.url ?: t.url)
        updateNav()
    }

    private fun closeTab(index: Int) {
        val tab = tabs.getOrNull(index) ?: return
        content.removeView(tab.webView)
        tab.webView.stopLoading()
        tab.webView.destroy()
        tabs.removeAt(index)
        if (tab.incognito && tabs.none { it.incognito }) clearPrivateData()
        if (tabs.isEmpty()) {
            newTab(null)
        } else {
            selectTab(minOf(index, tabs.size - 1))
        }
    }

    private fun tabFor(wv: WebView?): Tab? = tabs.firstOrNull { it.webView === wv }

    private fun showTabs() {
        val labels = Array<CharSequence>(tabs.size + 1) { i ->
            if (i == tabs.size) "＋ New tab"
            else {
                val t = tabs[i]
                val prefix = if (t.incognito) "🕶 " else if (i == current) "● " else "   "
                prefix + t.title.ifBlank { t.webView.url ?: "New tab" }
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Tabs (${tabs.size})")
            .setItems(labels) { _, which ->
                if (which == tabs.size) newTab(null) else selectTab(which)
            }
            .show()
    }

    // ------------------------------------------------------------------ navigation

    private fun navigate(input: String) {
        val t = currentTab() ?: return
        t.webView.loadUrl(normalize(input))
    }

    private fun openInCurrentTab(url: String) {
        navigate(url)
    }

    /** Turns free text into a URL: explicit URLs stay, domains get https, everything else searches. */
    private fun normalize(input: String): String {
        val s = input.trim()
        if (s.isEmpty()) return store.homeUrl
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://.*").matches(s) || s.startsWith("about:")) return s
        if (!s.contains(' ') && s.contains('.')) return "https://$s"
        return "https://duckduckgo.com/?q=" + Uri.encode(s)
    }

    // ------------------------------------------------------------------ WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val wv = WebView(this)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(true)
        }
        applyWebSettings(wv)

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                return when (uri.scheme) {
                    null, "http", "https", "about", "data", "blob", "file" -> false
                    else -> try {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                        true
                    } catch (e: ActivityNotFoundException) {
                        true
                    }
                }
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (request != null && store.adBlock && !request.isForMainFrame &&
                    AdBlocker.isBlocked(request.url?.host)) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                val tab = tabFor(view) ?: return
                tab.url = url ?: ""
                if (view === currentTab()?.webView) {
                    if (!urlBar.hasFocus()) urlBar.setText(url ?: "")
                    progress.progress = 0
                    progress.visibility = View.VISIBLE
                }
                updateNav()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val tab = tabFor(view) ?: return
                tab.url = url ?: tab.url
                tab.title = view?.title ?: tab.url
                if (view === currentTab()?.webView) progress.visibility = View.GONE
                if (!tab.incognito && !tab.url.startsWith("about:") && tab.url.isNotBlank()) {
                    store.addHistory(tab.title, tab.url)
                }
                updateNav()
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (view === currentTab()?.webView) {
                    progress.progress = newProgress
                    progress.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                tabFor(view)?.title = title ?: ""
            }

            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                if (resultMsg == null) return false
                // Block script-opened popups (typical ad behaviour) unless the user asked for it.
                if (!isUserGesture && store.adBlock) return false
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                val popup = createWebView()
                addTab(popup, incognito = false)
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }
        }

        wv.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                addRequestHeader("User-Agent", userAgent)
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                setMimeType(mimetype)
                setTitle(fileName)
                setDescription(url)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            toast("Downloading $fileName")
        }

        return wv
    }

    /** Clears cookies and web storage. Used when the last incognito tab closes. */
    private fun clearPrivateData() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
    }

    // ------------------------------------------------------------------ menus

    private fun showMenu() {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        fun add(label: String, action: () -> Unit) { actions.add(label to action) }

        add("New tab") { newTab(null) }
        add("New incognito tab") { newTab(null, incognito = true) }
        val url = currentUrl()
        val bookmarked = url != null && store.isBookmarked(url)
        add(if (bookmarked) "Remove bookmark" else "Bookmark this page") { toggleBookmark() }
        add("Bookmarks") { showBookmarks() }
        add("History") { showHistory() }
        add("Downloads") { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) }
        add("Share page") { sharePage() }
        add("Open in external browser") { openExternal() }
        add("Find on page") { findOnPage() }
        add(if (store.darkMode) "Dark mode: on" else "Dark mode: off") { toggleDarkMode() }
        add(if (store.adBlock) "Ad & tracker blocking: on" else "Ad & tracker blocking: off") { toggleAdBlock() }
        add("Set home page") { askHomePage() }
        add("Clear browsing history") { store.clearHistory(); toast("History cleared") }
        add("Clear cookies & site data") { clearPrivateData(); toast("Cookies cleared") }
        add("Close current tab") { closeTab(current) }

        val labels = Array<CharSequence>(actions.size) { actions[it].first }
        AlertDialog.Builder(this)
            .setItems(labels) { _, which -> actions[which].second() }
            .show()
    }

    private fun toggleDarkMode() {
        store.darkMode = !store.darkMode
        applyTheme()
    }

    private fun toggleAdBlock() {
        store.adBlock = !store.adBlock
        toast(if (store.adBlock) "Ad blocking on" else "Ad blocking off")
    }

    private fun toggleBookmark() {
        val url = currentUrl() ?: return
        if (store.isBookmarked(url)) {
            store.removeBookmark(url)
            toast("Bookmark removed")
        } else {
            val title = currentTab()?.webView?.title?.ifBlank { url } ?: url
            store.addBookmark(title, url)
            toast("Bookmarked")
        }
    }

    private fun showBookmarks() {
        val entries = store.bookmarks()
        if (entries.isEmpty()) {
            toast("No bookmarks yet")
            return
        }
        val labels = Array<CharSequence>(entries.size) { entries[it].title.ifBlank { entries[it].url } }
        AlertDialog.Builder(this)
            .setTitle("Bookmarks")
            .setItems(labels) { _, which -> openInCurrentTab(entries[which].url) }
            .show()
    }

    private fun showHistory() {
        val entries = store.history().take(300)
        if (entries.isEmpty()) {
            toast("History is empty")
            return
        }
        val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val labels = Array<CharSequence>(entries.size) { i ->
            val e = entries[i]
            (e.title.ifBlank { e.url }) + "\n" + fmt.format(Date(e.time))
        }
        AlertDialog.Builder(this)
            .setTitle("History")
            .setItems(labels) { _, which -> openInCurrentTab(entries[which].url) }
            .show()
    }

    private fun askHomePage() {
        val input = EditText(this).apply {
            setSingleLine(true)
            setText(store.homeUrl)
        }
        AlertDialog.Builder(this)
            .setTitle("Home page")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotEmpty()) {
                    store.homeUrl = normalize(value)
                    toast("Home page saved")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sharePage() {
        val url = currentUrl() ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        startActivity(Intent.createChooser(intent, "Share page"))
    }

    private fun openExternal() {
        val url = currentUrl() ?: return
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun findOnPage() {
        val wv = currentTab()?.webView ?: return
        val input = EditText(this).apply { setSingleLine(true) }
        AlertDialog.Builder(this)
            .setTitle("Find on page")
            .setView(input)
            .setPositiveButton("Find") { _, _ -> wv.findAllAsync(input.text.toString()) }
            .setNeutralButton("Close") { _, _ -> wv.clearMatches() }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
