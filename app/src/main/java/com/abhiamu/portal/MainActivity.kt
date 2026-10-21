package com.abhiamu.portal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton

class MainActivity : AppCompatActivity() {

    private lateinit var sites: MutableList<Site>
    private lateinit var adapter: SiteAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById<Toolbar>(R.id.mainToolbar))

        sites = SiteStore.load(this)
        adapter = SiteAdapter(
            onOpen = { startActivity(BrowserActivity.intentFor(this, it)) },
            onLongPress = { site, anchor -> showSiteMenu(site, anchor) }
        )
        findViewById<RecyclerView>(R.id.siteGrid).apply {
            val cols = (resources.configuration.screenWidthDp / 170).coerceIn(2, 5)
            layoutManager = GridLayoutManager(this@MainActivity, cols)
            adapter = this@MainActivity.adapter
        }
        findViewById<FloatingActionButton>(R.id.addFab).setOnClickListener { addSite() }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        // A site may have been saved from inside a browser screen.
        sites = SiteStore.load(this); refresh()
    }

    private fun refresh(filter: String = currentFilter) {
        currentFilter = filter
        val q = filter.trim().lowercase()
        adapter.submit(if (q.isEmpty()) sites else sites.filter {
            it.name.lowercase().contains(q) || it.url.lowercase().contains(q) || it.profile.lowercase().contains(q)
        })
    }
    private var currentFilter = ""

    private fun persist() { SiteStore.save(this, sites); refresh() }

    private fun addSite() {
        SiteDialogs.edit(this, Site("", "", DEFAULT_PROFILE), isNew = true) { sites.add(it); persist() }
    }

    private fun showSiteMenu(site: Site, anchor: android.view.View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Edit")
            menu.add(0, 2, 1, "Add to home screen")
            menu.add(0, 3, 2, "Duplicate (new account)")
            menu.add(0, 4, 3, "Move up")
            menu.add(0, 5, 4, "Delete")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> SiteDialogs.edit(this@MainActivity, site, isNew = false) { persist() }
                    2 -> pinShortcut(site)
                    3 -> {
                        val nextProfile = nextFreeProfileName(site.profile)
                        SiteDialogs.edit(this@MainActivity,
                            Site(if (site.name == site.profile) nextProfile else "${site.name} · $nextProfile",
                                site.url, nextProfile),
                            isNew = true) { sites.add(sites.indexOf(site) + 1, it); persist() }
                    }
                    4 -> { val i = sites.indexOf(site); if (i > 0) { sites.removeAt(i); sites.add(i - 1, site); persist() } }
                    5 -> AlertDialog.Builder(this@MainActivity)
                        .setTitle("Delete \"${site.name}\"?")
                        .setMessage("The login in profile \"${site.profile}\" is kept. Wipe it from Profiles if you want it gone.")
                        .setPositiveButton("Delete") { _, _ -> sites.remove(site); persist() }
                        .setNegativeButton("Cancel", null).show()
                }
                true
            }
            show()
        }
    }

    private fun nextFreeProfileName(base: String): String {
        val stem = base.replace(Regex("\\s*\\d+$"), "").ifBlank { "Account" }
        val used = SiteStore.profiles(sites).toSet()
        var n = 2
        while ("$stem $n" in used) n++
        return "$stem $n"
    }

    private fun pinShortcut(site: Site) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
            Toast.makeText(this, "Your launcher doesn't support pinned shortcuts", Toast.LENGTH_SHORT).show(); return
        }
        val info = ShortcutInfoCompat.Builder(this, "site_${site.id}")
            .setShortLabel(site.name.take(24))
            .setLongLabel("${site.name} · ${site.profile}")
            .setIcon(IconCompat.createWithAdaptiveBitmap(letterIcon(site)))
            .setIntent(BrowserActivity.intentFor(this, site))
            .build()
        ShortcutManagerCompat.requestPinShortcut(this, info, null)
    }

    private fun letterIcon(site: Site): Bitmap {
        val size = 216
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(SiteAdapter.colorFor(site.profile))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 72f; textAlign = Paint.Align.CENTER; isFakeBoldText = true
        }
        val label = SiteAdapter.initials(site)
        c.drawText(label, size / 2f, size / 2f - (p.descent() + p.ascent()) / 2, p)
        return bmp
    }

    // ---- Toolbar ----

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        (menu.findItem(R.id.action_search).actionView as SearchView).apply {
            queryHint = "Search sites"
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(q: String?) = true
                override fun onQueryTextChange(q: String?): Boolean { refresh(q ?: ""); return true }
            })
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_add -> { addSite(); true }
        R.id.action_profiles -> { showProfiles(); true }
        R.id.action_export -> { exportSites(); true }
        R.id.action_import -> { importSites(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun showProfiles() {
        val names = SiteStore.profiles(sites)
        val labels = names.map { n -> "$n  (${sites.count { it.profile == n }} sites)" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Login profiles")
            .setItems(labels) { _, which ->
                val n = names[which]
                AlertDialog.Builder(this)
                    .setTitle("Log out of \"$n\"?")
                    .setMessage("Deletes all cookies and site data in this profile. Every site using it will be logged out. " +
                            "Close any open \"$n\" screens first.")
                    .setPositiveButton("Wipe") { _, _ ->
                        Profiles.wipe(n)
                        Toast.makeText(this, "Profile \"$n\" wiped", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Only names + URLs + profile names. No cookies/tokens ever leave the app this way. */
    private fun exportSites() {
        val json = SiteStore.toJson(sites)
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("Portal sites", json))
        Toast.makeText(this, "${sites.size} sites copied to clipboard (no logins included)", Toast.LENGTH_LONG).show()
    }

    private fun importSites() {
        val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val input = EditText(this).apply {
            setText(clip); hint = "Paste exported JSON"; minLines = 4; maxLines = 10
        }
        AlertDialog.Builder(this)
            .setTitle("Import sites")
            .setMessage("Adds sites from the JSON below. Existing ones (same id) are skipped.")
            .setView(input)
            .setPositiveButton("Import") { _, _ ->
                val incoming = runCatching { SiteStore.fromJson(input.text.toString()) }.getOrNull()
                if (incoming == null) {
                    Toast.makeText(this, "Couldn't read that JSON", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val have = sites.map { it.id }.toSet()
                val added = incoming.filter { it.id !in have && SiteDialogs.normalizeUrl(it.url) != null }
                sites.addAll(added); persist()
                Toast.makeText(this, "Imported ${added.size} sites", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
