package com.abhiamu.portal

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * One open site. Each site runs as its own "document" in Android's Recents
 * (see manifest: documentLaunchMode=intoExisting), so switching between
 * Claude 1 / Claude 2 / anything else is just the normal app switcher, and
 * re-opening a site resumes it instead of reloading.
 *
 * Isolation: the WebView is bound to a named WebView Profile before it loads
 * anything. Profiles have separate cookies, localStorage, IndexedDB, cache and
 * service workers. That is what lets three Claude accounts coexist.
 */
class BrowserActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var site: Site

    private lateinit var toolbar: Toolbar
    private lateinit var container: FrameLayout
    private var popup: WebView? = null
    private var showBarButton: View? = null

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermission: PermissionRequest? = null

    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data))
        fileCallback = null
    }

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private var afterStorage: (() -> Unit)? = null
    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val a = afterStorage; afterStorage = null
        if (ok) a?.invoke()
        else Toast.makeText(this, "Storage permission is needed to save downloads on this Android version", Toast.LENGTH_LONG).show()
    }

    private var afterLocation: ((Boolean) -> Unit)? = null
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val a = afterLocation; afterLocation = null
        a?.invoke(res.values.any { it })
    }

    private val blobDownloads = mutableMapOf<String, BlobDownload>()
    private class BlobDownload(val file: java.io.File, var name: String, var mime: String?)

    private val mediaPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        grantPendingIfAllowed()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val id = intent.getStringExtra(EXTRA_SITE_ID) ?: intent.data?.lastPathSegment
        val found = id?.let { SiteStore.find(this, it) }
        if (found == null) { finishAndRemoveTask(); return }
        site = found

        if (site.profile != DEFAULT_PROFILE && !Profiles.supported()) {
            AlertDialog.Builder(this)
                .setTitle("Isolation not available")
                .setMessage("This phone's Android System WebView is too old to keep separate logins. " +
                        "Update \"Android System WebView\" from the Play Store, then try again.\n\n" +
                        "Not opening \"${site.name}\" so its login can't mix with another account.")
                .setPositiveButton("OK") { _, _ -> finishAndRemoveTask() }
                .setCancelable(false)
                .show()
            return
        }

        setContentView(R.layout.activity_browser)
        setTaskDescription(ActivityManager.TaskDescription("${site.name} · ${site.profile}"))

        toolbar = findViewById(R.id.browserToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = site.name
        supportActionBar?.subtitle = site.profile

        progressBar = findViewById(R.id.browserProgress)

        container = findViewById(R.id.webContainer)
        webView = newWebView(isPopup = false)
        container.addView(webView, MATCH)

        setupShowBarButton()
        setBarHidden(prefs().getBoolean(barKey(), false))

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val p = popup
                when {
                    customView != null -> exitCustomView()
                    p != null && p.canGoBack() -> p.goBack()
                    p != null -> closePopup()
                    webView.canGoBack() -> webView.goBack()
                    else -> moveTaskToBack(true)
                }
            }
        })

        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(site.url)
        }
    }

    /** Every WebView (main + sign-in popups) is bound to this site's profile before first use. */
    private fun newWebView(isPopup: Boolean): WebView {
        val wv = WebView(this)
        Profiles.bind(wv, site.profile)
        configure(wv, isPopup)
        // Channel for blob: downloads (Claude file exports etc.). Only accepts messages that
        // carry a one-time nonce Portal generated for a download the user just started.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(wv, "PortalDl", setOf("*")) { _, msg, _, _, _ ->
                msg.data?.let { onBlobChunk(it) }
            }
        }
        return wv
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(wv: WebView, isPopup: Boolean) {
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            // Hardening: page can't read app files or content providers.
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // Needed for "Sign in with Google" and other popup-based logins: the popup must
            // be a real child window with window.opener, or the final step lands on a blank page.
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = true
            safeBrowsingEnabled = true
            setGeolocationEnabled(true)
            // Keep WebView's own user agent. Rewriting it to look like Chrome made it
            // disagree with the client-hint headers WebView still sends ("Android WebView"),
            // and Google sign-in rejects that mismatch as an unsafe browser.
        }
        wv.overScrollMode = View.OVER_SCROLL_NEVER
        wv.setBackgroundColor(Color.TRANSPARENT)
        // Login flows (Google, SSO) use third-party cookies inside iframes.
        // Still scoped to this site's profile, so accounts never mix.
        Profiles.cookieManager(site.profile).apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(wv.settings, true)
        }
        // No addJavascriptInterface anywhere: pages get no bridge into the app.

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                return when (uri.scheme?.lowercase()) {
                    "https", "http" -> false               // stay inside, same profile
                    "intent", "mailto", "tel", "sms", "market", "upi", "whatsapp" -> {
                        launchExternal(uri); true
                    }
                    else -> true                           // block file:, content:, javascript:, etc.
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (isPopup) return
                supportActionBar?.subtitle = "${site.profile} · ${Uri.parse(url ?: "").host ?: ""}"
                tintFromPage()
            }

            // Single-page apps (claude.ai) change pages without onPageFinished.
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                if (!isPopup) tintFromPage()
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                if (isPopup) return false   // no popups-of-popups
                closePopup()
                val child = newWebView(isPopup = true)
                popup = child
                container.addView(child, MATCH)
                supportActionBar?.subtitle = "Signing in…"
                (resultMsg.obj as WebView.WebViewTransport).webView = child
                resultMsg.sendToTarget()
                return true
            }

            // Fired when the sign-in popup calls window.close() after handing the result back.
            override fun onCloseWindow(window: WebView) {
                if (window == popup) closePopup()
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: android.webkit.GeolocationPermissions.Callback) {
                AlertDialog.Builder(this@BrowserActivity)
                    .setTitle("Share location?")
                    .setMessage("${Uri.parse(origin).host ?: origin} wants to use your location.")
                    .setPositiveButton("Allow") { _, _ -> withLocation { ok -> callback.invoke(origin, ok, false) } }
                    .setNegativeButton("Block") { _, _ -> callback.invoke(origin, false, false) }
                    .setOnCancelListener { callback.invoke(origin, false, false) }
                    .show()
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) { callback.onCustomViewHidden(); return }
                customView = view; customViewCallback = callback
                view.setBackgroundColor(Color.BLACK)
                (window.decorView as FrameLayout).addView(view, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                    hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }

            override fun onHideCustomView() { exitCustomView() }

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                progressBar.progress = newProgress
            }

            override fun onShowFileChooser(
                view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    fileChooser.launch(params.createIntent().apply {
                        if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    })
                    true
                } catch (e: ActivityNotFoundException) {
                    fileCallback = null; false
                }
            }

            // Mic / camera (e.g. voice mode). Only for https pages, only after Android grants it.
            override fun onPermissionRequest(request: PermissionRequest) {
                if (request.origin.scheme != "https") { request.deny(); return }
                pendingPermission?.deny()
                pendingPermission = request
                val needed = androidPermsFor(request).filter {
                    ContextCompat.checkSelfPermission(this@BrowserActivity, it) != PackageManager.PERMISSION_GRANTED
                }
                if (needed.isEmpty()) grantPendingIfAllowed() else mediaPermission.launch(needed.toTypedArray())
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                if (pendingPermission == request) pendingPermission = null
            }
        }

        wv.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            download(wv, url, userAgent, contentDisposition, mimeType)
        }
    }

    private fun closePopup() {
        val p = popup ?: return
        popup = null
        container.removeView(p)
        p.destroy()
        supportActionBar?.subtitle = site.profile
    }

    // ---- Native look ----

    /** Color the toolbar + system bars with the page's own theme color (falls back to its background). */
    private fun tintFromPage() {
        val js = """(function(){
            var m=document.querySelector('meta[name="theme-color"]');
            var c=m&&m.content;
            if(!c||c==='transparent'){c=getComputedStyle(document.body||document.documentElement).backgroundColor;}
            if(!c||c==='rgba(0, 0, 0, 0)'){c=getComputedStyle(document.documentElement).backgroundColor;}
            return c||'';})()"""
        webView.evaluateJavascript(js) { raw ->
            val color = parseCssColor(raw?.trim('"') ?: "") ?: return@evaluateJavascript
            applyBarColor(color)
        }
    }

    private fun applyBarColor(color: Int) {
        toolbar.setBackgroundColor(color)
        window.statusBarColor = color
        window.navigationBarColor = color
        val light = androidx.core.graphics.ColorUtils.calculateLuminance(color) > 0.5
        val fg = if (light) 0xFF1A1A1A.toInt() else 0xFFF2F2F2.toInt()
        toolbar.setTitleTextColor(fg)
        toolbar.setSubtitleTextColor((fg and 0x00FFFFFF) or 0x99000000.toInt())
        toolbar.navigationIcon?.setTint(fg)
        toolbar.overflowIcon?.setTint(fg)
        for (i in 0 until toolbar.menu.size()) toolbar.menu.getItem(i).icon?.setTint(fg)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    private fun parseCssColor(css: String): Int? {
        val s = css.trim()
        Regex("""rgba?\(\s*(\d+)[,\s]+(\d+)[,\s]+(\d+)(?:[,\s/]+([\d.]+))?""").find(s)?.let { m ->
            val (r, g, b) = m.destructured
            val a = m.groupValues[4].toFloatOrNull() ?: 1f
            if (a < 0.5f) return null
            return Color.rgb(r.toInt(), g.toInt(), b.toInt())
        }
        return runCatching { Color.parseColor(if (s.length == 4 && s.startsWith("#"))
            "#" + s.drop(1).map { "$it$it" }.joinToString("") else s) }.getOrNull()
    }

    // Full-screen: hide the toolbar; a small handle brings it back.
    private fun prefs() = getSharedPreferences("portal_prefs", MODE_PRIVATE)
    private fun barKey() = "bar_hidden_${site.id}"

    private fun setBarHidden(hidden: Boolean) {
        toolbar.visibility = if (hidden) View.GONE else View.VISIBLE
        showBarButton?.visibility = if (hidden) View.VISIBLE else View.GONE
        prefs().edit().putBoolean(barKey(), hidden).apply()
    }

    private fun setupShowBarButton() {
        val d = resources.displayMetrics.density
        val b = android.widget.ImageButton(this).apply {
            setImageResource(R.drawable.ic_expand); setColorFilter(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0x66000000)
            }
            alpha = 0.7f
            contentDescription = "Show toolbar"
            setOnClickListener { setBarHidden(false) }
        }
        val size = (32 * d).toInt()
        container.addView(b, FrameLayout.LayoutParams(size, size, android.view.Gravity.TOP or android.view.Gravity.END).apply {
            topMargin = (6 * d).toInt(); rightMargin = (6 * d).toInt()
        })
        showBarButton = b
    }

    private fun androidPermsFor(req: PermissionRequest): List<String> = req.resources.mapNotNull {
        when (it) {
            PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
            PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
            else -> null
        }
    }

    private fun grantPendingIfAllowed() {
        val req = pendingPermission ?: return
        pendingPermission = null
        val allowed = req.resources.filter { res ->
            val perm = when (res) {
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
                else -> return@filter false
            }
            ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
        }
        if (allowed.isEmpty()) req.deny() else req.grant(allowed.toTypedArray())
    }

    private fun download(wv: WebView, url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        when {
            url.startsWith("https://") || url.startsWith("http://") ->
                withStorage { downloadHttp(url, userAgent, contentDisposition, mimeType) }
            url.startsWith("data:") -> withStorage { saveDataUrl(url, contentDisposition, mimeType) }
            url.startsWith("blob:") -> withStorage { fetchBlob(wv, url, contentDisposition, mimeType) }
            else -> Toast.makeText(this, "This download type isn't supported", Toast.LENGTH_SHORT).show()
        }
    }

    /** Android 9 and below need the storage permission to write to Downloads; 10+ don't. */
    private fun withStorage(action: () -> Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 29 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        ) action()
        else { afterStorage = action; storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
    }

    private fun withLocation(then: (Boolean) -> Unit) {
        val perms = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (perms.any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) then(true)
        else { afterLocation = then; locationPermission.launch(perms) }
    }

    private fun downloadHttp(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        try {
            val name = Downloads.safeName(URLUtil.guessFileName(url, contentDisposition, mimeType), mimeType)
            val req = DownloadManager.Request(Uri.parse(url))
                .setMimeType(mimeType)
                .setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            // Cookie only from this site's own profile, only for the same URL.
            Profiles.cookieFor(site.profile, url)?.let { req.addRequestHeader("Cookie", it) }
            userAgent?.let { req.addRequestHeader("User-Agent", it) }
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            Toast.makeText(this, "Downloading $name", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveDataUrl(url: String, contentDisposition: String?, mimeType: String?) {
        try {
            val header = url.substringAfter("data:").substringBefore(',')
            val payload = url.substringAfter(',')
            val mime = header.substringBefore(';').ifBlank { mimeType ?: "application/octet-stream" }
            val bytes = if (header.contains(";base64")) android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
                        else Uri.decode(payload).toByteArray()
            val name = Downloads.save(this, nameFromDisposition(contentDisposition) ?: "download", mime) { it.write(bytes) }
            Toast.makeText(this, "Saved to Downloads/$name", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * blob: URLs only exist inside the page, so the page reads the blob and streams it
     * back in base64 chunks over the PortalDl channel, tagged with a one-time nonce.
     */
    private fun fetchBlob(wv: WebView, url: String, contentDisposition: String?, mimeType: String?) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            Toast.makeText(this, "Update Android System WebView to download this file", Toast.LENGTH_LONG).show(); return
        }
        val nonce = java.util.UUID.randomUUID().toString()
        val tmp = java.io.File.createTempFile("blob", ".part", cacheDir)
        blobDownloads[nonce] = BlobDownload(tmp, nameFromDisposition(contentDisposition) ?: "download", mimeType)
        val js = """(function(u,n,fb){
            var a=document.querySelector('a[href="'+u+'"]');
            var name=(a&&a.getAttribute('download'))||fb;
            fetch(u).then(function(r){return r.blob();}).then(function(b){
              var CH=393216,total=Math.max(1,Math.ceil(b.size/CH)),i=0;
              function next(){
                var fr=new FileReader();
                fr.onload=function(){
                  var s=fr.result; s=s.substring(s.indexOf(',')+1);
                  PortalDl.postMessage(JSON.stringify({n:n,i:i,t:total,d:s,name:name,mime:b.type}));
                  if(++i<total) next();
                };
                fr.readAsDataURL(b.slice(i*CH,(i+1)*CH));
              }
              next();
            }).catch(function(e){PortalDl.postMessage(JSON.stringify({n:n,err:String(e)}));});
          })(${org.json.JSONObject.quote(url)},${org.json.JSONObject.quote(nonce)},${org.json.JSONObject.quote(blobDownloads[nonce]!!.name)})"""
        wv.evaluateJavascript(js, null)
        Toast.makeText(this, "Downloading…", Toast.LENGTH_SHORT).show()
    }

    private fun onBlobChunk(raw: String) {
        val o = runCatching { org.json.JSONObject(raw) }.getOrNull() ?: return
        val nonce = o.optString("n")
        val dl = blobDownloads[nonce] ?: return            // not ours / not requested -> ignore
        if (o.has("err")) {
            blobDownloads.remove(nonce); dl.file.delete()
            Toast.makeText(this, "Download failed: ${o.optString("err")}", Toast.LENGTH_LONG).show(); return
        }
        try {
            o.optString("name").takeIf { it.isNotBlank() }?.let { dl.name = it }
            o.optString("mime").takeIf { it.isNotBlank() }?.let { dl.mime = it }
            java.io.FileOutputStream(dl.file, true).use {
                it.write(android.util.Base64.decode(o.optString("d"), android.util.Base64.DEFAULT))
            }
            if (o.optInt("i") >= o.optInt("t") - 1) {
                blobDownloads.remove(nonce)
                val name = Downloads.save(this, dl.name, dl.mime) { out -> dl.file.inputStream().use { it.copyTo(out) } }
                dl.file.delete()
                Toast.makeText(this, "Saved to Downloads/$name", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            blobDownloads.remove(nonce); dl.file.delete()
            Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun nameFromDisposition(cd: String?): String? =
        cd?.let { Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)"?""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
            ?.let { Uri.decode(it) }?.takeIf { it.isNotBlank() }

    private fun exitCustomView() {
        val v = customView ?: return
        customView = null
        (window.decorView as FrameLayout).removeView(v)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            .show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
    }

    private fun launchExternal(uri: Uri) {
        try {
            val i = if (uri.scheme == "intent") Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).apply {
                addCategory(Intent.CATEGORY_BROWSABLE); component = null; selector = null
            } else Intent(Intent.ACTION_VIEW, uri)
            if (uri.scheme == "http" || uri.scheme == "https") {
                // Portal is now a browser too; don't open the link back in ourselves.
                val others = packageManager.queryIntentActivities(i, 0)
                    .filter { it.activityInfo.packageName != packageName }
                if (others.isEmpty()) throw ActivityNotFoundException()
                i.setPackage(others.first().activityInfo.packageName)
            }
            startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_browser, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.b_back -> { if (webView.canGoBack()) webView.goBack(); true }
        R.id.b_forward -> { if (webView.canGoForward()) webView.goForward(); true }
        R.id.b_reload -> { webView.reload(); true }
        R.id.b_close -> { finishAndRemoveTask(); true }
        R.id.b_fullscreen -> { setBarHidden(true); true }
        R.id.b_home -> { webView.loadUrl(site.url); true }
        R.id.b_save_here -> { saveCurrentAsSite(); true }
        R.id.b_open_external -> { webView.url?.let { launchExternal(Uri.parse(it)) }; true }
        R.id.b_all_sites -> {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    /** Bookmark the current page as a new tile, in the same profile (so it's already logged in). */
    private fun saveCurrentAsSite() {
        val url = webView.url ?: return
        SiteDialogs.edit(this, Site(webView.title?.take(40) ?: url, url, site.profile), isNew = true) { s ->
            val all = SiteStore.load(this); all.add(s); SiteStore.save(this, all)
            Toast.makeText(this, "Saved \"${s.name}\"", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::webView.isInitialized) webView.saveState(outState)
    }

    override fun onPause() { super.onPause(); if (::webView.isInitialized) webView.onPause() }
    override fun onResume() { super.onResume(); if (::webView.isInitialized) webView.onResume() }

    override fun onDestroy() {
        exitCustomView()
        blobDownloads.values.forEach { it.file.delete() }
        blobDownloads.clear()
        closePopup()
        if (::webView.isInitialized) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        fileCallback?.onReceiveValue(null)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SITE_ID = "site_id"
        private val MATCH = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        fun intentFor(context: Context, site: Site): Intent =
            Intent(context, BrowserActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                // Unique data per site => one Recents card per site, re-used on relaunch.
                .setData(Uri.parse("portal://site/${site.id}"))
                .putExtra(EXTRA_SITE_ID, site.id)
    }
}
