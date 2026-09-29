package com.minitag.app

import android.net.Uri

/** Um ficheiro de áudio na lista. `values` reflecte sempre o que está gravado no ficheiro. */
class Track(
    val uri: Uri,
    val name: String,
    val path: String,
    /** Árvore SAF e documento-pai (pasta onde o ficheiro está). */
    val tree: Uri? = null,
    val parentId: String? = null,
    var values: Map<TagField, String> = emptyMap(),
    var hasCover: Boolean = false,
    var error: String? = null,
    /** Excepção completa (tipo + stack trace) para diagnóstico. */
    var errorDetail: String? = null,
    var selected: Boolean = false,
) {
    val ext get() = name.substringAfterLast('.', "").lowercase()
    operator fun get(f: TagField) = values[f].orEmpty()
}
