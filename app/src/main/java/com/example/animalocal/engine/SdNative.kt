package com.example.animalocal.engine

interface NativeListener {
    fun onProgress(step: Int, steps: Int, secs: Float)
    fun onLog(level: Int, text: String)
    fun onImage(width: Int, height: Int, rgb: ByteArray)
}

/** Raw JNI surface. Only EngineService (":engine" process) may call this. */
object SdNative {
    init { System.loadLibrary("animabridge") }

    external fun generate(
        diffusion: String, llm: String, vae: String,
        prompt: String, negative: String, sampler: String,
        width: Int, height: Int, steps: Int, cfg: Float, seed: Long,
        vaeTiling: Boolean, flashAttn: Boolean,
        threads: Int, affinityMask: Int, offloadMode: Int, backend: String,
        loraPaths: Array<String>, loraStrengths: FloatArray,
        listener: NativeListener,
    ): Int
}
