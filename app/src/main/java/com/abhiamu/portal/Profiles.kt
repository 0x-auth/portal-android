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

    fun cookieFor(name: String, url: String): String? {
        val cm: CookieManager = if (supported())
            ProfileStore.getInstance().getOrCreateProfile(internalName(name)).cookieManager
        else CookieManager.getInstance()
        return cm.getCookie(url)
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
