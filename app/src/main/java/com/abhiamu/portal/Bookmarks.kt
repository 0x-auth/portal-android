package com.abhiamu.portal

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Bookmark(var name: String, var url: String, var id: String = UUID.randomUUID().toString())

object BookmarkStore {
    private const val PREFS = "portal_prefs"
    private const val KEY = "bookmarks_json"

    fun load(context: Context): MutableList<Bookmark> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null)
        if (raw == null) {
            val defaults = defaultBookmarks()
            save(context, defaults)
            return defaults
        }
        val arr = JSONArray(raw)
        val list = mutableListOf<Bookmark>()
        var needsMigration = false
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = if (o.has("id")) o.getString("id") else {
                needsMigration = true
                UUID.randomUUID().toString()
            }
            list.add(Bookmark(o.getString("name"), o.getString("url"), id))
        }
        if (needsMigration) save(context, list)
        return list
    }

    fun save(context: Context, bookmarks: List<Bookmark>) {
        val arr = JSONArray()
        bookmarks.forEach {
            val o = JSONObject()
            o.put("name", it.name)
            o.put("url", it.url)
            o.put("id", it.id)
            arr.put(o)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, arr.toString())
            .apply()
    }

    private fun defaultBookmarks(): MutableList<Bookmark> = mutableListOf(
        Bookmark("Darmiyan Shoot", "https://darmiyan-shoot.netlify.app/post-alpha/"),
        Bookmark("Claude Archive", "https://claude-archive.pages.dev/")
    )
}
