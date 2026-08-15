package com.abhiamu.portal

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.ImageButton
import androidx.recyclerview.widget.RecyclerView

class BookmarkAdapter(
    private val items: MutableList<Bookmark>,
    private val onClick: (Bookmark) -> Unit,
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<BookmarkAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.bookmarkName)
        val url: TextView = view.findViewById(R.id.bookmarkUrl)
        val delete: ImageButton = view.findViewById(R.id.deleteBookmark)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_bookmark, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.name.text = item.name
        holder.url.text = item.url
        holder.itemView.setOnClickListener { onClick(item) }
        holder.delete.setOnClickListener { onDelete(holder.adapterPosition) }
    }

    override fun getItemCount() = items.size
}
