package com.example.animalocal.engine

import java.io.File

sealed interface EngineEvent {
    data class Progress(val step: Int, val steps: Int, val secs: Float, val rssMb: Int, val peakMb: Int) : EngineEvent
    data class Log(val text: String) : EngineEvent
}

class EngineCancelled : RuntimeException("cancelled")

/** The UI talks only to this. Swap the implementation (other engine, other process model) without touching UI. */
interface ImageEngine {
    /** Returns a PNG file. Events arrive on the main thread. */
    suspend fun generate(req: GenerationRequest, onEvent: (EngineEvent) -> Unit): File
    fun cancel()
}

object Proto {
    const val GENERATE = 1
    const val CANCEL = 2
    const val PROGRESS = 3
    const val LOG = 4
    const val DONE = 5
    const val ERROR = 6
}
