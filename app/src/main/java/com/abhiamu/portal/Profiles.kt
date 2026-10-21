package com.abhiamu.portal

import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Thin wrapper over androidx.webkit multi-profile.
 * "Default" maps to WebView's built-in default profile; every other name gets
 * its own on-disk jar under the app's private data dir.
 */
object Profiles {

    fun supported(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    private fun internalName(name: String): String =
        if (name == DEFAULT_PROFILE) androidx.webkit.Profile.DEFAULT_PROFILE_NAME
        else "p_" + name.replace(Regex("[^A-Za-z0-9_-]"), "_").take(32) + "_" +
                Integer.toHexString(name.hashCode())

    /** Must be called on a fresh WebView, before settings/loadUrl. */
    fun bind(webView: WebView, name: String) {
        if (!supported()) return // only DEFAULT reaches here when unsupported
        val n = internalName(name)
        ProfileStore.getInstance().getOrCreateProfile(n)
        WebViewCompat.setProfile(webView, n)
    }

    fun cookieManager(name: String): CookieManager =
        if (supported()) ProfileStore.getInstance().getOrCreateProfile(internalName(name)).cookieManager
        else CookieManager.getInstance()

    fun cookieFor(name: String, url: String): String? = cookieManager(name).getCookie(url)

    /**
     * Log out of ONE site inside a profile: expire every cookie visible for its URL
     * (all parent domains/paths too) and delete that origin's storage. Other sites in
     * the same profile keep their logins.
     */
    fun clearSite(name: String, url: String) {
        val cm = cookieManager(name)
        val u = android.net.Uri.parse(url)
        val host = u.host ?: return
        val labels = host.split('.')
        val domains = (0 until (labels.size - 1).coerceAtLeast(1)).map { labels.drop(it).joinToString(".") }
        val names = cm.getCookie(url).orEmpty().split(';')
            .mapNotNull { it.substringBefore('=').trim().takeIf { n -> n.isNotEmpty() } }.distinct()
        for (n in names) for (d in domains) for (path in listOf("/", u.path ?: "/")) {
            val expired = "$n=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=$path"
            cm.setCookie("https://$d", "$expired; Domain=$d")
            cm.setCookie("https://$d", expired)               // host-only variant
        }
        cm.flush()
        val storage = if (supported())
            ProfileStore.getInstance().getOrCreateProfile(internalName(name)).webStorage
        else android.webkit.WebStorage.getInstance()
        for (d in domains) storage.deleteOrigin("https://$d")
    }

    /** Log out everything in a profile: cookies + site storage. */
    fun wipe(name: String) {
        if (!supported()) {
            CookieManager.getInstance().removeAllCookies(null)
            android.webkit.WebStorage.getInstance().deleteAllData()
            return
        }
        val p = ProfileStore.getInstance().getOrCreateProfile(internalName(name))
        p.cookieManager.removeAllCookies(null)
        p.cookieManager.flush()
        p.webStorage.deleteAllData()
    }
}
