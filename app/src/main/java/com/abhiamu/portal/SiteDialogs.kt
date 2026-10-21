package com.abhiamu.portal

import android.content.Context
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

object SiteDialogs {

    /** Add / edit a site: name, URL, and which profile (login jar) it lives in. */
    fun edit(ctx: Context, site: Site, isNew: Boolean, onSave: (Site) -> Unit) {
        val pad = (20 * ctx.resources.displayMetrics.density).toInt()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        fun label(t: String) = TextView(ctx).apply { text = t; setPadding(0, pad / 2, 0, 0) }

        val name = EditText(ctx).apply { setText(site.name); hint = "Name"; setSingleLine() }
        val url = EditText(ctx).apply {
            setText(site.url); hint = "claude.ai"; setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val profiles = SiteStore.profiles(SiteStore.load(ctx))
        val profile = AutoCompleteTextView(ctx).apply {
            setText(site.profile); setSingleLine(); threshold = 0
            setAdapter(ArrayAdapter(ctx, android.R.layout.simple_dropdown_item_1line, profiles))
            setOnFocusChangeListener { _, f -> if (f) showDropDown() }
            setOnClickListener { showDropDown() }
        }

        box.addView(label("Name")); box.addView(name)
        box.addView(label("URL")); box.addView(url)
        box.addView(label("Login profile — same profile = same login. Type a new name for a new account.")); box.addView(profile)

        AlertDialog.Builder(ctx)
            .setTitle(if (isNew) "Add site" else "Edit site")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val u = normalizeUrl(url.text.toString())
                if (u == null) {
                    Toast.makeText(ctx, "That doesn't look like a web address", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                site.url = u
                site.name = name.text.toString().trim().ifEmpty { android.net.Uri.parse(u).host ?: u }
                site.profile = profile.text.toString().trim().ifEmpty { DEFAULT_PROFILE }
                onSave(site)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun normalizeUrl(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty() || t.contains(' ')) return null
        val withScheme = if (t.startsWith("https://") || t.startsWith("http://")) t else "https://$t"
        val host = android.net.Uri.parse(withScheme).host
        return if (host.isNullOrBlank() || !host.contains('.') && host != "localhost") null else withScheme
    }
}
