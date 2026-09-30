package com.minitag.app

import de.sciss.jump3r.mp3.BitStream
import de.sciss.jump3r.mp3.GainAnalysis
import de.sciss.jump3r.mp3.ID3Tag
import de.sciss.jump3r.mp3.Lame
import de.sciss.jump3r.mp3.LameGlobalFlags
import de.sciss.jump3r.mp3.MPEGMode
import de.sciss.jump3r.mp3.Presets
import de.sciss.jump3r.mp3.Quantize
import de.sciss.jump3r.mp3.QuantizePVT
import de.sciss.jump3r.mp3.Reservoir
import de.sciss.jump3r.mp3.Takehiro
import de.sciss.jump3r.mp3.VBRTag
import de.sciss.jump3r.mp3.VbrMode
import de.sciss.jump3r.mp3.Version
import de.sciss.jump3r.mpg.Common
import de.sciss.jump3r.mpg.Interface
import de.sciss.jump3r.mpg.MPGLib
import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile

/** Qualidades disponíveis na conversão. */
enum class Mp3Quality(val label: String, val kbps: Int?) {
    CBR_320("320 kbps", 320),
    CBR_256("256 kbps", 256),
    CBR_192("192 kbps", 192),
    CBR_128("128 kbps", 128),
    VBR("VBR (alta qualidade, ~190 kbps)", null),
}

/**
 * Codificador MP3 com o LAME em Java puro (jump3r). Usa directamente o núcleo
 * (pacote mp3), porque o LameEncoder "de alto nível" depende de javax.sound, que não existe no Android.
 * Não depende de Android: recebe PCM 16 bits entrelaçado.
 */
class Mp3Encoder(sampleRate: Int, private val channels: Int, quality: Mp3Quality) : Closeable {
    private val lame = Lame()
    private val vbrTag = VBRTag()
    private val gfp: LameGlobalFlags

    init {
        require(channels in 1..2) { "O LAME só aceita 1 ou 2 canais (recebeu $channels)" }
        // Ligações entre módulos, iguais às do LameEncoder original.
        val ga = GainAnalysis(); val bs = BitStream(); val p = Presets(); val qupvt = QuantizePVT()
        val qu = Quantize(); val ver = Version(); val id3 = ID3Tag(); val rv = Reservoir()
        val tak = Takehiro(); val mpg = MPGLib(); val intf = Interface(); val common = Common()
        lame.setModules(ga, bs, p, qupvt, qu, vbrTag, ver, id3, mpg)
        bs.setModules(ga, mpg, ver, vbrTag)
        id3.setModules(bs, ver)
        p.setModules(lame)
        qu.setModules(bs, rv, qupvt, tak)
        qupvt.setModules(tak, rv, lame.enc.psy)
        rv.setModules(bs)
        tak.setModules(qupvt)
        vbrTag.setModules(lame, bs, ver)
        mpg.setModules(intf, common)
        intf.setModules(vbrTag, common)

        gfp = lame.lame_init()
        gfp.num_channels = channels
        gfp.in_samplerate = sampleRate
        gfp.mode = if (channels == 1) MPEGMode.MONO else MPEGMode.JOINT_STEREO
        gfp.quality = 3 // algoritmo: equivalente ao "-q 3" do LAME (bom equilíbrio velocidade/qualidade)
        if (quality.kbps != null) {
            gfp.VBR = VbrMode.vbr_off
            gfp.brate = quality.kbps
        } else {
            gfp.VBR = VbrMode.vbr_default
            gfp.VBR_q = 2 // "-V2": o preset "standard" do LAME
        }
        gfp.bWriteVbrTag = true           // cabeçalho Xing/LAME: duração correcta e reprodução sem pausas
        gfp.write_id3tag_automatic = false // as tags são escritas depois com o jaudiotagger
        id3.id3tag_init(gfp)
        gfp.write_id3tag_automatic = false
        val rc = lame.lame_init_params(gfp)
        require(rc >= 0) { "Parâmetros não suportados pelo LAME ($rc)" }
    }

    private var out = ByteArray(0)
    private var left = IntArray(0)
    private var right = IntArray(0)

    /** `pcm` entrelaçado (L R L R… ou mono), `frames` = nº de amostras por canal. */
    fun encode(pcm: ShortArray, frames: Int, dest: OutputStream) {
        if (frames == 0) return
        if (left.size < frames) { left = IntArray(frames); right = IntArray(frames) }
        if (channels == 2) {
            for (i in 0 until frames) { left[i] = pcm[2 * i].toInt() shl 16; right[i] = pcm[2 * i + 1].toInt() shl 16 }
        } else {
            for (i in 0 until frames) { val s = pcm[i].toInt() shl 16; left[i] = s; right[i] = s }
        }
        val need = (1.25 * frames + 7200).toInt()
        if (out.size < need) out = ByteArray(need)
        val n = lame.lame_encode_buffer_int(gfp, left, right, frames, out, 0, out.size)
        check(n >= 0) { "Erro do LAME ao codificar ($n)" }
        dest.write(out, 0, n)
    }

    fun finish(dest: OutputStream) {
        if (out.size < 7200) out = ByteArray(7200)
        val n = lame.lame_encode_flush(gfp, out, 0, out.size)
        check(n >= 0) { "Erro do LAME ao terminar ($n)" }
        dest.write(out, 0, n)
    }

    /** Depois de fechar o ficheiro: preenche o cabeçalho Xing/LAME no 1.º frame. */
    fun writeVbrTag(file: File) {
        RandomAccessFile(file, "rw").use { vbrTag.putVbrTag(gfp, it) }
    }

    override fun close() { lame.lame_close(gfp) }
}
