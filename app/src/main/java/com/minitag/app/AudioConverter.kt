package com.minitag.app

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.DocumentsContract
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

/**
 * Converte ficheiros de áudio para MP3:
 *   MediaExtractor + MediaCodec (descodificadores do próprio Android) → PCM → LAME (jump3r) → MP3,
 *   depois copia tags e capa com o TagEngine e grava em <pasta do original>/MP3/.
 * O original nunca é alterado.
 */
private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

class AudioConverter(private val context: Context, private val repo: TagRepository) {
    private val resolver = context.contentResolver

    data class Probe(val track: Track, val codec: String, val lossy: Boolean, val unsupportedReason: String?)

    // Formatos sem perdas; tudo o resto é tratado como com perdas.
    private val losslessMimes = setOf("audio/flac", "audio/raw", "audio/alac", "audio/x-wav", "audio/wav")

    /** Lê só o cabeçalho: codec, se é com perdas e se este telemóvel o consegue descodificar. */
    fun probe(t: Track): Probe {
        when (t.ext) {
            "wma" -> return Probe(t, "WMA", true, "o Android não descodifica WMA")
            "dsf" -> return Probe(t, "DSD", false, "o Android não descodifica DSD")
        }
        return try {
            withExtractor(t.uri) { ex, idx, fmt ->
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                val decoder = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(fmt)
                Probe(t, codecName(mime), mime !in losslessMimes,
                    if (decoder == null) "sem descodificador para ${codecName(mime)} neste telemóvel" else null)
            }
        } catch (e: Exception) {
            Probe(t, "?", false, "não foi possível analisar (${e.message ?: e.javaClass.simpleName})")
        }
    }

    private fun codecName(mime: String) = when (mime) {
        "audio/mpeg" -> "MP3"; "audio/mp4a-latm" -> "AAC"; "audio/vorbis" -> "Vorbis"
        "audio/opus" -> "Opus"; "audio/flac" -> "FLAC"; "audio/alac" -> "ALAC"
        "audio/raw" -> "PCM"; "audio/ac3" -> "AC-3"; "audio/eac3" -> "E-AC-3"
        else -> mime.removePrefix("audio/").uppercase()
    }

    /** Cache de pastas MP3/ e nomes já usados, válida durante uma conversão em lote. */
    class Session {
        val dirs = mutableMapOf<String, String>()            // caminho relativo no destino -> id da pasta
        val names = mutableMapOf<String, MutableSet<String>>() // id da pasta -> nomes (minúsculas)
    }

    class Result(val track: Track, val warnings: List<String>)

    /**
     * Converte um ficheiro. `progress` recebe 0..1. `cancelled` é verificado com frequência.
     * Devolve o novo Track (já com tags lidas).
     */
    fun convert(src: Track, quality: Mp3Quality, dest: Uri, session: Session,
                progress: (Float) -> Unit, cancelled: () -> Boolean): Result {
        val segments = mirrorPath(src, dest)
        val temp = File.createTempFile("minitag_conv_", ".mp3", context.cacheDir)
        try {
            encode(src.uri, temp, quality, progress, cancelled)
            if (cancelled()) throw CancellationException()

            // Tags e capa do original → MP3 novo. Uma falha aqui não invalida o áudio: fica como aviso.
            val warnings = mutableListOf<String>()
            try {
                val cover = if (src.hasCover) repo.readCover(src) else null
                val changes = TagChanges(
                    fields = src.values.filterValues { it.isNotBlank() },
                    cover = cover?.let { CoverChange.Replace(it) } ?: CoverChange.Keep,
                )
                warnings += TagEngine.write(temp, "mp3", changes)
            } catch (e: Exception) {
                warnings += "tags não copiadas (${e.javaClass.simpleName}: ${e.message})"
            }

            // Criar <destino>/<caminho espelhado>/<nome>.mp3 sem sobrescrever nada.
            // Sincronizado: com várias conversões em paralelo, duas não podem escolher o mesmo nome
            // nem criar a mesma pasta duas vezes.
            val dirId: String
            val name: String
            val outUri: Uri
            synchronized(session) {
                dirId = ensureDirs(dest, segments, session)
                val taken = session.names.getOrPut(dirId) { childNames(dest, dirId) }
                name = uniqueName(src.name.substringBeforeLast('.'), taken)
                val dirUri = DocumentsContract.buildDocumentUriUsingTree(dest, dirId)
                outUri = DocumentsContract.createDocument(resolver, dirUri, "audio/mpeg", name)
                    ?: error("não foi possível criar $name")
                taken += name.lowercase()
            }
            try {
                (resolver.openOutputStream(outUri, "w") ?: error("sem permissão de escrita"))
                    .use { o -> temp.inputStream().use { it.copyTo(o) } }
            } catch (e: Exception) {
                runCatching { DocumentsContract.deleteDocument(resolver, outUri) } // não deixar ficheiro meio escrito
                throw e
            }
            // O fornecedor pode ter ajustado o nome; usar o nome real.
            val realName = queryName(outUri) ?: name
            if (realName != name) synchronized(session) { session.names[dirId]?.add(realName.lowercase()) }
            repo.scan(outUri)

            val newTrack = Track(outUri, realName, (segments + realName).joinToString("/"), dest, dirId)
            runCatching { repo.read(newTrack) }.onFailure { newTrack.error = it.message }
            return Result(newTrack, warnings)
        } finally {
            temp.delete()
        }
    }

    // ---------- Descodificar + codificar ----------

    private fun encode(uri: Uri, out: File, quality: Mp3Quality, progress: (Float) -> Unit, cancelled: () -> Boolean) =
        withExtractor(uri) { ex, idx, fmt ->
            val durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            val codecName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(fmt)
                ?: error("sem descodificador para ${fmt.getString(MediaFormat.KEY_MIME)}")
            val codec = MediaCodec.createByCodecName(codecName)
            var encoder: Mp3Encoder? = null
            try {
                codec.configure(fmt, null, null, 0)
                codec.start()
                BufferedOutputStream(FileOutputStream(out), 1 shl 16).use { os ->
                    val info = MediaCodec.BufferInfo()
                    var inDone = false
                    var outDone = false
                    var pcm = ShortArray(0)
                    var tmpS = ShortArray(0)
                    var tmpF = FloatArray(0)
                    var srcChannels = 0
                    var pcmFloat = false
                    while (!outDone) {
                        if (cancelled()) throw CancellationException()
                        if (!inDone) {
                            val i = codec.dequeueInputBuffer(10_000)
                            if (i >= 0) {
                                val buf = codec.getInputBuffer(i)!!
                                val n = ex.readSampleData(buf, 0)
                                if (n < 0) {
                                    codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inDone = true
                                } else {
                                    codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                                    ex.advance()
                                }
                            }
                        }
                        val o = codec.dequeueOutputBuffer(info, 10_000)
                        if (o < 0) continue // INFO_TRY_AGAIN_LATER / FORMAT_CHANGED: o formato é lido por buffer
                        if (info.size > 0) {
                            if (encoder == null) {
                                val of = codec.getOutputFormat(o)
                                srcChannels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                val rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                val enc = if (of.containsKey(MediaFormat.KEY_PCM_ENCODING))
                                    of.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                                pcmFloat = when (enc) {
                                    AudioFormat.ENCODING_PCM_16BIT -> false
                                    AudioFormat.ENCODING_PCM_FLOAT -> true
                                    else -> error("formato PCM não suportado ($enc)")
                                }
                                encoder = Mp3Encoder(rate, minOf(srcChannels, 2), quality)
                            }
                            val buf = codec.getOutputBuffer(o)!!
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            buf.order(ByteOrder.nativeOrder())
                            val samples = if (pcmFloat) info.size / 4 else info.size / 2
                            val frames = samples / srcChannels
                            val outCh = minOf(srcChannels, 2)
                            if (pcm.size < frames * outCh) pcm = ShortArray(frames * outCh)
                            if (pcmFloat) {
                                if (tmpF.size < samples) tmpF = FloatArray(samples)
                                buf.asFloatBuffer().get(tmpF, 0, samples)
                                val tmp = tmpF
                                mix(frames, srcChannels, pcm) { i -> tmp[i] * 32767f }
                            } else {
                                if (srcChannels <= 2) buf.asShortBuffer().get(pcm, 0, frames * srcChannels)
                                else {
                                    if (tmpS.size < samples) tmpS = ShortArray(samples)
                                    buf.asShortBuffer().get(tmpS, 0, samples)
                                    val tmp = tmpS
                                    mix(frames, srcChannels, pcm) { i -> tmp[i].toFloat() }
                                }
                            }
                            encoder!!.encode(pcm, frames, os)
                            if (durationUs > 0) progress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    }
                    (encoder ?: error("o ficheiro não tem áudio")).finish(os)
                }
                encoder!!.writeVbrTag(out)
            } finally {
                runCatching { codec.stop() }
                codec.release()
                encoder?.close()
            }
        }

    /**
     * Converte para PCM 16 bits com no máximo 2 canais.
     * 5.1 (FL FR FC LFE BL BR): mistura ITU (centro e surround a -3 dB, sem LFE).
     * Outros multicanal: usa os 2 primeiros canais.
     */
    private fun clip(v: Float): Short = v.coerceIn(-32768f, 32767f).toInt().toShort()

    private inline fun mix(frames: Int, ch: Int, out: ShortArray, sample: (Int) -> Float) {
        when {
            ch == 1 -> for (f in 0 until frames) out[f] = clip(sample(f))
            ch == 2 -> for (i in 0 until frames * 2) out[i] = clip(sample(i))
            ch == 6 -> for (f in 0 until frames) {
                val b = f * 6
                val c = 0.7071f * sample(b + 2)
                out[2 * f] = clip(sample(b) + c + 0.7071f * sample(b + 4))
                out[2 * f + 1] = clip(sample(b + 1) + c + 0.7071f * sample(b + 5))
            }
            else -> for (f in 0 until frames) {
                out[2 * f] = clip(sample(f * ch)); out[2 * f + 1] = clip(sample(f * ch + 1))
            }
        }
    }

    private fun <T> withExtractor(uri: Uri, block: (MediaExtractor, Int, MediaFormat) -> T): T {
        val pfd = resolver.openFileDescriptor(uri, "r") ?: error("não foi possível abrir o ficheiro")
        val ex = MediaExtractor()
        try {
            pfd.use {
                ex.setDataSource(it.fileDescriptor)
                val idx = (0 until ex.trackCount).firstOrNull {
                    ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: error("o ficheiro não tem faixa de áudio")
                ex.selectTrack(idx)
                return block(ex, idx, ex.getTrackFormat(idx))
            }
        } finally {
            ex.release()
        }
    }

    // ---------- Pastas e nomes (SAF) ----------

    /**
     * Caminho (pastas) a recriar dentro da pasta MP3, espelhando o caminho completo do original:
     *   primary:Spotiflac/Sam Smith/Álbum/x.flac  →  [Spotiflac, Sam Smith, Álbum]
     * Cartão SD: o identificador do volume vem primeiro  →  [1234-ABCD, Música, …]
     * Se o original já estiver dentro da pasta MP3, usa o caminho relativo a ela.
     * Fornecedores que não usam caminhos (ex.: "Transferências", clouds): nome da pasta aberta + caminho relativo.
     */
    fun mirrorPath(src: Track, dest: Uri): List<String> {
        val srcId = DocumentsContract.getDocumentId(src.uri)
        val destId = DocumentsContract.getTreeDocumentId(dest)
        if (src.uri.authority == EXTERNAL_STORAGE && srcId.contains(':')) {
            val dir = srcId.substringBeforeLast('/', srcId.substringBefore(':') + ":")
            if (dest.authority == EXTERNAL_STORAGE && (dir == destId || dir.startsWith("$destId/")))
                return split(dir.removePrefix(destId))
            val volume = srcId.substringBefore(':')
            val rel = split(dir.substringAfter(':'))
            return if (volume.equals("primary", true)) rel else listOf(volume) + rel
        }
        val treeName = src.tree?.let { t ->
            queryName(DocumentsContract.buildDocumentUriUsingTree(t, DocumentsContract.getTreeDocumentId(t)))
        } ?: "Outros"
        return listOf(treeName) + split(src.path.substringBeforeLast('/', ""))
    }

    private fun split(path: String) = path.split('/').map { it.trim() }.filter { it.isNotEmpty() }

    /** Cria (se preciso) cada pasta do caminho dentro do destino e devolve o id da última. */
    private fun ensureDirs(dest: Uri, segments: List<String>, session: Session): String {
        var id = DocumentsContract.getTreeDocumentId(dest)
        val path = StringBuilder()
        for (seg in segments) {
            path.append('/').append(seg.lowercase())
            val parent = id
            id = session.dirs.getOrPut(path.toString()) { findOrCreateDir(dest, parent, seg) }
        }
        return id
    }

    private fun findOrCreateDir(tree: Uri, parentId: String, name: String): String {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        resolver.query(children, arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
            while (c.moveToNext()) if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR &&
                c.getString(1).equals(name, ignoreCase = true)) return c.getString(0)
        }
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        val created = DocumentsContract.createDocument(resolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: error("não foi possível criar a pasta $name")
        return DocumentsContract.getDocumentId(created)
    }

    private fun childNames(tree: Uri, dirId: String): MutableSet<String> {
        val set = mutableSetOf<String>()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId)
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) c.getString(0)?.let { set += it.lowercase() }
        }
        return set
    }

    /** "nome.mp3", ou "nome (1).mp3", "nome (2).mp3"… se já existir. */
    private fun uniqueName(base: String, taken: Set<String>): String {
        var candidate = "$base.mp3"
        var i = 1
        while (candidate.lowercase() in taken) candidate = "$base (${i++}).mp3"
        return candidate
    }

    private fun queryName(uri: Uri): String? =
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
}
