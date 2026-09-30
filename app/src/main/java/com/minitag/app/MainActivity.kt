package com.minitag.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import android.view.View
import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), ConversionManager.Listener {
    private val executor = Executors.newSingleThreadExecutor()
    private val tracks = mutableListOf<Track>()
    private lateinit var repo: TagRepository
    private lateinit var adapter: TrackAdapter
    private lateinit var folderLabel: TextView
    private lateinit var editButton: Button
    private lateinit var openButton: Button
    private lateinit var selectAll: CheckBox
    private lateinit var progress: LinearProgressIndicator
    private lateinit var convertButton: Button
    private lateinit var statusRow: View
    private lateinit var statusLabel: TextView
    private lateinit var convProgress: LinearProgressIndicator
    private lateinit var converter: AudioConverter
    /** Conversão à espera da resposta ao pedido de autorização de notificações. */
    private var pendingJob: ConversionManager.Job? = null
    /** Leitura de pasta, gravação de tags ou análise em curso nesta Activity. */
    private var loading = false
    /** Ocupado = trabalho local OU conversão em curso no serviço. */
    private val busy get() = loading || ConversionManager.running

    private val prefs by lazy { getSharedPreferences("minitag", MODE_PRIVATE) }

    private val openTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        prefs.edit().putString("tree", uri.toString()).apply()
        loadFolder(uri)
    }

    private var imageCallback: ((Uri) -> Unit)? = null
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) imageCallback?.invoke(uri)
        imageCallback = null
    }

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Com ou sem autorização, a conversão avança; sem ela só não se vê a notificação.
        pendingJob?.let { startJob(it) }
        pendingJob = null
    }

    private val selectAllListener = CompoundButton.OnCheckedChangeListener { _, checked ->
        tracks.forEach { it.selected = checked }
        adapter.notifyDataSetChanged()
        updateSelectionUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        repo = TagRepository(applicationContext)
        converter = AudioConverter(applicationContext, repo)

        // targetSdk 35 força edge-to-edge: sem isto os botões ficam debaixo da barra de estado.
        val root = findViewById<View>(R.id.root)
        val pad = root.paddingLeft
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(pad + b.left, pad + b.top, pad + b.right, pad + b.bottom)
            insets
        }

        folderLabel = findViewById(R.id.folderLabel)
        editButton = findViewById(R.id.editSelected)
        openButton = findViewById(R.id.openFolder)
        selectAll = findViewById(R.id.selectAll)
        progress = findViewById(R.id.progress)
        convertButton = findViewById(R.id.convertSelected)
        statusRow = findViewById(R.id.statusRow)
        statusLabel = findViewById(R.id.statusLabel)
        convProgress = findViewById(R.id.convProgress)
        convertButton.setOnClickListener { convertSelected() }
        findViewById<Button>(R.id.cancelButton).setOnClickListener { ConversionManager.cancel() }

        adapter = TrackAdapter(tracks, onEdit = { t ->
            when {
                busy -> Unit
                t.error != null -> showError(t)
                else -> openEditor(listOf(t))
            }
        }, onSelectionChanged = ::updateSelectionUi)
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }
        openButton.setOnClickListener { openTree.launch(null) }
        editButton.setOnClickListener {
            val sel = tracks.filter { it.selected && it.error == null }
            if (sel.isEmpty()) toast("Seleccione pelo menos um ficheiro (toque longo ou caixa)")
            else openEditor(sel)
        }
        selectAll.setOnCheckedChangeListener(selectAllListener)
        updateSelectionUi()

        // Reabrir a última pasta, se a permissão ainda existir.
        prefs.getString("tree", null)?.let(Uri::parse)?.let { uri ->
            if (contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }) loadFolder(uri)
        }
    }

    // A Activity só ouve a conversão enquanto está visível; a conversão em si vive no serviço.
    override fun onStart() {
        super.onStart()
        ConversionManager.addListener(this)
        ConversionManager.progress?.let { onProgress(it) }
        refreshBusy()
        ConversionManager.pendingOutcome?.let {
            ConversionManager.pendingOutcome = null
            onFinished(it)
        }
    }

    override fun onStop() {
        ConversionManager.removeListener(this)
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    private fun updateSelectionUi() {
        val n = tracks.count { it.selected }
        editButton.text = if (n > 0) "Editar seleccionados ($n)" else "Editar seleccionados"
        convertButton.text = if (n > 0) "Converter seleccionados para MP3 ($n)" else "Converter seleccionados para MP3"
        selectAll.setOnCheckedChangeListener(null)
        selectAll.isChecked = tracks.isNotEmpty() && n == tracks.size
        selectAll.setOnCheckedChangeListener(selectAllListener)
    }

    private fun setBusy(b: Boolean) {
        loading = b
        refreshBusy()
    }

    private fun refreshBusy() {
        val b = busy
        openButton.isEnabled = !b; editButton.isEnabled = !b; selectAll.isEnabled = !b; convertButton.isEnabled = !b
        progress.visibility = if (loading) View.VISIBLE else View.GONE
        statusRow.visibility = if (ConversionManager.running) View.VISIBLE else View.GONE
    }

    // ---------- Ler pasta ----------

    private fun loadFolder(tree: Uri) {
        tracks.clear(); adapter.notifyDataSetChanged(); updateSelectionUi()
        folderLabel.text = DocumentsContract.getTreeDocumentId(tree).replace(':', '/')
        setBusy(true)
        progress.isIndeterminate = true
        executor.execute {
            val found = mutableListOf<Track>()
            runCatching { walk(tree, DocumentsContract.getTreeDocumentId(tree), "", found) }
                .onFailure { e -> runOnUiThread { toast("Erro a listar a pasta: ${e.message}") } }
            found.sortBy { it.path.lowercase() }
            runOnUiThread {
                tracks.addAll(found); adapter.notifyDataSetChanged()
                progress.isIndeterminate = false; progress.max = found.size.coerceAtLeast(1)
            }
            found.forEachIndexed { i, t ->
                readTrack(t)
                runOnUiThread { adapter.notifyItemChanged(i); progress.progress = i + 1 }
            }
            runOnUiThread {
                setBusy(false)
                val errors = found.count { it.error != null }
                toast("${found.size} ficheiros" + if (errors > 0) " ($errors com erro)" else "")
            }
        }
    }

    /** Percorre a árvore com DocumentsContract: muito mais rápido que DocumentFile.listFiles(). */
    private fun walk(tree: Uri, docId: String, prefix: String, out: MutableList<Track>) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0); val name = c.getString(1) ?: continue; val mime = c.getString(2)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) walk(tree, id, "$prefix$name/", out)
                else if (isSupportedAudio(name))
                    out += Track(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, "$prefix$name", tree, docId)
            }
        }
    }

    /** Lê as tags e, se falhar, guarda a excepção completa (antes só ficava a mensagem, cortada na lista). */
    private fun readTrack(t: Track) {
        try {
            repo.read(t)
            t.errorDetail = null
        } catch (e: Throwable) { // Throwable: apanha também erros como NoSuchMethodError / OutOfMemoryError
            t.error = "${e.javaClass.simpleName}: ${e.message ?: "(sem mensagem)"}"
            t.errorDetail = e.stackTraceToString()
            Log.e("MiniTag", "Erro a ler ${t.path}", e)
        }
    }

    private fun showError(t: Track) {
        val detail = buildString {
            append("Ficheiro: ").append(t.path).append("\n\n")
            append(t.errorDetail ?: t.error)
        }
        val text = TextView(this).apply {
            this.text = detail
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            textSize = 11f
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Erro ao ler o ficheiro")
            .setView(cappedScroll(text, 0.4f))
            .setPositiveButton("Copiar") { _, _ ->
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("MiniTag erro", detail))
                toast("Erro copiado")
            }
            .setNeutralButton("Repetir") { _, _ ->
                setBusy(true); progress.isIndeterminate = true
                executor.execute {
                    t.error = null
                    readTrack(t)
                    runOnUiThread {
                        setBusy(false)
                        tracks.indexOf(t).takeIf { it >= 0 }?.let(adapter::notifyItemChanged)
                        if (t.error != null) toast("Continua a falhar") else toast("Lido com sucesso")
                    }
                }
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    /**
     * ScrollView com altura máxima (fracção do ecrã). Sem limite, um stack trace longo
     * ocupa o diálogo todo e empurra os botões para fora do ecrã.
     */
    private fun cappedScroll(child: View, fraction: Float): ScrollView {
        val maxH = (resources.displayMetrics.heightPixels * fraction).toInt()
        return object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val h = View.MeasureSpec.getSize(heightMeasureSpec)
                val cap = if (h > 0) minOf(h, maxH) else maxH
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST))
            }
        }.apply { addView(child) }
    }

    private fun isSupportedAudio(name: String) =
        name.substringAfterLast('.', "").lowercase() in setOf("mp3", "flac", "ogg", "oga", "m4a", "wav", "aif", "aiff", "wma", "dsf")

    // ---------- Editar / gravar ----------

    private fun openEditor(list: List<Track>) {
        EditDialog(this, list, repo, executor,
            pickImage = { cb -> imageCallback = cb; pickImage.launch("image/*") },
            onSave = { changes -> save(list, changes) }
        ).show()
    }

    private fun save(list: List<Track>, changes: TagChanges) {
        setBusy(true)
        progress.isIndeterminate = false; progress.max = list.size; progress.progress = 0
        executor.execute {
            val errors = mutableListOf<String>()
            val warnings = linkedSetOf<String>()
            var ok = 0
            list.forEachIndexed { i, t ->
                try {
                    val (after, w) = repo.write(t, changes)
                    runOnUiThread { t.values = after.values; t.hasCover = after.hasCover; after.info?.let { t.info = it }; t.error = null }
                    warnings += w.map { "${t.ext.uppercase()} – $it" }
                    ok++
                } catch (e: Throwable) {
                    Log.e("MiniTag", "Erro a gravar ${t.path}", e)
                    errors += "${t.name}: ${e.javaClass.simpleName}: ${e.message ?: "(sem mensagem)"}"
                }
                runOnUiThread {
                    tracks.indexOf(t).takeIf { it >= 0 }?.let(adapter::notifyItemChanged)
                    progress.progress = i + 1
                }
            }
            runOnUiThread {
                setBusy(false)
                if (errors.isEmpty() && warnings.isEmpty()) toast("$ok/${list.size} ficheiros gravados")
                else MaterialAlertDialogBuilder(this)
                    .setTitle("$ok/${list.size} ficheiros gravados")
                    .setMessage((errors.map { "✗ $it" } + warnings.map { "⚠ $it" }).joinToString("\n\n"))
                    .setPositiveButton("OK", null).show()
            }
        }
    }

    // ---------- Converter para MP3 ----------

    private fun convertSelected() {
        val sel = tracks.filter { it.selected && it.error == null }
        if (sel.isEmpty()) { toast("Seleccione pelo menos um ficheiro (toque longo ou caixa)"); return }
        setBusy(true); progress.isIndeterminate = true
        executor.execute {
            val probes = sel.map { converter.probe(it) }
            runOnUiThread { setBusy(false); showConvertDialog(probes) }
        }
    }

    private fun showConvertDialog(probes: List<AudioConverter.Probe>) {
        val ok = probes.filter { it.unsupportedReason == null }
        val bad = probes.filter { it.unsupportedReason != null }
        val lossy = ok.filter { it.lossy }
        val dp = resources.displayMetrics.density
        fun text(s: String, warn: Boolean = false) = TextView(this).apply {
            text = s
            setPadding(0, (8 * dp).toInt(), 0, 0)
            if (warn) setTextColor(com.google.android.material.color.MaterialColors.getColor(
                this, com.google.android.material.R.attr.colorError))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (8 * dp).toInt(), (24 * dp).toInt(), 0)
        }
        if (ok.isNotEmpty()) box.addView(text(
            "${ok.size} ficheiro(s) serão convertidos e gravados numa subpasta MP3/ junto de cada original. Os originais não são alterados."))
        if (lossy.isNotEmpty()) {
            val codecs = lossy.map { it.codec }.distinct().joinToString(", ")
            box.addView(text("⚠ ${lossy.size} ficheiro(s) já estão num formato com perdas ($codecs). " +
                "Converter para MP3 volta a comprimir o áudio e perde qualidade — o resultado nunca soa melhor que o original.", warn = true))
        }
        if (bad.isNotEmpty()) box.addView(text("✗ ${bad.size} ficheiro(s) serão ignorados:\n" +
            bad.joinToString("\n") { "• ${it.track.name}: ${it.unsupportedReason}" }))

        if (ok.isEmpty()) {
            MaterialAlertDialogBuilder(this).setTitle("Nada para converter").setView(cappedScroll(box, 0.5f))
                .setPositiveButton("OK", null).show()
            return
        }
        box.addView(text("Qualidade:").apply { setTypeface(typeface, Typeface.BOLD) })
        val saved = runCatching { Mp3Quality.valueOf(prefs.getString("mp3quality", null) ?: "") }.getOrDefault(Mp3Quality.CBR_320)
        val group = RadioGroup(this)
        Mp3Quality.values().forEach { q ->
            group.addView(RadioButton(this).apply { id = View.generateViewId(); text = q.label; tag = q; isChecked = q == saved })
        }
        box.addView(group)

        MaterialAlertDialogBuilder(this)
            .setTitle("Converter para MP3")
            .setView(cappedScroll(box, 0.6f))
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Converter") { _, _ ->
                val q = group.findViewById<RadioButton>(group.checkedRadioButtonId)?.tag as? Mp3Quality ?: saved
                prefs.edit().putString("mp3quality", q.name).apply()
                runConversion(ok.map { it.track }, q, skipped = bad.size)
            }.show()
    }

    private fun runConversion(list: List<Track>, quality: Mp3Quality, skipped: Int) {
        val job = ConversionManager.Job(list, quality, skipped)
        // Android 13+: pedir autorização para a notificação de progresso (uma vez).
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("askedNotifications", false)
        ) {
            prefs.edit().putBoolean("askedNotifications", true).apply()
            pendingJob = job
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startJob(job)
    }

    private fun startJob(job: ConversionManager.Job) {
        if (!ConversionManager.start(this, job)) { toast("Já há uma conversão em curso"); return }
        onProgress(ConversionManager.progress ?: return)
        refreshBusy()
    }

    override fun onProgress(p: ConversionManager.Progress) {
        statusRow.visibility = View.VISIBLE
        statusLabel.text = when {
            p.cancelling -> "A cancelar…"
            p.workers == 0 -> "A preparar conversão…"
            else -> "A converter ${p.done}/${p.total} · ${p.workers} em simultâneo · pode sair da app"
        }
        convProgress.max = p.total * 1000
        convProgress.setProgressCompat(p.permille, true)
    }

    override fun onFinished(o: ConversionManager.Outcome) {
        getSystemService(NotificationManager::class.java).cancel(ConversionService.ID_DONE)
        if (o.created.isNotEmpty()) {
            // Os MP3 novos aparecem na lista, se ainda não lá estiverem.
            val known = tracks.map { it.uri }.toSet()
            tracks += o.created.filter { it.uri !in known }
            tracks.sortBy { it.path.lowercase() }
            adapter.notifyDataSetChanged()
        }
        refreshBusy()
        updateSelectionUi()
        if (o.lines.isEmpty()) toast("${o.title} · ${o.quality.label}")
        else MaterialAlertDialogBuilder(this).setTitle(o.title)
            .setView(cappedScroll(TextView(this).apply {
                text = o.lines.joinToString("\n\n"); setTextIsSelectable(true)
                val p = (20 * resources.displayMetrics.density).toInt(); setPadding(p, p / 2, p, 0)
            }, 0.5f))
            .setPositiveButton("OK", null).show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
