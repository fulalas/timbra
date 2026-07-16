package com.timbra.ui.library

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.timbra.data.model.Category
import com.timbra.databinding.RowCategoryBinding

class CategoryAdapter(
    private val categories: List<Category>,
    private val onClick: (Category) -> Unit,
) : RecyclerView.Adapter<CategoryAdapter.VH>() {

    inner class VH(val b: RowCategoryBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(RowCategoryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = categories.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val cat = categories[position]
        holder.b.icon.setImageResource(cat.iconRes)
        holder.b.label.setText(cat.titleRes)
        holder.b.root.setOnClickListener { onClick(cat) }
    }
}
