package com.minitag.app

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Estado da conversão, partilhado entre o ConversionService (que trabalha) e a MainActivity
 * (que mostra). Vive no processo, não na Activity: se o ecrã for fechado e reaberto durante
 * a conversão, a Activity nova volta a ver o progresso.
 */
object ConversionManager {
    class Job(val tracks: List<Track>, val quality: Mp3Quality, val skipped: Int)

    data class Progress(
        val done: Int, val total: Int,
        /** 0..total*1000 */ val permille: Int,
        val workers: Int, val cancelling: Boolean,
    )

    class Outcome(
        val created: List<Track>, val total: Int, val cancelled: Boolean,
        val lines: List<String>, val quality: Mp3Quality,
    ) {
        val title get() = (if (cancelled) "Cancelado — " else "") + "${created.size}/$total convertidos"
    }

    interface Listener {
        fun onProgress(p: Progress)
        fun onFinished(o: Outcome)
    }

    @Volatile var running = false; private set
    @Volatile var progress: Progress? = null; private set
    /** Resultado que terminou sem nenhum ecrã aberto; mostrado quando a app voltar. Só no thread principal. */
    var pendingOutcome: Outcome? = null

    internal val cancelFlag = AtomicBoolean(false)
    internal var job: Job? = null; private set

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val main = Handler(Looper.getMainLooper())

    /** Chamar a partir do thread principal, com a app visível (requisito do Android para serviços em 1.º plano). */
    fun start(context: Context, job: Job): Boolean {
        if (running) return false
        this.job = job
        cancelFlag.set(false)
        running = true
        progress = Progress(0, job.tracks.size, 0, 0, false)
        ContextCompat.startForegroundService(context, Intent(context, ConversionService::class.java))
        return true
    }

    fun cancel() {
        if (!running) return
        cancelFlag.set(true)
        progress?.let { publish(it.copy(cancelling = true)) }
    }

    fun addListener(l: Listener) = listeners.add(l)
    fun removeListener(l: Listener) = listeners.remove(l)
    val hasListeners get() = listeners.isNotEmpty()

    internal fun publish(p: Progress) {
        progress = p
        main.post { listeners.forEach { it.onProgress(p) } }
    }

    internal fun finish(o: Outcome) {
        main.post {
            running = false
            progress = null
            job = null
            if (listeners.isEmpty()) pendingOutcome = o else listeners.forEach { it.onFinished(o) }
        }
    }
}
