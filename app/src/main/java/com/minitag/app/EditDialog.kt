package com.minitag.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.jaudiotagger.tag.id3.valuepair.ImageFormats
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService

/**
 * Diálogo de edição, igual para 1 ou N ficheiros.
 *  - Campos com o mesmo valor em todos os ficheiros aparecem preenchidos.
 *  - Campos com valores diferentes aparecem vazios com a indicação "vários valores — mantém-se".
 *  - Só os campos que o utilizador alterar são gravados (como o <keep> do Mp3tag).
 *  - O ícone ✕ de cada campo marca-o para apagar, mesmo quando tem valores diferentes.
 */
class EditDialog(
    private val activity: Activity,
    private val tracks: List<Track>,
    private val repo: TagRepository,
    private val executor: ExecutorService,
    private val pickImage: ((Uri) -> Unit) -> Unit,
    private val onSave: (TagChanges) -> Unit,
) {
    private class FieldState(
        val field: TagField, val layout: TextInputLayout, val edit: TextInputEditText,
        val initial: String, val mixed: Boolean,
    ) {
        var cleared = false
        val text get() = edit.text?.toString().orEmpty()
        val changed get() = text != initial || cleared
    }

    private val batch = tracks.size > 1
    private val states = mutableListOf<FieldState>()
    private var cover: CoverChange = CoverChange.Keep
    private lateinit var coverView: ImageView
    private lateinit var coverStatus: TextView
    private lateinit var coverUndo: Button
    private var originalCover: Bitmap? = null
    private var originalCoverText = ""

    fun show() {
        val view = LayoutInflater.from(activity).inflate(R.layout.edit_dialog, null)
        val container = view.findViewById<LinearLayout>(R.id.fields)
        for (f in TagField.values()) container.addView(buildField(f))
        setupCover(view)

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(if (batch) "Editar ${tracks.size} ficheiros" else tracks[0].name)
            .setView(view)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Gravar", null) // listener definido abaixo para poder validar sem fechar
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { trySave(dialog) }
        }
        dialog.show()
    }

    private fun buildField(f: TagField): TextInputLayout {
        val distinct = tracks.map { it[f] }.distinct()
        val mixed = distinct.size > 1
        val initial = if (mixed) "" else distinct.firstOrNull().orEmpty()

        val layout = TextInputLayout(activity, null, com.google.android.material.R.attr.textInputOutlinedStyle)
        layout.hint = f.label
        layout.endIconMode = TextInputLayout.END_ICON_CUSTOM
        layout.setEndIconDrawable(android.R.drawable.ic_menu_close_clear_cancel)
        layout.endIconContentDescription = "Apagar ${f.label}"
        val edit = TextInputEditText(layout.context)
        edit.inputType = when (f) {
            // DATETIME permite "2008" e também "2008-06-23" (o teclado numérico simples não tem "-").
            TagField.YEAR -> InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE
            TagField.COMMENT -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        if (f.numeric) edit.inputType = InputType.TYPE_CLASS_TEXT // aceita "3/12"
        edit.setText(initial)
        if (mixed) {
            // Não usar edit.hint: o TextInputLayout desenha o rótulo por cima dele.
            // Com o hint expandido desligado, o rótulo fica sempre no topo
            // e o placeholder aparece dentro do campo mesmo sem foco.
            layout.isExpandedHintEnabled = false
            layout.placeholderText = "‹vários valores›"
        }
        layout.addView(edit)
        layout.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (6 * activity.resources.displayMetrics.density).toInt() }

        val st = FieldState(f, layout, edit, initial, mixed)
        states += st
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (st.text != st.initial) st.cleared = false
                layout.error = null
                refreshHelper(st)
            }
        })
        layout.setEndIconOnClickListener {
            edit.setText("")
            st.cleared = true
            refreshHelper(st)
        }
        refreshHelper(st)
        return layout
    }

    private fun refreshHelper(st: FieldState) {
        val n = tracks.size
        st.layout.helperText = when {
            st.changed && st.text.isEmpty() -> if (batch) "Será apagado em $n ficheiros" else "Será apagado"
            st.changed -> if (batch) "Será aplicado a $n ficheiros" else null
            st.mixed -> "Vários valores — mantém-se"
            else -> null
        }
    }

    // ---------- Capa ----------

    private fun setupCover(view: android.view.View) {
        coverView = view.findViewById(R.id.cover)
        coverStatus = view.findViewById(R.id.coverStatus)
        coverUndo = view.findViewById(R.id.coverUndo)
        val withCover = tracks.count { it.hasCover }

        originalCoverText = when {
            !batch && withCover == 1 -> "Capa incorporada"
            !batch -> "Sem capa"
            withCover == 0 -> "Nenhum tem capa"
            else -> "$withCover de ${tracks.size} têm capa"
        }
        coverStatus.text = originalCoverText

        // Pré-visualização: a do ficheiro (ou do primeiro que tenha capa, em lote).
        tracks.firstOrNull { it.hasCover }?.let { t ->
            executor.execute {
                val bmp = runCatching { repo.readCover(t)?.let { decodeSampled(it, 300) } }.getOrNull()
                activity.runOnUiThread {
                    originalCover = bmp
                    if (cover === CoverChange.Keep) coverView.setImageBitmap(bmp)
                }
            }
        }

        view.findViewById<Button>(R.id.coverPick).setOnClickListener { pickImage { uri -> loadImage(uri) } }
        view.findViewById<Button>(R.id.coverRemove).setOnClickListener {
            cover = CoverChange.Remove
            coverView.setImageDrawable(null)
            coverStatus.text = if (batch) "Será removida de ${tracks.size} ficheiros" else "Será removida"
            coverUndo.visibility = android.view.View.VISIBLE
        }
        coverUndo.setOnClickListener {
            cover = CoverChange.Keep
            coverView.setImageBitmap(originalCover)
            coverStatus.text = originalCoverText
            coverUndo.visibility = android.view.View.GONE
        }
    }

    private fun loadImage(uri: Uri) {
        executor.execute {
            val result = runCatching {
                var bytes = activity.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                val preview = decodeSampled(bytes, 300) ?: error("não é uma imagem válida")
                // Tags só aceitam JPEG/PNG de forma fiável: converter WebP/HEIC/etc. para JPEG.
                val mime = ImageFormats.getMimeTypeForBinarySignature(bytes)
                if (mime != ImageFormats.MIME_TYPE_JPEG && mime != ImageFormats.MIME_TYPE_PNG) {
                    val full = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    bytes = ByteArrayOutputStream().also { full.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
                }
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                Triple(bytes, preview, o)
            }
            activity.runOnUiThread {
                result.onSuccess { (bytes, preview, o) ->
                    cover = CoverChange.Replace(bytes, o.outWidth, o.outHeight)
                    coverView.setImageBitmap(preview)
                    coverStatus.text = "Nova capa ${o.outWidth}×${o.outHeight}, ${bytes.size / 1024} KB" +
                        if (batch) " → ${tracks.size} ficheiros" else ""
                    coverUndo.visibility = android.view.View.VISIBLE
                }.onFailure {
                    Toast.makeText(activity, "Imagem inválida: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun decodeSampled(bytes: ByteArray, target: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0) return null
        var sample = 1
        while (o.outWidth / (sample * 2) >= target && o.outHeight / (sample * 2) >= target) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    // ---------- Gravar ----------

    private val pairRegex = Regex("""^\s*\d*\s*(/\s*\d*\s*)?$""")

    private fun trySave(dialog: AlertDialog) {
        var ok = true
        for (st in states) if (st.field.numeric && st.changed && !pairRegex.matches(st.text)) {
            st.layout.error = "Use um número, ex.: 3 ou 3/12"; ok = false
        }
        if (!ok) return
        val changes = TagChanges(
            fields = states.filter { it.changed }.associate { it.field to it.text.trim() },
            cover = cover,
        )
        if (changes.isEmpty) {
            Toast.makeText(activity, "Nada foi alterado", Toast.LENGTH_SHORT).show()
            return
        }
        dialog.dismiss()
        onSave(changes)
    }
}
