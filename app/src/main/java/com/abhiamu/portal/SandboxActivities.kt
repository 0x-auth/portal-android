package com.abhiamu.portal

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar

/**
 * Isolated per-site browsing surface. Each concrete subclass below is pinned
 * to its own process (see AndroidManifest.xml android:process=":sandboxN"),
 * so each gets its own WebView cookie/storage jar independent of the others
 * and of MainActivity.
 */
abstract class SandboxActivityBase : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sandbox)

        val toolbar = findViewById<Toolbar>(R.id.sandboxToolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        progressBar = findViewById(R.id.sandboxProgressBar)
        webView = findViewById(R.id.sandboxWebView)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                supportActionBar?.subtitle = url
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                progressBar.progress = newProgress
            }
            override fun onReceivedTitle(view: WebView?, title: String?) {
                supportActionBar?.title = title
            }
        }

        webView.loadUrl(intent.getStringExtra(EXTRA_URL) ?: "about:blank")
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_sandbox, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.sandbox_go_back -> { if (webView.canGoBack()) webView.goBack(); true }
            R.id.sandbox_go_forward -> { if (webView.canGoForward()) webView.goForward(); true }
            R.id.sandbox_reload -> { webView.reload(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    companion object {
        const val EXTRA_URL = "url"
    }
}

class Sandbox0Activity : SandboxActivityBase()
class Sandbox1Activity : SandboxActivityBase()
class Sandbox2Activity : SandboxActivityBase()
class Sandbox3Activity : SandboxActivityBase()
