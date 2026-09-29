package com.minitag.app

import org.jaudiotagger.audio.AudioFile
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.audio.flac.metadatablock.MetadataBlockDataPicture
import org.jaudiotagger.audio.generic.GenericAudioHeader
import org.jaudiotagger.audio.mp4.Mp4TagReader
import org.jaudiotagger.audio.mp3.MP3File
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.flac.FlacTag
import org.jaudiotagger.tag.id3.ID3v11Tag
import org.jaudiotagger.tag.id3.valuepair.ImageFormats
import org.jaudiotagger.tag.images.ArtworkFactory
import org.jaudiotagger.tag.reference.PictureTypes
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentFieldKey
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag
import org.jaudiotagger.tag.vorbiscomment.util.Base64Coder
import org.jaudiotagger.audio.wav.WavOptions
import org.jaudiotagger.audio.wav.WavSaveOptions
import org.jaudiotagger.tag.wav.WavTag
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

/** Campos editáveis. A ordem aqui é a ordem em que aparecem no diálogo. */
enum class TagField(val label: String, val numeric: Boolean = false) {
    TITLE("Título"),
    ARTIST("Artista"),
    ALBUM("Álbum"),
    ALBUM_ARTIST("Artista do álbum"),
    COMPOSER("Compositor"),
    YEAR("Ano"),
    TRACK("Faixa (n ou n/total)", numeric = true),
    DISC("Disco (n ou n/total)", numeric = true),
    GENRE("Género"),
    COMMENT("Comentário"),
}

sealed class CoverChange {
    object Keep : CoverChange()
    object Remove : CoverChange()
    class Replace(val bytes: ByteArray, val width: Int = 0, val height: Int = 0) : CoverChange()
}

/** Só o que o utilizador alterou. Campos ausentes do mapa não são tocados. */
data class TagChanges(
    val fields: Map<TagField, String> = emptyMap(),
    val cover: CoverChange = CoverChange.Keep,
) {
    val isEmpty get() = fields.isEmpty() && cover === CoverChange.Keep
}

data class TagData(val values: Map<TagField, String>, val hasCover: Boolean)

/**
 * Lógica de tags pura JVM (sem Android), trabalha sobre um java.io.File.
 * O TagRepository trata de copiar de/para o URI do SAF.
 */
object TagEngine {
    init {
        // Sem isto o jaudiotagger tenta usar javax.imageio / java.awt, que não existem no Android.
        TagOptionSingleton.getInstance().apply {
            isAndroid = true
            // WAV: usar ID3 (suporta capa) mas manter o chunk LIST/INFO sincronizado.
            wavOptions = WavOptions.READ_ID3_ONLY_AND_SYNC
            wavSaveOptions = WavSaveOptions.SAVE_BOTH_AND_SYNC
        }
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
    }

    private val mp4Exts = setOf("m4a", "m4b", "m4p", "mp4")

    /**
     * Abre o ficheiro. Para MP4/M4A há um recurso: o jaudiotagger 3.0.1 rebenta com
     * NullPointerException em GenericAudioHeader.getChannelNumber() quando a faixa de áudio
     * não é AAC/ALAC (ex.: AC-3, E-AC-3) ou o "esds" não tem nº de canais — o campo é um
     * Integer null que é convertido para int. Isto só afecta a info técnica (canais/bitrate);
     * as tags estão noutro sítio (moov/udta/meta/ilst). Por isso lemos só as tags e
     * usamos um cabeçalho vazio, o que chega para ler e para gravar.
     */
    private fun open(file: File, ext: String): AudioFile = try {
        AudioFileIO.readAs(file, ext)
    } catch (e: RuntimeException) {
        if (ext.lowercase() !in mp4Exts) throw e
        val tag = Mp4TagReader().read(file.toPath())
        AudioFile(file, GenericAudioHeader(), tag).also { it.ext = ext.lowercase() }
    }

    fun read(file: File, ext: String): TagData {
        val af = open(file, ext)
        val tag = af.tag
        // MP3 só com ID3v1 (ficheiros antigos): usar o v1 como recurso para campos vazios.
        val sources = listOfNotNull(tag, (af as? MP3File)?.takeIf { it.hasID3v1Tag() }?.getID3v1Tag())
        fun get(k: FieldKey) = sources.firstNotNullOfOrNull { t ->
            runCatching { t.getFirst(k) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        }.orEmpty()
        fun pair(n: FieldKey, total: FieldKey): String {
            val a = get(n).takeUnless { it == "0" }.orEmpty()
            val b = get(total).takeUnless { it == "0" }.orEmpty()
            return if (b.isEmpty()) a else "$a/$b"
        }
        val values = mapOf(
            TagField.TITLE to get(FieldKey.TITLE),
            TagField.ARTIST to get(FieldKey.ARTIST),
            TagField.ALBUM to get(FieldKey.ALBUM),
            TagField.ALBUM_ARTIST to get(FieldKey.ALBUM_ARTIST),
            TagField.COMPOSER to get(FieldKey.COMPOSER),
            TagField.YEAR to get(FieldKey.YEAR),
            TagField.TRACK to pair(FieldKey.TRACK, FieldKey.TRACK_TOTAL),
            TagField.DISC to pair(FieldKey.DISC_NO, FieldKey.DISC_TOTAL),
            TagField.GENRE to get(FieldKey.GENRE),
            TagField.COMMENT to get(FieldKey.COMMENT),
        )
        val hasCover = tag?.let { runCatching { artworkTag(it)?.artworkList?.isNotEmpty() }.getOrNull() } ?: false
        return TagData(values, hasCover)
    }

    fun readCover(file: File, ext: String): ByteArray? {
        val tag = open(file, ext).tag ?: return null
        return runCatching { artworkTag(tag)?.firstArtwork?.binaryData }.getOrNull()
    }

    /**
     * Aplica as alterações e grava no ficheiro.
     * Devolve avisos (campos que o formato não suporta); lança excepção em erros reais.
     */
    fun write(file: File, ext: String, changes: TagChanges): List<String> {
        validate(changes)
        val warnings = mutableListOf<String>()
        val af: AudioFile = open(file, ext)
        val tag: Tag = af.tagOrCreateAndSetDefault

        for ((field, raw) in changes.fields) {
            val value = raw.trim()
            try {
                when (field) {
                    TagField.TRACK -> setPair(tag, FieldKey.TRACK, FieldKey.TRACK_TOTAL, value)
                    TagField.DISC -> setPair(tag, FieldKey.DISC_NO, FieldKey.DISC_TOTAL, value)
                    else -> set(tag, keyFor(field), value)
                }
            } catch (e: Exception) {
                warnings += "${field.label}: não suportado neste formato (${e.javaClass.simpleName})"
            }
        }

        when (val c = changes.cover) {
            CoverChange.Keep -> Unit
            CoverChange.Remove -> runCatching { artworkTag(tag)?.deleteArtworkField() }
            is CoverChange.Replace -> setCover(tag, c)
        }

        // MP3 com ID3v1 antigo: sincronizar para que leitores antigos não mostrem os valores velhos.
        if (af is MP3File && af.hasID3v1Tag() && af.getID3v2Tag() != null) {
            runCatching { af.setID3v1Tag(ID3v11Tag(af.getID3v2Tag())) }
        }

        af.commit()
        return warnings
    }

    private fun keyFor(f: TagField) = when (f) {
        TagField.TITLE -> FieldKey.TITLE
        TagField.ARTIST -> FieldKey.ARTIST
        TagField.ALBUM -> FieldKey.ALBUM
        TagField.ALBUM_ARTIST -> FieldKey.ALBUM_ARTIST
        TagField.COMPOSER -> FieldKey.COMPOSER
        TagField.YEAR -> FieldKey.YEAR
        TagField.GENRE -> FieldKey.GENRE
        TagField.COMMENT -> FieldKey.COMMENT
        TagField.TRACK, TagField.DISC -> error("par")
    }

    /** No WAV a capa vive sempre no chunk ID3, mesmo quando o tag "activo" é o LIST/INFO. */
    private fun artworkTag(tag: Tag): Tag? = if (tag is WavTag) tag.getID3Tag() else tag

    private fun artworkTagForWrite(tag: Tag): Tag =
        if (tag is WavTag) tag.getID3Tag() ?: WavTag.createDefaultID3Tag().also { tag.setID3Tag(it) } else tag

    private val pairRegex = Regex("""^(\d*)\s*(?:/\s*(\d*))?$""")

    private fun validate(changes: TagChanges) {
        for ((f, v) in changes.fields) if (f.numeric && !pairRegex.matches(v.trim()))
            throw IllegalArgumentException("${f.label}: valor inválido \"$v\" (use por ex. 3 ou 3/12)")
    }

    private fun set(tag: Tag, key: FieldKey, value: String) {
        if (value.isEmpty()) runCatching { tag.deleteField(key) }
        else tag.setField(key, value)
    }

    private fun setPair(tag: Tag, num: FieldKey, total: FieldKey, value: String) {
        val m = pairRegex.matchEntire(value)!!
        val n = m.groupValues[1].trimStart('0').ifEmpty { if (m.groupValues[1].isEmpty()) "" else "0" }
        val t = m.groupValues[2].trimStart('0')
        // Apagar primeiro evita restos (ex.: "5/12" -> "5" tem de remover o total).
        runCatching { tag.deleteField(total) }
        runCatching { tag.deleteField(num) }
        if (n.isNotEmpty()) tag.setField(num, n)
        if (t.isNotEmpty()) tag.setField(total, t)
    }

    private fun setCover(original: Tag, c: CoverChange.Replace) {
        val tag = artworkTagForWrite(original)
        val mime = ImageFormats.getMimeTypeForBinarySignature(c.bytes)
            ?: throw IllegalArgumentException("Formato de imagem não reconhecido (use JPEG ou PNG)")
        runCatching { tag.deleteArtworkField() }
        // FLAC e Ogg: o jaudiotagger chama Artwork.setImageFromData(), que no modo Android
        // lança UnsupportedOperationException. Por isso construímos o bloco PICTURE à mão.
        val picture by lazy {
            MetadataBlockDataPicture(c.bytes, PictureTypes.DEFAULT_ID, mime, "", c.width, c.height, 0, 0)
        }
        when (tag) {
            is FlacTag -> tag.setField(picture)
            is VorbisCommentTag -> {
                val b64 = String(Base64Coder.encode(picture.rawContent))
                tag.setField(tag.createField(VorbisCommentFieldKey.METADATA_BLOCK_PICTURE, b64))
            }
            else -> {
                val art = ArtworkFactory.getNew().apply {
                    binaryData = c.bytes
                    mimeType = mime
                    pictureType = PictureTypes.DEFAULT_ID
                    width = c.width
                    height = c.height
                }
                tag.setField(art)
            }
        }
    }
}
