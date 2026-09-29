package com.minitag.app

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Ponte entre o Storage Access Framework (URIs content://) e o TagEngine (java.io.File).
 * O jaudiotagger precisa de um ficheiro real, por isso copiamos para a cache, editamos e copiamos de volta.
 */
class TagRepository(private val context: Context) {
    private val resolver = context.contentResolver

    fun read(track: Track) {
        withTemp(track) { temp ->
            val d = TagEngine.read(temp, track.ext)
            track.values = d.values; track.hasCover = d.hasCover; track.error = null
        }
    }

    fun readCover(track: Track): ByteArray? = withTemp(track) { TagEngine.readCover(it, track.ext) }

    /** Grava e devolve (valores lidos de novo do ficheiro editado, avisos). */
    fun write(track: Track, changes: TagChanges): Pair<TagData, List<String>> = withTemp(track) { temp ->
        val warnings = TagEngine.write(temp, track.ext, changes)
        val after = TagEngine.read(temp, track.ext)
        copyBack(temp, track.uri)
        scan(track.uri)
        after to warnings
    }

    private fun <T> withTemp(track: Track, block: (File) -> T): T {
        val temp = File.createTempFile("minitag_", ".${track.ext}", context.cacheDir)
        try {
            val input = resolver.openInputStream(track.uri) ?: error("Não foi possível abrir ${track.name}")
            input.use { i -> temp.outputStream().use { i.copyTo(it) } }
            return block(temp)
        } finally {
            temp.delete()
        }
    }

    /**
     * Escreve por cima do original. Usamos "rw" + truncate em vez de openOutputStream(uri, "w"),
     * porque em vários Android o modo "w" NÃO trunca e o ficheiro fica com lixo no fim
     * quando o tag novo é mais pequeno.
     */
    private fun copyBack(temp: File, uri: Uri) {
        val size = temp.length()
        val pfd = runCatching { resolver.openFileDescriptor(uri, "rw") }.getOrNull()
        if (pfd != null) {
            pfd.use {
                FileOutputStream(it.fileDescriptor).channel.use { out ->
                    FileInputStream(temp).channel.use { src ->
                        out.position(0)
                        var pos = 0L
                        while (pos < size) pos += src.transferTo(pos, size - pos, out)
                        out.truncate(size)
                        out.force(true)
                    }
                }
            }
        } else {
            // Fornecedores que não dão descritor "rw" (ex.: alguns serviços de cloud).
            val out = resolver.openOutputStream(uri, "wt") ?: error("Sem permissão de escrita em $uri")
            out.use { o -> temp.inputStream().use { it.copyTo(o) } }
        }
    }

    /**
     * Pede ao MediaStore para reler o ficheiro; caso contrário os leitores de música
     * continuam a mostrar as tags antigas e parece que "não gravou".
     */
    private fun scan(uri: Uri) {
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return
        val volume = docId.substringBefore(':', "")
        val rel = docId.substringAfter(':', "")
        if (volume.isEmpty() || rel.isEmpty()) return
        val base = if (volume.equals("primary", true)) Environment.getExternalStorageDirectory().path else "/storage/$volume"
        MediaScannerConnection.scanFile(context, arrayOf("$base/$rel"), null, null)
    }
}
