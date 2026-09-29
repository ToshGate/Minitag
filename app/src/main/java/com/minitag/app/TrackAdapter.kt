package com.minitag.app

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors

class TrackAdapter(
    private val tracks: List<Track>,
    private val onEdit: (Track) -> Unit,
    private val onSelectionChanged: () -> Unit,
) : RecyclerView.Adapter<TrackAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val check: CheckBox = v.findViewById(R.id.checked)
        val title: TextView = v.findViewById(R.id.title)
        val artist: TextView = v.findViewById(R.id.artist)
        val album: TextView = v.findViewById(R.id.album)
        val file: TextView = v.findViewById(R.id.file)
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(LayoutInflater.from(p.context).inflate(R.layout.row_track, p, false))

    override fun getItemCount() = tracks.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val x = tracks[pos]
        h.check.setOnCheckedChangeListener(null)
        h.check.isChecked = x.selected
        h.check.setOnCheckedChangeListener { _, b -> x.selected = b; paint(h, x); onSelectionChanged() }

        h.title.text = x[TagField.TITLE].ifBlank { x.name.substringBeforeLast('.') }
        h.artist.text = x[TagField.ARTIST].ifBlank { "Artista desconhecido" }
        h.album.text = buildString {
            append(x[TagField.ALBUM].ifBlank { "Álbum desconhecido" })
            if (x[TagField.TRACK].isNotBlank()) append("  •  Faixa ").append(x[TagField.TRACK])
            if (x[TagField.YEAR].isNotBlank()) append("  •  ").append(x[TagField.YEAR])
        }
        if (x.error != null) {
            h.file.ellipsize = android.text.TextUtils.TruncateAt.END
            h.file.text = "⚠ Erro ao ler — toque para ver detalhes\n${x.error}"
            h.file.setTextColor(Color.rgb(0xC6, 0x28, 0x28))
        } else {
            h.file.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            h.file.text = buildString {
                append(x.path)
                append("  •  ").append(x.ext.uppercase())
                if (x.hasCover) append("  •  capa")
            }
            h.file.setTextColor(MaterialColors.getColor(h.file, android.R.attr.textColorSecondary))
        }
        paint(h, x)

        // Toque = editar este ficheiro. Toque longo = seleccionar (para edição em lote).
        h.itemView.setOnClickListener { onEdit(x) }
        h.itemView.setOnLongClickListener { h.check.toggle(); true }
    }

    private fun paint(h: VH, x: Track) {
        h.itemView.setBackgroundColor(
            if (x.selected) MaterialColors.getColor(h.itemView, com.google.android.material.R.attr.colorSecondaryContainer)
            else Color.TRANSPARENT
        )
    }
}
