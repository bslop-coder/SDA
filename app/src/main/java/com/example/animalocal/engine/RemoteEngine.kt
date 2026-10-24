package com.example.animalocal.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import java.io.File

/** Client side of EngineService. Cancel = kill the engine process; the next job respawns it. */
class RemoteEngine(private val ctx: Context) : ImageEngine {
    private var service: Messenger? = null
    private var waiter: CompletableDeferred<Messenger>? = null
    private var active: CompletableDeferred<File>? = null
    private var onEvent: ((EngineEvent) -> Unit)? = null
    private var cancelled = false

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val m = Messenger(binder)
            service = m
            waiter?.complete(m)
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            val a = active
            if (a != null) {
                a.completeExceptionally(
                    if (cancelled) EngineCancelled()
                    else IllegalStateException("Engine process died (probably out of memory). Try offload mode Staged, a smaller size, or Q4 weights.")
                )
            }
        }
    }

    private val reply = Messenger(Handler(Looper.getMainLooper(), Handler.Callback { m: Message ->
        when (m.what) {
            Proto.PROGRESS -> onEvent?.invoke(
                EngineEvent.Progress(m.arg1, m.arg2, m.data.getFloat("secs"), m.data.getInt("rss"), m.data.getInt("peak"))
            )
            Proto.LOG -> onEvent?.invoke(EngineEvent.Log(m.data.getString("t", "")))
            Proto.DONE -> active?.complete(File(m.data.getString("t", "")))
            Proto.ERROR -> active?.completeExceptionally(IllegalStateException(m.data.getString("t", "error")))
        }
        true
    }))

    private suspend fun ensureService(): Messenger {
        service?.let { return it }
        val i = Intent(ctx, EngineService::class.java)
        ContextCompat.startForegroundService(ctx, i)
        val w = CompletableDeferred<Messenger>()
        waiter = w
        ctx.bindService(i, conn, Context.BIND_AUTO_CREATE)
        return w.await()
    }

    private fun release() {
        try { ctx.unbindService(conn) } catch (_: Exception) { }
        try { ctx.stopService(Intent(ctx, EngineService::class.java)) } catch (_: Exception) { }
        service = null
    }

    override suspend fun generate(req: GenerationRequest, onEvent: (EngineEvent) -> Unit): File {
        cancelled = false
        this.onEvent = onEvent
        val d = CompletableDeferred<File>()
        active = d
        try {
            val svc = ensureService()
            svc.send(Message.obtain(null, Proto.GENERATE).apply { data = req.toBundle(); replyTo = reply })
            return d.await()
        } finally {
            active = null
            release()
        }
    }

    override fun cancel() {
        cancelled = true
        try { service?.send(Message.obtain(null, Proto.CANCEL)) } catch (_: Exception) { }
    }
}
