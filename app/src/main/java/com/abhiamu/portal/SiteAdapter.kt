package com.abhiamu.portal

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SiteAdapter(
    private val onOpen: (Site) -> Unit,
    private val onLongPress: (Site, View) -> Unit
) : RecyclerView.Adapter<SiteAdapter.VH>() {

    private var items: List<Site> = emptyList()

    fun submit(list: List<Site>) { items = list.toList(); notifyDataSetChanged() }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val badge: TextView = v.findViewById(R.id.siteBadge)
        val name: TextView = v.findViewById(R.id.siteName)
        val host: TextView = v.findViewById(R.id.siteHost)
        val profile: TextView = v.findViewById(R.id.siteProfile)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_site, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val s = items[position]
        val color = colorFor(s.profile)
        h.badge.text = initials(s)
        (h.badge.background.mutate() as GradientDrawable).setColor(color)
        h.name.text = s.name
        h.host.text = Uri.parse(s.url).host ?: s.url
        h.profile.text = s.profile
        h.profile.setTextColor(color)
        h.itemView.setOnClickListener { onOpen(s) }
        h.itemView.setOnLongClickListener { onLongPress(s, it); true }
    }

    override fun getItemCount() = items.size

    companion object {
        private val palette = intArrayOf(
            0xFF6C5CE7.toInt(), 0xFFE17055.toInt(), 0xFF00B894.toInt(), 0xFF0984E3.toInt(),
            0xFFE84393.toInt(), 0xFFFDCB6E.toInt(), 0xFF00CEC9.toInt(), 0xFFA29BFE.toInt()
        )
        /** Same profile => same color everywhere, so you can see at a glance which account a tile uses. */
        fun colorFor(profile: String): Int =
            if (profile == DEFAULT_PROFILE) Color.parseColor("#55576E")
            else palette[Math.floorMod(profile.hashCode(), palette.size)]

        fun initials(s: Site): String {
            val digit = s.profile.lastOrNull()?.takeIf { it.isDigit() }?.toString().orEmpty()
            val first = s.name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
            return if (digit.isNotEmpty() && !first.equals(digit)) first + digit else first
        }
    }
}
