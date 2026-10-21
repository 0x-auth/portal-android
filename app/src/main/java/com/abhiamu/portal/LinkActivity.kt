package com.abhiamu.portal

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Entry point for http(s) links from other apps ("Open with Portal", or Portal as
 * default browser). Invisible: picks the login profile, saves the link as a site,
 * opens it, and gets out of the way.
 *
 * Profile choice:
 *  - a saved site on the same domain  -> that site's profile (already signed in)
 *  - several accounts on that domain   -> quick picker (e.g. Claude 1 / 2 / 3)
 *  - nothing saved for that domain     -> Default profile
 */
class LinkActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = linkFrom(intent)
        if (url == null) { finish(); return }
        val host = Uri.parse(url).host.orEmpty()

        val candidates = SiteStore.profilesForHost(SiteStore.load(this), host)
        when (candidates.size) {
            0 -> open(url, DEFAULT_PROFILE)
            1 -> open(url, candidates[0])
            else -> AlertDialog.Builder(this)
                .setTitle("Open $host as…")
                .setItems(candidates.toTypedArray()) { _, i -> open(url, candidates[i]) }
                .setOnCancelListener { finish() }
                .show()
        }
    }

    private fun open(url: String, profile: String) {
        val site = SiteStore.saveLink(this, url, profile)
        startActivity(BrowserActivity.intentFor(this, site))
        finish()
    }

    /** VIEW with an http(s) URL, or text/plain shared from another app that is a URL. */
    private fun linkFrom(i: Intent): String? {
        val raw = when (i.action) {
            Intent.ACTION_VIEW -> i.dataString
            Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)
                ?.let { Regex("""https?://\S+""").find(it)?.value }
            else -> null
        } ?: return null
        val u = Uri.parse(raw.trim())
        return if ((u.scheme == "https" || u.scheme == "http") && !u.host.isNullOrBlank()) u.toString() else null
    }
}
