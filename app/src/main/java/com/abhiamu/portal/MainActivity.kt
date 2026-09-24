package com.abhiamu.portal

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var bookmarkList: RecyclerView
    private lateinit var bookmarks: MutableList<Bookmark>

    private var sandboxCounter = 0
    private val sandboxActivities = listOf(
        Sandbox0Activity::class.java,
        Sandbox1Activity::class.java,
        Sandbox2Activity::class.java,
        Sandbox3Activity::class.java
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawerLayout = findViewById(R.id.mainDrawerLayout)

        val toolbar = findViewById<Toolbar>(R.id.mainToolbar)
        setSupportActionBar(toolbar)

        progressBar = findViewById(R.id.mainProgressBar)
        webView = findViewById(R.id.mainWebView)

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
                supportActionBar?.title = title ?: getString(R.string.app_name)
            }
        }
        webView.loadUrl("about:blank")

        bookmarks = BookmarkStore.load(this)
        bookmarkList = findViewById(R.id.bookmarkList)
        bookmarkList.layoutManager = LinearLayoutManager(this)
        bookmarkList.adapter = BookmarkAdapter(
            bookmarks,
            onClick = { bookmark -> openInSandbox(bookmark) },
            onDelete = { position ->
                bookmarks.removeAt(position)
                BookmarkStore.save(this, bookmarks)
                bookmarkList.adapter?.notifyItemRemoved(position)
            }
        )
    }

    private fun openInSandbox(bookmark: Bookmark) {
        val target = sandboxActivities[sandboxCounter % sandboxActivities.size]
        sandboxCounter++
        val intent = Intent(this, target)
        intent.putExtra(SandboxActivityBase.EXTRA_URL, bookmark.url)
        startActivity(intent)
        drawerLayout.closeDrawers()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_bookmarks_drawer -> { drawerLayout.openDrawer(bookmarkList); true }
            R.id.action_go_back -> { if (webView.canGoBack()) webView.goBack(); true }
            R.id.action_go_forward -> { if (webView.canGoForward()) webView.goForward(); true }
            R.id.action_go_to_url -> { promptForUrl(); true }
            R.id.action_reload -> { webView.reload(); true }
            R.id.action_bookmark_current -> { promptToBookmarkCurrentPage(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun promptForUrl() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_TEXT_VARIATION_URI
        input.hint = "https://example.com"
        AlertDialog.Builder(this)
            .setTitle("Open URL")
            .setView(input)
            .setPositiveButton("Go") { _, _ ->
                val raw = input.text.toString().trim()
                if (raw.isNotEmpty()) webView.loadUrl(normalizeUrl(raw))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptToBookmarkCurrentPage() {
        val url = webView.url
        if (url.isNullOrBlank() || url == "about:blank") return
        val input = EditText(this)
        input.setText(webView.title ?: url)
        AlertDialog.Builder(this)
            .setTitle("Save bookmark")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { url }
                bookmarks.add(Bookmark(name, url))
                BookmarkStore.save(this, bookmarks)
                bookmarkList.adapter?.notifyItemInserted(bookmarks.size - 1)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun normalizeUrl(raw: String): String =
        if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            drawerLayout.isDrawerOpen(bookmarkList) -> drawerLayout.closeDrawers()
            webView.canGoBack() -> webView.goBack()
            else -> super.onBackPressed()
        }
    }
}
