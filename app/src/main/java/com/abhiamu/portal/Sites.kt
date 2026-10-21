package com.abhiamu.portal

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A saved site. [profile] is the name of the isolated WebView profile
 * (cookie jar + localStorage + IndexedDB + service workers) it runs in.
 * Two sites with the same profile share a login; different profiles never do.
 */
data class Site(
    var name: String,
    var url: String,
    var profile: String = DEFAULT_PROFILE,
    val id: String = UUID.randomUUID().toString()
)

const val DEFAULT_PROFILE = "Default"

object SiteStore {
    private const val PREFS = "portal_prefs"
    private const val KEY_V2 = "sites_v2"
    private const val KEY_V1 = "bookmarks_json" // old format, migrated once

    fun load(context: Context): MutableList<Site> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_V2, null)?.let { return runCatching { fromJson(it) }.getOrElse { defaults() } }

        val migrated = prefs.getString(KEY_V1, null)?.let { runCatching { fromJson(it) }.getOrNull() }
        val list = migrated ?: defaults()
        save(context, list)
        return list
    }

    fun save(context: Context, sites: List<Site>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_V2, toJson(sites)).apply()
    }

    fun find(context: Context, id: String): Site? = load(context).firstOrNull { it.id == id }

    fun profiles(sites: List<Site>): List<String> =
        (listOf(DEFAULT_PROFILE) + sites.map { it.profile }).distinct()

    fun toJson(sites: List<Site>): String {
        val arr = JSONArray()
        sites.forEach {
            arr.put(JSONObject().put("name", it.name).put("url", it.url)
                .put("profile", it.profile).put("id", it.id))
        }
        return arr.toString(2)
    }

    /** Accepts both the v2 format and the old v1 bookmark format (no profile/id). */
    fun fromJson(raw: String): MutableList<Site> {
        val arr = JSONArray(raw)
        return MutableList(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Site(
                name = o.getString("name"),
                url = o.getString("url"),
                profile = o.optString("profile", DEFAULT_PROFILE).ifBlank { DEFAULT_PROFILE },
                id = o.optString("id").ifBlank { UUID.randomUUID().toString() }
            )
        }
    }

    private fun defaults(): MutableList<Site> = mutableListOf(
        Site("Claude 1", "https://claude.ai/", "Claude 1"),
        Site("Claude 2", "https://claude.ai/", "Claude 2"),
        Site("Claude 3", "https://claude.ai/", "Claude 3"),
        Site("Darmiyan Shoot", "https://darmiyan-shoot.netlify.app/post-alpha/"),
        Site("Claude Archive", "https://claude-archive.pages.dev/")
    )
}
