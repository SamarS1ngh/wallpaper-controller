package com.samar.wallpapercontroller

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.concurrent.Executors

class ThumbAdapter(
    private val onRemove: (File) -> Unit,
) : RecyclerView.Adapter<ThumbAdapter.Holder>() {

    private val executor = Executors.newFixedThreadPool(2)
    private var files: List<File> = emptyList()

    fun submit(newFiles: List<File>) {
        files = newFiles
        notifyDataSetChanged()
    }

    class Holder(val image: ImageView) : RecyclerView.ViewHolder(image)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_thumb, parent, false) as ImageView
        return Holder(view)
    }

    override fun getItemCount() = files.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val file = files[position]
        holder.image.setImageBitmap(null)
        holder.image.tag = file.path
        executor.execute {
            val bitmap = decodeThumb(file.path, 256)
            holder.image.post {
                if (holder.image.tag == file.path) holder.image.setImageBitmap(bitmap)
            }
        }
        holder.image.setOnLongClickListener {
            onRemove(file)
            true
        }
    }
}
