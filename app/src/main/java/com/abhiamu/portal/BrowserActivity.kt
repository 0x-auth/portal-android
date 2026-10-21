package com.abhiamu.portal

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermission: PermissionRequest? = null

    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data))
        fileCallback = null
    }

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

        val toolbar = findViewById<Toolbar>(R.id.browserToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = site.name
        supportActionBar?.subtitle = site.profile
        toolbar.setNavigationOnClickListener { finishAndRemoveTask() }

        progressBar = findViewById(R.id.browserProgress)

        // Create the WebView in code so the profile is bound before anything else touches it.
        webView = WebView(this)
        Profiles.bind(webView, site.profile)
        findViewById<FrameLayout>(R.id.webContainer).addView(
            webView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        configure(webView)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else moveTaskToBack(true)
            }
        })

        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(site.url)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(wv: WebView) {
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            // Hardening: page can't read app files or content providers.
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)   // target=_blank opens in place, same profile
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = true
            safeBrowsingEnabled = true
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
                supportActionBar?.subtitle = "${site.profile} · ${Uri.parse(url ?: "").host ?: ""}"
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
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
            download(url, userAgent, contentDisposition, mimeType)
        }
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

    private fun download(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            Toast.makeText(this, "This download type isn't supported here", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
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

    private fun launchExternal(uri: Uri) {
        try {
            val i = if (uri.scheme == "intent") Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).apply {
                addCategory(Intent.CATEGORY_BROWSABLE); component = null; selector = null
            } else Intent(Intent.ACTION_VIEW, uri)
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
        if (::webView.isInitialized) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        fileCallback?.onReceiveValue(null)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SITE_ID = "site_id"

        fun intentFor(context: Context, site: Site): Intent =
            Intent(context, BrowserActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                // Unique data per site => one Recents card per site, re-used on relaunch.
                .setData(Uri.parse("portal://site/${site.id}"))
                .putExtra(EXTRA_SITE_ID, site.id)
    }
}
