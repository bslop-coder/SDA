package com.example.animalocal.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import java.io.File
import java.util.concurrent.Executors

/**
 * Runs in the ":engine" process. One job per process lifetime: after a job the process kills itself,
 * so every image starts from a clean native heap. CANCEL also kills the process (sd.cpp has no abort).
 */
class EngineService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var replyTo: Messenger? = null

    private val messenger = Messenger(Handler(Looper.getMainLooper()) { m ->
        when (m.what) {
            Proto.GENERATE -> {
                replyTo = m.replyTo
                val req = GenerationRequest.fromBundle(m.data)
                worker.execute { runJob(req) }
            }
            Proto.CANCEL -> Process.killProcess(Process.myPid())
        }
        true
    })

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("engine", "Image generation", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "engine")
            .setContentTitle("Generating image")
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        return START_NOT_STICKY
    }

    private fun mem(key: String): Int = try {
        File("/proc/self/status").readLines().first { it.startsWith(key) }.filter { it.isDigit() }.toInt() / 1024
    } catch (e: Exception) { -1 }

    private fun send(what: Int, a1: Int = 0, a2: Int = 0, data: Bundle? = null) {
        try {
            val msg = Message.obtain(null, what, a1, a2)
            if (data != null) msg.data = data
            replyTo?.send(msg)
        } catch (_: Exception) { }
    }

    private fun msgText(s: String) = Bundle().apply { putString("t", s) }

    private fun runJob(req: GenerationRequest) {
        var out: File? = null
        val listener = object : NativeListener {
            override fun onProgress(step: Int, steps: Int, secs: Float) {
                send(Proto.PROGRESS, step, steps, Bundle().apply {
                    putFloat("secs", secs); putInt("rss", mem("VmRSS")); putInt("peak", mem("VmHWM"))
                })
            }
            override fun onLog(level: Int, text: String) {
                var t = text.trim()
                // Memory evidence at stage boundaries, to verify staged offload really frees weights.
                if (listOf("load", "complet", "generat", "decod", "free").any { t.contains(it, true) })
                    t += "  [RSS ${mem("VmRSS")} MB, peak ${mem("VmHWM")} MB]"
                send(Proto.LOG, data = msgText(t))
            }
            override fun onImage(width: Int, height: Int, rgb: ByteArray) {
                val px = IntArray(width * height)
                for (i in px.indices) {
                    val r = rgb[i * 3].toInt() and 0xFF
                    val g = rgb[i * 3 + 1].toInt() and 0xFF
                    val b = rgb[i * 3 + 2].toInt() and 0xFF
                    px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
                val bmp = Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888)
                val f = File(cacheDir, "out_${System.currentTimeMillis()}.png")
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                out = f
            }
        }
        try {
            send(Proto.LOG, data = msgText("engine: starting (offload mode ${req.offloadMode}, ${req.threads} threads, affinity 0x${req.affinityMask.toString(16)})"))
            val rc = SdNative.generate(
                req.diffusionPath, req.llmPath, req.vaePath,
                req.prompt, req.negative, req.sampler,
                req.width, req.height, req.steps, req.cfg, req.seed,
                req.vaeTiling, req.flashAttn,
                req.threads, req.affinityMask, req.offloadMode,
                req.loras.map { it.path }.toTypedArray(), req.loras.map { it.strength }.toFloatArray(),
                listener,
            )
            val f = out
            if (rc == 0 && f != null) send(Proto.DONE, data = msgText(f.path))
            else send(Proto.ERROR, data = msgText("native generate failed (code $rc; 1 = model load failed, 2 = generation failed). See log."))
        } catch (t: Throwable) {
            send(Proto.ERROR, data = msgText(t.toString()))
        }
        main.post { stopForeground(STOP_FOREGROUND_REMOVE) }
        main.postDelayed({ Process.killProcess(Process.myPid()) }, 1500)
    }
}
