package com.minitag.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLong

/**
 * Serviço em primeiro plano para a conversão para MP3.
 * Sem ele, ao sair da app o Android passa-a para segundo plano (só núcleos lentos)
 * e pode matá-la a meio. Com ele há uma notificação com progresso e botão Cancelar.
 */
class ConversionService : Service() {
    companion object {
        private const val CHANNEL_PROGRESS = "conversion"
        private const val CHANNEL_DONE = "conversion_done"
        private const val ID_PROGRESS = 1
        const val ID_DONE = 2
        const val ACTION_CANCEL = "com.minitag.app.CANCEL_CONVERSION"
    }

    private val started = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var nm: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        nm = getSystemService(NotificationManager::class.java)
        createChannels()
        if (intent?.action == ACTION_CANCEL) {
            ConversionManager.cancel()
            return START_NOT_STICKY
        }
        val job = ConversionManager.job
        if (job == null) { stopSelf(); return START_NOT_STICKY }
        if (!started.compareAndSet(false, true)) return START_NOT_STICKY

        // Tem de ser chamado logo (o Android dá poucos segundos). O tipo "mediaProcessing"
        // (Android 15+) é o próprio para conversões; nas versões anteriores usa-se "dataSync".
        val type = when {
            Build.VERSION.SDK_INT >= 35 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
        // Chamada directa (não ServiceCompat): o ServiceCompat do androidx.core 1.15 filtra os tipos
        // que conhece e descarta o "mediaProcessing" (Android 15), ficando com tipo "nenhum" — o que
        // o Android proíbe com targetSdk 34+ (InvalidForegroundServiceTypeException).
        val notification = progressNotification(0, job.tracks.size, 0, 0, false)
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID_PROGRESS, notification, type)
        else startForeground(ID_PROGRESS, notification)

        // O serviço impede o Android de matar a app, mas com o ecrã desligado o CPU adormece:
        // o wake lock parcial mantém o CPU acordado (o ecrã pode apagar-se à vontade).
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MiniTag:conversion")
            .apply { acquire(6 * 60 * 60 * 1000L) }

        Thread({ run(job) }, "MiniTag-conversion").start()
        return START_NOT_STICKY
    }

    /** Android 15+: limite de tempo do serviço atingido (6 h) — cancelar em vez de ser terminado à força. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        ConversionManager.cancel()
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun run(job: ConversionManager.Job) {
        val list = job.tracks
        val converter = AudioConverter(applicationContext, TagRepository(applicationContext))
        // Um ficheiro por núcleo livre, no máximo 4 (acima disso aquece e já não ganha nos núcleos "pequenos").
        val workers = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4).coerceAtMost(list.size)
        val pool = Executors.newFixedThreadPool(workers)
        val session = AudioConverter.Session()
        val created = Collections.synchronizedList(mutableListOf<Track>())
        val errors = Collections.synchronizedList(mutableListOf<String>())
        val warnings = Collections.synchronizedList(mutableListOf<String>())
        val perFile = AtomicIntegerArray(list.size)
        val done = AtomicInteger(0)
        val lastUi = AtomicLong(0)
        val lastNotif = AtomicLong(0)
        val cancelled = AtomicBoolean(false)
        val flag = ConversionManager.cancelFlag

        fun report(force: Boolean = false) {
            val now = System.currentTimeMillis()
            val prev = lastUi.get()
            if (!force && (now - prev < 250 || !lastUi.compareAndSet(prev, now))) return
            if (force) lastUi.set(now)
            var total = 0
            for (k in 0 until list.size) total += perFile.get(k)
            val d = done.get()
            ConversionManager.publish(ConversionManager.Progress(d, list.size, total, workers, flag.get()))
            // O Android limita actualizações de notificações; 1 por segundo chega.
            val pn = lastNotif.get()
            if (force || now - pn >= 1000) {
                lastNotif.set(now)
                nm.notify(ID_PROGRESS, progressNotification(d, list.size, total, workers, flag.get()))
            }
        }

        val futures = list.mapIndexed { i, t ->
            pool.submit(Runnable {
                if (flag.get()) { cancelled.set(true); return@Runnable }
                try {
                    val r = converter.convert(t, job.quality, session,
                        progress = { f -> perFile.set(i, (f * 1000).toInt()); report() },
                        cancelled = { flag.get() })
                    created += r.track
                    warnings += r.warnings.map { "${t.name}: $it" }
                } catch (e: CancellationException) {
                    cancelled.set(true)
                } catch (e: Throwable) {
                    Log.e("MiniTag", "Erro a converter ${t.path}", e)
                    errors += "${t.name}: ${e.javaClass.simpleName}: ${e.message ?: "(sem mensagem)"}"
                } finally {
                    perFile.set(i, 1000)
                    done.incrementAndGet()
                    report(force = true)
                }
            })
        }
        futures.forEach { runCatching { it.get() } }
        pool.shutdown()
        if (flag.get()) cancelled.set(true)

        val lines = errors.map { "✗ $it" } + warnings.map { "⚠ $it" } +
            (if (job.skipped > 0) listOf("${job.skipped} ficheiro(s) ignorados (formato não suportado)") else emptyList())
        val outcome = ConversionManager.Outcome(created.toList(), list.size, cancelled.get(), lines, job.quality)

        // Se ninguém estiver a ver a app, deixa uma notificação com o resultado.
        if (!ConversionManager.hasListeners) nm.notify(ID_DONE, doneNotification(outcome))
        ConversionManager.finish(outcome)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------- Notificações ----------

    private fun createChannels() {
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_PROGRESS, "Conversão em curso", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Progresso da conversão para MP3"; setShowBadge(false) })
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_DONE, "Conversão terminada", NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "Aviso quando a conversão para MP3 termina" })
    }

    private fun openAppIntent() = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun progressNotification(done: Int, total: Int, permille: Int, workers: Int, cancelling: Boolean): Notification {
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, ConversionService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_convert)
            .setContentTitle(if (cancelling) "A cancelar a conversão…" else "A converter para MP3")
            .setContentText("$done de $total ficheiros" + if (workers > 1) " · $workers em simultâneo" else "")
            .setProgress(total * 1000, permille, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openAppIntent())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply { if (!cancelling) addAction(0, "Cancelar", cancel) }
            .build()
    }

    private fun doneNotification(o: ConversionManager.Outcome): Notification =
        NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_convert)
            .setContentTitle("Conversão terminada")
            .setContentText(o.title + if (o.lines.isNotEmpty()) " · toque para ver detalhes" else "")
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
}
